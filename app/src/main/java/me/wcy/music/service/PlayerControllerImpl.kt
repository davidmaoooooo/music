package me.wcy.music.service

import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.wcy.music.discover.DiscoverApi
import me.wcy.music.listen.ListenTogetherManager
import me.wcy.music.storage.db.MusicDatabase
import me.wcy.music.storage.preference.ConfigPreferences
import me.wcy.music.utils.getSongId
import me.wcy.music.utils.toMediaItem
import me.wcy.music.utils.toSongEntity
import top.wangchenyan.common.ext.toUnMutable
import top.wangchenyan.common.ext.toast

/**
 * Created by wangchenyan.top on 2024/3/27.
 */
class PlayerControllerImpl(
    private val player: MediaController,
    private val db: MusicDatabase,
) : PlayerController, CoroutineScope by MainScope() {

    override val mediaController: MediaController
        get() = player

    private val _playlist = MutableStateFlow(emptyList<MediaItem>())
    override val playlist = _playlist.toUnMutable()

    private val _currentSong = MutableStateFlow<MediaItem?>(null)
    override val currentSong = _currentSong.toUnMutable()

    private val _playState = MutableStateFlow<PlayState>(PlayState.Idle)
    override val playState = _playState.toUnMutable()

    private val _playProgress = MutableStateFlow<Long>(0)
    override val playProgress = _playProgress.toUnMutable()

    private val _bufferingPercent = MutableStateFlow(0)
    override val bufferingPercent = _bufferingPercent.toUnMutable()

    private val _playMode = MutableStateFlow(PlayMode.valueOf(ConfigPreferences.playMode))
    override val playMode: StateFlow<PlayMode> = _playMode

    private var audioSessionId = 0
    private var applyingRemote = false
    private var heartModeSession: HeartModeSession? = null
    private var heartModeAppendJob: Job? = null
    private var progressJob: Job? = null
    private val restoreJob: Job

    init {
        player.playWhenReady = false
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                when (playbackState) {
                    Player.STATE_IDLE -> {
                        stopProgressUpdates(reset = true)
                        _playState.value = PlayState.Idle
                        _playProgress.value = 0
                        _bufferingPercent.value = 0
                    }

                    Player.STATE_BUFFERING -> {
                        stopProgressUpdates(reset = false)
                        _playState.value = PlayState.Preparing
                    }

                    Player.STATE_READY -> {
                        _playState.value = if (player.isPlaying) PlayState.Playing else PlayState.Pause
                        updateProgressUpdates(player.isPlaying)
                    }

                    Player.STATE_ENDED -> {
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                if (player.playbackState == Player.STATE_READY) {
                    _playState.value = if (isPlaying) PlayState.Playing else PlayState.Pause
                    updateProgressUpdates(isPlaying)
                    if (!applyingRemote) {
                        ListenTogetherManager.onLocalPlayStateChanged(isPlaying)
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)
                _playProgress.value = player.currentPosition
                if (!applyingRemote && reason == Player.DISCONTINUITY_REASON_SEEK) {
                    ListenTogetherManager.onLocalSeek(player.currentPosition)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                mediaItem ?: return
                val playlist = _playlist.value
                val current = playlist.find { it.mediaId == mediaItem.mediaId }
                _currentSong.value = current
                maybeAppendHeartModeSongs(current)
                if (!applyingRemote) {
                    ListenTogetherManager.onLocalSongChanged(current)
                }
            }

            @OptIn(UnstableApi::class)
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                super.onAudioSessionIdChanged(audioSessionId)
                this@PlayerControllerImpl.audioSessionId = audioSessionId
            }

            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                stop()
                toast("播放失败(${error.errorCodeName},${error.localizedMessage})")
            }
        })
        setPlayMode(PlayMode.valueOf(ConfigPreferences.playMode))

        restoreJob = launch(Dispatchers.Main.immediate) {
            val playlist = withContext(Dispatchers.IO) {
                db.playlistDao()
                    .queryAll()
                    .onEach {
                        // 兼容老版本数据库
                        if (it.uri.isEmpty()) {
                            it.uri = it.path
                        }
                    }
                    .map { it.toMediaItem() }
            }
            if (playlist.isNotEmpty()) {
                _playlist.value = playlist
                player.setMediaItems(playlist)
                val currentSongId = ConfigPreferences.currentSongId
                if (currentSongId.isNotEmpty()) {
                    val currentSongIndex = playlist.indexOfFirst {
                        it.mediaId == currentSongId
                    }.coerceAtLeast(0)
                    _currentSong.value = playlist[currentSongIndex]
                    player.seekTo(currentSongIndex, 0)
                }
            }
        }

        launch(Dispatchers.Main.immediate) {
            restoreJob.join()
            _currentSong.collectLatest {
                ConfigPreferences.currentSongId = it?.mediaId ?: ""
            }
        }

    }

    @MainThread
    override fun addAndPlay(song: MediaItem) {
        stopHeartModeSession()
        launch(Dispatchers.Main.immediate) {
            restoreJob.join()
            val newPlaylist = _playlist.value.toMutableList()
            val index = newPlaylist.indexOfFirst { it.mediaId == song.mediaId }
            if (index >= 0) {
                newPlaylist[index] = song
                player.replaceMediaItem(index, song)
            } else {
                newPlaylist.add(song)
                player.addMediaItem(song)
            }
            withContext(Dispatchers.IO) {
                db.playlistDao().clear()
                db.playlistDao().insertAll(newPlaylist.map { it.toSongEntity() })
            }
            _playlist.value = newPlaylist
            play(song.mediaId)
            ListenTogetherManager.onLocalPlaylistChanged()
        }
    }

    @MainThread
    override fun replaceAll(songList: List<MediaItem>, song: MediaItem) {
        replaceAll(songList, song, true)
    }

    @MainThread
    override fun replaceAll(songList: List<MediaItem>, song: MediaItem, playWhenReady: Boolean) {
        stopHeartModeSession()
        replaceAllInternal(songList, song, playWhenReady, remote = false)
    }

    @MainThread
    override fun replaceAllFromRemote(songList: List<MediaItem>, song: MediaItem, playWhenReady: Boolean) {
        stopHeartModeSession()
        replaceAllInternal(songList, song, playWhenReady, remote = true)
    }

    @MainThread
    override fun startHeartMode(seedSong: MediaItem, playlistId: Long) {
        stopHeartModeSession()
        val seedSongId = seedSong.getSongId()
        if (seedSongId <= 0 || playlistId <= 0) {
            toast("心动模式参数无效")
            return
        }
        heartModeAppendJob = launch(Dispatchers.Main.immediate) {
            restoreJob.join()
            kotlin.runCatching {
                val songs = withContext(Dispatchers.IO) {
                    DiscoverApi.get()
                        .getHeartModeSongs(seedSongId, playlistId, sid = seedSongId, count = HEART_MODE_FETCH_COUNT)
                        .data
                        .map { it.songInfo }
                        .filter { it.id > 0 }
                        .distinctBy { it.id }
                        .map { it.toMediaItem(playlistId) }
                }
                if (songs.isEmpty()) {
                    throw IllegalStateException("没有拿到心动推荐")
                }
                val sessionSongs = listOf(seedSong) + songs.filterNot { it.mediaId == seedSong.mediaId }
                heartModeSession = HeartModeSession(playlistId)
                if (_currentSong.value?.mediaId == seedSong.mediaId) {
                    replaceUpcomingInternal(
                        songs.filterNot { it.mediaId == seedSong.mediaId },
                        playlistId
                    )
                } else {
                    replaceAllInternal(sessionSongs, seedSong, playWhenReady = true, remote = false, keepHeartMode = true)
                }
            }.onFailure {
                stopHeartModeSession()
                toast(it.message ?: "心动模式失败")
            }
        }
    }

    private suspend fun replaceUpcomingInternal(upcomingSongs: List<MediaItem>, playlistId: Long) {
        restoreJob.join()
        val current = _currentSong.value ?: return
        val playlist = _playlist.value
        val currentIndex = playlist.indexOfFirst { it.mediaId == current.mediaId }
        if (currentIndex < 0) return

        val nextSongs = upcomingSongs.distinctBy { it.mediaId }
        val merged = playlist.take(currentIndex + 1) + nextSongs
        val removeFrom = currentIndex + 1
        if (player.mediaItemCount > removeFrom) {
            player.removeMediaItems(removeFrom, player.mediaItemCount)
        }
        player.addMediaItems(nextSongs)
        _playlist.value = merged
        withContext(Dispatchers.IO) {
            db.playlistDao().clear()
            db.playlistDao().insertAll(merged.map { it.toSongEntity() })
        }
        ListenTogetherManager.onLocalPlaylistChanged()
    }

    private fun replaceAllInternal(
        songList: List<MediaItem>,
        song: MediaItem,
        playWhenReady: Boolean,
        remote: Boolean,
        keepHeartMode: Boolean = false
    ) {
        launch(Dispatchers.Main.immediate) {
            if (!keepHeartMode && !remote) {
                stopHeartModeSession()
            }
            if (remote) applyingRemote = true
            try {
                restoreJob.join()
                withContext(Dispatchers.IO) {
                    db.playlistDao().clear()
                    db.playlistDao().insertAll(songList.map { it.toSongEntity() })
                }
                player.setMediaItems(songList)
                _playlist.value = songList
                _currentSong.value = song
                playInternal(song.mediaId, playWhenReady)
                if (!remote) {
                    ListenTogetherManager.onLocalPlaylistChanged()
                }
            } finally {
                if (remote) {
                    launch {
                        delay(500L)
                        applyingRemote = false
                    }
                }
            }
        }
    }

    @MainThread
    override fun play(mediaId: String) {
        launch(Dispatchers.Main.immediate) {
            restoreJob.join()
            playInternal(mediaId, true)
        }
    }

    private fun playInternal(mediaId: String, playWhenReady: Boolean) {
        val playlist = _playlist.value
        if (playlist.isEmpty()) {
            return
        }
        val index = playlist.indexOfFirst { it.mediaId == mediaId }
        if (index < 0) {
            return
        }

        stop()
        player.seekTo(index, 0)
        player.prepare()
        if (playWhenReady) {
            player.play()
        } else {
            player.pause()
        }

        _currentSong.value = playlist[index]
        _playProgress.value = 0
        _bufferingPercent.value = 0
    }

    @MainThread
    override fun delete(song: MediaItem) {
        launch(Dispatchers.Main.immediate) {
            restoreJob.join()
            val playlist = _playlist.value.toMutableList()
            val index = playlist.indexOfFirst { it.mediaId == song.mediaId }
            if (index < 0) return@launch
            if (playlist.size == 1) {
                clearPlaylist()
            } else {
                playlist.removeAt(index)
                _playlist.value = playlist
                withContext(Dispatchers.IO) {
                    db.playlistDao().delete(song.toSongEntity())
                }
                player.removeMediaItem(index)
                ListenTogetherManager.onLocalPlaylistChanged()
            }
        }
    }

    @MainThread
    override fun clearPlaylist() {
        stopHeartModeSession()
        launch(Dispatchers.Main.immediate) {
            restoreJob.join()
            withContext(Dispatchers.IO) {
                db.playlistDao().clear()
            }
            stop()
            player.clearMediaItems()
            _playlist.value = emptyList()
            _currentSong.value = null
            ListenTogetherManager.onLocalPlaylistChanged()
        }
    }

    @MainThread
    override fun playPause() {
        if (restoreJob.isCompleted.not()) {
            launch(Dispatchers.Main.immediate) {
                restoreJob.join()
                playPause()
            }
            return
        }
        if (player.mediaItemCount == 0) return
        when (player.playbackState) {
            Player.STATE_IDLE -> {
                player.prepare()
                player.play()
                _playState.value = PlayState.Preparing
            }

            Player.STATE_BUFFERING -> {
                stop()
            }

            Player.STATE_READY -> {
                if (player.isPlaying) {
                    player.pause()
                    _playState.value = PlayState.Pause
                } else {
                    player.play()
                    _playState.value = PlayState.Playing
                }
            }

            Player.STATE_ENDED -> {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    @MainThread
    override fun next() {
        if (restoreJob.isCompleted.not()) {
            launch(Dispatchers.Main.immediate) {
                restoreJob.join()
                next()
            }
            return
        }
        if (player.mediaItemCount == 0) return
        _playProgress.value = 0
        _bufferingPercent.value = 0
        player.seekToNextMediaItem()
        player.prepare()
        player.play()
    }

    @MainThread
    override fun prev() {
        if (restoreJob.isCompleted.not()) {
            launch(Dispatchers.Main.immediate) {
                restoreJob.join()
                prev()
            }
            return
        }
        if (player.mediaItemCount == 0) return
        _playProgress.value = 0
        _bufferingPercent.value = 0
        player.seekToPreviousMediaItem()
        player.prepare()
        player.play()
    }

    @MainThread
    override fun seekTo(msec: Int) {
        if (restoreJob.isCompleted.not()) {
            launch(Dispatchers.Main.immediate) {
                restoreJob.join()
                seekTo(msec)
            }
            return
        }
        if (player.playbackState == Player.STATE_READY) {
            player.seekTo(msec.toLong())
            _playProgress.value = msec.toLong()
        }
    }

    @MainThread
    override fun getAudioSessionId(): Int {
        return audioSessionId
    }

    @MainThread
    override fun setPlayMode(mode: PlayMode) {
        ConfigPreferences.playMode = mode.value
        _playMode.value = mode
        when (mode) {
            PlayMode.Loop -> {
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.shuffleModeEnabled = false
            }

            PlayMode.Shuffle -> {
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.shuffleModeEnabled = true
            }

            PlayMode.Single -> {
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.shuffleModeEnabled = false
            }
        }
    }

    @MainThread
    override fun stop() {
        player.stop()
        stopProgressUpdates(reset = true)
        _playState.value = PlayState.Idle
    }

    private fun updateProgressUpdates(isPlaying: Boolean) {
        if (isPlaying) {
            startProgressUpdates()
        } else {
            _playProgress.value = player.currentPosition
            stopProgressUpdates(reset = false)
        }
    }

    private fun startProgressUpdates() {
        if (progressJob?.isActive == true) return
        progressJob = launch(Dispatchers.Main.immediate) {
            while (true) {
                _playProgress.value = player.currentPosition
                delay(1000)
            }
        }
    }

    private fun stopProgressUpdates(reset: Boolean) {
        progressJob?.cancel()
        progressJob = null
        if (reset) {
            _playProgress.value = 0
        }
    }

    private fun maybeAppendHeartModeSongs(current: MediaItem?) {
        val session = heartModeSession ?: return
        current ?: return
        if (heartModeAppendJob?.isActive == true) return
        val playlist = _playlist.value
        val currentIndex = playlist.indexOfFirst { it.mediaId == current.mediaId }
        if (currentIndex < 0) return
        if (playlist.size - currentIndex - 1 > HEART_MODE_APPEND_THRESHOLD) return

        val seedSongId = current.getSongId()
        if (seedSongId <= 0) return
        heartModeAppendJob = launch(Dispatchers.Main.immediate) {
            kotlin.runCatching {
                val newSongs = withContext(Dispatchers.IO) {
                    DiscoverApi.get()
                        .getHeartModeSongs(
                            seedSongId,
                            session.playlistId,
                            sid = seedSongId,
                            count = HEART_MODE_FETCH_COUNT
                        )
                        .data
                        .map { it.songInfo }
                        .filter { it.id > 0 }
                        .distinctBy { it.id }
                        .map { it.toMediaItem(session.playlistId) }
                }
                val existingIds = _playlist.value.mapTo(mutableSetOf()) { it.mediaId }
                newSongs.filterNot { it.mediaId in existingIds }
            }.onSuccess { appendList ->
                if (appendList.isEmpty()) return@onSuccess
                val merged = _playlist.value + appendList
                _playlist.value = merged
                appendList.forEach { player.addMediaItem(it) }
                withContext(Dispatchers.IO) {
                    db.playlistDao().clear()
                    db.playlistDao().insertAll(merged.map { it.toSongEntity() })
                }
                ListenTogetherManager.onLocalPlaylistChanged()
            }
        }
    }

    private fun stopHeartModeSession() {
        heartModeSession = null
        heartModeAppendJob?.cancel()
        heartModeAppendJob = null
    }

    private data class HeartModeSession(
        val playlistId: Long
    )

    private companion object {
        private const val HEART_MODE_FETCH_COUNT = 30
        private const val HEART_MODE_APPEND_THRESHOLD = 3
    }
}
