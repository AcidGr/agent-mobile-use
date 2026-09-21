package com.agent.mobileuse;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class DemoDialogActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        buildUI();
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            getResources().getDisplayMetrics()
        );
    }

    private void buildUI() {
        // Root dim backdrop
        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        rootLayout.setBackgroundColor(0x70000000); // 44% dark dim
        rootLayout.setClickable(true);

        // Bottom Sheet Card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        );
        card.setLayoutParams(cardLp);
        card.setClickable(true);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF1E2026); // Sleek dark
        float r = dpToPx(24);
        cardBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        card.setBackground(cardBg);

        int padH = dpToPx(24);
        int padV = dpToPx(16);
        card.setPadding(padH, padV, padH, padV + dpToPx(24));

        // Drag handle indicator
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
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        titleTv.setLayoutParams(titleLp);
        headerRow.addView(titleTv);

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

        // Badge banner
        TextView badgeTv = new TextView(this);
        badgeTv.setText("🎉 侧边快捷按键 (Action Button) 唤醒成功！");
        badgeTv.setTextColor(0xFF00E5FF);
        badgeTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        badgeTv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        badgeLp.topMargin = dpToPx(16);
        badgeTv.setLayoutParams(badgeLp);
        card.addView(badgeTv);

        // Content description
        TextView descTv = new TextView(this);
        descTv.setText("已成功拦截系统底层硬件按键信号。\n\n当前为一个完全无意义的测试弹窗，用于闭环验证「硬件侧边按键 -> 系统级Hook拦截 -> 立即拉起无感弹窗」全链路。\n\n后续可在此直接挂载对话输入框与 DSH 任务调度！");
        descTv.setTextColor(0xFFD0D5DD);
        descTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        descTv.setLineSpacing(dpToPx(4), 1.0f);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        descLp.topMargin = dpToPx(10);
        descLp.bottomMargin = dpToPx(20);
        descTv.setLayoutParams(descLp);
        card.addView(descTv);

        // Confirm button
        TextView confirmBtn = new TextView(this);
        confirmBtn.setText("我知道了 (关闭)");
        confirmBtn.setGravity(Gravity.CENTER);
        confirmBtn.setTextColor(Color.WHITE);
        confirmBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        confirmBtn.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(48)
        );
        confirmBtn.setLayoutParams(btnLp);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0xFF0066FF);
        btnBg.setCornerRadius(dpToPx(12));
        confirmBtn.setBackground(btnBg);
        confirmBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        card.addView(confirmBtn);

        rootLayout.addView(card);
        setContentView(rootLayout);
    }
}
