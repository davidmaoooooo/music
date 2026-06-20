package me.wcy.music.discover.playlist.detail.bean

import com.google.gson.annotations.SerializedName
import me.wcy.music.common.bean.SongData

data class HeartModeData(
    @SerializedName("code")
    val code: Int = 0,
    @SerializedName("data")
    val data: List<HeartModeItemData> = emptyList()
)

data class HeartModeItemData(
    @SerializedName("songInfo", alternate = ["song", "simpleSong"])
    val songInfo: SongData = SongData()
)
