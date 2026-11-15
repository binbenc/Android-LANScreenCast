package com.benchan.wireless.screencasting.feature.sender.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.feature.sender.R
import com.benchan.wireless.screencasting.feature.sender.ui.push.PushCopy
import com.benchan.wireless.screencasting.protocol.dlna.dmc.DmcController
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushEndInfo
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushSessionState
import com.benchan.wireless.screencasting.protocol.dlna.dmc.TranscodeUi
import com.benchan.wireless.screencasting.protocol.upnp.MediaKind
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushEndReason
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushPhase
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * DLNA 推送前台服务（PRD 8.1.1 D、技术方案 3.13.4.6，决策 #32）：
 * - 类型 `mediaPlayback`；Android 15+ 转换视频期间叠加 `mediaProcessing`，转完去掉（技术方案 3.13.4.7）。
 * - 只在**本次**会话（启动时传入的会话 ID）结束时停止；启动后 5 秒内还没看到这次会话也停止（第 1 轮 PM P0-1）。
 * - 挂 Media3 MediaSession（[RemotePushPlayer]）：锁屏媒体控件 + 音量键调电视音量 + “停止推送”按钮（第 1 轮 EMU-4）。
 * - 通知按状态切换：连接中 / 失败 / 转换中 / 等你选择 / 照片即将结束 只放必要按钮，不挂媒体控件（第 1 轮 UX P1-9）。
 * - 电视在拉流 / 播放 / 正在转换时持有部分唤醒锁；推送期间持有 Wi-Fi 低延迟锁；结束马上释放。
 * - 从最近任务划掉 App 不结束推送（stopWithTask = false）。
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class MediaPushService : LifecycleService() {
    @Inject
    lateinit var controller: DmcController

    private var session: MediaSession? = null
    private var notificationController: ListenableFuture<MediaController>? = null
    private var player: RemotePushPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var started = false
    private var processingType = false
    private var watchId: String? = null
    private var watchSince = 0L
    private var stopping = false
    private val thumbs = HashMap<String, Bitmap?>()

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_SESSION_ID)?.let { id ->
                watchId = id
                watchSince = SystemClock.elapsedRealtime()
                stopping = false
            }
            ACTION_STOP -> controller.stop()
            ACTION_TOGGLE -> controller.togglePlay()
            ACTION_NEXT -> controller.next()
            ACTION_PREV -> controller.previous()
            ACTION_CANCEL_TRANSCODE -> controller.cancelTranscode()
            ACTION_KEEP_ALIVE -> controller.keepAlive()
            ACTION_ACCEPT_TRANSCODE -> controller.acceptTranscode(videoOnly = false, autoForList = true)
            ACTION_SKIP -> controller.skipItem()
        }
        if (!started) {
            started = true
            ensureChannel(this)
            startForegroundCompat(buildNotification(controller.session.value), transcoding = controller.transcoding.value)
            setUp()
        }
        return START_NOT_STICKY
    }

    private fun setUp() {
        val p = RemotePushPlayer(controller).also { player = it }
        val stopButton = CommandButton.Builder(CommandButton.ICON_STOP)
            .setDisplayName(getString(R.string.push_stop_push))
            .setSessionCommand(SessionCommand(COMMAND_STOP, Bundle.EMPTY))
            .build()
        session = runCatching {
            MediaSession.Builder(this, p)
                .setId("wsc-push")
                .setMediaButtonPreferences(listOf(stopButton))
                .setCallback(object : MediaSession.Callback {
                    // 平台会话（系统媒体控件 / 锁屏，`dumpsys media_session` 里的 custom actions）只采用“媒体通知控制器”
                    // 连接结果里的按钮偏好与可用命令；这里对所有控制器都放行“停止推送”（第 1 轮 EMU-4 / 第 2 轮复测）
                    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                        MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                            .setAvailableSessionCommands(
                                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(SessionCommand(COMMAND_STOP, Bundle.EMPTY)).build(),
                            )
                            .setMediaButtonPreferences(listOf(stopButton))
                            .build()

                    override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                        if (customCommand.customAction == COMMAND_STOP) this@MediaPushService.controller.stop()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                })
                .build()
        }.onFailure { WscLog.w(TAG, "media session failed", it) }.getOrNull()
        // 不用 MediaSessionService 时没有“媒体通知控制器”连上来，平台会话的 custom actions 一直为空（第 2 轮复测：
        // `dumpsys media_session` 显示 wsc-push custom actions=[]）。按 Media3 MediaNotificationManager 的做法，
        // 由本应用自己连一个带 `androidx.media3.session.MediaNotificationManager=true` 连接提示的 MediaController：
        // MediaSessionImpl.isMediaNotificationController() 据此识别，并把上面 onConnect 的按钮偏好与可用命令同步到平台会话。
        // 自检：推送中执行 `adb shell dumpsys media_session`，wsc-push 的 PlaybackState 应有 custom actions=[Action:mName=停止推送…]。
        session?.let { ms ->
            notificationController = MediaController.Builder(this, ms.token)
                .setConnectionHints(Bundle().apply { putBoolean(MEDIA_NOTIFICATION_CONTROLLER_HINT, true) })
                .buildAsync()
        }
        lifecycleScope.launch {
            combine(controller.session, controller.transcoding) { s, t -> s to t }.collect { (s, transcoding) ->
                p.hideTitles = !lockScreenShowsPrivate()
                p.update(s?.takeIf { it.sessionId == watchId })
                val mine = s != null && s.sessionId == watchId
                when (PushServiceWatch.decide(watchId, watchSince, SystemClock.elapsedRealtime(), s?.sessionId, s?.ended != null)) {
                    PushServiceWatch.Decision.STOP_ENDED -> {
                        WscLog.i(TAG, "push session over, stopping service")
                        if (!controller.remoteVisible) postEndNotification(s!!, s.ended!!)
                        shutdown()
                    }
                    PushServiceWatch.Decision.STOP_TIMEOUT -> shutdown()
                    PushServiceWatch.Decision.WAIT -> {
                        // 本次会话还没发布（start 是异步的）：到期再判一次
                        lifecycleScope.launch {
                            delay(START_GRACE_MS)
                            val cur = controller.session.value
                            if (PushServiceWatch.decide(watchId, watchSince, SystemClock.elapsedRealtime(), cur?.sessionId, cur?.ended != null) == PushServiceWatch.Decision.STOP_TIMEOUT) shutdown()
                        }
                    }
                    PushServiceWatch.Decision.KEEP ->
                        if (transcoding != processingType) startForegroundCompat(buildNotification(s), transcoding) else notify(buildNotification(s))
                }
                s?.current?.let { cur -> if (mine) ensureThumb(s.sessionId, cur.index, cur.uri, cur.kind) }
            }
        }
        // 唤醒锁按需持有（每 5 秒评估一次）
        lifecycleScope.launch {
            while (isActive) {
                updateLocks(controller.session.value?.takeIf { it.sessionId == watchId })
                delay(5_000)
            }
        }
    }

    private fun shutdown() {
        if (stopping) return
        stopping = true
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notify(n: Notification) = runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n) }

    private fun startForegroundCompat(n: Notification, transcoding: Boolean) {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (transcoding && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
            processingType = transcoding
        } catch (e: Exception) {
            // 后台无法追加类型时仍以 mediaPlayback 运行（技术方案 3.13.4.7：待真机验证）
            WscLog.w(TAG, "startForeground(type=$type) failed", e)
            if (type != ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) {
                runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
            }
            processingType = transcoding
        }
    }

    /** Android 15+：mediaProcessing 每 24 小时累计 6 小时上限，到时系统回调；取消转换并退回 mediaPlayback。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        WscLog.w(TAG, "foreground service timeout type=$fgsType")
        controller.cancelTranscode()
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(controller.session.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            processingType = false
        }.onFailure { stopSelf() }
    }

    private fun updateLocks(s: PushSessionState?) {
        val active = s != null && s.ended == null
        val local = s?.items?.any { !it.remote } == true
        // Wi-Fi：推送本地文件期间持有（电视随时可能拉流）；失败 / 等你选择时不持有（第 1 轮 PM P2-16）
        val waiting = s?.engine?.phase == PushPhase.FAILED || s?.engine?.phase == PushPhase.AWAITING_CHOICE
        if (active && local && !waiting) acquireWifi() else releaseWifi()
        val needCpu = active && (controller.transcoding.value || controller.mediaStreamingRecently() ||
            (local && s?.engine?.phase == PushPhase.PLAYING))
        if (needCpu) acquireWake() else releaseWake()
    }

    private fun acquireWake() {
        val wl = wakeLock ?: getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wsc:dlna-push")
            .apply { setReferenceCounted(false) }.also { wakeLock = it }
        // 带超时续期：即使进程异常也不会无限持有
        wl.acquire(WAKE_RENEW_MS)
    }

    private fun releaseWake() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifi() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(WifiManager::class.java) ?: return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = (wifiLock ?: wm.createWifiLock(mode, "wsc:dlna-push").apply { setReferenceCounted(false) }).also { it.acquire() }
    }

    private fun releaseWifi() {
        wifiLock?.let { if (it.isHeld) it.release() }
    }

    private fun releaseLocks() {
        releaseWake()
        releaseWifi()
    }

    private fun lockScreenShowsPrivate(): Boolean =
        runCatching { Settings.Secure.getInt(contentResolver, "lock_screen_allow_private_notifications", 1) != 0 }.getOrDefault(true)

    /** 通知大图标：本地缩略图（隐私模式的公开版本不带）。 */
    private fun ensureThumb(sessionId: String, index: Int, uri: android.net.Uri?, kind: MediaKind) {
        val key = "$sessionId-$index"
        if (uri == null || kind == MediaKind.AUDIO || thumbs.containsKey(key)) return
        thumbs[key] = null
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { contentResolver.loadThumbnail(uri, Size(256, 256), null) }.getOrNull() }
            if (thumbs.size > 8) thumbs.keys.take(thumbs.size - 8).forEach { thumbs.remove(it) }
            thumbs[key] = bmp
            controller.session.value?.takeIf { it.sessionId == watchId && it.ended == null }?.let { notify(buildNotification(it)) }
        }
    }

    private fun base(title: String, text: String): NotificationCompat.Builder {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        val contentIntent = launch?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
    }

    private fun buildNotification(s: PushSessionState?): Notification {
        val name = s?.target?.name.orEmpty()
        val title = getString(R.string.push_notif_title, name)
        val e = s?.engine
        val t = s?.transcode
        val stop = { b: NotificationCompat.Builder -> b.addAction(0, getString(R.string.push_stop_push), action(ACTION_STOP, 16)) }
        if (s == null || e == null) return base(title, "").build()
        // 转换中 / 过热暂停：只留 [取消]
        if (t != null && (t.phase == TranscodeUi.Phase.RUNNING || t.phase == TranscodeUi.Phase.PAUSED_THERMAL)) {
            val hot = t.phase == TranscodeUi.Phase.PAUSED_THERMAL
            val detail = if (hot) getString(R.string.push_notif_transcode_hot_detail, t.progress)
            else getString(R.string.push_notif_transcoding_detail, name) + (PushCopy.minutes(t.etaMs)?.let { " · 约剩 ${it.removePrefix("约 ")}" } ?: "")
            return base(if (hot) getString(R.string.push_notif_transcode_hot) else getString(R.string.push_notif_transcoding, t.progress), detail)
                .setProgress(100, t.progress, t.progress == 0)
                .addAction(0, getString(R.string.push_cancel), action(ACTION_CANCEL_TRANSCODE, 10))
                .build()
        }
        // 列表在后台遇到需要转换的项（第 1 轮 PM P1-10）
        if (t != null && t.phase == TranscodeUi.Phase.OFFER) {
            val head = if (s.items.size > 1) getString(R.string.push_notif_choose, t.index + 1) else getString(R.string.push_notif_choose_single)
            return base(head, getString(R.string.push_notif_choose_detail))
                .addAction(0, getString(R.string.push_transcode), action(ACTION_ACCEPT_TRANSCODE, 17))
                .apply { if (s.items.size > 1) addAction(0, getString(R.string.push_skip), action(ACTION_SKIP, 18)) }
                .apply { stop(this) }
                .build()
        }
        when (e.phase) {
            PushPhase.PREPARING, PushPhase.LOADING, PushPhase.IDLE ->
                return base(getString(R.string.push_notif_connecting, name), s.current?.title.orEmpty()).apply { stop(this) }.build()
            PushPhase.FAILED -> {
                val f = PushCopy.failure(e.failure?.error ?: com.benchan.wireless.screencasting.protocol.upnp.dmc.PushError.UNREACHABLE, name, s.current, s.ownApp)
                return base(getString(R.string.push_notif_failed, f.title), name).apply { stop(this) }.build()
            }
            PushPhase.AWAITING_CHOICE -> return base(title, "等你选择转换方式").apply { stop(this) }.build()
            else -> Unit
        }
        if (e.idleWarning) {
            return base(getString(R.string.push_notif_idle), getString(R.string.push_notif_idle_detail))
                .addAction(0, getString(R.string.push_keep_alive), action(ACTION_KEEP_ALIVE, 12))
                .apply { stop(this) }
                .build()
        }
        val cur = s.current
        val count = if (e.count > 1) "${e.index + 1} / ${e.count}" else null
        val times = if (cur?.kind != MediaKind.IMAGE && e.positionMs != null) "${PushCopy.time(e.positionMs)} / ${PushCopy.time(e.durationMs)}" else null
        val text = listOfNotNull(cur?.title, count, times).joinToString(" · ")
        // 锁屏公开版本：不含文件名（交互稿 P12，跟随系统锁屏通知隐私设置）
        val pubText = listOfNotNull(cur?.let { PushCopy.kindLabel(it.kind) }, count).joinToString(" · ")
        val pub = base(title, pubText).build()
        val b = base(title, text).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(pub)
        thumbs["${s.sessionId}-${e.index}"]?.let { b.setLargeIcon(it) }
        val photo = cur?.kind == MediaKind.IMAGE
        val compact = mutableListOf<Int>()
        var idx = 0
        if (e.count > 1) {
            b.addAction(android.R.drawable.ic_media_previous, getString(if (photo) R.string.push_prev_photo else R.string.push_prev), action(ACTION_PREV, 13)); idx++
        }
        if (!photo) {
            val playing = e.phase == PushPhase.PLAYING
            b.addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                getString(if (playing) R.string.push_pause else R.string.push_play), action(ACTION_TOGGLE, 14),
            )
            compact += idx++
        }
        if (e.count > 1) {
            b.addAction(android.R.drawable.ic_media_next, getString(if (photo) R.string.push_next_photo else R.string.push_next), action(ACTION_NEXT, 15)); idx++
        }
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.push_stop_push), action(ACTION_STOP, 16))
        compact += idx
        session?.let { ms -> b.setStyle(MediaStyleNotificationHelper.MediaStyle(ms).setShowActionsInCompactView(*compact.take(3).toIntArray())) }
        return b.build()
    }

    /** App 在后台时推送结束：静默通知写结束原因，60 秒后自动消失（交互稿 P12 / P14）。 */
    private fun postEndNotification(s: PushSessionState, ended: PushEndInfo) {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        val contentIntent = launch?.let { PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val title = if (ended.reason == PushEndReason.USER_STOPPED && ended.failure == null) getString(R.string.push_notif_ended_plain)
        else getString(R.string.push_notif_ended, PushCopy.endTitle(ended, s.target.name).first)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentTitle(title)
            .setContentText(getString(R.string.push_notif_ended_detail, s.target.name, PushCopy.minutes(ended.durationMs)?.removePrefix("约 ") ?: PushCopy.time(ended.durationMs)))
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setContentIntent(contentIntent)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(END_NOTIFICATION_ID, n) }
    }

    private fun action(a: String, req: Int): PendingIntent =
        PendingIntent.getService(this, req, Intent(this, MediaPushService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)

    override fun onDestroy() {
        releaseLocks()
        notificationController?.let { MediaController.releaseFuture(it) }
        notificationController = null
        session?.release()
        session = null
        player = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MediaPushService"
        const val CHANNEL_ID = "dlna_push"
        const val NOTIFICATION_ID = 0x5754
        private const val END_NOTIFICATION_ID = 0x5755
        private const val WAKE_RENEW_MS = 2 * 60_000L
        private const val START_GRACE_MS = 5_000L
        private const val COMMAND_STOP = "wsc.push.STOP"

        /** Media3 识别“媒体通知控制器”的连接提示键（MediaSessionImpl.isMediaNotificationController，1.11.1 实测）。 */
        internal const val MEDIA_NOTIFICATION_CONTROLLER_HINT = "androidx.media3.session.MediaNotificationManager"
        private const val EXTRA_SESSION_ID = "session_id"
        const val ACTION_START = "com.benchan.wireless.screencasting.action.PUSH_START"
        const val ACTION_STOP = "com.benchan.wireless.screencasting.action.PUSH_STOP"
        const val ACTION_TOGGLE = "com.benchan.wireless.screencasting.action.PUSH_TOGGLE"
        const val ACTION_NEXT = "com.benchan.wireless.screencasting.action.PUSH_NEXT"
        const val ACTION_PREV = "com.benchan.wireless.screencasting.action.PUSH_PREV"
        const val ACTION_CANCEL_TRANSCODE = "com.benchan.wireless.screencasting.action.PUSH_CANCEL_TRANSCODE"
        const val ACTION_KEEP_ALIVE = "com.benchan.wireless.screencasting.action.PUSH_KEEP_ALIVE"
        const val ACTION_ACCEPT_TRANSCODE = "com.benchan.wireless.screencasting.action.PUSH_ACCEPT_TRANSCODE"
        const val ACTION_SKIP = "com.benchan.wireless.screencasting.action.PUSH_SKIP"

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.push_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                        description = context.getString(R.string.push_channel_desc)
                        setShowBadge(false)
                    },
                )
            }
        }

        /** 用户点“推送”时（App 在前台）启动，并告诉服务这次会话的 ID（第 1 轮 PM P0-1）。 */
        fun start(context: Context, sessionId: String) {
            context.startForegroundService(Intent(context, MediaPushService::class.java).setAction(ACTION_START).putExtra(EXTRA_SESSION_ID, sessionId))
        }
    }
}
