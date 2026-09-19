package com.agent;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * READ-ONLY accessibility signal probe for agent-mobile-use.
 *
 * This class performs NO input injection of any kind: no tap, no swipe, no key
 * event, no text injection. It only connects UiAutomation, reads the window /
 * node tree, and prints the raw signals so we can decide which ones are worth
 * exposing to the model.
 *
 * Usage: app_process /system/bin com.agent.ProbeMain <displayId>
 */
public class ProbeMain {

    /** One collected node plus the ancestor link we need for click-target resolution. */
    static class Row {
        int id;
        int depth;
        String type = "";
        String text = "";
        String desc = "";
        String viewId = "";

        // raw signals
        boolean isClickable;      // AccessibilityNodeInfo.isClickable()
        boolean isLongClickable;  // AccessibilityNodeInfo.isLongClickable()
        boolean actionClick;      // ACTION_CLICK present in getActionList()
        boolean actionLongClick;  // ACTION_LONG_CLICK present in getActionList()
        int actionCount;

        boolean enabled = true;
        boolean focusable;
        boolean focused;
        boolean checkable;
        boolean checked;
        boolean scrollable;
        boolean visibleToUser;
        boolean visCheckThrew;

        int left, top, right, bottom;
        int centerX, centerY;

        int winIndex = -1;
        String winType = "?";
        boolean winActive;
        boolean winFocused;
        int winLayer = -1;

        int ancestorId = -1;   // nearest ancestor that can take a click
        int ancestorArea = 0;
        String ancestorText = "";
    }

    /** id -> 1 if that node can take a click, else 0. Small, bounded by node count. */
    static final java.util.HashMap<Integer, Integer> clickableById = new java.util.HashMap<Integer, Integer>();
    static int idCounter = 0;
    static int emitted = 0;

    /** Prints one collected node immediately: nothing accumulates in memory. */
    private static void emit(Row r) {
        StringBuilder sb = new StringBuilder();
        sb.append("id=").append(r.id);
        sb.append(" d=").append(r.depth);
        sb.append(" win=").append(r.winIndex).append(":").append(r.winType)
          .append(r.winActive ? "/act" : "").append(r.winFocused ? "/foc" : "")
          .append(" L").append(r.winLayer);
        sb.append(" | ").append(r.type);
        sb.append(" | b=[").append(r.left).append(",").append(r.top)
          .append(",").append(r.right).append(",").append(r.bottom).append("]");
        sb.append(" ctr=[").append(r.centerX).append(",").append(r.centerY).append("]");

        // ---- the signals under test ----
        sb.append(" || clickable=").append(r.isClickable ? 1 : 0);
        sb.append(" longClickable=").append(r.isLongClickable ? 1 : 0);
        sb.append(" ACT_CLICK=").append(r.actionClick ? 1 : 0);
        sb.append(" ACT_LONG=").append(r.actionLongClick ? 1 : 0);
        sb.append(" nAct=").append(r.actionCount);

        sb.append(" en=").append(r.enabled ? 1 : 0);
        sb.append(" foc=").append(r.focusable ? 1 : 0);
        sb.append(r.focused ? "(F)" : "");
        sb.append(" chk=").append(r.checkable ? 1 : 0);
        sb.append(r.checked ? "(C)" : "");
        sb.append(" scr=").append(r.scrollable ? 1 : 0);
        sb.append(" vis=").append(r.visCheckThrew ? "ERR" : (r.visibleToUser ? "1" : "0"));

        if (r.ancestorId >= 0) {
            sb.append(" anc=").append(r.ancestorId).append("(x").append(areaRatio(r)).append(")");
        } else {
            sb.append(" anc=-");
        }

        if (r.viewId.length() > 0) sb.append(" vid=").append(r.viewId);
        if (r.text.length() > 0) sb.append(" T=").append(oneLine(r.text));
        if (r.desc.length() > 0) sb.append(" D=").append(oneLine(r.desc));
        System.out.println(sb.toString());
        System.out.flush();
        emitted++;
    }

    /** Nearest clickable ancestor's area, read back from the compact map. */
    private static int ancestorAreaOf(int id) {
        return id;
    }

    public static void main(String[] args) {
        System.out.println("PROBE start args=" + args.length);
        System.out.flush();
        // app_process has no main Looper until one is prepared. UiAutomation's
        // connect() reaches AccessibilityInteractionClient, whose constructor does
        // new Handler(Looper.getMainLooper()); without a main Looper that throws on
        // a background thread and RuntimeInit kills the process (exit 137).
        try {
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        } catch (Throwable t) {
            System.out.println("PROBE prepareMainLooper failed: " + t);
        }
        int targetDisplayId = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        HandlerThread ht = null;
        Object uiAutomation = null;
        try {
            ht = new HandlerThread("ProbeThread");
            ht.start();

            Class<?> uacClass = Class.forName("android.app.UiAutomationConnection");
            Object uac = uacClass.getConstructor().newInstance();
            Class<?> uiClass = Class.forName("android.app.UiAutomation");
            Class<?> iuacClass = Class.forName("android.app.IUiAutomationConnection");
            uiAutomation = uiClass.getConstructor(Looper.class, iuacClass)
                    .newInstance(ht.getLooper(), uac);

            try {
                uiClass.getMethod("connect", int.class).invoke(uiAutomation, 0);
            } catch (NoSuchMethodException e) {
                uiClass.getMethod("connect").invoke(uiAutomation);
            }

            AccessibilityServiceInfo info = new AccessibilityServiceInfo();
            info.eventTypes = -1;
            info.feedbackType = 16;
            info.flags = 82; // FLAG_RETRIEVE_INTERACTIVE_WINDOWS | ... | FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            uiClass.getMethod("setServiceInfo", AccessibilityServiceInfo.class).invoke(uiAutomation, info);

            Thread.sleep(400);

            Method getWindows = uiClass.getMethod("getWindowsOnAllDisplays");
            System.out.println("PROBE connected, querying display " + targetDisplayId);
            System.out.flush();
            Object displays = getWindows.invoke(uiAutomation);
            System.out.println("PROBE got displays object=" + (displays != null));
            System.out.flush();

            int windowCount = 0;
            if (displays != null) {
                Class<?> saClass = displays.getClass();
                int size = (Integer) saClass.getMethod("size").invoke(displays);
                Method keyAt = saClass.getMethod("keyAt", int.class);
                Method valueAt = saClass.getMethod("valueAt", int.class);

                for (int i = 0; i < size; i++) {
                    int dId = (Integer) keyAt.invoke(displays, i);
                    if (dId != targetDisplayId) continue;
                    List<?> wins = (List<?>) valueAt.invoke(displays, i);
                    if (wins == null) continue;

                    for (Object win : wins) {
                        windowCount++;
                        String wType = str(call(win, "getType"));
                        int wLayer = intOf(call(win, "getLayer"), -1);
                        boolean wActive = boolOf(call(win, "isActive"), false);
                        boolean wFocused = boolOf(call(win, "isFocused"), false);

                        Object root = call(win, "getRoot");
                        if (root instanceof AccessibilityNodeInfo) {
                            collect((AccessibilityNodeInfo) root, 0, -1, 0,
                                    windowCount - 1, wType, wActive, wFocused, wLayer);
                        }
                    }
                }
            }

            System.out.println("PROBE done: emitted=" + emitted);
            System.out.flush();

        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            if (uiAutomation != null) {
                try { uiAutomation.getClass().getMethod("disconnect").invoke(uiAutomation); }
                catch (Throwable ignored) {}
            }
            if (ht != null) ht.quit();
        }
    }

    private static void collect(AccessibilityNodeInfo node, int depth,
                                int nearestClickableAncestor,
                                int nearestClickableAncestorArea,
                                int winIndex, String winType, boolean winActive,
                                boolean winFocused, int winLayer) {
        if (node == null) return;

        Row r = new Row();
        r.id = ++idCounter;
        r.depth = depth;
        r.winIndex = winIndex;
        r.winType = winType;
        r.winActive = winActive;
        r.winFocused = winFocused;
        r.winLayer = winLayer;

        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        CharSequence c = node.getClassName();
        r.text = t == null ? "" : t.toString();
        r.desc = d == null ? "" : d.toString();
        r.type = c == null ? "?" : simplify(c.toString());
        String vid = node.getViewIdResourceName();
        r.viewId = vid == null ? "" : vid;

        try { r.isClickable = node.isClickable(); } catch (Throwable ignored) {}
        try { r.isLongClickable = node.isLongClickable(); } catch (Throwable ignored) {}
        try { r.enabled = node.isEnabled(); } catch (Throwable ignored) {}
        try { r.focusable = node.isFocusable(); } catch (Throwable ignored) {}
        try { r.focused = node.isFocused(); } catch (Throwable ignored) {}
        try { r.checkable = node.isCheckable(); } catch (Throwable ignored) {}
        try { r.checked = node.isChecked(); } catch (Throwable ignored) {}
        try { r.scrollable = node.isScrollable(); } catch (Throwable ignored) {}
        try { r.visibleToUser = node.isVisibleToUser(); }
        catch (Throwable e) { r.visCheckThrew = true; }

        // Action list: the authoritatively "can this be actuated" signal.
        // Action ids are hard-coded constants (ACTION_CLICK=0x10, ACTION_LONG_CLICK=0x20)
        // so this does not depend on API-24+ static fields existing at runtime.
        try {
            List<?> actions = node.getActionList();
            if (actions != null) {
                r.actionCount = actions.size();
                for (Object a : actions) {
                    Object idObj = call(a, "getId");
                    int aid = intOf(idObj, -1);
                    if (aid == 0x10) r.actionClick = true;
                    if (aid == 0x20) r.actionLongClick = true;
                }
            }
        } catch (Throwable ignored) {}

        Rect b = new Rect();
        try { node.getBoundsInScreen(b); } catch (Throwable ignored) {}
        r.left = b.left; r.top = b.top; r.right = b.right; r.bottom = b.bottom;
        r.centerX = b.centerX(); r.centerY = b.centerY();

        // Resolve this node's click target: itself, or the nearest clickable ancestor.
        boolean selfCanClick = r.isClickable || r.actionClick;
        if (selfCanClick) {
            r.ancestorId = r.id;
        } else if (nearestClickableAncestor >= 0) {
            r.ancestorId = nearestClickableAncestor;
            r.ancestorArea = nearestClickableAncestorArea;
        }

        boolean hasSemantic = r.text.length() > 0 || r.desc.length() > 0;
        boolean kept = hasSemantic || selfCanClick || r.scrollable || r.checkable;
        if (kept) emit(r);

        int childAncestor = nearestClickableAncestor;
        int childAncestorArea = nearestClickableAncestorArea;
        if (selfCanClick) {
            childAncestor = r.id;
            childAncestorArea = Math.max(1, (r.right - r.left) * (r.bottom - r.top));
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = null;
            try { child = node.getChild(i); } catch (Throwable ignored) {}
            if (child != null) {
                collect(child, depth + 1, childAncestor, childAncestorArea, winIndex, winType,
                        winActive, winFocused, winLayer);
            }
        }
    }

    private static int areaRatio(Row r) {
        if (r.ancestorId == r.id) return 1;
        if (r.ancestorArea <= 0) return -1;
        int self = Math.max(1, (r.right - r.left) * (r.bottom - r.top));
        return Math.round((float) r.ancestorArea / self);
    }

    /** Reflective call; returns null instead of throwing. */
    private static Object call(Object target, String method) {
        try { return target.getClass().getMethod(method).invoke(target); }
        catch (Throwable t) { return null; }
    }

    private static String str(Object o) {
        return o == null ? "?" : String.valueOf(o);
    }

    private static int intOf(Object o, int def) {
        return (o instanceof Integer) ? (Integer) o : def;
    }

    private static boolean boolOf(Object o, boolean def) {
        return (o instanceof Boolean) ? (Boolean) o : def;
    }

    private static String oneLine(String s) {
        s = s.replace('\n', ' ').replace('\r', ' ').replace('|', '/');
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static String simplify(String className) {
        int i = className.lastIndexOf('.');
        return (i >= 0 && i < className.length() - 1) ? className.substring(i + 1) : className;
    }
}
