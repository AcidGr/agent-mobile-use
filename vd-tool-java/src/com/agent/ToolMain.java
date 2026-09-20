package com.agent;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** Why the last dispatchGesture() attempt failed. Null when it succeeded. */
    private static String lastGestureError = null;

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
        public String tapReason;       // set when an inherited target was suppressed

        // ---- budget ranking (filled by rankForBudget) ----
        public int priority;

        /**
         * Whether this node does something the model cares about: navigate, open a
         * submenu, show a destination. A pure checkbox or a decorative toggle ranks below
         * a control that changes where you are, which is the difference that cost Amap
         * its 查路线 button when the budget ran out inside one priority tier.
         */
        public boolean actionBearing;

        // ---- window provenance ----
        /**
         * Index of the window this node came from, in the z-order returned by
         * getWindowsOnAllDisplays (0 = bottom-most).
         *
         * All windows used to be flattened into one list, which meant a modal dialog's
         * nodes sat interleaved with the activity underneath it and the model had no way
         * to tell that the lower ones were covered. Taobao's 闪购外卖红包 poplayer is
         * exactly this case.
         */
        public int windowIndex;
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
     * Hard caps. The DSH tool-result pruner replaces the middle of any result over
     * thresholdChars with a fixed marker, keeping only headChars + tailChars — so an
     * over-budget dump reaches the model as a corrupt, silently incomplete node list.
     * The budget below must therefore stay under the preset's thresholdChars (raised to
     * 14000 alongside this change) with room for the envelope and the fields the Go
     * server adds.
     *
     * `ctr` used to be ~15% of this payload for zero information, which is what made
     * 6800 too small for a dense screen. With it gone, 12000 fits Amap's whole tree
     * (~10500 after the removal) in one call.
     */
    private static final int MAX_NODES = 200;
    private static final int MAX_NODES_CHARS = 12000;

    /** Inherit an ancestor's click target only when the ancestor is not far bigger. */
    private static final int MAX_ANCESTOR_RATIO = 4;

    /** An ancestor target covering more than this fraction of the screen is unusable. */
    private static final double MAX_ANCESTOR_SCREEN_FRACTION = 0.5;

    /**
     * Retries when the accessibility engine reports no window at all. This is almost
     * always a timing artifact around an activity transition, not an app that hides its
     * tree, so it is worth waiting out rather than reporting an empty screen.
     */
    private static final int DUMP_ATTEMPTS = 3;
    private static final int DUMP_RETRY_SLEEP_MS = 350;

    /**
     * Wait between the two passes. A WebView re-enables its renderer accessibility when it
     * is queried, but not synchronously, so reading twice in a row without a gap sees the
     * same disabled tree both times.
     */
    private static final int WEBVIEW_WAKE_SLEEP_MS = 600;

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
            // Optional paging window. -1 means "unbounded" so the shell can pass empty
            // slots without the caller having to build a different argv shape.
            int yMin = args.length > 2 && args[2].length() > 0 ? Integer.parseInt(args[2]) : -1;
            int yMax = args.length > 3 && args[3].length() > 0 ? Integer.parseInt(args[3]) : -1;
            // Diagnostic escape hatch: raise the node budget so a full tree can be
            // compared against what the model actually receives. Never sent by the
            // server, so production behaviour is unchanged.
            int budgetOverride = args.length > 4 && args[4].length() > 0
                    ? Integer.parseInt(args[4]) : 0;
            dumpTree(displayId, yMin, yMax, budgetOverride);
        } else if ("type".equals(cmd)) {
            if (args.length < 3) {
                System.err.println("Usage: type <displayId> <text>");
                return;
            }
            int displayId = Integer.parseInt(args[1]);
            injectType(displayId, args[2]);
        } else if ("tapfocus".equals(cmd)) {
            // The focus-chain click, i.e. what a screen reader actually does.
            //
            // Measured on this device: a WeChat bottom-tab container reports
            // actions=[1,4,8,16,32,64,16908342] — it DOES advertise ACTION_CLICK (16) —
            // while performAction(ACTION_CLICK) on it changes nothing observable. A screen
            // reader does not do that: it moves ACCESSIBILITY focus onto the node first
            // (ACTION_ACCESSIBILITY_FOCUS, id 64) and activates from there. TalkBack can
            // operate this exact control, so the difference must be the focus step, not
            // the click.
            //
            // This command exists to test that hypothesis. It is deliberately separate
            // from tapnode so the two can be compared on the same node without either
            // one's behaviour hiding the other's.
            if (args.length < 4) {
                System.err.println("Usage: tapfocus <displayId> <x> <y>");
                System.exit(2);
            }
            int displayId = Integer.parseInt(args[1]);
            tapNode(displayId, Integer.parseInt(args[2]), Integer.parseInt(args[3]), true);
        } else if ("tapnode".equals(cmd)) {
            // A click that NEVER injects a touch event. This exists because injected touches
            // and a real finger collide: measured on this device, `input -d 0 swipe` running
            // as a 5s long-press was cut short by ACTION_CANCEL (flags=0x20) at the exact
            // moment a `input -d 3 tap` was issued, and the same class of interference makes
            // the soft keyboard collapse while the user is typing. performAction() calls the
            // View's click handler directly and produces no InputEvent at all, so it cannot
            // enter InputDispatcher's touch arbitration.
            //
            // See tapNode() for what this deliberately does NOT do.
            if (args.length < 4) {
                System.err.println("Usage: tapnode <displayId> <x> <y>");
                System.exit(2);
            }
            int displayId = Integer.parseInt(args[1]);
            tapNode(displayId, Integer.parseInt(args[2]), Integer.parseInt(args[3]), false, "a11y");
        } else if ("tapgesture".equals(cmd)) {
            // MEASURED NEGATIVE RESULT — this command cannot succeed in this environment.
            //
            // It was written to test whether a dispatchGesture() tap could activate a node
            // without the side effect performAction has (moving the input method's token to
            // this display, dumpsys input_method mCurTokenDisplayId 0 -> 3, which collapses
            // the user's soft keyboard).
            //
            // It cannot: the UiAutomation reachable from app_process here does not expose
            // dispatchGesture at all. Enumerated on-device with reflection, its full method
            // set is connect/connect(int)/connectWithTimeout/disconnect/getConnectionId/
            // performGlobalAction/syncInputTransactions — no gesture API. So this always
            // returns ok:false with gesture_error set. Kept as a documented dead end rather
            // than deleted, so the next person does not spend the same time on it.
            //
            // GestureDescription itself DOES exist on this device (API 24+); it is
            // UiAutomation's exposure of it that is missing.
            if (args.length < 4) {
                System.err.println("Usage: tapgesture <displayId> <x> <y>");
                System.exit(2);
            }
            int displayId = Integer.parseInt(args[1]);
            tapNode(displayId, Integer.parseInt(args[2]), Integer.parseInt(args[3]), false, "gesture");
        } else if ("clicknode".equals(cmd)) {
            // Click by NODE IDENTITY rather than by coordinate: locate a node whose text
            // or description matches, then performAction(ACTION_CLICK) on it. See
            // clickNode() for what this buys over a coordinate tap and where it fails.
            if (args.length < 3) {
                System.err.println("Usage: clicknode <displayId> <text|desc> [contains]");
                return;
            }
            int displayId = Integer.parseInt(args[1]);
            boolean contains = args.length > 3 && "contains".equals(args[3]);
            clickNode(displayId, args[2], contains);
        } else {
            printUsage();
        }
    }

    private static void printUsage() {
        System.out.println("Usage: ToolMain <tree|type|tapnode|tapgesture|tapfocus|clicknode> [args...]");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read-only dump
    // ─────────────────────────────────────────────────────────────────────────

    private static void dumpTree(int targetDisplayId, int yMin, int yMax, int budgetOverride) {
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
            // Bits below were read off this device at runtime, not copied from a doc:
            //   FLAG_INCLUDE_NOT_IMPORTANT_VIEWS        0x2
            //   FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY 0x8
            //   FLAG_REPORT_VIEW_IDS                    0x10
            //   FLAG_RETRIEVE_INTERACTIVE_WINDOWS       0x40
            // 0x52 (the original set) is enough for native views. 0x8 is kept because it
            // is the correct declaration for reading web content, but it is NOT a fix for
            // WebViews here: measured, an H5 page still collapses to a single WebView node
            // because Settings.Secure.accessibility_enabled is 0, so Chromium's
            // AccessibilityBridge never attaches. Flipping that setting is a device-level
            // change that would affect the whole system, so it stays off.
            info.flags = 0x2 | 0x8 | 0x10 | 0x40;
            uiClass.getMethod("setServiceInfo", AccessibilityServiceInfo.class).invoke(uiAutomation, info);

            Thread.sleep(400);

            // Display geometry: an observation without it leaves the model unable to judge
            // whether a coordinate is even inside the screen.
            int[] size = queryDisplaySize(targetDisplayId);
            int dispW = size[0];
            int dispH = size[1];

            List<NodeItem> list = null;
            List<NodeItem> firstList = null;
            int windowCount = 0;
            int firstWindows = 0;
            int firstSize = 0;
            // How many attempts the FIRST scan needed. The rescue loop below can push the
            // raw counter much higher, and reporting that as `retries` would make a normal
            // screen look like a struggling one.
            int scanAttempts = 0;
            // Set when a rescue scan is what finally produced nodes. The caller is told,
            // because a payload that needed rescue means the tree was not ready yet — a
            // model that knows this will not conclude the screen is empty.
            boolean recovered = false;
            int attempt = 0;
            // Set once the paging window is applied, BEFORE the scan loop: the loop reads it
            // to decide whether a second read is worth its wait.
            boolean paged = yMin >= 0 || yMax >= 0;

            // Reading a tree is a race, so no single scan is trusted on its own. Three shapes
            // of "not ready yet" were measured on this device, and they need DIFFERENT
            // waits:
            //
            //  - a dump racing an activity transition returns zero WINDOWS. UiAutomation
            //    .connect() returns before the service is bound, so
            //    AccessibilityInteractionClient has no window list to hand out. Measured on
            //    Zhihu: windows:0 and a perfectly good tree two seconds later. A 350ms
            //    retry loop fixes this cheaply.
            //
            //  - a window exists but yields NO NODES, and keeps doing so for seconds.
            //    Measured on WeChat, and this was the surprise: it is INTERMITTENT, not
            //    conditional. Six consecutive scans of one unchanged screen, ~6s apart,
            //    returned 0, 0, 0, 68, 0, 70 nodes — with `tree_blocked` on every empty
            //    one. There is no "correct display" or "correct launch order" to find; the
            //    tree simply is not there every time it is asked for. Reacting to that with
            //    a single re-read is what produced the contradiction: the same screen was
            //    reported as tree_blocked and as 73 readable nodes, hours apart, and the
            //    only variable was luck. So the second scan waits 2.5s, which is what
            //    actually catches a non-empty window.
            //
            //  - a WebView whose renderer-side accessibility was auto-disabled. Chromium
            //    tears its tree down after NO_ACCESSIBILITY_SERVICES_ENABLED_DELAY_MS (5s)
            //    when it cannot see an accessibility service
            //    (AccessibilityState.isAnyAccessibilityServiceEnabled consults
            //    getEnabledAccessibilityServiceList, which UiAutomation never appears in).
            //    Querying it re-enables it asynchronously: the first dump returns only the
            //    WebView's chrome (9 nodes, no page content) and the next one returns the
            //    page (15 nodes with the real buttons). Measured deterministic 3/3.
            //
            // All three are covered by the same shape: scan, wait, scan again, and keep
            // whichever scan saw more. Ordering it that way matters — the second scan must
            // be allowed to REPLACE a thin first one, or the WebView wake is lost.
            //
            // The wait is skipped when the caller asked for a paging window: re-reading
            // would return the same clipped result, so it would only burn 2.5s.
            for (int pass = 0; pass < 2; pass++) {
                for (attempt = 0; attempt < DUMP_ATTEMPTS; attempt++) {
                    list = new ArrayList<NodeItem>();
                    windowCount = 0;
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
                    if (pass == 0) scanAttempts = attempt + 1;
                    // Zero windows is a scan failure and is worth retrying fast; a window
                    // with no nodes is NOT retried here, because the thing that fixes it is
                    // time, not repetition — pass 1 owns that case.
                    if (windowCount > 0) break;
                    if (attempt < DUMP_ATTEMPTS - 1) {
                        try { Thread.sleep(DUMP_RETRY_SLEEP_MS); } catch (InterruptedException ignored) {}
                    }
                }

                if (pass == 0) {
                    firstSize = list.size();
                    firstList = list;
                    firstWindows = windowCount;
                    // Give a not-yet-ready tree time to appear: a WebView's page content, or
                    // WeChat's intermittently empty window. The second read is the difference
                    // between a blank tree and the real one.
                    if (!paged) {
                        try { Thread.sleep(WEBVIEW_WAKE_SLEEP_MS); } catch (InterruptedException ignored) {}
                    }
                } else {
                    // Keep whichever pass saw more. A thin second pass must not replace a
                    // thin first one, and a richer second pass is exactly the wake.
                    if (list.size() <= firstSize) {
                        list = firstList;
                        windowCount = firstWindows;
                    } else if (firstWindows > 0 && firstSize == 0) {
                        // The screen had a window but no nodes on the first read, and did
                        // have nodes on the retry. Report it: a caller that knows the tree
                        // was slow to appear will not read a later `tree_blocked` as "this
                        // app hides its tree", which is exactly the wrong lesson to take
                        // from an intermittently-empty window. Measured on WeChat, which
                        // does this on both displays.
                        recovered = true;
                    }
                }
            }
            if (list == null) list = new ArrayList<NodeItem>();

            // Order matters all the way through: dedup -> tap suppression -> paging ->
            // ranking. Each stage removes or reorders nodes, so it has to see the output
            // of the one before it.
            int droppedDup = dedupeIdenticalNodes(list);
            suppressUnusableTargets(list, dispW, dispH);

            // Paging window. The full tree geometry is reported in the envelope even when
            // a window is applied, so the caller always knows what it is not seeing.
            int clipped = 0;
            if (paged) {
                List<NodeItem> windowed = new ArrayList<NodeItem>(list.size());
                for (NodeItem n : list) {
                    boolean above = yMin >= 0 && n.bottom <= yMin;
                    boolean below = yMax >= 0 && n.top >= yMax;
                    if (above || below) { clipped++; continue; }
                    windowed.add(n);
                }
                list = windowed;
            }

            rankForBudget(list);

            emitEnvelope(targetDisplayId, dispW, dispH, windowCount, list,
                    droppedDup, clipped, paged, scanAttempts, recovered, budgetOverride);

        } catch (Throwable t) {
            // Never die silently. Emit the SAME shape as a success so "did this fail?"
            // is answered by an explicit `ok` field rather than by the absence of one:
            // success carries ok:true, failure carries ok:false plus `error`, and an
            // empty tree carries ok:true with nodes:[] and a reason field. Callers that
            // only check for a missing key cannot misread a failure as an empty screen.
            StringBuilder sb = new StringBuilder();
            sb.append("{\"ok\":false,\"error\":\"").append(escapeJson(String.valueOf(t))).append("\"}");
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

    // ─────────────────────────────────────────────────────────────────────────
    // Post-processing passes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Collapse nodes that are indistinguishable on screen.
     *
     * Android nests container after container at byte-identical bounds (Taobao rendered
     * "淘工厂" three times at [31,406,238,623]), and those copies were eating the token
     * budget that the bottom half of the screen needed. Two nodes are the same element to
     * a model that only sees (bounds, text, desc), so they are merged: the keeper takes
     * over every interaction flag.
     *
     * A text node inside a textless container is KEPT — that node holds the only label
     * for the pair, so collapsing the pair would destroy the label.
     *
     * @return how many nodes were dropped
     */
    private static int dedupeIdenticalNodes(List<NodeItem> list) {
        Map<String, Integer> firstSeen = new HashMap<String, Integer>();
        List<NodeItem> out = new ArrayList<NodeItem>(list.size());
        int dropped = 0;
        for (NodeItem n : list) {
            String key = n.left + ":" + n.top + ":" + n.right + ":" + n.bottom
                    + "|" + norm(n.text) + "|" + norm(n.desc);
            Integer at = firstSeen.get(key);
            if (at == null) {
                firstSeen.put(key, out.size());
                out.add(n);
                continue;
            }
            NodeItem keep = out.get(at);
            boolean keepHasOwnSemantic = nonEmpty(keep.text) || nonEmpty(keep.desc);
            if (keep.clickable || !keepHasOwnSemantic) {
                mergeInto(keep, n);
                dropped++;
            } else {
                // The earlier node is only a label holder; this one carries the
                // interaction. Fold the label forward and drop the earlier node.
                mergeInto(n, keep);
                out.set(at, n);
                dropped++;
            }
        }
        return dropped;
    }

    /** Absorb every interaction flag and the tighter click target of {@code from}. */
    private static void mergeInto(NodeItem keep, NodeItem from) {
        keep.clickable |= from.clickable;
        keep.editableFlag |= from.editableFlag;
        keep.checkable |= from.checkable;
        keep.checked |= from.checked;
        keep.scrollable |= from.scrollable;
        keep.focused |= from.focused;
        if (from.clickable) {
            // A self-target always beats an inherited one.
            keep.targetId = keep.id;
            keep.targetCenterX = keep.centerX;
            keep.targetCenterY = keep.centerY;
            keep.targetRatio = 1;
            keep.tapReason = null;
        } else if (from.targetId > 0 && keep.targetId <= 0) {
            keep.targetId = from.targetId;
            keep.targetCenterX = from.targetCenterX;
            keep.targetCenterY = from.targetCenterY;
            keep.targetRatio = from.targetRatio;
            keep.tapReason = from.tapReason;
        }
    }

    /**
     * Second pass: drop inherited click targets that would actively mislead.
     *
     * Two shapes are worse than no answer at all:
     *   - a scrollable container inheriting a target: tapping its centre just scrolls,
     *     and the model reads that as "the tap did nothing";
     *   - a target covering more than half the screen: it is a layout wrapper, not a
     *     control, so the tap lands wherever the wrapper happens to be centred.
     * The node keeps its own `click`; only the inherited `tap` goes away, and `why` says so.
     */
    private static void suppressUnusableTargets(List<NodeItem> list, int dispW, int dispH) {
        long screenArea = (dispW > 0 && dispH > 0) ? (long) dispW * dispH : 0L;
        for (NodeItem n : list) {
            if (n.targetId <= 0 || n.targetId == n.id) continue;
            if (n.scrollable) {
                n.targetId = -1;
                n.tapReason = "scrollable";
                continue;
            }
            if (screenArea > 0) {
                long selfArea = Math.max(1L, (long) (n.right - n.left) * (n.bottom - n.top));
                long targetArea = selfArea * Math.max(1, n.targetRatio); // target ~ ratio x self
                if (targetArea > screenArea * MAX_ANCESTOR_SCREEN_FRACTION) {
                    n.targetId = -1;
                    n.tapReason = "target>halfscreen";
                }
            }
        }
    }

    /**
     * Order nodes so that if the budget runs out, it runs out on the least useful content.
     *
     * The salary of this pass is the bottom of the screen: the old greedy tree-order fill
     * stopped dead at y=2444 on Taobao and the model never learned the rest existed.
     * Ranking keeps a node's absolute position as the final tiebreak, so the emitted list
     * still reads roughly top-to-bottom.
     *
     * Tiers, best first:
     *   0  on-screen label with a resolved tap target (the model can act on it now)
     *   1  on-screen, actionable on its own `click`
     *   2  off-screen but actionable (a valid target once scrolled to)
     *   3  disabled
     *   4  on-screen, nothing to act on (labels and context)
     *   5  off-screen, nothing to act on
     */
    private static void rankForBudget(List<NodeItem> list) {
        List<Ranked> ranked = new ArrayList<Ranked>(list.size());
        for (int i = 0; i < list.size(); i++) {
            NodeItem n = list.get(i);
            n.priority = priorityOf(n);
            n.actionBearing = isActionBearing(n);
            // Document order is both the tiebreak and the keeper of the reading order.
            ranked.add(new Ranked(n, i));
        }
        Collections.sort(ranked);
        for (int i = 0; i < ranked.size(); i++) list.set(i, ranked.get(i).node);
    }

    private static int priorityOf(NodeItem n) {
        boolean interactive = n.clickable || n.checkable || n.editableFlag;
        if (!interactive) return n.visibleToUser ? 4 : 5;
        if (!n.enabled) return 3;
        if (n.visibleToUser) return (n.targetId > 0 && n.targetId != n.id) ? 0 : 1;
        return 2;
    }

    /** Heuristic for the within-tier tiebreak; deliberately conservative. */
    private static boolean isActionBearing(NodeItem n) {
        if (n.checkable || n.editableFlag) return false;
        if (!n.clickable) return n.targetId > 0;
        return true;
    }

    /** Sort key: usefulness tier, then document order. */
    static class Ranked implements Comparable<Ranked> {
        final NodeItem node;
        final int seq;

        Ranked(NodeItem node, int seq) {
            this.node = node;
            this.seq = seq;
        }

        public int compareTo(Ranked o) {
            if (node.priority != o.node.priority) return node.priority - o.node.priority;
            if (node.actionBearing != o.node.actionBearing) return node.actionBearing ? -1 : 1;
            if (seq != o.seq) return seq - o.seq;
            return node.id - o.node.id;
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * Serialize the observation.
     *
     * Envelope carries display geometry, window count and truncation state, so the model can
     * tell apart: screen is genuinely empty / accessibility tree suppressed / output clipped /
     * dump failed. Each node carries only raw signals plus a resolved click target.
     */
    private static void emitEnvelope(int displayId, int dispW, int dispH,
                                     int windowCount, List<NodeItem> list,
                                     int droppedDup, int clipped, boolean paged,
                                     int scanAttempts, boolean recovered, int budgetOverride) {
        int total = list.size();
        StringBuilder nodes = new StringBuilder();
        int emitted = 0;
        boolean truncated = false;
        int lastBottom = 0;
        int omitted = 0;
        // Lossless accounting: how many nodes in the ENTIRE tree are actionable
        // (clickable / checkable), and how many of those actually reached the model.
        // Without this, "truncated" only says the budget ran out, not whether anything
        // the model could have tapped was lost — and comparing two dumps taken seconds
        // apart cannot answer it either, because the screen changes in between.
        int actTotal = 0;
        int actSent = 0;
        int omittedMinPriority = Integer.MAX_VALUE;
        boolean omittedTopTier = false;
        int fullMinX = Integer.MAX_VALUE, fullMaxX = Integer.MIN_VALUE;
        int fullMinY = Integer.MAX_VALUE, fullMaxY = Integer.MIN_VALUE;

        for (int i = 0; i < total; i++) {
            NodeItem n = list.get(i);
            if (n.left < fullMinX) fullMinX = n.left;
            if (n.right > fullMaxX) fullMaxX = n.right;
            if (n.top < fullMinY) fullMinY = n.top;
            if (n.bottom > fullMaxY) fullMaxY = n.bottom;
            // Count only nodes that would actually render. Counting every raw clickable
            // node reports a phantom loss, because renderNode drops some of them as noise
            // and act_sent can then never reach act_total however large the budget is.
            if ((n.clickable || n.checkable) && isEmittable(n, dispW, dispH)) actTotal++;
        }

        for (int i = 0; i < total; i++) {
            int charBudget = budgetOverride > 0 ? budgetOverride : MAX_NODES_CHARS;
            int nodeBudget = budgetOverride > 0 ? Integer.MAX_VALUE : MAX_NODES;
            if (emitted >= nodeBudget || nodes.length() >= charBudget) {
                // Everything still queued is a candidate for omission, but only nodes that
                // would actually have rendered count — otherwise `omitted` reports noise
                // the caller was never going to see.
                truncated = true;
                for (int k = i; k < total; k++) {
                    NodeItem n = list.get(k);
                    if (!isEmittable(n, dispW, dispH)) continue;
                    omitted++;
                    if (n.priority < omittedMinPriority) omittedMinPriority = n.priority;
                    // Tier 1 is "on screen, actionable through its own click" — a real
                    // control the model can tap. Measured on Amap, where the budget cut
                    // straight into tier 1 and silently dropped 查路线 and 我的位置 with no
                    // signal at all, because only tier 0 was treated as top-tier.
                    if (n.priority <= 1) omittedTopTier = true;
                }
                break;
            }
            String s = renderNode(list.get(i), dispW, dispH);
            if (s == null) continue;
            if (emitted > 0) nodes.append(",");
            nodes.append(s);
            emitted++;
            if (list.get(i).clickable || list.get(i).checkable) actSent++;
            if (list.get(i).bottom > lastBottom) lastBottom = list.get(i).bottom;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"ok\":true");
        sb.append(",\"display_id\":").append(displayId);
        sb.append(",\"width\":").append(dispW);
        sb.append(",\"height\":").append(dispH);
        sb.append(",\"windows\":").append(windowCount);
        if (fullMinX != Integer.MAX_VALUE && (fullMinX < 0 || fullMaxX > dispW)) {
            sb.append(",\"x_extent\":[").append(fullMinX).append(",").append(fullMaxX).append("]");
        }
        if (fullMinY != Integer.MAX_VALUE && (fullMinY < 0 || fullMaxY > dispH)) {
            sb.append(",\"y_extent\":[").append(fullMinY).append(",").append(fullMaxY).append("]");
        }
        sb.append(",\"total\":").append(total);
        if (scanAttempts > 0) sb.append(",\"retries\":").append(scanAttempts);
        // The tree was not there on the first read and appeared on a retry. Reported
        // because it changes how a later empty result should be read: WeChat returns an
        // empty window intermittently rather than refusing outright (measured 0,0,0,68,0,70
        // on one unchanged screen), so "it worked a minute ago" is not a contradiction.
        if (recovered) sb.append(",\"recovered\":1");
        // Three ways to end up with no nodes, and they call for different reactions.
        // Each is now named for what it actually is:
        //   no_windows   the engine returned no window object at all (a scan failure)
        //   tree_blocked a window exists but getRoot() yielded nothing. NOTE: this is NOT
        //                proof that the app withholds its tree. WeChat lands here
        //                intermittently — the same unchanged screen returned 0 nodes on
        //                four scans and 68/70 on two others — so treat it as "not readable
        //                right now" and re-read before concluding anything, and check
        //                `recovered` on a later call.
        // Both are `ok:true` — the call succeeded, the screen just has no readable tree.
        if (windowCount == 0) {
            sb.append(",\"no_windows\":1");
        } else if (total == 0) {
            sb.append(",\"tree_blocked\":1");
        }
        if (droppedDup > 0) sb.append(",\"dup\":").append(droppedDup);
        if (paged) sb.append(",\"clipped\":").append(clipped);
        sb.append(",\"returned\":").append(emitted);
        sb.append(",\"act_total\":").append(actTotal);
        sb.append(",\"act_sent\":").append(actSent);
        sb.append(",\"truncated\":").append(truncated);
        if (truncated) {
            // Say WHAT was lost, not just that something was. "omitted":73 alone tells the
            // model nothing about whether it needs to page; combined with the top tier it
            // does.
            sb.append(",\"omitted\":").append(omitted);
            if (omittedTopTier) sb.append(",\"omitted_top\":1");
            if (omittedMinPriority != Integer.MAX_VALUE) {
                sb.append(",\"omitted_min\":").append(omittedMinPriority);
            }
            if (lastBottom > 0) {
                // Where the emitted list stops, so the model can page instead of
                // concluding the rest of the screen is empty.
                sb.append(",\"next_y\":").append(lastBottom);
            }
        }
        sb.append(",\"nodes\":[").append(nodes).append("]");
        sb.append("}");
        System.out.print(sb.toString());
    }

    /** Renders one node, or null when it must be dropped as noise. */
    private static String renderNode(NodeItem n, int dispW, int dispH) {
        if (!isEmittable(n, dispW, dispH)) return null;
        boolean visible = n.visibleToUser && withinScreen(n, dispW, dispH);

        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":").append(n.id);
        if (n.depth > 0) sb.append(",\"d\":").append(n.depth);
        if (n.windowIndex > 0) {
            // Only windows above the base one are tagged: `w` appearing at all means a
            // dialog/overlay is on screen, and anything from a LOWER window is covered.
            sb.append(",\"w\":").append(n.windowIndex);
        }
        sb.append(",\"type\":\"").append(escapeJson(n.type)).append("\"");
        if (nonEmpty(n.text)) sb.append(",\"text\":\"").append(escapeJson(n.text)).append("\"");
        if (nonEmpty(n.desc)) sb.append(",\"desc\":\"").append(escapeJson(n.desc)).append("\"");
        if (nonEmpty(n.viewId)) sb.append(",\"vid\":\"").append(escapeJson(n.viewId)).append("\"");
        sb.append(",\"b\":[").append(n.left).append(",").append(n.top).append(",")
          .append(n.right).append(",").append(n.bottom).append("]");
        // No `ctr`: it is exactly [(left+right)/2, (top+bottom)/2], so emitting it cost
        // ~15% of the payload for zero information (measured on Amap: 1950 of 12780 bytes).
        // `tap` below is still emitted because that is an ANCESTOR's centre, which the
        // node's own bounds cannot reconstruct.

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
        } else if (n.tapReason != null) {
            // Say why there is no target — silence here reads as "not clickable", and the
            // model goes looking for a worse coordinate on its own.
            sb.append(",\"why\":\"").append(escapeJson(n.tapReason)).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Whether a node earns its place in the output.
     *
     * Kept separate from renderNode so the omission count and the renderer can never
     * disagree about what counts as content.
     */
    private static boolean isEmittable(NodeItem n, int dispW, int dispH) {
        // A node with no semantics AND no interaction is noise for the model.
        boolean hasSemantic = nonEmpty(n.text) || nonEmpty(n.desc);
        boolean interactive = n.clickable || n.checkable || n.scrollable || n.editableFlag;
        if (!hasSemantic && !interactive) return false;

        // isVisibleToUser can lag for off-screen content; the geometry check is the
        // reliable part and the two agree on the rows we measured.
        boolean visible = n.visibleToUser && withinScreen(n, dispW, dispH);
        // Drop only invisible content that offers nothing to act on. Invisible but
        // clickable nodes stay, flagged, because they are still valid tap targets
        // once the user scrolls.
        if (!visible && !interactive) return false;
        return true;
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
            item.windowIndex = winIndex;
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

    /** Depth cap for node search. WeChat's real tree runs 14+ deep. */
    private static final int MAX_NODE_DEPTH = 30;

    // ─────────────────────────────────────────────────────────────────────────
    // Node-identity click
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Click a node by identity instead of by coordinate.
     *
     * Why this is worth having: performAction(ACTION_CLICK) asks the system to run the
     * target View's click directly, so it does not depend on (a) the coordinate still
     * being correct after the layout settled, or (b) nothing overlapping that point.
     * A coordinate tap goes through InputDispatcher and lands on whatever is topmost.
     *
     * Where it fails, which is why the coordinate path must stay:
     *   - no node, no click (canvas-drawn UI exposes nothing to click)
     *   - plenty of controls return false from performAction even though they are
     *     clickable, most often RecyclerView items and anything with a custom
     *     touch handler
     *   - an invisible or disabled node cannot be actioned
     * Callers get an explicit `ok` plus `why`, so a false result is never mistaken for
     * a successful tap.
     */
    private static void clickNode(int targetDisplayId, String label, boolean contains) {
        HandlerThread ht = null;
        Object uiAutomation = null;
        String err = null;
        String cls = null;
        String txt = null;
        String dsc = null;
        String vid = null;
        int[] bounds = null;
        boolean ok = false;
        int tried = 0;
        try {
            ht = new HandlerThread("NodeClickThread");
            ht.start();
            Object uac = Class.forName("android.app.UiAutomationConnection")
                    .getConstructor().newInstance();
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
            uiClass.getMethod("setServiceInfo", AccessibilityServiceInfo.class).invoke(uiAutomation, info);
            Thread.sleep(400);

            List<AccessibilityNodeInfo> all = new ArrayList<AccessibilityNodeInfo>();
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
                        Object rootObj = win.getClass().getMethod("getRoot").invoke(win);
                        if (rootObj instanceof AccessibilityNodeInfo) {
                            collectAll((AccessibilityNodeInfo) rootObj, 0, all);
                        }
                    }
                }
            }

            // Prefer a node this action can actually land on: clickable, enabled, visible.
            // A label often sits inside the real control, and actioning the label is what
            // makes a node click look like it silently failed.
            AccessibilityNodeInfo best = null;
            AccessibilityNodeInfo fallback = null;
            for (AccessibilityNodeInfo an : all) {
                if (!labelMatches(an.getText(), label, contains)
                        && !labelMatches(an.getContentDescription(), label, contains)) {
                    continue;
                }
                tried++;
                if (an.isClickable() && an.isEnabled() && an.isVisibleToUser()) {
                    best = an;
                    break;
                }
                if (fallback == null) fallback = an;
            }
            if (best == null) best = fallback;
            if (best != null && !best.isClickable()) {
                // A label usually sits inside the real control and is not itself
                // clickable; actioning the label is what makes a node click look like it
                // silently did nothing. Walk up to the nearest actionable ancestor.
                AccessibilityNodeInfo anc = clickableAncestor(best);
                if (anc != null) best = anc;
            }
            if (best != null) {
                Rect r = new Rect();
                best.getBoundsInScreen(r);
                bounds = new int[] { r.left, r.top, r.right, r.bottom };
                cls = best.getClassName() != null ? best.getClassName().toString() : null;
                txt = best.getText() != null ? best.getText().toString() : null;
                dsc = best.getContentDescription() != null
                        ? best.getContentDescription().toString() : null;
                vid = best.getViewIdResourceName();
                ok = best.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (!ok) err = "performAction(ACTION_CLICK) returned false";
            } else {
                err = "no node matched";
            }
        } catch (Throwable t) {
            err = String.valueOf(t);
        } finally {
            if (uiAutomation != null) {
                try {
                    uiAutomation.getClass().getMethod("disconnect").invoke(uiAutomation);
                } catch (Throwable ignored) {}
            }
            if (ht != null) ht.quit();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":").append(ok);
        if (err != null) sb.append(",\"error\":\"").append(escapeJson(err)).append("\"");
        sb.append(",\"label\":\"").append(escapeJson(label)).append("\"");
        sb.append(",\"matched\":").append(tried);
        if (cls != null) sb.append(",\"type\":\"").append(escapeJson(cls)).append("\"");
        if (txt != null) sb.append(",\"text\":\"").append(escapeJson(txt)).append("\"");
        if (dsc != null) sb.append(",\"desc\":\"").append(escapeJson(dsc)).append("\"");
        if (vid != null) sb.append(",\"vid\":\"").append(escapeJson(vid)).append("\"");
        if (bounds != null) {
            sb.append(",\"b\":[").append(bounds[0]).append(",").append(bounds[1]).append(",")
              .append(bounds[2]).append(",").append(bounds[3]).append("]");
        }
        sb.append("}");
        System.out.print(sb.toString());
    }

    /**
     * Click whatever is at (x, y) using ONLY AccessibilityNodeInfo.performAction, so that no
     * touch event is ever injected.
     *
     * WHY: injected touches and real fingers fight. Measured on this device, a 5s
     * `input -d 0 swipe` was terminated by ACTION_CANCEL (flags=0x20) the moment an
     * `input -d 3 tap` was issued, and the same interference collapses the soft keyboard
     * while the user is typing on the physical screen. performAction() invokes the View's
     * click handler directly and produces no InputEvent, so it stays out of the
     * InputDispatcher touch arbitration that causes both symptoms.
     *
     * WHAT IT DELIBERATELY DOES NOT DO: it never falls back to injecting a coordinate tap.
     * A silent fallback would reintroduce the exact interference this command exists to
     * avoid, and would do it invisibly. When there is no actionable node under the point
     * the call FAILS and says why, leaving the decision to the caller.
     */
    private static void tapNode(int targetDisplayId, int x, int y) {
        tapNode(targetDisplayId, x, y, false);
    }

    /**
     * Inject a tap as a real gesture at (x, y) through the UiAutomation connection.
     *
     * This is the second activation path. It does inject an InputEvent into the display's
     * input pipeline (unlike performAction), so it is subject to touch arbitration — but it
     * targets a display explicitly instead of relying on "whichever display owns the device",
     * which is exactly the limitation of the `input` tool (its shell command's device shows
     * AssociatedDisplayPort: <none>, so it only reaches the focused display).
     *
     * GestureDescription is API 24+ and this tool compiles against an API 23 jar, so every
     * step is reflective. Written as a separate method so tapNode() stays readable.
     */
    private static boolean dispatchGestureTap(Object uiAutomation, Class<?> uiClass, int x, int y) {
        lastGestureError = null;
        try {
            Class<?> pathClass = Class.forName("android.graphics.Path");
            Object path = pathClass.getConstructor().newInstance();
            pathClass.getMethod("moveTo", float.class, float.class)
                    .invoke(path, (float) x, (float) y);

            Class<?> strokeClass = Class.forName("android.accessibilityservice.GestureDescription$StrokeDescription");
            Object stroke = strokeClass
                    .getConstructor(pathClass, long.class, long.class)
                    .newInstance(path, 0L, 120L);

            Class<?> gdClass = Class.forName("android.accessibilityservice.GestureDescription");
            Object builder = Class.forName("android.accessibilityservice.GestureDescription$Builder")
                    .getConstructor().newInstance();
            builder.getClass().getMethod("addStroke", strokeClass).invoke(builder, stroke);
            Object gesture = builder.getClass().getMethod("build").invoke(builder);

            // dispatchGesture() requires a Handler whose Looper is alive, or it throws/returns
            // false. Passing null works only when the calling thread already has one, which is
            // why the first attempt silently failed.
            Object handler = null;
            try {
                Class<?> handlerClass = Class.forName("android.os.Handler");
                handler = handlerClass.getConstructor(android.os.Looper.class)
                        .newInstance(android.os.Looper.myLooper() != null
                                ? android.os.Looper.myLooper()
                                : android.os.Looper.getMainLooper());
            } catch (Throwable ignored) {}

            for (Method m : uiClass.getMethods()) {
                if (!"dispatchGesture".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 3) {
                    Object r = m.invoke(uiAutomation, gesture, null, handler);
                    return Boolean.TRUE.equals(r);
                }
            }
            lastGestureError = "no dispatchGesture(GestureDescription,*,Handler) method found";
            return false;
        } catch (Throwable t) {
            lastGestureError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Throwable c = t.getCause();
            if (c != null) lastGestureError += " | cause=" + c.getClass().getSimpleName() + ": " + c.getMessage();
            return false;
        }
    }

    private static void tapNode(int targetDisplayId, int x, int y, boolean focusChain) {
        tapNode(targetDisplayId, x, y, focusChain, "a11y");
    }

    private static void tapNode(int targetDisplayId, int x, int y, boolean focusChain, String mode) {
        HandlerThread ht = null;
        Object uiAutomation = null;
        try {
            ht = new HandlerThread("NodeTapThread");
            ht.start();
            Object uac = Class.forName("android.app.UiAutomationConnection")
                    .getConstructor().newInstance();
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
            uiClass.getMethod("setServiceInfo", AccessibilityServiceInfo.class).invoke(uiAutomation, info);
            Thread.sleep(400);

            List<AccessibilityNodeInfo> all = new ArrayList<AccessibilityNodeInfo>();
            int windowCount = 0;
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
                        Object rootObj = win.getClass().getMethod("getRoot").invoke(win);
                        if (rootObj instanceof AccessibilityNodeInfo) {
                            collectAll((AccessibilityNodeInfo) rootObj, 0, all);
                        }
                    }
                }
            }

            if (windowCount == 0) {
                System.out.print("{\"ok\":false,\"error\":\"no_windows\",\"display_id\":"
                        + targetDisplayId + "}");
                return;
            }

            AccessibilityNodeInfo best = findActionableAt(all, x, y);

            StringBuilder sb = new StringBuilder();
            boolean acted = false;
            String actionErr = null;
            if (best == null) {
                actionErr = "no_actionable_node_at_point";
            } else if ("gesture".equals(mode)) {
                // Activation path B: inject a real gesture at the node's centre.
                acted = dispatchGestureTap(uiAutomation, uiClass, x, y);
                if (!acted) actionErr = "dispatchGesture returned false";
            } else {
                // Set accessibility focus first when asked, mirroring what a screen reader
                // does before activating a control. ACTION_ACCESSIBILITY_FOCUS is id 64.
                if (focusChain) {
                    try {
                        best.performAction(0x00000040);
                        Thread.sleep(120);
                    } catch (Throwable ignored) {}
                }
                // Activation path A: ask the system to run the View's click handler.
                // Measured side effect: this moves the input method to this display.
                acted = best.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (!acted) actionErr = "performAction(ACTION_CLICK) returned false";
            }

            sb.append("{\"ok\":").append(acted);
            sb.append(",\"display_id\":").append(targetDisplayId);
            sb.append(",\"point\":[").append(x).append(",").append(y).append("]");
            sb.append(",\"nodes\":").append(all.size());
            if (best == null) {
                sb.append(",\"error\":\"no_actionable_node_at_point\"");
            } else {
                Rect r = new Rect();
                best.getBoundsInScreen(r);
                sb.append(",\"type\":\"").append(escapeJson(simplifyType(
                        best.getClassName() != null ? best.getClassName().toString() : ""))).append("\"");
                if (best.getText() != null) {
                    sb.append(",\"text\":\"").append(escapeJson(best.getText().toString())).append("\"");
                }
                if (best.getContentDescription() != null) {
                    sb.append(",\"desc\":\"").append(escapeJson(best.getContentDescription().toString())).append("\"");
                }
                if (best.getViewIdResourceName() != null) {
                    sb.append(",\"vid\":\"").append(escapeJson(best.getViewIdResourceName())).append("\"");
                }
                sb.append(",\"b\":[").append(r.left).append(",").append(r.top).append(",")
                  .append(r.right).append(",").append(r.bottom).append("]");
                sb.append(",\"via\":\"").append("gesture".equals(mode) ? "dispatchGesture" : "performAction").append("\"");
                // Reported honestly instead of hardcoded: only the gesture path puts an
                // InputEvent into the pipeline. A caller that trusts this field needs it to
                // be true only when a touch really was injected.
                sb.append(",\"injected_touch\":").append("gesture".equals(mode));
                sb.append(",\"actions\":").append(actionIds(best));
                sb.append(",\"clickable_flag\":").append(best.isClickable());
            }
            if (lastGestureError != null) {
                sb.append(",\"gesture_error\":\"").append(escapeJson(lastGestureError)).append("\"");
            }
            sb.append("}");
            System.out.print(sb.toString());

        } catch (Throwable t) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"ok\":false,\"error\":\"").append(escapeJson(String.valueOf(t))).append("\"}");
            System.out.print(sb.toString());
        } finally {
            if (uiAutomation != null) {
                try {
                    uiAutomation.getClass().getMethod("disconnect").invoke(uiAutomation);
                } catch (Throwable ignored) {}
            }
            if (ht != null) ht.quitSafely();
        }
    }

    /**
     * Serialises the accessibility actions a node actually declares, as a JSON array of
     * ids, e.g. [1,4,8,16,32,64].
     *
     * WHY THIS EXISTS: the dump has only ever emitted `clickable = isClickable()`, and a
     * comment in NodeItem claimed that was "measured identical to ACTION_CLICK across 115
     * nodes / 3 apps". That sample never covered the case that breaks — a textless layout
     * container reporting isClickable()=true. getActionList() is the signal that separates
     * "the app handles an accessibility click here" from "the View merely claims to be
     * clickable", which is exactly the difference between a control a screen reader can
     * activate and one that only a real touch reaches. ACTION_CLICK is id 16.
     */
    private static String actionIds(AccessibilityNodeInfo node) {
        try {
            List<AccessibilityNodeInfo.AccessibilityAction> acts = node.getActionList();
            if (acts == null) return "null";
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < acts.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(acts.get(i).getId());
            }
            return sb.append("]").toString();
        } catch (Throwable t) {
            return "null";
        }
    }

    /**
     * The most specific clickable, enabled, visible node whose bounds contain (x, y).
     * "Most specific" wins because a tap should land on the smallest thing under the
     * finger — the innermost clickable, not the full-screen container that also contains
     * the point. Returns null when nothing actionable is there.
     */
    private static AccessibilityNodeInfo findActionableAt(
            List<AccessibilityNodeInfo> all, int x, int y) {
        AccessibilityNodeInfo best = null;
        long bestArea = Long.MAX_VALUE;
        for (AccessibilityNodeInfo n : all) {
            if (!n.isClickable() || !n.isEnabled() || !n.isVisibleToUser()) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (x < r.left || x >= r.right || y < r.top || y >= r.bottom) continue;
            long area = (long) (r.right - r.left) * (r.bottom - r.top);
            if (area <= 0) continue;
            if (area < bestArea) {
                bestArea = area;
                best = n;
            }
        }
        return best;
    }

    /**
     * Nearest ancestor (or the node itself) that accepts a click. This is the node whose
     * click the user meant when they named a label.
     */
    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int up = 0; up < MAX_NODE_DEPTH && cur != null; up++) {
            if (cur.isClickable() && cur.isEnabled()) return cur;
            AccessibilityNodeInfo p = null;
            try { p = cur.getParent(); } catch (Throwable ignored) {}
            if (p == null) return null;
            cur = p;
        }
        return null;
    }

    private static void collectAll(AccessibilityNodeInfo node, int depth,
                                   List<AccessibilityNodeInfo> out) {
        if (node == null || depth > MAX_NODE_DEPTH) return;
        out.add(node);
        int c = node.getChildCount();
        for (int i = 0; i < c; i++) {
            AccessibilityNodeInfo ch = null;
            try { ch = node.getChild(i); } catch (Throwable ignored) {}
            if (ch != null) collectAll(ch, depth + 1, out);
        }
    }

    private static boolean labelMatches(CharSequence value, String label, boolean contains) {
        if (value == null) return false;
        String s = value.toString().trim();
        if (s.length() == 0) return false;
        return contains ? s.contains(label) : s.equals(label);
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
