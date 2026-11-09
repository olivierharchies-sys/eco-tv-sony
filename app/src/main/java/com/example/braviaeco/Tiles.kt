package com.example.braviaeco

import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlin.concurrent.thread

/** Tuile « Réglages rapides » : marche / arrêt de la TV. */
class PowerTileService : TileService() {

    override fun onStartListening() {
        render()
    }

    override fun onClick() {
        val ctx = applicationContext
        thread {
            val result = BraviaClient.togglePower(ctx)
            result.exceptionOrNull()?.let { Ui.toast(ctx, it.message ?: "Échec") }
            PowerWidgetProvider.refreshAll(ctx)
            Handler(Looper.getMainLooper()).post { render() }
        }
    }

    private fun render() {
        runCatching {
            val tile = qsTile ?: return
            tile.state = if (Prefs.lastPower(this) == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }
}

/** Tuile « Réglages rapides » : couper / remettre le son de la TV. */
class MuteTileService : TileService() {

    override fun onStartListening() {
        runCatching {
            val tile = qsTile ?: return
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    override fun onClick() {
        val ctx = applicationContext
        thread {
            val result = BraviaClient.sendIrcc(ctx, "Mute")
            result.exceptionOrNull()?.let { Ui.toast(ctx, it.message ?: "Échec") }
        }
    }
}
