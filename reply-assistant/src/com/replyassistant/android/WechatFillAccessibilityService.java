package com.replyassistant.android;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** One-shot WeChat text insertion. It never reads node text or activates a send control. */
public class WechatFillAccessibilityService extends AccessibilityService {
    public static final String PREFS = "wechat_fill_flow";
    public static final String KEY_LAST_RESULT = "last_result";
    public static final String RESULT_SUCCESS = "已将文字写入微信输入框；请检查后手动发送。";
    public static final String RESULT_FAILED = "未能写入微信输入框，请使用复制兜底。";
    public static final String RESULT_CANCELLED = "已取消回填；可以改用复制。";
    public static final String RESULT_EXPIRED = "回填请求已超时取消；可以重新发起或复制。";
    public static final String RESULT_COPIED = "微信未提供可识别的输入框；已复制文字，请在目标聊天中手动粘贴并检查后发送。";
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final String TAG = "ReplyFillA11y";
    private static final long PENDING_TTL_MS = 2 * 60 * 1000L;
    private static final long CONFIRM_TTL_MS = 20 * 1000L;
    private static volatile String pendingText;
    private static volatile long pendingExpiresAt;
    private static volatile Handler activeHandler;
    private static final Runnable expirePending = () -> {
        if (pendingText != null && SystemClock.elapsedRealtime() >= pendingExpiresAt) {
            Log.i(TAG, "request_expired_timer");
            clearOneShot();
        }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View confirmationView;
    private boolean copyFallbackVisible;
    private String candidateViewId;
    private CharSequence candidateClass;
    private int candidateWindowId;
    private final Runnable expireConfirmation = () -> {
        if (copyFallbackVisible) dismissCopyFallback();
        else finishConfirmation(false);
    };

    public static void armOneShot(String text) {
        pendingText = text;
        pendingExpiresAt = SystemClock.elapsedRealtime() + PENDING_TTL_MS;
        Log.i(TAG, "request_armed ttl_ms=" + PENDING_TTL_MS);
        Handler currentHandler = activeHandler;
        if (currentHandler != null) {
            currentHandler.removeCallbacks(expirePending);
            currentHandler.postDelayed(expirePending, PENDING_TTL_MS);
        }
    }

    public static void clearOneShot() {
        pendingText = null;
        pendingExpiresAt = 0L;
        Handler currentHandler = activeHandler;
        if (currentHandler != null) currentHandler.removeCallbacks(expirePending);
    }

    public static boolean isOneShotPending() {
        if (pendingText == null) return false;
        if (SystemClock.elapsedRealtime() >= pendingExpiresAt) clearOneShot();
        return pendingText != null;
    }

    public static boolean isEnabled(Context context) {
        android.view.accessibility.AccessibilityManager manager =
                (android.view.accessibility.AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (manager == null) return false;
        String expected = new android.content.ComponentName(context, WechatFillAccessibilityService.class).flattenToString();
        for (android.accessibilityservice.AccessibilityServiceInfo service :
                manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (service.getResolveInfo() != null && service.getResolveInfo().serviceInfo != null) {
                android.content.pm.ServiceInfo info = service.getResolveInfo().serviceInfo;
                if (new android.content.ComponentName(info.packageName, info.name).flattenToString().equals(expected)) return true;
            }
        }
        return false;
    }

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        activeHandler = handler;
        Log.i(TAG, "service_connected");
    }

    @Override public boolean onUnbind(Intent intent) {
        Log.w(TAG, "service_unbound");
        activeHandler = null;
        return super.onUnbind(intent);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !WECHAT_PACKAGE.contentEquals(event.getPackageName())) return;
        String text = pendingText;
        if (text == null) return;
        if (SystemClock.elapsedRealtime() >= pendingExpiresAt) {
            Log.i(TAG, "request_expired_before_event");
            clearOneShot();
            saveResult(RESULT_EXPIRED);
            Toast.makeText(this, RESULT_EXPIRED, Toast.LENGTH_LONG).show();
            return;
        }
        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED
                && eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return;
        AccessibilityNodeInfo source = event.getSource();
        logNode("event_source type=" + eventType, source);
        if (isEligibleInput(source)) {
            showConfirmation(source);
        } else if (confirmationView == null) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            logNode("active_root", root);
            AccessibilityNodeInfo focused = null;
            if (root != null && WECHAT_PACKAGE.contentEquals(root.getPackageName())) {
                focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            }
            logNode("active_focus", focused);
            if (isEligibleInput(focused)) showConfirmation(focused);
            else if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
                    && (source == null || source.getClassName() == null)
                    && (root == null || root.getClassName() == null)) showCopyFallback();
            if (focused != null) focused.recycle();
            if (root != null) root.recycle();
        }
        if (source != null) source.recycle();
    }

    private void logNode(String label, AccessibilityNodeInfo node) {
        if (node == null) {
            Log.i(TAG, label + " null");
            return;
        }
        Log.i(TAG, label + " class=" + node.getClassName()
                + " viewId=" + node.getViewIdResourceName()
                + " packageIsWechat=" + WECHAT_PACKAGE.contentEquals(node.getPackageName())
                + " editable=" + node.isEditable()
                + " enabled=" + node.isEnabled()
                + " focused=" + node.isFocused()
                + " window=" + node.getWindowId());
    }

    private boolean isEligibleInput(AccessibilityNodeInfo node) {
        return node != null
                && WECHAT_PACKAGE.contentEquals(node.getPackageName())
                && node.isEditable()
                && node.isEnabled()
                && node.isFocused();
    }

    private void showConfirmation(AccessibilityNodeInfo node) {
        if (confirmationView != null || windowManager == null || pendingText == null) return;
        copyFallbackVisible = false;
        candidateViewId = node.getViewIdResourceName();
        candidateClass = node.getClassName();
        candidateWindowId = node.getWindowId();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), Color.rgb(203, 213, 225));
        card.setBackground(background);
        card.setElevation(dp(8));

        TextView message = new TextView(this);
        message.setText("当前微信文本框已聚焦。请确认这是聊天消息输入框；如果是搜索框，请取消。写入后仍需你检查并手动发送。");
        message.setTextColor(Color.rgb(15, 23, 42));
        message.setTextSize(15);
        card.addView(message);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setAllCaps(false);
        cancel.setOnClickListener(view -> finishConfirmation(false));
        actions.addView(cancel);
        Button write = new Button(this);
        write.setText("确认写入");
        write.setAllCaps(false);
        write.setOnClickListener(view -> finishConfirmation(true));
        actions.addView(write);
        card.addView(actions);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(48);
        try {
            windowManager.addView(card, params);
            confirmationView = card;
            Log.i(TAG, "confirmation_shown");
            handler.removeCallbacks(expireConfirmation);
            handler.postDelayed(expireConfirmation, CONFIRM_TTL_MS);
        } catch (RuntimeException error) {
            Log.w(TAG, "confirmation_overlay_failed " + error.getClass().getSimpleName());
            clearOneShot();
            releaseCandidate();
            saveResult(RESULT_FAILED);
        }
    }

    private void showCopyFallback() {
        if (confirmationView != null || windowManager == null || pendingText == null) return;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), Color.rgb(203, 213, 225));
        card.setBackground(background);
        card.setElevation(dp(8));

        TextView message = new TextView(this);
        message.setText("微信没有提供可识别的输入框，因此不能安全写入。如果刚才点的是聊天消息框，可复制文字后在微信中手动粘贴；否则点“继续等待”。不会自动发送。");
        message.setTextColor(Color.rgb(15, 23, 42));
        message.setTextSize(15);
        card.addView(message);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button wait = new Button(this);
        wait.setText("继续等待");
        wait.setAllCaps(false);
        wait.setOnClickListener(view -> dismissCopyFallback());
        actions.addView(wait);
        Button copy = new Button(this);
        copy.setText("复制后手动粘贴");
        copy.setAllCaps(false);
        copy.setOnClickListener(view -> copyFallback());
        actions.addView(copy);
        card.addView(actions);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(48);
        try {
            windowManager.addView(card, params);
            confirmationView = card;
            copyFallbackVisible = true;
            Log.i(TAG, "copy_fallback_shown");
            handler.removeCallbacks(expireConfirmation);
            handler.postDelayed(expireConfirmation, CONFIRM_TTL_MS);
        } catch (RuntimeException error) {
            Log.w(TAG, "copy_fallback_overlay_failed " + error.getClass().getSimpleName());
        }
    }

    private void dismissCopyFallback() {
        handler.removeCallbacks(expireConfirmation);
        removeConfirmationView();
        copyFallbackVisible = false;
    }

    private void copyFallback() {
        String text = pendingText;
        boolean withinWindow = text != null && SystemClock.elapsedRealtime() < pendingExpiresAt;
        dismissCopyFallback();
        clearOneShot();
        String result = RESULT_EXPIRED;
        if (withinWindow) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("微信表达助手建议", text));
                result = RESULT_COPIED;
                Log.i(TAG, "copy_fallback_selected");
            } else result = RESULT_FAILED;
        }
        saveResult(result);
        Toast.makeText(this, result, Toast.LENGTH_LONG).show();
    }

    private void finishConfirmation(boolean writeText) {
        handler.removeCallbacks(expireConfirmation);
        removeConfirmationView();
        if (!writeText) {
            clearOneShot();
            saveResult(RESULT_CANCELLED);
            return;
        }

        String text = pendingText;
        boolean withinWindow = text != null && SystemClock.elapsedRealtime() < pendingExpiresAt;
        clearOneShot(); // Consume before acting so events/re-entry cannot duplicate a write.
        AccessibilityNodeInfo focused = null;
        boolean wrote = false;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && WECHAT_PACKAGE.contentEquals(root.getPackageName())) {
                focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            }
            boolean sameField = focused != null
                    && isEligibleInput(focused)
                    && focused.getWindowId() == candidateWindowId
                    && same(candidateClass, focused.getClassName())
                    && same(candidateViewId, focused.getViewIdResourceName());
            if (withinWindow && sameField) {
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                wrote = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            }
        } catch (RuntimeException ignored) {
            wrote = false;
        } finally {
            if (focused != null) focused.recycle();
            releaseCandidate();
        }
        String result = wrote ? RESULT_SUCCESS : RESULT_FAILED;
        saveResult(result);
        Toast.makeText(this, result, Toast.LENGTH_LONG).show();
    }

    private boolean same(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private void saveResult(String result) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_RESULT, result).apply();
    }

    private void removeConfirmationView() {
        if (confirmationView != null && windowManager != null) {
            try { windowManager.removeView(confirmationView); } catch (RuntimeException ignored) { }
        }
        confirmationView = null;
    }

    private void releaseCandidate() {
        candidateViewId = null;
        candidateClass = null;
        candidateWindowId = -1;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override public void onInterrupt() {
        removeConfirmationView();
        releaseCandidate();
        clearOneShot();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(expireConfirmation);
        removeConfirmationView();
        releaseCandidate();
        clearOneShot();
        if (activeHandler == handler) activeHandler = null;
        super.onDestroy();
    }
}
