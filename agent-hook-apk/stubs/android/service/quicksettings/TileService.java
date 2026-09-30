package android.service.quicksettings;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class TileService extends Service {
    public static final String ACTION_QS_TILE = "android.service.quicksettings.action.QS_TILE";
    public static final String ACTION_QS_TILE_PREFERENCES = "android.service.quicksettings.action.QS_TILE_PREFERENCES";
    public static final String META_DATA_ACTIVE_TILE = "android.service.quicksettings.ACTIVE_TILE";

    public Tile getQsTile() { return null; }
    public void onClick() {}
    public void onStartListening() {}
    public void onStopListening() {}
    public void onTileAdded() {}
    public void onTileRemoved() {}
    public void startActivityAndCollapse(Intent intent) {}
    public void unlockAndRun(Runnable runnable) {}
    public boolean isSecure() { return false; }
    public boolean isLocked() { return false; }
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
