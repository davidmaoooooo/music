package me.wcy.music.mine.bean

import com.google.gson.annotations.SerializedName
import me.wcy.music.account.bean.ProfileData
import me.wcy.music.common.bean.PlaylistData
import me.wcy.music.common.bean.SongData

data class MineVirtualPlaylist(
    @SerializedName("id")
    val id: Long = 0L,
    @SerializedName("name")
    val name: String = "",
    @SerializedName("coverImgUrl")
    val coverImgUrl: String = "",
    @SerializedName("trackCount")
    val trackCount: Int = 0,
    @SerializedName("type")
    val type: String = ""
) {
    fun toPlaylistData(): PlaylistData {
        return PlaylistData(
            id = id,
            name = name,
            coverImgUrl = coverImgUrl,
            trackCount = trackCount,
            creator = ProfileData(
                userId = VIRTUAL_USER_ID,
                nickname = "音乐 Music"
            ),
            userId = VIRTUAL_USER_ID,
            description = when (type) {
                TYPE_RECENT_SONGS -> "根据账号最近播放歌曲生成"
                TYPE_LISTENING_RANK -> "根据账号近期听歌排行生成"
                else -> ""
            }
        )
    }

    companion object {
        const val TYPE_RECENT_SONGS = "recent_songs"
        const val TYPE_LISTENING_RANK = "listening_rank"
        const val VIRTUAL_USER_ID = -105L
    }
}

data class RecentSongListData(
    @SerializedName("code")
    val code: Int = 0,
    @SerializedName("data")
    val data: RecentSongWrap = RecentSongWrap()
)

data class RecentSongWrap(
    @SerializedName("list", alternate = ["songs"])
    val list: List<RecentSongItemData> = emptyList()
)

data class RecentSongItemData(
    @SerializedName("data", alternate = ["song"])
    val song: SongData = SongData()
)
