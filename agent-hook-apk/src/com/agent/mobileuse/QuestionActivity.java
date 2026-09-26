package com.agent.mobileuse;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class QuestionActivity extends Activity {
    private static final String TAG = "QuestionActivity";
    public static final int NOTIFICATION_ID = 20086;
    private static final String CHANNEL_ID = "agent_question_channel";
    private static final String CHANNEL_NAME = "Agent 交互确认";

    private String mRequestId;
    private String mDataJson;
    private boolean mAnswered = false;

    private static class OptionItem {
        String label;
        String desc;
        LinearLayout layout;
        GradientDrawable bg;
    }

    private static class QuestionItem {
        String id;
        String header;
        String question;
        boolean multiSelect;
        Set<String> selectedLabels = new LinkedHashSet<String>();
        EditText customEt;
        List<OptionItem> options = new ArrayList<OptionItem>();
    }

    private final List<QuestionItem> mQuestionList = new ArrayList<QuestionItem>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Make window translucent and layout into system bars
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.addFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            );
        }

        Intent intent = getIntent();
        if (handleActionIntent(intent)) {
            return;
        }

        // Dismiss the heads-up notification since user is now interacting with the card
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(NOTIFICATION_ID);
            }
        } catch (Throwable ignored) {}

        try {
            buildUI();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to build UI: " + t.getMessage(), t);
            finish();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleActionIntent(intent);
    }

    private boolean handleActionIntent(Intent intent) {
        if (intent == null) return false;

        // 1. Capsule action for GlowService (START_FOREGROUND, START_BACKGROUND, STOP)
        String capsuleAction = intent.getStringExtra("capsule_action");
        if (capsuleAction != null) {
            try {
                Intent sIntent = new Intent(this, GlowService.class);
                sIntent.setAction(capsuleAction);
                String sid = intent.getStringExtra("session_id");
                if (sid != null) sIntent.putExtra("session_id", sid);
                String title = intent.getStringExtra("session_title");
                if (title != null) sIntent.putExtra("session_title", title);
                if ("STOP".equals(capsuleAction)) {
                    try {
                        stopService(sIntent);
                    } catch (Throwable ignored) {}
                    try {
                        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        if (nm != null) {
                            nm.cancel(10086);
                        }
                    } catch (Throwable ignored) {}
                    try {
                        android.content.SharedPreferences sp = getSharedPreferences("agent_capsule_state", Context.MODE_PRIVATE);
                        sp.edit().clear().apply();
                    } catch (Throwable ignored) {}
                } else {
                    boolean started = false;
                    if (Build.VERSION.SDK_INT >= 26) {
                        try {
                            java.lang.reflect.Method m = getClass().getMethod("startForegroundService", Intent.class);
                            m.invoke(this, sIntent);
                            started = true;
                        } catch (Throwable ignored) {}
                    }
                    if (!started) {
                        startService(sIntent);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to handle capsule_action: " + t.getMessage(), t);
            }
            finish();
            overridePendingTransition(0, 0);
            return true;
        }

        // 2. Notification cancel
        if (intent.getBooleanExtra("cancel_question", false)) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.cancel(NOTIFICATION_ID);
                }
            } catch (Throwable ignored) {}
            finish();
            overridePendingTransition(0, 0);
            return true;
        }

        if (intent.getBooleanExtra("clear_completed", false)) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.cancel(NotifyReceiver.DEFAULT_TAG, NotifyReceiver.DEFAULT_ID);
                }
            } catch (Throwable ignored) {}
            finish();
            overridePendingTransition(0, 0);
            return true;
        }

        // 3. Task Notifications (Completed / In-progress)
        boolean hasCompleted = intent.hasExtra("is_completed");
        boolean onlyNotify = intent.getBooleanExtra("only_notify", false);
        if (hasCompleted || (onlyNotify && intent.hasExtra("content"))) {
            boolean isCompleted = intent.getBooleanExtra("is_completed", false);
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    String title = intent.getStringExtra("title");
                    String subtext = intent.getStringExtra("subtext");
                    String content = intent.getStringExtra("content");
                    String tag = intent.getStringExtra("tag");
                    if (tag == null || tag.isEmpty()) tag = NotifyReceiver.DEFAULT_TAG;
                    int id = intent.getIntExtra("id", NotifyReceiver.DEFAULT_ID);
                    int total = intent.getIntExtra("total", 0);
                    int completed = intent.getIntExtra("completed", 0);
                    String sessionId = intent.getStringExtra("session_id");
                    if (sessionId == null || sessionId.isEmpty()) {
                        sessionId = intent.getStringExtra("session");
                    }
                    if (isCompleted) {
                        try {
                            Intent sIntent = new Intent(this, GlowService.class);
                            stopService(sIntent);
                        } catch (Throwable ignored) {}
                        try {
                            nm.cancel(10086);
                        } catch (Throwable ignored) {}
                        try {
                            android.content.SharedPreferences sp = getSharedPreferences("agent_capsule_state", Context.MODE_PRIVATE);
                            sp.edit().clear().apply();
                        } catch (Throwable ignored) {}
                        NotifyReceiver.postCompletedNotification(this, nm, tag, id, title, subtext, content, total, completed, sessionId);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "postNotification failed: " + t.getMessage(), t);
            }
            finish();
            overridePendingTransition(0, 0);
            return true;
        }

        // 4. Question Notification (only_notify=true)
        mRequestId = intent.getStringExtra("request_id");
        mDataJson = intent.getStringExtra("data");
        if (onlyNotify && mRequestId != null && mDataJson != null) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    showQuestionNotification(this, nm, mRequestId, mDataJson);
                }
            } catch (Throwable t) {
                Log.e(TAG, "showQuestionNotification failed: " + t.getMessage(), t);
            }
            finish();
            overridePendingTransition(0, 0);
            return true;
        }

        // 5. If no valid request for interactive BottomSheet, finish immediately
        if (mRequestId == null || mDataJson == null) {
            finish();
            overridePendingTransition(0, 0);
            return true;
        }

        return false;
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            getResources().getDisplayMetrics()
        );
    }

    private void buildUI() throws Exception {
        JSONObject root = new JSONObject(mDataJson);
        JSONArray questions = root.optJSONArray("questions");
        if (questions == null || questions.length() == 0) {
            finish();
            return;
        }

        final int qTotal = questions.length();
        mQuestionList.clear();

        for (int i = 0; i < qTotal; i++) {
            JSONObject qObj = questions.getJSONObject(i);
            QuestionItem qItem = new QuestionItem();
            qItem.id = qObj.optString("id", "q_" + i);
            qItem.header = qObj.optString("header", "");
            qItem.question = qObj.optString("question", "");
            qItem.multiSelect = qObj.optBoolean("multi_select", false);

            JSONArray optArr = qObj.optJSONArray("options");
            if (optArr != null) {
                for (int j = 0; j < optArr.length(); j++) {
                    JSONObject oObj = optArr.getJSONObject(j);
                    OptionItem oItem = new OptionItem();
                    oItem.label = oObj.optString("label", "");
                    oItem.desc = oObj.optString("description", "");
                    qItem.options.add(oItem);
                }
            }
            mQuestionList.add(qItem);
        }

        // Root Container: Dimmed backdrop
        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        rootLayout.setBackgroundColor(0x70000000);
        rootLayout.setClickable(true);

        // Bottom Sheet Card (custom measured to max 85% height to prevent off-screen overflow)
        LinearLayout card = new LinearLayout(this) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.85);
                int hMode = MeasureSpec.getMode(heightMeasureSpec);
                int hSize = MeasureSpec.getSize(heightMeasureSpec);
                if (hMode == MeasureSpec.UNSPECIFIED || hSize > maxH) {
                    heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST);
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        card.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        );
        card.setLayoutParams(cardLp);
        card.setClickable(true);

        // Rounded top corners gradient background
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF1E2026);
        float r = dpToPx(24);
        cardBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        card.setBackground(cardBg);

        int padH = dpToPx(20);
        int padV = dpToPx(16);
        card.setPadding(padH, padV, padH, padV + dpToPx(16));

        // Top Drag Handle Indicator
        View handle = new View(this);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dpToPx(40), dpToPx(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = dpToPx(14);
        handle.setLayoutParams(handleLp);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(0x44FFFFFF);
        handleBg.setCornerRadius(dpToPx(2));
        handle.setBackground(handleBg);
        card.addView(handle);

        // Header Row: Title on left, Close button on right
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerRowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        headerRowLp.bottomMargin = dpToPx(14);
        headerRow.setLayoutParams(headerRowLp);

        TextView titleTv = new TextView(this);
        String mainTitle = qTotal > 1 ? ("Agent 交互确认 (共 " + qTotal + " 题)") : "DeepSeek Agent 需要您的选择";
        titleTv.setText(mainTitle);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1.0f
        );
        titleTv.setLayoutParams(titleLp);
        headerRow.addView(titleTv);

        // Close Button (✕)
        TextView closeBtn = new TextView(this);
        closeBtn.setText("✕");
        closeBtn.setTextColor(0xFFB0B5C0);
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        closeBtn.setGravity(Gravity.CENTER);
        int btnSize = dpToPx(32);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(btnSize, btnSize);
        closeLp.leftMargin = dpToPx(12);
        closeBtn.setLayoutParams(closeLp);

        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(0x22FFFFFF);
        closeBg.setCornerRadius(btnSize / 2f);
        closeBtn.setBackground(closeBg);
        closeBtn.setClickable(true);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        headerRow.addView(closeBtn);
        card.addView(headerRow);

        // Scrollable Questions Container
        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        );
        scroll.setLayoutParams(scrollLp);
        scroll.setFillViewport(true);

        LinearLayout questionsContainer = new LinearLayout(this);
        questionsContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(questionsContainer);

        // Build each question section
        for (int qIdx = 0; qIdx < qTotal; qIdx++) {
            final QuestionItem qItem = mQuestionList.get(qIdx);

            LinearLayout qSection = new LinearLayout(this);
            qSection.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams qSecLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            qSecLp.bottomMargin = dpToPx(16);
            qSection.setLayoutParams(qSecLp);

            // If multiple questions, wrap each in a card
            if (qTotal > 1) {
                GradientDrawable secBg = new GradientDrawable();
                secBg.setColor(0xFF262933);
                secBg.setCornerRadius(dpToPx(14));
                secBg.setStroke(dpToPx(1), 0xFF353B4A);
                qSection.setBackground(secBg);
                qSection.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(14));

                // Question index badge
                TextView badgeTv = new TextView(this);
                String badgeText = "问题 " + (qIdx + 1) + " / " + qTotal;
                if (!qItem.header.isEmpty()) {
                    badgeText += " • " + qItem.header;
                }
                if (qItem.multiSelect) {
                    badgeText += " (多选)";
                }
                badgeTv.setText(badgeText);
                badgeTv.setTextColor(0xFF64B5F6);
                badgeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                badgeTv.setTypeface(Typeface.DEFAULT_BOLD);
                qSection.addView(badgeTv);
            }

            // Question Text
            TextView qTv = new TextView(this);
            qTv.setText(qItem.question);
            qTv.setTextColor(Color.WHITE);
            qTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            qTv.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams qTvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            qTvLp.topMargin = dpToPx(qTotal > 1 ? 6 : 0);
            qTvLp.bottomMargin = dpToPx(12);
            qTv.setLayoutParams(qTvLp);
            qSection.addView(qTv);

            // Render Options
            for (int optIdx = 0; optIdx < qItem.options.size(); optIdx++) {
                final OptionItem oItem = qItem.options.get(optIdx);

                final LinearLayout optLayout = new LinearLayout(this);
                optLayout.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams optLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                );
                optLp.bottomMargin = dpToPx(8);
                optLayout.setLayoutParams(optLp);
                optLayout.setPadding(dpToPx(14), dpToPx(11), dpToPx(14), dpToPx(11));

                final GradientDrawable optBg = new GradientDrawable();
                optBg.setColor(0xFF2E323D);
                optBg.setCornerRadius(dpToPx(10));
                optBg.setStroke(dpToPx(1), 0xFF3E4453);
                optLayout.setBackground(optBg);

                oItem.layout = optLayout;
                oItem.bg = optBg;

                TextView optLabelTv = new TextView(this);
                optLabelTv.setText(oItem.label);
                optLabelTv.setTextColor(Color.WHITE);
                optLabelTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                optLabelTv.setTypeface(Typeface.DEFAULT_BOLD);
                optLayout.addView(optLabelTv);

                if (!oItem.desc.isEmpty()) {
                    TextView optDescTv = new TextView(this);
                    optDescTv.setText(oItem.desc);
                    optDescTv.setTextColor(0xFF9EA3AF);
                    optDescTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    descLp.topMargin = dpToPx(2);
                    optDescTv.setLayoutParams(descLp);
                    optLayout.addView(optDescTv);
                }

                optLayout.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (qItem.multiSelect) {
                            if (qItem.selectedLabels.contains(oItem.label)) {
                                qItem.selectedLabels.remove(oItem.label);
                                optBg.setColor(0xFF2E323D);
                                optBg.setStroke(dpToPx(1), 0xFF3E4453);
                            } else {
                                qItem.selectedLabels.add(oItem.label);
                                optBg.setColor(0xFF1E3A5F);
                                optBg.setStroke(dpToPx(1), 0xFF007AFF);
                            }
                        } else {
                            // Single select: toggle or select this one
                            for (OptionItem other : qItem.options) {
                                other.bg.setColor(0xFF2E323D);
                                other.bg.setStroke(dpToPx(1), 0xFF3E4453);
                            }
                            qItem.selectedLabels.clear();
                            qItem.selectedLabels.add(oItem.label);
                            optBg.setColor(0xFF0051C7);
                            optBg.setStroke(dpToPx(1), 0xFF007AFF);

                            // Clear custom input if an option is picked
                            if (qItem.customEt != null) {
                                qItem.customEt.setText("");
                            }
                        }
                    }
                });

                qSection.addView(optLayout);
            }

            // Custom Input Box (EditText)
            final EditText customEt = new EditText(this);
            qItem.customEt = customEt;
            customEt.setHint("或在此输入自定义回答...");
            customEt.setHintTextColor(0xFF757B88);
            customEt.setTextColor(Color.WHITE);
            customEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            customEt.setPadding(dpToPx(14), dpToPx(11), dpToPx(14), dpToPx(11));

            final GradientDrawable etBg = new GradientDrawable();
            etBg.setColor(0xFF1B1D23);
            etBg.setCornerRadius(dpToPx(10));
            etBg.setStroke(dpToPx(1), 0xFF3E4453);
            customEt.setBackground(etBg);

            LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            etLp.topMargin = dpToPx(4);
            customEt.setLayoutParams(etLp);

            customEt.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        etBg.setStroke(dpToPx(1), 0xFF007AFF);
                    } else {
                        etBg.setStroke(dpToPx(1), 0xFF3E4453);
                    }
                }
            });

            customEt.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable s) {
                    String txt = s.toString().trim();
                    // If user is typing custom text in single-select, deselect preset buttons
                    if (!txt.isEmpty() && !qItem.multiSelect) {
                        qItem.selectedLabels.clear();
                        for (OptionItem o : qItem.options) {
                            o.bg.setColor(0xFF2E323D);
                            o.bg.setStroke(dpToPx(1), 0xFF3E4453);
                        }
                    }
                }
            });

            qSection.addView(customEt);
            questionsContainer.addView(qSection);
        }

        card.addView(scroll);

        // Submit Button (Fixed at bottom of card)
        final TextView submitBtn = new TextView(this);
        submitBtn.setText("确认提交");
        submitBtn.setGravity(Gravity.CENTER);
        submitBtn.setTextColor(Color.WHITE);
        submitBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        submitBtn.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(46)
        );
        btnLp.topMargin = dpToPx(12);
        submitBtn.setLayoutParams(btnLp);

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0xFF0066FF);
        btnBg.setCornerRadius(dpToPx(12));
        submitBtn.setBackground(btnBg);

        submitBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAnswered) return;

                // Validate each question
                for (int i = 0; i < mQuestionList.size(); i++) {
                    QuestionItem qi = mQuestionList.get(i);
                    String custom = qi.customEt != null ? qi.customEt.getText().toString().trim() : "";
                    if (qi.selectedLabels.isEmpty() && custom.isEmpty()) {
                        String hint = mQuestionList.size() > 1 ? ("请先回答第 " + (i + 1) + " 题") : "请选择或输入回答";
                        Toast.makeText(QuestionActivity.this, hint, Toast.LENGTH_SHORT).show();
                        return;
                    }
                }

                mAnswered = true;
                submitAllAnswers();
            }
        });

        card.addView(submitBtn);

        rootLayout.addView(card);
        setContentView(rootLayout);
    }

    private void submitAllAnswers() {
        // Dismiss notification immediately
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(NOTIFICATION_ID);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL("http://127.0.0.1:3070/api/answer");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(3000);

                    JSONObject payload = new JSONObject();
                    payload.put("request_id", mRequestId);
                    JSONArray answers = new JSONArray();

                    for (QuestionItem qItem : mQuestionList) {
                        JSONObject ansItem = new JSONObject();
                        ansItem.put("id", qItem.id);
                        JSONArray selArr = new JSONArray();

                        for (String opt : qItem.selectedLabels) {
                            selArr.put(opt);
                        }

                        String customText = qItem.customEt != null ? qItem.customEt.getText().toString().trim() : "";
                        if (!customText.isEmpty()) {
                            selArr.put(customText);
                        }

                        ansItem.put("selected", selArr);
                        answers.put(ansItem);
                    }

                    payload.put("answers", answers);

                    byte[] bytes = payload.toString().getBytes("UTF-8");
                    conn.setFixedLengthStreamingMode(bytes.length);
                    OutputStream os = conn.getOutputStream();
                    os.write(bytes);
                    os.flush();
                    os.close();

                    int code = conn.getResponseCode();
                    Log.i(TAG, "submitAllAnswers response code: " + code);
                } catch (Throwable t) {
                    Log.e(TAG, "submitAllAnswers failed: " + t.getMessage(), t);
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 150);
    }

    public static void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> channelClass = Class.forName("android.app.NotificationChannel");
                Constructor<?> ctor = channelClass.getConstructor(String.class, CharSequence.class, int.class);
                Object channel = ctor.newInstance(CHANNEL_ID, CHANNEL_NAME, 4);

                Method setDesc = channelClass.getMethod("setDescription", String.class);
                setDesc.invoke(channel, "DeepSeek Harness Agent 交互提问与选择通知");

                Method enableLights = channelClass.getMethod("enableLights", boolean.class);
                enableLights.invoke(channel, true);

                Method enableVibration = channelClass.getMethod("enableVibration", boolean.class);
                enableVibration.invoke(channel, true);

                try {
                    android.media.AudioAttributes audioAttributes = new android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .build();
                    android.net.Uri soundUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION);
                    Method setSound = channelClass.getMethod("setSound", android.net.Uri.class, android.media.AudioAttributes.class);
                    setSound.invoke(channel, soundUri, audioAttributes);
                } catch (Throwable ignored) {}

                Method createMethod = nm.getClass().getMethod("createNotificationChannel", channelClass);
                createMethod.invoke(nm, channel);
            } catch (Throwable t) {
                Log.w(TAG, "ensureChannel warning: " + t.getMessage());
            }
        }
    }

    public static void showQuestionNotification(Context context, NotificationManager nm, String requestId, String dataJson) {
        try {
            ensureChannel(nm);

            JSONObject root = new JSONObject(dataJson);
            JSONArray questions = root.optJSONArray("questions");
            if (questions == null || questions.length() == 0) return;

            int qCount = questions.length();
            JSONObject firstQ = questions.getJSONObject(0);
            String header = firstQ.optString("header", "DeepSeek Agent 需要您的选择");
            if (qCount > 1) {
                header = header + " (共 " + qCount + " 个问题)";
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < qCount; i++) {
                JSONObject q = questions.getJSONObject(i);
                if (i > 0) sb.append("\n");
                if (qCount > 1) sb.append((i + 1)).append(". ");
                sb.append(q.optString("question", ""));
            }
            String questionText = sb.toString();

            Notification.Builder builder = new Notification.Builder(context);
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    Method setChannelMethod = builder.getClass().getMethod("setChannelId", String.class);
                    setChannelMethod.invoke(builder, CHANNEL_ID);
                } catch (Throwable t) {
                    Log.w(TAG, "setChannelId warning: " + t.getMessage());
                }
            }

            builder.setContentTitle("有问题");
            builder.setSmallIcon(R.drawable.dsh_whale_icon);

            try {
                Bitmap questionIcon = createWhiteBlackQuestionBitmap(192);
                if (questionIcon != null) {
                    builder.setLargeIcon(questionIcon);
                }
            } catch (Throwable ignored) {}

            Notification.BigTextStyle bigStyle = new Notification.BigTextStyle();
            bigStyle.setBigContentTitle(header);
            bigStyle.bigText(questionText);
            builder.setStyle(bigStyle);

            Intent cardIntent = new Intent(context, QuestionActivity.class);
            cardIntent.putExtra("request_id", requestId);
            cardIntent.putExtra("data", dataJson);
            cardIntent.putExtra("only_notify", false);
            cardIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                piFlags |= 0x04000000;
            }
            PendingIntent contentPi = PendingIntent.getActivity(context, 101, cardIntent, piFlags);
            builder.setContentIntent(contentPi);

            builder.setDefaults(Notification.DEFAULT_ALL);
            builder.setPriority(2);
            builder.setAutoCancel(true);
            builder.setShowWhen(true);

            Notification notification = builder.build();
            nm.notify(NOTIFICATION_ID, notification);
            Log.i(TAG, "Question notification posted: requestId=" + requestId);
        } catch (Throwable t) {
            Log.e(TAG, "showQuestionNotification failed: " + t.getMessage(), t);
        }
    }

    private static Bitmap createWhiteBlackQuestionBitmap(int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);

        float center = size / 2.0f;
        float radius = center - 4.0f;

        android.graphics.Paint bgPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(0xFFFFFFFF);
        bgPaint.setStyle(android.graphics.Paint.Style.FILL);
        canvas.drawCircle(center, center, radius, bgPaint);

        android.graphics.Paint textPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFF1E2024);
        textPaint.setTextSize(size * 0.65f);
        textPaint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        textPaint.setTextAlign(android.graphics.Paint.Align.CENTER);

        android.graphics.Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = center - (fm.descent + fm.ascent) / 2.0f;
        canvas.drawText("?", center, textY, textPaint);

        return bitmap;
    }
}
