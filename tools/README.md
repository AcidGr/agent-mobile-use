# Dump-quality measurement harness

Tooling that answers one question honestly: **did the dump capture everything on screen?**

`check-completeness.py` is the one that matters — it reports `act_sent`/`act_total`, the
number of controls actually delivered versus present. `measure-payload.py` reports the
response size in code points against the pruner limit. `dump-matrix.sh` is the older
device-side version, kept for a quick look without a host.

Run from the host (talks to `vd_server` on `127.0.0.1:3070`):

```sh
python3 tools/check-completeness.py
python3 tools/measure-payload.py
```

Both drive the system browser (`com.heytap.browser`) for the WebView rows, and force-stop
it before the native apps — otherwise a leftover browser tab sits on top and the "app
launches" land inside a WebView instead of the app under test.

Never use `mark.via` for this: it is the user's own browser.

## What the pruner actually does (correcting an earlier claim here)

An earlier revision of this file asserted that the DSH pruner truncates any tool result
over `thresholdChars` **as it arrives**, and that the tool budget therefore had to stay
under it. That was wrong, and it was load-bearing: it is where the whole "must page"
design came from.

Reading `dsh-compaction-tool-result-pruner` shows `pruneSession` is not called per tool
call. It is called only from `compaction-basic#compactIfNeeded`, on two triggers:
context overflow, or token pressure crossing the model's compaction threshold. So
`thresholdChars` is a **cleanup watermark applied to history when compaction runs** — not
a live limit on what a tool may return.

Measured confirmation: an 11844-code-point dump was delivered whole, with no
`[... tool result middle pruned ...]` marker, while the threshold was still 8192.

Consequences:

- A dump never needed to be kept small to "get through". The truncation being fought was
  self-inflicted by `MAX_NODES_CHARS`, not imposed.
- Paging is therefore an **optimisation, not a necessity** — it lets a caller take one
  region instead of paying for the whole screen, which is a real but different benefit.
- `thresholdChars` still matters: it decides how aggressively old results are pruned
  during compaction. The two values below are kept in step so a dump survives compaction
  intact rather than being cut mid-array.

| number | where | value |
| --- | --- | --- |
| `MAX_NODES_CHARS` | `agent-mobile-use/vd-tool-java/src/com/agent/ToolMain.java` | 12000 |
| `thresholdChars` | `dsh-preset-mobile-use/preset/mobile-use/agent.cordis.yml` | 14000 |

Neither is a platform limit: `thresholdChars` is ordinary config (8192 is only the
plugin's default), and `MAX_NODES_CHARS` is a constant we wrote.

## Baseline at the time of writing

Full run, all nine screens. `act` is `act_sent/act_total`; `cp` is code points.

```
淘宝        cp=11660  trunc=False  nodes=129 ret=129  act=45/45  OK
小红书      cp=6401   trunc=False  nodes=49  ret=49   act=28/28  OK
抖音        cp=7228   trunc=False  nodes=62  ret=62   act=39/39  OK
美团        cp=5635   trunc=False  nodes=59  ret=59   act=32/32  OK
知乎        cp=9384   trunc=False  nodes=90  ret=89   act=47/47  OK
QQ          cp=8478   trunc=False  nodes=75  ret=75   act=52/52  OK
支付宝      cp=9470   trunc=False  nodes=75  ret=75   act=37/37  OK
qq.com      cp=11226  trunc=False  nodes=114 ret=114  act=61/61  OK
m.zhihu     cp=4645   trunc=False  nodes=45  ret=45   act=31/31  OK
高德地图    cp=10441  trunc=False  nodes=120 ret=120  act=86/86  OK
```

Every screen delivers every control in a single call, none reports `truncated`, and the
worst payload is 11690 code points against the 14000 threshold. No paging is needed for
any of them.

The harness refuses to report a screen it did not actually reach. Launching with
`am start --display 3 -W` is required: without `-W` a leftover browser tab can win the
foreground race, and the script prints SKIPPED rather than measuring the wrong app. That
check is what caught a phantom 微博 row (`com.sina.weibo` is not installed here).

## Why the threshold was raised from 8192

Not for headroom — five screens genuinely exceeded it and were being sliced mid-JSON:

```
com.taobao.taobao     11660      com.eg.android.AlipayGphone   9388
qq.com (WebView)      11206      com.zhihu.android             9133
com.tencent.mobileqq   8285
```

## The `ctr` removal

`ctr` was `[(left+right)/2, (top+bottom)/2]` — pure redundancy, ~15% of every payload
(measured on Amap: 1950 of 12780 bytes). Dropping it is free, and it is what brought the
dense screens back under budget. `tap` is kept: that is an *ancestor's* centre and cannot
be derived from the node's own bounds.

## Paging still exists, as a fallback

`mobile_dump_ui y_min=...` remains, driven by `next_y` when `omitted_top` is set. With the
budget raised it is no longer needed for the screens above, but a screen with more
controls than ~12000 code points can hold will still report `truncated`, and then paging
recovers the rest instead of losing it.

## Failure vs. an empty tree

Four shapes, all measured, and the distinction matters because the correct reaction
differs:

| state | envelope | what to do |
| --- | --- | --- |
| normal | `ok: true` + nodes present | act on it |
| scan failure | `ok: false` + `error` | retry; the dump did not run |
| engine gave nothing | `ok: true`, `no_windows: 1` | retry; no window object at all |
| tree blocked | `ok: true`, `tree_blocked: 1` | **do NOT retry** — screenshot and use coordinates |

`tree_blocked` is the one that actually happens. Measured on WeChat:

```
LauncherUI (chat list)              windows=1  nodes=0    ← blocked
chat page                           windows=1  nodes=0    ← blocked
plugin.settings.ui.MMSettingUI      windows=1  nodes=135  ← fine
```

while a screenshot of the blocked screens shows a full screen of content. It is
deliberate, so retrying is wasted effort — the opposite of the `no_windows` case.

`no_windows` replaced an earlier `empty_scan` field that was misnamed (it detected a
missing window, not an empty tree) and effectively unreachable: it required the engine
to return no window object at all, which could not be reproduced by foreground
transitions, mode switches, or timing races.

## WebView / H5 pages

Content **is** readable; an earlier revision of this file claimed otherwise.

Chromium auto-disables a WebView's renderer accessibility after
`NO_ACCESSIBILITY_SERVICES_ENABLED_DELAY_MS` (5s) when
`AccessibilityState.isAnyAccessibilityServiceEnabled()` is false. That check consults
`AccessibilityManager.getEnabledAccessibilityServiceList()`, which UiAutomation does not
appear in: `AccessibilityManagerService.registerUiTestAutomationService` clears
`mEnabledServices` and substitutes a fake component, so the list comes back empty even
though `AccessibilityManager.isEnabled()` is true.

Querying the WebView re-enables it, but asynchronously: the first read returns only the
WebView's own chrome (`total=9`, no page content) and the next returns the page
(`total=15`, with the buttons). The dump tool therefore reads twice and keeps the richer
result. Verified deterministic on 3/3 fresh page loads, and end to end against
`qq.com` (114 nodes) and `m.zhihu.com` (48 nodes).

## The one thing no budget fixes

Canvas-drawn UI. Baidu Maps exposes 16 nodes for a full map of place labels, because the
labels are drawn rather than laid out. No budget, query strategy, or accessibility flag
recovers those; they would need OCR over a screenshot.
