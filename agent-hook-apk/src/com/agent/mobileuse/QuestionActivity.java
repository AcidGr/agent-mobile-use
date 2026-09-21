package com.agent.mobileuse;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class QuestionActivity extends Activity {
    private static final String TAG = "QuestionActivity";

    private String mRequestId;
    private String mDataJson;
    private BroadcastReceiver mDismissReceiver;
    private boolean mAnswered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Make window translucent and layout into system bars
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            );
        }

        Intent intent = getIntent();
        if (intent != null) {
            mRequestId = intent.getStringExtra("request_id");
            mDataJson = intent.getStringExtra("data");
        }

        if (mRequestId == null || mDataJson == null) {
            finish();
            return;
        }

        // Register dismiss receiver to close card if cancelled from Web
        mDismissReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (QuestionReceiver.ACTION_DISMISS_CARD.equals(intent.getAction())) {
                    Log.i(TAG, "Dismiss card received from external broadcast");
                    finish();
                }
            }
        };
        IntentFilter filter = new IntentFilter(QuestionReceiver.ACTION_DISMISS_CARD);
        registerReceiver(mDismissReceiver, filter);

        try {
            buildUI();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to build UI: " + t.getMessage(), t);
            finish();
        }
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

        final JSONObject qObj = questions.getJSONObject(0);
        final String questionId = qObj.optString("id");
        String header = qObj.optString("header", "DeepSeek Agent 需要您的选择");
        String questionText = qObj.optString("question", "");
        final boolean isMultiSelect = qObj.optBoolean("multi_select", false);
        final JSONArray options = qObj.optJSONArray("options");

        // Root Container: Dimmed backdrop, tap to dismiss
        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        rootLayout.setBackgroundColor(0x70000000); // 44% black dim
        rootLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // Bottom Sheet Card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        );
        card.setLayoutParams(cardLp);
        card.setClickable(true); // Don't trigger root dismiss when tapping card

        // Rounded top corners gradient background
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF1E2026); // Deep sleek dark
        float r = dpToPx(24);
        cardBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        card.setBackground(cardBg);

        int padH = dpToPx(24);
        int padV = dpToPx(16);
        card.setPadding(padH, padV, padH, padV + dpToPx(20)); // Extra bottom pad for navigation bar

        // Top Drag Handle Indicator
        View handle = new View(this);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dpToPx(40), dpToPx(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = dpToPx(16);
        handle.setLayoutParams(handleLp);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(0x44FFFFFF);
        handleBg.setCornerRadius(dpToPx(2));
        handle.setBackground(handleBg);
        card.addView(handle);

        // Header Title
        TextView titleTv = new TextView(this);
        titleTv.setText(header);
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(titleTv);

        // Question Description
        if (!questionText.isEmpty()) {
            TextView questionTv = new TextView(this);
            questionTv.setText(questionText);
            questionTv.setTextColor(0xFFB0B5C0);
            questionTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            qLp.topMargin = dpToPx(6);
            qLp.bottomMargin = dpToPx(16);
            questionTv.setLayoutParams(qLp);
            card.addView(questionTv);
        } else {
            ((LinearLayout.LayoutParams) titleTv.getLayoutParams()).bottomMargin = dpToPx(16);
        }

        // Scrollable Options List
        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        );
        scroll.setLayoutParams(scrollLp);
        scroll.setFillViewport(true);

        final LinearLayout optionsContainer = new LinearLayout(this);
        optionsContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(optionsContainer);

        final Set<String> selectedSet = new HashSet<String>();
        final List<View> optionViews = new ArrayList<View>();

        if (options != null) {
            for (int i = 0; i < options.length(); i++) {
                final JSONObject opt = options.getJSONObject(i);
                final String label = opt.optString("label");
                final String desc = opt.optString("description", "");

                final LinearLayout optLayout = new LinearLayout(this);
                optLayout.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams optLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                );
                optLp.bottomMargin = dpToPx(10);
                optLayout.setLayoutParams(optLp);
                optLayout.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));

                final GradientDrawable optBg = new GradientDrawable();
                optBg.setColor(0xFF282B33);
                optBg.setCornerRadius(dpToPx(12));
                optBg.setStroke(dpToPx(1), 0xFF383D4A);
                optLayout.setBackground(optBg);

                TextView optLabelTv = new TextView(this);
                optLabelTv.setText(label);
                optLabelTv.setTextColor(Color.WHITE);
                optLabelTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                optLabelTv.setTypeface(Typeface.DEFAULT_BOLD);
                optLayout.addView(optLabelTv);

                if (!desc.isEmpty()) {
                    TextView optDescTv = new TextView(this);
                    optDescTv.setText(desc);
                    optDescTv.setTextColor(0xFF9095A0);
                    optDescTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    descLp.topMargin = dpToPx(3);
                    optDescTv.setLayoutParams(descLp);
                    optLayout.addView(optDescTv);
                }

                optLayout.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (isMultiSelect) {
                            if (selectedSet.contains(label)) {
                                selectedSet.remove(label);
                                optBg.setColor(0xFF282B33);
                                optBg.setStroke(dpToPx(1), 0xFF383D4A);
                            } else {
                                selectedSet.add(label);
                                optBg.setColor(0xFF1E3A5F);
                                optBg.setStroke(dpToPx(1), 0xFF007AFF);
                            }
                        } else {
                            // Single select: instant feedback & submit
                            if (mAnswered) return;
                            mAnswered = true;
                            optBg.setColor(0xFF0051C7);
                            optBg.setStroke(dpToPx(1), 0xFF007AFF);

                            submitAnswer(questionId, label);
                        }
                    }
                });

                optionViews.add(optLayout);
                optionsContainer.addView(optLayout);
            }
        }

        card.addView(scroll);

        // If multi-select, add confirm button
        if (isMultiSelect) {
            final TextView submitBtn = new TextView(this);
            submitBtn.setText("确认提交");
            submitBtn.setGravity(Gravity.CENTER);
            submitBtn.setTextColor(Color.WHITE);
            submitBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            submitBtn.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(48)
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
                    mAnswered = true;
                    submitMultiAnswer(questionId, selectedSet);
                }
            });

            card.addView(submitBtn);
        }

        rootLayout.addView(card);
        setContentView(rootLayout);
    }

    private void submitAnswer(final String questionId, final String selected) {
        // Cancel notification immediately
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(QuestionReceiver.NOTIFICATION_ID);

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
                    JSONObject ansItem = new JSONObject();
                    ansItem.put("id", questionId);
                    JSONArray selArr = new JSONArray();
                    selArr.put(selected);
                    ansItem.put("selected", selArr);
                    answers.put(ansItem);
                    payload.put("answers", answers);

                    byte[] bytes = payload.toString().getBytes("UTF-8");
                    conn.setFixedLengthStreamingMode(bytes.length);
                    OutputStream os = conn.getOutputStream();
                    os.write(bytes);
                    os.flush();
                    os.close();

                    int code = conn.getResponseCode();
                    Log.i(TAG, "submitAnswer response code: " + code);
                } catch (Throwable t) {
                    Log.e(TAG, "submitAnswer failed: " + t.getMessage(), t);
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();

        // Finish after brief animation delay
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 150);
    }

    private void submitMultiAnswer(final String questionId, final Set<String> selectedSet) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(QuestionReceiver.NOTIFICATION_ID);

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
                    JSONObject ansItem = new JSONObject();
                    ansItem.put("id", questionId);
                    JSONArray selArr = new JSONArray();
                    for (String s : selectedSet) {
                        selArr.put(s);
                    }
                    ansItem.put("selected", selArr);
                    answers.put(ansItem);
                    payload.put("answers", answers);

                    byte[] bytes = payload.toString().getBytes("UTF-8");
                    conn.setFixedLengthStreamingMode(bytes.length);
                    OutputStream os = conn.getOutputStream();
                    os.write(bytes);
                    os.flush();
                    os.close();

                    int code = conn.getResponseCode();
                    Log.i(TAG, "submitMultiAnswer response code: " + code);
                } catch (Throwable t) {
                    Log.e(TAG, "submitMultiAnswer failed: " + t.getMessage(), t);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mDismissReceiver != null) {
            try {
                unregisterReceiver(mDismissReceiver);
            } catch (Throwable ignored) {}
            mDismissReceiver = null;
        }
    }
}
