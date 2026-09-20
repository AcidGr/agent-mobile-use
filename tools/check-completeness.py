#!/usr/bin/env python3
"""Report, per screen, how many ACTIONABLE nodes the dump captured vs. dropped.

`truncated=1` only says the character budget ran out. `act_sent` vs `act_total`
says whether that cost the model anything it could have tapped, which is the question
that actually matters. Comparing two separate dumps cannot answer it, because the screen
changes between them.

The observation is no longer a JSON document: /api/dump_ui answers
{"success":..,"message":..,"data":"<text>"} and `data` is a status line, a column
line, then one row per element. The counters live on the status line, which is shaped
`ok key=value key=value...` precisely so this file never has to parse a row.
"""
import json
import sys
import urllib.request

# Keep in step with the preset's thresholdChars (agent.cordis.yml).
LIMIT = 23000

APPS = [
    ("com.taobao.taobao", "淘宝"),
    ("com.xingin.xhs", "小红书"),
    ("com.ss.android.ugc.aweme", "抖音"),
    ("com.sankuai.meituan", "美团"),
    ("com.zhihu.android", "知乎"),
    ("com.tencent.mobileqq", "QQ"),
    ("com.eg.android.AlipayGphone", "支付宝"),
]
WEB = [("https://www.qq.com", "qq.com"), ("https://m.zhihu.com", "m.zhihu")]


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


def foreground():
    """Package currently resumed on the target display, or None."""
    out = sh("dumpsys activity activities --display %s 2>/dev/null "
             "| grep topResumedActivity | head -1" % DISPLAY)
    # ActivityRecord{... u0 <pkg>/<cls> tNNNN}
    for tok in out.split():
        if "/" in tok and not tok.startswith("ActivityRecord"):
            return tok.split("/")[0]
    return None


def measure(label):
    try:
        raw = urllib.request.urlopen("http://127.0.0.1:3070/api/dump_ui", timeout=60).read()
        resp = json.loads(raw.decode("utf-8"))
    except Exception as exc:  # noqa: BLE001
        print("%-12s FAILED %r" % (label, exc))
        return
    if not resp.get("success"):
        print("%-12s FAILED %s" % (label, resp.get("message")))
        return

    text = resp.get("data") or ""
    cp = len(text)
    status = {"status": "?", }
    head = text.split("\n", 1)[0].split(" ")
    for token in head[1:]:
        if "=" in token:
            key, value = token.split("=", 1)
            status[key] = value
    at, asent = status.get("act_total"), status.get("act_sent")
    lost = None
    if at is not None and asent is not None:
        lost = int(at) - int(asent)
    verdict = ""
    if lost is not None:
        verdict = "OK" if lost == 0 else "LOST %d ACTIONABLE" % lost
    print("%-12s cp=%-6s trunc=%-6s nodes=%-4s ret=%-4s act=%-4s/%-4s ommin=%-4s %s%s" % (
        label, cp, status.get("truncated"), status.get("total"), status.get("returned"),
        asent, at, status.get("omitted_min"), verdict,
        "  <-- OVER PRUNE LIMIT" if cp > LIMIT else ""))


def main():
    # Close the browser first: a VIEW intent routed to com.heytap.browser leaves it on top, and the
    # "app launches" then land inside a WebView instead of the app under test.
    sh("am force-stop com.heytap.browser >/dev/null 2>&1; sleep 1;")
    for pkg, name in APPS:
        started = sh("cmd package resolve-activity --brief %s 2>/dev/null | tail -1" % pkg).strip()
        if not started or "No activity found" in started:
            # Skipping loudly. Measured the hard way: a launch that fails silently leaves
            # the PREVIOUS app on screen, so the script cheerfully reports that app twice
            # under two different names — which is exactly what happened with 微博.
            print("%-12s SKIPPED (not installed)" % name)
            continue
        sh("am start --display %s -n %s >/dev/null 2>&1; sleep 9;" % (DISPLAY, started))
        fg = foreground()
        if fg and fg != pkg:
            print("%-12s SKIPPED (foreground is %s, launch did not take)" % (name, fg))
            continue
        measure(name)
    sh("am force-stop com.heytap.browser >/dev/null 2>&1; sleep 2;")
    for url, name in WEB:
        sh('am force-stop com.heytap.browser >/dev/null 2>&1; sleep 2; am start --display %s '
           '-a android.intent.action.VIEW -d "%s" com.heytap.browser >/dev/null 2>&1; sleep 9;' % (DISPLAY, url))
        if foreground() != "com.heytap.browser":
            print("%-12s SKIPPED (browser not in foreground)" % name)
            continue
        measure(name)
    return 0


if __name__ == "__main__":
    sys.exit(main())
