package com.agent.mobileuse;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class DemoDialogActivity extends Activity {
    private static final String TAG = "DemoDialogActivity";
    private static final String BASE_URL = "http://127.0.0.1:3070";

    private Handler mMainHandler;
    private FrameLayout mRootLayout;
    private LinearLayout mCard;
    private boolean mIsActiveTask = false;
    private boolean mIsPolling = false;

    // View references in State B (Monitoring)
    private TextView mStatusDot;
    private TextView mStatusTitle;
    private TextView mToolNameTv;
    private TextView mToolSummaryTv;
    private TextView mWorkspaceBadge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mMainHandler = new Handler(Looper.getMainLooper());

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        initBaseUI();
        queryWorkspaceStatus();
    }

    @Override
    public void finish() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
        super.finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mIsPolling = false;
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            getResources().getDisplayMetrics()
        );
    }

    private void initBaseUI() {
        mRootLayout = new FrameLayout(this);
        mRootLayout.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        mRootLayout.setBackgroundColor(Color.TRANSPARENT);
        mRootLayout.setClickable(true);
        mRootLayout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        mCard = new LinearLayout(this);
        mCard.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        );
        int marginH = dpToPx(24);
        cardLp.leftMargin = marginH;
        cardLp.rightMargin = marginH;
        mCard.setLayoutParams(cardLp);
        mCard.setClickable(true);
        mCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Consume click inside card to prevent backdrop dismiss
            }
        });

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF1E2026); // Dark sleek card
        cardBg.setCornerRadius(dpToPx(20));
        cardBg.setStroke(dpToPx(1), 0xFF383D4A);
        mCard.setBackground(cardBg);

        int padH = dpToPx(20);
        int padV = dpToPx(20);
        mCard.setPadding(padH, padV, padH, padV);

        mRootLayout.addView(mCard);
        setContentView(mRootLayout);
    }

    private void queryWorkspaceStatus() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(BASE_URL + "/api/chat/status");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);

                    int code = conn.getResponseCode();
                    if (code == 200) {
                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line);
                        }
                        br.close();

                        final JSONObject obj = new JSONObject(sb.toString());
                        final boolean isActive = obj.optBoolean("is_active", false);

                        mMainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isActive) {
                                    renderStateB(obj);
                                } else {
                                    renderStateA();
                                }
                            }
                        });
                        return;
                    }
                } catch (Throwable t) {
                    android.util.Log.w(TAG, "Query status failed: " + t.getMessage());
                }

                // Fallback to State A
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        renderStateA();
                    }
                });
            }
        }).start();
    }

    // State A: Idle input mode (No emoji, sleek cyberpunk dark UI)
    private void renderStateA() {
        mIsPolling = false;
        mCard.removeAllViews();

        // Header row
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerRowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        headerRow.setLayoutParams(headerRowLp);

        TextView titleTv = new TextView(this);
        titleTv.setText("DeepSeek Mobile Agent");
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleTv.setLayoutParams(titleLp);
        headerRow.addView(titleTv);
        mCard.addView(headerRow);

        // Workspace Badge
        TextView wsBadge = new TextView(this);
        wsBadge.setText("工作区: /storage/emulated/0/workspace");
        wsBadge.setTextColor(0xFF8A90A0);
        wsBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams wsLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        wsLp.topMargin = dpToPx(6);
        wsLp.bottomMargin = dpToPx(16);
        wsBadge.setLayoutParams(wsLp);
        mCard.addView(wsBadge);

        // Input EditText
        final EditText inputEt = new EditText(this);
        inputEt.setHint("输入任务指令，例如：检查便签并整理待办...");
        inputEt.setHintTextColor(0xFF686F80);
        inputEt.setTextColor(Color.WHITE);
        inputEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        inputEt.setMinLines(3);
        inputEt.setMaxLines(6);
        inputEt.setGravity(Gravity.TOP | Gravity.LEFT);
        inputEt.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12));

        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(0xFF282B33);
        inputBg.setCornerRadius(dpToPx(12));
        inputBg.setStroke(dpToPx(1), 0xFF383D4A);
        inputEt.setBackground(inputBg);

        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        inputLp.bottomMargin = dpToPx(16);
        inputEt.setLayoutParams(inputLp);
        mCard.addView(inputEt);

        // Action Buttons Row: Send Button
        final TextView sendBtn = new TextView(this);
        sendBtn.setText("发送指令并开始");
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setTextColor(Color.WHITE);
        sendBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sendBtn.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(48)
        );
        sendBtn.setLayoutParams(btnLp);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0xFF0066FF);
        btnBg.setCornerRadius(dpToPx(12));
        sendBtn.setBackground(btnBg);

        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = inputEt.getText().toString().trim();
                if (text.isEmpty()) return;

                sendBtn.setEnabled(false);
                sendBtn.setText("正在调度任务...");
                submitPrompt(text);
            }
        });
        mCard.addView(sendBtn);

        // Request focus and show keyboard
        inputEt.requestFocus();
        mMainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(inputEt, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 150);
    }

    private void submitPrompt(final String prompt) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(BASE_URL + "/api/chat/send");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(3000);

                    JSONObject payload = new JSONObject();
                    payload.put("prompt", prompt);

                    byte[] bytes = payload.toString().getBytes("UTF-8");
                    conn.setFixedLengthStreamingMode(bytes.length);
                    OutputStream os = conn.getOutputStream();
                    os.write(bytes);
                    os.flush();
                    os.close();

                    int code = conn.getResponseCode();
                    if (code == 200) {
                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        br.close();

                        final JSONObject res = new JSONObject(sb.toString());
                        mMainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                renderStateB(res);
                            }
                        });
                        return;
                    }
                } catch (Throwable t) {
                    android.util.Log.e(TAG, "submitPrompt failed: " + t.getMessage(), t);
                }

                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        // Switch to monitoring state
                        JSONObject mock = new JSONObject();
                        try {
                            mock.put("is_active", true);
                            mock.put("active_prompt", prompt);
                        } catch (Throwable ignored) {}
                        renderStateB(mock);
                    }
                });
            }
        }).start();
    }

    // State B: Active Task Monitoring (Strictly No Emoji, Professional Technical Monitor)
    private void renderStateB(JSONObject statusObj) {
        mIsPolling = true;
        mCard.removeAllViews();

        // Hide soft keyboard if visible
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(mCard.getWindowToken(), 0);

        // Header Row
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerRowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        headerRow.setLayoutParams(headerRowLp);

        // Status Breathing Dot
        mStatusDot = new TextView(this);
        mStatusDot.setText("● ");
        mStatusDot.setTextColor(0xFF00E5FF); // Electric Cyan
        mStatusDot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        headerRow.addView(mStatusDot);

        mStatusTitle = new TextView(this);
        mStatusTitle.setText("任务执行中 [会话接力]");
        mStatusTitle.setTextColor(Color.WHITE);
        mStatusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        mStatusTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        mStatusTitle.setLayoutParams(titleLp);
        headerRow.addView(mStatusTitle);
        mCard.addView(headerRow);

        // Workspace Label
        mWorkspaceBadge = new TextView(this);
        mWorkspaceBadge.setText("工作区: /storage/emulated/0/workspace (并发已锁定)");
        mWorkspaceBadge.setTextColor(0xFF00E5FF);
        mWorkspaceBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams wsLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        wsLp.topMargin = dpToPx(4);
        wsLp.bottomMargin = dpToPx(14);
        mWorkspaceBadge.setLayoutParams(wsLp);
        mCard.addView(mWorkspaceBadge);

        // Live Tool Card Container
        LinearLayout toolCard = new LinearLayout(this);
        toolCard.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams toolCardLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        toolCardLp.bottomMargin = dpToPx(16);
        toolCard.setLayoutParams(toolCardLp);
        toolCard.setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14));

        GradientDrawable toolCardBg = new GradientDrawable();
        toolCardBg.setColor(0xFF282B33);
        toolCardBg.setCornerRadius(dpToPx(12));
        toolCardBg.setStroke(dpToPx(1), 0xFF383D4A);
        toolCard.setBackground(toolCardBg);

        // Tool Title Line
        mToolNameTv = new TextView(this);
        mToolNameTv.setText("[当前动作] 正在连接执行管道...");
        mToolNameTv.setTextColor(0xFFE0E5F0);
        mToolNameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mToolNameTv.setTypeface(Typeface.DEFAULT_BOLD);
        toolCard.addView(mToolNameTv);

        // Tool Summary Line
        mToolSummaryTv = new TextView(this);
        mToolSummaryTv.setText("等待底层工具分发...");
        mToolSummaryTv.setTextColor(0xFF9095A0);
        mToolSummaryTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        summaryLp.topMargin = dpToPx(6);
        mToolSummaryTv.setLayoutParams(summaryLp);
        toolCard.addView(mToolSummaryTv);

        mCard.addView(toolCard);

        // Bottom Action Bar
        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bottomRowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(44)
        );
        bottomRow.setLayoutParams(bottomRowLp);

        // Stop Task Button
        TextView stopBtn = new TextView(this);
        stopBtn.setText("中止任务");
        stopBtn.setGravity(Gravity.CENTER);
        stopBtn.setTextColor(0xFFFF4D4F);
        stopBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        stopBtn.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(0, dpToPx(44), 1.0f);
        stopLp.rightMargin = dpToPx(8);
        stopBtn.setLayoutParams(stopLp);
        GradientDrawable stopBg = new GradientDrawable();
        stopBg.setColor(0x22FF4D4F);
        stopBg.setCornerRadius(dpToPx(10));
        stopBg.setStroke(dpToPx(1), 0x66FF4D4F);
        stopBtn.setBackground(stopBg);
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopActiveTask();
            }
        });
        bottomRow.addView(stopBtn);

        // Dismiss View Button
        TextView dismissBtn = new TextView(this);
        dismissBtn.setText("收起面板");
        dismissBtn.setGravity(Gravity.CENTER);
        dismissBtn.setTextColor(Color.WHITE);
        dismissBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams disLp = new LinearLayout.LayoutParams(0, dpToPx(44), 1.0f);
        disLp.leftMargin = dpToPx(8);
        dismissBtn.setLayoutParams(disLp);
        GradientDrawable disBg = new GradientDrawable();
        disBg.setColor(0xFF383D4A);
        disBg.setCornerRadius(dpToPx(10));
        dismissBtn.setBackground(disBg);
        dismissBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bottomRow.addView(dismissBtn);

        mCard.addView(bottomRow);

        // Start live polling loop (1000ms interval, strictly lightweight)
        startLiveMonitoring();
    }

    private void startLiveMonitoring() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (mIsPolling) {
                    try {
                        URL url = new URL(BASE_URL + "/api/chat/status");
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(1500);
                        conn.setReadTimeout(1500);

                        int code = conn.getResponseCode();
                        if (code == 200) {
                            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = br.readLine()) != null) sb.append(line);
                            br.close();

                            final JSONObject obj = new JSONObject(sb.toString());
                            final boolean isActive = obj.optBoolean("is_active", false);
                            final JSONObject lastEv = obj.optJSONObject("last_event");

                            mMainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (!mIsPolling) return;
                                    if (!isActive) {
                                        // Task completed
                                        mStatusTitle.setText("任务已完成 [空闲]");
                                        mStatusDot.setTextColor(0xFF00E676); // Green
                                        mToolNameTv.setText("[执行完毕] 所有指令已处理完成");
                                        mToolSummaryTv.setText("可点击收起面板或重新提交新任务");
                                    } else if (lastEv != null) {
                                        String tool = lastEv.optString("tool", "system");
                                        String summary = lastEv.optString("summary", "");
                                        String type = lastEv.optString("type", "");

                                        if ("tool_start".equals(type)) {
                                            mToolNameTv.setText("[正在执行] " + tool);
                                            mToolSummaryTv.setText(summary.isEmpty() ? "调用参数处理中..." : summary);
                                            mStatusDot.setTextColor(0xFF00E5FF);
                                        } else if ("tool_end".equals(type)) {
                                            boolean ok = lastEv.optBoolean("success", true);
                                            long dur = lastEv.optLong("duration_ms", 0);
                                            mToolNameTv.setText("[完成步骤] " + tool + (ok ? " (成功)" : " (失败)"));
                                            mToolSummaryTv.setText("耗时 " + dur + "ms, 等待下一步指令...");
                                            mStatusDot.setTextColor(ok ? 0xFF00E5FF : 0xFFFF4D4F);
                                        }
                                    }
                                }
                            });
                        }
                    } catch (Throwable ignored) {}

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }).start();
    }

    private void stopActiveTask() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(BASE_URL + "/api/chat/stop");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);
                    conn.getResponseCode();
                } catch (Throwable ignored) {}

                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        renderStateA();
                    }
                });
            }
        }).start();
    }
}
