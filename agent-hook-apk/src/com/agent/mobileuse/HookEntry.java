package com.agent.mobileuse;

import android.content.Context;
import android.content.Intent;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // Hook system_server (android / system)
        if ("android".equals(lpparam.packageName) || "system".equals(lpparam.packageName)) {
            hookSystemServer(lpparam);
        }
        // Hook SystemUI (ColorOS Fluid Cloud)
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

    private static Object invokeNoArg(Object obj, String methodName) {
        if (obj == null) return null;
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod(methodName);
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isValidAgentLiveAlert(Object sbn) {
        if (sbn == null) return false;
        try {
            String pkg = (String) invokeNoArg(sbn, "getPackageName");
            if (!"com.agent.mobileuse".equals(pkg)) return false;

            Integer id = (Integer) invokeNoArg(sbn, "getId");
            if (id == null || id == 0) return false;

            // Only whitelist our designated Fluid Cloud notification IDs:
            // 10086: GlowService (Capsule: "运行中", "后台接管", "前台接管")
            // 2020:  NotifyReceiver (Task completed card)
            // 20086: QuestionReceiver (Interactive question card)
            if (id != 10086 && id != 2020 && id != 20086) return false;

            Object notif = invokeNoArg(sbn, "getNotification");
            if (notif instanceof android.app.Notification) {
                android.app.Notification n = (android.app.Notification) notif;
                // Exclude Android system AutoGroupSummary notifications (FLAG_GROUP_SUMMARY = 0x200)
                if ((n.flags & 0x00000200) != 0) return false;
                if (n.extras != null) {
                    CharSequence title = n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE);
                    if (title == null || title.toString().trim().isEmpty()) return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void hookSystemUI(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log("[AgentMobileUseHook] SystemUI loaded: " + lpparam.packageName);
        ClassLoader cl = lpparam.classLoader;

        // 1. Hook OplusLiveAlertFilters.shouldFilter to allow designated com.agent.mobileuse notifications
        try {
            Class<?> filtersClass = XposedHelpers.findClass(
                "com.oplus.systemui.statusbar.notification.livealert.data.repository.OplusLiveAlertFilters",
                cl
            );
            XposedBridge.hookAllMethods(filtersClass, "shouldFilter", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                        Object entry = param.args[0];
                        try {
                            Object sbn = invokeNoArg(entry, "getSbn");
                            if (isValidAgentLiveAlert(sbn)) {
                                param.setResult(Boolean.TRUE);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            });
            XposedBridge.log("[AgentMobileUseHook] OplusLiveAlertFilters.shouldFilter hooked with whitelist filtering!");
        } catch (Throwable t) {
            XposedBridge.log("[AgentMobileUseHook] Failed to hook OplusLiveAlertFilters: " + t.getMessage());
        }

        // 2. Hook OplusLiveAlertFilterByPlugin.shouldFilter (Double insurance)
        try {
            Class<?> pluginFilterClass = XposedHelpers.findClass(
                "com.oplus.systemui.statusbar.notification.livealert.data.repository.OplusLiveAlertFilterByPlugin",
                cl
            );
            XposedBridge.hookAllMethods(pluginFilterClass, "shouldFilter", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                        Object entry = param.args[0];
                        try {
                            Object sbn = invokeNoArg(entry, "getSbn");
                            if (isValidAgentLiveAlert(sbn)) {
                                param.setResult(Boolean.TRUE);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            });
            XposedBridge.log("[AgentMobileUseHook] OplusLiveAlertFilterByPlugin.shouldFilter hooked with whitelist filtering!");
        } catch (Throwable t) {
            XposedBridge.log("[AgentMobileUseHook] Failed to hook OplusLiveAlertFilterByPlugin: " + t.getMessage());
        }
    }
}
