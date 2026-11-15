package com.benchan.wireless.screencasting.feature.sender.share

import com.benchan.wireless.screencasting.feature.sender.ui.push.PushCopy
import com.benchan.wireless.screencasting.protocol.upnp.MediaKind

/** 分享页文案（交互稿第 14 章 S01–S08）。纯逻辑，单测覆盖。 */
object ShareCopy {
    const val TITLE = "推送到电视"
    const val CLOSE_LABEL = "关闭，不推送"
    const val CLOSE_LABEL_CONTINUE = "关闭，推送继续"

    /** “3 个视频 · 3 张照片 · 2 首音乐”。 */
    /** 读屏用：把“ · ”换成逗号（各引擎对间隔号的读法不一，UX P2-6）。 */
    fun spoken(text: String): String = text.replace(" · ", "，")

    fun summary(kinds: List<MediaKind>): String {
        val v = kinds.count { it == MediaKind.VIDEO }
        val p = kinds.count { it == MediaKind.IMAGE }
        val a = kinds.count { it == MediaKind.AUDIO }
        return listOfNotNull(
            v.takeIf { it > 0 }?.let { "$it 个视频" },
            p.takeIf { it > 0 }?.let { "$it 张照片" },
            a.takeIf { it > 0 }?.let { "$it 首音乐" },
        ).joinToString(" · ")
    }

    fun ignored(n: Int) = "已忽略 $n 个文件" to "只能推送视频、照片和音乐"

    fun unreadable(n: Int, reason: UnreadableReason): Pair<String, String> = "有 $n 个文件读不到，已跳过" to unreadableDetail(reason)

    fun unreadableDetail(reason: UnreadableReason): String = when (reason) {
        UnreadableReason.NO_PERMISSION -> "分享它的应用没有允许读取"
        UnreadableReason.GENERIC -> "读不到这个文件。如果它在云盘里，可以先在云盘 App 里下载，再分享一次"
    }

    const val TRUNCATED = "一次最多推送 100 项，已取前 100 项"

    // —— 阻断结果卡（S05 ⑥⑦⑨⑩）——
    const val NOTHING_TITLE = "这些文件不能推送到电视"
    const val NOTHING_DETAIL = "可以推送视频、照片、音乐，或者视频网址。PDF、文档可以打开后用屏幕镜像在电视上看"
    const val UNREADABLE_TITLE = "读不到分享的文件"
    const val NO_URL_TITLE = "没有找到网址"
    const val NO_URL_DETAIL = "可以分享视频文件，或者以 .mp4、.m3u8 结尾的视频地址"
    const val NO_SPACE_TITLE = "手机空间不足"

    fun noSpaceDetail(itemCount: Int, required: Long, available: Long): String {
        val need = PushCopy.size(required) ?: "—"
        val have = PushCopy.size(available.coerceAtLeast(0)) ?: "—"
        return if (itemCount <= 1) "这个文件需要约 $need，当前可用 $have。清理空间后再分享，或者改用屏幕镜像"
        else "$itemCount 个文件需要约 $need，当前可用 $have"
    }

    const val PREPARE_FAILED_TITLE = "文件准备失败"
    const val PREPARE_FAILED_DETAIL = "分享的文件都没能准备好。可以再分享一次，或者改用屏幕镜像"
    fun notReady(n: Int) = "$n 个文件没准备好"

    /** 推送中某项复制失败（替代通用的“手机收回了读取授权”，PM P1-8）。 */
    fun copyFailure(f: CopyFailure): Pair<String, String> = when (f) {
        CopyFailure.NO_SPACE -> "手机空间不足，无法准备这个文件" to "清理空间后再分享一次，或者改用屏幕镜像"
        CopyFailure.TOO_LARGE -> "文件太大，无法准备" to "超过 4 GB 的云盘文件需要先下载到手机，再分享一次"
        CopyFailure.READ_FAILED -> "准备文件失败" to "读取中断了。可以再分享一次"
        CopyFailure.GONE -> "文件已不存在" to "它可能已被删除或移动"
    }

    fun onlyFirst(n: Int, bytes: Long) = "只推送前 $n 项（${PushCopy.size(bytes) ?: "—"}）"

    // —— 准备进度（S01 / S02 / S03）——

    /** 0~100；大小未知时 null。 */
    fun percent(copied: Long, total: Long?): Int? = total?.takeIf { it > 0 }?.let { ((copied * 100) / it).toInt().coerceIn(0, 100) }

    /** “正在准备文件 43%” / “正在准备文件 · 已读取 320 MB”。 */
    fun preparing(copied: Long, total: Long?): String {
        val p = percent(copied, total)
        return if (p != null) "正在准备文件 $p%" else "正在准备文件 · 已读取 ${PushCopy.size(copied) ?: "0 MB"}"
    }

    /** “1.2 GB / 2.8 GB”。 */
    fun bytes(copied: Long, total: Long?): String = if (total != null && total > 0) "${PushCopy.size(copied) ?: "0 MB"} / ${PushCopy.size(total)}" else "已读取 ${PushCopy.size(copied) ?: "0 MB"}"

    /** 剩余时间只按档变化（交互稿 S02：不逐秒跳；超过 60 秒改写“文件较大…可以先离开”）。 */
    fun remaining(ms: Long?): String? {
        ms ?: return null
        return when {
            ms <= 10_000 -> "约剩 10 秒"
            ms <= 20_000 -> "约剩 20 秒"
            ms <= 30_000 -> "约剩 30 秒"
            ms <= 60_000 -> "约剩 1 分钟"
            else -> "文件较大，约剩 ${(ms + 59_999) / 60_000} 分钟。可以先离开，准备好后自动播放"
        }
    }

    /** 读屏每 25% 播报一次（交互稿 S01 读屏）。 */
    fun announceStep(percent: Int?): Int? = percent?.let { (it / 25) * 25 }

    fun firstUseNote(transferVideo: Boolean) = "电视会直接从手机读取你分享的文件" to
        if (transferVideo) "不需要存储权限。照片和音乐会先复制到本应用里，视频由电视直接从手机读取；推送结束后立即删除副本、停止分享。电视自带投屏不加密。"
        else "不需要存储权限。文件会先复制到本应用里，推送结束后立即删除。电视自带投屏不加密。"

    // —— 网页地址（S04）——
    const val WEB_TITLE = "这是网页地址，电视通常打不开"
    const val WEB_DETAIL = "电视只能打开视频文件的地址（如 .mp4、.m3u8）。想在电视上看这个视频，可以把手机屏幕镜像到电视，再回到视频 App 播放。"
    const val WEB_MIRROR = "改用屏幕镜像"
    const val WEB_MIRROR_NOTE = "需要电视上装有本应用 · 手机会询问是否允许录屏"
    const val WEB_PUSH = "仍然推送"
    const val WEB_PUSH_NOTE = "电视会自己联网打开这个地址，本应用不会访问它"
    fun webMirroring(tv: String) = "正在镜像到 $tv。回到视频 App 直接播放，电视上就能看到。"

    /** 本应用里设备列表顶部的横幅（S04 改用镜像）。 */
    const val MIRROR_HINT = "选好电视开始镜像后，回到视频 App 播放即可"

    // —— 设备列表 ——
    const val GROUP_RECENT = "最近推送过"
    const val GROUP_FAMILY = "家人设置的电视"
    const val GROUP_DLNA = "电视自带投屏"
    const val GROUP_OWN = "本应用接收端"
    const val NEEDS_DLNA = "在这台电视的设置里打开“DLNA 接收”后才能推送"
    const val AUDIO_ONLY = "只能推送音乐"
    const val STILL_SEARCHING = "还在找… 请确认电视已开机，并且和手机连的是同一个路由器"
    const val EMPTY_TITLE = "没有发现可以推送的电视"
    const val EMPTY_DETAIL = "请确认电视已开机，并且和手机连的是同一个路由器。电视自带投屏还需要路由器允许设备互相发现。"
    const val NO_WIFI_TITLE = "手机没有连接 Wi‑Fi"
    const val NO_WIFI_DETAIL = "连上和电视同一个路由器后，会自动重新搜索。"

    fun directAudioOnly(name: String) = "$name 只能推送音乐"

    // —— 长辈模式（F24，决策：只列家人设置的电视和已经确认过的电视）——
    const val ELDER_NEW_TV = "要推送到新的电视，请家人在长辈设置里添加"
    const val ELDER_NEEDS_SETUP = "这台电视暂时不能放照片和视频，请家人帮忙设置"
    const val ELDER_OK_SUB = "可以放照片和视频"
    const val ELDER_AUDIO_SUB = "只能放音乐"

    // —— 修改网址（UX P2-18）——
    const val URL_LABEL = "视频网址"
    const val URL_CONFIRM = "用这个网址"

    fun directMissed(name: String) = "没有找到 $name。请确认电视已开机、和手机连的是同一个路由器"
    fun directFinding(name: String) = "正在找 $name…"

    // —— 冲突（S07）——
    fun bannerMirroring(tv: String) = "正在镜像到 $tv" to "镜像和推送不能同时进行"
    fun bannerPushing(tv: String, status: String) = "正在推送到 $tv" to "$status · 点它可以替换内容"
    fun stopMirrorTitle(to: String, connecting: Boolean) = if (connecting) "取消正在进行的连接，改为推送到 $to？" else "结束镜像，改为推送到 $to？"
    fun stopMirrorBody(from: String?) = (from?.let { "$it 会停止显示手机画面。" } ?: "电视会停止显示手机画面。")
    fun stopMirrorConfirm(connecting: Boolean) = if (connecting) "改为推送" else "结束镜像并推送"
    fun replaceTitle(tv: String) = "替换 $tv 上正在播放的内容？"
    fun replaceBody(current: String?, index: Int, count: Int, newSummary: String): String {
        val now = current?.let { if (count > 1) "正在播放“$it”（${index + 1} / $count）。" else "正在播放“$it”。" }.orEmpty()
        return "${now}替换后改为播放刚分享的 $newSummary，原来的播放列表不会保留。"
    }
    fun switchTitle(from: String, to: String) = "停止推送到 $from，改为推送到 $to？"
    const val TRANSCODE_TAIL = "正在进行的视频转换也会取消。"

    // —— 准备与连接（S02）/ 已开始（S03）——
    fun preparingFirst(multi: Boolean) = if (multi) "正在准备第 1 项" else "正在准备文件"
    fun connectStep(tv: String) = "连接 $tv"
    const val PLAY_STEP = "开始播放"
    const val LEAVE_NOTE = "第 1 项准备好就开始播放，其余边放边准备。现在离开这里，推送也不会中断。"
    const val CANCELLED_TOAST = "已取消，没有推送任何内容"
    fun started(tv: String, photo: Boolean) = if (photo) "已发送到 $tv" else "已在 $tv 上开始播放"
    fun restPreparing(n: Int, percent: Int?) = "其余 $n 项正在准备" + (percent?.let { " $it%" } ?: "")
    fun allReady(n: Int) = "全部 $n 项已准备好"
    fun itemSkipped(index: Int, gone: Boolean) = if (gone) "第 ${index + 1} 项已不存在，已跳过" else "第 ${index + 1} 项读取中断，已跳过"
    const val STARTED_NOTE = "离开后，可以在通知栏或锁屏上暂停、切换和结束推送。"
    const val REPLACED_SNACK = "已换成新分享的内容"
}
