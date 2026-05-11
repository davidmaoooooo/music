package me.wcy.music.source

import com.google.gson.annotations.SerializedName

data class ThirdPartySourceInfo(
    @SerializedName("id")
    val id: String = "",
    @SerializedName("name")
    val name: String = "",
    @SerializedName("fileName")
    val fileName: String = "",
    @SerializedName("importTime")
    val importTime: Long = 0L,
    @SerializedName("enabled")
    val enabled: Boolean = false
)

data class ThirdPartyMusicInfo(
    val id: Long,
    val name: String,
    val singer: String,
    val albumName: String,
    val interval: Long
)
