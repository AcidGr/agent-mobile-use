#!/usr/bin/env python3
"""Measure the largest dump_ui payload across a set of screens.

The DSH result pruner counts Unicode CODE POINTS and replaces the middle of anything
over thresholdChars, so bytes are the wrong unit. This reports code points and bytes
side by side and flags any screen that would be pruned.

The measurement is of the OBSERVATION TEXT the model reads, not of the HTTP envelope
around it. /api/dump_ui answers {"success":..,"message":..,"data":"<text>"}, where
`data` is a status line, a column line, then one row per element. Measuring the
envelope instead would understate every screen by the JSON escaping of the body.
"""
import json
import subprocess
import sys
import urllib.request

# Keep in step with the preset's thresholdChars (agent.cordis.yml):
# dsh-compaction-tool-result-pruner starts cutting history at this many code points.
LIMIT = 23000

APPS = [
    ("com.taobao.taobao", "淘宝"),
    ("com.xingin.xhs", "小红书"),
    ("com.ss.android.ugc.aweme", "抖音"),
    ("com.sankuai.meituan", "美团"),
    ("com.zhihu.android", "知乎"),
    ("com.tencent.mobileqq", "QQ"),
]

# Optional WebView targets: (activity, url) triples exercised through com.heytap.browser.
WEB = [
    ("https://www.qq.com", "qq.com"),
    ("https://m.zhihu.com", "m.zhihu"),
]


def sh(cmd, timeout=300):
    req = urllib.request.Request(
        "http://127.0.0.1:3070/api/shell",
        data=json.dumps({"command": cmd}).encode(),
        headers={"Content-Type": "application/json"},
    )
    return json.load(urllib.request.urlopen(req, timeout=timeout)).get("output", "")


def target_display():
    """Display id of the observation target, straight from the gateway.

    Hardcoding this was a real defect: the virtual display id changes between starts
    (it has been 3 and it has been 4 on the same device), so a script that assumed one
    id launched every app somewhere the dump never looked and reported the whole run as
    SKIPPED rather than as a failure. Ask, never assume.
    """
    try:
        raw = urllib.request.urlopen("http://127.0.0.1:3070/api/status", timeout=30).read()
        return int(json.loads(raw.decode("utf-8")).get("target_display_id") or 0)
    except Exception:  # noqa: BLE001
        return 0


DISPLAY = target_display()


def dump_status(text):
    """Parse the observation's status line into a dict.

    The line is `ok key=value key=value...` (or `fail error="..."`), deliberately
    shaped so this parser needs no JSON library and cannot be broken by whatever an
    app puts inside an element label.
    """
    line = text.split("\n", 1)[0]
    fields = line.split(" ")
    out = {"status": fields[0]}
    for token in fields[1:]:
        if "=" in token:
            key, value = token.split("=", 1)
            out[key] = value
    return out


def measure(label):
    try:
        raw = urllib.request.urlopen(
            "http://127.0.0.1:3070/api/dump_ui", timeout=60
        ).read()
        resp = json.loads(raw.decode("utf-8"))
    except Exception as exc:  # noqa: BLE001
        # A 404 from the gateway is a plain ActionResponse, not a raised HTTPError,
        # so the failure surfaces as `success: false` rather than as an exception.
        print("%-14s FAILED %r" % (label, exc))
        return None
    if not resp.get("success"):
        print("%-14s FAILED %s" % (label, resp.get("message")))
        return None

    text = resp.get("data") or ""
    cp = len(text)
    status = dump_status(text)
    summary = "rows=%-4s act=%-5s trunc=%-3s omitted=%-4s omtop=%-5s ommin=%-3s" % (
        status.get("returned"), "%s/%s" % (status.get("act_sent"), status.get("act_total")),
        status.get("truncated"), status.get("omitted"),
        status.get("omitted_top"), status.get("omitted_min"),
    )
    flag = "  <-- WOULD BE PRUNED" if cp > LIMIT else ""
    print("%-14s codepoints=%-6s bytes=%-6s %s%s" % (label, cp, len(text.encode("utf-8")), summary, flag))
    return cp


def main():
    worst = 0
    for pkg, name in APPS:
        sh("am start --display %s -n $(cmd package resolve-activity --brief %s 2>/dev/null | tail -1) >/dev/null 2>&1; sleep 8; " % (DISPLAY, pkg))
        cp = measure(name)
        if cp:
            worst = max(worst, cp)
    for url, name in WEB:
        sh('am force-stop com.heytap.browser >/dev/null 2>&1; sleep 2; '
           'am start --display %s -a android.intent.action.VIEW -d "%s" com.heytap.browser >/dev/null 2>&1; sleep 9;' % (DISPLAY, url))
        cp = measure(name)
        if cp:
            worst = max(worst, cp)
    print("\nworst = %s code points (limit %s, headroom %s)" % (worst, LIMIT, LIMIT - worst))
    return 0


if __name__ == "__main__":
    sys.exit(main())
