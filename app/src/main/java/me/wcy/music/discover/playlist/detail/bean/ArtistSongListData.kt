package me.wcy.music.discover.playlist.detail.bean

import com.google.gson.annotations.SerializedName
import me.wcy.music.common.bean.SongData

data class ArtistSongListData(
    @SerializedName("code")
    val code: Int = 0,
    @SerializedName("hotSongs")
    val hotSongs: List<SongData> = emptyList(),
    @SerializedName("songs")
    val songs: List<SongData> = emptyList(),
    @SerializedName("more")
    val more: Boolean = false,
    @SerializedName("total")
    val total: Int = 0
)
