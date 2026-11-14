package com.benchan.wireless.screencasting.core.data.settings

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsStore.edit { block(it) }
    }

    companion object {
        const val MAX_NAME = 40
    }
}
