package me.wcy.music.discover.playlist.detail.bean

import com.google.gson.annotations.SerializedName
import me.wcy.music.common.bean.AlbumData
import me.wcy.music.common.bean.SongData

data class AlbumDetailData(
    @SerializedName("code")
    val code: Int = 0,
    @SerializedName("album")
    val album: AlbumData = AlbumData(),
    @SerializedName("songs")
    val songs: List<SongData> = emptyList()
)

data class ArtistAlbumListData(
    @SerializedName("code")
    val code: Int = 0,
    @SerializedName("artist")
    val artist: me.wcy.music.common.bean.ArtistData = me.wcy.music.common.bean.ArtistData(),
    @SerializedName("hotAlbums")
    val hotAlbums: List<AlbumData> = emptyList(),
    @SerializedName("more")
    val more: Boolean = false
)
