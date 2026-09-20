package com.agent;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
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

    /** Fields kept per node. The wire format is written by hand in renderNode(). */
    public static class NodeItem {
        public int id;
        public int depth;
        public String type;
        public String text;
        public String desc;
        public String hint;
        public String tooltip;
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
        public boolean selected;
        public boolean scrollable;
        public boolean folded;

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
     * 23000 alongside this change) with room for the envelope and the fields the Go
     * server adds.
     *
     * Sized so that a dense screen arrives WHOLE in one call, because there is no paging
     * to fall back on any more. Measured worst case over five dense apps: Amap 10403
     * chars / 120 nodes, Taobao 11010 / 125, WeChat 7319 / 67, Meituan 4241 / 45,
     * Settings 3920 / 34. 20000 therefore leaves roughly 2x headroom over everything
     * measured, while still staying clear of the pruner's line.
     *
     * MAX_NODES is deliberately far above what the char budget can ever admit (a node
     * costs roughly 100 chars, so 20000 is about 200 nodes). Keeping it that high means
     * the char budget is the ONLY thing that can truncate a dump, so there is one place
     * to reason about rather than two.
     *
     * `ctr` used to be ~15% of this payload for zero information, which is what made
     * 6800 too small for a dense screen.
     */
    private static final int MAX_NODES = 1000;
    private static final int MAX_NODES_CHARS = 20000;

    /**
     * The one column order every element row uses. Stated once here, emitted once per
     * dump as the second header line, and never repeated per element — which is the
     * whole point of the flat format. Before this, every node carried the strings
     * "id", "type", "b" and so on, and those key names alone were 62% of the payload.
     *
     * Fields that contain free text (`name=`, `d=`, `id=`, `hint=`, `tip=`) always sit
     * at the END, so the rows stay splittable on whitespace no matter what an app puts
     * in its labels.
     *
     * Rows are ordered by usefulness (see rankForBudget) and that order is the ONLY
     * place the tier is stated: a per-row `pr=` field cost 9% of the payload to repeat
     * what the line's position already said. The column lines therefore spell the
     * ordering and the flag letters out ONCE, instead of coding them into every row.
     */
    private static final String NODE_COLUMNS =
            "# one element per row, most useful first: tappable, then own-click, then"
            + " offscreen-actionable, disabled, label-only, offscreen\n"
            + "# columns: id type name x1,y1,x2,y2 flags"
            + " | flags: c=clickable e=editable s=scrollable k+=checked-or-selected k-=unchecked"
            + " off=disabled gone=offscreen focus=focused wN=window dN=depth"
            + " | optional: d= extra-desc id= resource how= why-unnamed target= ancestorId@x,y"
            + " hint= input-hint tip= tooltip";

    /** Longest free-text field emitted per node; a label is a label, not a paragraph. */
    private static final int MAX_FIELD_CHARS = 72;

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
            // Diagnostic escape hatch: raise the char budget so a full tree can be
            // compared against what the model actually receives. Never sent by the
            // server, so production behaviour is unchanged.
            //
            // There is deliberately no paging argument any more. An earlier design
            // exposed y_min/y_max plus a next_y hint so a caller could fetch the part
            // of a screen the budget could not fit. It was removed because the hint was
            // wrong in the common case: nodes are ranked by usefulness, not by position,
            // so when the budget runs out the omitted nodes are scattered across the
            // whole screen rather than sitting below the last emitted one. next_y then
            // pointed at the bottom of the screen and "page from here" returned nothing
            // (measured: 39/88 controls on page 1, next_y=2800, page 2 empty). The
            // budget is sized instead so a dense screen arrives whole in one call.
            int budgetOverride = args.length > 2 && args[2].length() > 0
                    ? Integer.parseInt(args[2]) : 0;
            dumpTree(displayId, budgetOverride);
        } else if ("type".equals(cmd)) {
            if (args.length < 3) {
                System.err.println("Usage: type <displayId> <text>");
                return;
            }
            int displayId = Integer.parseInt(args[1]);
            injectType(displayId, args[2]);
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
        System.out.println("Usage: ToolMain <tree|type|clicknode> [args...]");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read-only dump
    // ─────────────────────────────────────────────────────────────────────────

    private static void dumpTree(int targetDisplayId, int budgetOverride) {
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
                    try { Thread.sleep(WEBVIEW_WAKE_SLEEP_MS); } catch (InterruptedException ignored) {}
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

            // Order matters all the way through: dedup -> semantic hoisting/folding -> tap suppression -> ranking.
            // Each stage removes or reorders nodes, so it has to see the output of the
            // one before it.
            int droppedDup = dedupeIdenticalNodes(list);
            hoistAndFoldCards(list, dispW, dispH);
            suppressUnusableTargets(list, dispW, dispH);

            rankForBudget(list);

            emitEnvelope(targetDisplayId, dispW, dispH, windowCount, list,
                    droppedDup, scanAttempts, recovered, budgetOverride);

        } catch (Throwable t) {
            // Never die silently. Emit the SAME shape as a success so "did this fail?"
            // is answered by an explicit status on the header line rather than by the
            // absence of one: success starts with `ok display=...`, failure starts with
            // `fail error=...`, and an empty tree carries `ok` plus tree_blocked=1 on the
            // header and no rows after the column line. A caller that only looks for the
            // header token cannot misread a failure as an empty screen.
            StringBuilder sb = new StringBuilder();
            sb.append("fail error=\"").append(oneLine(String.valueOf(t))).append("\"");
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
            boolean keepHasOwnSemantic = nonEmpty(keep.text) || nonEmpty(keep.desc)
                    || nonEmpty(keep.hint) || nonEmpty(keep.tooltip);
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
        keep.selected |= from.selected;
        keep.scrollable |= from.scrollable;
        keep.focused |= from.focused;
        keep.folded |= from.folded;
        // A label is the whole reason a node is worth keeping; never let a merge that
        // only happens to reconstruct interaction state throw one away. The later node
        // fills only what the keeper is still missing.
        if (!nonEmpty(keep.text) && nonEmpty(from.text)) keep.text = from.text;
        if (!nonEmpty(keep.desc) && nonEmpty(from.desc)) keep.desc = from.desc;
        if (!nonEmpty(keep.hint) && nonEmpty(from.hint)) keep.hint = from.hint;
        if (!nonEmpty(keep.tooltip) && nonEmpty(from.tooltip)) keep.tooltip = from.tooltip;
        if (!nonEmpty(keep.viewId) && nonEmpty(from.viewId)) keep.viewId = from.viewId;
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
                                     int droppedDup,
                                     int scanAttempts, boolean recovered, int budgetOverride) {
        int total = list.size();
        StringBuilder nodes = new StringBuilder();
        int emitted = 0;
        boolean truncated = false;
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
            String s = renderNode(list.get(i), dispW, dispH, list);
            if (s == null) continue;
            if (emitted > 0) nodes.append("\n");
            nodes.append(s);
            emitted++;
            if (list.get(i).clickable || list.get(i).checkable) actSent++;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("ok display=").append(displayId);
        sb.append(" size=").append(dispW).append("x").append(dispH);
        sb.append(" windows=").append(windowCount);
        if (fullMinX != Integer.MAX_VALUE && (fullMinX < 0 || fullMaxX > dispW)) {
            sb.append(" x_extent=").append(fullMinX).append(",").append(fullMaxX);
        }
        if (fullMinY != Integer.MAX_VALUE && (fullMinY < 0 || fullMaxY > dispH)) {
            sb.append(" y_extent=").append(fullMinY).append(",").append(fullMaxY);
        }
        sb.append(" total=").append(total);
        if (scanAttempts > 0) sb.append(" retries=").append(scanAttempts);
        // The tree was not there on the first read and appeared on a retry. Reported
        // because it changes how a later empty result should be read: WeChat returns an
        // empty window intermittently rather than refusing outright (measured 0,0,0,68,0,70
        // on one unchanged screen), so "it worked a minute ago" is not a contradiction.
        if (recovered) sb.append(" recovered=1");
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
            sb.append(" no_windows=1");
        } else if (total == 0) {
            sb.append(" tree_blocked=1");
        }
        if (droppedDup > 0) sb.append(" dup=").append(droppedDup);
        sb.append(" returned=").append(emitted);
        sb.append(" act_sent=").append(actSent);
        sb.append(" act_total=").append(actTotal);
        sb.append(" truncated=").append(truncated ? 1 : 0);
        if (truncated) {
            // Say WHAT was lost, not just that something was. "omitted":73 alone tells the
            // model nothing about how much of the screen it is missing; combined with the
            // top tier it does.
            //
            // There is no "where to resume" field. An earlier next_y was removed because it
            // was wrong whenever the budget cut into the ranking rather than the screen:
            // nodes are ordered by usefulness, so the omitted ones are scattered rather
            // than sitting below the last emitted one, and next_y pointed at the bottom of
            // the screen. A caller that followed it got an empty second page and no reason
            // to doubt it. Nothing replaces it: the budget is instead sized so that a dense
            // screen arrives whole, and `truncated` is the honest signal that it did not.
            sb.append(" omitted=").append(omitted);
            if (omittedTopTier) sb.append(" omitted_top=1");
            if (omittedMinPriority != Integer.MAX_VALUE) {
                sb.append(" omitted_min=").append(omittedMinPriority);
            }
        }
        sb.append("\n").append(NODE_COLUMNS).append("\n");
        sb.append(nodes);
        System.out.print(sb.toString());
    }

    /**
     * Renders one node as ONE line, or null when it must be dropped as noise.
     *
     * Shape: id type "label" x1,y1,x2,y2 flags, then optional labelled fields, then the
     * free-text fields last. `list` is the full ranked list, needed only to answer one
     * question about a silent node — "is everything it contains also tappable on its
     * own?" — which is what tells a real unlabelled control apart from a layout wrapper.
     */
    private static String renderNode(NodeItem n, int dispW, int dispH, List<NodeItem> list) {
        if (!isEmittable(n, dispW, dispH)) return null;
        boolean visible = n.visibleToUser && withinScreen(n, dispW, dispH);

        // One label, not two. When the app gives both text and a content-desc, the
        // richer one is worth a look instead of a mechanical preference for `text`:
        // Settings rows carry the short title in `text` and the sentence that says what
        // the row does in `desc`, and only the second one tells the model what it gets.
        String text = nonEmpty(n.text) ? n.text : "";
        String desc = nonEmpty(n.desc) ? n.desc : "";
        String label;
        String extra = null;
        if (nonEmpty(text) && nonEmpty(desc) && !text.equals(desc)) {
            label = text.length() >= desc.length() ? text : desc;
            extra = desc;
        } else {
            label = nonEmpty(text) ? text : desc;
        }
        // An icon-only control often explains itself in one of the two fields the tool
        // used to never read, so a node that is silent everywhere else is not yet silent.
        boolean hasLabel = nonEmpty(label) || nonEmpty(n.hint) || nonEmpty(n.tooltip);
        String how = null;
        if (!hasLabel) how = maybeReason(n, list, visible);

        StringBuilder row = new StringBuilder();
        row.append(n.id).append(' ').append(simplifyType(n.type));
        if (hasLabel) row.append(" \"").append(oneLine(label)).append('"');
        row.append(' ').append(n.left).append(',').append(n.top).append(',')
           .append(n.right).append(',').append(n.bottom);

        if (n.clickable) row.append(" c");
        if (n.editableFlag) row.append(" e");
        if (n.scrollable) row.append(" s");
        if (n.checkable) row.append(n.checked ? " k+" : " k-");
        else if (n.selected) row.append(" k+");
        if (!n.enabled) row.append(" off");
        if (!visible) row.append(" gone");
        if (n.focused) row.append(" focus");
        if (n.windowIndex > 0) row.append(" w").append(n.windowIndex);
        if (n.depth > 0) row.append(" d").append(n.depth);

        // `desc` only when the label above is the other field's text.
        if (extra != null) row.append(" d=\"").append(oneLine(extra)).append('"');
        // The resource name is the only identifier a caller can echo back verbatim, so
        // it is worth its bytes even though it repeats part of the label. Package is
        // dropped: the package is constant for a whole screen and identifies nothing.
        if (nonEmpty(n.viewId)) row.append(" id=").append(shortResource(n.viewId));
        if (how != null) row.append(" how=").append(how);

        // The resolved ancestor target. Only when it actually differs from this node's
        // own box: `tap_x=1` used to emit the node's own centre (and its id, and its
        // ratio) three more times per node, which is pure duplication.
        if (n.targetId > 0 && n.targetId != n.id) {
            row.append(" target=").append(n.targetId).append('@')
               .append(n.targetCenterX).append(',').append(n.targetCenterY);
        }
        if (nonEmpty(n.hint)) row.append(" hint=\"").append(oneLine(n.hint)).append('"');
        if (nonEmpty(n.tooltip)) row.append(" tip=\"").append(oneLine(n.tooltip)).append('"');
        return row.toString();
    }

    /**
     * Why a node is worth emitting even though it says nothing about itself.
     *
     * Silence used to be ambiguous in the worst way: the model could not tell an
     * unlabelled button — which it should try — from a layout wrapper that only looks
     * tappable because a child inside it is, which it should not. Measured on this
     * tool's own logs, 433 of 1239 actionable nodes carried no readable name, and 65 of
     * those were full-screen wrappers.
     */
    private static String maybeReason(NodeItem n, List<NodeItem> list, boolean visible) {
        if (!visible) return "offscreen";
        if (!n.enabled) return "disabled";
        if (!n.clickable && !n.checkable) {
            // Nothing acts here; the node is only a spatial anchor, so there is no
            // ambiguity to resolve and no reason to spend bytes on one.
            return n.scrollable ? "scrollonly" : null;
        }
        // "Everything I contain is tappable on its own" is the definition of a wrapper:
        // tapping it lands on whatever child happens to sit at its centre. The same
        // geometric test also catches the ancestor chains AutoDroid clears with
        // _adjust_view_clickability, without mutating the tree.
        if (containsClickable(n, list)) return "wraps";
        if (containsAnySemantic(n, list)) return "wraps";
        return "unlabeled";
    }

    /** Whether any other tappable node lies strictly inside this node's box. */
    private static boolean containsClickable(NodeItem n, List<NodeItem> list) {
        long selfArea = Math.max(1L, (long) (n.right - n.left) * (n.bottom - n.top));
        for (NodeItem m : list) {
            if (m == n || m.id == n.id) continue;
            if (!(m.clickable || m.checkable)) continue;
            if (m.left < n.left || m.top < n.top || m.right > n.right || m.bottom > n.bottom) continue;
            long area = Math.max(1L, (long) (m.right - m.left) * (m.bottom - m.top));
            // A child that is not meaningfully smaller is the same element re-reported,
            // not a nested target.
            if (area * 10L < selfArea * 9L) return true;
        }
        return false;
    }

    /** Whether any node with non-empty text or desc lies strictly inside this node's box. */
    private static boolean containsAnySemantic(NodeItem n, List<NodeItem> list) {
        long selfArea = Math.max(1L, (long) (n.right - n.left) * (n.bottom - n.top));
        for (NodeItem m : list) {
            if (m == n || m.id == n.id) continue;
            if (!nonEmpty(m.text) && !nonEmpty(m.desc)) continue;
            if (m.left < n.left || m.top < n.top || m.right > n.right || m.bottom > n.bottom) continue;
            long area = Math.max(1L, (long) (m.right - m.left) * (m.bottom - m.top));
            if (area * 10L < selfArea * 9L) return true;
        }
        return false;
    }

    /**
     * Hoist semantic text from non-interactive children into textless clickable containers (cards/items),
     * and fold the consumed text-only leaf nodes to save tokens and eliminate misleading "unlabeled" nodes.
     *
     * Guards:
     *   - Only containers <= 40% of the screen area and height <= 900px (no screen-wide wrappers / backgrounds).
     *   - Only non-interactive children (clickable/checkable/editable children are NEVER folded).
     *   - Text children belonging to a nested smaller clickable container are NOT stolen by the outer parent.
     *   - Concatenated text length is capped to prevent long body text from blowing up the label.
     */
    private static void hoistAndFoldCards(List<NodeItem> list, int dispW, int dispH) {
        if (list == null || list.isEmpty()) return;
        long screenArea = (dispW > 0 && dispH > 0) ? (long) dispW * dispH : 0L;

        for (int i = 0; i < list.size(); i++) {
            NodeItem parent = list.get(i);
            if (!parent.clickable) continue;
            if (parent.scrollable) continue;
            if (nonEmpty(parent.text) || nonEmpty(parent.desc)) continue;

            long parentArea = Math.max(1L, (long) (parent.right - parent.left) * (parent.bottom - parent.top));
            if (screenArea > 0 && parentArea > screenArea * 2 / 5) continue;
            if (parent.bottom - parent.top > 900) continue;

            List<NodeItem> textChildren = new ArrayList<NodeItem>();
            boolean hasSubClickable = false;

            for (int j = 0; j < list.size(); j++) {
                if (i == j) continue;
                NodeItem child = list.get(j);
                if (child.windowIndex != parent.windowIndex) continue;
                if (child.left < parent.left || child.top < parent.top
                        || child.right > parent.right || child.bottom > parent.bottom) {
                    continue;
                }
                if (child.depth <= parent.depth) continue;

                if (child.clickable || child.checkable || child.editableFlag) {
                    long childArea = Math.max(1L, (long) (child.right - child.left) * (child.bottom - child.top));
                    if (childArea * 10L < parentArea * 9L) {
                        hasSubClickable = true;
                    }
                    continue;
                }

                if (nonEmpty(child.text) || nonEmpty(child.desc)) {
                    textChildren.add(child);
                }
            }

            if (textChildren.isEmpty()) continue;

            List<NodeItem> directTextChildren = new ArrayList<NodeItem>();
            for (NodeItem tc : textChildren) {
                boolean insideSub = false;
                if (hasSubClickable) {
                    for (int j = 0; j < list.size(); j++) {
                        if (i == j) continue;
                        NodeItem mid = list.get(j);
                        if (!mid.clickable && !mid.checkable) continue;
                        if (mid.depth <= parent.depth || tc.depth <= mid.depth) continue;
                        if (tc.left >= mid.left && tc.top >= mid.top && tc.right <= mid.right && tc.bottom <= mid.bottom) {
                            insideSub = true;
                            break;
                        }
                    }
                }
                if (!insideSub) {
                    directTextChildren.add(tc);
                }
            }

            if (directTextChildren.isEmpty()) continue;

            Collections.sort(directTextChildren, new Comparator<NodeItem>() {
                @Override
                public int compare(NodeItem a, NodeItem b) {
                    if (Math.abs(a.top - b.top) > 15) {
                        return Integer.compare(a.top, b.top);
                    }
                    return Integer.compare(a.left, b.left);
                }
            });

            StringBuilder hoisted = new StringBuilder();
            String lastText = "";
            for (NodeItem tc : directTextChildren) {
                String t = nonEmpty(tc.text) ? tc.text.trim() : (nonEmpty(tc.desc) ? tc.desc.trim() : "");
                if (t.isEmpty() || t.equals(lastText)) continue;
                if (hoisted.length() > 0) {
                    if (hoisted.toString().contains(t)) continue;
                    hoisted.append(' ');
                }
                hoisted.append(t);
                lastText = t;
                if (hoisted.length() >= 64) break;
            }

            if (hoisted.length() == 0) continue;

            parent.text = hoisted.toString();
            parent.targetId = parent.id;
            parent.targetCenterX = parent.centerX;
            parent.targetCenterY = parent.centerY;
            parent.targetRatio = 1;
            parent.tapReason = null;

            for (NodeItem tc : directTextChildren) {
                tc.folded = true;
            }
        }

        Iterator<NodeItem> it = list.iterator();
        while (it.hasNext()) {
            if (it.next().folded) {
                it.remove();
            }
        }
    }

    /** `com.sankuai.meituan:id/k71` -> `k71`; the package is screen-constant noise. */
    private static String shortResource(String viewId) {
        int slash = viewId.lastIndexOf('/');
        String local = slash >= 0 ? viewId.substring(slash + 1) : viewId;
        return local.length() > 0 ? local : viewId;
    }

    /** Cap and flatten a free-text field so it can never break the one-row-per-node grid. */
    private static String oneLine(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(Math.min(s.length(), MAX_FIELD_CHARS + 8));
        boolean lastSpace = false;
        for (int i = 0; i < s.length() && out.length() < MAX_FIELD_CHARS; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c == '"') c = ' ';
            boolean space = Character.isWhitespace(c);
            if (space && lastSpace) continue;
            out.append(c);
            lastSpace = space;
        }
        if (s.length() > MAX_FIELD_CHARS) out.append("~");
        return out.toString();
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

    /**
     * Call a no-argument getter that returns CharSequence and coerce it to String.
     *
     * Returns null when the method does not exist on this device's API level or when it
     * throws — both are normal on an OEM tree, and neither may cost us the dump. Same
     * reflection pattern this file already uses for AccessibilityNodeInfo methods that
     * postdate the android-23 jar it compiles against.
     */
    private static String reflectString(Object target, String method) {
        try {
            Object value = target.getClass().getMethod(method).invoke(target);
            if (value == null) return null;
            return value.toString();
        } catch (Throwable ignored) {
            return null;
        }
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

        // Fields the tree carries but this tool used to never read. Each of the three is
        // the ONLY semantics an element has when its text and content-desc are empty:
        //   hintText     an empty search box is anonymous without it
        //   tooltipText  an icon-only button explains itself here
        //   isSelected   which tab of a tab bar is the current one
        // Read through reflection for the same reason the whole file is: this is compiled
        // against android-23, where getHintText() (API 26) and getTooltipText() (API 24)
        // do not exist yet, while the device runs Android 15/16. `isSelected` is old
        // enough to call directly, but one style for all three is easier to verify.
        String hintStr = reflectString(node, "getHintText");
        String tooltipStr = reflectString(node, "getTooltipText");
        boolean selected = false;
        try { selected = node.isSelected(); } catch (Throwable ignored) {}

        boolean hasTextOrDesc = (text != null && text.length() > 0)
                || (desc != null && desc.length() > 0)
                || nonEmpty(hintStr)
                || nonEmpty(tooltipStr);

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
            item.hint = hintStr;
            item.tooltip = tooltipStr;
            item.viewId = viewId;
            item.left = bounds.left; item.top = bounds.top;
            item.right = bounds.right; item.bottom = bounds.bottom;
            item.centerX = bounds.centerX();
            item.centerY = bounds.centerY();

            item.clickable = clickable;
            item.editableFlag = editable;
            item.checkable = checkable;
            item.checked = checked;
            item.selected = selected;
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
