package com.livepets3d.app.overlay

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.livepets3d.app.LivePetsApp
import com.livepets3d.app.R
import com.livepets3d.app.ui.MainActivity

/** Quick Settings tile: one tap to hide / show the floating pet from anywhere. */
class PetTileService : TileService() {
    override fun onStartListening() { update() }

    override fun onClick() {
        val repo = LivePetsApp.instance.repo
        if (OverlayService.running) {
            OverlayService.send(this, if (OverlayService.isPaused) OverlayService.ACTION_RESUME else OverlayService.ACTION_PAUSE)
        } else {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 9, i, PendingIntent.FLAG_IMMUTABLE))
            else @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
        repo.overlayWanted = true
        qsTile?.let { it.state = if (OverlayService.isPaused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; it.updateTile() }
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        val visible = OverlayService.running && !OverlayService.isPaused
        t.state = if (visible) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = if (visible) "Hide pet" else "Show pet"
        t.icon = Icon.createWithResource(this, R.drawable.ic_stat_paw)
        t.updateTile()
    }
}
