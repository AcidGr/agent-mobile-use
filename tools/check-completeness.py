#!/usr/bin/env python3
"""Report, per screen, how many ACTIONABLE nodes the dump captured vs. dropped.

`truncated: true` only says the character budget ran out. `act_total` vs `act_sent`
says whether that cost the model anything it could have tapped, which is the question
that actually matters. Comparing two separate dumps cannot answer it, because the screen
changes between them.
"""
import json
import sys
import urllib.request

LIMIT = 8192

APPS = [
    ("com.taobao.taobao", "淘宝"),
    ("com.xingin.xhs", "小红书"),
    ("com.ss.android.ugc.aweme", "抖音"),
    ("com.sankuai.meituan", "美团"),
    ("com.zhihu.android", "知乎"),
    ("com.tencent.mobileqq", "QQ"),
    ("com.sina.weibo", "微博"),
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


def measure(label):
    try:
        raw = urllib.request.urlopen("http://127.0.0.1:3070/api/dump_ui", timeout=60).read()
    except Exception as exc:  # noqa: BLE001
        print("%-12s FAILED %r" % (label, exc))
        return
    text = raw.decode("utf-8")
    cp = len(text)
    try:
        e = json.loads(text)
    except json.JSONDecodeError:
        print("%-12s codepoints=%-6s !! MALFORMED (pruned)" % (label, cp))
        return
    at, asent = e.get("act_total"), e.get("act_sent")
    lost = (at - asent) if (at is not None and asent is not None) else None
    verdict = ""
    if lost is not None:
        verdict = "OK" if lost == 0 else "LOST %d ACTIONABLE" % lost
    print("%-12s cp=%-6s trunc=%-6s nodes=%-4s ret=%-4s act=%-4s/%-4s ommin=%-4s %s%s" % (
        label, cp, e.get("truncated"), e.get("total"), e.get("returned"),
        asent, at, e.get("omitted_min"), verdict,
        "  <-- OVER PRUNE LIMIT" if cp > LIMIT else ""))


def main():
    # Close the browser first: a VIEW intent routed to com.heytap.browser leaves it on top, and the
    # "app launches" then land inside a WebView instead of the app under test.
    sh("am force-stop com.heytap.browser >/dev/null 2>&1; sleep 1;")
    for pkg, name in APPS:
        sh("am start --display 3 -n $(cmd package resolve-activity --brief %s 2>/dev/null | tail -1) "
           ">/dev/null 2>&1; sleep 9;" % pkg)
        measure(name)
    sh("am force-stop com.heytap.browser >/dev/null 2>&1; sleep 2;")
    for url, name in WEB:
        sh('am force-stop com.heytap.browser >/dev/null 2>&1; sleep 2; am start --display 3 '
           '-a android.intent.action.VIEW -d "%s" com.heytap.browser >/dev/null 2>&1; sleep 9;' % url)
        measure(name)
    return 0


if __name__ == "__main__":
    sys.exit(main())
