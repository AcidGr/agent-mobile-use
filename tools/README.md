# Dump-quality measurement harness

Tooling that answers one question honestly: **did the dump capture everything on screen?**

`check-completeness.py` is the one that matters — it reports `act_sent`/`act_total`, the
number of controls actually delivered versus present. `measure-payload.py` reports the
response size in code points against the pruner limit. `dump-matrix.sh` is the older
device-side version, kept for a quick look without a host. `uncapped_field_test.py` is
the stress test behind the per-field cap: it lifts the cap, dumps rich-text screens,
and reports the longest field and the heaviest payload (see the field-cap section below).

Run from the host (talks to `vd_server` on `127.0.0.1:3070`):

```sh
python3 tools/check-completeness.py
python3 tools/measure-payload.py
```

Both drive the system browser (`com.heytap.browser`) for the WebView rows, and both
force-stop it before opening a web target. `check-completeness.py` also closes it before
the native-app run: otherwise a leftover browser tab sits on top and the "app launches"
land inside a WebView instead of the app under test.

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
worst payload is 11660 code points — against the 14000 threshold in force on the day
this baseline was recorded (today 23000, see the table above).

The harness refuses to report a screen it did not actually reach. The display id is
queried dynamically from the gateway (`/api/status`), never hardcoded — a script that assumed
a fixed id launched apps where the dump never looked and reported the whole run as SKIPPED.
Each launch is verified by reading `topResumedActivity` back from the target display:
if not installed or the foreground belongs to another package, it is reported as SKIPPED.
That check is what caught a phantom 微博 row (`com.sina.weibo` is not installed here).

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

## Per-field cuts: 140 head-only -> 4000 head+tail

The whole-tree budget (`MAX_NODES_CHARS = 20000`) is not the only truncation mechanism.
Each free-text field was historically cut at 140 code points by `oneLine()` in `ToolMain.java`,
head-only, with a bare `~` appended. That cut does **NOT** set `truncated=1` — the status line's
truncation flag belongs to the whole-tree budget alone. The status line could therefore report
`truncated=0` (healthy dump) while a critical field was silently cut in half.

This broke a real workflow: a 177-character customer-service chat message carrying
`https://h5.kfzz20.oreo20.cn/#/pages/order/detail?orderId=jiwY0lNldwOw` was emitted as
`https://h5.kfzz20.oreo20.cn/#/pa~` — the `orderId` lived past code point 140 — and recovering
the link required zooming a screenshot.

With the cap lifted to 99,999, `tools/uncapped_field_test.py` dumped 8 rich-text screens on the
background virtual display:

```
知乎 (feed+scroll)      cp=3681  rows=47  trunc=0  maxfield=311  n>140=2
微信                   cp=2204  rows=26  trunc=0  maxfield=35   n>140=0
淘宝 (feed+scroll)      cp=4376  rows=76  trunc=0  maxfield=43   n>140=0
美团                   cp=2694  rows=45  trunc=0  maxfield=23   n>140=0
QQ                     cp=4792  rows=68  trunc=0  maxfield=148  n>140=1
百科·长文页             cp=3852  rows=71  trunc=0  maxfield=32   n>140=0
m.zhihu feed           cp=2711  rows=39  trunc=0  maxfield=101  n>140=0
腾讯新闻 feed          cp=5131  rows=83  trunc=0  maxfield=69   n>140=0
```

Worst screen: 5131 of the 23,000 pruner threshold; longest real field: 311 chars. WebViews break
long articles into per-element a11y nodes (the Baike page peaked at only 32 chars), so the feared
single-node multi-kilobyte explosion did not occur.

Policy shipped in v5.1 (`fix(dump)`):

| | Legacy (140) | Current v5.1 (4000) |
| --- | --- | --- |
| Cap | 140 (label budget) | **4000** (disaster wall, ~13x measured max) |
| Cut shape | Head-only (URL params always died) | **Head + 160-char Tail** (query params preserved) |
| Marker | Bare `~` | Explicit `...[cut:N]...` with dropped length |
| Cut decision | Raw length (mislabelled fields that fit after space collapse) | Flattened length (the row's actual cost) |

`hoisting` shares the same constant, bounding merged container labels. 9/9 unit tests pass; the
service-chat link now dumps completely in one call.

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
