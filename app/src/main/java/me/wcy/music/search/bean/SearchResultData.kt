package me.wcy.music.search.bean

import com.google.gson.annotations.SerializedName
import me.wcy.music.common.bean.PlaylistData
import me.wcy.music.common.bean.SongData

/**
 * Created by wangchenyan.top on 2023/9/20.
 */
data class SearchResultData(
    @SerializedName("songs")
    val songs: List<SongData> = emptyList(),
    @SerializedName("songCount")
    val songCount: Int = 0,
    @SerializedName("playlists")
    val playlists: List<PlaylistData> = emptyList(),
    @SerializedName("playlistCount")
    val playlistCount: Int = 0,
    @SerializedName("artists")
    val artists: List<SearchArtistData> = emptyList(),
    @SerializedName("artistCount")
    val artistCount: Int = 0,
)

data class SearchArtistData(
    @SerializedName("id")
    val id: Long = 0,
    @SerializedName("name")
    val name: String = "",
    @SerializedName("picUrl")
    val picUrl: String = "",
    @SerializedName("img1v1Url")
    val img1v1Url: String = "",
    @SerializedName("albumSize")
    val albumSize: Int = 0,
    @SerializedName("musicSize")
    val musicSize: Int = 0,
    @SerializedName("mvSize")
    val mvSize: Int = 0,
    @SerializedName("alias")
    val alias: List<String> = emptyList(),
    @SerializedName("trans")
    val trans: String = "",
) {
    fun getCover(): String {
        return img1v1Url.ifEmpty { picUrl }
    }
}
