package com.replyassistant.android;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.AlertDialog;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.IBinder;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-started overlay for voice capture and reply review. It never writes to WeChat or sends messages. */
public class FloatingAssistantService extends Service {
    private static final String CHANNEL_ID = "reply_assistant_voice";
    private static final String TAG = "ReplyAssistantVoice";
    private static final int PAPER = Color.rgb(246, 239, 226);
    private static final int SURFACE = Color.rgb(251, 248, 240);
    private static final int SAGE = Color.rgb(227, 238, 229);
    private static final int INK = Color.rgb(32, 50, 41);
    private static final int MUTED = Color.rgb(109, 118, 109);
    private static final int OUTLINE = Color.rgb(217, 209, 192);
    private static final int FOREST = Color.rgb(47, 120, 91);
    private static final int TERRACOTTA = Color.rgb(179, 78, 50);
    private static final String LOCAL_BACKEND_URL = "http://127.0.0.1:8000/v1/generate-replies";
    private WindowManager windowManager;
    private View bubble;
    private WindowManager.LayoutParams bubbleParams;
    private LinearLayout card;
    private TextView state;
    private EditText transcript;
    private EditText reply;
    private SpeechRecognizer recognizer;
    private boolean listening;
    private boolean holdingToRecord;
    private int recognitionSegmentsStarted;
    private static final int MAX_RECOGNITION_SEGMENTS = 8;
    private static final long MAX_RECORDING_DURATION_MS = 60_000L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder recognizedText = new StringBuilder();
    private final Runnable restartRecognition = this::startRecognitionSegment;
    private final Runnable recordingTimeout = this::finishRecognition;
    private String lastPartialText = "";
    private long recordingStartedAt;
    private boolean textMode;
    private int foregroundType = -1;
    private EditText objectName, identity, preference, stage, memory, incoming, chatContext, situation;
    private Button pasteIncoming;
    private Button generateChatButton;
    private final AtomicBoolean chatRequestInFlight = new AtomicBoolean(false);
    private Spinner profileSelector, taskMode, routeMode, replyType;
    private boolean profileSelectorUpdating;
    private String activeProfileName = "";
    private LinearLayout candidates;
    private ScrollView textScroller;
    private WindowManager.LayoutParams textCardParams;
    private TextView profileSummary, panelToggle;
    private LinearLayout profileEditor;
    private LinearLayout advancedPanel, errorActions;
    private Button advancedToggle;
    private boolean profileEditorExpanded;
    private boolean cardExpanded;
    private boolean keyboardLayout;
    private boolean lastImeVisible;
    private int lastTextScrollY;
    private static final String PROFILE_PREFS = "chat_profiles";
    private static final String PROFILE_INDEX_KEY = "profile_names";
    private static final String CURRENT_PROFILE_KEY = "current_profile_name";

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        boolean requestedTextMode = intent != null && "text".equals(intent.getStringExtra("mode"));
        if (bubble != null && textMode != requestedTextMode) hideCard();
        textMode = requestedTextMode;
        ensureForeground();
        if (bubble == null) showBubble();
        if (bubble instanceof TextView) ((TextView) bubble).setText(textMode ? "聊" : "说");
        return START_NOT_STICKY;
    }
    private void ensureForeground() {
        int desiredType = Build.VERSION.SDK_INT >= 29
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE | (textMode ? 0 : ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                : 0;
        if (foregroundType == desiredType) return;
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1001, notification(), desiredType);
        } else {
            startForeground(1001, notification());
        }
        foregroundType = desiredType;
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    private void showBubble() {
        TextView bubbleView = new TextView(this); bubble = bubbleView; bubbleView.setText("说"); bubbleView.setTextColor(Color.WHITE); bubbleView.setTextSize(16); bubbleView.setGravity(Gravity.CENTER); bubbleView.setBackground(round(TERRACOTTA, 100));
        bubbleParams = new WindowManager.LayoutParams(dp(62), dp(62), overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.LEFT;
        bubble.setContentDescription("助手入口，点击打开，按住拖动");
        if (Build.VERSION.SDK_INT >= 23) {
            bubble.setOnApplyWindowInsetsListener((view, insets) -> {
                view.post(this::constrainBubblePosition);
                return insets;
            });
        }
        bubble.setOnClickListener(view -> {
            if (textMode) { dismissExternalIme(); constrainBubblePosition(); showCard(); }
            else if (holdingToRecord) stopListening();
            else { showCard(); startListening(); }
        });
        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        bubble.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startX, startY;
            private boolean dragging, cancelled;

            private void move(MotionEvent event) {
                float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                if (!dragging && dx * dx + dy * dy <= touchSlop * touchSlop) return;
                dragging = true;
                Rect bounds = bubbleDragBounds();
                bubbleParams.x = Math.max(bounds.left, Math.min(bounds.right, startX + Math.round(dx)));
                bubbleParams.y = Math.max(bounds.top, Math.min(bounds.bottom, startY + Math.round(dy)));
                windowManager.updateViewLayout(bubble, bubbleParams);
            }

            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX(); downY = event.getRawY();
                        startX = bubbleParams.x; startY = bubbleParams.y;
                        dragging = false; cancelled = false;
                        return true;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        cancelled = true;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!cancelled) move(event);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!cancelled) move(event);
                        if (dragging) saveBubblePosition();
                        else if (!cancelled) view.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        cancelled = true;
                        if (dragging) saveBubblePosition();
                        return true;
                    default:
                        return true;
                }
            }
        });
        restoreBubblePosition();
        windowManager.addView(bubble, bubbleParams);
        bubble.post(this::restoreBubblePosition);
    }

    // Window offsets are relative to the usable display area, excluding system bars.
    private Rect bubbleDragBounds() {
        Rect display = new Rect();
        if (bubble != null && bubble.isAttachedToWindow()) bubble.getWindowVisibleDisplayFrame(display);
        if (display.isEmpty()) display.set(0, 0, getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        int margin = dp(18);
        boolean imeVisible = Build.VERSION.SDK_INT >= 30 && bubble != null && bubble.getRootWindowInsets() != null
                && bubble.getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime());
        int reservedBottom = imeVisible ? dp(620) : dp(300);
        return new Rect(margin, margin, Math.max(margin, display.width() - dp(62) - margin),
                Math.max(margin, display.height() - dp(62) - reservedBottom));
    }

    private void constrainBubblePosition() {
        if (bubble == null || bubbleParams == null) return;
        Rect bounds = bubbleDragBounds();
        bubbleParams.x = Math.max(bounds.left, Math.min(bounds.right, bubbleParams.x));
        bubbleParams.y = Math.max(bounds.top, Math.min(bounds.bottom, bubbleParams.y));
        if (bubble.isAttachedToWindow()) windowManager.updateViewLayout(bubble, bubbleParams);
    }

    private void restoreBubblePosition() {
        if (bubble == null || bubbleParams == null) return;
        Rect bounds = bubbleDragBounds();
        android.content.SharedPreferences prefs = getSharedPreferences("floating_entry", MODE_PRIVATE);
        float x = Math.max(0f, Math.min(1f, prefs.getFloat("bubble_x", 1f)));
        float y = Math.max(0f, Math.min(1f, prefs.getFloat("bubble_y", 0.5f)));
        bubbleParams.x = bounds.left + Math.round((bounds.right - bounds.left) * x);
        bubbleParams.y = bounds.top + Math.round((bounds.bottom - bounds.top) * y);
        bubbleParams.x = Math.max(bounds.left, Math.min(bounds.right, bubbleParams.x));
        bubbleParams.y = Math.max(bounds.top, Math.min(bounds.bottom, bubbleParams.y));
        if (bubble.isAttachedToWindow()) windowManager.updateViewLayout(bubble, bubbleParams);
    }

    private void saveBubblePosition() {
        Rect bounds = bubbleDragBounds();
        float x = (bubbleParams.x - bounds.left) / (float) Math.max(1, bounds.right - bounds.left);
        float y = (bubbleParams.y - bounds.top) / (float) Math.max(1, bounds.bottom - bounds.top);
        getSharedPreferences("floating_entry", MODE_PRIVATE).edit().putFloat("bubble_x", x).putFloat("bubble_y", y).apply();
    }

    private void dismissExternalIme() {
        if (bubble == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(bubble.getWindowToken(), 0);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (bubble != null) bubble.post(this::restoreBubblePosition);
    }

    private void showCard() {
        if (card != null) return;
        if (textMode) { showTextCard(); return; }
        card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(18), dp(14), dp(18), dp(14)); card.setBackground(round(PAPER, 18));
        TextView title = text("微信表达助手 · 语音整理", 18, INK); card.addView(title);
        state = text("点击右侧“说”开始，再点一次结束。", 13, MUTED); card.addView(state);
        transcript = edit("识别文字会出现在这里，可修改后再整理", true); card.addView(transcript);
        Button organize = button("整理消息", FOREST, Color.WHITE); organize.setOnClickListener(v -> organize()); card.addView(organize);
        Button collapse = button("收起", Color.TRANSPARENT, FOREST); collapse.setOnClickListener(v -> hideCard()); card.addView(collapse);
        // 卡片需要接收 EditText 输入；仅允许触摸事件落到窗口外，不使用 FLAG_NOT_FOCUSABLE。
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -2, overlayType(), WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; params.y = dp(54); params.width = getResources().getDisplayMetrics().widthPixels - dp(24);
        windowManager.addView(card, params);
    }

    private void showTextCard() {
        profileEditorExpanded = false;
        cardExpanded = false;
        card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(8), dp(16), dp(12)); card.setBackground(round(PAPER, 24));
        FrameLayout handleSlot = new FrameLayout(this); View handle = new View(this); handle.setBackground(round(Color.rgb(170,151,128), 100)); FrameLayout.LayoutParams handleParams = new FrameLayout.LayoutParams(dp(32), dp(4), Gravity.CENTER); handleSlot.addView(handle, handleParams); handleSlot.setContentDescription("向下拖动关闭助手面板"); final float[] dragStartY = new float[]{0f}; handleSlot.setOnTouchListener((view, event) -> { if (event.getAction() == MotionEvent.ACTION_DOWN) { dragStartY[0] = event.getRawY(); return true; } if (event.getAction() == MotionEvent.ACTION_UP) { if (event.getRawY() - dragStartY[0] > dp(72)) hideCard(); return true; } return true; }); card.addView(handleSlot, new LinearLayout.LayoutParams(-1, dp(28)));
        LinearLayout titleRow = new LinearLayout(this); titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("微信文字助手", 18, INK); title.setTypeface(null, 1); titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        panelToggle = text("收起", 13, FOREST); panelToggle.setGravity(Gravity.CENTER); panelToggle.setOnClickListener(v -> hideCard()); titleRow.addView(panelToggle, new LinearLayout.LayoutParams(dp(72), dp(44))); card.addView(titleRow);
        state = text("选择对象，粘贴对方消息后生成候选。", 12, MUTED); card.addView(state);
        errorActions = new LinearLayout(this); errorActions.setVisibility(View.GONE); errorActions.setGravity(Gravity.END); Button retry = button("重试", TERRACOTTA, Color.WHITE); retry.setOnClickListener(v -> generateChat()); errorActions.addView(retry, new LinearLayout.LayoutParams(0, dp(44), 1)); Button settings = button("打开连接设置", Color.TRANSPARENT, FOREST); settings.setOnClickListener(v -> { Intent intent = new Intent(this, MainActivity.class); intent.putExtra("show_connection_settings", true); intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent); }); errorActions.addView(settings, new LinearLayout.LayoutParams(0, dp(44), 1)); card.addView(errorActions);
        textScroller = new ScrollView(this); textScroller.setFillViewport(true); LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); textScroller.addView(body); card.addView(textScroller, new LinearLayout.LayoutParams(-1, 0, 1));

        body.addView(text("当前对象", 12, MUTED));
        profileSummary = text("当前对象：未选择\n点击选择或编辑对象档案", 14, INK); profileSummary.setPadding(dp(12), dp(10), dp(12), dp(10)); profileSummary.setBackground(outlined(SAGE, OUTLINE)); profileSummary.setOnClickListener(v -> setProfileEditorExpanded(!profileEditorExpanded)); body.addView(profileSummary);
        profileEditor = new LinearLayout(this); profileEditor.setOrientation(LinearLayout.VERTICAL); profileEditor.setPadding(dp(8), dp(6), dp(8), 0); profileEditor.setVisibility(View.GONE); body.addView(profileEditor);
        profileEditor.addView(text("本机对象列表", 13, MUTED));
        profileSelector = new Spinner(this); profileEditor.addView(profileSelector); profileSelector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() { public void onNothingSelected(android.widget.AdapterView<?> parent) { } public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { if (profileSelectorUpdating) return; String selected = position < profileNames().size() ? profileNames().get(position) : ""; if (!selected.isEmpty()) loadProfileByName(selected); } });
        objectName = addField(profileEditor, "对象名称（新建或当前对象）", false, 52); identity = addField(profileEditor, "你的真实身份设定（可选）", true, 58); preference = addField(profileEditor, "回复偏好（可选）", true, 58); stage = addField(profileEditor, "关系阶段（可选）", true, 58); memory = addField(profileEditor, "本地记忆（可选）", true, 58);
        Button load = button("载入此对象档案", Color.TRANSPARENT, FOREST); profileEditor.addView(load); load.setOnClickListener(v -> loadProfile()); Button save = button("保存此对象档案", FOREST, Color.WHITE); profileEditor.addView(save); save.setOnClickListener(v -> saveProfile()); Button clear = button("清除此对象档案", Color.TRANSPARENT, TERRACOTTA); profileEditor.addView(clear); clear.setOnClickListener(v -> clearProfile());

        TextView incomingLabel = text("对方消息", 12, MUTED); incomingLabel.setPadding(0, dp(12), 0, dp(6)); body.addView(incomingLabel);
        incoming = addField(body, "对方刚发来的消息（回复模式必填）", true, 88); pasteIncoming = button("从剪贴板粘贴对方消息", Color.TRANSPARENT, FOREST); pasteIncoming.setContentDescription("从剪贴板粘贴对方消息"); pasteIncoming.setOnClickListener(v -> pasteClipboardToIncoming()); LinearLayout.LayoutParams pasteParams = new LinearLayout.LayoutParams(-1, -2); pasteParams.topMargin = dp(8); body.addView(pasteIncoming, pasteParams);
        advancedToggle = button("高级设置 · 生成路线、上下文、主动开聊", Color.TRANSPARENT, FOREST); advancedToggle.setOnClickListener(v -> { boolean visible = advancedPanel.getVisibility() == View.VISIBLE; advancedPanel.setVisibility(visible ? View.GONE : View.VISIBLE); advancedToggle.setText(visible ? "高级设置 · 生成路线、上下文、主动开聊" : "收起高级设置"); }); LinearLayout.LayoutParams advancedParams = new LinearLayout.LayoutParams(-1, -2); advancedParams.topMargin = dp(8); body.addView(advancedToggle, advancedParams);
        advancedPanel = new LinearLayout(this); advancedPanel.setOrientation(LinearLayout.VERTICAL); advancedPanel.setVisibility(View.GONE); body.addView(advancedPanel);
        taskMode = addSpinner(advancedPanel, "任务模式", new String[]{"回复对方消息", "主动开启聊天"}); routeMode = addSpinner(advancedPanel, "生成路线", new String[]{"真实情况路线 A", "通用回复路线 B"}); chatContext = addField(advancedPanel, "最近几句上下文（可选）", true, 68); situation = addField(advancedPanel, "今天经历、想聊的内容或心情（主动开聊时填写）", true, 68); replyType = addSpinner(advancedPanel, "回复类型", new String[]{"AI 推荐", "轻松接梗", "互动选择", "悬念引导", "具体延展", "轻微暧昧"});
        taskMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() { public void onNothingSelected(android.widget.AdapterView<?> parent) { } public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { updateTaskModeVisibility(); } }); updateTaskModeVisibility();
        candidates = new LinearLayout(this); candidates.setOrientation(LinearLayout.VERTICAL); body.addView(candidates);
        refreshProfileSelector(getSharedPreferences(PROFILE_PREFS, MODE_PRIVATE).getString(CURRENT_PROFILE_KEY, "")); String current = getSharedPreferences(PROFILE_PREFS, MODE_PRIVATE).getString(CURRENT_PROFILE_KEY, ""); if (!current.isEmpty()) loadProfileByName(current); updateProfileSummary();
        generateChatButton = button("生成三条候选", TERRACOTTA, Color.WHITE); generateChatButton.setTextSize(16); generateChatButton.setMinHeight(dp(50)); generateChatButton.setOnClickListener(v -> generateChat()); LinearLayout.LayoutParams generateParams = new LinearLayout.LayoutParams(-1, -2); generateParams.topMargin = dp(8); card.addView(generateChatButton, generateParams);
        textCardParams = new WindowManager.LayoutParams(-1, textCardHeight(), overlayType(), WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT);
        textCardParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; textCardParams.y = dp(18); textCardParams.width = getResources().getDisplayMetrics().widthPixels - dp(20); textCardParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        card.setOnTouchListener((view, event) -> { if (event.getAction() == MotionEvent.ACTION_OUTSIDE && !keyboardLayout) { hideCard(); return true; } return false; });
        windowManager.addView(card, textCardParams);
        card.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            boolean imeVisible = isImeVisible();
            if (imeVisible != lastImeVisible) { lastImeVisible = imeVisible; setKeyboardLayout(imeVisible); }
            else if (imeVisible) updateKeyboardCardHeight();
        });
        textScroller.post(() -> textScroller.scrollTo(0, Math.min(lastTextScrollY, Math.max(0, textScroller.getChildAt(0).getHeight() - textScroller.getHeight()))));
    }

    private EditText addField(LinearLayout parent, String hint, String profileKey) {
        return addField(parent, hint, true, 90);
    }
    private EditText addField(LinearLayout parent, String hint, boolean multi, int minHeightDp) {
        EditText field = edit(hint, multi); field.setMinHeight(dp(minHeightDp)); parent.addView(field);
        field.setOnFocusChangeListener((view, focused) -> {
            if (focused && textScroller != null) {
                setKeyboardLayout(true);
                textScroller.postDelayed(() -> scrollFieldToTop(view), 220);
                textScroller.postDelayed(() -> scrollFieldToTop(view), 700);
            }
        });
        return field;
    }
    private void scrollFieldToTop(View field) {
        if (textScroller == null || field == null) return;
        int[] fieldLocation = new int[2]; int[] scrollerLocation = new int[2];
        field.getLocationOnScreen(fieldLocation); textScroller.getLocationOnScreen(scrollerLocation);
        int contentTop = textScroller.getScrollY() + fieldLocation[1] - scrollerLocation[1];
        textScroller.smoothScrollTo(0, Math.max(0, contentTop - dp(24)));
    }
    private boolean isImeVisible() {
        return Build.VERSION.SDK_INT >= 30 && card != null && card.getRootWindowInsets() != null && card.getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime());
    }
    private void setKeyboardLayout(boolean enabled) {
        if (textCardParams == null || card == null || keyboardLayout == enabled) return;
        keyboardLayout = enabled;
        if (enabled) {
            textCardParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; textCardParams.y = dp(4); textCardParams.height = keyboardCardHeight();
            if (panelToggle != null) panelToggle.setText("收起");
        } else {
            textCardParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; textCardParams.y = dp(18); textCardParams.height = textCardHeight();
            if (panelToggle != null) panelToggle.setText("收起");
        }
        windowManager.updateViewLayout(card, textCardParams);
    }
    private int keyboardCardHeight() {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        if (Build.VERSION.SDK_INT >= 30 && card != null && card.getRootWindowInsets() != null) {
            android.graphics.Insets ime = card.getRootWindowInsets().getInsets(android.view.WindowInsets.Type.ime());
            if (ime.bottom > 0) return Math.max(dp(520), screenHeight - ime.bottom - dp(20));
        }
        return (int) (screenHeight * 0.45f);
    }
    private void updateKeyboardCardHeight() {
        if (!keyboardLayout || textCardParams == null || card == null) return;
        int height = keyboardCardHeight();
        if (textCardParams.height != height) {
            textCardParams.height = height;
            windowManager.updateViewLayout(card, textCardParams);
        }
    }
    private Spinner addSpinner(LinearLayout parent, String label, String[] choices) {
        parent.addView(text(label, 13, MUTED)); Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, choices)); parent.addView(spinner); return spinner;
    }
    private void updateTaskModeVisibility() {
        if (taskMode == null || incoming == null || situation == null) return;
        boolean isReply = taskMode.getSelectedItemPosition() == 0;
        incoming.setVisibility(isReply ? View.VISIBLE : View.GONE);
        if (pasteIncoming != null) pasteIncoming.setVisibility(isReply ? View.VISIBLE : View.GONE);
        situation.setVisibility(isReply ? View.GONE : View.VISIBLE);
    }

    private void pasteClipboardToIncoming() {
        if (incoming == null) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            state.setText("剪贴板没有可粘贴的文字，请先在微信复制对方消息。 ");
            return;
        }
        ClipData clip = clipboard.getPrimaryClip();
        CharSequence value = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).coerceToText(this);
        if (value == null || value.length() == 0) {
            state.setText("剪贴板中没有可粘贴的文字，请先在微信复制对方消息。 ");
            return;
        }
        incoming.requestFocus();
        int start = Math.max(0, incoming.getSelectionStart());
        int end = Math.max(start, incoming.getSelectionEnd());
        incoming.getText().replace(start, end, value);
        incoming.setSelection(Math.min(incoming.length(), start + value.length()));
        state.setText("已粘贴对方消息（" + value.length() + " 字），请检查内容后再生成。 ");
    }
    private void updateProfileSummary() {
        if (profileSummary == null) return;
        String typedName = objectName == null ? "" : objectName.getText().toString().trim();
        String currentName = !activeProfileName.isEmpty() ? activeProfileName : typedName;
        String label = currentName.isEmpty() ? "当前对象：未选择" : "当前对象：" + currentName;
        String action = profileEditorExpanded ? "点击收起对象档案" : (activeProfileName.isEmpty() ? "点击选择或编辑对象档案" : "已载入本机档案 · 点击编辑");
        profileSummary.setText(label + "\n" + action);
    }
    private void setProfileEditorExpanded(boolean expanded) {
        profileEditorExpanded = expanded;
        if (profileEditor != null) profileEditor.setVisibility(expanded ? View.VISIBLE : View.GONE);
        updateProfileSummary();
    }
    private void toggleTextCardSize() {
        if (card == null || textCardParams == null) return;
        cardExpanded = !cardExpanded;
        textCardParams.height = textCardHeight();
        panelToggle.setText(cardExpanded ? "收起" : "收起");
        windowManager.updateViewLayout(card, textCardParams);
    }
    private int textCardHeight() {
        float fraction = cardExpanded ? 0.72f : 0.52f;
        return (int) (getResources().getDisplayMetrics().heightPixels * fraction);
    }
    private void saveProfile() {
        String name = objectName.getText().toString().trim();
        if (name.isEmpty()) { state.setText("请先填写对象名称。"); return; }
        try {
            String profile = new org.json.JSONObject().put("identity", identity.getText().toString()).put("preference", preference.getText().toString()).put("stage", stage.getText().toString()).put("memory", memory.getText().toString()).toString();
            android.content.SharedPreferences prefs = getSharedPreferences(PROFILE_PREFS, MODE_PRIVATE);
            java.util.ArrayList<String> names = profileNames();
            if (!names.contains(name)) names.add(name);
            prefs.edit().putString("profile_" + name, profile).putString(CURRENT_PROFILE_KEY, name).putString(PROFILE_INDEX_KEY, new org.json.JSONArray(names).toString()).apply();
            activeProfileName = name;
            refreshProfileSelector(name);
        } catch (org.json.JSONException error) { state.setText("档案保存失败，请稍后重试。"); return; }
        updateProfileSummary();
        setProfileEditorExpanded(false);
        state.setText("对象档案已保存在本机，并已选中「" + name + "」。");
    }
    private void loadProfile() {
        String name = objectName.getText().toString().trim();
        if (name.isEmpty()) { state.setText("请先填写要载入的对象名称。"); return; }
        loadProfileByName(name);
    }
    private void loadProfileByName(String name) {
        String raw = getSharedPreferences(PROFILE_PREFS, MODE_PRIVATE).getString("profile_" + name, null);
        if (name.isEmpty() || raw == null) { activeProfileName = ""; clearProfileFields(); updateProfileSummary(); state.setText("没有找到该对象的本地档案，可以填写信息后保存。"); return; }
        try {
            org.json.JSONObject profile = new org.json.JSONObject(raw);
            clearChatInputs();
            objectName.setText(name);
            identity.setText(profile.optString("identity", "")); preference.setText(profile.optString("preference", ""));
            stage.setText(profile.optString("stage", "")); memory.setText(profile.optString("memory", ""));
            activeProfileName = name;
            getSharedPreferences(PROFILE_PREFS, MODE_PRIVATE).edit().putString(CURRENT_PROFILE_KEY, name).apply();
            updateProfileSummary();
            state.setText("已载入「" + name + "」的本地档案。");
        } catch (org.json.JSONException error) { state.setText("该对象档案无法读取，请重新保存。"); }
    }
    private void clearProfile() {
        String name = objectName.getText().toString().trim();
        if (name.isEmpty()) { state.setText("请先填写要清除的对象名称。"); return; }
        new AlertDialog.Builder(this).setTitle("清除对象档案？").setMessage("将删除本机保存的对象设定和当前选择，无法恢复。")
                .setNegativeButton("取消", null).setPositiveButton("清除", (dialog, which) -> clearProfileNow(name)).show();
    }
    private void clearProfileNow(String name) {
        android.content.SharedPreferences prefs = getSharedPreferences(PROFILE_PREFS, MODE_PRIVATE);
        java.util.ArrayList<String> names = profileNames(); names.remove(name);
        android.content.SharedPreferences.Editor editor = prefs.edit().remove("profile_" + name).putString(PROFILE_INDEX_KEY, new org.json.JSONArray(names).toString());
        if (name.equals(prefs.getString(CURRENT_PROFILE_KEY, ""))) editor.remove(CURRENT_PROFILE_KEY);
        editor.apply();
        activeProfileName = "";
        clearProfileFields(); clearChatInputs(); refreshProfileSelector(""); updateProfileSummary(); setProfileEditorExpanded(false);
        state.setText("已彻底清除「" + name + "」的本地档案、列表记录和当前选择。");
    }
    private java.util.ArrayList<String> profileNames() {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        String raw = getSharedPreferences(PROFILE_PREFS, MODE_PRIVATE).getString(PROFILE_INDEX_KEY, "[]");
        try {
            org.json.JSONArray array = new org.json.JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String name = array.optString(i, "").trim();
                if (!name.isEmpty() && !names.contains(name) && getSharedPreferences(PROFILE_PREFS, MODE_PRIVATE).contains("profile_" + name)) names.add(name);
            }
        } catch (org.json.JSONException ignored) { }
        return names;
    }
    private void refreshProfileSelector(String selectedName) {
        if (profileSelector == null) return;
        java.util.ArrayList<String> names = profileNames();
        if (names.isEmpty()) names.add("");
        profileSelectorUpdating = true;
        profileSelector.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names));
        int selected = names.indexOf(selectedName);
        profileSelector.setSelection(selected < 0 ? 0 : selected);
        profileSelectorUpdating = false;
    }
    private void clearProfileFields() {
        if (objectName != null) objectName.setText("");
        if (identity != null) identity.setText("");
        if (preference != null) preference.setText("");
        if (stage != null) stage.setText("");
        if (memory != null) memory.setText("");
    }
    private void clearChatInputs() {
        if (incoming != null) incoming.setText("");
        if (chatContext != null) chatContext.setText("");
        if (situation != null) situation.setText("");
        if (taskMode != null) taskMode.setSelection(0);
        if (routeMode != null) routeMode.setSelection(0);
        if (replyType != null) replyType.setSelection(0);
        if (candidates != null) candidates.removeAllViews();
    }
    private void generateChat() {
        String name = objectName.getText().toString().trim();
        if (!activeProfileName.isEmpty() && !activeProfileName.equals(name)) { state.setText("对象名称已变化，请先从本机对象列表载入或保存该对象档案。"); return; }
        boolean isReply = taskMode.getSelectedItemPosition() == 0;
        String message = incoming.getText().toString().trim();
        String userSituation = situation.getText().toString().trim();
        if (name.isEmpty()) {
            setProfileEditorExpanded(true);
            if (textScroller != null) textScroller.smoothScrollTo(0, 0);
            state.setText("请先在当前对象中填写名称；其他档案项可以留空。");
            return;
        }
        if ((isReply && message.isEmpty()) || (!isReply && userSituation.isEmpty())) { state.setText(isReply ? "请粘贴对方刚发来的消息。" : "请填写今天经历、想聊的内容或心情。"); return; }
        if (name.length() > 100 || identity.getText().length() > 500 || preference.getText().length() > 500 || stage.getText().length() > 100 || memory.getText().length() > 3000 || message.length() > 3000 || chatContext.getText().length() > 3000 || userSituation.length() > 2000) { state.setText("输入内容过长，请缩短后再生成。"); return; }
        final JSONObject payload = new JSONObject();
        try {
            payload.put("mode", isReply ? "reply" : "start"); payload.put("route", routeMode.getSelectedItemPosition() == 0 ? "real" : "general");
            payload.put("object_name", name); payload.put("user_identity", identity.getText().toString().trim()); payload.put("reply_preference", preference.getText().toString().trim()); payload.put("relationship_stage", stage.getText().toString().trim()); payload.put("memory", memory.getText().toString().trim()); payload.put("incoming_message", message); payload.put("context", chatContext.getText().toString().trim()); payload.put("user_situation", userSituation);
            String selected = replyType.getSelectedItem().toString(); payload.put("reply_type", "AI 推荐".equals(selected) ? "" : selected);
        } catch (org.json.JSONException error) { state.setText("请求准备失败，请重试。"); return; }
        if (!DirectAiSettings.hasKey(this)) {
            state.setText("手机直连尚未配置 API Key，请在设置中保存密钥。"); showErrorActions(); return;
        }
        if (!DirectAiSettings.hasConsent(this)) {
            AlertDialog consentDialog = new AlertDialog.Builder(this).setTitle("发送给 DeepSeek 前请确认")
                    .setMessage("你填写的当前对象档案、对方消息或想聊的话题将直接发送给 DeepSeek 生成候选。只提交你愿意分享的内容；候选由你检查、复制并手动发送。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("同意并生成", (dialog, which) -> {
                        DirectAiSettings.setConsent(this);
                        sendChat(payload);
                    }).create();
            consentDialog.getWindow().setType(overlayType());
            consentDialog.show();
            return;
        }
        sendChat(payload);
    }
    private void sendChat(JSONObject payload) {
        if (!chatRequestInFlight.compareAndSet(false, true)) { state.setText("正在生成，请稍候。"); return; }
        final View activeCard = card;
        if (generateChatButton != null) generateChatButton.setEnabled(false);
        state.setText("正在请求真实 AI…"); hideErrorActions(); candidates.removeAllViews();
        new Thread(() -> {
            try {
                JSONObject response = DirectChatClient.generate(this, payload);
                runOnMain(() -> { if (card == activeCard && state != null) renderChatResponse(response); });
            } catch (Exception error) { runOnMain(() -> {
                if (card != activeCard || state == null) return;
                state.setText("真实 AI 失败：" + directChatErrorMessage(error) + "。没有生成候选。");
                showErrorActions();
            }); }
            finally { chatRequestInFlight.set(false); runOnMain(() -> { if (card == activeCard && generateChatButton != null) generateChatButton.setEnabled(true); }); }
        }).start();
    }
    private String directChatErrorMessage(Exception error) {
        if (error instanceof DirectChatClient.ApiException) {
            int status = ((DirectChatClient.ApiException) error).status;
            if (status == 401 || status == 403) return "API Key 无效或无权使用，请在设置中检查";
            if (status == 402) return "DeepSeek 账号余额不足，请到平台核对";
            if (status == 429) return "请求过于频繁，请稍后再试";
            if (status >= 500) return "DeepSeek 服务暂不可用，本次不会自动重试";
            return "DeepSeek 拒绝了请求，请检查输入和密钥";
        }
        if (error instanceof SocketTimeoutException) return "请求超时且不会自动重试；本次可能已计费，请到平台核对";
        if (error instanceof ConnectException || error instanceof java.net.UnknownHostException) return "手机无法连接 DeepSeek，请检查网络";
        if (error instanceof IllegalStateException) return error.getMessage();
        if (error instanceof java.io.IOException && "无法保存本机试用用量，请暂停生成".equals(error.getMessage())) return error.getMessage();
        return "直连失败或 AI 返回异常，请稍后检查";
    }
    private JSONObject requestChatWithRetry(JSONObject payload) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try { return requestChat(payload); }
            catch (Exception error) {
                if (attempt >= 1 || !isRetryableChat(error)) throw error;
                runOnMain(() -> state.setText("连接暂时不稳定，正在重试一次…"));
                Thread.sleep(1000);
            }
        }
    }
    private boolean isRetryableChat(Exception error) {
        if (error instanceof SocketTimeoutException || error instanceof ConnectException) return true;
        return error instanceof HttpException && (((HttpException) error).code == 502 || ((HttpException) error).code == 503 || ((HttpException) error).code == 504);
    }
    private JSONObject requestChat(JSONObject payload) throws Exception {
        String origin = backendOrigin();
        String endpoint = (origin.isEmpty() ? "http://127.0.0.1:8000" : origin) + "/v1/chat/generate";
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try { connection.setRequestMethod("POST"); connection.setConnectTimeout(5000); connection.setReadTimeout(30000); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            try (OutputStream output = connection.getOutputStream()) { output.write(payload.toString().getBytes("UTF-8")); }
            int code = connection.getResponseCode(); InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            if (code < 200 || code >= 300) throw new HttpException(code);
            StringBuilder raw = new StringBuilder(); try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"))) { String line; while ((line = reader.readLine()) != null) raw.append(line); }
            return new JSONObject(raw.toString());
        } finally { connection.disconnect(); }
    }
    private void renderChatResponse(JSONObject response) {
        candidates.removeAllViews();
        hideErrorActions();
        if (response.optBoolean("need_more_information", false)) { state.setText("真实 AI 判断信息不足：" + response.optString("message", "请补充必要信息。")); return; }
        JSONArray replies = response.optJSONArray("replies");
        if (replies == null || replies.length() != 3) { state.setText("AI 返回异常：没有得到三条完整候选。"); return; }
        String type = response.optString("recommended_type", "回复建议");
        state.setText("AI 推荐「" + type + "」：" + response.optString("recommendation_reason", "") + "（可编辑后复制）"
                + "\n" + DirectAiSettings.budgetLabel(this));
        for (int i = 0; i < replies.length(); i++) {
            EditText candidate = edit("候选 " + (i + 1), true); candidate.setText(replies.optString(i));
            candidate.setOnTouchListener((view, event) -> { if (textScroller != null && event.getAction() == MotionEvent.ACTION_DOWN) textScroller.requestDisallowInterceptTouchEvent(false); return false; });
            candidates.addView(candidate);
            Button copy = button("复制第 " + (i + 1) + " 条", Color.TRANSPARENT, FOREST); candidates.addView(copy);
            copy.setOnClickListener(v -> { ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("微信聊天候选", candidate.getText().toString())); state.setText("已复制，请回微信手动粘贴、检查并发送。"); });
        }
        if (!keyboardLayout && textCardParams != null && card != null) {
            cardExpanded = true;
            textCardParams.height = textCardHeight();
            windowManager.updateViewLayout(card, textCardParams);
        }
        if (textScroller != null) textScroller.postDelayed(() -> {
            View content = textScroller.getChildAt(0);
            if (content != null) textScroller.scrollTo(0, content.getHeight());
            lastTextScrollY = textScroller.getScrollY();
        }, 300);
    }

    private void showErrorActions() { if (errorActions != null) errorActions.setVisibility(View.VISIBLE); }
    private void hideErrorActions() { if (errorActions != null) errorActions.setVisibility(View.GONE); }

    private void hideCard() {
        stopListening();
        if (textScroller != null) lastTextScrollY = textScroller.getScrollY();
        if (card != null) { windowManager.removeView(card); card = null; state = null; transcript = null; reply = null; }
        textScroller = null; textCardParams = null; profileSummary = null; profileEditor = null; panelToggle = null; advancedPanel = null; advancedToggle = null; errorActions = null; generateChatButton = null; keyboardLayout = false; lastImeVisible = false;
    }

    private void startListening() {
        if (holdingToRecord) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { if (state != null) state.setText("请先在应用中允许麦克风权限。"); return; }
        if (findRecognitionService() == null) { if (state != null) state.setText("当前没有可用的系统语音识别服务，请直接输入文字。"); return; }
        holdingToRecord = true;
        recognitionSegmentsStarted = 0;
        recordingStartedAt = SystemClock.elapsedRealtime();
        recognizedText.setLength(0);
        mainHandler.removeCallbacks(restartRecognition);
        mainHandler.removeCallbacks(recordingTimeout);
        mainHandler.postDelayed(recordingTimeout, MAX_RECORDING_DURATION_MS);
        if (transcript != null) transcript.setText("");
        updateBubble(true);
        startRecognitionSegment();
    }

    private void startRecognitionSegment() {
        if (!holdingToRecord || recognitionSegmentsStarted >= MAX_RECOGNITION_SEGMENTS || SystemClock.elapsedRealtime() - recordingStartedAt >= MAX_RECORDING_DURATION_MS) {
            finishRecognition();
            return;
        }
        ComponentName recognitionService = findRecognitionService();
        if (recognitionService == null) { holdingToRecord = false; finishRecognition(); if (state != null) state.setText("系统语音识别服务不可用，已保留已有文字。"); return; }
        if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); recognizer = null; }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this, recognitionService); recognizer.setRecognitionListener(new Listener());
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH); intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE.toLanguageTag()); intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true); intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3); intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10000L); intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L); intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L);
        lastPartialText = "";
        listening = true; recognitionSegmentsStarted++; if (state != null) state.setText(recognitionSegmentsStarted == 1 ? "正在听，请松开“说”结束…" : "检测到停顿，正在继续听（" + recognitionSegmentsStarted + "/" + MAX_RECOGNITION_SEGMENTS + "）…"); Log.i(TAG, "recognition_start segment=" + recognitionSegmentsStarted + " service=" + recognitionService.flattenToShortString()); recognizer.startListening(intent);
    }

    private void stopListening() {
        if (!holdingToRecord && !listening) return;
        holdingToRecord = false;
        mainHandler.removeCallbacks(restartRecognition);
        mainHandler.removeCallbacks(recordingTimeout);
        Log.i(TAG, "recognition_stop segments=" + recognitionSegmentsStarted + " recognized_length=" + recognizedText.length());
        if (listening && recognizer != null) recognizer.stopListening();
        else finishRecognition();
    }

    private void finishRecognition() {
        holdingToRecord = false;
        listening = false;
        mainHandler.removeCallbacks(restartRecognition);
        mainHandler.removeCallbacks(recordingTimeout);
        releaseRecognizer();
        updateBubble(false);
        if (state != null) state.setText(recognizedText.length() == 0 ? "没有识别到内容，请重试。" : "识别完成，请检查并修改文字。");
    }

    private void updateBubble(boolean recording) { if (bubble instanceof TextView) ((TextView) bubble).setText(recording ? "停" : "说"); }

    private void acceptSegment(String value) {
        String segment = value == null ? "" : value.trim();
        if (!segment.isEmpty()) {
            if (recognizedText.length() == 0) recognizedText.append(segment);
            else recognizedText.append(removeOverlappingPrefix(recognizedText.toString(), segment));
            if (transcript != null) transcript.setText(recognizedText.toString());
        }
    }

    private String removeOverlappingPrefix(String previous, String next) {
        int max = Math.min(16, Math.min(previous.length(), next.length()));
        for (int overlap = max; overlap >= 2; overlap--) {
            if (previous.regionMatches(previous.length() - overlap, next, 0, overlap)) return next.substring(overlap);
        }
        return next;
    }

    private void continueOrFinish() {
        if (holdingToRecord && recognitionSegmentsStarted < MAX_RECOGNITION_SEGMENTS) {
            if (state != null) state.setText("检测到停顿，正在继续听（" + (recognitionSegmentsStarted + 1) + "/" + MAX_RECOGNITION_SEGMENTS + "）…");
            mainHandler.postDelayed(restartRecognition, 250);
        } else finishRecognition();
    }

    private void releaseRecognizer() {
        if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); recognizer = null; }
    }

    private ComponentName findRecognitionService() {
        Intent query = new Intent("android.speech.RecognitionService");
        List<ResolveInfo> services = getPackageManager().queryIntentServices(query, 0);
        if (services == null || services.isEmpty()) return null;
        for (ResolveInfo info : services) {
            if (info.serviceInfo != null) {
                String packageName = info.serviceInfo.packageName == null ? "" : info.serviceInfo.packageName;
                if (packageName.contains("googlequicksearchbox")) return new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name);
            }
        }
        ResolveInfo first = services.get(0);
        return first.serviceInfo == null ? null : new ComponentName(first.serviceInfo.packageName, first.serviceInfo.name);
    }

    private void organize() {
        final String spoken = transcript == null ? "" : transcript.getText().toString().trim();
        if (spoken.isEmpty()) { if (state != null) state.setText("请先说话或输入想表达的内容。"); return; }
        if (state != null) state.setText("正在请求真实 AI…");
        new Thread(() -> {
            try {
                List<String> replies = request(spoken);
                runOnMain(() -> showReply(replies.isEmpty() ? "AI 未返回可用建议。" : replies.get(0), false));
            } catch (Exception error) { runOnMain(() -> showReply("真实 AI 失败：" + errorMessage(error) + "。请检查连接后重试。", true)); }
        }).start();
    }

    private List<String> request(String spoken) throws Exception {
        String origin = backendOrigin();
        String endpoint = origin.isEmpty() ? LOCAL_BACKEND_URL : origin + "/v1/generate-replies";
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setRequestMethod("POST"); connection.setConnectTimeout(5000); connection.setReadTimeout(30000); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            JSONObject payload = new JSONObject(); payload.put("purpose", spoken); payload.put("background", "用户通过系统语音整理入口口述的内容"); payload.put("relationship", "其他"); payload.put("context", ""); payload.put("tone_requirements", "自然、礼貌");
            try (OutputStream output = connection.getOutputStream()) { output.write(payload.toString().getBytes("UTF-8")); }
            int code = connection.getResponseCode(); InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            if (code < 200 || code >= 300) throw new HttpException(code);
            StringBuilder raw = new StringBuilder(); try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"))) { String line; while ((line = reader.readLine()) != null) raw.append(line); }
            JSONObject response = new JSONObject(raw.toString()); JSONArray array = response.optJSONArray("replies");
            if (response.optBoolean("need_more_information", false)) throw new IllegalStateException("信息不足：" + response.optString("message", "请补充内容"));
            if (array == null) throw new IllegalStateException("服务返回异常");
            List<String> replies = new ArrayList<>(); for (int i = 0; i < array.length() && i < 3; i++) replies.add(array.getString(i)); return replies;
        } finally { connection.disconnect(); }
    }

    private void showReply(String text, boolean failed) {
        if (card == null) return;
        if (reply != null) card.removeView(reply);
        reply = edit(text, true); card.addView(reply, 2); state.setText(failed ? "失败状态：未生成可发送建议。" : "建议文本，可编辑后复制；不会自动发送。");
        Button copy = button("复制建议", Color.TRANSPARENT, FOREST); copy.setOnClickListener(v -> { ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("微信表达助手建议", reply.getText().toString())); state.setText("已复制，请回到微信手动粘贴和发送。"); }); card.addView(copy, 3);
    }

    private class Listener implements RecognitionListener {
        public void onResults(android.os.Bundle results) { ArrayList<String> values = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); String finalText = values != null && !values.isEmpty() ? values.get(0).trim() : ""; Log.i(TAG, "recognition_results segment=" + recognitionSegmentsStarted + " count=" + (values == null ? 0 : values.size()) + " final_length=" + finalText.length()); acceptSegment(finalText.isEmpty() ? lastPartialText : finalText); listening = false; releaseRecognizer(); continueOrFinish(); }
        public void onError(int error) { Log.w(TAG, "recognition_error segment=" + recognitionSegmentsStarted + " code=" + error + " partial_length=" + lastPartialText.length()); acceptSegment(lastPartialText); listening = false; releaseRecognizer(); if (holdingToRecord && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)) continueOrFinish(); else if (recognizedText.length() > 0) finishRecognition(); else { holdingToRecord = false; if (state != null) state.setText("语音识别失败：" + recognitionErrorMessage(error) + "。请重试或直接输入文字。"); } }
        public void onReadyForSpeech(android.os.Bundle p) {} public void onBeginningOfSpeech() {} public void onRmsChanged(float r) {} public void onBufferReceived(byte[] b) {} public void onEndOfSpeech() { Log.i(TAG, "recognition_end_of_speech segment=" + recognitionSegmentsStarted); } public void onPartialResults(android.os.Bundle b) { ArrayList<String> values = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); if (values != null && !values.isEmpty() && values.get(0) != null && !values.get(0).trim().isEmpty()) { lastPartialText = values.get(0).trim(); String preview = recognizedText.length() == 0 ? lastPartialText : recognizedText.toString() + removeOverlappingPrefix(recognizedText.toString(), lastPartialText); if (transcript != null) transcript.setText(preview); Log.i(TAG, "recognition_partial segment=" + recognitionSegmentsStarted + " length=" + lastPartialText.length()); } } public void onEvent(int t, android.os.Bundle b) {}
    }

    private void runOnMain(Runnable action) { new android.os.Handler(getMainLooper()).post(action); }
    private String recognitionErrorMessage(int error) { if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) return "识别器正在处理上一轮请求"; if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) return "系统语音服务已断开"; if (error == SpeechRecognizer.ERROR_NO_MATCH) return "没有听清有效内容"; if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) return "等待说话超时"; return "系统错误码 " + error; }
    private String errorMessage(Exception e) { if (e instanceof HttpException) { int code = ((HttpException)e).code; if (code == 404 || code == 405) return "连接到了错误的服务端口，请检查微信回复助手后端和 USB 转发"; if (code == 429) return "请求过于频繁，请稍后再试"; if (code == 400 || code == 422) return "请求内容未通过校验"; if (code == 502 || code == 503 || code == 504) return "AI 服务暂不可用"; } if (e instanceof SocketTimeoutException) return "请求超时，请检查电脑后端是否正常响应"; if (e instanceof ConnectException) return "无法连接服务，请启动电脑后端并完成 USB 端口转发"; return "服务暂不可用"; }
    private static class HttpException extends java.io.IOException { final int code; HttpException(int code) { this.code = code; } }
    private int overlayType() { return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE; }
    private String backendOrigin() { return getSharedPreferences("MainActivity_preferences", MODE_PRIVATE).getString("private_backend_origin", "").trim(); }
    private Notification notification() { Intent intent = new Intent(this, MainActivity.class); PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT); return new Notification.Builder(this, CHANNEL_ID).setContentTitle("微信表达助手").setContentText(textMode ? "悬浮文字入口已开启" : "悬浮语音入口已开启").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(pi).setOngoing(true).build(); }
    private void createChannel() { if (Build.VERSION.SDK_INT >= 26) ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL_ID, "语音整理入口", NotificationManager.IMPORTANCE_LOW)); }
    private TextView text(String value, int size, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setPadding(0, dp(5), 0, dp(5)); return v; }
    private EditText edit(String hint, boolean multi) { EditText e = new EditText(this); e.setHint(hint); e.setTextSize(16); e.setTextColor(INK); e.setHintTextColor(Color.rgb(151,154,143)); e.setGravity(Gravity.TOP); e.setPadding(dp(12),dp(8),dp(12),dp(8)); e.setMinHeight(dp(multi ? 90 : 52)); e.setSingleLine(!multi); e.setInputType(multi ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)); if (multi) e.setHorizontallyScrolling(false); e.setBackground(outlined(SURFACE, OUTLINE)); return e; }
    private Button button(String value, int bg, int fg) { Button b = new Button(this); b.setText(value); b.setTextColor(fg); b.setAllCaps(false); b.setMinHeight(dp(48)); b.setPadding(dp(12), 0, dp(12), 0); b.setBackground(bg == Color.TRANSPARENT ? outlined(Color.TRANSPARENT, FOREST) : round(bg, 12)); return b; }
    private GradientDrawable outlined(int fill, int stroke) { GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(12)); d.setStroke(dp(1), stroke); return d; }
    private GradientDrawable round(int fill, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radius)); return d; }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }

    @Override public void onDestroy() { if (recognizer != null) recognizer.destroy(); if (bubble != null) windowManager.removeView(bubble); hideCard(); super.onDestroy(); }
}
