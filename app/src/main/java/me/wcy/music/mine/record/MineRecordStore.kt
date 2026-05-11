package me.wcy.music.mine.record

import me.wcy.music.common.bean.SongData
import me.wcy.music.mine.bean.MineVirtualPlaylist
import me.wcy.music.mine.bean.UserRecordItemData
import me.wcy.music.common.bean.PlaylistData

object MineRecordStore {
    var recentSongs: List<SongData> = emptyList()
    var listeningRanks: List<UserRecordItemData> = emptyList()

    fun virtualPlaylists(): List<MineVirtualPlaylist> {
        return listOfNotNull(
            recentSongs.takeIf { it.isNotEmpty() }?.let { songs ->
                MineVirtualPlaylist(
                    id = RECENT_SONGS_ID,
                    name = "最近播放",
                    coverImgUrl = songs.firstOrNull()?.al?.picUrl.orEmpty(),
                    trackCount = songs.size,
                    type = MineVirtualPlaylist.TYPE_RECENT_SONGS
                )
            },
            listeningRanks.takeIf { it.isNotEmpty() }?.let { ranks ->
                MineVirtualPlaylist(
                    id = LISTENING_RANK_ID,
                    name = "近期听歌排行",
                    coverImgUrl = ranks.firstOrNull()?.song?.al?.picUrl.orEmpty(),
                    trackCount = ranks.size,
                    type = MineVirtualPlaylist.TYPE_LISTENING_RANK
                )
            }
        )
    }

    fun songsOf(type: String): List<SongData> {
        return when (type) {
            MineVirtualPlaylist.TYPE_RECENT_SONGS -> recentSongs
            MineVirtualPlaylist.TYPE_LISTENING_RANK -> listeningRanks.map { it.song }
            else -> emptyList()
        }
    }

    fun songsOfPlaylistId(playlistId: Long): List<SongData> {
        return when (playlistId) {
            RECENT_SONGS_ID -> recentSongs
            LISTENING_RANK_ID -> listeningRanks.map { it.song }
            else -> emptyList()
        }
    }

    fun playlistOf(playlistId: Long): PlaylistData {
        val virtual = virtualPlaylists().firstOrNull { it.id == playlistId }
        if (virtual != null) return virtual.toPlaylistData()
        return when (playlistId) {
            RECENT_SONGS_ID -> MineVirtualPlaylist(
                id = RECENT_SONGS_ID,
                name = "最近播放",
                trackCount = recentSongs.size,
                type = MineVirtualPlaylist.TYPE_RECENT_SONGS
            ).toPlaylistData()
            LISTENING_RANK_ID -> MineVirtualPlaylist(
                id = LISTENING_RANK_ID,
                name = "近期听歌排行",
                trackCount = listeningRanks.size,
                type = MineVirtualPlaylist.TYPE_LISTENING_RANK
            ).toPlaylistData()
            else -> PlaylistData()
        }
    }

    const val RECENT_SONGS_ID = -10501L
    const val LISTENING_RANK_ID = -10502L
}
