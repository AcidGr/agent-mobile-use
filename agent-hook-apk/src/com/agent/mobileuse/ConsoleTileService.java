package com.agent.mobileuse;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

/**
 * Universal Quick Settings Tile for launching Agent Web Console / DemoDialogActivity
 * across all Android OEM devices (OnePlus, Xiaomi, Samsung, Pixel, vivo, etc.).
 */
public class ConsoleTileService extends TileService {
    private static final String TAG = "ConsoleTileService";

    @Override
    public void onStartListening() {
        super.onStartListening();
        try {
            Tile tile = getQsTile();
            if (tile != null) {
                tile.setState(Tile.STATE_INACTIVE);
                tile.setLabel("Agent 控制台");
                tile.updateTile();
            }
        } catch (Throwable t) {
            Log.w(TAG, "onStartListening warning: " + t.getMessage());
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        try {
            Intent intent = new Intent(this, DemoDialogActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivityAndCollapse(intent);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to launch DemoDialogActivity from Tile: " + t.getMessage(), t);
        }
    }
}
