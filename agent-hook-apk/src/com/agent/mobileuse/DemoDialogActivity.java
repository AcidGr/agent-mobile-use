package com.agent.mobileuse;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
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
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class DemoDialogActivity extends Activity {
    private static final String TAG = "DemoDialogActivity";
    private static final String DSH_WEB_URL = "http://127.0.0.1:3080/?ov=1";

    public static volatile boolean sIsForeground = false;
    public static volatile String sCurrentViewingSessionId = "";

    public static void reportViewState(final boolean foreground, final String sessionId) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    java.net.URL url = new java.net.URL("http://127.0.0.1:3070/api/view_state");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(800);
                    conn.setReadTimeout(800);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    org.json.JSONObject json = new org.json.JSONObject();
                    json.put("foreground", foreground);
                    json.put("session_id", sessionId != null ? sessionId : "");
                    java.io.OutputStream os = conn.getOutputStream();
                    os.write(json.toString().getBytes("UTF-8"));
                    os.flush();
                    os.close();
                    conn.getResponseCode();
                    conn.disconnect();
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    public static class OverlayBridge {
        private final DemoDialogActivity mActivity;

        public OverlayBridge(DemoDialogActivity activity) {
            this.mActivity = activity;
        }

        @JavascriptInterface
        public void reportSession(String sessionId) {
            if (sessionId != null && !sessionId.isEmpty() && !sessionId.equals(sCurrentViewingSessionId)) {
                sCurrentViewingSessionId = sessionId;
                if (sIsForeground) {
                    reportViewState(true, sCurrentViewingSessionId);
                }
            }
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
                        mActivity.finish();
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
        public boolean isKeyboardShowing() {
            return mActivity != null && mActivity.mIsKeyboardShowing;
        }

        @JavascriptInterface
        public void hideSoftInput() {
            if (mActivity != null) {
                mActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mActivity.hideSoftInput();
                    }
                });
            }
        }

        @JavascriptInterface
        public void pressBack() {
            if (mActivity != null) {
                mActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mActivity.onBackPressed();
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
    private volatile boolean mIsKeyboardShowing = false;
    private static final int REQUEST_CODE_PERMISSIONS = 1001;
    private static final int REQUEST_CODE_FILE_CHOOSER = 1002;
    private PermissionRequest mPendingPermissionRequest;
    private ValueCallback<Uri[]> mFilePathCallback;
    private String mTargetSessionId = null;
    private String mTargetUrl = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);

        Intent intent = getIntent();
        if (intent != null) {
            mTargetUrl = intent.getStringExtra("target_url");
            mTargetSessionId = intent.getStringExtra("session_id");
            if (mTargetSessionId == null) mTargetSessionId = intent.getStringExtra("session");
        }

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

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null) {
            mTargetUrl = intent.getStringExtra("target_url");
            String sid = intent.getStringExtra("session_id");
            if (sid == null || sid.isEmpty()) {
                sid = intent.getStringExtra("session");
            }
            mTargetSessionId = sid;
        } else {
            mTargetUrl = null;
            mTargetSessionId = null;
        }
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
        // 1. Root backdrop
        SharedPreferences sp = getSharedPreferences("agent_auth_prefs", Context.MODE_PRIVATE);
        boolean isTranslucent = sp.getBoolean("enable_translucent_theme", true);
        int winBg = isTranslucent ? Color.TRANSPARENT : Color.parseColor("#151517");

        mRootLayout = new FrameLayout(this);
        mRootLayout.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        mRootLayout.setBackgroundColor(winBg);

        // Dynamically handle soft keyboard (IME) insets in edge-to-edge mode
        mRootLayout.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int bottom = 0;
                if (insets != null) {
                    try {
                        // API 30+ WindowInsets.Type.ime()
                        Class<?> typeClass = Class.forName("android.view.WindowInsets$Type");
                        java.lang.reflect.Method imeMethod = typeClass.getMethod("ime");
                        int imeType = ((Integer) imeMethod.invoke(null)).intValue();
                        java.lang.reflect.Method getInsetsMethod = WindowInsets.class.getMethod("getInsets", int.class);
                        Object insetsObj = getInsetsMethod.invoke(insets, Integer.valueOf(imeType));
                        if (insetsObj != null) {
                            java.lang.reflect.Field bottomField = insetsObj.getClass().getField("bottom");
                            bottom = bottomField.getInt(insetsObj);
                        }
                    } catch (Throwable ignored) {
                        bottom = insets.getSystemWindowInsetBottom();
                    }
                    mIsKeyboardShowing = (bottom > 200);
                    v.setPadding(0, 0, 0, bottom);
                }
                return insets;
            }
        });

        // 2. Full-screen Floating Card
        mCard = new LinearLayout(this);
        mCard.setOrientation(LinearLayout.VERTICAL);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        );
        mCard.setLayoutParams(cardLp);
        mCard.setClickable(false);

        // Card background
        mCard.setBackgroundColor(winBg);

        // 3. Loading Progress Bar
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
        mWebView.setBackgroundColor(winBg);
        mWebView.setAlpha(0f); // Hidden initially to eliminate flash, smoothly faded in after overlay injection

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            ws.setMediaPlaybackRequiresUserGesture(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // Allow internal SPA page navigation and redirects without opening external browser
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Log.d(TAG, "shouldOverrideUrlLoading: " + url);
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                Log.i(TAG, "onPageStarted: " + url);
                SharedPreferences spTheme = getSharedPreferences("agent_auth_prefs", Context.MODE_PRIVATE);
                boolean isTrans = spTheme.getBoolean("enable_translucent_theme", true);
                if (isTrans) {
                    view.evaluateJavascript(
                        "try{" +
                        "  document.documentElement.setAttribute('data-dsh-overlay','true');" +
                        "  window.__DSH_OVERLAY__=true;" +
                        "  if(!document.getElementById('dsh-early-boot-hide')){" +
                        "    var s=document.createElement('style');" +
                        "    s.id='dsh-early-boot-hide';" +
                        "    s.textContent='html[data-dsh-overlay=\"true\"],body{background:transparent!important}[class*=\"_boot\"]{background:transparent!important}[class*=\"_boot\"] [class*=\"_card\"]{display:none!important}';" +
                        "    (document.head||document.documentElement).appendChild(s);" +
                        "  }" +
                        "}catch(e){}", null);
                } else {
                    view.evaluateJavascript(
                        "try{" +
                        "  document.documentElement.removeAttribute('data-dsh-overlay');" +
                        "  window.__DSH_OVERLAY__=false;" +
                        "  var st=document.getElementById('dsh-early-boot-hide');if(st)st.remove();" +
                        "}catch(e){}", null);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.i(TAG, "onPageFinished: " + url);
                view.clearHistory();
                injectMobileOverlay(view);

                // Diagnostic: inspect document title and body child count
                view.evaluateJavascript("(function(){ return 'title=' + document.title + ' | bodyChildren=' + (document.body ? document.body.children.length : -1) + ' | url=' + location.href; })()", new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String val) {
                        Log.i(TAG, "Page DOM probe: " + val);
                    }
                });
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.e(TAG, "onReceivedError: code=" + errorCode + " desc=" + description + " url=" + failingUrl);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                int status = errorResponse != null ? errorResponse.getStatusCode() : -1;
                String reqUrl = request != null && request.getUrl() != null ? request.getUrl().toString() : "";
                Log.e(TAG, "onReceivedHttpError: status=" + status + " url=" + reqUrl);
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                Log.i("DSHWebConsole", "[" + consoleMessage.messageLevel() + "] " + consoleMessage.message() + " (" + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber() + ")");
                return true;
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                Log.d(TAG, "onProgressChanged: " + newProgress);
                if (mProgressBar != null) {
                    if (newProgress < 100) {
                        mProgressBar.setVisibility(View.VISIBLE);
                        mProgressBar.setProgress(newProgress);
                    } else {
                        mProgressBar.setVisibility(View.GONE);
                    }
                }
                if (newProgress >= 50) {
                    injectMobileOverlay(view);
                }
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                Log.i(TAG, "onPermissionRequest for: " + java.util.Arrays.toString(request.getResources()));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        boolean needsAudio = false;
                        for (String res : request.getResources()) {
                            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                                needsAudio = true;
                                break;
                            }
                        }

                        if (needsAudio) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                    mPendingPermissionRequest = request;
                                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_PERMISSIONS);
                                    return;
                                }
                            }
                        }

                        try {
                            request.grant(request.getResources());
                            Log.i(TAG, "PermissionRequest granted successfully");
                        } catch (Throwable t) {
                            Log.e(TAG, "Error granting PermissionRequest: " + t.getMessage(), t);
                        }
                    }
                });
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                Log.w(TAG, "onPermissionRequestCanceled");
                if (mPendingPermissionRequest == request) {
                    mPendingPermissionRequest = null;
                }
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (mFilePathCallback != null) {
                    mFilePathCallback.onReceiveValue(null);
                    mFilePathCallback = null;
                }
                mFilePathCallback = filePathCallback;

                try {
                    Intent intent = null;
                    if (fileChooserParams != null) {
                        intent = fileChooserParams.createIntent();
                    }
                    if (intent == null) {
                        intent = new Intent(Intent.ACTION_GET_CONTENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                    }
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), REQUEST_CODE_FILE_CHOOSER);
                    return true;
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to start file chooser", t);
                    if (mFilePathCallback != null) {
                        mFilePathCallback.onReceiveValue(null);
                        mFilePathCallback = null;
                    }
                    return false;
                }
            }
        });
    }

    private static volatile byte[] sCachedOverlayCssBytes = null;
    private static volatile byte[] sCachedOverlayJsBytes = null;

    private synchronized byte[] getOverlayCssBytes() {
        if (sCachedOverlayCssBytes != null) return sCachedOverlayCssBytes;
        sCachedOverlayCssBytes = loadAssetBytes("overlay_style.css");
        return sCachedOverlayCssBytes;
    }

    private synchronized byte[] getOverlayJsBytes() {
        if (sCachedOverlayJsBytes != null) return sCachedOverlayJsBytes;
        sCachedOverlayJsBytes = loadAssetBytes("overlay_script.js");
        return sCachedOverlayJsBytes;
    }

    private byte[] loadAssetBytes(String filename) {
        try {
            InputStream is = getAssets().open(filename);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            is.close();
            return baos.toByteArray();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load asset " + filename + ": " + t.getMessage(), t);
            return new byte[0];
        }
    }

    private void injectMobileOverlay(WebView view) {
        if (view == null) return;
        try {
            SharedPreferences sp = getSharedPreferences("agent_auth_prefs", Context.MODE_PRIVATE);
            boolean enableTranslucent = sp.getBoolean("enable_translucent_theme", true);
            boolean enableWhale = sp.getBoolean("enable_floating_whale", true);
            boolean enableKbAssist = sp.getBoolean("enable_keyboard_assist", true);

            byte[] cssBytes = enableTranslucent ? getOverlayCssBytes() : new byte[0];
            byte[] jsBytes = getOverlayJsBytes();

            String cssBase64 = (cssBytes != null && cssBytes.length > 0) ? Base64.encodeToString(cssBytes, Base64.NO_WRAP) : "";
            String jsBase64 = (jsBytes != null && jsBytes.length > 0) ? Base64.encodeToString(jsBytes, Base64.NO_WRAP) : "";

            StringBuilder sb = new StringBuilder();
            sb.append("(function() {");
            sb.append("  try {");
            sb.append("    window.__DSH_MOBILE_CONFIG__ = {");
            sb.append("      enableTranslucent: ").append(enableTranslucent).append(",");
            sb.append("      enableWhale: ").append(enableWhale).append(",");
            sb.append("      enableKeyboardAssist: ").append(enableKbAssist);
            sb.append("    };");
            if (enableTranslucent) {
                sb.append("    document.documentElement.setAttribute('data-dsh-overlay', 'true');");
                sb.append("    window.__DSH_OVERLAY__ = true;");
                if (!cssBase64.isEmpty()) {
                    sb.append("    if (!document.getElementById('dsh-overlay-injected-style')) {");
                    sb.append("      var s = document.createElement('style');");
                    sb.append("      s.id = 'dsh-overlay-injected-style';");
                    sb.append("      s.textContent = decodeURIComponent(escape(atob('").append(cssBase64).append("')));");
                    sb.append("      (document.head || document.documentElement).appendChild(s);");
                    sb.append("    }");
                }
            } else {
                sb.append("    document.documentElement.removeAttribute('data-dsh-overlay');");
                sb.append("    window.__DSH_OVERLAY__ = false;");
                sb.append("    var st = document.getElementById('dsh-overlay-injected-style'); if (st) st.remove();");
                sb.append("    var eb = document.getElementById('dsh-early-boot-hide'); if (eb) eb.remove();");
            }
            if (!jsBase64.isEmpty()) {
                sb.append("    if (!window.__DSH_MOBILE_OVERLAY_INITIALIZED__) {");
                sb.append("      var code = decodeURIComponent(escape(atob('").append(jsBase64).append("')));");
                sb.append("      var sc = document.createElement('script');");
                sb.append("      sc.id = 'dsh-overlay-injected-script';");
                sb.append("      sc.textContent = code;");
                sb.append("      (document.head || document.documentElement).appendChild(sc);");
                sb.append("    } else if (typeof window.__DSH_UPDATE_MOBILE_CONFIG__ === 'function') {");
                sb.append("      window.__DSH_UPDATE_MOBILE_CONFIG__(window.__DSH_MOBILE_CONFIG__);");
                sb.append("    }");
            }
            sb.append("  } catch(e) { console.error('[overlay-inject] error:', e); }");
            sb.append("})();");

            view.evaluateJavascript(sb.toString(), null);

            // Smooth fade-in once styles and controls are injected
            if (mWebView != null && mWebView.getAlpha() < 1f) {
                mWebView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mWebView != null && mWebView.getAlpha() < 1f) {
                            mWebView.animate()
                                    .alpha(1f)
                                    .setDuration(240)
                                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                    .start();
                        }
                    }
                });
            }
        } catch (Throwable t) {
            Log.e(TAG, "injectMobileOverlay error: " + t.getMessage(), t);
        }
    }

    private void loadWebConsole() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String cookie = getDshAuthCookie(DemoDialogActivity.this);

                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mWebView != null) {
                            String url;
                            if (mTargetUrl != null && !mTargetUrl.isEmpty()) {
                                url = mTargetUrl;
                            } else {
                                url = DSH_WEB_URL;
                                if (mTargetSessionId != null && !mTargetSessionId.isEmpty()) {
                                    url = DSH_WEB_URL + "&session=" + mTargetSessionId;
                                }
                            }
                            if (cookie != null && !cookie.isEmpty() && url.contains("3080")) {
                                try {
                                    CookieManager cm = CookieManager.getInstance();
                                    cm.setAcceptCookie(true);
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        cm.setAcceptThirdPartyCookies(mWebView, true);
                                    }
                                    cm.setCookie("http://127.0.0.1:3080/", cookie + "; Path=/; Max-Age=2505600");
                                    cm.flush();
                                    Log.i(TAG, "DSH auth cookie injected successfully into WebView");
                                } catch (Throwable t) {
                                    Log.e(TAG, "Failed to setup auth cookie: " + t.getMessage(), t);
                                }
                            }
                            Log.i(TAG, "Navigating WebView to: " + url);
                            mWebView.loadUrl(url);
                        }
                    }
                });
            }
        }).start();
    }

    public static String getDshAuthCookie(Context context) {
        try {
            String secret = "";

            // 1. Try querying local gateway http://127.0.0.1:3070/api/auth/secret
            try {
                java.net.URL url = new java.net.URL("http://127.0.0.1:3070/api/auth/secret");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(400);
                conn.setReadTimeout(400);
                if (conn.getResponseCode() == 200) {
                    java.io.InputStream is = conn.getInputStream();
                    byte[] buf = new byte[1024];
                    int n = is.read(buf);
                    is.close();
                    if (n > 0) {
                        JSONObject json = new JSONObject(new String(buf, 0, n, "UTF-8"));
                        String s = json.optString("secret", "").trim();
                        if (!s.isEmpty()) {
                            secret = s;
                            if (context != null) {
                                context.getSharedPreferences("agent_auth_prefs", Context.MODE_PRIVATE)
                                        .edit().putString("dsh_secret", secret).apply();
                            }
                        }
                    }
                }
                conn.disconnect();
            } catch (Throwable ignored) {}

            // 2. Try /data/local/tmp/.dsh_secret (dynamically synced by vd_server or DSH)
            if (secret.isEmpty()) {
                try {
                    File tmpFile = new File("/data/local/tmp/.dsh_secret");
                    if (tmpFile.exists() && tmpFile.length() > 0) {
                        FileInputStream fis = new FileInputStream(tmpFile);
                        byte[] buf = new byte[(int) tmpFile.length()];
                        int read = fis.read(buf);
                        fis.close();
                        if (read > 0) {
                            String s = new String(buf, 0, read, "UTF-8").trim();
                            if (!s.isEmpty()) secret = s;
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // 3. Try /storage/emulated/0/workspace/.dsh_secret (user override file)
            if (secret.isEmpty()) {
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
            }

            // 4. Try SharedPreferences cache
            if (secret.isEmpty() && context != null) {
                try {
                    String s = context.getSharedPreferences("agent_auth_prefs", Context.MODE_PRIVATE)
                            .getString("dsh_secret", "");
                    if (s != null && !s.trim().isEmpty()) {
                        secret = s.trim();
                    }
                } catch (Throwable ignored) {}
            }

            if (secret.isEmpty()) {
                Log.w(TAG, "No DSH auth secret found; loading without pre-authenticated session cookie");
                return null;
            }

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

    public static String getDshAuthCookie() {
        return getDshAuthCookie(null);
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
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (mPendingPermissionRequest != null) {
                boolean granted = grantResults.length > 0;
                for (int res : grantResults) {
                    if (res != PackageManager.PERMISSION_GRANTED) {
                        granted = false;
                        break;
                    }
                }
                if (granted) {
                    try {
                        mPendingPermissionRequest.grant(mPendingPermissionRequest.getResources());
                        Log.i(TAG, "Granted pending permission request after user approval");
                    } catch (Throwable t) {
                        Log.e(TAG, "Error granting permission after result: " + t.getMessage(), t);
                    }
                } else {
                    try {
                        mPendingPermissionRequest.deny();
                        Log.w(TAG, "Denied pending permission request after user rejection");
                    } catch (Throwable t) {
                        Log.e(TAG, "Error denying permission: " + t.getMessage(), t);
                    }
                }
                mPendingPermissionRequest = null;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_FILE_CHOOSER) {
            if (mFilePathCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    try {
                        results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                    } catch (Throwable ignored) {}
                    if (results == null && data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    } else if (results == null && data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    }
                }
                mFilePathCallback.onReceiveValue(results);
                mFilePathCallback = null;
            }
        }
    }

    @Override
    public void onBackPressed() {
        hideSoftInput();
        finish();
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
        hideSoftInput();
        sIsForeground = true;
        reportViewState(true, sCurrentViewingSessionId);
        try {
            SharedPreferences sp = getSharedPreferences("agent_auth_prefs", Context.MODE_PRIVATE);
            boolean isTranslucent = sp.getBoolean("enable_translucent_theme", true);
            int winBg = isTranslucent ? Color.TRANSPARENT : Color.parseColor("#151517");
            if (mRootLayout != null) mRootLayout.setBackgroundColor(winBg);
            if (mCard != null) mCard.setBackgroundColor(winBg);
            if (mWebView != null) {
                mWebView.setBackgroundColor(winBg);
                mWebView.clearFocus();
                mWebView.onResume();
                injectMobileOverlay(mWebView);
            }
        } catch (Throwable ignored) {
            if (mWebView != null) {
                mWebView.clearFocus();
                mWebView.onResume();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        overridePendingTransition(0, 0);
        sIsForeground = false;
        reportViewState(false, sCurrentViewingSessionId);
        if (mWebView != null) {
            mWebView.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mPendingPermissionRequest != null) {
            try {
                mPendingPermissionRequest.deny();
            } catch (Throwable ignored) {}
            mPendingPermissionRequest = null;
        }
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
