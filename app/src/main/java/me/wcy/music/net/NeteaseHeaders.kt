package me.wcy.music.net

import me.wcy.music.storage.preference.ConfigPreferences

object NeteaseHeaders {
    val userAgent: String
        get() = ConfigPreferences.requestUserAgent.ifEmpty {
            "Mozilla/5.0 (Linux; Android 16; RMX8899 Build/BP2A.250605.015; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/138.0.7204.179 Mobile Safari/537.36 MMWEBID/8737 MicroMessenger/8.0.66.2963(0x28004243) WeChat/arm64 Weixin GPVersion/1 NetType/WIFI Language/zh_CN ABI/arm64"
        }
}
