#!/usr/bin/env python3
"""Measure the largest dump_ui payload across a set of screens.

The DSH result pruner counts Unicode CODE POINTS and replaces the middle of anything
over 8192 of them, so bytes are the wrong unit. This reports code points and bytes
side by side and flags any screen that would be pruned.
"""
import json
import subprocess
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
]

# Optional WebView targets: (activity, url) triples exercised through mark.via.
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


def measure(label):
    try:
        raw = urllib.request.urlopen(
            "http://127.0.0.1:3070/api/dump_ui", timeout=60
        ).read()
    except Exception as exc:  # noqa: BLE001
        print("%-14s FAILED %r" % (label, exc))
        return None
    text = raw.decode("utf-8")
    cp = len(text)
    try:
        env = json.loads(text)
        summary = "total=%-4s ret=%-4s trunc=%-6s omitted=%-4s omtop=%-5s ommin=%-3s" % (
            env.get("total"), env.get("returned"), env.get("truncated"),
            env.get("omitted"), env.get("omitted_top"), env.get("omitted_min"),
        )
    except json.JSONDecodeError:
        summary = "!! MALFORMED JSON (already pruned?)"
    flag = "  <-- WOULD BE PRUNED" if cp > LIMIT else ""
    print("%-14s codepoints=%-6s bytes=%-6s %s%s" % (label, cp, len(raw), summary, flag))
    return cp


def main():
    worst = 0
    for pkg, name in APPS:
        sh("am start --display 3 -n $(cmd package resolve-activity --brief %s 2>/dev/null | tail -1) >/dev/null 2>&1; sleep 8; " % pkg)
        cp = measure(name)
        if cp:
            worst = max(worst, cp)
    for url, name in WEB:
        sh('am force-stop mark.via >/dev/null 2>&1; sleep 2; '
           'am start --display 3 -a android.intent.action.VIEW -d "%s" mark.via >/dev/null 2>&1; sleep 9;' % url)
        cp = measure(name)
        if cp:
            worst = max(worst, cp)
    print("\nworst = %s code points (limit %s, headroom %s)" % (worst, LIMIT, LIMIT - worst))
    return 0


if __name__ == "__main__":
    sys.exit(main())
