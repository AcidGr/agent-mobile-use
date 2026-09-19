# Dump-quality measurement harness

`dump-matrix.sh` runs on the device and dumps a fixed set of layout-heavy apps,
printing the envelope fields that say whether an observation is complete.

Run it after any change to the dump path:

```sh
adb push tools/dump-matrix.sh /data/local/tmp/
adb shell sh /data/local/tmp/dump-matrix.sh
```

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

```
com.taobao.taobao          codepoints=6961 total=136 ret=69 trunc=true  omitted=65 omtop=     ommin=4
com.xingin.xhs             codepoints=6919 total=49  ret=48 trunc=true  omitted=1  omtop=     ommin=4
com.ss.android.ugc.aweme   codepoints=6983 total=69  ret=52 trunc=true  omitted=13 omtop=     ommin=4
com.sankuai.meituan        codepoints=6507 total=58  ret=58 trunc=false omitted=                ommin=
com.zhihu.android          codepoints=6942 total=64  ret=55 trunc=true  omitted=8  omtop=     ommin=4
com.tencent.mobileqq       codepoints=6893 total=73  ret=50 trunc=true  omitted=23 omtop=     ommin=2
qq.com (WebView)           codepoints=6945 total=62  ret=60 trunc=true  omitted=2  omtop=     ommin=4
m.zhihu.com (WebView)      codepoints=3735 total=32  ret=32 trunc=false omitted=                ommin=
```

`omtop` is empty everywhere, so on none of these screens does truncation cost a
tappable control. Regressions show up there first. `ommin=2` on QQ means one off-screen
actionable node was dropped; page it with `next_y` if that screen needs it.

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
