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

## The two numbers that must stay in step

They live in **different repositories**, which is exactly why they drifted apart:

| number | where | value |
| --- | --- | --- |
| `MAX_NODES_CHARS` | `agent-mobile-use/vd-tool-java/src/com/agent/ToolMain.java` | 12000 |
| `thresholdChars` | `dsh-preset-mobile-use/preset/mobile-use/agent.cordis.yml` | 14000 |

The tool budget must stay **below** the pruner threshold. Over `thresholdChars` the DSH
pruner replaces the middle with a marker and keeps only `headChars` (4096) + `tailChars`
(1024), so an over-budget dump arrives as a corrupt JSON fragment rather than a shortened
one. Neither number is a platform limit: `thresholdChars` is ordinary config (8192 is just
the plugin's default), and `MAX_NODES_CHARS` is a constant we wrote.

## Baseline at the time of writing

`check-completeness.py` output. `act` is `act_sent/act_total`; `cp` is code points.

```
淘宝        cp=11660  trunc=False  nodes=136 ret=134  act=45/45  OK
小红书      cp=6401   trunc=False  nodes=50  ret=50   act=29/29  OK
抖音        cp=7228   trunc=False  nodes=59  ret=59   act=36/36  OK
美团        cp=4247   trunc=False  nodes=46  ret=46   act=31/31  OK
知乎        cp=9133   trunc=False  nodes=88  ret=87   act=46/46  OK
QQ          cp=8285   trunc=False  nodes=73  ret=73   act=51/51  OK
支付宝      cp=9388   trunc=False  nodes=74  ret=74   act=37/37  OK
qq.com      cp=11206  trunc=False  nodes=114 ret=114  act=61/61  OK
m.zhihu     cp=4861   trunc=False  nodes=48  ret=48   act=35/35  OK
高德地图    cp=10441  trunc=False  nodes=120 ret=120  act=86/86  OK
```

Every screen now delivers every control **in a single call** — no paging needed, and no
screen reports `truncated`. Worst payload measured 11690 against the 14000 limit.

The harness refuses to report a screen it did not actually reach. A launch that fails
leaves the previous app on top, so it checks the resumed package and prints SKIPPED
rather than measuring the wrong app; that is how a phantom 微博 row was caught
(`com.sina.weibo` is not installed on this device).

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
