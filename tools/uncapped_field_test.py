#!/usr/bin/env python3
"""Uncapped-field dump stress test (MAX_FIELD_CHARS 140 -> 99999).

Launches rich-text apps / long-form web pages on the BACKGROUND virtual display,
dumps each screen (twice: pre/post splash, plus one scroll pass where useful), and
reports per screen:

  cp/bytes      observation code points vs DSH pruner threshold (23000) and
                the Java node budget (20000)
  rows/trunc    returned nodes and whether the whole-tree budget kicked in
  maxfield      longest single free-text field (the thing 140 used to cut)
  n>140/n>2000  how many fields exceed the old cap / a new 'disaster' level
  cut~          fields still ending in '~' => still being cut at 99999

Everything runs headlessly against the gateway at 127.0.0.1:3070; the physical
display is never touched.
"""
import json
import re
import sys
import time
import urllib.request

GW = "http://127.0.0.1:3070"
LIMIT = 23000       # DSH pruner thresholdChars
NODE_BUDGET = 20000 # MAX_NODES_CHARS in ToolMain.java
OLD_CAP = 140
NEW_CAP = 99999


def post(path, obj, timeout=120):
    req = urllib.request.Request(
        GW + path, data=json.dumps(obj).encode(),
        headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=timeout))


def get(path, timeout=60):
    return json.load(urllib.request.urlopen(GW + path, timeout=timeout))


def sh(cmd, timeout=120):
    return post("/api/shell", {"command": cmd}, timeout).get("output", "")


def dump():
    try:
        r = get("/api/dump_ui", 90)
    except Exception as exc:
        print("   dump failed: %r" % (exc,))
        return None
    if not r.get("success"):
        print("   dump not success: %s" % r.get("message"))
        return None
    return r.get("data") or ""


def parse(text):
    st = {}
    lines = text.split("\n")
    for tok in (lines[0].split()[1:] if lines else []):
        if "=" in tok:
            k, v = tok.split("=", 1)
            st[k] = v
    fields = []
    rows = 0
    for line in lines[1:]:
        if not line or line.startswith("#"):
            continue
        rows += 1
        fields.extend(re.findall(r'"([^"]*)"', line))
    maxlen = max((len(f) for f in fields), default=0)
    longest = max(fields, key=len, default="")
    return {
        "cp": len(text),
        "bytes": len(text.encode("utf-8")),
        "rows": st.get("returned", "?"),
        "truncated": st.get("truncated", "?"),
        "omitted": st.get("omitted", "-"),
        "maxfield": maxlen,
        "longest": longest,
        "n140": sum(1 for f in fields if len(f) > OLD_CAP),
        "n2000": sum(1 for f in fields if len(f) > 2000),
        "ncut": sum(1 for f in fields if f.endswith("~") and len(f) >= NEW_CAP),
    }


def best(dumps):
    ok = [d for d in dumps if d]
    if not ok:
        return None
    return max(ok, key=lambda t: len(t))


def report(label, text):
    m = parse(text)
    flag = ""
    if m["cp"] > LIMIT:
        flag = " <-- WOULD BE PRUNED"
    elif m["truncated"] == "1":
        flag = " <-- NODE BUDGET HIT (truncated=1)"
    elif m["maxfield"] > NODE_BUDGET:
        flag = " <-- SINGLE FIELD >20000"
    print("%-22s cp=%-6s bytes=%-6s rows=%-4s trunc=%-2s "
          "maxfield=%-5s n>140=%-4s n>2000=%-3s cut~=%s%s"
          % (label, m["cp"], m["bytes"], m["rows"], m["truncated"],
             m["maxfield"], m["n140"], m["n2000"], m["ncut"], flag))
    if m["maxfield"] > OLD_CAP:
        snip = m["longest"][:100].replace("\n", " ")
        print("%-22s   longest field #%d: %r"
              % ("", m["maxfield"], snip))
    sys.stdout.flush()
    return m


def main():
    did = int(get("/api/status").get("target_display_id") or 0)
    print("target display = %d, old cap %d -> new cap %d"
          % (did, OLD_CAP, NEW_CAP))
    sys.stdout.flush()

    results = []

    apps = [
        ("com.zhihu.android", "知乎", True),
        ("com.tencent.mm", "微信", False),
        ("com.taobao.taobao", "淘宝", True),
        ("com.sankuai.meituan", "美团", False),
        ("com.tencent.mobileqq", "QQ", False),
    ]
    for pkg, name, scroll in apps:
        print("[app] %s ..." % name)
        sys.stdout.flush()
        post("/api/launch", {"package": pkg})
        time.sleep(7)
        d1 = dump()
        time.sleep(7)
        d2 = dump()
        if scroll:
            post("/api/swipe", {"x1": 636, "y1": 1820, "x2": 636,
                                "y2": 600, "duration": 350})
            time.sleep(4)
            d3 = dump()
        else:
            d3 = None
        text = best([d1, d2, d3])
        if text:
            results.append((name, report(name, text)))
        else:
            print("%-22s FAILED (no dump)" % name)

    web = [
        ("https://baike.baidu.com/item/%E4%B8%AD%E5%8D%8E%E4%BA%BA%E6%B0%91"
         "%E5%85%B1%E5%92%8C%E5%9B%BD", "百科·中华人民共和国"),
        ("https://m.zhihu.com", "m.zhihu feed"),
        ("https://news.qq.com", "腾讯新闻 feed"),
    ]
    for url, name in web:
        print("[web] %s ..." % name)
        sys.stdout.flush()
        sh("am force-stop com.heytap.browser")
        time.sleep(2)
        sh('am start --display %d -a android.intent.action.VIEW '
           '-d "%s" com.heytap.browser' % (did, url))
        time.sleep(9)
        d1 = dump()
        time.sleep(5)
        d2 = dump()
        text = best([d1, d2])
        if text:
            results.append((name, report(name, text)))
        else:
            print("%-22s FAILED (no dump)" % name)

    print("\n==== SUMMARY ====")
    if results:
        worst = max(results, key=lambda kv: kv[1]["cp"])
        worstf = max(results, key=lambda kv: kv[1]["maxfield"])
        print("screens tested: %d" % len(results))
        print("worst payload : %s at %s cp (limit %d, headroom %d)"
              % (worst[1]["cp"], worst[0], LIMIT, LIMIT - worst[1]["cp"]))
        print("longest field : %s at %s = %d chars (old cap %d)"
              % (worstf[1]["maxfield"], worstf[0], worstf[1]["maxfield"], OLD_CAP))
        print("any truncated=1 screens: %s"
              % [n for n, m in results if m["truncated"] == "1"] or "none")
        print("any would-be-pruned    : %s"
              % [n for n, m in results if m["cp"] > LIMIT] or "none")
        print("any still-cut fields   : %s"
              % [n for n, m in results if m["ncut"] > 0] or "none")
    return 0


if __name__ == "__main__":
    sys.exit(main())
