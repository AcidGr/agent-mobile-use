package com.agent.mobileuse;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.List;

public class GlowService extends Service {
    public static final String ACTION_TOUCH = "com.agent.mobileuse.ACTION_TOUCH";

    private static final String CHANNEL_ID = "agent_glow_notify_channel";
    private static final int NOTIFICATION_ID = 10086;
    private WindowManager mWindowManager;
    private GlowView mGlowView;
    private CapsuleView mCapsuleView;
    private boolean mIsShowing = false;
    private BroadcastReceiver mTouchReceiver;
    private Handler mMainHandler;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mMainHandler = new Handler(Looper.getMainLooper());
        promoteToForeground();
    }

    private void promoteToForeground() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                Class<?> channelClass = Class.forName("android.app.NotificationChannel");
                java.lang.reflect.Constructor<?> ctor = channelClass.getConstructor(String.class, CharSequence.class, int.class);
                Object channel = ctor.newInstance(CHANNEL_ID, "Agent Foreground Glow", 1);
                java.lang.reflect.Method createMethod = nm.getClass().getMethod("createNotificationChannel", channelClass);
                createMethod.invoke(nm, channel);

                Notification.Builder builder = new Notification.Builder(this);
                java.lang.reflect.Method setChannelMethod = builder.getClass().getMethod("setChannelId", String.class);
                setChannelMethod.invoke(builder, CHANNEL_ID);
                builder.setContentTitle("Agent 移动端前台操作中")
                       .setContentText("屏幕边缘光效与触控轨迹常驻运行")
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
            int strokeWidth = 24; // Bold cyber glow edge (elevated presence)

            mGlowView = new GlowView(this, width, height, cornerRadius, strokeWidth);
            mGlowView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            );

            // TYPE_APPLICATION_OVERLAY = 2038
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

            // Add Centered Dynamic Island Capsule over camera cutout (Width 400, Height 140, Centered at X=636)
            try {
                int capWidth = 400;
                int capHeight = 140;
                mCapsuleView = new CapsuleView(this, new Runnable() {
                    @Override
                    public void run() {
                        triggerHandoff();
                    }
                });

                int capWindowType = 2038; // TYPE_APPLICATION_OVERLAY
                int capWinFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

                WindowManager.LayoutParams capLp = new WindowManager.LayoutParams(
                    capWidth,
                    capHeight,
                    capWindowType,
                    capWinFlags,
                    PixelFormat.TRANSLUCENT
                );
                capLp.gravity = Gravity.TOP | Gravity.LEFT;
                capLp.x = 436; // Perfectly centered at X=636 (436 + 200 = 636)
                capLp.y = 24;  // Enclosing camera cutout, extending to Y=164 (>141) for seamless and easy touch
                capLp.setTitle("AgentMobileCapsule");

                try {
                    java.lang.reflect.Field cutoutField = WindowManager.LayoutParams.class.getField("layoutInDisplayCutoutMode");
                    cutoutField.setInt(capLp, 3); // LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } catch (Throwable ignored) {}

                try {
                    java.lang.reflect.Method fitInsetsMethod = WindowManager.LayoutParams.class.getMethod("setFitInsetsTypes", int.class);
                    fitInsetsMethod.invoke(capLp, 0);
                } catch (Throwable ignored) {}

                mWindowManager.addView(mCapsuleView, capLp);
                applySkipScreenshot(mCapsuleView);
                android.util.Log.i("AgentGlowService", "CapsuleView added at (" + capLp.x + ", " + capLp.y + ") with size " + capWidth + "x" + capHeight);
            } catch (Throwable capErr) {
                android.util.Log.e("AgentGlowService", "Failed to add capsule view: " + capErr.getMessage(), capErr);
            }

            registerTouchReceiver();
            mIsShowing = true;
            android.util.Log.i("AgentGlowService", "Glow Overlay added (full screen edge glow + touch indicator enabled).");
        } catch (Throwable t) {
            android.util.Log.e("AgentGlowService", "Failed to add glow view: " + t.getMessage(), t);
        }
    }

    private void registerTouchReceiver() {
        if (mTouchReceiver != null) return;
        mTouchReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!mIsShowing || mGlowView == null || intent == null) return;
                int type = intent.getIntExtra("type", 1);
                if (type == 1) {
                    // Click
                    final int x = intent.getIntExtra("x", 0);
                    final int y = intent.getIntExtra("y", 0);
                    android.util.Log.i("AgentGlowService", "Trigger ripple at (" + x + ", " + y + ")");
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (mGlowView != null) mGlowView.addRipple(x, y);
                        }
                    });
                } else if (type == 2) {
                    // Swipe
                    final int x1 = intent.getIntExtra("x1", 0);
                    final int y1 = intent.getIntExtra("y1", 0);
                    final int x2 = intent.getIntExtra("x2", 0);
                    final int y2 = intent.getIntExtra("y2", 0);
                    final int duration = intent.getIntExtra("duration", 300);
                    android.util.Log.i("AgentGlowService", "Trigger swipe from (" + x1 + ", " + y1 + ") to (" + x2 + ", " + y2 + ")");
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (mGlowView != null) mGlowView.addSwipe(x1, y1, x2, y2, duration);
                        }
                    });
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_TOUCH);
        try {
            // Android 14+ RECEIVER_EXPORTED = 2
            java.lang.reflect.Method regMethod = Context.class.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class, int.class);
            regMethod.invoke(this, mTouchReceiver, filter, 2);
            android.util.Log.i("AgentGlowService", "TouchReceiver registered with RECEIVER_EXPORTED (2)");
        } catch (Throwable t) {
            android.util.Log.w("AgentGlowService", "Fallback registerReceiver: " + t.getMessage());
            try {
                registerReceiver(mTouchReceiver, filter);
            } catch (Throwable t2) {
                android.util.Log.e("AgentGlowService", "Failed to register TouchReceiver: " + t2.getMessage(), t2);
            }
        }
    }

    private void unregisterTouchReceiver() {
        if (mTouchReceiver != null) {
            try {
                unregisterReceiver(mTouchReceiver);
            } catch (Throwable ignored) {}
            mTouchReceiver = null;
        }
    }

    private void hideGlow() {
        if (!mIsShowing) return;
        try {
            unregisterTouchReceiver();
            if (mGlowView != null) {
                mGlowView.stopAnimation();
                if (mWindowManager != null) {
                    mWindowManager.removeView(mGlowView);
                }
                mGlowView = null;
            }
            if (mCapsuleView != null) {
                if (mWindowManager != null) {
                    try {
                        mWindowManager.removeView(mCapsuleView);
                    } catch (Throwable ignored) {}
                }
                mCapsuleView = null;
            }
            mIsShowing = false;
            android.util.Log.i("AgentGlowService", "Glow Overlay and CapsuleView removed.");
        } catch (Throwable t) {
            android.util.Log.e("AgentGlowService", "Failed to remove views: " + t.getMessage(), t);
        }
    }

    private void triggerHandoff() {
        android.util.Log.i("AgentGlowService", "Capsule clicked! Triggering handoff to background...");
        if (mCapsuleView != null) {
            mCapsuleView.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(150).start();
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.net.URL url = new java.net.URL("http://127.0.0.1:3070/api/handoff");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(1500);
                    conn.setReadTimeout(1500);
                    int code = conn.getResponseCode();
                    android.util.Log.i("AgentGlowService", "Handoff request finished, response code: " + code);
                    conn.disconnect();
                } catch (Throwable t) {
                    android.util.Log.w("AgentGlowService", "HTTP handoff failed, trying curl fallback: " + t.getMessage());
                    try {
                        Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", "curl -s http://127.0.0.1:3070/api/handoff"}).waitFor();
                        android.util.Log.i("AgentGlowService", "Fallback curl handoff executed.");
                    } catch (Throwable t2) {
                        android.util.Log.e("AgentGlowService", "Curl fallback failed: " + t2.getMessage(), t2);
                    }
                }
            }
        }).start();
    }

    private void applySkipScreenshot(final View view) {
        mMainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    java.lang.reflect.Method getRoot = view.getClass().getMethod("getViewRootImpl");
                    Object vri = getRoot.invoke(view);
                    if (vri != null) {
                        java.lang.reflect.Method getSc = vri.getClass().getMethod("getSurfaceControl");
                        Object sc = getSc.invoke(vri);
                        if (sc != null) {
                            Class<?> tClass = Class.forName("android.view.SurfaceControl$Transaction");
                            Object t = tClass.getConstructor().newInstance();
                            java.lang.reflect.Method setSkip = tClass.getMethod("setSkipScreenshot", Class.forName("android.view.SurfaceControl"), boolean.class);
                            setSkip.invoke(t, sc, true);
                            java.lang.reflect.Method apply = tClass.getMethod("apply");
                            apply.invoke(t);
                            android.util.Log.i("AgentGlowService", "setSkipScreenshot(true) applied to CapsuleView!");
                        }
                    }
                } catch (Throwable t) {
                    android.util.Log.w("AgentGlowService", "SkipScreenshot reflection: " + t.getMessage());
                }
            }
        }, 100);
    }

    private static class CapsuleView extends View {
        private final Paint mBgPaint;
        private final Paint mStrokePaint;
        private final Paint mTextPaint;
        private final Paint mDotPaint;
        private final RectF mBounds = new RectF();
        private final Runnable mOnClickCallback;

        public CapsuleView(Context context, Runnable onClick) {
            super(context);
            mOnClickCallback = onClick;
            mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mBgPaint.setColor(0xF2121316); // 深黑灰，高对比度
            mBgPaint.setStyle(Paint.Style.FILL);

            mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mDotPaint.setColor(0xFF00E5FF); // 呼吸青色圆点
            mDotPaint.setStyle(Paint.Style.FILL);

            mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mTextPaint.setColor(0xFFFFFFFF);
            mTextPaint.setTextSize(28f);
            mTextPaint.setFakeBoldText(true);
            mTextPaint.setTextAlign(Paint.Align.LEFT);

            setClickable(true);
            setWillNotDraw(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    animate().scaleX(0.92f).scaleY(0.92f).setDuration(100).start();
                    invalidate();
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start();
                    invalidate();
                    if (mOnClickCallback != null) {
                        mOnClickCallback.run();
                    }
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start();
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            float r = h / 2.0f;
            mBounds.set(0, 0, w, h);

            canvas.drawRoundRect(mBounds, r, r, mBgPaint);

            // Front camera cutout is centered at X=200 (occupies [162, 238])
            // Left side: Glowing cyan breathing dot
            float dotX = 75f;
            float centerY = h / 2.0f;
            canvas.drawCircle(dotX, centerY, 8f, mDotPaint);

            // Right side: "切到后台" bold text
            mTextPaint.setTextSize(30f);
            Paint.FontMetrics fm = mTextPaint.getFontMetrics();
            float textY = centerY - (fm.descent + fm.ascent) / 2.0f;
            canvas.drawText("切到后台", 242f, textY, mTextPaint);
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
        private final Paint mRippleOuterPaint;
        private final Paint mRippleInnerPaint;
        private final Paint mRippleCorePaint;
        private final Paint mSwipeTrailPaint;
        private final Paint mSwipeCorePaint;
        private final Paint mSwipePointPaint;

        private final RectF mRect;
        private final float mCornerRadius;
        private final float mStrokeWidth;
        private ValueAnimator mAnimator;
        private float mAlphaScale = 1.0f;

        private final List<RippleItem> mRipples = new ArrayList<>();
        private final List<SwipeItem> mSwipes = new ArrayList<>();

        public GlowView(Context context, int w, int h, int radius, int stroke) {
            super(context);
            mCornerRadius = radius;
            mStrokeWidth = stroke;
            float halfStroke = stroke / 2.0f;
            mRect = new RectF(halfStroke, halfStroke, w - halfStroke, h - halfStroke);

            // Edge Layer 1: Electric cyan aura (broader and richer)
            mPaintOuter = new Paint(Paint.ANTI_ALIAS_FLAG);
            mPaintOuter.setStyle(Paint.Style.STROKE);
            mPaintOuter.setStrokeWidth(stroke * 2.5f);
            mPaintOuter.setColor(Color.argb(120, 0, 210, 255));

            // Edge Layer 2: Deep indigo/purple transition
            mPaintInner = new Paint(Paint.ANTI_ALIAS_FLAG);
            mPaintInner.setStyle(Paint.Style.STROKE);
            mPaintInner.setStrokeWidth(stroke * 1.4f);
            mPaintInner.setColor(Color.argb(180, 110, 70, 255));

            // Edge Layer 3: Sharp core bright line
            mPaintCore = new Paint(Paint.ANTI_ALIAS_FLAG);
            mPaintCore.setStyle(Paint.Style.STROKE);
            mPaintCore.setStrokeWidth(stroke * 0.55f);
            mPaintCore.setColor(Color.argb(255, 230, 245, 255));

            // Ripple Paints (bold impact rings)
            mRippleOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mRippleOuterPaint.setStyle(Paint.Style.STROKE);
            mRippleOuterPaint.setStrokeWidth(12.0f);

            mRippleInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mRippleInnerPaint.setStyle(Paint.Style.STROKE);
            mRippleInnerPaint.setStrokeWidth(7.0f);

            mRippleCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mRippleCorePaint.setStyle(Paint.Style.FILL);

            // Swipe Paints (laser-bold trail)
            mSwipeTrailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mSwipeTrailPaint.setStyle(Paint.Style.STROKE);
            mSwipeTrailPaint.setStrokeCap(Paint.Cap.ROUND);
            mSwipeTrailPaint.setStrokeWidth(32.0f);

            mSwipeCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mSwipeCorePaint.setStyle(Paint.Style.STROKE);
            mSwipeCorePaint.setStrokeCap(Paint.Cap.ROUND);
            mSwipeCorePaint.setStrokeWidth(14.0f);

            mSwipePointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mSwipePointPaint.setStyle(Paint.Style.FILL);
        }

        public void addRipple(float x, float y) {
            final RippleItem item = new RippleItem(x, y);
            mRipples.add(item);
            ValueAnimator va = ValueAnimator.ofFloat(0.0f, 1.0f);
            va.setDuration(450);
            va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    item.progress = (float) animation.getAnimatedValue();
                    invalidate();
                }
            });
            va.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mRipples.remove(item);
                    invalidate();
                }
            });
            va.start();
        }

        public void addSwipe(float x1, float y1, float x2, float y2, int duration) {
            final SwipeItem item = new SwipeItem(x1, y1, x2, y2);
            mSwipes.add(item);
            int animDuration = Math.max(duration + 150, 450);
            ValueAnimator va = ValueAnimator.ofFloat(0.0f, 1.0f);
            va.setDuration(animDuration);
            va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    item.progress = (float) animation.getAnimatedValue();
                    invalidate();
                }
            });
            va.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mSwipes.remove(item);
                    invalidate();
                }
            });
            va.start();
        }

        public void startPulseAnimation() {
            mAnimator = ValueAnimator.ofFloat(0.55f, 1.0f);
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
            mRipples.clear();
            mSwipes.clear();
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

            // 1. Draw edge glow frame
            mPaintOuter.setAlpha((int) (120 * mAlphaScale));
            mPaintInner.setAlpha((int) (180 * mAlphaScale));
            mPaintCore.setAlpha((int) (255 * mAlphaScale));

            canvas.drawRoundRect(mRect, mCornerRadius, mCornerRadius, mPaintOuter);
            canvas.drawRoundRect(mRect, mCornerRadius, mCornerRadius, mPaintInner);
            canvas.drawRoundRect(mRect, mCornerRadius, mCornerRadius, mPaintCore);

            // 2. Draw active click ripples
            for (int i = 0; i < mRipples.size(); i++) {
                RippleItem r = mRipples.get(i);
                float p = r.progress;
                float radius = 20.0f + 130.0f * p;
                int alpha = (int) (230 * (1.0f - p));

                // Outer cyan ring
                mRippleOuterPaint.setColor(Color.argb(alpha, 0, 210, 255));
                canvas.drawCircle(r.x, r.y, radius, mRippleOuterPaint);

                // Inner purple ring
                mRippleInnerPaint.setColor(Color.argb((int) (alpha * 0.8f), 110, 70, 255));
                canvas.drawCircle(r.x, r.y, radius * 0.65f, mRippleInnerPaint);

                // Center bright white core
                if (p < 0.7f) {
                    int coreAlpha = (int) (255 * (1.0f - p / 0.7f));
                    mRippleCorePaint.setColor(Color.argb(coreAlpha, 255, 255, 255));
                    canvas.drawCircle(r.x, r.y, 16.0f * (1.0f - p), mRippleCorePaint);
                }
            }

            // 3. Draw active swipe trails
            for (int i = 0; i < mSwipes.size(); i++) {
                SwipeItem s = mSwipes.get(i);
                float p = s.progress;

                // Motion progress: reaches end at p = 0.65, then fades out
                float moveP = Math.min(1.0f, p / 0.65f);
                float curX = s.x1 + (s.x2 - s.x1) * moveP;
                float curY = s.y1 + (s.y2 - s.y1) * moveP;

                float fade = (p < 0.65f) ? 1.0f : (1.0f - (p - 0.65f) / 0.35f);
                int alpha = (int) (220 * fade);

                // Outer cyan aura line
                mSwipeTrailPaint.setColor(Color.argb((int) (alpha * 0.7f), 0, 210, 255));
                canvas.drawLine(s.x1, s.y1, curX, curY, mSwipeTrailPaint);

                // Inner bright white/cyan core line
                mSwipeCorePaint.setColor(Color.argb(alpha, 220, 245, 255));
                canvas.drawLine(s.x1, s.y1, curX, curY, mSwipeCorePaint);

                // Leading head dot
                mSwipePointPaint.setColor(Color.argb(alpha, 0, 230, 255));
                canvas.drawCircle(curX, curY, 24.0f, mSwipePointPaint);
                mSwipePointPaint.setColor(Color.argb(alpha, 255, 255, 255));
                canvas.drawCircle(curX, curY, 12.0f, mSwipePointPaint);

                // Start point small anchor dot
                mSwipePointPaint.setColor(Color.argb((int) (alpha * 0.5f), 110, 70, 255));
                canvas.drawCircle(s.x1, s.y1, 14.0f, mSwipePointPaint);
            }
        }
    }

    private static class RippleItem {
        final float x;
        final float y;
        float progress = 0.0f;

        RippleItem(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private static class SwipeItem {
        final float x1, y1, x2, y2;
        float progress = 0.0f;

        SwipeItem(float x1, float y1, float x2, float y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }
    }
}
