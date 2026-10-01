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
        // Hook self (SettingsActivity activation check)
        if ("com.agent.mobileuse".equals(lpparam.packageName)) {
            hookSelf(lpparam);
        }
    }

    private void hookSelf(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> clazz = XposedHelpers.findClass("com.agent.mobileuse.SettingsActivity", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(clazz, "isModuleActive", XC_MethodReplacement.returnConstant(Boolean.TRUE));
            XposedBridge.log("[AgentMobileUseHook] isModuleActive hooked successfully!");
        } catch (Throwable t) {
            XposedBridge.log("[AgentMobileUseHook] Failed to hook isModuleActive: " + t.getMessage());
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

        // Screenshot exclusion must be applied from system_server. Doing this from the
        // ordinary app process is blocked by hidden-API / WindowManager privilege checks.
        hookAgentScreenshotExclusion(cl);

        // Reclassify only the accessibility representation of our overlay. The real
        // WindowState remains an ordinary Activity window, so UI/focus/touch semantics
        // are unchanged while accessibility keeps the application underneath introspectable.
        hookAgentAccessibilityOverlayClassification(cl);

        // Intercept Action Button on OnePlus 13 (ColorOS) to launch DemoDialogActivity
        hookActionButton(cl);
    }

    private static Object getFieldRecursive(Object obj, String fieldName) {
        if (obj == null) return null;
        Class<?> cur = obj.getClass();
        while (cur != null) {
            try {
                java.lang.reflect.Field f = cur.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                cur = cur.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static boolean isAgentWindowState(Object win) {
        if (win == null) return false;
        try {
            Object attrsObj = getFieldRecursive(win, "mAttrs");
            if (attrsObj instanceof android.view.WindowManager.LayoutParams) {
                android.view.WindowManager.LayoutParams attrs =
                        (android.view.WindowManager.LayoutParams) attrsObj;
                if ("com.agent.mobileuse".equals(attrs.packageName)) return true;
                CharSequence title = attrs.getTitle();
                if (title != null) {
                    String s = title.toString();
                    if (s.contains("com.agent.mobileuse") || "AgentMobileEdgeGlow".equals(s)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            Object pkg = invokeNoArg(win, "getOwningPackage");
            if (pkg != null && "com.agent.mobileuse".equals(pkg.toString())) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static Object extractSurfaceControl(Object result, Object animator) {
        try {
            Class<?> scClass = Class.forName("android.view.SurfaceControl");
            if (result != null && scClass.isInstance(result)) return result;

            Object sc = getFieldRecursive(result, "mSurfaceControl");
            if (sc != null && scClass.isInstance(sc)) return sc;

            sc = getFieldRecursive(animator, "mSurfaceControl");
            if (sc != null && scClass.isInstance(sc)) return sc;

            Object controller = getFieldRecursive(animator, "mSurfaceController");
            sc = getFieldRecursive(controller, "mSurfaceControl");
            if (sc != null && scClass.isInstance(sc)) return sc;
        } catch (Throwable ignored) {}
        return null;
    }

    private static void markSkipScreenshotFromSystemServer(Object surfaceControl) throws Throwable {
        if (surfaceControl == null) return;
        Class<?> scClass = Class.forName("android.view.SurfaceControl");
        Class<?> txClass = Class.forName("android.view.SurfaceControl$Transaction");
        Object tx = txClass.getConstructor().newInstance();

        java.lang.reflect.Method setSkip;
        try {
            setSkip = txClass.getMethod("setSkipScreenshot", scClass, boolean.class);
        } catch (NoSuchMethodException e) {
            setSkip = txClass.getDeclaredMethod("setSkipScreenshot", scClass, boolean.class);
            setSkip.setAccessible(true);
        }
        setSkip.invoke(tx, surfaceControl, true);
        txClass.getMethod("apply").invoke(tx);
        try {
            txClass.getMethod("close").invoke(tx);
        } catch (Throwable ignored) {}
    }

    private void hookAgentScreenshotExclusion(ClassLoader cl) {
        try {
            Class<?> animatorClass = XposedHelpers.findClass(
                    "com.android.server.wm.WindowStateAnimator", cl);
            XposedBridge.hookAllMethods(animatorClass, "createSurfaceLocked",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object win = getFieldRecursive(param.thisObject, "mWin");
                    if (!isAgentWindowState(win)) return;

                    Object sc = extractSurfaceControl(param.getResult(), param.thisObject);
                    if (sc == null) {
                        XposedBridge.log("[AgentMobileUseHook] Agent window surface created but SurfaceControl was not found");
                        return;
                    }

                    try {
                        markSkipScreenshotFromSystemServer(sc);
                        XposedBridge.log("[AgentMobileUseHook] SKIP_SCREENSHOT applied to agent overlay surface");
                    } catch (Throwable t) {
                        XposedBridge.log("[AgentMobileUseHook] Failed to apply SKIP_SCREENSHOT: " + t);
                    }
                }
            });
            XposedBridge.log("[AgentMobileUseHook] WindowStateAnimator screenshot exclusion hook installed");
        } catch (Throwable t) {
            XposedBridge.log("[AgentMobileUseHook] Failed to hook screenshot exclusion: " + t);
        }
    }

    private static boolean setFieldRecursive(Object obj, String fieldName, Object value) {
        if (obj == null) return false;
        Class<?> cur = obj.getClass();
        while (cur != null) {
            try {
                java.lang.reflect.Field f = cur.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(obj, value);
                return true;
            } catch (NoSuchFieldException e) {
                cur = cur.getSuperclass();
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    private static Object getWindowStateFromAccessibilityWindow(Object service, Object a11yWindow) {
        if (service == null || a11yWindow == null) return null;
        try {
            Object token = getFieldRecursive(a11yWindow, "mWindow");
            if (token == null) return null;
            Object map = getFieldRecursive(service, "mWindowMap");
            if (map instanceof java.util.Map) {
                return ((java.util.Map<?, ?>) map).get(token);
            }
            if (map != null) {
                java.lang.reflect.Method get = map.getClass().getMethod("get", Object.class);
                return get.invoke(map, token);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isAgentAccessibilityWindow(Object service, Object a11yWindow) {
        Object win = getWindowStateFromAccessibilityWindow(service, a11yWindow);
        if (isAgentWindowState(win)) return true;

        // Fallback for OEM builds where mWindowMap is wrapped or renamed.
        try {
            Object windowInfo = getFieldRecursive(a11yWindow, "mWindowInfo");
            Object title = getFieldRecursive(windowInfo, "title");
            if (title != null) {
                String s = title.toString();
                if (s.contains("com.agent.mobileuse") || "AgentMobileEdgeGlow".equals(s)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void reclassifyAccessibilityWindow(Object a11yWindow) {
        final int typeAccessibilityOverlay =
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;

        // AccessibilityWindowManager consumes AccessibilityWindow#getType() while
        // computing unaccounted screen space. Changing only this cached a11y type
        // makes our overlay transparent to that computation without touching mAttrs.
        setFieldRecursive(a11yWindow, "mType", Integer.valueOf(typeAccessibilityOverlay));

        // Keep the WindowInfo handed to AccessibilityManager consistent so clients see
        // TYPE_ACCESSIBILITY_OVERLAY rather than TYPE_APPLICATION for this one window.
        Object windowInfo = getFieldRecursive(a11yWindow, "mWindowInfo");
        setFieldRecursive(windowInfo, "type", Integer.valueOf(typeAccessibilityOverlay));
    }

    private void hookAgentAccessibilityOverlayClassification(ClassLoader cl) {
        try {
            Class<?> a11yWindowClass = XposedHelpers.findClass(
                    "com.android.server.wm.AccessibilityWindowsPopulator$AccessibilityWindow", cl);

            XposedBridge.hookAllMethods(a11yWindowClass, "initializeData",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object a11yWindow = param.getResult();
                    if (a11yWindow == null || param.args == null || param.args.length == 0) {
                        return;
                    }

                    Object windowManagerService = param.args[0];
                    if (!isAgentAccessibilityWindow(windowManagerService, a11yWindow)) {
                        return;
                    }

                    reclassifyAccessibilityWindow(a11yWindow);
                    XposedBridge.log(
                            "[AgentMobileUseHook] Agent window reclassified as TYPE_ACCESSIBILITY_OVERLAY for accessibility only");
                }
            });

            XposedBridge.log(
                    "[AgentMobileUseHook] AccessibilityWindowsPopulator overlay classification hook installed");
        } catch (Throwable t) {
            XposedBridge.log(
                    "[AgentMobileUseHook] Failed to hook accessibility overlay classification: " + t);
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
                    // Check user personalization switch: Fluid Cloud conversion
                    if (!n.extras.getBoolean("enable_fluid_cloud", true)) {
                        return false;
                    }
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
                            if (sbn != null) {
                                String pkg = (String) invokeNoArg(sbn, "getPackageName");
                                if ("com.agent.mobileuse".equals(pkg)) {
                                    if (isValidAgentLiveAlert(sbn)) {
                                        param.setResult(Boolean.TRUE);
                                    } else {
                                        param.setResult(Boolean.FALSE);
                                    }
                                }
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
                            if (sbn != null) {
                                String pkg = (String) invokeNoArg(sbn, "getPackageName");
                                if ("com.agent.mobileuse".equals(pkg)) {
                                    if (isValidAgentLiveAlert(sbn)) {
                                        param.setResult(Boolean.TRUE);
                                    } else {
                                        param.setResult(Boolean.FALSE);
                                    }
                                }
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
