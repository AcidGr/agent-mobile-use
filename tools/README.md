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
com.taobao.taobao          bytes=7755  win=1 total=135 ret=74 dup=9 trunc=true  omitted=59 omtop=     ommin=4 nexty=2744
com.xingin.xhs             bytes=7516  win=1 total=49  ret=49 dup=2 trunc=false omitted=                ommin=   nexty=
com.ss.android.ugc.aweme   bytes=7829  win=1 total=66  ret=55 dup=1 trunc=true  omitted=7  omtop=     ommin=4 nexty=2744
com.sankuai.meituan        bytes=7143  win=1 total=60  ret=60 dup=  trunc=false omitted=                ommin=   nexty=
com.zhihu.android          bytes=8166  win=1 total=64  ret=58 dup=2 trunc=true  omitted=5  omtop=     ommin=4 nexty=2744
com.tencent.mobileqq       bytes=8108  win=1 total=73  ret=55 dup=5 trunc=true  omitted=18 omtop=     ommin=4 nexty=2800
```

`omtop` is empty everywhere, so on none of these screens does truncation cost a
tappable control. Regressions show up there first.

## Known gap

WebView/H5 pages return a single `WebView` node regardless of the flags set on
the accessibility service, because `Settings.Secure.accessibility_enabled` is 0
and Chromium's `AccessibilityBridge` therefore never attaches. Such screens must
be read by screenshot, and their buttons cannot be resolved from the tree.
