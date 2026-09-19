package com.agent.mobileuse;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

public class GlowService extends Service {
    private static final String CHANNEL_ID = "agent_glow_notify_channel";
    private static final int NOTIFICATION_ID = 10086;
    private WindowManager mWindowManager;
    private GlowView mGlowView;
    private boolean mIsShowing = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        promoteToForeground();
    }

    private void promoteToForeground() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                // Use reflection for NotificationChannel to ensure compile-time compat with android-23 jar
                Class<?> channelClass = Class.forName("android.app.NotificationChannel");
                java.lang.reflect.Constructor<?> ctor = channelClass.getConstructor(String.class, CharSequence.class, int.class);
                // IMPORTANCE_MIN = 1, IMPORTANCE_LOW = 2
                Object channel = ctor.newInstance(CHANNEL_ID, "Agent Foreground Glow", 1);
                java.lang.reflect.Method createMethod = nm.getClass().getMethod("createNotificationChannel", channelClass);
                createMethod.invoke(nm, channel);

                Notification.Builder builder = new Notification.Builder(this);
                java.lang.reflect.Method setChannelMethod = builder.getClass().getMethod("setChannelId", String.class);
                setChannelMethod.invoke(builder, CHANNEL_ID);
                builder.setContentTitle("Agent 移动端前台操作中")
                       .setContentText("屏幕边缘光效常驻运行")
                       .setSmallIcon(android.R.drawable.stat_notify_sync);
                startForeground(NOTIFICATION_ID, builder.build());
            } else {
                Notification.Builder builder = new Notification.Builder(this);
                builder.setContentTitle("Agent 移动端前台操作中")
                       .setSmallIcon(android.R.drawable.stat_notify_sync);
                startForeground(NOTIFICATION_ID, builder.build());
            }
            android.util.Log.i("AgentGlowService", "Promoted to ForegroundService successfully!");
        } catch (Throwable t) {
            android.util.Log.e("AgentGlowService", "Failed to promote to foreground: " + t.getMessage(), t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promoteToForeground();
        String action = (intent != null) ? intent.getAction() : null;
        if ("START".equals(action)) {
            showGlow();
        } else if ("STOP".equals(action)) {
            hideGlow();
            stopForeground(true);
            stopSelf();
        }
        return START_STICKY;
    }

    private void showGlow() {
        if (mIsShowing) return;

        try {
            mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (mWindowManager == null) return;

            int width = 1272;
            int height = 2800;
            try {
                android.view.Display display = mWindowManager.getDefaultDisplay();
                android.util.DisplayMetrics realMetrics = new android.util.DisplayMetrics();
                java.lang.reflect.Method getRealMetricsMethod = android.view.Display.class.getMethod("getRealMetrics", android.util.DisplayMetrics.class);
                getRealMetricsMethod.invoke(display, realMetrics);
                if (realMetrics.widthPixels > 0) width = realMetrics.widthPixels;
                if (realMetrics.heightPixels > 0) height = realMetrics.heightPixels;
            } catch (Throwable t) {
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                if (dm.widthPixels > 0) width = dm.widthPixels;
                if (dm.heightPixels > 0) height = dm.heightPixels;
            }

            int cornerRadius = 135;
            int strokeWidth = 10; // Sleek 10px cyber glow edge

            mGlowView = new GlowView(this, width, height, cornerRadius, strokeWidth);
            mGlowView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            );

            // TYPE_APPLICATION_OVERLAY = 2038
            // No FLAG_SECURE: perfectly normal screen rendering, screenshot will show normal app + edge glow!
            int windowType = 2038;
            int winFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                         | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                         | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                         | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                winFlags,
                PixelFormat.TRANSLUCENT
            );
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.setTitle("AgentMobileEdgeGlow");

            // Allow overlay to extend into cutout / notch / status bar area (LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS = 3)
            try {
                java.lang.reflect.Field cutoutField = WindowManager.LayoutParams.class.getField("layoutInDisplayCutoutMode");
                cutoutField.setInt(lp, 3);
            } catch (Throwable ignored) {}

            // Ignore system bar insets so window covers 100% of physical display edges
            try {
                java.lang.reflect.Method fitInsetsMethod = WindowManager.LayoutParams.class.getMethod("setFitInsetsTypes", int.class);
                fitInsetsMethod.invoke(lp, 0);
            } catch (Throwable ignored) {}

            mWindowManager.addView(mGlowView, lp);
            mGlowView.startPulseAnimation();
            mIsShowing = true;
            android.util.Log.i("AgentGlowService", "Glow Overlay added (normal transparent blending, visible on display & screenshot).");
        } catch (Throwable t) {
            android.util.Log.e("AgentGlowService", "Failed to add glow view: " + t.getMessage(), t);
        }
    }

    private void hideGlow() {
        if (!mIsShowing || mGlowView == null) return;
        try {
            mGlowView.stopAnimation();
            if (mWindowManager != null) {
                mWindowManager.removeView(mGlowView);
            }
            mGlowView = null;
            mIsShowing = false;
            android.util.Log.i("AgentGlowService", "Glow Overlay removed.");
        } catch (Throwable t) {
            android.util.Log.e("AgentGlowService", "Failed to remove glow view: " + t.getMessage(), t);
        }
    }

    @Override
    public void onDestroy() {
        hideGlow();
        try {
            stopForeground(true);
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    private static class GlowView extends View {
        private final Paint mPaintOuter;
        private final Paint mPaintInner;
        private final Paint mPaintCore;
        private final RectF mRect;
        private final float mCornerRadius;
        private final float mStrokeWidth;
        private ValueAnimator mAnimator;
        private float mAlphaScale = 1.0f;

        public GlowView(Context context, int w, int h, int radius, int stroke) {
            super(context);
            mCornerRadius = radius;
            mStrokeWidth = stroke;
            float halfStroke = stroke / 2.0f;
            mRect = new RectF(halfStroke, halfStroke, w - halfStroke, h - halfStroke);

            // Layer 1: Electric cyan aura
            mPaintOuter = new Paint(Paint.ANTI_ALIAS_FLAG);
            mPaintOuter.setStyle(Paint.Style.STROKE);
            mPaintOuter.setStrokeWidth(stroke * 2.2f);
            mPaintOuter.setColor(Color.argb(75, 0, 210, 255));

            // Layer 2: Deep indigo/purple transition
            mPaintInner = new Paint(Paint.ANTI_ALIAS_FLAG);
            mPaintInner.setStyle(Paint.Style.STROKE);
            mPaintInner.setStrokeWidth(stroke * 1.3f);
            mPaintInner.setColor(Color.argb(150, 110, 70, 255));

            // Layer 3: Sharp core bright line
            mPaintCore = new Paint(Paint.ANTI_ALIAS_FLAG);
            mPaintCore.setStyle(Paint.Style.STROKE);
            mPaintCore.setStrokeWidth(stroke * 0.6f);
            mPaintCore.setColor(Color.argb(255, 230, 245, 255));
        }

        public void startPulseAnimation() {
            mAnimator = ValueAnimator.ofFloat(0.35f, 1.0f);
            mAnimator.setDuration(1200);
            mAnimator.setRepeatMode(ValueAnimator.REVERSE);
            mAnimator.setRepeatCount(ValueAnimator.INFINITE);
            mAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    mAlphaScale = (float) animation.getAnimatedValue();
                    invalidate();
                }
            });
            mAnimator.start();
        }

        public void stopAnimation() {
            if (mAnimator != null) {
                mAnimator.cancel();
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w > 0 && h > 0) {
                float halfStroke = mStrokeWidth / 2.0f;
                mRect.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            mPaintOuter.setAlpha((int) (75 * mAlphaScale));
            mPaintInner.setAlpha((int) (150 * mAlphaScale));
            mPaintCore.setAlpha((int) (255 * mAlphaScale));

            canvas.drawRoundRect(mRect, mCornerRadius, mCornerRadius, mPaintOuter);
            canvas.drawRoundRect(mRect, mCornerRadius, mCornerRadius, mPaintInner);
            canvas.drawRoundRect(mRect, mCornerRadius, mCornerRadius, mPaintCore);
        }
    }
}
