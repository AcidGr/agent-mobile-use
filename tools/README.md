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

| column | meaning |
| --- | --- |
| `bytes` | response size; must stay under 8192 or DSH's result pruner slices the JSON |
| `win` | windows on the display; `0` means the accessibility engine gave nothing |
| `total` / `ret` | nodes collected vs. emitted after ranking and the budget cap |
| `dup` | identical overlapping nodes merged away |
| `trunc` | whether the budget ran out |
| `omitted` / `ommin` | how many nodes were dropped, and the best usability tier among them |
| `omtop` | **the one that matters** — non-empty means something tappable was dropped |
| `nexty` | y coordinate to resume from via `mobile_dump_ui y_min=...` |

Read it like this: `trunc=true` on its own is not a problem. `trunc=true` with
`omtop` set is a real loss. `win=0` is a retry, not an unreadable app.

## Baseline at the time of writing

`check-completeness.py` output, with the system browser (`com.heytap.browser`) for the
WebView rows. `act` is `act_sent/act_total`.

```
淘宝        cp=7186  trunc=True   nodes=132 ret=65  act=45/45  ommin=4   OK
小红书      cp=7090  trunc=True   nodes=50  ret=49  act=29/29  ommin=4   OK
抖音        cp=7171  trunc=True   nodes=65  ret=52  act=40/40  ommin=4   OK
美团        cp=6529  trunc=False  nodes=58  ret=58  act=32/32  ommin=None OK
知乎        cp=7185  trunc=True   nodes=86  ret=59  act=43/43  ommin=4   OK
QQ          cp=7077  trunc=True   nodes=73  ret=51  act=51/51  ommin=4   OK
微博        cp=7077  trunc=True   nodes=73  ret=51  act=51/51  ommin=4   OK
支付宝      cp=7105  trunc=True   nodes=74  ret=50  act=37/37  ommin=4   OK
qq.com      cp=7126  trunc=True   nodes=114 ret=64  act=61/61  ommin=4   OK
m.zhihu     cp=5664  trunc=False  nodes=48  ret=48  act=35/35  ommin=None OK
```

Worst payload is 7186 code points against the 8192 prune limit.

## Dense screens need a paged read

`check-completeness.py` reports `act_sent`/`act_total`, which is the only honest measure
of "did the dump capture everything": `truncated` merely says the character budget ran
out, and comparing two dumps taken seconds apart is invalid because the screen changes
between them.

Most screens are complete in one call. **Amap is not** — measured stable at 120 nodes / 86
controls, of which 11 never arrive:

```
FrameLayout '查路线'    [1096,1242,1271,1435]
FrameLayout '我的位置'  [1096,1067,1271,1242]
LinearLayout '(无标签)' [0,2548,1272,2800]     <- bottom nav
```

Its full tree is ~12000 code points against a ~6800 budget, so something must be dropped;
that is arithmetic, not a bug. What matters is that the loss is **loud** (`omitted_top: 1`)
and **recoverable**:

```
y_min=2400  ->  15 controls, 0 lost        (95 nodes clipped by the window)
```

so a paged read covers 90/86 controls across two calls. The tool description therefore
requires paging whenever `omitted_top` is set, rather than leaving it to taste.

Ranking cannot fix this class of screen — sorting decides *which* nodes are dropped, not
how many. The one genuinely unreadable category is canvas-drawn UI: Baidu Maps exposes
16 nodes for a full map of labels, because the labels are drawn, not laid out. No budget
or query strategy recovers those; they would need OCR on a screenshot.

## Payload budget

`measure-payload.py` reports the response size in **code points**, because that is the
unit the DSH result pruner counts. Over `thresholdChars` (8192) the pruner replaces the
middle with a marker, keeping only `headChars` (4096) + `tailChars` (1024) — so an
over-budget dump arrives as a corrupt JSON fragment rather than a shortened one.

Measured worst case across the six native apps plus two real WebView sites:

```
worst = 6983 code points (limit 8192, headroom 1209)
```

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
`qq.com` (62 nodes) and `m.zhihu.com` (32 nodes).
