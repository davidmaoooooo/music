package me.wcy.music.main

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat
import dagger.hilt.android.AndroidEntryPoint
import me.wcy.music.R
import me.wcy.music.common.BaseMusicActivity
import me.wcy.music.consts.PreferenceName
import me.wcy.music.storage.preference.ConfigPreferences
import me.wcy.router.annotation.Route
import top.wangchenyan.common.R as CommonR
import top.wangchenyan.common.widget.TitleLayout

@Route("/debug/settings")
@AndroidEntryPoint
class DebugSettingsActivity : BaseMusicActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<TitleLayout>(CommonR.id.common_title_layout)?.setTitleText("调试设置")
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, DebugSettingsFragment())
            .commitAllowingStateLoss()
    }

    class DebugSettingsFragment : PreferenceFragmentCompat() {
        private val mainHandler = Handler(Looper.getMainLooper())

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = PreferenceName.CONFIG
            addPreferencesFromResource(R.xml.preference_debug_setting)
            alignPreferencesLeft(preferenceScreen)
            sanitizeExclusiveSwitches()
            bindEditText(
                getString(R.string.setting_key_api_domain),
                ConfigPreferences.apiDomain,
                "修改后建议重启应用"
            )
            bindEditText(
                getString(R.string.setting_key_listen_together_worker_url),
                ConfigPreferences.sanitizeListenTogetherWorkerUrl(
                    ConfigPreferences.listenTogetherWorkerUrl
                ),
                "留空则使用默认服务器，填写则使用自定义地址"
            )
            bindSwitch(
                getString(R.string.setting_key_listen_together_enabled),
                ConfigPreferences.listenTogetherEnabled,
                "播放页和侧边栏会显示一起听入口"
            ) { enabled ->
                if (!enabled) {
                    me.wcy.music.listen.ListenTogetherManager.leave(notifyPeer = true)
                    ConfigPreferences.listenTogetherDebugLog = false
                }
                scheduleSwitchStateRefresh()
            }
            bindSwitch(
                getString(R.string.setting_key_listen_together_debug_log),
                ConfigPreferences.listenTogetherDebugLog,
                "会在 Download 目录写入一起听调试日志"
            )
            bindSwitch(
                getString(R.string.setting_key_third_party_source_enabled),
                ConfigPreferences.thirdPartySourceEnabled,
                "兼容落雪音乐音源"
            ) { enabled ->
                if (!enabled) {
                    me.wcy.music.source.ThirdPartySourceRuntime.clear()
                    ConfigPreferences.thirdPartySourceDebugLog = false
                }
                scheduleSwitchStateRefresh()
            }
            bindSwitch(
                getString(R.string.setting_key_third_party_source_debug_log),
                ConfigPreferences.thirdPartySourceDebugLog,
                "会在 Download 目录写入音源调试日志"
            ) { enabled ->
                if (!enabled) {
                    me.wcy.music.source.ThirdPartySourceDebugLogger.reset()
                }
            }
            bindEditText(
                getString(R.string.setting_key_song_request_cookie),
                ConfigPreferences.songRequestCookie,
                "留空时使用当前登录用户 Cookie"
            )
            bindEditText(
                getString(R.string.setting_key_request_user_agent),
                ConfigPreferences.requestUserAgent
            )
            updateSwitchStates()
        }

        private fun alignPreferencesLeft(group: PreferenceGroup?) {
            group ?: return
            group.isIconSpaceReserved = false
            for (index in 0 until group.preferenceCount) {
                val preference = group.getPreference(index)
                preference.isIconSpaceReserved = false
                if (preference is PreferenceGroup) {
                    alignPreferencesLeft(preference)
                }
            }
        }

        private fun bindEditText(key: String, value: String, extraSummary: String = "") {
            val preference = findPreference<EditTextPreference>(key) ?: return
            val isListenTogetherWorkerUrl =
                key == getString(R.string.setting_key_listen_together_worker_url)
            preference.text = value
            preference.summary = buildSummary(value, extraSummary)
            preference.setOnPreferenceChangeListener { pref, newValue ->
                val input = newValue?.toString().orEmpty()
                val text = if (isListenTogetherWorkerUrl) {
                    ConfigPreferences.sanitizeListenTogetherWorkerUrl(input)
                } else {
                    input
                }
                pref.summary = buildSummary(text, extraSummary)
                if (text != input) {
                    preference.text = text
                    false
                } else {
                    true
                }
            }
        }

        private fun buildSummary(value: String, extra: String): String {
            val display = value.ifEmpty { "未设置" }
            return if (extra.isEmpty()) display else "$display\n$extra"
        }

        private fun bindSwitch(
            key: String,
            value: Boolean,
            enabledSummary: String,
            onChanged: (Boolean) -> Unit = {}
        ) {
            val preference = findPreference<SwitchPreferenceCompat>(key) ?: return
            preference.isChecked = value
            preference.summary = buildSwitchSummary(value, enabledSummary)
            preference.setOnPreferenceChangeListener { pref, newValue ->
                val checked = newValue as? Boolean == true
                pref.summary = buildSwitchSummary(checked, enabledSummary)
                onChanged(checked)
                true
            }
        }

        private fun buildSwitchSummary(value: Boolean, enabledSummary: String): String {
            return if (value) enabledSummary else "已关闭"
        }

        private fun scheduleSwitchStateRefresh() {
            mainHandler.post {
                sanitizeExclusiveSwitches()
                updateSwitchStates()
            }
        }

        private fun sanitizeExclusiveSwitches() {
            if (ConfigPreferences.listenTogetherEnabled && ConfigPreferences.thirdPartySourceEnabled) {
                ConfigPreferences.thirdPartySourceEnabled = false
                ConfigPreferences.thirdPartySourceDebugLog = false
                me.wcy.music.source.ThirdPartySourceRuntime.clear()
            }
            if (!ConfigPreferences.listenTogetherEnabled && ConfigPreferences.listenTogetherDebugLog) {
                ConfigPreferences.listenTogetherDebugLog = false
            }
            if (!ConfigPreferences.thirdPartySourceEnabled && ConfigPreferences.thirdPartySourceDebugLog) {
                ConfigPreferences.thirdPartySourceDebugLog = false
                me.wcy.music.source.ThirdPartySourceDebugLogger.reset()
            }
        }

        private fun updateSwitchStates() {
            val listenSwitch = findPreference<SwitchPreferenceCompat>(
                getString(R.string.setting_key_listen_together_enabled)
            )
            val listenDebugSwitch = findPreference<SwitchPreferenceCompat>(
                getString(R.string.setting_key_listen_together_debug_log)
            )
            val sourceSwitch = findPreference<SwitchPreferenceCompat>(
                getString(R.string.setting_key_third_party_source_enabled)
            )
            val sourceDebugSwitch = findPreference<SwitchPreferenceCompat>(
                getString(R.string.setting_key_third_party_source_debug_log)
            )

            val listenEnabled = ConfigPreferences.listenTogetherEnabled
            val sourceEnabled = ConfigPreferences.thirdPartySourceEnabled

            listenSwitch?.isChecked = listenEnabled
            listenSwitch?.isEnabled = !sourceEnabled
            listenSwitch?.summary = when {
                sourceEnabled -> "请先关闭第三方音源"
                listenEnabled -> "播放页和侧边栏会显示一起听入口"
                else -> "已关闭"
            }

            sourceSwitch?.isChecked = sourceEnabled
            sourceSwitch?.isEnabled = !listenEnabled
            sourceSwitch?.summary = when {
                listenEnabled -> "请先关闭一起听功能"
                sourceEnabled -> "兼容落雪音乐音源"
                else -> "已关闭"
            }

            listenDebugSwitch?.isChecked = ConfigPreferences.listenTogetherDebugLog
            listenDebugSwitch?.isEnabled = listenEnabled
            listenDebugSwitch?.summary = if (listenEnabled) {
                buildSwitchSummary(
                    ConfigPreferences.listenTogetherDebugLog,
                    "会在 Download 目录写入一起听调试日志"
                )
            } else {
                "请先开启一起听功能"
            }

            sourceDebugSwitch?.isChecked = ConfigPreferences.thirdPartySourceDebugLog
            sourceDebugSwitch?.isEnabled = sourceEnabled
            sourceDebugSwitch?.summary = if (sourceEnabled) {
                buildSwitchSummary(
                    ConfigPreferences.thirdPartySourceDebugLog,
                    "会在 Download 目录写入音源调试日志"
                )
            } else {
                "请先开启第三方音源"
            }
        }
    }
}
