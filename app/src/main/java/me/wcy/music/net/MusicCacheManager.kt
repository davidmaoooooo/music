package me.wcy.music.net

import android.net.Uri
import me.wcy.music.consts.FilePath
import me.wcy.music.storage.preference.ConfigPreferences
import java.io.File

object MusicCacheManager {
    private val cacheDir: File
        get() = File(FilePath.httpCache)

    fun isMusicCacheAllowed(): Boolean {
        val limitBytes = getLimitBytes()
        if (limitBytes <= 0L) return false
        return getCacheSizeBytes() < limitBytes
    }

    fun clear() {
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
    }

    fun getCachedSongFile(uri: Uri): File? {
        return getCachedSongFile(getSongId(uri))
    }

    fun getCachedSongFile(songId: String?): File? {
        val id = songId ?: return null
        val file = File(songCacheDir(), "$id.audio")
        return file.takeIf { it.exists() && it.length() > 0 }
    }

    fun getSongTempFile(uri: Uri): File? {
        return getSongTempFile(getSongId(uri))
    }

    fun getSongTempFile(songId: String?): File? {
        val id = songId ?: return null
        if (!isMusicCacheAllowed()) return null
        val dir = songCacheDir()
        return File(dir, "$id.audio.tmp")
    }

    fun getSongFinalFile(uri: Uri): File? {
        return getSongFinalFile(getSongId(uri))
    }

    fun getSongFinalFile(songId: String?): File? {
        val id = songId ?: return null
        return File(songCacheDir(), "$id.audio")
    }

    fun getCacheSizeBytes(): Long {
        return cacheDir.size()
    }

    fun getLimitBytes(): Long {
        return ConfigPreferences.musicCacheLimitMb.toLongOrNull()?.coerceAtLeast(0L)
            ?.times(1024L)
            ?.times(1024L)
            ?: 200L * 1024L * 1024L
    }

    fun formatSize(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return String.format("%.1f MB", mb)
    }

    private fun File.size(): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        return listFiles()?.sumOf { it.size() } ?: 0L
    }

    private fun songCacheDir(): File {
        return File(cacheDir, "songs").apply { mkdirs() }
    }

    private fun getSongId(uri: Uri): String? {
        return uri.getQueryParameter("id") ?: uri.getQueryParameter("songId")
    }
}
