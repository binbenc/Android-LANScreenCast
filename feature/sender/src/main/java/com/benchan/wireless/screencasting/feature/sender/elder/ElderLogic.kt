package com.benchan.wireless.screencasting.feature.sender.elder

import android.content.res.Resources
import com.benchan.wireless.screencasting.core.data.devices.RecentDevice
import com.benchan.wireless.screencasting.core.data.dlna.DlnaRendererRecord
import com.benchan.wireless.screencasting.core.data.settings.ElderTarget
import com.benchan.wireless.screencasting.core.discovery.DiscoveredDevice
import com.benchan.wireless.screencasting.core.protocol.quality.LatencyGrade
import com.benchan.wireless.screencasting.feature.sender.R
import com.benchan.wireless.screencasting.feature.sender.session.ConnectFailure
import com.benchan.wireless.screencasting.feature.sender.session.EndReason
import com.benchan.wireless.screencasting.feature.sender.ui.UiText
import com.benchan.wireless.screencasting.feature.sender.ui.uiPlural
import com.benchan.wireless.screencasting.feature.sender.ui.uiText

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
     * 固定的电视叫法为空时显示**最新**设备名（[currentName]），不再停留在设置时的名字快照。
     */
    fun resolve(
        pinned: List<ElderTarget>,
        recent: List<RecentDevice>,
        dlna: List<DlnaRendererRecord>,
        found: List<DiscoveredDevice> = emptyList(),
    ): List<ElderHomeTarget> {
        if (pinned.isNotEmpty()) {
            return pinned.take(ElderTarget.MAX_TARGETS).map {
                val name = currentName(it, found, recent, dlna)
                // 叫法为空 = 用完整设备名（不截断，EMU-F2）；家人填了叫法时始终用叫法
                ElderHomeTarget(it.label.ifBlank { name }, name, it.wscId, it.udn, pinned = true)
            }
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

    /**
     * 固定电视此刻的设备名：发现结果里同 wscId / UDN 的当前名字 > 最近设备（wscId）> DLNA 记录（UDN）> 设置时的快照。
     * 电视改名后（例如 sdk_gphone64_arm64 → 客厅电视）首页立即跟上。
     */
    fun currentName(t: ElderTarget, found: List<DiscoveredDevice>, recent: List<RecentDevice>, dlna: List<DlnaRendererRecord>): String =
        foundName(t.wscId, t.udn, found)
            ?: t.wscId?.let { id -> recent.firstOrNull { it.deviceId == id } }?.name?.takeIf { it.isNotBlank() }
            ?: t.udn?.let { u -> dlna.firstOrNull { it.udn == u } }?.name?.takeIf { it.isNotBlank() }
            ?: t.name

    /**
     * 用发现结果里的新名字刷新存储的快照（叫法不动）；没有变化返回 null（不写存储）。
     * 只认发现结果：最近设备 / DLNA 记录的名字在 [resolve] 里只用于显示。
     */
    fun refreshSnapshots(pinned: List<ElderTarget>, found: List<DiscoveredDevice>): List<ElderTarget>? {
        var changed = false
        val out = pinned.map { t ->
            val name = foundName(t.wscId, t.udn, found)
            if (name != null && name != t.name) {
                changed = true
                t.copy(name = name)
            } else {
                t
            }
        }
        return out.takeIf { changed }
    }

    /** 向导完成 / 预填时：用最新名字覆盖快照（发现结果 > 候选里的名字 > 原快照），叫法不动。 */
    fun withLatestNames(selected: List<ElderTarget>, candidates: List<ElderTarget>, found: List<DiscoveredDevice>): List<ElderTarget> =
        selected.map { t ->
            val name = foundName(t.wscId, t.udn, found)
                ?: candidates.firstOrNull { it.key == t.key }?.name?.takeIf { it.isNotBlank() }
                ?: t.name
            t.copy(name = name)
        }

    private fun foundName(wscId: String?, udn: String?, found: List<DiscoveredDevice>): String? =
        match(wscId, udn, found)?.name?.takeIf { it.isNotBlank() }

    /** 发现结果里对应的设备：镜像身份按 deviceId，推送身份按 UDN（本应用接收端的 DLNA 会并进它的 WSC 行）。 */
    fun match(t: ElderHomeTarget, devices: List<DiscoveredDevice>): DiscoveredDevice? = match(t.wscId, t.udn, devices)

    fun match(wscId: String?, udn: String?, devices: List<DiscoveredDevice>): DiscoveredDevice? =
        devices.firstOrNull { wscId != null && it.deviceId == wscId && it.canMirror }
            ?: devices.firstOrNull { udn != null && it.dlna?.udn == udn }
            ?: devices.firstOrNull { wscId != null && it.deviceId == wscId }
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

/**
 * 长辈页面文案（交互稿 E01–E12）：不出现 DLNA、IP、延迟、码率；PIN 说成“4 个数字”；一句话只说一件事。
 * 只返回资源引用 + 参数（[UiText]），按系统语言解析（values = 英文，values-zh = 中文）。
 * 例外：E05 预演里的系统按钮名（[captureButton]）模拟系统界面文字，按系统语言直接给字面量（EMU-F3）。
 */
object ElderCopy {
    fun castTo(label: String): UiText = uiText(R.string.elder_cast_to, label)
    val CAST_SUB: UiText = uiText(R.string.elder_cast_sub)
    val PHOTOS: UiText = uiText(R.string.elder_photos)
    fun photosTo(label: String): UiText = uiText(R.string.elder_photos_to, label)
    val PUSH_ONLY_NOTE: UiText = uiText(R.string.elder_push_only_note)
    val OTHER_DEVICES: UiText = uiText(R.string.elder_other_devices)
    val PICK_ONE: UiText = uiText(R.string.elder_pick_one)
    val SETTINGS: UiText = uiText(R.string.elder_settings)

    fun statusFinding(label: String): UiText = uiText(R.string.elder_status_finding, label)
    fun statusFound(label: String, pinned: Boolean): UiText = uiText(if (pinned) R.string.elder_status_found else R.string.elder_status_found_last, label)
    fun statusMissing(label: String): UiText = uiText(R.string.elder_status_missing, label)

    /** 主按钮合并朗读：“投屏到客厅电视，按钮。电视已找到”。 */
    fun mainButtonSpoken(label: String, found: Boolean?): UiText = uiText(
        R.string.elder_main_spoken, label,
        uiText(
            when (found) {
                true -> R.string.elder_spoken_found
                false -> R.string.elder_spoken_missing
                null -> R.string.elder_spoken_finding
            },
        ),
    )

    /**
     * E05 录屏提示预演：按系统版本与系统语言区分（预演要和真提示同样文字，EMU-F3）。
     * Android 14+ 用整屏预设发起，系统通常不再让选范围；个别 ROM 仍出现时第二行提醒（UX P1-9，按真机对照表 Q15-G 再校正）。
     */
    fun capturePrompt(sdk: Int, zh: Boolean = true): Pair<UiText, UiText> =
        uiText(R.string.elder_capture_title) to uiText(R.string.elder_capture_tap, captureButton(sdk, zh))

    fun captureScopeHint(sdk: Int, zh: Boolean = true): UiText? =
        if (sdk >= 34) uiText(R.string.elder_capture_scope, if (zh) "整个屏幕" else "Entire screen") else null

    /** 系统录屏提示上的按钮文字（AOSP：中文“立即开始 / 开始”，英文“Start now / Start”）。模拟系统文字，不走资源。 */
    fun captureButton(sdk: Int, zh: Boolean = true) = when {
        sdk >= 34 -> if (zh) "开始" else "Start"
        else -> if (zh) "立即开始" else "Start now"
    }

    fun connecting(label: String): UiText = uiText(R.string.elder_connecting, label)
    val TV_CONFIRM: UiText = uiText(R.string.elder_tv_confirm)
    val PIN_TITLE: UiText = uiText(R.string.elder_pin_title)
    val PIN_BODY: UiText = uiText(R.string.elder_pin_body)
    val PIN_WRONG: UiText = uiText(R.string.elder_pin_wrong)

    fun castingTo(label: String): UiText = uiText(R.string.elder_casting_to, label)

    /** 画面状态是否正常（决定绿点 / 黄点）。 */
    fun qualityGood(grade: LatencyGrade?): Boolean = !(grade == LatencyGrade.POOR || grade == LatencyGrade.FAIR)
    fun quality(grade: LatencyGrade?): UiText = uiText(if (qualityGood(grade)) R.string.elder_quality_good else R.string.elder_quality_poor)

    fun elapsed(ms: Long): UiText {
        val min = (ms / 60_000).toInt()
        return when {
            min < 1 -> uiText(R.string.elder_elapsed_just)
            min < 60 -> uiPlural(R.plurals.elder_elapsed_minutes, min)
            else -> uiText(R.string.elder_elapsed_hours, min / 60, min % 60)
        }
    }
    val CASTING_HINT: UiText = uiText(R.string.elder_casting_hint)
    val RECONNECTING: UiText = uiText(R.string.elder_reconnecting)
    val RECONNECTING_SUB: UiText = uiText(R.string.elder_reconnecting_sub)
    val STOP: UiText = uiText(R.string.elder_stop_casting)

    /** E08 失败卡：标题 / 正文。 */
    fun failure(o: ElderOutcome, label: String, sdk: Int = 34, zh: Boolean = true): Pair<UiText, UiText> = when (o) {
        ElderOutcome.NO_WIFI -> uiText(R.string.elder_fail_no_wifi_title) to uiText(R.string.elder_fail_no_wifi_body)
        ElderOutcome.NOT_FOUND -> uiText(R.string.elder_fail_not_found_title, label) to uiText(R.string.elder_fail_not_found_body)
        ElderOutcome.PUSH_NOT_FOUND -> uiText(R.string.elder_fail_not_found_title, label) to uiText(R.string.elder_fail_push_not_found_body)
        ElderOutcome.CANNOT_PUSH -> uiText(R.string.elder_fail_cannot_push_title, label) to uiText(R.string.elder_fail_cannot_push_body)
        ElderOutcome.NO_TARGET -> uiText(R.string.elder_fail_no_target_title) to uiText(R.string.elder_fail_no_target_body)
        ElderOutcome.TV_REJECTED -> uiText(R.string.elder_fail_rejected_title) to uiText(R.string.elder_fail_rejected_body)
        ElderOutcome.TV_NO_ANSWER -> uiText(R.string.elder_fail_no_answer_title) to uiText(R.string.elder_fail_no_answer_body)
        ElderOutcome.TV_BUSY -> uiText(R.string.elder_fail_busy_title, label) to uiText(R.string.elder_fail_busy_body)
        ElderOutcome.PIN_LOCKED -> uiText(R.string.elder_fail_pin_locked_title) to uiText(R.string.elder_fail_pin_locked_body)
        ElderOutcome.CAPTURE_DENIED -> uiText(R.string.elder_fail_capture_title) to uiText(R.string.elder_fail_capture_body, captureButton(sdk, zh))
        ElderOutcome.DISCONNECTED -> uiText(R.string.elder_fail_disconnected_title) to uiText(R.string.elder_fail_disconnected_body)
        ElderOutcome.OTHER -> uiText(R.string.elder_fail_other_title, label) to uiText(R.string.elder_fail_other_body)
        ElderOutcome.STOPPED, ElderOutcome.LOCKED -> ended(o, photos = false)
    }

    fun ended(o: ElderOutcome, photos: Boolean): Pair<UiText, UiText> = when {
        photos -> uiText(R.string.elder_ended_photos_title) to uiText(R.string.elder_ended_photos_body)
        o == ElderOutcome.LOCKED -> uiText(R.string.elder_ended_locked_title) to uiText(R.string.elder_ended_locked_body)
        else -> uiText(R.string.elder_ended_title) to uiText(R.string.elder_ended_body)
    }

    /** 技术摘要里的问题描述（给家人看）。 */
    fun problem(o: ElderOutcome): UiText = uiText(
        when (o) {
            ElderOutcome.NO_WIFI -> R.string.elder_problem_no_wifi
            ElderOutcome.NOT_FOUND -> R.string.elder_problem_not_found
            ElderOutcome.PUSH_NOT_FOUND -> R.string.elder_problem_push_not_found
            ElderOutcome.CANNOT_PUSH -> R.string.elder_problem_cannot_push
            ElderOutcome.NO_TARGET -> R.string.elder_problem_no_target
            ElderOutcome.TV_REJECTED -> R.string.elder_problem_rejected
            ElderOutcome.TV_NO_ANSWER -> R.string.elder_problem_no_answer
            ElderOutcome.TV_BUSY -> R.string.elder_problem_busy
            ElderOutcome.PIN_LOCKED -> R.string.elder_problem_pin_locked
            ElderOutcome.CAPTURE_DENIED -> R.string.elder_problem_capture
            ElderOutcome.DISCONNECTED -> R.string.elder_problem_disconnected
            ElderOutcome.OTHER -> R.string.elder_problem_other
            ElderOutcome.STOPPED -> R.string.elder_problem_stopped
            ElderOutcome.LOCKED -> R.string.elder_problem_locked
        },
    )

    fun photoCounter(index: Int, count: Int): UiText = uiText(R.string.elder_photo_counter, index + 1, count)
    fun itemCounter(index: Int, count: Int): UiText = uiText(R.string.elder_item_counter, index + 1, count)

    /** 预计耗时：“大约 6 分钟” / “不到 1 分钟”；null = 不知道。 */
    fun duration(ms: Long?): UiText? = when {
        ms == null -> null
        ms < 60_000 -> uiText(R.string.elder_duration_under)
        else -> ((ms + 30_000) / 60_000).toInt().let { uiPlural(R.plurals.elder_duration_about, it) }
    }

    /** 剩余时间：“大约还要 6 分钟” / “还要不到 1 分钟”。 */
    fun eta(ms: Long?): UiText? = when {
        ms == null -> null
        ms < 60_000 -> uiText(R.string.elder_eta_under)
        else -> ((ms + 30_000) / 60_000).toInt().let { uiPlural(R.plurals.elder_eta, it) }
    }

    fun transcodeOffer(minutes: UiText?): UiText = uiText(R.string.elder_transcode_offer, minutes ?: uiText(R.string.elder_duration_unknown))
    fun transcodeRunning(p: Int, eta: UiText?): UiText =
        if (eta == null) uiText(R.string.elder_transcode_running, p) else uiText(R.string.elder_transcode_running_eta, p, eta)
}

/**
 * “请家人帮忙”（交互稿 E12）发给家人的纯文本：大字原因 + 技术摘要。
 * **不含** Wi‑Fi 名称、IP 地址、文件名或其他个人信息（PRD 8.1.4 F）。
 */
object ElderHelpText {
    data class Facts(
        val reasonTitle: UiText,
        val reasonDetail: UiText,
        val phoneModel: String,
        val androidVersion: String,
        val tvLabel: String?,
        val tvKind: UiText?,
        val problem: UiText,
        val hasLan: Boolean,
        /** 组播（mDNS / SSDP）是否有回应；null = 没检测。 */
        val multicastReply: Boolean?,
        /** 电视地址能否连通；null = 没检测。 */
        val tvReachable: Boolean?,
        val time: String,
    )

    fun summary(f: Facts): List<UiText> = listOfNotNull(
        uiText(R.string.elder_help_phone, f.phoneModel, f.androidVersion),
        f.tvLabel?.let { l -> f.tvKind?.let { k -> uiText(R.string.elder_help_tv_kind, l, k) } ?: uiText(R.string.elder_help_tv, l) },
        uiText(R.string.elder_help_problem, f.problem),
        uiText(
            R.string.elder_help_network,
            uiText(if (f.hasLan) R.string.elder_help_connected else R.string.elder_help_disconnected),
            uiText(
                when (f.multicastReply) {
                    true -> R.string.elder_help_reply
                    false -> R.string.elder_help_no_reply
                    null -> R.string.elder_help_unchecked
                },
            ),
        ),
        // 只说“发现结果里有没有这台电视”，不冒充地址探测（PM P2-18）
        uiText(
            R.string.elder_help_tv,
            uiText(
                when (f.tvReachable) {
                    true -> R.string.elder_help_discovered
                    false -> R.string.elder_help_undiscovered
                    null -> R.string.elder_help_unchecked
                },
            ),
        ),
        uiText(R.string.elder_help_time, f.time),
    )

    /** 发出去的文本由 [res] 解析成当前系统语言。 */
    fun build(f: Facts, res: Resources): String = buildString {
        appendLine(res.getString(R.string.elder_help_header))
        appendLine(f.reasonTitle.resolve(res))
        appendLine(f.reasonDetail.resolve(res).replace('\n', ' '))
        appendLine()
        appendLine(res.getString(R.string.elder_help_tech))
        summary(f).forEach { appendLine(it.resolve(res)) }
    }.trim()
}

/** 设备列表顶部“字太小？可以打开长辈模式”推荐卡（交互稿 E02）。 */
object ElderRecommend {
    fun shouldShow(fontScale: Float, displayLargest: Boolean, hasSuccessfulCast: Boolean, dismissed: Boolean, elderOn: Boolean): Boolean =
        !elderOn && !dismissed && hasSuccessfulCast && (fontScale >= 1.3f || displayLargest)
}
