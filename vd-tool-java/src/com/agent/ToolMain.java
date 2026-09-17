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

public class ToolMain {
    public static class NodeItem {
        public int id;
        public String type;
        public String text;
        public String desc;
        public String viewId;
        public int left, top, right, bottom;
        public int centerX, centerY;
        public boolean clickable;
        public boolean editable;
        public boolean checkable;
        public boolean checked;
        public boolean scrollable;
    }

    public static void main(String[] args) {
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

    private static void dumpTree(int targetDisplayId) {
        HandlerThread ht = null;
        Object uiAutomation = null;
        try {
            Class<?> htClass = Class.forName("android.os.HandlerThread");
            Constructor<?> htCtor = htClass.getConstructor(String.class);
            ht = (HandlerThread) htCtor.newInstance("UiToolThread");
            ht.start();

            Class<?> uacClass = Class.forName("android.app.UiAutomationConnection");
            Object uac = uacClass.getConstructor().newInstance();

            Class<?> uiClass = Class.forName("android.app.UiAutomation");
            Class<?> iuacClass = Class.forName("android.app.IUiAutomationConnection");
            Constructor<?> uiCtor = uiClass.getConstructor(Looper.class, iuacClass);
            uiAutomation = uiCtor.newInstance(ht.getLooper(), uac);

            try {
                Method connectMethod = uiClass.getMethod("connect", int.class);
                connectMethod.invoke(uiAutomation, 0);
            } catch (NoSuchMethodException e) {
                Method connectMethod = uiClass.getMethod("connect");
                connectMethod.invoke(uiAutomation);
            }

            AccessibilityServiceInfo info = new AccessibilityServiceInfo();
            info.eventTypes = -1;
            info.feedbackType = 16;
            info.flags = 82; // FLAG_RETRIEVE_INTERACTIVE_WINDOWS | FLAG_REQUEST_TOUCH_EXPLORATION_MODE | FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            uiClass.getMethod("setServiceInfo", AccessibilityServiceInfo.class).invoke(uiAutomation, info);

            Thread.sleep(400);

            Method getWindowsMethod = uiClass.getMethod("getWindowsOnAllDisplays");
            Object displays = getWindowsMethod.invoke(uiAutomation);
            List<NodeItem> list = new ArrayList<NodeItem>();
            int[] idCounter = new int[] { 1 };

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
                        Class<?> winClass = win.getClass();
                        Method getRootMethod = winClass.getMethod("getRoot");
                        AccessibilityNodeInfo root = (AccessibilityNodeInfo) getRootMethod.invoke(win);
                        if (root != null) {
                            collectInteractiveNodes(root, list, idCounter);
                        }
                    }
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                NodeItem item = list.get(i);
                sb.append("  {");
                sb.append("\"id\":").append(item.id).append(",");
                sb.append("\"type\":\"").append(escapeJson(item.type)).append("\"");
                if (item.text != null && item.text.length() > 0) {
                    sb.append(",\"text\":\"").append(escapeJson(item.text)).append("\"");
                }
                if (item.desc != null && item.desc.length() > 0) {
                    sb.append(",\"desc\":\"").append(escapeJson(item.desc)).append("\"");
                }
                if (item.viewId != null && item.viewId.length() > 0) {
                    sb.append(",\"view_id\":\"").append(escapeJson(item.viewId)).append("\"");
                }
                sb.append(",\"center\":[").append(item.centerX).append(",").append(item.centerY).append("]");
                sb.append(",\"bounds\":[").append(item.left).append(",").append(item.top).append(",").append(item.right).append(",").append(item.bottom).append("]");
                if (item.clickable) sb.append(",\"clickable\":true");
                if (item.editable) sb.append(",\"editable\":true");
                if (item.checkable) {
                    sb.append(",\"checkable\":true");
                    sb.append(",\"checked\":").append(item.checked);
                }
                if (item.scrollable) sb.append(",\"scrollable\":true");
                sb.append("}");
                if (i < list.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("]\n");
            System.out.print(sb.toString());

        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            if (uiAutomation != null) {
                try {
                    uiAutomation.getClass().getMethod("disconnect").invoke(uiAutomation);
                } catch (Throwable t) {}
            }
            if (ht != null) {
                ht.quit();
            }
        }
    }

    private static void collectInteractiveNodes(AccessibilityNodeInfo node, List<NodeItem> list, int[] idCounter) {
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

        boolean hasTextOrDesc = (text != null && text.length() > 0) || (desc != null && desc.length() > 0);
        boolean isInteractive = clickable || editable || checkable || scrollable;

        if (hasTextOrDesc || isInteractive) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.width() > 0 && bounds.height() > 0 && bounds.right > 0 && bounds.bottom > 0) {
                NodeItem item = new NodeItem();
                item.id = idCounter[0]++;
                item.type = cls != null ? simplifyType(cls.toString()) : "View";
                item.text = text != null ? text.toString() : null;
                item.desc = desc != null ? desc.toString() : null;
                item.viewId = viewId;
                item.left = bounds.left;
                item.top = bounds.top;
                item.right = bounds.right;
                item.bottom = bounds.bottom;
                item.centerX = bounds.centerX();
                item.centerY = bounds.centerY();
                item.clickable = clickable;
                item.editable = editable;
                item.checkable = checkable;
                item.checked = checked;
                item.scrollable = scrollable;
                list.add(item);
            }
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectInteractiveNodes(child, list, idCounter);
            }
        }
    }

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
                pArgs[0] = clip;
                if (pTypes.length > 1) pArgs[1] = "com.android.shell";
                if (pTypes.length > 2) pArgs[2] = 0; // userId
                if (pTypes.length > 3) pArgs[3] = displayId;
                setPrimaryClip.invoke(clipboardService, pArgs);
            }

            // Send Paste keycode (279)
            Runtime.getRuntime().exec(new String[] { "/system/bin/input", "-d", String.valueOf(displayId), "keyevent", "279" }).waitFor();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

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
