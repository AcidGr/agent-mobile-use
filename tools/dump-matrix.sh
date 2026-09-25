#!/system/bin/sh
did=$(curl -s http://127.0.0.1:3070/api/status | grep -o '"display_id":[0-9]*' | cut -d: -f2)
if [ -z "$did" ] || [ "$did" -le 0 ]; then
  did=3
fi
for pkg in com.taobao.taobao com.xingin.xhs com.ss.android.ugc.aweme com.sankuai.meituan com.zhihu.android com.tencent.mobileqq; do
  am start --display "$did" -n "$(cmd package resolve-activity --brief $pkg 2>/dev/null | tail -1)" >/dev/null 2>&1
  sleep 8
  out=$(curl -s --max-time 25 http://127.0.0.1:3070/api/dump_ui)
  g() { echo "$out" | grep -o "\"$1\":[0-9a-z]*" | cut -d: -f2; }
  printf "%-26s bytes=%-6s win=%-2s total=%-4s ret=%-4s dup=%-4s trunc=%-6s omitted=%-4s omtop=%-5s ommin=%-3s\n" \
    "$pkg" "$(echo -n "$out" | wc -c)" "$(g windows)" "$(g total)" "$(g returned)" "$(g dup)" "$(g truncated)" "$(g omitted)" "$(g omitted_top)" "$(g omitted_min)"
done
