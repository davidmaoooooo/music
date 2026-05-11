package me.wcy.music.mine.bean

import com.google.gson.annotations.SerializedName
import me.wcy.music.common.bean.PlaylistData

data class RecentPlaylistListData(
    @SerializedName("code")
    val code: Int = 0,
    @SerializedName("data")
    val data: RecentPlaylistWrap = RecentPlaylistWrap()
)

data class RecentPlaylistWrap(
    @SerializedName("list", alternate = ["playlists"])
    val list: List<RecentPlaylistItemData> = emptyList()
)

data class RecentPlaylistItemData(
    @SerializedName("data", alternate = ["playlist"])
    val playlist: PlaylistData = PlaylistData()
)
