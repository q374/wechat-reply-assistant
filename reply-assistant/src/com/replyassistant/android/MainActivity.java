package com.replyassistant.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int PAPER = Color.rgb(246, 239, 226);
    private static final int SURFACE = Color.rgb(251, 248, 240);
    private static final int SAGE = Color.rgb(227, 238, 229);
    private static final int INK = Color.rgb(32, 50, 41);
    private static final int MUTED = Color.rgb(109, 118, 109);
    private static final int OUTLINE = Color.rgb(217, 209, 192);
    private static final int FOREST = Color.rgb(47, 120, 91);
    private static final int TERRACOTTA = Color.rgb(179, 78, 50);
    private LinearLayout content;
    private TextView status;
    private TextView connectionStatus;
    private TextView directSettingsStatus;
    private TextView currentProfileLabel;
    private EditText directKeyInput;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        buildScreen();
        if (getIntent().getBooleanExtra("show_connection_settings", false)) {
            getWindow().getDecorView().post(this::showSettingsSheet);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refreshCurrentProfileLabel();
        showConnectionStatus();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && connectionStatus != null) {
            refreshCurrentProfileLabel();
            showConnectionStatus();
        }
    }

    private void buildScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(PAPER);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(20), dp(28), dp(20), dp(32));
        scroll.addView(content); root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout appBar = new LinearLayout(this); appBar.setGravity(Gravity.CENTER_VERTICAL); appBar.setPadding(0, dp(8), 0, dp(12));
        LinearLayout brand = new LinearLayout(this); brand.setOrientation(LinearLayout.VERTICAL); brand.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        TextView brandTitle = label("微信表达助手"); brandTitle.setTextSize(17); brandTitle.setTypeface(null, 1); brand.addView(brandTitle);
        TextView brandSub = label("今天先回这一条"); brandSub.setTextSize(12); brandSub.setTextColor(MUTED); brand.addView(brandSub); appBar.addView(brand);
        Button settings = button("设置", Color.TRANSPARENT, FOREST); settings.setBackgroundColor(Color.TRANSPARENT); settings.setMinHeight(dp(44)); settings.setOnClickListener(v -> showSettingsSheet()); appBar.addView(settings, new LinearLayout.LayoutParams(dp(72), dp(48))); content.addView(appBar);

        TextView eyebrow = label("回复工作台"); eyebrow.setTextColor(TERRACOTTA); eyebrow.setTextSize(12); eyebrow.setTypeface(null, 1); content.addView(eyebrow);
        TextView title = label("自然一点，\n就能聊下去。"); title.setTextSize(29); title.setTextColor(INK); title.setTypeface(null, 1); title.setPadding(0, dp(10), 0, dp(10)); content.addView(title);
        TextView intro = label("你掌握最后决定权。助手只负责把话整理成几种可选说法。"); intro.setTextColor(MUTED); intro.setTextSize(14); intro.setLineSpacing(dp(3), 1f); content.addView(intro);
        View accent = new View(this); accent.setBackground(round(TERRACOTTA, TERRACOTTA)); LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(dp(46), dp(3)); accentParams.topMargin = dp(14); accentParams.bottomMargin = dp(4); content.addView(accent, accentParams);

        addSection("当前对象");
        currentProfileLabel = label(""); currentProfileLabel.setTextColor(INK); currentProfileLabel.setTextSize(16); currentProfileLabel.setBackground(round(SURFACE, OUTLINE)); currentProfileLabel.setPadding(dp(16),dp(14),dp(16),dp(14)); currentProfileLabel.setOnClickListener(v -> showProfileSheet()); content.addView(currentProfileLabel);
        Button startOverlay = button("打开微信文字助手", TERRACOTTA, Color.WHITE); startOverlay.setTextSize(16); startOverlay.setMinHeight(dp(52)); startOverlay.setOnClickListener(v -> startTextOverlay()); content.addView(startOverlay);

        connectionStatus = label(""); connectionStatus.setTextColor(FOREST); connectionStatus.setTextSize(12); connectionStatus.setGravity(Gravity.CENTER_VERTICAL); connectionStatus.setPadding(dp(12), dp(10), dp(12), dp(10)); connectionStatus.setMinHeight(dp(48)); connectionStatus.setBackground(round(SAGE, OUTLINE)); connectionStatus.setOnClickListener(v -> showSettingsSheet()); connectionStatus.setContentDescription("手机直连状态，点击打开设置"); content.addView(connectionStatus); showConnectionStatus();

        TextView paused = label("语音整理已暂停，本版本专注文字回复。"); paused.setTextColor(MUTED); paused.setTextSize(12); paused.setPadding(0, dp(18), 0, dp(2)); content.addView(paused);

        status = label("生成结果不会自动发送；复制后请回微信检查并手动发送。"); status.setTextColor(MUTED); status.setTextSize(12); status.setPadding(0, dp(20), 0, 0); content.addView(status);

        setContentView(root);
    }

    private Button toolRow(String title, String hint, String arrow) {
        Button row = button(title + "\n" + hint + "                                      " + arrow, Color.TRANSPARENT, INK);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT); row.setBackground(round(SURFACE, OUTLINE));
        row.setTextSize(14);
        row.setPadding(dp(2), dp(10), dp(2), dp(10));
        row.setMinHeight(dp(64));
        return row;
    }

    private LinearLayout sheetContainer() {
        LinearLayout sheet = new LinearLayout(this); sheet.setOrientation(LinearLayout.VERTICAL); sheet.setPadding(dp(20), dp(10), dp(20), dp(22)); sheet.setBackground(round(PAPER, PAPER)); return sheet;
    }

    private void showSheet(Dialog dialog, View sheet) {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE); dialog.setContentView(sheet); dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) { window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); WindowManager.LayoutParams params = window.getAttributes(); params.gravity = Gravity.BOTTOM; params.width = WindowManager.LayoutParams.MATCH_PARENT; params.height = WindowManager.LayoutParams.WRAP_CONTENT; params.dimAmount = 0.34f; window.setAttributes(params); window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT); }
        }); dialog.show();
    }

    private View sheetHandle(LinearLayout sheet) {
        FrameLayout slot = new FrameLayout(this);
        View handle = new View(this);
        handle.setBackground(round(Color.rgb(170, 151, 128), Color.rgb(170, 151, 128)));
        FrameLayout.LayoutParams handleParams = new FrameLayout.LayoutParams(dp(32), dp(4), Gravity.CENTER);
        slot.addView(handle, handleParams);
        sheet.addView(slot, new LinearLayout.LayoutParams(-1, dp(28)));
        return slot;
    }

    private LinearLayout sheetHeader(LinearLayout sheet, String title, View.OnClickListener close) {
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); TextView name = label(title); name.setTextSize(20); name.setTypeface(null,1); header.addView(name, new LinearLayout.LayoutParams(0, -2, 1)); Button closeButton = button("关闭", Color.TRANSPARENT, FOREST); closeButton.setContentDescription("关闭" + title); closeButton.setOnClickListener(close); header.addView(closeButton, new LinearLayout.LayoutParams(dp(72), dp(48))); sheet.addView(header); return header;
    }

    private void showSettingsSheet() {
        Dialog dialog = new Dialog(this); LinearLayout sheet = sheetContainer(); sheetHandle(sheet); sheetHeader(sheet, "设置", v -> dialog.dismiss());
        TextView intro = label("手机联网即可生成文字候选；微信内入口会在你主动开启后显示。"); intro.setTextColor(MUTED); intro.setTextSize(13); intro.setPadding(0, dp(4), 0, dp(12)); sheet.addView(intro);
        TextView directTitle = label("手机直连 DeepSeek"); directTitle.setTypeface(null, 1); directTitle.setTextSize(15); sheet.addView(directTitle);
        TextView directNote = label("手机联网即可生成文字候选。API Key 只在本机加密保存；1 元为 App 内试用预算，不是 DeepSeek 扣费硬上限。使用专用 Key，发现异常请到 DeepSeek 平台撤销。"); directNote.setTextColor(MUTED); directNote.setTextSize(12); directNote.setPadding(0, dp(4), 0, dp(6)); sheet.addView(directNote);
        directKeyInput = field("粘贴 DeepSeek API Key；已保存的 Key 不会显示", false);
        directKeyInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        directKeyInput.setSingleLine(true); directKeyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); sheet.addView(directKeyInput);
        Button saveDirect = button("保存并启用手机直连", FOREST, Color.WHITE);
        saveDirect.setOnClickListener(v -> {
            try {
                DirectAiSettings.saveKey(this, directKeyInput.getText().toString());
                directKeyInput.setText("");
                showConnectionStatus();
                directSettingsStatus.setText("Key 已加密保存在本机，尚未进行真实连接测试。");
            } catch (Exception error) { directSettingsStatus.setText("保存失败，请检查密钥格式或手机安全存储状态。"); }
        }); sheet.addView(saveDirect);
        Button resumeDirect = button("启用已保存的手机直连", Color.TRANSPARENT, FOREST);
        resumeDirect.setOnClickListener(v -> {
            if (!DirectAiSettings.hasKey(this)) { directSettingsStatus.setText("本机没有已保存的 Key，请先输入并保存。"); return; }
            DirectAiSettings.setEnabled(this, true); showConnectionStatus();
        }); sheet.addView(resumeDirect);
        Button removeDirect = button("移除本机 Key", Color.TRANSPARENT, TERRACOTTA);
        removeDirect.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("移除本机 API Key？")
                .setMessage("将删除手机上保存的密钥，文字助手会暂停生成，直到重新设置 Key。DeepSeek 平台上的 Key 不会因此失效。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (ignored, which) -> {
                    DirectAiSettings.removeKey(this); directKeyInput.setText(""); showConnectionStatus();
                    directSettingsStatus.setText("本机 Key 已移除；如需撤销，请到 DeepSeek 平台操作。");
                }).show()); sheet.addView(removeDirect);
        directSettingsStatus = label(""); directSettingsStatus.setTextColor(MUTED); directSettingsStatus.setTextSize(12); directSettingsStatus.setPadding(0, dp(4), 0, dp(10)); sheet.addView(directSettingsStatus);
        TextView note = label("文字助手不会读取、写入或发送微信内容；复制后请回微信手动检查。语音整理已暂停。"); note.setTextColor(MUTED); note.setTextSize(12); note.setPadding(0, dp(8), 0, 0); sheet.addView(note);
        ScrollView scroll = new ScrollView(this); scroll.addView(sheet); showSheet(dialog, scroll);
    }

    private void showProfileSheet() {
        Dialog dialog = new Dialog(this); LinearLayout sheet = sheetContainer(); sheetHandle(sheet); sheetHeader(sheet, "对象档案", v -> dialog.dismiss()); TextView intro = label("对象档案只保存在本机。先选择对象，再编辑详细设定。"); intro.setTextColor(MUTED); intro.setTextSize(13); intro.setPadding(0, dp(4), 0, dp(12)); sheet.addView(intro);
        String current = getSharedPreferences("chat_profiles", MODE_PRIVATE).getString("current_profile_name", "").trim();
        for (String name : profileNames()) { Button row = toolRow(name, name.equals(current) ? "当前对象 · 档案已保存" : "点击切换当前对象", name.equals(current) ? "✓" : "›"); row.setOnClickListener(v -> { setCurrentProfile(name); dialog.dismiss(); }); sheet.addView(row); }
        Button create = toolRow("新建对象", "创建新的对象档案", "›"); create.setOnClickListener(v -> { dialog.dismiss(); showProfileEditorSheet(""); }); sheet.addView(create);
        if (!current.isEmpty()) { Button edit = toolRow("编辑当前档案", "身份、偏好、关系阶段、本地记忆", "›"); edit.setOnClickListener(v -> { dialog.dismiss(); showProfileEditorSheet(current); }); sheet.addView(edit); }
        showSheet(dialog, sheet);
    }

    private void showProfileEditorSheet(String selectedName) {
        Dialog dialog = new Dialog(this); ScrollView scroll = new ScrollView(this); LinearLayout sheet = sheetContainer(); scroll.addView(sheet); sheetHandle(sheet); sheetHeader(sheet, selectedName.isEmpty() ? "新建对象" : "编辑对象档案", v -> dialog.dismiss());
        addFieldLabel(sheet, "对象名称"); EditText name = field("例如：小李", false); name.setText(selectedName); sheet.addView(name);
        addFieldLabel(sheet, "你的真实身份设定（可选）"); EditText identity = field("你希望助手如何理解你", true); sheet.addView(identity);
        addFieldLabel(sheet, "回复偏好（可选）"); EditText preference = field("例如：自然、礼貌、不要太正式", true); sheet.addView(preference);
        addFieldLabel(sheet, "关系阶段（可选）"); EditText stage = field("例如：刚认识、稳定联系", true); sheet.addView(stage);
        addFieldLabel(sheet, "本地记忆（可选）"); EditText memory = field("只保存你主动填写的内容", true); sheet.addView(memory);
        if (!selectedName.isEmpty()) { String raw = getSharedPreferences("chat_profiles", MODE_PRIVATE).getString("profile_" + selectedName, ""); try { JSONObject profile = new JSONObject(raw); identity.setText(profile.optString("identity", "")); preference.setText(profile.optString("preference", "")); stage.setText(profile.optString("stage", "")); memory.setText(profile.optString("memory", "")); } catch (Exception ignored) { } }
        Button save = button("保存并选择此对象", FOREST, Color.WHITE); save.setOnClickListener(v -> { String object = name.getText().toString().trim(); if (object.isEmpty()) { name.setError("请填写对象名称"); return; } saveProfileRecord(object, identity.getText().toString(), preference.getText().toString(), stage.getText().toString(), memory.getText().toString()); dialog.dismiss(); }); sheet.addView(save);
        if (!selectedName.isEmpty()) { Button delete = button("清除此对象档案", Color.TRANSPARENT, TERRACOTTA); delete.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("清除对象档案？").setMessage("将删除本机保存的对象设定和当前选择，无法恢复。") .setNegativeButton("取消", null).setPositiveButton("清除", (ignored, which) -> { deleteProfileRecord(selectedName); dialog.dismiss(); }).show()); sheet.addView(delete); }
        showSheet(dialog, scroll);
    }

    private void addFieldLabel(LinearLayout parent, String text) {
        TextView fieldLabel = label(text); fieldLabel.setTypeface(null, 1); fieldLabel.setTextSize(14);
        fieldLabel.setPadding(0, dp(14), 0, dp(6)); parent.addView(fieldLabel);
    }

    private List<String> profileNames() { ArrayList<String> names = new ArrayList<>(); String raw = getSharedPreferences("chat_profiles", MODE_PRIVATE).getString("profile_names", "[]"); try { JSONArray array = new JSONArray(raw); for (int i = 0; i < array.length(); i++) { String name = array.optString(i, "").trim(); if (!name.isEmpty()) names.add(name); } } catch (Exception ignored) { } return names; }
    private void setCurrentProfile(String name) { getSharedPreferences("chat_profiles", MODE_PRIVATE).edit().putString("current_profile_name", name).apply(); refreshCurrentProfileLabel(); }
    private void saveProfileRecord(String name, String identity, String preference, String stage, String memory) { try { android.content.SharedPreferences prefs = getSharedPreferences("chat_profiles", MODE_PRIVATE); ArrayList<String> names = new ArrayList<>(profileNames()); if (!names.contains(name)) names.add(name); JSONObject profile = new JSONObject().put("identity", identity).put("preference", preference).put("stage", stage).put("memory", memory); prefs.edit().putString("profile_" + name, profile.toString()).putString("current_profile_name", name).putString("profile_names", new JSONArray(names).toString()).apply(); refreshCurrentProfileLabel(); status.setText("对象档案已保存在本机，并已选中「" + name + "」。"); } catch (Exception ignored) { status.setText("对象档案保存失败，请稍后重试。"); } }
    private void deleteProfileRecord(String name) { android.content.SharedPreferences prefs = getSharedPreferences("chat_profiles", MODE_PRIVATE); ArrayList<String> names = new ArrayList<>(profileNames()); names.remove(name); String next = names.isEmpty() ? "" : names.get(0); prefs.edit().remove("profile_" + name).putString("profile_names", new JSONArray(names).toString()).putString("current_profile_name", next).apply(); refreshCurrentProfileLabel(); status.setText("已清除对象档案。"); }

    private void showConnectionStatus() {
        setConnectionStatus(DirectAiSettings.hasKey(this)
                ? "手机直连已启用\n" + DirectAiSettings.budgetLabel(this)
                : "手机直连未配置\n点击设置保存 API Key");
        if (directSettingsStatus != null) directSettingsStatus.setText(DirectAiSettings.budgetLabel(this));
    }

    private void setConnectionStatus(String message) { if (connectionStatus != null) connectionStatus.setText(message); }

    private void startTextOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            status.setText("请在系统设置中允许悬浮窗权限，然后返回应用再次开启。");
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())); startActivity(intent); return;
        }
        Intent intent = new Intent(this, FloatingAssistantService.class);
        intent.putExtra("mode", "text");
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        status.setText("微信内助手已开启。回到微信后点击“聊”开始使用。");
    }

    private void refreshCurrentProfileLabel() {
        if (currentProfileLabel == null) return;
        String name = getSharedPreferences("chat_profiles", MODE_PRIVATE).getString("current_profile_name", "").trim();
        currentProfileLabel.setText(name.isEmpty() ? "当前对象：未选择\n点击选择对象档案" : "当前对象：" + name + "\n档案已保存在本机 · 点击切换或编辑");
        currentProfileLabel.setContentDescription(name.isEmpty() ? "当前对象未选择，点击选择对象档案" : "当前对象：" + name + "，点击切换或编辑对象档案");
    }
    private TextView label(String text) { TextView v = new TextView(this); v.setText(text); v.setTextSize(15); v.setTextColor(INK); return v; }
    private void addSection(String text) { addSpace(18); TextView v = label(text); v.setTypeface(null,1); v.setTextColor(INK); content.addView(v); addSpace(6); }
    private void addSpace(int h) { Space s = new Space(this); content.addView(s, new LinearLayout.LayoutParams(1, dp(h))); }
    private EditText field(String hint, boolean multi) { EditText e = new EditText(this); e.setHint(hint); e.setTextSize(16); e.setTextColor(INK); e.setHintTextColor(Color.rgb(151,154,143)); e.setGravity(Gravity.TOP); e.setPadding(dp(14),dp(12),dp(14),dp(12)); e.setMinHeight(dp(multi ? 92 : 54)); if (multi) e.setSingleLine(false); e.setBackground(round(SURFACE, OUTLINE)); return e; }
    private Button button(String text, int bg, int fg) { Button b = new Button(this); b.setText(text); b.setTextColor(fg); b.setTextSize(14); b.setAllCaps(false); b.setMinHeight(dp(48)); b.setPadding(dp(12),0,dp(12),0); b.setBackground(round(bg, bg == Color.TRANSPARENT ? FOREST : bg)); return b; }
    private GradientDrawable round(int fill, int stroke) { GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(12)); if (stroke != fill) d.setStroke(dp(1), stroke); return d; }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }
}

