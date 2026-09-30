package com.agent.mobileuse;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class SettingsActivity extends Activity {
    private static final String TAG = "SettingsActivity";
    private static final String PREFS_AUTH = "agent_auth_prefs";
    private static final String PREFS_CAPSULE = "agent_capsule_state";
    private static final String KEY_SECRET = "dsh_secret";
    private static final String ALIAS_NAME = "com.agent.mobileuse.LauncherAlias";

    private Handler mHandler;
    private int mCurrentTab = 0;

    // Header Views
    private TextView mTvHeaderSubtitle;

    // Tab Icon Views
    private TabIconView mTabIcon0;
    private TabIconView mTabIcon1;
    private TabIconView mTabIcon2;

    // Content Containers
    private ScrollView mTab0Container;
    private ScrollView mTab1Container;
    private ScrollView mTab2Container;

    // Tab 0 Views (Basic Info & System Preferences)
    private TextView mTvStatusDshPort;
    private TextView mTvStatusDshVersion;
    private TextView mTvStatusGatewayPort;
    private TextView mTvStatusMode;
    private TextView mTvStatusLsposed;
    private Switch mSwitchFluidCloud;
    private Switch mSwitchCompletionAlert;
    private Switch mSwitchLauncherIcon;

    public static boolean isModuleActive() {
        return false;
    }

    // Tab 1 Views (DSH Core & Auth)
    private EditText mEtSecret;
    private Switch mSwitchTranslucentTheme;
    private Switch mSwitchFloatingWhale;
    private Switch mSwitchKeyboardAssist;

    // Tab 2 Views (Virtual Display & Automation)
    private TextView mTvDisplayResolution;
    private TextView mTvDisplayDpi;
    private TextView mTvDisplayId;
    private Switch mSwitchAudioMute;
    private Switch mSwitchGlowFrame;
    private Switch mSwitchAutoStandby;
    private boolean mAudioMuted = false;

    // Switch Listeners
    private final CompoundButton.OnCheckedChangeListener mFluidCloudChangeListener = new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            getSharedPreferences(PREFS_CAPSULE, Context.MODE_PRIVATE)
                    .edit().putBoolean("enable_fluid_cloud", isChecked).commit();
            try {
                Intent sIntent = new Intent(SettingsActivity.this, GlowService.class);
                sIntent.setAction("REFRESH");
                if (Build.VERSION.SDK_INT >= 26) {
                    try {
                        java.lang.reflect.Method m = Context.class.getMethod("startForegroundService", Intent.class);
                        m.invoke(SettingsActivity.this, sIntent);
                    } catch (Throwable t) {
                        startService(sIntent);
                    }
                } else {
                    startService(sIntent);
                }
            } catch (Throwable ignored) {}
            Toast.makeText(SettingsActivity.this, isChecked ? "通知流体云化已开启" : "通知流体云化已关闭 (退回原生通知栏)", Toast.LENGTH_SHORT).show();
        }
    };

    private final CompoundButton.OnCheckedChangeListener mCompletionAlertChangeListener = new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            getSharedPreferences(PREFS_CAPSULE, Context.MODE_PRIVATE)
                    .edit().putBoolean("enable_completion_alert", isChecked).commit();
        }
    };

    private final CompoundButton.OnCheckedChangeListener mGlowFrameChangeListener = new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            getSharedPreferences(PREFS_CAPSULE, Context.MODE_PRIVATE)
                    .edit().putBoolean("enable_glow_frame", isChecked).commit();
        }
    };

    private final CompoundButton.OnCheckedChangeListener mAutoStandbyChangeListener = new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            getSharedPreferences(PREFS_CAPSULE, Context.MODE_PRIVATE)
                    .edit().putBoolean("enable_auto_standby", isChecked).commit();
        }
    };

    private final CompoundButton.OnCheckedChangeListener mTranslucentThemeChangeListener = new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
                    .edit().putBoolean("enable_translucent_theme", isChecked).commit();
            Toast.makeText(SettingsActivity.this, isChecked ? "毛玻璃透明主题已开启" : "已恢复 DSH 原生纯黑实色主题", Toast.LENGTH_SHORT).show();
        }
    };

    private final CompoundButton.OnCheckedChangeListener mFloatingWhaleChangeListener = new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
                    .edit().putBoolean("enable_floating_whale", isChecked).commit();
        }
    };

    private final CompoundButton.OnCheckedChangeListener mKeyboardAssistChangeListener = new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
                    .edit().putBoolean("enable_keyboard_assist", isChecked).commit();
        }
    };

    private final CompoundButton.OnCheckedChangeListener mAudioMuteChangeListener = new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            toggleAudioMuteAsync(isChecked);
        }
    };

    private final CompoundButton.OnCheckedChangeListener mLauncherIconChangeListener = new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            setLauncherIconEnabled(isChecked);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHandler = new Handler(Looper.getMainLooper());
        initDarkWindow();
        initRootLayout();
        refreshStatusAsync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatusAsync();
        syncSwitchesState();
    }

    private void initDarkWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.parseColor("#0F1117"));
            getWindow().setNavigationBarColor(Color.parseColor("#141720"));
        }
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()
        );
    }

    private GradientDrawable makeCardDrawable() {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#181B24"));
        gd.setCornerRadius(dp(12));
        gd.setStroke(dp(1), Color.parseColor("#282D3D"));
        return gd;
    }

    private GradientDrawable makeButtonDrawable(String bgColor, String strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(bgColor));
        gd.setCornerRadius(dp(8));
        if (strokeColor != null) {
            gd.setStroke(dp(1), Color.parseColor(strokeColor));
        }
        return gd;
    }

    private void initRootLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.setBackgroundColor(Color.parseColor("#0F1117"));

        // 1. Sticky Header (Fixed top, perfectly aligned across all 3 pages)
        View headerView = createStickyHeader();
        root.addView(headerView);

        // 2. Center Content Area (FrameLayout containing 3 ScrollViews)
        FrameLayout contentArea = new FrameLayout(this);
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f
        );
        contentArea.setLayoutParams(contentLp);

        mTab0Container = createTab0Layout();
        mTab1Container = createTab1Layout();
        mTab2Container = createTab2Layout();

        contentArea.addView(mTab0Container);
        contentArea.addView(mTab1Container);
        contentArea.addView(mTab2Container);
        root.addView(contentArea);

        // 3. Bottom Navigation Bar (56dp, pure vector icons, NO text, NO emojis)
        View bottomBar = createBottomBar();
        root.addView(bottomBar);

        setContentView(root);
        selectTab(0);
    }

    private View createStickyHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(Color.parseColor("#12141C"));

        LinearLayout headerContent = new LinearLayout(this);
        headerContent.setOrientation(LinearLayout.HORIZONTAL);
        headerContent.setGravity(Gravity.CENTER_VERTICAL);
        headerContent.setPadding(dp(16), dp(10), dp(16), dp(10));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        titleCol.setLayoutParams(titleLp);

        TextView title = new TextView(this);
        title.setText("Agent Mobile 控制中心");
        title.setTextSize(15);
        title.setTextColor(Color.parseColor("#00D2FF"));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(title);

        mTvHeaderSubtitle = new TextView(this);
        mTvHeaderSubtitle.setText("基本信息 / 监控");
        mTvHeaderSubtitle.setTextSize(11);
        mTvHeaderSubtitle.setTextColor(Color.parseColor("#8B949E"));
        mTvHeaderSubtitle.setPadding(0, dp(1), 0, 0);
        titleCol.addView(mTvHeaderSubtitle);

        headerContent.addView(titleCol);

        Button btnRefresh = createStyledButton("刷新", "#162338", "#00A3C4");
        btnRefresh.setTextColor(Color.parseColor("#00D2FF"));
        btnRefresh.setTextSize(11);
        btnRefresh.setPadding(dp(10), dp(4), dp(10), dp(4));
        btnRefresh.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)
        ));
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshStatusAsync();
                Toast.makeText(SettingsActivity.this, "已刷新", Toast.LENGTH_SHORT).show();
            }
        });
        headerContent.addView(btnRefresh);

        header.addView(headerContent);

        // Hairline divider
        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#1F2433"));
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ));
        header.addView(divider);

        return header;
    }

    private View createBottomBar() {
        LinearLayout barWrapper = new LinearLayout(this);
        barWrapper.setOrientation(LinearLayout.VERTICAL);
        barWrapper.setBackgroundColor(Color.parseColor("#141720"));

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#1F2433"));
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ));
        barWrapper.addView(divider);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
        ));

        // Tab 0: Dashboard
        FrameLayout tab0 = createTabItem(0);
        mTabIcon0 = new TabIconView(this, 0);
        tab0.addView(mTabIcon0);
        bar.addView(tab0);

        // Tab 1: Terminal
        FrameLayout tab1 = createTabItem(1);
        mTabIcon1 = new TabIconView(this, 1);
        tab1.addView(mTabIcon1);
        bar.addView(tab1);

        // Tab 2: Display
        FrameLayout tab2 = createTabItem(2);
        mTabIcon2 = new TabIconView(this, 2);
        tab2.addView(mTabIcon2);
        bar.addView(tab2);

        barWrapper.addView(bar);
        return barWrapper;
    }

    private FrameLayout createTabItem(final int index) {
        FrameLayout item = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        item.setLayoutParams(lp);
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(index);
            }
        });
        return item;
    }

    private void selectTab(int index) {
        mCurrentTab = index;
        if (mTabIcon0 != null) mTabIcon0.setSelectedState(index == 0);
        if (mTabIcon1 != null) mTabIcon1.setSelectedState(index == 1);
        if (mTabIcon2 != null) mTabIcon2.setSelectedState(index == 2);

        if (mTab0Container != null) mTab0Container.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        if (mTab1Container != null) mTab1Container.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        if (mTab2Container != null) mTab2Container.setVisibility(index == 2 ? View.VISIBLE : View.GONE);

        if (mTvHeaderSubtitle != null) {
            if (index == 0) {
                mTvHeaderSubtitle.setText("基本信息 / 监控");
            } else if (index == 1) {
                mTvHeaderSubtitle.setText("DSH 设置 / 凭据");
            } else {
                mTvHeaderSubtitle.setText("副屏设置 / 环境");
            }
        }
    }

    // =========================================================================
    // Tab 0: 基本信息 (Status & System Preferences)
    // =========================================================================
    private ScrollView createTab0Layout() {
        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        sv.setFillViewport(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(12), dp(16), dp(20));

        // 1. 服务与网络监控
        layout.addView(createSectionHeader("服务与网络监控"));
        LinearLayout portsCard = createCardLayout();
        mTvStatusDshPort = createStatusRow(portsCard, "DSH 控制台", "[CHECKING] 3080");
        mTvStatusDshVersion = createStatusRow(portsCard, "DSH 版本", "[FETCHING...]");
        mTvStatusGatewayPort = createStatusRow(portsCard, "网关服务", "[CHECKING] 3070");
        mTvStatusMode = createStatusRow(portsCard, "运行模式", "IDLE (待机)");
        mTvStatusLsposed = createStatusRow(portsCard, "LSPosed 模块", "[CHECKING...]");
        layout.addView(portsCard);

        // 2. 系统特性偏好
        layout.addView(createSectionHeader("系统特性偏好"));
        LinearLayout prefsCard = createCardLayout();

        // Switch 1: 流体云化
        mSwitchFluidCloud = new Switch(this);
        LinearLayout rowFluid = createSwitchRow("通知流体云化", mSwitchFluidCloud);
        mSwitchFluidCloud.setOnCheckedChangeListener(mFluidCloudChangeListener);
        prefsCard.addView(rowFluid);
        prefsCard.addView(createCardDivider());

        // Switch 2: 任务完成强提醒
        mSwitchCompletionAlert = new Switch(this);
        LinearLayout rowAlert = createSwitchRow("任务完成提醒", mSwitchCompletionAlert);
        mSwitchCompletionAlert.setOnCheckedChangeListener(mCompletionAlertChangeListener);
        prefsCard.addView(rowAlert);
        prefsCard.addView(createCardDivider());

        // Switch 3: 桌面快捷图标
        mSwitchLauncherIcon = new Switch(this);
        LinearLayout rowLauncher = createSwitchRow("桌面图标快捷方式", mSwitchLauncherIcon);
        mSwitchLauncherIcon.setOnCheckedChangeListener(mLauncherIconChangeListener);
        prefsCard.addView(rowLauncher);

        layout.addView(prefsCard);

        // 3. 快速唤起
        layout.addView(createSectionHeader("快捷唤起"));
        LinearLayout actionCard = createCardLayout();
        Button btnOpenOverlay = createStyledButton("打开控制台", "#0F2836", "#00A3C4");
        btnOpenOverlay.setTextColor(Color.parseColor("#00D2FF"));
        btnOpenOverlay.setTextSize(12);
        btnOpenOverlay.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36)
        ));
        btnOpenOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(SettingsActivity.this, DemoDialogActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            }
        });
        actionCard.addView(btnOpenOverlay);
        layout.addView(actionCard);

        sv.addView(layout);
        return sv;
    }

    // =========================================================================
    // Tab 1: DSH 设置 (DSH Core & Authentication)
    // =========================================================================
    private ScrollView createTab1Layout() {
        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        sv.setFillViewport(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(12), dp(16), dp(20));

        // 1. 网关连接
        layout.addView(createSectionHeader("网关节点"));
        LinearLayout connCard = createCardLayout();
        createStatusRow(connCard, "网关节点", "http://127.0.0.1:3070");
        createStatusRow(connCard, "控制台节点", "http://127.0.0.1:3080");
        layout.addView(connCard);

        // 2. 通信密钥
        layout.addView(createSectionHeader("通信凭据密钥"));
        LinearLayout secretCard = createCardLayout();

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setPadding(0, dp(4), 0, dp(8));
        inputRow.setGravity(Gravity.CENTER_VERTICAL);

        mEtSecret = new EditText(this);
        mEtSecret.setTextSize(12);
        mEtSecret.setTextColor(Color.parseColor("#E6EDF3"));
        mEtSecret.setBackground(makeButtonDrawable("#12141C", "#282D3D"));
        mEtSecret.setPadding(dp(8), dp(6), dp(8), dp(6));
        mEtSecret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(0, dp(34), 1.0f);
        etLp.rightMargin = dp(6);
        mEtSecret.setLayoutParams(etLp);
        inputRow.addView(mEtSecret);

        final Button btnToggleMask = createStyledButton("显示", "#1E2230", "#30363D");
        btnToggleMask.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)
        ));
        btnToggleMask.setOnClickListener(new View.OnClickListener() {
            private boolean isMasked = true;
            @Override
            public void onClick(View v) {
                isMasked = !isMasked;
                if (isMasked) {
                    mEtSecret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    btnToggleMask.setText("显示");
                } else {
                    mEtSecret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                    btnToggleMask.setText("隐藏");
                }
                mEtSecret.setSelection(mEtSecret.getText().length());
            }
        });
        inputRow.addView(btnToggleMask);
        secretCard.addView(inputRow);

        Button btnSaveSecret = createStyledButton("保存并同步", "#00A3C4", null);
        btnSaveSecret.setTextColor(Color.parseColor("#FFFFFF"));
        btnSaveSecret.setTextSize(12);
        btnSaveSecret.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34)
        ));
        btnSaveSecret.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndSyncSecret(mEtSecret.getText().toString().trim());
            }
        });
        secretCard.addView(btnSaveSecret);

        layout.addView(secretCard);

        // 3. 控制台偏好
        layout.addView(createSectionHeader("控制台偏好"));
        LinearLayout clientPrefsCard = createCardLayout();

        mSwitchTranslucentTheme = new Switch(this);
        LinearLayout rowTranslucent = createSwitchRow("毛玻璃透明主题", mSwitchTranslucentTheme);
        mSwitchTranslucentTheme.setOnCheckedChangeListener(mTranslucentThemeChangeListener);
        clientPrefsCard.addView(rowTranslucent);
        clientPrefsCard.addView(createCardDivider());

        mSwitchFloatingWhale = new Switch(this);
        LinearLayout rowWhale = createSwitchRow("悬浮控制球与快捷条", mSwitchFloatingWhale);
        mSwitchFloatingWhale.setOnCheckedChangeListener(mFloatingWhaleChangeListener);
        clientPrefsCard.addView(rowWhale);
        clientPrefsCard.addView(createCardDivider());

        mSwitchKeyboardAssist = new Switch(this);
        LinearLayout rowKb = createSwitchRow("输入法自适应滚动", mSwitchKeyboardAssist);
        mSwitchKeyboardAssist.setOnCheckedChangeListener(mKeyboardAssistChangeListener);
        clientPrefsCard.addView(rowKb);

        layout.addView(clientPrefsCard);

        sv.addView(layout);
        return sv;
    }

    // =========================================================================
    // Tab 2: 虚拟副屏设置 (Virtual Display & Automation)
    // =========================================================================
    private ScrollView createTab2Layout() {
        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ));
        sv.setFillViewport(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(12), dp(16), dp(20));

        // 1. 硬件参数
        layout.addView(createSectionHeader("副屏参数"));
        LinearLayout vdCard = createCardLayout();
        mTvDisplayId = createStatusRow(vdCard, "Display ID", "-1 (待机)");
        mTvDisplayResolution = createStatusRow(vdCard, "分辨率", "1080 x 2400");
        mTvDisplayDpi = createStatusRow(vdCard, "像素密度", "480 DPI");
        layout.addView(vdCard);

        // 2. 自动化运行环境
        layout.addView(createSectionHeader("自动化环境"));
        LinearLayout envCard = createCardLayout();

        mSwitchAudioMute = new Switch(this);
        LinearLayout rowAudio = createSwitchRow("副屏自动化静音", mSwitchAudioMute);
        mSwitchAudioMute.setOnCheckedChangeListener(mAudioMuteChangeListener);
        envCard.addView(rowAudio);
        envCard.addView(createCardDivider());

        mSwitchGlowFrame = new Switch(this);
        LinearLayout rowGlow = createSwitchRow("前台接管呼吸光", mSwitchGlowFrame);
        mSwitchGlowFrame.setOnCheckedChangeListener(mGlowFrameChangeListener);
        envCard.addView(rowGlow);
        envCard.addView(createCardDivider());

        mSwitchAutoStandby = new Switch(this);
        LinearLayout rowStandby = createSwitchRow("完成后自动待机", mSwitchAutoStandby);
        mSwitchAutoStandby.setOnCheckedChangeListener(mAutoStandbyChangeListener);
        envCard.addView(rowStandby);

        layout.addView(envCard);

        // 3. 环境模式切换
        layout.addView(createSectionHeader("模式手动切换"));
        LinearLayout modeCard = createCardLayout();

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnIdle = createStyledButton("待机", "#162338", "#282D3D");
        LinearLayout.LayoutParams btnLp1 = new LinearLayout.LayoutParams(0, dp(32), 1.0f);
        btnLp1.rightMargin = dp(4);
        btnIdle.setLayoutParams(btnLp1);
        btnIdle.setTextSize(11);
        btnIdle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchModeAsync("idle");
            }
        });
        btnRow.addView(btnIdle);

        Button btnBg = createStyledButton("后台副屏", "#162338", "#282D3D");
        LinearLayout.LayoutParams btnLp2 = new LinearLayout.LayoutParams(0, dp(32), 1.0f);
        btnLp2.rightMargin = dp(4);
        btnBg.setLayoutParams(btnLp2);
        btnBg.setTextSize(11);
        btnBg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchModeAsync("background");
            }
        });
        btnRow.addView(btnBg);

        Button btnFg = createStyledButton("前台接管", "#162338", "#282D3D");
        LinearLayout.LayoutParams btnLp3 = new LinearLayout.LayoutParams(0, dp(32), 1.0f);
        btnFg.setLayoutParams(btnLp3);
        btnFg.setTextSize(11);
        btnFg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchModeAsync("foreground");
            }
        });
        btnRow.addView(btnFg);

        modeCard.addView(btnRow);
        layout.addView(modeCard);

        sv.addView(layout);
        return sv;
    }

    // =========================================================================
    // UI Helpers
    // =========================================================================
    private TextView createSectionHeader(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(11);
        tv.setTextColor(Color.parseColor("#00D2FF"));
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(2), dp(10), 0, dp(4));
        return tv;
    }

    private LinearLayout createCardLayout() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        card.setBackground(makeCardDrawable());
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        return card;
    }

    private View createCardDivider() {
        View v = new View(this);
        v.setBackgroundColor(Color.parseColor("#222736"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        );
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView createStatusRow(LinearLayout parent, String label, String defaultVal) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(2), 0, dp(2));

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label + ": ");
        tvLabel.setTextSize(12);
        tvLabel.setTextColor(Color.parseColor("#8B949E"));
        row.addView(tvLabel);

        TextView tvVal = new TextView(this);
        tvVal.setText(defaultVal);
        tvVal.setTextSize(12);
        tvVal.setTextColor(Color.parseColor("#E6EDF3"));
        row.addView(tvVal);

        parent.addView(row);
        return tvVal;
    }

    private LinearLayout createSwitchRow(String title, Switch sw) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        row.setPadding(0, dp(2), 0, dp(2));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(12);
        tvTitle.setTextColor(Color.parseColor("#E6EDF3"));
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        textLp.rightMargin = dp(8);
        tvTitle.setLayoutParams(textLp);

        row.addView(tvTitle);
        row.addView(sw);
        return row;
    }

    private Button createStyledButton(String text, String bgColor, String strokeColor) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(11);
        btn.setTextColor(Color.parseColor("#E6EDF3"));
        btn.setBackground(makeButtonDrawable(bgColor, strokeColor));
        btn.setPadding(dp(10), dp(4), dp(10), dp(4));
        btn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)
        ));
        return btn;
    }

    // =========================================================================
    // State Synchronization & Asynchronous Operations
    // =========================================================================
    private void setSwitchCheckedSilently(Switch sw, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        if (sw != null) {
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(checked);
            sw.setOnCheckedChangeListener(listener);
        }
    }

    private void syncSwitchesState() {
        SharedPreferences spCapsule = getSharedPreferences(PREFS_CAPSULE, Context.MODE_PRIVATE);
        setSwitchCheckedSilently(mSwitchFluidCloud, spCapsule.getBoolean("enable_fluid_cloud", true), mFluidCloudChangeListener);
        setSwitchCheckedSilently(mSwitchCompletionAlert, spCapsule.getBoolean("enable_completion_alert", true), mCompletionAlertChangeListener);
        setSwitchCheckedSilently(mSwitchGlowFrame, spCapsule.getBoolean("enable_glow_frame", true), mGlowFrameChangeListener);
        setSwitchCheckedSilently(mSwitchAutoStandby, spCapsule.getBoolean("enable_auto_standby", true), mAutoStandbyChangeListener);

        SharedPreferences spAuth = getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE);
        setSwitchCheckedSilently(mSwitchTranslucentTheme, spAuth.getBoolean("enable_translucent_theme", true), mTranslucentThemeChangeListener);
        setSwitchCheckedSilently(mSwitchFloatingWhale, spAuth.getBoolean("enable_floating_whale", true), mFloatingWhaleChangeListener);
        setSwitchCheckedSilently(mSwitchKeyboardAssist, spAuth.getBoolean("enable_keyboard_assist", true), mKeyboardAssistChangeListener);

        // Launcher Icon
        try {
            PackageManager pm = getPackageManager();
            ComponentName cn = new ComponentName(this, ALIAS_NAME);
            int state = pm.getComponentEnabledSetting(cn);
            boolean isEnabled = (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
            setSwitchCheckedSilently(mSwitchLauncherIcon, isEnabled, mLauncherIconChangeListener);
        } catch (Throwable ignored) {}
    }

    private void setLauncherIconEnabled(boolean enable) {
        try {
            PackageManager pm = getPackageManager();
            ComponentName cn = new ComponentName(this, ALIAS_NAME);
            int newState = enable
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            pm.setComponentEnabledSetting(cn, newState, PackageManager.DONT_KILL_APP);
            Toast.makeText(this, enable ? "桌面图标已启用" : "桌面图标已隐藏 (纯隐形模式)", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "设置桌面图标失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String getSavedSecret() {
        SharedPreferences prefs = getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE);
        String s = prefs.getString(KEY_SECRET, "").trim();
        if (!s.isEmpty()) return s;

        // Try /data/local/tmp/.dsh_secret
        try {
            File f = new File("/data/local/tmp/.dsh_secret");
            if (f.exists() && f.canRead()) {
                byte[] b = new byte[(int) f.length()];
                InputStream is = new java.io.FileInputStream(f);
                int r = is.read(b);
                is.close();
                if (r > 0) return new String(b, 0, r).trim();
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private void refreshStatusAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 1. Check 3080 port listening
                final boolean dshListening = isPort3080Listening();

                // 2. Fetch DSH version directly from 3080 RPC
                final String dshVersion = dshListening ? fetchDshVersionFrom3080() : "[OFFLINE]";

                // 3. Query 3070 daemon status
                final JSONObject status3070 = fetchGatewayStatusFrom3070();

                // 4. Query audio mute status
                final boolean audioMuted = fetchAudioMuteStatus();

                // 5. Query saved secret if EditText is empty
                final String savedSecret = getSavedSecret();

                // 6. Direct LSPosed self-hook probe
                final boolean lspDirect = isModuleActive();

                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        // Update 3080 Port status
                        if (mTvStatusDshPort != null) {
                            if (dshListening) {
                                mTvStatusDshPort.setText("[ONLINE] (3080)");
                                mTvStatusDshPort.setTextColor(Color.parseColor("#49E9A6"));
                            } else {
                                mTvStatusDshPort.setText("[OFFLINE]");
                                mTvStatusDshPort.setTextColor(Color.parseColor("#F85149"));
                            }
                        }

                        // Update DSH Version
                        if (mTvStatusDshVersion != null) {
                            mTvStatusDshVersion.setText(dshVersion);
                            if (dshVersion.startsWith("v")) {
                                mTvStatusDshVersion.setTextColor(Color.parseColor("#00D2FF"));
                            } else if (dshVersion.contains("AUTH")) {
                                mTvStatusDshVersion.setTextColor(Color.parseColor("#E3B341"));
                            } else {
                                mTvStatusDshVersion.setTextColor(Color.parseColor("#8B949E"));
                            }
                        }

                        // Update 3070 Gateway status
                        if (status3070 != null) {
                            int pid = status3070.optInt("pid", 0);
                            String mode = status3070.optString("mode", "idle").toUpperCase();
                            int did = status3070.optInt("display_id", -1);
                            int w = status3070.optInt("width", 1080);
                            int h = status3070.optInt("height", 2400);
                            int dpi = status3070.optInt("dpi", 480);

                            if (mTvStatusGatewayPort != null) {
                                mTvStatusGatewayPort.setText("[ONLINE] (PID: " + pid + ")");
                                mTvStatusGatewayPort.setTextColor(Color.parseColor("#49E9A6"));
                            }
                            if (mTvStatusMode != null) {
                                mTvStatusMode.setText(mode + ("IDLE".equals(mode) ? " (待机)" : ""));
                                mTvStatusMode.setTextColor(Color.parseColor("#00D2FF"));
                            }
                            if (mTvDisplayId != null) {
                                mTvDisplayId.setText(String.valueOf(did));
                            }
                            if (mTvDisplayResolution != null) {
                                mTvDisplayResolution.setText(w + " x " + h);
                            }
                            if (mTvDisplayDpi != null) {
                                mTvDisplayDpi.setText(dpi + " DPI");
                            }
                        } else {
                            if (mTvStatusGatewayPort != null) {
                                mTvStatusGatewayPort.setText("[OFFLINE]");
                                mTvStatusGatewayPort.setTextColor(Color.parseColor("#F85149"));
                            }
                        }

                        // Update LSPosed module status
                        if (mTvStatusLsposed != null) {
                            boolean lspActive = lspDirect || (status3070 != null && status3070.optBoolean("lsposed_active", false));
                            if (lspActive) {
                                mTvStatusLsposed.setText("[ACTIVE]");
                                mTvStatusLsposed.setTextColor(Color.parseColor("#49E9A6"));
                            } else {
                                mTvStatusLsposed.setText("[INACTIVE]");
                                mTvStatusLsposed.setTextColor(Color.parseColor("#F85149"));
                            }
                        }

                        // Audio mute switch
                        mAudioMuted = audioMuted;
                        setSwitchCheckedSilently(mSwitchAudioMute, audioMuted, mAudioMuteChangeListener);

                        // Secret field
                        if (mEtSecret != null && mEtSecret.getText().toString().isEmpty() && !savedSecret.isEmpty()) {
                            mEtSecret.setText(savedSecret);
                        }
                    }
                });
            }
        }).start();
    }

    private boolean isPort3080Listening() {
        try {
            URL url = new URL("http://127.0.0.1:3080/");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            int code = conn.getResponseCode();
            return code > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private String fetchDshVersionFrom3080() {
        try {
            String authority = "127.0.0.1:3080";
            String cookie = DemoDialogActivity.getDshAuthCookie(this);
            if (cookie == null || cookie.isEmpty()) {
                return "[AUTH REQUIRED]";
            }

            URL url = new URL("http://127.0.0.1:3080/api/pluginManager/listBundles");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("Host", authority);
            conn.setRequestProperty("Cookie", cookie);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            JSONObject reqBody = new JSONObject();
            reqBody.put("type", "client-request");
            reqBody.put("rpcId", "cfg-ver-" + System.currentTimeMillis());
            reqBody.put("method", "pluginManager/listBundles");
            reqBody.put("payload", new JSONObject().put("args", new JSONObject()));

            OutputStream os = conn.getOutputStream();
            os.write(reqBody.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code == 200) {
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int n;
                while ((n = is.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
                is.close();
                String respStr = baos.toString("UTF-8");
                JSONObject json = new JSONObject(respStr);
                JSONObject resObj = json.optJSONObject("result");
                if (resObj != null && resObj.optBoolean("ok")) {
                    JSONArray arr = resObj.optJSONArray("value");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject item = arr.getJSONObject(i);
                            String name = item.optString("name", "");
                            if ("@deepseek-ai/dsh-base".equals(name) || name.startsWith("@deepseek-ai/dsh")) {
                                String ver = item.optString("version", "");
                                if (!ver.isEmpty()) {
                                    return "v" + ver;
                                }
                            }
                        }
                    }
                }
            } else if (code == 401 || code == 403) {
                return "[AUTH REQUIRED]";
            }
        } catch (java.net.ConnectException ce) {
            return "[OFFLINE]";
        } catch (Throwable t) {
            Log.w(TAG, "fetchDshVersion error: " + t.getMessage());
        }
        return "[UNAVAILABLE]";
    }

    private JSONObject fetchGatewayStatusFrom3070() {
        try {
            URL url = new URL("http://127.0.0.1:3070/api/status");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            if (code == 200) {
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = is.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
                is.close();
                return new JSONObject(baos.toString("UTF-8"));
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean fetchAudioMuteStatus() {
        try {
            URL url = new URL("http://127.0.0.1:3070/api/audio/status");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            if (conn.getResponseCode() == 200) {
                InputStream is = conn.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[512];
                int n;
                while ((n = is.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
                is.close();
                JSONObject json = new JSONObject(baos.toString("UTF-8"));
                return json.optBoolean("enabled", false);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void toggleAudioMuteAsync(final boolean targetMute) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL("http://127.0.0.1:3070/api/audio/toggle");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    JSONObject p = new JSONObject();
                    p.put("enabled", targetMute);
                    OutputStream os = conn.getOutputStream();
                    os.write(p.toString().getBytes("UTF-8"));
                    os.flush();
                    os.close();
                    conn.getResponseCode();
                } catch (Throwable t) {
                    Log.w(TAG, "toggleAudio error: " + t.getMessage());
                }
            }
        }).start();
    }

    private void switchModeAsync(final String mode) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL("http://127.0.0.1:3070/api/mode?mode=" + mode);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);
                    conn.getResponseCode();
                } catch (Throwable ignored) {}
                refreshStatusAsync();
            }
        }).start();
    }

    private void saveAndSyncSecret(final String newSecret) {
        if (newSecret.isEmpty()) {
            Toast.makeText(this, "密钥不能为空", Toast.LENGTH_SHORT).show();
            return;
        }

        // 1. Save to SharedPreferences
        getSharedPreferences(PREFS_AUTH, Context.MODE_PRIVATE)
                .edit().putString(KEY_SECRET, newSecret).apply();

        // 2. Persist to /data/local/tmp/.dsh_secret
        try {
            FileOutputStream fos = new FileOutputStream("/data/local/tmp/.dsh_secret");
            fos.write(newSecret.getBytes("UTF-8"));
            fos.flush();
            fos.close();
        } catch (Throwable ignored) {}

        // 3. POST to local gateway http://127.0.0.1:3070/api/auth/secret
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean synced = false;
                try {
                    URL url = new URL("http://127.0.0.1:3070/api/auth/secret");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);

                    JSONObject body = new JSONObject();
                    body.put("secret", newSecret);
                    OutputStream os = conn.getOutputStream();
                    os.write(body.toString().getBytes("UTF-8"));
                    os.flush();
                    os.close();

                    int code = conn.getResponseCode();
                    synced = (code == 200);
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to POST secret to gateway: " + t.getMessage());
                }

                final boolean success = synced;
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (success) {
                            Toast.makeText(SettingsActivity.this, "密钥已保存并同步至网关", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(SettingsActivity.this, "密钥已本地保存 (网关同步稍后重试)", Toast.LENGTH_SHORT).show();
                        }
                        refreshStatusAsync();
                    }
                });
            }
        }).start();
    }

    // =========================================================================
    // Custom Pure-Vector Canvas Tab Icon View (Zero Emoji, Resolution-Independent)
    // =========================================================================
    public static class TabIconView extends View {
        private final int mType; // 0: Dashboard, 1: Terminal, 2: Display
        private boolean mSelected = false;
        private Paint mStrokePaint;
        private Paint mFillPaint;

        public TabIconView(Context context, int type) {
            super(context);
            mType = type;
            init();
        }

        private void init() {
            mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mStrokePaint.setStyle(Paint.Style.STROKE);
            mStrokePaint.setStrokeCap(Paint.Cap.ROUND);
            mStrokePaint.setStrokeJoin(Paint.Join.ROUND);

            mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mFillPaint.setStyle(Paint.Style.FILL);
        }

        public void setSelectedState(boolean selected) {
            if (mSelected != selected) {
                mSelected = selected;
                invalidate();
            }
        }

        private float dp(float v) {
            return TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()
            );
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            float cx = w / 2.0f;
            float cy = h / 2.0f - dp(2.0f);

            int color = mSelected ? Color.parseColor("#00D2FF") : Color.parseColor("#505A69");
            mStrokePaint.setColor(color);
            mStrokePaint.setStrokeWidth(dp(1.8f));
            mFillPaint.setColor(color);

            float s = dp(10.0f);

            if (mType == 0) {
                // Tab 0: Dashboard / Gauge Icon
                RectF rect = new RectF(cx - s, cy - s, cx + s, cy + s);
                canvas.drawArc(rect, 140, 260, false, mStrokePaint);
                // Center pivot dot
                canvas.drawCircle(cx, cy, dp(1.8f), mFillPaint);
                // Needle pointing up-right
                float nx = cx + (float) (s * 0.65f * Math.cos(Math.toRadians(-40)));
                float ny = cy + (float) (s * 0.65f * Math.sin(Math.toRadians(-40)));
                canvas.drawLine(cx, cy, nx, ny, mStrokePaint);
            } else if (mType == 1) {
                // Tab 1: Terminal / Code Prompt (>_) Icon
                RectF rect = new RectF(cx - s * 1.15f, cy - s * 0.85f, cx + s * 1.15f, cy + s * 0.85f);
                canvas.drawRoundRect(rect, dp(3), dp(3), mStrokePaint);
                // '>' prompt
                float px = cx - s * 0.6f;
                float py = cy;
                Path prompt = new Path();
                prompt.moveTo(px, py - dp(3.5f));
                prompt.lineTo(px + dp(3.5f), py);
                prompt.lineTo(px, py + dp(3.5f));
                canvas.drawPath(prompt, mStrokePaint);
                // '_' underscore line
                float ux = px + dp(5.5f);
                canvas.drawLine(ux, py + dp(3.5f), ux + dp(4.5f), py + dp(3.5f), mStrokePaint);
            } else if (mType == 2) {
                // Tab 2: Display / Dual Virtual Screens Icon
                // Back screen
                RectF backRect = new RectF(cx - s * 0.6f, cy - s * 1.0f, cx + s * 1.1f, cy + s * 0.2f);
                canvas.drawRoundRect(backRect, dp(2), dp(2), mStrokePaint);
                // Front screen
                RectF frontRect = new RectF(cx - s * 1.0f, cy - s * 0.4f, cx + s * 0.6f, cy + s * 0.7f);
                canvas.drawRoundRect(frontRect, dp(2.5f), dp(2.5f), mStrokePaint);
                // Base stand
                float fx = cx - s * 0.2f;
                canvas.drawLine(fx, cy + s * 0.7f, fx, cy + s * 0.95f, mStrokePaint);
                canvas.drawLine(fx - dp(4.0f), cy + s * 0.95f, fx + dp(4.0f), cy + s * 0.95f, mStrokePaint);
            }

            // Glowing Indicator Dot when Selected
            if (mSelected) {
                canvas.drawCircle(cx, h - dp(7.0f), dp(2.0f), mFillPaint);
            }
        }
    }
}
