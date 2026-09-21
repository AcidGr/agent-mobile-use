package com.agent.mobileuse;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class DemoDialogActivity extends Activity {
    private static final String TAG = "DemoDialogActivity";
    private static final String DSH_WEB_URL = "http://127.0.0.1:3080/?ov=1";
    private static final String DEFAULT_SECRET = "5E8js7iGeZGFiTXVT1Mi0ZnkBqEXqChPpZ2rPT1X0u8";

    public static class OverlayBridge {
        private final DemoDialogActivity mActivity;

        public OverlayBridge(DemoDialogActivity activity) {
            this.mActivity = activity;
        }

        @JavascriptInterface
        public boolean isOverlay() {
            return true;
        }

        @JavascriptInterface
        public void minimize() {
            if (mActivity != null) {
                mActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mActivity.hideSoftInput();
                        mActivity.moveTaskToBack(true);
                        mActivity.overridePendingTransition(0, 0);
                    }
                });
            }
        }

        @JavascriptInterface
        public void exit() {
            if (mActivity != null) {
                mActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mActivity.finish();
                        mActivity.overridePendingTransition(0, 0);
                    }
                });
            }
        }

        @JavascriptInterface
        public void close() {
            minimize();
        }
    }

    private Handler mMainHandler;
    private FrameLayout mRootLayout;
    private LinearLayout mCard;
    private WebView mWebView;
    private ProgressBar mProgressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);

        mMainHandler = new Handler(Looper.getMainLooper());

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);

            try {
                java.lang.reflect.Method m1 = Window.class.getMethod("setStatusBarContrastEnforced", boolean.class);
                m1.invoke(window, false);
                java.lang.reflect.Method m2 = Window.class.getMethod("setNavigationBarContrastEnforced", boolean.class);
                m2.invoke(window, false);
            } catch (Throwable ignored) {}

            try {
                WindowManager.LayoutParams lp = window.getAttributes();
                java.lang.reflect.Field f = WindowManager.LayoutParams.class.getField("layoutInDisplayCutoutMode");
                f.setInt(lp, 1); // LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES = 1
                window.setAttributes(lp);
            } catch (Throwable ignored) {}

            View decorView = window.getDecorView();
            if (decorView != null) {
                decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                );
            }

            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            window.setWindowAnimations(0);
        }

        initBaseUI();
        loadWebConsole();
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            getResources().getDisplayMetrics()
        );
    }

    private void initBaseUI() {
        // 1. Root backdrop: transparent
        mRootLayout = new FrameLayout(this);
        mRootLayout.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        mRootLayout.setBackgroundColor(Color.TRANSPARENT);

        // 2. Full-screen Floating Card
        mCard = new LinearLayout(this);
        mCard.setOrientation(LinearLayout.VERTICAL);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        );
        mCard.setLayoutParams(cardLp);
        mCard.setClickable(false);

        // Fully transparent card background
        mCard.setBackgroundColor(Color.TRANSPARENT);

        // 3. Header Bar - hidden for clean floating chat experience
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setVisibility(View.GONE);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setBackgroundColor(0xFF181A20);
        int padH = dpToPx(16);
        int padV = dpToPx(10);
        headerRow.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(44)
        );
        headerRow.setLayoutParams(headerLp);

        // Status Dot
        TextView dotTv = new TextView(this);
        dotTv.setText("● ");
        dotTv.setTextColor(0xFF00E5FF);
        dotTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        headerRow.addView(dotTv);

        // Title
        TextView titleTv = new TextView(this);
        titleTv.setText("DeepSeek Agent 控制台");
        titleTv.setTextColor(Color.WHITE);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        titleTv.setLayoutParams(titleLp);
        headerRow.addView(titleTv);

        int btnSize = dpToPx(30);

        // Reload Button
        TextView refreshBtn = new TextView(this);
        refreshBtn.setText("↻");
        refreshBtn.setTextColor(0xFFB0B5C0);
        refreshBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        refreshBtn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams refLp = new LinearLayout.LayoutParams(btnSize, btnSize);
        refLp.rightMargin = dpToPx(8);
        refreshBtn.setLayoutParams(refLp);
        GradientDrawable refBg = new GradientDrawable();
        refBg.setColor(0x22FFFFFF);
        refBg.setCornerRadius(btnSize / 2f);
        refreshBtn.setBackground(refBg);
        refreshBtn.setClickable(true);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mWebView != null) {
                    mWebView.reload();
                }
            }
        });
        headerRow.addView(refreshBtn);

        // New Session Button
        TextView newBtn = new TextView(this);
        newBtn.setText("+");
        newBtn.setTextColor(Color.WHITE);
        newBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        newBtn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams newLp = new LinearLayout.LayoutParams(btnSize, btnSize);
        newLp.rightMargin = dpToPx(8);
        newBtn.setLayoutParams(newLp);
        GradientDrawable newBg = new GradientDrawable();
        newBg.setColor(0xFF0066FF);
        newBg.setCornerRadius(btnSize / 2f);
        newBtn.setBackground(newBg);
        newBtn.setClickable(true);
        newBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mWebView != null) {
                    mWebView.loadUrl(DSH_WEB_URL);
                }
            }
        });
        headerRow.addView(newBtn);

        // Close Button
        TextView closeBtn = new TextView(this);
        closeBtn.setText("✕");
        closeBtn.setTextColor(0xFFB0B5C0);
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        closeBtn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(btnSize, btnSize);
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

        mCard.addView(headerRow);

        // 4. Loading Progress Bar
        mProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProgressBar.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dpToPx(2)
        ));
        mProgressBar.setMax(100);
        mProgressBar.setVisibility(View.GONE);
        mCard.addView(mProgressBar);

        // 5. WebView Container
        mWebView = new WebView(this);
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        );
        mWebView.setLayoutParams(webLp);
        mWebView.setBackgroundColor(Color.TRANSPARENT);

        setupWebViewSettings();
        mCard.addView(mWebView);

        mRootLayout.addView(mCard);
        setContentView(mRootLayout);
    }

    private void setupWebViewSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        mWebView.addJavascriptInterface(new OverlayBridge(this), "DSHOverlayBridge");

        WebSettings ws = mWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // Allow internal SPA page navigation and redirects without opening external browser
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (mProgressBar != null) {
                    if (newProgress < 100) {
                        mProgressBar.setVisibility(View.VISIBLE);
                        mProgressBar.setProgress(newProgress);
                    } else {
                        mProgressBar.setVisibility(View.GONE);
                    }
                }
            }
        });
    }

    private void loadWebConsole() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                // Ensure auth cookie is injected before navigation
                setupAuthCookie();

                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mWebView != null) {
                            Log.i(TAG, "Navigating WebView to: " + DSH_WEB_URL);
                            mWebView.loadUrl(DSH_WEB_URL);
                        }
                    }
                });
            }
        }).start();
    }

    private void setupAuthCookie() {
        try {
            String cookie = getDshAuthCookie();
            if (cookie != null && !cookie.isEmpty()) {
                CookieManager cm = CookieManager.getInstance();
                cm.setAcceptCookie(true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    cm.setAcceptThirdPartyCookies(mWebView, true);
                }
                cm.setCookie("http://127.0.0.1:3080/", cookie + "; Path=/; Max-Age=2505600");
                cm.flush();
                Log.i(TAG, "DSH auth cookie injected successfully into WebView");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to setup auth cookie: " + t.getMessage(), t);
        }
    }

    public static String getDshAuthCookie() {
        try {
            String secret = DEFAULT_SECRET;
            // Check if overridden in workspace file
            try {
                File credFile = new File("/storage/emulated/0/workspace/.dsh_secret");
                if (credFile.exists() && credFile.length() > 0) {
                    FileInputStream fis = new FileInputStream(credFile);
                    byte[] buf = new byte[(int) credFile.length()];
                    int read = fis.read(buf);
                    fis.close();
                    if (read > 0) {
                        String s = new String(buf, 0, read, "UTF-8").trim();
                        if (!s.isEmpty()) secret = s;
                    }
                }
            } catch (Throwable ignored) {}

            String authority = "127.0.0.1:3080";
            byte[] secBytes = Base64.decode(secret, Base64.URL_SAFE);

            // 1. cookie name: "dsh-auth-" + sha256(authority)
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] authHash = md.digest(authority.getBytes("UTF-8"));
            String name = "dsh-auth-" + Base64.encodeToString(authHash, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);

            // 2. payload
            long now = System.currentTimeMillis();
            long exp = now + 29L * 24 * 3600 * 1000;
            JSONObject payload = new JSONObject();
            payload.put("version", 1);
            payload.put("authority", authority);
            payload.put("issuedAt", now);
            payload.put("expiresAt", exp);

            byte[] payloadBytes = payload.toString().getBytes("UTF-8");
            String body = Base64.encodeToString(payloadBytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);

            // 3. HMAC-SHA256 signature
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secBytes, "HmacSHA256"));
            byte[] sig = mac.doFinal(body.getBytes("UTF-8"));
            String sigStr = Base64.encodeToString(sig, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);

            return name + "=v1." + body + "." + sigStr;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to generate cookie: " + t.getMessage(), t);
            return null;
        }
    }

    public void hideSoftInput() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                View currentFocus = getCurrentFocus();
                if (currentFocus != null) {
                    imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
                } else if (mWebView != null) {
                    imm.hideSoftInputFromWindow(mWebView.getWindowToken(), 0);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error hiding soft input: " + t.getMessage());
        }
    }

    @Override
    public void onBackPressed() {
        if (mWebView != null && mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            hideSoftInput();
            moveTaskToBack(true);
            overridePendingTransition(0, 0);
        }
    }

    @Override
    public void finish() {
        hideSoftInput();
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        overridePendingTransition(0, 0);
        if (mWebView != null) {
            mWebView.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        overridePendingTransition(0, 0);
        if (mWebView != null) {
            mWebView.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mWebView != null) {
            try {
                mWebView.stopLoading();
                mWebView.clearHistory();
                ViewGroup parent = (ViewGroup) mWebView.getParent();
                if (parent != null) {
                    parent.removeView(mWebView);
                }
                mWebView.destroy();
            } catch (Throwable t) {
                Log.e(TAG, "Error cleaning up WebView: " + t.getMessage(), t);
            }
            mWebView = null;
        }
    }
}
