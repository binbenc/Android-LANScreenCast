package com.benchan.wireless.screencasting.core.data.settings

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.benchan.wireless.screencasting.core.control.TvControlMode
import com.benchan.wireless.screencasting.core.protocol.quality.QualityProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 设置只存本地（PRD 8.5），由 DataStore 持久化，备份已在 data_extraction_rules 中排除。 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val deviceName = stringPreferencesKey("device_name")
        val mode = stringPreferencesKey("mode")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val quality = stringPreferencesKey("quality")
        val autoAdjust = booleanPreferencesKey("auto_adjust")
        val audio = booleanPreferencesKey("audio")
        val policy = stringPreferencesKey("connection_policy")
        val dlna = booleanPreferencesKey("dlna")
        val dlnaConfirm = booleanPreferencesKey("dlna_confirm")
        val apple = booleanPreferencesKey("apple_compat")
        val applePin = booleanPreferencesKey("apple_compat_pin")
        val boot = booleanPreferencesKey("boot_start")
        val skipIntro = booleanPreferencesKey("skip_capture_intro")
        val remoteControl = booleanPreferencesKey("remote_control")
        val tvControlMode = stringPreferencesKey("tv_control_mode")
        val tvControlHint = booleanPreferencesKey("tv_control_hint_shown")
        val remoteAutoDisable = booleanPreferencesKey("remote_control_auto_disable")
        val remoteAutoDisabled = booleanPreferencesKey("remote_control_auto_disabled")
        val pinchHint = booleanPreferencesKey("pinch_hint_shown")
        val directShare = booleanPreferencesKey("direct_share")
        val shareIntro = booleanPreferencesKey("share_intro_dismissed")
        val elder = booleanPreferencesKey("elder_mode")
        val elderTargets = stringPreferencesKey("elder_targets")
        val elderRecommend = booleanPreferencesKey("elder_recommend_dismissed")
        val elderExitNotice = booleanPreferencesKey("elder_exit_notice")
        val elderPrevQuality = stringPreferencesKey("elder_prev_quality")
        val elderPrevAuto = booleanPreferencesKey("elder_prev_auto_adjust")
        val elderPrevSkipIntro = booleanPreferencesKey("elder_prev_skip_intro")
    }

    /** 默认设备名 = 机型名（PRD 8.6）。 */
    val defaultDeviceName: String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p ->
        AppSettings(
            deviceName = p[Keys.deviceName]?.takeIf { it.isNotBlank() } ?: defaultDeviceName,
            mode = p[Keys.mode]?.let { runCatching { AppMode.valueOf(it) }.getOrNull() },
            onboardingDone = p[Keys.onboardingDone] ?: false,
            quality = p[Keys.quality]?.let { runCatching { QualityProfile.valueOf(it) }.getOrNull() } ?: QualityProfile.DEFAULT,
            autoAdjustQuality = p[Keys.autoAdjust] ?: true,
            audioEnabled = p[Keys.audio] ?: true,
            connectionPolicy = p[Keys.policy]?.let { runCatching { ConnectionPolicy.valueOf(it) }.getOrNull() }
                ?: ConnectionPolicy.TRUSTED_SKIP_CONFIRM,
            dlnaEnabled = p[Keys.dlna] ?: true,
            dlnaConfirmUnknown = p[Keys.dlnaConfirm] ?: true,
            appleCompatEnabled = p[Keys.apple] ?: false,
            appleCompatPin = p[Keys.applePin] ?: true,
            bootStart = p[Keys.boot] ?: false,
            skipCaptureIntro = p[Keys.skipIntro] ?: false,
            remoteControlEnabled = p[Keys.remoteControl] ?: false,
            tvControlMode = p[Keys.tvControlMode]?.let { runCatching { TvControlMode.valueOf(it) }.getOrNull() } ?: TvControlMode.CURSOR,
            tvControlHintShown = p[Keys.tvControlHint] ?: false,
            remoteControlAutoDisable = p[Keys.remoteAutoDisable] ?: false,
            remoteControlAutoDisabled = p[Keys.remoteAutoDisabled] ?: false,
            pinchHintShown = p[Keys.pinchHint] ?: false,
            directShareEnabled = p[Keys.directShare] ?: true,
            shareIntroDismissed = p[Keys.shareIntro] ?: false,
            elderModeEnabled = p[Keys.elder] ?: false,
            elderTargets = ElderTarget.decode(p[Keys.elderTargets]),
            elderRecommendDismissed = p[Keys.elderRecommend] ?: false,
            elderExitNotice = p[Keys.elderExitNotice] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setDeviceName(name: String) = edit { it[Keys.deviceName] = name.trim().take(MAX_NAME) }
    suspend fun setMode(mode: AppMode) = edit { it[Keys.mode] = mode.name }
    suspend fun setOnboardingDone() = edit { it[Keys.onboardingDone] = true }
    suspend fun setQuality(profile: QualityProfile) = edit { it[Keys.quality] = profile.name }
    suspend fun setAutoAdjust(enabled: Boolean) = edit { it[Keys.autoAdjust] = enabled }
    suspend fun setAudioEnabled(enabled: Boolean) = edit { it[Keys.audio] = enabled }
    suspend fun setConnectionPolicy(policy: ConnectionPolicy) = edit { it[Keys.policy] = policy.name }
    suspend fun setDlnaEnabled(enabled: Boolean) = edit { it[Keys.dlna] = enabled }
    suspend fun setDlnaConfirmUnknown(enabled: Boolean) = edit { it[Keys.dlnaConfirm] = enabled }
    suspend fun setAppleCompat(enabled: Boolean, requirePin: Boolean) = edit {
        it[Keys.apple] = enabled
        it[Keys.applePin] = requirePin
    }
    suspend fun setBootStart(enabled: Boolean) = edit { it[Keys.boot] = enabled }
    suspend fun setSkipCaptureIntro(skip: Boolean) = edit { it[Keys.skipIntro] = skip }
    suspend fun setRemoteControlEnabled(enabled: Boolean) = edit { it[Keys.remoteControl] = enabled }
    suspend fun setTvControlMode(mode: TvControlMode) = edit { it[Keys.tvControlMode] = mode.name }
    suspend fun setTvControlHintShown() = edit { it[Keys.tvControlHint] = true }
    suspend fun setRemoteControlAutoDisable(enabled: Boolean) = edit { it[Keys.remoteAutoDisable] = enabled }
    suspend fun setRemoteControlAutoDisabled(disabled: Boolean) = edit { it[Keys.remoteAutoDisabled] = disabled }
    suspend fun setPinchHintShown() = edit { it[Keys.pinchHint] = true }
    suspend fun setDirectShareEnabled(enabled: Boolean) = edit { it[Keys.directShare] = enabled }
    suspend fun setShareIntroDismissed() = edit { it[Keys.shareIntro] = true }
    suspend fun setElderRecommendDismissed() = edit { it[Keys.elderRecommend] = true }
    suspend fun setElderExitNoticeShown() = edit { it[Keys.elderExitNotice] = false }

    /** 只改家人固定的电视（长辈设置页“换电视 / 改叫法”）。 */
    suspend fun setElderTargets(targets: List<ElderTarget>) = edit {
        it[Keys.elderTargets] = ElderTarget.encode(targets.take(ElderTarget.MAX_TARGETS))
    }

    /**
     * 开启长辈模式（PRD 8.1.4 B/D）：保存家人固定的电视；画质固定“均衡”+ 自动调节开、录屏前普通说明卡跳过
     * （长辈页面自己每次显示 E05 预演）。原来的这三项记下来，退出时恢复。
     */
    suspend fun enableElderMode(targets: List<ElderTarget>) = edit {
        if (it[Keys.elder] != true) {
            it[Keys.elderPrevQuality] = it[Keys.quality] ?: QualityProfile.DEFAULT.name
            it[Keys.elderPrevAuto] = it[Keys.autoAdjust] ?: true
            it[Keys.elderPrevSkipIntro] = it[Keys.skipIntro] ?: false
        }
        it[Keys.elder] = true
        it[Keys.elderTargets] = ElderTarget.encode(targets.take(ElderTarget.MAX_TARGETS))
        it[Keys.quality] = QualityProfile.BALANCED.name
        it[Keys.autoAdjust] = true
        it[Keys.skipIntro] = true
        it[Keys.elderExitNotice] = false
        // 已经开启过：设备列表的推荐卡不再出现
        it[Keys.elderRecommend] = true
    }

    /** 退出长辈模式：恢复开启前的画质 / 自动调节 / 说明卡设置；家人设置的电视与叫法保留（交互稿 E11）。 */
    suspend fun disableElderMode() = edit {
        if (it[Keys.elder] != true) return@edit
        it[Keys.elder] = false
        it[Keys.elderPrevQuality]?.let { q -> it[Keys.quality] = q }
        it[Keys.elderPrevAuto]?.let { a -> it[Keys.autoAdjust] = a }
        it[Keys.elderPrevSkipIntro]?.let { k -> it[Keys.skipIntro] = k }
        it[Keys.elderExitNotice] = true
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsStore.edit { block(it) }
    }

    companion object {
        const val MAX_NAME = 40
    }
}
