package me.wcy.music.net.datasource

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.wcy.music.net.MusicCacheManager
import me.wcy.music.net.NeteaseHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

object MusicCacheDownloader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloading = mutableSetOf<String>()
    private val downloadSlots = Semaphore(2)
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.MINUTES)
        .build()

    fun cacheAsync(songUri: Uri, playUrl: String) {
        val songId = songUri.getQueryParameter("id") ?: return
        if (MusicCacheManager.getCachedSongFile(songId) != null) return
        if (!MusicCacheManager.isMusicCacheAllowed()) return
        synchronized(downloading) {
            if (!downloading.add(songId)) return
        }
        scope.launch {
            try {
                downloadSlots.acquire()
                try {
                    download(songId, playUrl)
                } finally {
                    downloadSlots.release()
                }
            } finally {
                synchronized(downloading) {
                    downloading.remove(songId)
                }
            }
        }
    }

    private fun download(songId: String, playUrl: String) {
        val temp = MusicCacheManager.getSongTempFile(songId) ?: return
        val target = MusicCacheManager.getSongFinalFile(songId) ?: return
        if (target.exists() && target.length() > 0) return
        if (temp.exists()) temp.delete()

        val request = Request.Builder()
            .url(playUrl)
            .header("User-Agent", NeteaseHeaders.userAgent)
            .header("Referer", "https://music.163.com/")
            .header("Origin", "https://music.163.com")
            .header("Accept", "*/*")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                temp.delete()
                return
            }
            val body = response.body ?: return
            temp.outputStream().use { output ->
                body.byteStream().use { input ->
                    input.copyTo(output)
                }
            }
        }

        if (temp.length() > 0) {
            replace(temp, target)
        } else {
            temp.delete()
        }
    }

    private fun replace(temp: File, target: File) {
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }
}

