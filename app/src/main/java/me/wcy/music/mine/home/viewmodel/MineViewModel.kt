package me.wcy.music.mine.home.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.wcy.music.account.service.UserService
import me.wcy.music.common.bean.SongData
import me.wcy.music.common.bean.PlaylistData
import me.wcy.music.mine.MineApi
import me.wcy.music.mine.bean.MineVirtualPlaylist
import me.wcy.music.mine.bean.UserRecordItemData
import me.wcy.music.net.NetCache
import me.wcy.music.mine.record.MineRecordStore
import top.wangchenyan.common.ext.toUnMutable
import top.wangchenyan.common.model.CommonResult
import top.wangchenyan.common.net.apiCall
import javax.inject.Inject

/**
 * Created by wangchenyan.top on 2023/9/28.
 */
@HiltViewModel
class MineViewModel @Inject constructor() : ViewModel() {
    private val _likePlaylist = MutableStateFlow<PlaylistData?>(null)
    val likePlaylist = _likePlaylist.toUnMutable()
    private val _myPlaylists = MutableStateFlow<List<PlaylistData>>(emptyList())
    val myPlaylists = _myPlaylists
    private val _collectPlaylists = MutableStateFlow<List<PlaylistData>>(emptyList())
    val collectPlaylists = _collectPlaylists
    private val _recentPlaylists = MutableStateFlow<List<MineVirtualPlaylist>>(emptyList())
    val recentPlaylists = _recentPlaylists
    private val _recentPlayedPlaylists = MutableStateFlow<List<PlaylistData>>(emptyList())
    val recentPlayedPlaylists = _recentPlayedPlaylists
    private val _listeningRanks = MutableStateFlow<List<UserRecordItemData>>(emptyList())
    val listeningRanks = _listeningRanks

    @Inject
    lateinit var userService: UserService

    private var updateJob: Job? = null

    init {
        viewModelScope.launch {
            userService.profile.collectLatest { profile ->
                if (profile != null) {
                    updatePlaylist(profile.userId)
                } else {
                    _likePlaylist.value = null
                    _myPlaylists.value = emptyList()
                    _collectPlaylists.value = emptyList()
                    _recentPlaylists.value = emptyList()
                    _recentPlayedPlaylists.value = emptyList()
                    _listeningRanks.value = emptyList()
                    MineRecordStore.recentSongs = emptyList()
                    MineRecordStore.listeningRanks = emptyList()
                }
            }
        }
    }

    fun updatePlaylistFromCache() {
        viewModelScope.launch {
            if (userService.isLogin()) {
                updateRecordsFromCache()
                val uid = userService.profile.value?.userId ?: return@launch
                val cacheList = NetCache.userCache.getJsonArray(CACHE_KEY, PlaylistData::class.java)
                    ?: return@launch
                notifyPlaylist(uid, cacheList)
            }
        }
    }

    fun updatePlaylist() {
        if (userService.isLogin()) {
            val uid = userService.profile.value?.userId ?: return
            updatePlaylist(uid)
        }
    }

    private fun updatePlaylist(uid: Long) {
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            val res = kotlin.runCatching {
                MineApi.get().getUserPlaylist(uid)
            }
            if (res.getOrNull()?.code == 200) {
                val list = res.getOrThrow().playlists
                notifyPlaylist(uid, list)
                NetCache.userCache.putJson(CACHE_KEY, list)
            }
            updateRecords(uid)
        }
    }

    private suspend fun updateRecordsFromCache() {
        val recent = NetCache.userCache.getJsonArray(CACHE_KEY_RECENT_SONGS, SongData::class.java)
        if (recent != null) {
            MineRecordStore.recentSongs = recent
        }
        val ranks = NetCache.userCache.getJsonArray(CACHE_KEY_LISTENING_RANK, UserRecordItemData::class.java)
        if (ranks != null) {
            MineRecordStore.listeningRanks = ranks
            _listeningRanks.value = ranks
        }
        val recentPlayedPlaylists = NetCache.userCache.getJsonArray(CACHE_KEY_RECENT_PLAYLIST, PlaylistData::class.java)
        if (recentPlayedPlaylists != null) {
            _recentPlayedPlaylists.value = recentPlayedPlaylists
        }
        _recentPlaylists.value = MineRecordStore.virtualPlaylists()
    }

    private suspend fun updateRecords(uid: Long) {
        runCatching {
            MineApi.get().getRecentSongs(100)
        }.getOrNull()?.takeIf { it.code == 200 }?.let { result ->
            val list = result.data.list.map { it.song }.filter { it.id > 0 }.distinctBy { it.id }
            MineRecordStore.recentSongs = list
            NetCache.userCache.putJson(CACHE_KEY_RECENT_SONGS, list)
        }
        runCatching {
            MineApi.get().getRecentPlaylist(20)
        }.getOrNull()?.takeIf { it.code == 200 }?.let { result ->
            val list = result.data.list.map { it.playlist }.filter { it.id > 0 }.distinctBy { it.id }
            _recentPlayedPlaylists.value = list
            NetCache.userCache.putJson(CACHE_KEY_RECENT_PLAYLIST, list)
        }
        val weeklyRecord = runCatching {
            MineApi.get().getUserRecord(uid, 1)
        }.getOrNull()?.takeIf { it.code == 200 }
        val allRecord = if ((weeklyRecord?.weekData?.size ?: 0) < MIN_RECORD_FALLBACK_SIZE) {
            runCatching {
                MineApi.get().getUserRecord(uid, 0)
            }.getOrNull()?.takeIf { it.code == 200 }
        } else {
            null
        }
        (weeklyRecord ?: allRecord)?.let { result ->
            val weeklyList = result.weekData.ifEmpty { result.allData }
            val fallbackList = allRecord?.allData.orEmpty()
            val list = if (weeklyList.size < MIN_RECORD_FALLBACK_SIZE && fallbackList.size > weeklyList.size) {
                fallbackList
            } else {
                weeklyList
            }.filter { it.song.id > 0 }
                .distinctBy { it.song.id }
                .take(100)
            MineRecordStore.listeningRanks = list
            _listeningRanks.value = list
            NetCache.userCache.putJson(CACHE_KEY_LISTENING_RANK, list)
        }
        _recentPlaylists.value = MineRecordStore.virtualPlaylists()
    }

    private fun notifyPlaylist(uid: Long, list: List<PlaylistData>) {
        val mineList = list.filter { it.userId == uid }
        _likePlaylist.value = mineList.firstOrNull()
        _myPlaylists.value = mineList.takeLast((mineList.size - 1).coerceAtLeast(0))
        _collectPlaylists.value = list.filter { it.userId != uid }
    }

    suspend fun removeCollect(id: Long): CommonResult<Unit> {
        val res = apiCall { MineApi.get().collectPlaylist(id, 2) }
        return if (res.isSuccess()) {
            val list = _collectPlaylists.value
            _collectPlaylists.value = list.toMutableList().apply {
                removeAll { it.id == id }
            }
            CommonResult.success(Unit)
        } else {
            CommonResult.fail(res.code, res.msg)
        }
    }

    companion object {
        private const val CACHE_KEY = "my_playlist"
        private const val CACHE_KEY_RECENT_PLAYLIST = "recent_playlist"
        private const val CACHE_KEY_RECENT_SONGS = "recent_songs"
        private const val CACHE_KEY_LISTENING_RANK = "listening_rank"
        private const val MIN_RECORD_FALLBACK_SIZE = 10
    }
}
