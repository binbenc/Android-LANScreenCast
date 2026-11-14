package com.benchan.wireless.screencasting.feature.sender.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.benchan.wireless.screencasting.feature.sender.R
import com.benchan.wireless.screencasting.feature.sender.session.SenderController
import com.benchan.wireless.screencasting.feature.sender.session.SenderState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 快捷设置图块（F12、PRD L14）：未投屏时点一下打开 App 并直连最近使用的设备；投屏中点一下停止。
 * 录屏授权必须由 Activity 发起，所以“一键投屏”仍会经过系统授权弹窗。
 */
@AndroidEntryPoint
class CastTileService : TileService() {
    @Inject
    lateinit var controller: SenderController

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        job = s.launch { controller.state.collect { render(it) } }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    private fun render(state: SenderState) {
        val tile = qsTile ?: return
        val active = state is SenderState.Casting || state is SenderState.Reconnecting || state is SenderState.Starting
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.sender_tile_label)
        tile.subtitle = when (state) {
            is SenderState.Casting -> getString(R.string.sender_tile_casting)
            is SenderState.Reconnecting -> getString(R.string.sender_notif_reconnecting)
            else -> null
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val s = controller.state.value
        if (s is SenderState.Casting || s is SenderState.Reconnecting || s is SenderState.Starting) {
            controller.stop()
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_QUICK_CAST, true)
        } ?: return
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(launch)
        }
    }

    companion object {
        /** MainActivity 收到该 extra 时进入发送端并直连最近使用的设备。 */
        const val EXTRA_QUICK_CAST = "wsc.quick_cast"
    }
}
