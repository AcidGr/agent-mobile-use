package com.agent;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.reflect.Method;
import java.util.List;

/**
 * READ-ONLY window classifier probe. Performs NO input injection.
 *
 * Prints, for every window on every display:
 *   displayId, index, getType(), the window's OWN getPackageName(), whether
 *   getRoot() is null, the node count, and the system-chrome verdict.
 *
 * Purpose: settle by measurement whether AccessibilityWindowInfo.getPackageName()
 * is usable as the system-chrome discriminator on this OEM build.
 *
 * Usage: app_process /system/bin com.agent.WindowProbeMain
 */
public class WindowProbeMain {

    private static final int CONNECT_STABILIZE_SLEEP_MS = 400;

    static {
        try {
            System.setOut(new java.io.PrintStream(
                    new java.io.FileOutputStream(java.io.FileDescriptor.err), true));
        } catch (Throwable ignored) {
        }
    }

    private static final String[] SYS = {
            "com.android.systemui", "com.coloros.smartsidebar", "com.oplus.systemui",
    };

    public static void main(String[] args) {
        HandlerThread ht = null;
        Object uiAutomation = null;
        try {
            ht = new HandlerThread("WP");
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
            info.flags = 0x2 | 0x8 | 0x10 | 0x40;
            uiClass.getMethod("setServiceInfo", AccessibilityServiceInfo.class)
                    .invoke(uiAutomation, info);
            try {
                Thread.sleep(CONNECT_STABILIZE_SLEEP_MS);
            } catch (InterruptedException ignored) {
            }

            Object displays = uiClass.getMethod("getWindowsOnAllDisplays").invoke(uiAutomation);
            if (displays == null) {
                System.out.print("fail no displays\n");
                return;
            }
            Class<?> saClass = displays.getClass();
            int sizeN = (Integer) saClass.getMethod("size").invoke(displays);
            Method keyAt = saClass.getMethod("keyAt", int.class);
            Method valueAt = saClass.getMethod("valueAt", int.class);

            boolean hasGetPkg;
            try {
                Class.forName("android.view.accessibility.AccessibilityWindowInfo")
                        .getMethod("getPackageName");
                hasGetPkg = true;
            } catch (Throwable t) {
                hasGetPkg = false;
            }
            System.out.print("ok displays=" + sizeN + " hasGetPackageName=" + hasGetPkg + "\n");
            System.out.print("disp\tidx\ttype\twinPkg\tsysVerdict\trootNull\tnodes\ttitle\n");

            for (int i = 0; i < sizeN; i++) {
                int dId = (Integer) keyAt.invoke(displays, i);
                List<?> wins = (List<?>) valueAt.invoke(displays, i);
                if (wins == null) continue;
                int idx = 0;
                for (Object win : wins) {
                    int type = -1;
                    try {
                        type = (Integer) win.getClass().getMethod("getType").invoke(win);
                    } catch (Throwable ignored) {
                    }
                    String winPkg;
                    try {
                        CharSequence p = (CharSequence) win.getClass()
                                .getMethod("getPackageName").invoke(win);
                        winPkg = (p == null) ? "<null>" : p.toString();
                    } catch (Throwable t) {
                        winPkg = "<EX:" + t.getClass().getSimpleName() + ">";
                    }

                    Object rootObj = null;
                    boolean rootNull = true;
                    try {
                        rootObj = win.getClass().getMethod("getRoot").invoke(win);
                        rootNull = !(rootObj instanceof AccessibilityNodeInfo);
                    } catch (Throwable ignored) {
                    }
                    int nodes = rootNull ? 0 : countNodes((AccessibilityNodeInfo) rootObj);

                    String verdict = "keep";
                    if (!winPkg.startsWith("<")) {
                        String lp = winPkg.toLowerCase();
                        for (String s : SYS) {
                            if (lp.equals(s) || lp.startsWith(s + ".")) verdict = "DROP";
                        }
                    }
                    String title = "";
                    try {
                        CharSequence t = (CharSequence) win.getClass()
                                .getMethod("getTitle").invoke(win);
                        if (t != null) title = t.toString();
                    } catch (Throwable ignored) {
                    }
                    System.out.print(dId + "\t" + idx + "\t" + type + "\t" + winPkg
                            + "\t" + verdict + "\t" + (rootNull ? "YES" : "no")
                            + "\t" + nodes + "\t"
                            + title.replace('\t', ' ').replace('\n', ' ') + "\n");
                    idx++;
                }
            }
        } catch (Throwable t) {
            System.out.print("fail error=\"" + String.valueOf(t).replace('"', '\'') + "\"\n");
        } finally {
            if (uiAutomation != null) {
                try {
                    uiAutomation.getClass().getMethod("disconnect").invoke(uiAutomation);
                } catch (Throwable ignored) {
                }
            }
            if (ht != null) ht.quit();
        }
    }

    private static int countNodes(AccessibilityNodeInfo node) {
        return countNodes(node, 0);
    }

    /**
     * Bounded walk. getChild() on an OEM tree can hand back a node that leads back into
     * an ancestor, which turns a naive recursion into an infinite one and gets the
     * process OOM-killed — measured on this device before the depth cap was added.
     */
    private static int countNodes(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 60) return 0;
        int n = 1;
        int c = node.getChildCount();
        if (c > 200) c = 200;
        for (int i = 0; i < c; i++) {
            AccessibilityNodeInfo ch = null;
            try {
                ch = node.getChild(i);
            } catch (Throwable ignored) {
            }
            if (ch != null) {
                n += countNodes(ch, depth + 1);
                try {
                    ch.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
        return n;
    }
}
