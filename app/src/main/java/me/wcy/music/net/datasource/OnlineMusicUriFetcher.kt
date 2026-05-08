package me.wcy.music.net.datasource

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.wcy.music.discover.DiscoverApi
import top.wangchenyan.common.net.apiCall

/**
 * Created by wangchenyan.top on 2024/3/26.
 */
object OnlineMusicUriFetcher {
    private const val BROWSER_PLAY_LEVEL = "standard"
    private const val PREFETCH_TTL_MS = 10 * 60 * 1000L
    private const val MAX_PREFETCHED_URLS = 24

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefetchedUrls = mutableMapOf<Long, PrefetchedUrl>()

    fun fetchPlayUrl(uri: Uri): String {
        uri.getQueryParameter("playUrl")?.takeIf { it.isNotEmpty() }?.let {
            return it
        }
        val songId = uri.getQueryParameter("id")?.toLongOrNull() ?: return uri.toString()
        getPrefetchedUrl(songId)?.let { return it }
        return runBlocking {
            fetchPlayUrl(songId)
        }
    }

    fun prefetch(songIds: List<Long>) {
        if (songIds.isEmpty()) return
        scope.launch {
            songIds.distinct().forEach { songId ->
                if (getPrefetchedUrl(songId) != null) return@forEach
                val url = fetchPlayUrl(songId)
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

    private suspend fun fetchPlayUrl(songId: Long): String {
        val res = apiCall {
            DiscoverApi.get().getSongUrl(songId, BROWSER_PLAY_LEVEL)
        }
        if (res.isSuccessWithData()) {
            return res.getDataOrThrow().firstOrNull()?.url.orEmpty()
        }
        return ""
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
}
