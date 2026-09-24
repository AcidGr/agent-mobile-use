#!/system/bin/sh
MODDIR=${0%/*}

# Wait until system boot is fully completed
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done

# Wait extra seconds for network/system stability
sleep 3

# Clean stale status and stop files on boot
rm -f /data/local/tmp/vd_status.json 2>/dev/null
rm -f /data/local/tmp/vd_stop 2>/dev/null

# Ensure permissions and temp runtime files exist
chmod 755 "$MODDIR/bin/vd_server" 2>/dev/null
chmod 755 "$MODDIR/bin/run_daemon.sh" 2>/dev/null
chmod 755 "$MODDIR/system/bin/vd" 2>/dev/null

cp -f "$MODDIR/bin/agent_vd.dex" /data/local/tmp/agent_vd.dex 2>/dev/null
cp -f "$MODDIR/bin/agent_tools.dex" /data/local/tmp/agent_tools.dex 2>/dev/null
cp -f "$MODDIR/bin/run_daemon.sh" /data/local/tmp/run_daemon.sh 2>/dev/null
cp -f "$MODDIR/res/dsh_whale_avatar.png" /data/local/tmp/dsh_whale_avatar.png 2>/dev/null
cp -f "$MODDIR/res/dsh_whale_icon.png" /data/local/tmp/dsh_whale_icon.png 2>/dev/null
chmod 666 /data/local/tmp/dsh_whale_avatar.png 2>/dev/null
chmod 666 /data/local/tmp/dsh_whale_icon.png 2>/dev/null
chmod 755 /data/local/tmp/run_daemon.sh 2>/dev/null
pm grant com.agent.mobileuse android.permission.RECORD_AUDIO 2>/dev/null

if ! pgrep -f "vd_server" >/dev/null 2>&1; then
    nohup "$MODDIR/bin/vd_server" > /data/local/tmp/vd_server.log 2>&1 &
fi
