package com.agent;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Host-side UI tool for agent-mobile-use.
 *
 * Commands:
 *   tree|dump <displayId>   Read-only accessibility dump (JSON envelope; see dumpTree)
 *   type <displayId> <text> Inject text into the focused field via clipboard + PASTE
 *
 * NOTE ON THE MAIN LOOPER (do not remove):
 * app_process starts a process with no main Looper. UiAutomation.connect() reaches
 * AccessibilityInteractionClient, whose constructor runs new Handler(Looper.getMainLooper());
 * on a background thread with no main Looper that throws, and RuntimeInit then kills the
 * whole process (observed as exit 137 / "Killed", with an uncaught-exception stack in logcat).
 * Preparing the main looper up front is what keeps this tool alive.
 */
public class ToolMain {

    /** Fields kept per node. The wire format is written by hand in dumpTree(). */
    public static class NodeItem {
        public int id;
        public int depth;
        public String type;
        public String text;
        public String desc;
        public String viewId;
        public int left, top, right, bottom;
        public int centerX, centerY;

        // ---- raw signals ----
        public boolean clickable;   // isClickable(): the only click signal worth emitting
                                    // (measured identical to ACTION_CLICK across 115 nodes / 3 apps)
        public boolean editableFlag;
        public boolean enabled;
        public boolean visibleToUser;
        public boolean focusable;
        public boolean focused;
        public boolean checkable;
        public boolean checked;
        public boolean scrollable;

        // ---- resolved click target ----
        public int targetId = -1;      // self if clickable, else nearest clickable ancestor
        public int targetCenterX, targetCenterY;
        public int targetRatio = 1;    // ancestor area / self area; 1 when target is self
    }

    /** A clickable node offered as an ancestor target to non-clickable children. */
    static class Candidate {
        int id;
        int left, top, right, bottom;
        int centerX, centerY;
        int area;

        static Candidate of(NodeItem n) {
            Candidate c = new Candidate();
            c.id = n.id;
            c.left = n.left; c.top = n.top; c.right = n.right; c.bottom = n.bottom;
            c.centerX = n.centerX; c.centerY = n.centerY;
            c.area = Math.max(1, (n.right - n.left) * (n.bottom - n.top));
            return c;
        }
    }

    /**
     * Hard caps. The DSH tool-result pruner truncates results above 8192 chars
     * (head 4096 + tail 1024), which would cut the JSON mid-array and hand the model a
     * corrupt, silently incomplete node list. Capping here keeps the payload valid and
     * lets us report truncation honestly instead.
     */
    private static final int MAX_NODES = 140;
    private static final int MAX_NODES_CHARS = 6200;

    /** Inherit an ancestor's click target only when the ancestor is not far bigger. */
    private static final int MAX_ANCESTOR_RATIO = 5;

    public static void main(String[] args) {
        try {
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper();
            }
        } catch (Throwable t) {
            // ignore if already prepared
        }
        if (args.length < 1) {
            printUsage();
            return;
        }
        String cmd = args[0];
        if ("tree".equals(cmd) || "dump".equals(cmd)) {
            int displayId = args.length > 1 ? Integer.parseInt(args[1]) : 0;
            dumpTree(displayId);
        } else if ("type".equals(cmd)) {
            if (args.length < 3) {
                System.err.println("Usage: type <displayId> <text>");
                return;
            }
            int displayId = Integer.parseInt(args[1]);
            injectType(displayId, args[2]);
        } else {
            printUsage();
        }
    }

    private static void printUsage() {
        System.out.println("Usage: ToolMain <tree|type> [args...]");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read-only dump
    // ─────────────────────────────────────────────────────────────────────────

    private static void dumpTree(int targetDisplayId) {
        HandlerThread ht = null;
        Object uiAutomation = null;
        try {
            ht = new HandlerThread("UiToolThread");
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
            // FLAG_RETRIEVE_INTERACTIVE_WINDOWS | FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            // | FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            info.flags = 82;
            uiClass.getMethod("setServiceInfo", AccessibilityServiceInfo.class).invoke(uiAutomation, info);

            Thread.sleep(400);

            // Display geometry: an observation without it leaves the model unable to judge
            // whether a coordinate is even inside the screen.
            int[] size = queryDisplaySize(targetDisplayId);
            int dispW = size[0];
            int dispH = size[1];

            List<NodeItem> list = new ArrayList<NodeItem>();
            int windowCount = 0;
            int winIndex = 0;

            Object displays = uiClass.getMethod("getWindowsOnAllDisplays").invoke(uiAutomation);
            if (displays != null) {
                Class<?> saClass = displays.getClass();
                int sizeN = (Integer) saClass.getMethod("size").invoke(displays);
                Method keyAt = saClass.getMethod("keyAt", int.class);
                Method valueAt = saClass.getMethod("valueAt", int.class);

                for (int i = 0; i < sizeN; i++) {
                    int dId = (Integer) keyAt.invoke(displays, i);
                    if (dId != targetDisplayId) continue;
                    List<?> wins = (List<?>) valueAt.invoke(displays, i);
                    if (wins == null) continue;
                    for (Object win : wins) {
                        windowCount++;
                        Method getRootMethod = win.getClass().getMethod("getRoot");
                        Object rootObj = getRootMethod.invoke(win);
                        if (rootObj instanceof AccessibilityNodeInfo) {
                            int[] idCounter = new int[] { 1 };
                            collectInteractiveNodes((AccessibilityNodeInfo) rootObj, 0,
                                    null, list, idCounter, winIndex);
                        }
                        winIndex++;
                    }
                }
            }

            emitEnvelope(targetDisplayId, dispW, dispH, windowCount, list);

        } catch (Throwable t) {
            // Never die silently: emit a valid envelope so the caller can tell the
            // difference between "empty screen" and "the dump failed".
            StringBuilder sb = new StringBuilder();
            sb.append("{\"error\":\"").append(escapeJson(String.valueOf(t))).append("\"}");
            System.out.print(sb.toString());
        } finally {
            if (uiAutomation != null) {
                try {
                    uiAutomation.getClass().getMethod("disconnect").invoke(uiAutomation);
                } catch (Throwable ignored) {}
            }
            if (ht != null) {
                ht.quit();
            }
        }
    }

    /**
     * Real display size for the target display. Reflection only, so this cannot fail the
     * whole dump on an OEM build that hides these APIs.
     */
    private static int[] queryDisplaySize(int displayId) {
        // A DisplayManager instance gives the reliable answer, but app_process has no
        // Context. Try, in order: the instrumentation application, then the app-globals
        // application, then the process's own DisplayManagerGlobal.
        Object dm = null;
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app == null) {
                app = Class.forName("android.app.AppGlobals")
                        .getMethod("getInitialApplication").invoke(null);
            }
            if (app != null) {
                dm = Class.forName("android.content.Context")
                        .getMethod("getSystemService", String.class).invoke(app, "display");
            }
        } catch (Throwable ignored) {}

        try {
            if (dm == null) {
                Object global = Class.forName("android.hardware.display.DisplayManagerGlobal")
                        .getMethod("getInstance").invoke(null);
                dm = Class.forName("android.hardware.display.DisplayManager")
                        .getConstructor(Class.forName("android.hardware.display.DisplayManagerGlobal"))
                        .newInstance(global);
            }
            if (dm == null) return new int[] { 0, 0 };

            Object display = Class.forName("android.hardware.display.DisplayManager")
                    .getMethod("getDisplay", int.class).invoke(dm, displayId);
            if (display == null) return new int[] { 0, 0 };

            Class<?> displayClass = Class.forName("android.view.Display");
            Class<?> pointClass = Class.forName("android.graphics.Point");
            Object point = pointClass.getConstructor().newInstance();
            try {
                displayClass.getMethod("getRealSize", pointClass).invoke(display, point);
            } catch (NoSuchMethodException e) {
                displayClass.getMethod("getSize", pointClass).invoke(display, point);
            }
            int w = (Integer) pointClass.getField("x").get(point);
            int h = (Integer) pointClass.getField("y").get(point);
            return new int[] { w, h };
        } catch (Throwable t) {
            return new int[] { 0, 0 };
        }
    }

    /**
     * Serialize the observation.
     *
     * Envelope carries display geometry, window count and truncation state, so the model can
     * tell apart: screen is genuinely empty / accessibility tree suppressed / output clipped /
     * dump failed. Each node carries only raw signals plus a resolved click target.
     */
    private static void emitEnvelope(int displayId, int dispW, int dispH,
                                     int windowCount, List<NodeItem> list) {
        int total = list.size();
        StringBuilder nodes = new StringBuilder();
        int emitted = 0;
        boolean truncated = false;

        for (int i = 0; i < total; i++) {
            if (emitted >= MAX_NODES || nodes.length() >= MAX_NODES_CHARS) {
                truncated = true;
                break;
            }
            String s = renderNode(list.get(i), dispW, dispH);
            if (s == null) continue;
            if (emitted > 0) nodes.append(",");
            nodes.append(s);
            emitted++;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"display_id\":").append(displayId);
        sb.append(",\"width\":").append(dispW);
        sb.append(",\"height\":").append(dispH);
        sb.append(",\"windows\":").append(windowCount);
        sb.append(",\"total\":").append(total);
        sb.append(",\"returned\":").append(emitted);
        sb.append(",\"truncated\":").append(truncated);
        sb.append(",\"nodes\":[").append(nodes).append("]");
        sb.append("}");
        System.out.print(sb.toString());
    }

    /** Renders one node, or null when it must be dropped as noise. */
    private static String renderNode(NodeItem n, int dispW, int dispH) {
        // A node with no semantics AND no interaction is noise for the model.
        boolean hasSemantic = nonEmpty(n.text) || nonEmpty(n.desc);
        boolean interactive = n.clickable || n.checkable || n.scrollable || n.editableFlag;
        if (!hasSemantic && !interactive) return null;

        // isVisibleToUser can lag for off-screen content; the geometry check is the
        // reliable part and the two agree on the rows we measured.
        boolean visible = n.visibleToUser && withinScreen(n, dispW, dispH);
        // Drop only invisible content that offers nothing to act on. Invisible but
        // clickable nodes stay, flagged, because they are still valid tap targets
        // once the user scrolls.
        if (!visible && !interactive) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":").append(n.id);
        if (n.depth > 0) sb.append(",\"d\":").append(n.depth);
        sb.append(",\"type\":\"").append(escapeJson(n.type)).append("\"");
        if (nonEmpty(n.text)) sb.append(",\"text\":\"").append(escapeJson(n.text)).append("\"");
        if (nonEmpty(n.desc)) sb.append(",\"desc\":\"").append(escapeJson(n.desc)).append("\"");
        if (nonEmpty(n.viewId)) sb.append(",\"vid\":\"").append(escapeJson(n.viewId)).append("\"");
        sb.append(",\"b\":[").append(n.left).append(",").append(n.top).append(",")
          .append(n.right).append(",").append(n.bottom).append("]");
        sb.append(",\"ctr\":[").append(n.centerX).append(",").append(n.centerY).append("]");

        if (n.clickable) sb.append(",\"click\":1");
        if (!n.enabled) sb.append(",\"enabled\":0");
        if (!visible) sb.append(",\"visible\":0");
        if (n.checkable) sb.append(",\"checkable\":1,\"checked\":").append(n.checked ? 1 : 0);
        if (n.scrollable) sb.append(",\"scroll\":1");
        if (n.focused) sb.append(",\"focused\":1");

        // Resolved click target. Only emitted when it differs from the node itself,
        // so the model never has to work out Android's touch bubbling on its own.
        if (n.targetId > 0 && n.targetId != n.id) {
            sb.append(",\"tap\":[").append(n.targetCenterX).append(",").append(n.targetCenterY).append("]");
            sb.append(",\"tap_id\":").append(n.targetId);
            sb.append(",\"tap_x\":").append(n.targetRatio);
        }
        sb.append("}");
        return sb.toString();
    }

    private static boolean withinScreen(NodeItem n, int dispW, int dispH) {
        if (dispW <= 0 || dispH <= 0) return true; // geometry unknown: do not over-filter
        return n.right > 0 && n.bottom > 0 && n.left < dispW && n.top < dispH;
    }

    private static boolean nonEmpty(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Collection
    // ─────────────────────────────────────────────────────────────────────────

    private static void collectInteractiveNodes(AccessibilityNodeInfo node, int depth,
                                                Candidate ancestor,
                                                List<NodeItem> list, int[] idCounter,
                                                int winIndex) {
        if (node == null) return;

        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        CharSequence cls = node.getClassName();
        String viewId = node.getViewIdResourceName();

        boolean clickable = node.isClickable();
        boolean editable = node.isEditable();
        boolean checkable = node.isCheckable();
        boolean checked = node.isChecked();
        boolean scrollable = node.isScrollable();

        boolean hasTextOrDesc = (text != null && text.length() > 0)
                || (desc != null && desc.length() > 0);

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        // Guard against degenerate geometry: OEM trees contain rows where an off-screen
        // child is reported with bottom < top, which would yield a nonsense center.
        boolean sane = bounds.width() > 0 && bounds.height() > 0
                && bounds.right > bounds.left && bounds.bottom > bounds.top;

        NodeItem item = null;
        if (sane) {
            item = new NodeItem();
            item.id = idCounter[0]++;
            item.depth = depth;
            item.type = cls != null ? simplifyType(cls.toString()) : "View";
            item.text = text != null ? text.toString() : null;
            item.desc = desc != null ? desc.toString() : null;
            item.viewId = viewId;
            item.left = bounds.left; item.top = bounds.top;
            item.right = bounds.right; item.bottom = bounds.bottom;
            item.centerX = bounds.centerX();
            item.centerY = bounds.centerY();

            item.clickable = clickable;
            item.editableFlag = editable;
            item.checkable = checkable;
            item.checked = checked;
            item.scrollable = scrollable;
            try { item.enabled = node.isEnabled(); } catch (Throwable ignored) { item.enabled = true; }
            try { item.focusable = node.isFocusable(); } catch (Throwable ignored) {}
            try { item.focused = node.isFocused(); } catch (Throwable ignored) {}
            try { item.visibleToUser = node.isVisibleToUser(); } catch (Throwable ignored) { item.visibleToUser = true; }

            // Resolve the tap target. Keep textless clickable containers: they are
            // exactly the rows the model needs to tap, and dropping them was the reason
            // "the row is clickable but nothing says so" kept biting.
            if (clickable) {
                item.targetId = item.id;
                item.targetCenterX = item.centerX;
                item.targetCenterY = item.centerY;
                item.targetRatio = 1;
            } else if (ancestor != null) {
                int selfArea = Math.max(1, (item.right - item.left) * (item.bottom - item.top));
                int ratio = Math.max(1, Math.round((float) ancestor.area / selfArea));
                if (ratio <= MAX_ANCESTOR_RATIO) {
                    item.targetId = ancestor.id;
                    item.targetCenterX = ancestor.centerX;
                    item.targetCenterY = ancestor.centerY;
                    item.targetRatio = ratio;
                }
            }

            boolean kept = hasTextOrDesc || clickable || editable || checkable || scrollable;
            if (kept) list.add(item);
        }

        Candidate childAncestor = ancestor;
        if (item != null && clickable) {
            childAncestor = Candidate.of(item);
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = null;
            try { child = node.getChild(i); } catch (Throwable ignored) {}
            if (child != null) {
                collectInteractiveNodes(child, depth + 1, childAncestor, list, idCounter, winIndex);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Text injection (unchanged behaviour: clipboard + PASTE keycode)
    // ─────────────────────────────────────────────────────────────────────────

    private static void injectType(int displayId, String text) {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method getService = smClass.getMethod("getService", String.class);
            Object clipboardBinder = getService.invoke(null, "clipboard");

            Class<?> stubClass = Class.forName("android.content.IClipboard$Stub");
            Method asInterface = stubClass.getMethod("asInterface", android.os.IBinder.class);
            Object clipboardService = asInterface.invoke(null, clipboardBinder);

            Class<?> clipDataClass = Class.forName("android.content.ClipData");
            Method newPlainText = clipDataClass.getMethod("newPlainText", CharSequence.class, CharSequence.class);
            Object clip = newPlainText.invoke(null, "agent_input", text);

            Method setPrimaryClip = null;
            for (Method m : clipboardService.getClass().getMethods()) {
                if ("setPrimaryClip".equals(m.getName())) {
                    setPrimaryClip = m;
                    break;
                }
            }
            if (setPrimaryClip != null) {
                Class<?>[] pTypes = setPrimaryClip.getParameterTypes();
                Object[] pArgs = new Object[pTypes.length];
                int stringCount = 0;
                for (int i = 0; i < pTypes.length; i++) {
                    Class<?> pt = pTypes[i];
                    if (pt.isAssignableFrom(clip.getClass()) || pt.getName().contains("ClipData")) {
                        pArgs[i] = clip;
                    } else if (pt == String.class) {
                        if (stringCount == 0) {
                            pArgs[i] = "com.android.shell";
                        } else {
                            pArgs[i] = null;
                        }
                        stringCount++;
                    } else if (pt == int.class || pt == Integer.class) {
                        pArgs[i] = 0;
                    } else if (pt == boolean.class || pt == Boolean.class) {
                        pArgs[i] = false;
                    } else {
                        pArgs[i] = null;
                    }
                }
                setPrimaryClip.invoke(clipboardService, pArgs);
            }

            try { Thread.sleep(50); } catch (Exception ignored) {}

            Runtime.getRuntime().exec(new String[] {
                    "/system/bin/input", "-d", String.valueOf(displayId), "keyevent", "279"
            }).waitFor();
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String simplifyType(String className) {
        int idx = className.lastIndexOf('.');
        if (idx >= 0 && idx < className.length() - 1) {
            return className.substring(idx + 1);
        }
        return className;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < ' ') {
                        String hex = Integer.toHexString(c);
                        sb.append("\\u0000".substring(0, 6 - hex.length())).append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
