package com.agent.mobileuse;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

public class EdgeGlowController {
    private static final String TAG = "AgentEdgeGlow";
    private static EdgeGlowController sInstance;

    private final Context mContext;
    private WindowManager mWindowManager;
    private GlowView mGlowView;
    private boolean mIsShowing = false;
    private final Handler mMainHandler;

    private EdgeGlowController(Context context) {
        mContext = context;
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized EdgeGlowController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new EdgeGlowController(context);
        }
        return sInstance;
    }

    public void showGlow() {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mIsShowing) {
                        return;
                    }
                    if (mWindowManager == null) {
                        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
                    }
                    if (mWindowManager == null) {
                        return;
                    }

                    // 1272x2800 display metrics, 135px hardware rounded corner
                    int width = mContext.getResources().getDisplayMetrics().widthPixels;
                    int height = mContext.getResources().getDisplayMetrics().heightPixels;
                    if (width <= 0) width = 1272;
                    if (height <= 0) height = 2800;

                    int cornerRadius = 135;
                    int strokeWidth = 14;

                    mGlowView = new GlowView(mContext, width, height, cornerRadius, strokeWidth);

                    // TYPE_SECURE_SYSTEM_OVERLAY (2015) or TYPE_STATUS_BAR_SUB_PANEL (2017)
                    int windowType = 2017;
                    int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                              | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                              | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                              | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                              | WindowManager.LayoutParams.FLAG_SECURE; // Screen capture invisible!

                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        windowType,
                        flags,
                        PixelFormat.TRANSLUCENT
                    );
                    lp.gravity = Gravity.TOP | Gravity.LEFT;
                    lp.setTitle("AgentMobileEdgeGlow");

                    mWindowManager.addView(mGlowView, lp);
                    mGlowView.startPulseAnimation();
                    mIsShowing = true;
                    android.util.Log.i(TAG, "Edge Glow attached to WindowManager with FLAG_SECURE!");
                } catch (Throwable t) {
                    android.util.Log.e(TAG, "Failed to show edge glow: " + t.getMessage(), t);
                }
            }
        });
    }

    public void hideGlow() {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!mIsShowing || mGlowView == null) {
                        return;
                    }
                    mGlowView.stopAnimation();
                    if (mWindowManager != null) {
                        mWindowManager.removeView(mGlowView);
                    }
                    mGlowView = null;
                    mIsShowing = false;
                    android.util.Log.i(TAG, "Edge Glow removed from WindowManager.");
                } catch (Throwable t) {
                    android.util.Log.e(TAG, "Failed to hide edge glow: " + t.getMessage(), t);
                }
            }
        });
    }

    private static class GlowView extends View {
        private final Paint mPaintOuter;
        private final Paint mPaintInner;
        private final Paint mPaintCore;
        private final RectF mRect;
        private final float mCornerRadius;
        private ValueAnimator mAnimator;
        private float mAlphaScale = 1.0f;

        public GlowView(Context context, int w, int h, int radius, int stroke) {
            super(context);
            mCornerRadius = radius;
            float halfStroke = stroke / 2.0f;
            mRect = new RectF(halfStroke, halfStroke, w - halfStroke, h - halfStroke);

            // Layer 1: Wide electric cyan aura
            mPaintOuter = new Paint(Paint.ANTI_ALIAS_FLAG);
            mPaintOuter.setStyle(Paint.Style.STROKE);
            mPaintOuter.setStrokeWidth(stroke * 2.2f);
            mPaintOuter.setColor(Color.argb(75, 0, 210, 255));

            // Layer 2: Transition deep indigo/purple glow
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
