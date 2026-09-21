package com.agent.mobileuse;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookEntry implements IXposedHookLoadPackage {
    public static final String ACTION_GLOW_START = "com.agent.mobileuse.ACTION_GLOW_START";
    public static final String ACTION_GLOW_STOP = "com.agent.mobileuse.ACTION_GLOW_STOP";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 1. Hook system_server (android / system)
        if ("android".equals(lpparam.packageName) || "system".equals(lpparam.packageName)) {
            hookSystemServer(lpparam);
            return;
        }

        // 2. Hook SystemUI for Edge Glow Overlay
        if ("com.android.systemui".equals(lpparam.packageName)) {
            hookSystemUI(lpparam);
        }
    }

    private void hookSystemServer(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log("[AgentMobileUseHook] System server loaded: " + lpparam.packageName);
        ClassLoader cl = lpparam.classLoader;

        // Force allow hosting tasks on virtual display
        hookAllMethodsReturningTrue(cl, "android.view.Display", "canHostTasks");
        hookAllMethodsReturningTrue(cl, "com.android.server.wm.LogicalDisplay", "canHostTasksLocked");
        hookAllMethodsReturningTrue(cl, "com.android.server.wm.ActivityTaskSupervisor", "isCallerAllowedToLaunchOnDisplay");
        hookAllMethodsReturningTrue(cl, "com.android.server.wm.ActivityTaskSupervisor", "isCallerAllowedToLaunchOnTaskDisplayArea");
        hookAllMethodsReturningTrue(cl, "com.android.server.wm.ActivityTaskSupervisor", "canPlaceEntityOnDisplay");
        hookAllMethodsReturningTrue(cl, "com.android.server.wm.ActivityRecord", "canBeLaunchedOnDisplay");
        hookAllMethodsReturningTrue(cl, "com.android.server.wm.Task", "canBeLaunchedOnDisplay");
        hookAllMethodsReturningTrue(cl, "com.android.server.wm.RootWindowContainer", "canLaunchOnDisplay");
        hookAllMethodsReturningTrue(cl, "com.android.server.display.DisplayManagerService", "validatePackageName");

        // Isolate InputMethodManagerService (IME) to prevent soft keyboard popup on Display 0
        hookImmsDisplayIsolation(cl);

        // Intercept Action Button on OnePlus 13 (ColorOS) to launch DemoDialogActivity
        hookActionButton(cl);
    }

    private void hookSystemUI(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log("[AgentMobileUseHook] SystemUI loaded. Setting up Edge Glow receiver...");
        final ClassLoader cl = lpparam.classLoader;

        try {
            // Hook Application#onCreate to get Context
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                cl,
                "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        final Context context = (Context) param.thisObject;
                        if (context == null) return;

                        android.util.Log.i("AgentMobileUseHook", "SystemUI Application onCreate hooked! Registering glow receiver...");

                        IntentFilter filter = new IntentFilter();
                        filter.addAction(ACTION_GLOW_START);
                        filter.addAction(ACTION_GLOW_STOP);

                        // BroadcastReceiver for glow control
                        BroadcastReceiver receiver = new BroadcastReceiver() {
                            @Override
                            public void onReceive(Context ctx, Intent intent) {
                                String action = intent.getAction();
                                android.util.Log.i("AgentMobileUseHook", "Received glow broadcast: " + action);
                                if (ACTION_GLOW_START.equals(action)) {
                                    EdgeGlowController.getInstance(ctx).showGlow();
                                } else if (ACTION_GLOW_STOP.equals(action)) {
                                    EdgeGlowController.getInstance(ctx).hideGlow();
                                }
                            }
                        };

                        // Register receiver (Android 14+ RECEIVER_EXPORTED = 2 via reflection)
                        try {
                            java.lang.reflect.Method regMethod = Context.class.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class, int.class);
                            regMethod.invoke(context, receiver, filter, 2);
                        } catch (Throwable t) {
                            context.registerReceiver(receiver, filter);
                        }

                        android.util.Log.i("AgentMobileUseHook", "Edge Glow receiver registered successfully in SystemUI!");
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log("[AgentMobileUseHook] Failed to hook SystemUI: " + t.getMessage());
        }
    }

    private void hookAllMethodsReturningTrue(ClassLoader cl, String className, String methodName) {
        try {
            Class<?> clazz = XposedHelpers.findClass(className, cl);
            XposedBridge.hookAllMethods(clazz, methodName, XC_MethodReplacement.returnConstant(Boolean.TRUE));
        } catch (Throwable t) {
            XposedBridge.log("[AgentMobileUseHook] Failed to hook " + className + "#" + methodName + ": " + t.getMessage());
        }
    }

    private void hookImmsDisplayIsolation(ClassLoader cl) {
        try {
            Class<?> imms = XposedHelpers.findClass("com.android.server.inputmethod.InputMethodManagerService", cl);
            XposedBridge.hookAllMethods(imms, "computeImeDisplayIdForTarget", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    int displayId = (int) param.args[0];
                    if (displayId != 0) {
                        param.setResult(displayId);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[AgentMobileUseHook] Failed to hook IMMS: " + t.getMessage());
        }
    }

    private void hookActionButton(ClassLoader cl) {
        try {
            Class<?> strategyClass = XposedHelpers.findClass("com.android.server.policy.StrategyActionButtonKeyLaunchApp", cl);
            XposedBridge.hookAllMethods(strategyClass, "interceptActionKeyDown", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    android.util.Log.i("AgentMobileUseHook", "Action Button key down intercepted!");
                    Context ctx = null;
                    try {
                        Class<?> cur = param.thisObject.getClass();
                        while (cur != null && ctx == null) {
                            try {
                                java.lang.reflect.Field f = cur.getDeclaredField("mContext");
                                f.setAccessible(true);
                                ctx = (Context) f.get(param.thisObject);
                            } catch (NoSuchFieldException ignored) {
                                cur = cur.getSuperclass();
                            }
                        }
                    } catch (Throwable t) {
                        android.util.Log.w("AgentMobileUseHook", "Could not get mContext via reflection: " + t.getMessage());
                    }

                    if (ctx != null) {
                        try {
                            Intent intent = new Intent();
                            intent.setClassName("com.agent.mobileuse", "com.agent.mobileuse.DemoDialogActivity");
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            ctx.startActivity(intent);
                            android.util.Log.i("AgentMobileUseHook", "DemoDialogActivity launched successfully from Action Button!");
                        } catch (Throwable t) {
                            android.util.Log.e("AgentMobileUseHook", "Failed to launch DemoDialogActivity: " + t.getMessage(), t);
                        }
                    }
                    param.setResult(null);
                }
            });

            XposedBridge.hookAllMethods(strategyClass, "interceptActionKeyUp", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(null);
                }
            });

            XposedBridge.log("[AgentMobileUseHook] StrategyActionButtonKeyLaunchApp hooked successfully!");
        } catch (Throwable t) {
            XposedBridge.log("[AgentMobileUseHook] Failed to hook StrategyActionButtonKeyLaunchApp: " + t.getMessage());
        }
    }
}
