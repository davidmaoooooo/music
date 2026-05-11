package me.wcy.music.storage.preference

import com.blankj.utilcode.util.StringUtils
import me.wcy.music.R
import me.wcy.music.common.DarkModeService
import me.wcy.music.consts.PreferenceName
import top.wangchenyan.common.CommonApp
import top.wangchenyan.common.storage.IPreferencesFile
import top.wangchenyan.common.storage.PreferencesFile

/**
 * SharedPreferences工具类
 * Created by wcy on 2015/11/28.
 */
object ConfigPreferences :
    IPreferencesFile by PreferencesFile(CommonApp.app, PreferenceName.CONFIG, false) {
    const val DEFAULT_LISTEN_TOGETHER_WORKER_URL = "https://ncm.mjz0227.ccwu.cc"

    var playSoundQuality by IPreferencesFile.StringProperty(
        StringUtils.getString(R.string.setting_key_play_sound_quality),
        "standard"
    )

    var downloadSoundQuality by IPreferencesFile.StringProperty(
        StringUtils.getString(R.string.setting_key_download_sound_quality),
        "standard"
    )

    var filterSize by IPreferencesFile.StringProperty(
        StringUtils.getString(R.string.setting_key_filter_size),
        "0"
    )

    var filterTime by IPreferencesFile.StringProperty(
        StringUtils.getString(R.string.setting_key_filter_time),
        "0"
    )

    var darkMode by IPreferencesFile.StringProperty(
        "dark_mode",
        DarkModeService.DarkMode.Auto.value
    )

    var themeColorMode by IPreferencesFile.StringProperty("theme_color_mode", "blue")

    var customThemeColorMode by IPreferencesFile.StringProperty("custom_theme_color_mode", "teal")

    var customThemeColorHex by IPreferencesFile.StringProperty("custom_theme_color_hex", "#00897B")

    var playMode: Int by IPreferencesFile.IntProperty("play_mode", 0)

    var currentSongId: String by IPreferencesFile.StringProperty("current_song_id", "")

    var apiDomain: String by IPreferencesFile.StringProperty("api_domain", "https://music.163.com/")

    var musicCacheLimitMb: String by IPreferencesFile.StringProperty("music_cache_limit_mb", "200")

    var listenTogetherWorkerUrl: String by IPreferencesFile.StringProperty(
        "listen_together_worker_url",
        ""
    )

    val resolvedListenTogetherWorkerUrl: String
        get() = listenTogetherWorkerUrl.ifBlank { DEFAULT_LISTEN_TOGETHER_WORKER_URL }

    fun sanitizeListenTogetherWorkerUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        return trimmed.takeUnless {
            it.equals(DEFAULT_LISTEN_TOGETHER_WORKER_URL, ignoreCase = true)
        }.orEmpty()
    }

    var listenTogetherDebugLog: Boolean by IPreferencesFile.BooleanProperty(
        "listen_together_debug_log",
        false
    )

    var listenTogetherEnabled: Boolean by IPreferencesFile.BooleanProperty(
        "listen_together_enabled",
        false
    )

    var thirdPartySourceEnabled: Boolean by IPreferencesFile.BooleanProperty(
        "third_party_source_enabled",
        false
    )

    var thirdPartySourceDebugLog: Boolean by IPreferencesFile.BooleanProperty(
        "third_party_source_debug_log",
        false
    )

    var thirdPartySourceListJson: String by IPreferencesFile.StringProperty(
        "third_party_source_list_json",
        "[]"
    )

    var thirdPartySourceSelectedId: String by IPreferencesFile.StringProperty(
        "third_party_source_selected_id",
        ""
    )

    var songRequestCookie: String by IPreferencesFile.StringProperty("song_request_cookie", "")

    var requestUserAgent: String by IPreferencesFile.StringProperty(
        "request_user_agent",
        "Mozilla/5.0 (Linux; Android 16; RMX8899 Build/BP2A.250605.015; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/138.0.7204.179 Mobile Safari/537.36 MMWEBID/8737 MicroMessenger/8.0.66.2963(0x28004243) WeChat/arm64 Weixin GPVersion/1 NetType/WIFI Language/zh_CN ABI/arm64"
    )
}
