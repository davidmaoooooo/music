package me.wcy.music.net.datasource

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.wcy.music.discover.DiscoverApi
import me.wcy.music.common.bean.SongData
import me.wcy.music.source.ThirdPartyMusicInfo
import me.wcy.music.source.ThirdPartySourceDebugLogger
import me.wcy.music.source.ThirdPartySourceRuntime
import me.wcy.music.storage.preference.ConfigPreferences
import me.wcy.music.utils.getSimpleArtist
import top.wangchenyan.common.net.apiCall

/**
 * Created by wangchenyan.top on 2024/3/26.
 */
object OnlineMusicUriFetcher {
    private const val TAG = "OnlineMusicUriFetcher"
    private const val PREFETCH_TTL_MS = 10 * 60 * 1000L
    private const val MAX_PREFETCHED_URLS = 24

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefetchedUrls = mutableMapOf<Long, PrefetchedUrl>()
    private val songMetadataCache = mutableMapOf<Long, ThirdPartyMusicInfo>()

    fun fetchPlayUrl(uri: Uri): String {
        uri.getQueryParameter("playUrl")?.takeIf { it.isNotEmpty() }?.let {
            return it
        }
        val songId = uri.getQueryParameter("id")?.toLongOrNull() ?: return uri.toString()
        getPrefetchedUrl(songId)?.let { return it }
        return runBlocking {
            fetchPlayUrl(uri, songId)
        }
    }

    fun prefetch(songIds: List<Long>) {
        if (ConfigPreferences.thirdPartySourceEnabled) return
        if (songIds.isEmpty()) return
        scope.launch {
            songIds.distinct().forEach { songId ->
                if (getPrefetchedUrl(songId) != null) return@forEach
                val url = fetchPlayUrl(songId, ConfigPreferences.playSoundQuality)
                if (url.isNotEmpty()) {
                    synchronized(prefetchedUrls) {
                        trimExpiredLocked()
                        prefetchedUrls[songId] = PrefetchedUrl(url, System.currentTimeMillis())
                        trimOverflowLocked()
                    }
                }
            }
        }
    }

    fun clearPrefetchedUrls() {
        synchronized(prefetchedUrls) {
            prefetchedUrls.clear()
        }
    }

    fun rememberSongs(songs: List<SongData>) {
        if (songs.isEmpty()) return
        synchronized(songMetadataCache) {
            songs.forEach { song ->
                if (song.id > 0) {
                    songMetadataCache[song.id] = ThirdPartyMusicInfo(
                        id = song.id,
                        name = song.name,
                        singer = song.getSimpleArtist(),
                        albumName = song.al.name,
                        interval = song.dt
                    )
                }
            }
            if (songMetadataCache.size > MAX_METADATA_CACHE_SIZE) {
                val overflow = songMetadataCache.size - MAX_METADATA_CACHE_SIZE
                songMetadataCache.keys.take(overflow).forEach { songMetadataCache.remove(it) }
            }
        }
    }

    suspend fun fetchNeteasePlayUrl(songId: Long, preferredLevel: String): String {
        return fetchPlayUrl(songId, preferredLevel)
    }

    private suspend fun fetchPlayUrl(songId: Long, preferredLevel: String): String {
        NeteaseSoundQuality.fallbackLevels(preferredLevel).forEach { level ->
            val res = apiCall {
                DiscoverApi.get().getSongUrl(songId, level)
            }
            if (res.isSuccessWithData()) {
                val data = res.getDataOrThrow().firstOrNull()
                val url = data?.url.orEmpty()
                if (url.isNotEmpty() && NeteaseSoundQuality.accepts(level, data?.level.orEmpty())) {
                    return url
                }
            }
        }
        return ""
    }

    private suspend fun fetchPlayUrl(uri: Uri, songId: Long): String {
        if (ConfigPreferences.thirdPartySourceEnabled) {
            val info = uri.toThirdPartyMusicInfo(songId)
            Log.i(TAG, "third-party source resolving id=$songId name=${info.name} artist=${info.singer}")
            ThirdPartySourceDebugLogger.log(
                "resolve_start",
                mapOf(
                    "songId" to songId,
                    "name" to info.name,
                    "artist" to info.singer,
                    "album" to info.albumName,
                    "durationMs" to info.interval
                )
            )
            val result = ThirdPartySourceRuntime.fetchMusicUrl(info)
            result
                .onSuccess { url ->
                    val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
                    Log.i(TAG, "third-party source resolved id=$songId host=$host length=${url.length}")
                    ThirdPartySourceDebugLogger.logUrl(
                        "resolve_success",
                        url,
                        mapOf("songId" to songId)
                    )
                }
                .onFailure { error ->
                    Log.e(TAG, "third-party source failed id=$songId name=${info.name}: ${error.message}", error)
                    ThirdPartySourceDebugLogger.log(
                        "resolve_failed",
                        mapOf(
                            "songId" to songId,
                            "errorType" to error.javaClass.simpleName,
                            "errorMessage" to error.message.orEmpty()
                        )
                    )
                }
            return result.getOrElse { "" }
        }
        return fetchPlayUrl(songId, ConfigPreferences.playSoundQuality)
    }

    private fun Uri.toThirdPartyMusicInfo(songId: Long): ThirdPartyMusicInfo {
        val fallback = synchronized(songMetadataCache) { songMetadataCache[songId] }
        return ThirdPartyMusicInfo(
            id = songId,
            name = getQueryParameter("name").orEmpty().ifBlank { fallback?.name.orEmpty() },
            singer = getQueryParameter("artist").orEmpty().ifBlank { fallback?.singer.orEmpty() },
            albumName = getQueryParameter("album").orEmpty().ifBlank { fallback?.albumName.orEmpty() },
            interval = getQueryParameter("duration")?.toLongOrNull() ?: fallback?.interval ?: 0L
        )
    }

    private fun getPrefetchedUrl(songId: Long): String? {
        synchronized(prefetchedUrls) {
            val cached = prefetchedUrls[songId] ?: return null
            if (System.currentTimeMillis() - cached.timeMs > PREFETCH_TTL_MS) {
                prefetchedUrls.remove(songId)
                return null
            }
            return cached.url
        }
    }

    private fun trimExpiredLocked() {
        val now = System.currentTimeMillis()
        prefetchedUrls.entries.removeAll { now - it.value.timeMs > PREFETCH_TTL_MS }
    }

    private fun trimOverflowLocked() {
        if (prefetchedUrls.size <= MAX_PREFETCHED_URLS) return
        val overflow = prefetchedUrls.size - MAX_PREFETCHED_URLS
        prefetchedUrls.entries
            .sortedBy { it.value.timeMs }
            .take(overflow)
            .forEach { prefetchedUrls.remove(it.key) }
    }

    private data class PrefetchedUrl(
        val url: String,
        val timeMs: Long
    )

    private const val MAX_METADATA_CACHE_SIZE = 1000
}
