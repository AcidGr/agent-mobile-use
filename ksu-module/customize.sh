SKIPUNZIP=0

ui_print "**********************************************"
ui_print "*  Agent Mobile Sandbox & Tools v0.6.3-alpha *"
ui_print "*               作者：酸小明                 *"
ui_print "**********************************************"

ui_print "- 正在安装纯隐形 LSPosed 跨屏路由与输入法隔离补丁 (v0.6.3-alpha)..."
pm install -r "$MODPATH/apk/agent_hook.apk" >/dev/null 2>&1
if [ $? -eq 0 ]; then
    ui_print "- Hook 补丁安装成功"
    pm grant com.agent.mobileuse android.permission.RECORD_AUDIO >/dev/null 2>&1
else
    ui_print "! 警告: APK 安装失败，请检查系统环境"
fi

# Configure LSPosed scope DB
LSP_DB="/data/adb/lspd/config/modules_config.db"
SQLITE_BIN="$MODPATH/bin/sqlite3"
chmod 755 "$SQLITE_BIN" 2>/dev/null

if [ -f "$LSP_DB" ] && [ -x "$SQLITE_BIN" ]; then
    ui_print "- 正在自动配置 LSPosed 模块作用域..."
    APK_PATH=$(pm path com.agent.mobileuse 2>/dev/null | head -n 1 | cut -d':' -f2)
    if [ -n "$APK_PATH" ]; then
        "$SQLITE_BIN" "$LSP_DB" "INSERT OR REPLACE INTO modules (module_pkg_name, apk_path) VALUES ('com.agent.mobileuse', '$APK_PATH');" 2>/dev/null
        "$SQLITE_BIN" "$LSP_DB" "INSERT OR REPLACE INTO modules_state (module_pkg_name, user_id, enabled) VALUES ('com.agent.mobileuse', 0, 1);" 2>/dev/null
        "$SQLITE_BIN" "$LSP_DB" "INSERT OR REPLACE INTO scope (module_pkg_name, app_pkg_name, user_id) VALUES ('com.agent.mobileuse', 'android', 0);" 2>/dev/null
        "$SQLITE_BIN" "$LSP_DB" "INSERT OR REPLACE INTO scope (module_pkg_name, app_pkg_name, user_id) VALUES ('com.agent.mobileuse', 'system', 0);" 2>/dev/null
        "$SQLITE_BIN" "$LSP_DB" "INSERT OR REPLACE INTO scope (module_pkg_name, app_pkg_name, user_id) VALUES ('com.agent.mobileuse', 'com.android.systemui', 0);" 2>/dev/null
        ui_print "- LSPosed 作用域配置完成: $APK_PATH"
    fi
fi

ui_print "- 设置可执行权限..."
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/bin/vd" 0 0 0755
set_perm "$MODPATH/bin/run_daemon.sh" 0 0 0755
set_perm "$MODPATH/bin/vd_server" 0 0 0755
set_perm "$MODPATH/bin/sqlite3" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755

# Instant activation without waiting for reboot
cp -f "$MODPATH/bin/agent_vd.dex" /data/local/tmp/agent_vd.dex
cp -f "$MODPATH/bin/agent_tools.dex" /data/local/tmp/agent_tools.dex
cp -f "$MODPATH/bin/run_daemon.sh" /data/local/tmp/run_daemon.sh
chmod 755 /data/local/tmp/run_daemon.sh

ui_print "**********************************************"
ui_print "* 安装完成！                                 *"
ui_print "* 随时在终端执行 vd tree / vd tap / vd type  *"
ui_print "* 浏览器访问 http://127.0.0.1:3070 查看监控  *"
ui_print "**********************************************"
