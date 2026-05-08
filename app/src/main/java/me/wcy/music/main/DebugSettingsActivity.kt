package me.wcy.music.main

import android.os.Bundle
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
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = PreferenceName.CONFIG
            addPreferencesFromResource(R.xml.preference_debug_setting)
            alignPreferencesLeft(preferenceScreen)
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
                "已开启：播放页和侧边栏会显示一起听入口"
            ) { enabled ->
                if (!enabled) {
                    me.wcy.music.listen.ListenTogetherManager.leave(notifyPeer = true)
                }
            }
            bindSwitch(
                getString(R.string.setting_key_listen_together_debug_log),
                ConfigPreferences.listenTogetherDebugLog
            )
            bindEditText(
                getString(R.string.setting_key_song_request_cookie),
                ConfigPreferences.songRequestCookie,
                "留空时使用当前登录用户 Cookie"
            )
            bindEditText(
                getString(R.string.setting_key_request_user_agent),
                ConfigPreferences.requestUserAgent
            )
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
            enabledSummary: String = "已开启：会在 Download 目录写入一起听调试日志",
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
            return if (value) {
                enabledSummary
            } else {
                "已关闭"
            }
        }
    }
}
