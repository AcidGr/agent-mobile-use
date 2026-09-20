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
- `thresholdChars` still matters: it decides how aggressively old results are pruned
  during compaction. The two values below are kept in step so a dump survives compaction
  intact rather than being cut mid-list.

| number | where | value |
| --- | --- | --- |
| `MAX_NODES_CHARS` | `agent-mobile-use/vd-tool-java/src/com/agent/ToolMain.java` | 20000 |
| `thresholdChars` | `dsh-preset-mobile-use/preset/mobile-use/agent.cordis.yml` | 23000 |

Neither is a platform limit: `thresholdChars` is ordinary config (8192 is only the
plugin's default), and `MAX_NODES_CHARS` is a constant we wrote. `MAX_NODES_CHARS` is
counted in code points over the element rows only, so the header and column lines are
free.

**An earlier revision of this file also described paging as a fallback, driven by
`mobile_dump_ui y_min=...` and resumed from a `next_y` hint. None of that exists any
more.** `y_min`/`y_max`/`next_y` were removed in `cf40ba4`: the hint was wrong whenever
the budget cut into the ranking rather than into the screen (nodes are ordered by
usefulness, so the omitted ones are scattered rather than sitting below the last emitted
one, and `next_y` pointed at the bottom of the screen — measured on Amap with a
3000-char budget, 39/88 controls on page one, `next_y=2800`, second page empty). Today
there is no second page to fetch: `truncated=1` on the status line is the honest signal,
and `omitted`/`omitted_top`/`omitted_min` say what it cost.

## Baseline at the time of writing

`act` is `act_sent/act_total`; `cp` is code points of the observation text the model
reads — the status line, the column line, and the element rows — not the JSON envelope
around it.

The element rows are one line per element, so this table is directly comparable to the
figures recorded before that change only in the sense that both measure the same thing,
the delivered observation. Round-tripping the same nodes through the two representations
shows the flat form is ~40% smaller for identical information (165 real nodes: 106.8 ->
61.2 code points per node), and the screens below land where that predicts.

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
| tree blocked | `ok: true`, `tree_blocked: 1` | **re-read once** — it is intermittent, not a property of the app |

`tree_blocked` is the one that actually happens, and an earlier revision of this file
got it wrong in the most expensive way: it called the state deliberate and told the
reader not to retry. Measured on WeChat, six consecutive scans of ONE UNCHANGED SCREEN,
about 6s apart, returned:

```
0, 0, 0, 68, 0, 70      nodes, windows=1 on every scan
```

Every empty one carried `tree_blocked`. Same app, same screen, same display, same
service state. So the tree is NOT reliably available, and a later successful read is not
a contradiction. The envelope reports `recovered: 1` when a window yielded no nodes on
the first scan but did on a retry, which is how a caller tells "the tree was slow" apart
from "the app refuses".

What drives the difference is NOT yet identified, and one plausible answer has already
been ruled out. Six 6s-spaced reads look like periodic jitter, but raising the in-call
retry gap from 600ms to 2500ms changed nothing measurable (2/6 readable either way, and
`recovered` never fired: whenever a read succeeded, its FIRST scan had already succeeded;
whenever one failed, its second scan failed too). So it is not "wait a moment and it
appears" — retrying inside the call is cheap insurance, not a fix, and anything that
depends on WeChat's tree must tolerate an unreadable read.

Two claims that used to be in this file were measurement artefacts and are withdrawn:
`nodes=135` attributed to WeChat settings (the foreground at that moment was the
browser, and `plugin.settings.ui.MMSettingUI` does not exist on this device), and "74
nodes readable" attributed to WeChat (those labels — 下一页 / 完成 / 关闭 TalkBack —
are TalkBack's own setup activity). Both came from testing without checking who owned
the foreground.

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
