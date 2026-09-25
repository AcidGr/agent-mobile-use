package com.agent.mobileuse;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

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
    private PermissionRequest mPendingPermissionRequest;
    private String mTargetSessionId = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);

        Intent intent = getIntent();
        if (intent != null) {
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
            String sid = intent.getStringExtra("session_id");
            if (sid == null || sid.isEmpty()) {
                sid = intent.getStringExtra("session");
            }
            if (sid != null && !sid.isEmpty()) {
                final String targetSid = sid;
                mTargetSessionId = targetSid;
                if (mWebView != null) {
                    mWebView.post(new Runnable() {
                        @Override
                        public void run() {
                            Log.i(TAG, "onNewIntent switching session to: " + targetSid);
                            mWebView.evaluateJavascript("window.DSH_SWITCH_SESSION && window.DSH_SWITCH_SESSION('" + targetSid + "');", null);
                        }
                    });
                }
            }
        }
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

        // Fully transparent card background
        mCard.setBackgroundColor(Color.TRANSPARENT);

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

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                String js = "(function() {" +
                    "  if (window.__DSH_SESSION_OBSERVER_INSTALLED__) return;" +
                    "  window.__DSH_SESSION_OBSERVER_INSTALLED__ = true;" +
                    "  window.DSH_SWITCH_SESSION = function(id) {" +
                    "    try {" +
                    "      localStorage.setItem('dsh.sessions.current', JSON.stringify({ sessionId: id }));" +
                    "      if (location.search.indexOf('session=') >= 0) {" +
                    "        location.search = location.search.replace(/session=[^&]+/, 'session=' + id);" +
                    "      } else {" +
                    "        location.search += (location.search ? '&' : '?') + 'session=' + id;" +
                    "      }" +
                    "    } catch(e) {}" +
                    "  };" +
                    "  function check() {" +
                    "    try {" +
                    "      var raw = localStorage.getItem('dsh.sessions.current');" +
                    "      if (raw) {" +
                    "        var obj = JSON.parse(raw);" +
                    "        if (obj && obj.sessionId && window.DSHOverlayBridge && window.DSHOverlayBridge.reportSession) {" +
                    "          window.DSHOverlayBridge.reportSession(obj.sessionId);" +
                    "        }" +
                    "      }" +
                    "    } catch(e) {}" +
                    "  }" +
                    "  check();" +
                    "  setInterval(check, 1000);" +
                    "})();";
                view.evaluateJavascript(js, null);
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
                Log.d("DSHWebConsole", consoleMessage.message() + " -- Line " + consoleMessage.lineNumber() + " of " + consoleMessage.sourceId());
                return true;
            }

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
                            String url = DSH_WEB_URL;
                            if (mTargetSessionId != null && !mTargetSessionId.isEmpty()) {
                                url = DSH_WEB_URL + "&session=" + mTargetSessionId;
                            }
                            Log.i(TAG, "Navigating WebView to: " + url);
                            mWebView.loadUrl(url);
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
        hideSoftInput();
        sIsForeground = true;
        reportViewState(true, sCurrentViewingSessionId);
        if (mWebView != null) {
            mWebView.clearFocus();
            mWebView.onResume();
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
