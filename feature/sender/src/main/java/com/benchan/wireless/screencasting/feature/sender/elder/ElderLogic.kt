package com.benchan.wireless.screencasting.feature.sender.elder

import com.benchan.wireless.screencasting.core.data.devices.RecentDevice
import com.benchan.wireless.screencasting.core.data.dlna.DlnaRendererRecord
import com.benchan.wireless.screencasting.core.data.settings.ElderTarget
import com.benchan.wireless.screencasting.core.discovery.DiscoveredDevice
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyGrade
import com.benchan.wireless.screencasting.feature.sender.session.ConnectFailure
import com.benchan.wireless.screencasting.feature.sender.session.EndReason

/**
 * F24 长辈模式的纯逻辑（PRD 8.1.4，交互稿第 16 章）。不依赖 Android，单测覆盖。
 */

/** 长辈首页上的一台电视（家人固定的，或“上次成功投屏的设备”）。 */
data class ElderHomeTarget(
    val label: String,
    val name: String,
    val wscId: String?,
    val udn: String?,
    /** 家人固定的（false = 跟随上次成功投屏）。 */
    val pinned: Boolean,
) {
    val key: String get() = wscId ?: "dlna:$udn"
}

object ElderTargets {
    /**
     * 一键连的目标（决策 #41）：家人固定的 1~2 台，不随使用记录变化；未设置时退回“上次成功投屏的设备”
     * （镜像：最近一次通过认证的接收端；推送：最近一次推送成功的电视，取较新的那个）。都没有 = 空。
     */
    fun resolve(pinned: List<ElderTarget>, recent: List<RecentDevice>, dlna: List<DlnaRendererRecord>): List<ElderHomeTarget> {
        if (pinned.isNotEmpty()) {
            // 叫法为空 = 用完整设备名（不截断，EMU-F2）
            return pinned.take(ElderTarget.MAX_TARGETS).map { ElderHomeTarget(it.label.ifBlank { it.name }, it.name, it.wscId, it.udn, pinned = true) }
        }
        val lastMirror = recent.maxByOrNull { it.lastUsedAt }
        val lastPush = dlna.filter { it.lastSuccessAt != null }.maxByOrNull { it.lastSuccessAt!! }
        return when {
            lastMirror == null && lastPush == null -> emptyList()
            lastPush == null || (lastMirror != null && lastMirror.lastUsedAt >= lastPush.lastSuccessAt!!) ->
                listOf(ElderHomeTarget(lastMirror!!.name, lastMirror.name, lastMirror.deviceId, null, pinned = false))
            else -> listOf(ElderHomeTarget(lastPush.name, lastPush.name, null, lastPush.udn, pinned = false))
        }
    }

    /** 发现结果里对应的设备：镜像身份按 deviceId，推送身份按 UDN（本应用接收端的 DLNA 会并进它的 WSC 行）。 */
    fun match(t: ElderHomeTarget, devices: List<DiscoveredDevice>): DiscoveredDevice? =
        devices.firstOrNull { t.wscId != null && it.deviceId == t.wscId && it.canMirror }
            ?: devices.firstOrNull { t.udn != null && it.dlna?.udn == t.udn }
            ?: devices.firstOrNull { t.wscId != null && it.deviceId == t.wscId }
}

/** 一键连用哪个地址（修复：以前只用旧 IP）。 */
object QuickConnect {
    data class Address(val host: String, val port: Int, val fresh: Boolean)

    /** 优先用发现结果里同 deviceId 的新地址（电视换了 IP 也能连上），没发现时退回最近记录的旧地址。 */
    fun address(deviceId: String, recent: RecentDevice?, discovered: List<DiscoveredDevice>): Address? {
        discovered.firstOrNull { it.deviceId == deviceId && it.canMirror }?.let { return Address(it.host, it.port, fresh = true) }
        return recent?.let { Address(it.host, it.port, fresh = false) }
    }
}

/** 长辈版的结束 / 失败结果（交互稿 E08 / E09）。 */
enum class ElderOutcome {
    /** 正常停止（E09“已经停止”）。 */
    STOPPED,

    /** 手机锁屏后投屏停止（E09 锁屏变体，决策 #17）。 */
    LOCKED,
    NO_WIFI,
    NOT_FOUND,

    /** 只能放照片的电视没找到（检查项不提“局域投屏”，UX P1-10）。 */
    PUSH_NOT_FOUND,

    /** 电视找到了但不能放照片（本应用接收端关了接收推送，PM P1-4）。 */
    CANNOT_PUSH,

    /** 还没有可以投屏的电视（图块 / 没有目标，PM P1-6）。 */
    NO_TARGET,

    TV_REJECTED,

    /** 电视上没人点“允许”（等待超时，UX P1-7）。 */
    TV_NO_ANSWER,

    /** 电视正在被别的手机使用。 */
    TV_BUSY,

    /** 4 个数字输错太多次。 */
    PIN_LOCKED,
    CAPTURE_DENIED,

    /** 和电视断开了（重连 30 秒失败 / 对方关闭）。 */
    DISCONNECTED,

    /** 其他原因：只说没投上 + 请家人帮忙。 */
    OTHER,
}

object ElderOutcomes {
    fun of(reason: EndReason, failure: ConnectFailure?, hasLan: Boolean): ElderOutcome = when (reason) {
        EndReason.USER_STOPPED, EndReason.PROJECTION_STOPPED -> ElderOutcome.STOPPED
        EndReason.SCREEN_LOCKED -> ElderOutcome.LOCKED
        EndReason.CAPTURE_DENIED -> ElderOutcome.CAPTURE_DENIED
        EndReason.REJECTED -> ElderOutcome.TV_REJECTED
        EndReason.CONFIRM_TIMEOUT, EndReason.PIN_TIMEOUT -> ElderOutcome.TV_NO_ANSWER
        EndReason.PAIR_LOCKED -> ElderOutcome.PIN_LOCKED
        EndReason.BUSY -> ElderOutcome.TV_BUSY
        EndReason.RECONNECT_FAILED, EndReason.REMOTE_CLOSED -> if (hasLan) ElderOutcome.DISCONNECTED else ElderOutcome.NO_WIFI
        EndReason.RECEIVER_NO_DISPLAY -> if (hasLan) ElderOutcome.NOT_FOUND else ElderOutcome.NO_WIFI
        EndReason.CONNECT_FAILED -> when {
            !hasLan -> ElderOutcome.NO_WIFI
            // 已认证后断开（例如在 E05 停留期间）：说“断开了”，不说“没找到”（UX P1-18）
            failure == ConnectFailure.LOST -> ElderOutcome.DISCONNECTED
            failure == ConnectFailure.MEDIA_BLOCKED || failure == ConnectFailure.FIRST_FRAME_TIMEOUT -> ElderOutcome.OTHER
            else -> ElderOutcome.NOT_FOUND
        }
        else -> if (!hasLan) ElderOutcome.NO_WIFI else ElderOutcome.OTHER
    }
}

/** 长辈页面文案（交互稿 E01–E12）：不出现 DLNA、IP、延迟、码率；PIN 说成“4 个数字”；一句话只说一件事。 */
object ElderCopy {
    fun castTo(label: String) = "投屏到 $label"
    const val CAST_SUB = "把手机画面放到电视上"
    const val PHOTOS = "把照片和视频放到电视"
    fun photosTo(label: String) = "放照片和视频到 $label"
    const val PUSH_ONLY_NOTE = "这台电视只能放照片和视频"
    const val OTHER_DEVICES = "选其他设备"
    const val PICK_ONE = "选一台电视"
    const val SETTINGS = "设置"

    fun statusFinding(label: String) = "正在找 $label…"
    fun statusFound(label: String, pinned: Boolean) = if (pinned) "$label 已找到" else "上次用过的 $label 已找到"
    fun statusMissing(label: String) = "没找到 $label，点按钮会再找一次"

    /** 主按钮合并朗读：“投屏到客厅电视，按钮。电视已找到”。 */
    fun mainButtonSpoken(label: String, found: Boolean?) = "投屏到$label。" + when (found) {
        true -> "电视已找到"
        false -> "电视还没找到"
        null -> "正在找电视"
    }

    /**
     * E05 录屏提示预演：按系统版本与系统语言区分（预演要和真提示同样文字，EMU-F3）。
     * Android 14+ 用整屏预设发起，系统通常不再让选范围；个别 ROM 仍出现时第二行提醒（UX P1-9，按真机对照表 Q15-G 再校正）。
     */
    fun capturePrompt(sdk: Int, zh: Boolean = true): Pair<String, String> =
        "下一步，手机会弹出一个提示" to "请点「${captureButton(sdk, zh)}」"

    fun captureScopeHint(sdk: Int, zh: Boolean = true): String? = if (sdk >= 34) "如果让你选，请选「${if (zh) "整个屏幕" else "Entire screen"}」" else null

    /** 系统录屏提示上的按钮文字（AOSP：中文“立即开始 / 开始”，英文“Start now / Start”）。 */
    fun captureButton(sdk: Int, zh: Boolean = true) = when {
        sdk >= 34 -> if (zh) "开始" else "Start"
        else -> if (zh) "立即开始" else "Start now"
    }

    fun connecting(label: String) = "正在连接 $label…"
    const val TV_CONFIRM = "请用电视遥控器，在电视上点「允许」"
    const val PIN_TITLE = "电视需要重新确认"
    const val PIN_BODY = "请看电视屏幕上的 4 个数字，在下面输入"
    const val PIN_WRONG = "数字不对，请再看一下电视"

    fun castingTo(label: String) = "正在投到 $label"
    fun quality(grade: LatencyGrade?): String = if (grade == LatencyGrade.POOR || grade == LatencyGrade.FAIR) "网络不太好，可能会卡" else "画面正常"
    fun elapsed(ms: Long): String {
        val min = ms / 60_000
        return if (min < 1) "刚开始投屏" else if (min < 60) "已经投了 $min 分钟" else "已经投了 ${min / 60} 小时 ${min % 60} 分钟"
    }
    const val CASTING_HINT = "现在打开照片、视频或其他 App，电视上就能看到。"
    const val RECONNECTING = "网络不稳定，正在重新连接…"
    const val RECONNECTING_SUB = "不用做什么，连上后会自动继续"
    const val STOP = "停止投屏"

    /** E08 失败卡：标题 / 正文。 */
    fun failure(o: ElderOutcome, label: String, sdk: Int = 34, zh: Boolean = true): Pair<String, String> = when (o) {
        ElderOutcome.NO_WIFI -> "手机没有连上家里的 Wi‑Fi" to "连上之后会自动再试一次"
        ElderOutcome.NOT_FOUND -> "没找到 $label" to "请看看：\n① 电视开着吗？\n② 电视上打开「局域投屏」了吗？"
        ElderOutcome.PUSH_NOT_FOUND -> "没找到 $label" to "请看看：\n① 电视开着吗？\n② 电视和手机连的是家里同一个 Wi‑Fi 吗？"
        ElderOutcome.CANNOT_PUSH -> "$label 暂时不能放照片和视频" to "请家人在电视的设置里打开接收照片和视频"
        ElderOutcome.NO_TARGET -> "还没有可以投屏的电视" to "请家人帮忙设置"
        ElderOutcome.TV_REJECTED -> "电视上点了拒绝" to "要投屏时，请在电视上点「允许」"
        ElderOutcome.TV_NO_ANSWER -> "电视上没有人点「允许」" to "请拿起遥控器，再试一次时在电视上点「允许」"
        ElderOutcome.TV_BUSY -> "$label 正在被别的手机使用" to "请先在那台手机上停止投屏，再试一次"
        ElderOutcome.PIN_LOCKED -> "数字输错太多次了" to "请过一会儿再试，或者请家人帮忙"
        ElderOutcome.CAPTURE_DENIED -> "手机需要你同意才能投屏" to "再试一次时，请点「${captureButton(sdk, zh)}」"
        ElderOutcome.DISCONNECTED -> "和电视断开了" to "可能是电视关了，或者网络断了一下"
        ElderOutcome.OTHER -> "没能投到 $label" to "可以再试一次，或者请家人帮忙"
        ElderOutcome.STOPPED, ElderOutcome.LOCKED -> ended(o, photos = false)
    }

    fun ended(o: ElderOutcome, photos: Boolean): Pair<String, String> = when {
        photos -> "已经停止放照片" to "电视上不再显示照片和视频"
        o == ElderOutcome.LOCKED -> "手机锁屏后，投屏停止了" to "投屏时请不要锁屏"
        else -> "已经停止" to "电视上不再显示手机画面"
    }

    /** 技术摘要里的问题描述（给家人看）。 */
    fun problem(o: ElderOutcome): String = when (o) {
        ElderOutcome.NO_WIFI -> "手机没有局域网"
        ElderOutcome.NOT_FOUND -> "找不到电视（8 秒无回应）"
        ElderOutcome.PUSH_NOT_FOUND -> "找不到只能放照片的电视（8 秒无回应）"
        ElderOutcome.CANNOT_PUSH -> "电视已发现，但没有打开接收推送"
        ElderOutcome.NO_TARGET -> "没有设置可以投屏的电视"
        ElderOutcome.TV_REJECTED -> "电视端拒绝"
        ElderOutcome.TV_NO_ANSWER -> "电视端没有确认（超时）"
        ElderOutcome.TV_BUSY -> "电视正被其他手机使用"
        ElderOutcome.PIN_LOCKED -> "数字输错次数过多，暂时锁定"
        ElderOutcome.CAPTURE_DENIED -> "录屏授权被取消"
        ElderOutcome.DISCONNECTED -> "投屏中断开，30 秒内没有恢复"
        ElderOutcome.OTHER -> "连接失败（其他原因）"
        ElderOutcome.STOPPED -> "已停止"
        ElderOutcome.LOCKED -> "锁屏后停止"
    }

    fun photoCounter(index: Int, count: Int) = "第 ${index + 1} 张，共 $count 张"
    fun itemCounter(index: Int, count: Int) = "第 ${index + 1} 个，共 $count 个"
    fun transcodeOffer(minutes: String?) = "电视可能放不了这个视频。手机可以先转换一下（${minutes ?: "需要一会儿"}，手机会发热），要转换吗？"
    fun transcodeRunning(p: Int, eta: String?) = "正在转换 $p%" + (eta?.let { "，$it" } ?: "")
}

/**
 * “请家人帮忙”（交互稿 E12）发给家人的纯文本：大字原因 + 技术摘要。
 * **不含** Wi‑Fi 名称、IP 地址、文件名或其他个人信息（PRD 8.1.4 F）。
 */
object ElderHelpText {
    data class Facts(
        val reasonTitle: String,
        val reasonDetail: String,
        val phoneModel: String,
        val androidVersion: String,
        val tvLabel: String?,
        val tvKind: String?,
        val problem: String,
        val hasLan: Boolean,
        /** 组播（mDNS / SSDP）是否有回应；null = 没检测。 */
        val multicastReply: Boolean?,
        /** 电视地址能否连通；null = 没检测。 */
        val tvReachable: Boolean?,
        val time: String,
    )

    fun summary(f: Facts): List<String> = listOfNotNull(
        "手机：${f.phoneModel} · Android ${f.androidVersion}",
        f.tvLabel?.let { "电视：$it" + (f.tvKind?.let { k -> "（$k）" } ?: "") },
        "问题：${f.problem}",
        "局域网：" + (if (f.hasLan) "已连接" else "未连接") + " · 组播：" + when (f.multicastReply) {
            true -> "有回应"
            false -> "无回应"
            null -> "未检测"
        },
        // 只说“发现结果里有没有这台电视”，不冒充地址探测（PM P2-18）
        "电视：" + when (f.tvReachable) {
            true -> "已发现"
            false -> "未发现"
            null -> "未检测"
        },
        "时间：${f.time}",
    )

    fun build(f: Facts): String = buildString {
        appendLine("【局域投屏 · 请帮忙看看】")
        appendLine(f.reasonTitle)
        appendLine(f.reasonDetail.replace('\n', ' '))
        appendLine()
        appendLine("给家人看的技术信息：")
        summary(f).forEach { appendLine(it) }
    }.trim()
}

/** 设备列表顶部“字太小？可以打开长辈模式”推荐卡（交互稿 E02）。 */
object ElderRecommend {
    fun shouldShow(fontScale: Float, displayLargest: Boolean, hasSuccessfulCast: Boolean, dismissed: Boolean, elderOn: Boolean): Boolean =
        !elderOn && !dismissed && hasSuccessfulCast && (fontScale >= 1.3f || displayLargest)
}
