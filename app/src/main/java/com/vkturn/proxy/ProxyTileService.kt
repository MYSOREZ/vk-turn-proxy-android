package com.vkturn.proxy

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class ProxyTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        val toggleAction = Runnable {
            val isRunning = ProxyService.toggleProxy(this)
            updateTile(isRunning)
        }

        if (isSecure && isLocked) {
            unlockAndRun(toggleAction)
        } else {
            toggleAction.run()
        }
    }

    private fun updateTile(isRunningOverride: Boolean? = null) {
        val tile = qsTile ?: return
        val isRunning = isRunningOverride ?: ProxyService.isRunning

        tile.label = getString(R.string.app_name)
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (isRunning) R.string.qs_tile_state_running else R.string.qs_tile_state_stopped
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            tile.icon = Icon.createWithResource(this, android.R.drawable.ic_menu_manage)
        }
        tile.updateTile()
    }
}
