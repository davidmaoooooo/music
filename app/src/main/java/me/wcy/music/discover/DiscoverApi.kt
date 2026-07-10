package me.wcy.music.discover

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.wcy.music.common.bean.LrcDataWrap
import me.wcy.music.common.bean.CommentListData
import me.wcy.music.common.bean.SongData
import me.wcy.music.common.bean.SongUrlData
import me.wcy.music.discover.banner.BannerListData
import me.wcy.music.discover.playlist.detail.bean.AlbumDetailData
import me.wcy.music.discover.playlist.detail.bean.ArtistAlbumListData
import me.wcy.music.discover.playlist.detail.bean.ArtistSongListData
import me.wcy.music.discover.playlist.detail.bean.HeartModeData
import me.wcy.music.discover.playlist.detail.bean.PlaylistDetailData
import me.wcy.music.discover.playlist.detail.bean.SongListData
import me.wcy.music.discover.playlist.square.bean.PlaylistListData
import me.wcy.music.discover.playlist.square.bean.PlaylistTagListData
import me.wcy.music.discover.recommend.song.bean.RecommendSongListData
import me.wcy.music.net.HttpClient
import me.wcy.music.storage.preference.ConfigPreferences
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import top.wangchenyan.common.net.NetResult
import top.wangchenyan.common.net.gson.GsonConverterFactory
import top.wangchenyan.common.utils.GsonUtils

/**
 * Created by wangchenyan.top on 2023/9/6.
 */
interface DiscoverApi {

    @POST("recommend/songs")
    suspend fun getRecommendSongs(): NetResult<RecommendSongListData>

    @POST("recommend/resource")
    suspend fun getRecommendPlaylists(): PlaylistListData

    @POST("song/url/v1")
    suspend fun getSongUrl(
        @Query("id") id: Long,
        @Query("level") level: String,
    ): NetResult<List<SongUrlData>>

    @POST("lyric")
    suspend fun getLrc(
        @Query("id") id: Long,
        @Query("lv") lv: Int = -1,
        @Query("tv") tv: Int = -1,
    ): LrcDataWrap

    @POST("playlist/detail")
    suspend fun getPlaylistDetail(
        @Query("id") id: Long,
    ): PlaylistDetailData

    @POST("playlist/track/all")
    suspend fun getPlaylistSongList(
        @Query("id") id: Long,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Query("timestamp") timestamp: Long? = null
    ): SongListData

    @POST("song/detail")
    suspend fun getSongDetail(
        @Query("c") c: String,
    ): SongListData

    @POST("artists")
    suspend fun getArtistSongs(
        @Query("id") id: Long,
    ): ArtistSongListData

    @POST("artist/songs")
    suspend fun getArtistSongPage(
        @Query("id") id: Long,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
        @Query("order") order: String = "hot",
    ): ArtistSongListData

    @POST("artist/album")
    suspend fun getArtistAlbums(
        @Query("id") id: Long,
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0,
    ): ArtistAlbumListData

    @POST("album")
    suspend fun getAlbumSongs(
        @Query("id") id: Long,
    ): AlbumDetailData

    @POST("playmode/intelligence/list")
    suspend fun getHeartModeSongs(
        @Query("id") id: Long,
        @Query("pid") pid: Long,
        @Query("sid") sid: Long = id,
        @Query("count") count: Int = 20,
    ): HeartModeData

    @POST("scrobble")
    suspend fun scrobble(
        @Query("id") id: Long,
        @Query("time") time: Long,
        @Query("sourceid") sourceId: Long = 0L,
    ): JsonObject

    @POST("comment/music")
    suspend fun getMusicComments(
        @Query("id") id: Long,
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0,
    ): CommentListData

    @POST("playlist/hot")
    suspend fun getPlaylistTagList(): PlaylistTagListData

    @POST("top/playlist")
    suspend fun getPlaylistList(
        @Query("cat") cat: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
    ): PlaylistListData

    @POST("toplist")
    suspend fun getRankingList(): PlaylistListData

    @GET("banner?type=2")
    suspend fun getBannerList(): BannerListData

    companion object {
        private const val SONG_LIST_LIMIT = 800

        private val api: DiscoverApi by lazy {
            val retrofit = Retrofit.Builder()
                .baseUrl(ConfigPreferences.apiDomain)
                .addConverterFactory(GsonConverterFactory.create(GsonUtils.gson, true))
                .client(HttpClient.okHttpClient)
                .build()
            retrofit.create(DiscoverApi::class.java)
        }

        fun get(): DiscoverApi = api

        suspend fun getFullPlaylistSongList(id: Long, timestamp: Long? = null): SongListData {
            return withContext(Dispatchers.IO) {
                if (ConfigPreferences.apiDomain == "https://music.163.com/") {
                    val detail = get().getPlaylistDetail(id)
                    if (detail.code != 200) {
                        throw Exception("code = ${detail.code}")
                    }
                    val list = mutableListOf<SongData>()
                    var offset = 0
                    while (offset < detail.playlist.trackIds.size) {
                        val ids = detail.playlist.trackIds
                            .drop(offset)
                            .take(SONG_LIST_LIMIT)
                            .joinToString(prefix = "[", postfix = "]") { """{"id":${it.id}}""" }
                        if (ids == "[]") {
                            break
                        }
                        val songList = get().getSongDetail(ids)
                        if (songList.code != 200) {
                            throw Exception("code = ${songList.code}")
                        }
                        if (songList.songs.isEmpty()) {
                            break
                        }
                        list.addAll(songList.songs)
                        offset = list.size
                    }
                    return@withContext SongListData(200, list)
                }
                var offset = 0
                val list = mutableListOf<SongData>()
                while (true) {
                    val songList = get().getPlaylistSongList(
                        id,
                        limit = SONG_LIST_LIMIT,
                        offset = offset,
                        timestamp = timestamp
                    )
                    if (songList.code != 200) {
                        throw Exception("code = ${songList.code}")
                    }
                    if (songList.songs.isEmpty()) {
                        break
                    }
                    list.addAll(songList.songs)
                    offset = list.size
                }
                return@withContext SongListData(200, list)
            }
        }
    }
}
