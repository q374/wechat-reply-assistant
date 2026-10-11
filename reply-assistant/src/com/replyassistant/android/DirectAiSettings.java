package com.replyassistant.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Settings for the personal, on-device DeepSeek connection. Never logs or backs up the API key. */
final class DirectAiSettings {
    private static final String PREFS = "direct_ai_settings";
    private static final String ALIAS = "reply_assistant_personal_api_key";
    private static final String FILE = "personal_api_key.bin";
    static final long BUDGET_MICROYUAN = 1_000_000L;
    private static final String SPENT = "estimated_spend_micros";
    private static final String ENABLED = "direct_enabled";
    private static final String CONSENT = "direct_privacy_consent";

    private DirectAiSettings() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context context) { return prefs(context).getBoolean(ENABLED, false); }
    static void setEnabled(Context context, boolean enabled) { prefs(context).edit().putBoolean(ENABLED, enabled).apply(); }
    static boolean hasConsent(Context context) { return prefs(context).getBoolean(CONSENT, false); }
    static void setConsent(Context context) { prefs(context).edit().putBoolean(CONSENT, true).apply(); }
    static long estimatedSpend(Context context) { return prefs(context).getLong(SPENT, 0L); }
    static boolean hasKey(Context context) { return keyFile(context).getBaseFile().isFile(); }

    private static AtomicFile keyFile(Context context) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), FILE));
    }

    private static SecretKey encryptionKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        KeyStore.Entry existing = store.getEntry(ALIAS, null);
        if (existing instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) existing).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }

    static void saveKey(Context context, String apiKey) throws Exception {
        String trimmed = apiKey.trim();
        if (trimmed.isEmpty() || trimmed.length() > 512 || trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("密钥格式无效");
        }
        byte[] plain = trimmed.getBytes("UTF-8");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), new SecureRandom());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(plain);
            AtomicFile file = keyFile(context);
            java.io.FileOutputStream output = file.startWrite();
            try {
                DataOutputStream data = new DataOutputStream(output);
                data.writeByte(1);
                data.writeByte(iv.length);
                data.write(iv);
                data.writeInt(encrypted.length);
                data.write(encrypted);
                data.flush();
                file.finishWrite(output);
            } catch (Exception error) {
                file.failWrite(output);
                throw error;
            }
            prefs(context).edit().putBoolean(ENABLED, true).apply();
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    static String readKey(Context context) throws Exception {
        if (!hasKey(context)) throw new IllegalStateException("请先在设置中保存 DeepSeek API Key");
        byte[] raw;
        try (java.io.InputStream input = keyFile(context).openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int count;
            while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
            raw = out.toByteArray();
        }
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(raw))) {
            if (data.readUnsignedByte() != 1) throw new IOException("密钥存储版本无效");
            int ivSize = data.readUnsignedByte();
            if (ivSize != 12) throw new IOException("密钥存储格式无效");
            byte[] iv = new byte[ivSize]; data.readFully(iv);
            int size = data.readInt();
            if (size <= 0 || size > 2048 || data.available() != size) throw new IOException("密钥存储长度无效");
            byte[] encrypted = new byte[size]; data.readFully(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(encrypted);
            try { return new String(plain, "UTF-8"); }
            finally { Arrays.fill(plain, (byte) 0); }
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    static void removeKey(Context context) {
        keyFile(context).delete();
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            store.deleteEntry(ALIAS);
        } catch (Exception ignored) {
            // The encrypted file is gone; any stale Keystore entry cannot authorize a request.
        }
    }

    static synchronized void recordUsage(Context context, long promptTokens, long completionTokens) throws IOException {
        if (promptTokens < 0 || completionTokens < 0) throw new IOException("AI 用量无效");
        // Conservative 2026-10-09 flash peak estimate in CNY: all input as cache misses.
        long micros;
        try {
            micros = Math.addExact(Math.multiplyExact(promptTokens, 2L),
                    Math.multiplyExact(completionTokens, 8L));
        } catch (ArithmeticException error) {
            throw new IOException("AI 用量过大，无法继续估算", error);
        }
        long previous = estimatedSpend(context);
        if (micros < 0 || previous > Long.MAX_VALUE - micros
                || !prefs(context).edit().putLong(SPENT, previous + micros).commit()) {
            throw new IOException("无法保存本机试用用量，请暂停生成");
        }
    }

    static boolean nearBudget(Context context, int promptCharacters, int maxOutputTokens) {
        long reserved = Math.max(0L, promptCharacters * 2L) * 2L + maxOutputTokens * 8L;
        return estimatedSpend(context) >= BUDGET_MICROYUAN - reserved;
    }

    static String budgetLabel(Context context) {
        return String.format(java.util.Locale.CHINA, "本机保守估算约 ¥%.4f / ¥1；不是平台账单上限",
                estimatedSpend(context) / 1_000_000.0);
    }
}
