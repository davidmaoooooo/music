package me.wcy.music.listen

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.blankj.utilcode.util.GsonUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.wcy.music.BuildConfig
import me.wcy.music.storage.preference.ConfigPreferences
import top.wangchenyan.common.CommonApp
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object ListenTogetherDebugLogger {
    private val enabled: Boolean
        get() = BuildConfig.DEBUG && ConfigPreferences.listenTogetherDebugLog
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val sessionId = UUID.randomUUID().toString()
    private val logTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private val fileTimeFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA)

    @Volatile
    private var target: LogTarget? = null

    fun log(event: String, fields: Map<String, Any?> = emptyMap()) {
        if (!enabled) return
        val entry = linkedMapOf<String, Any?>(
            "time" to logTimeFormat.format(Date()),
            "epochMs" to System.currentTimeMillis(),
            "sessionId" to sessionId,
            "thread" to Thread.currentThread().name,
            "event" to event
        )
        fields.forEach { (key, value) ->
            entry[key] = sanitizeValue(value)
        }
        val line = GsonUtils.toJson(entry) + "\n"
        scope.launch {
            mutex.withLock {
                appendLine(line)
            }
        }
    }

    fun cookieSummary(cookie: String): Map<String, Any?> {
        return mapOf(
            "cookiePresent" to cookie.isNotEmpty(),
            "cookieLength" to cookie.length,
            "cookieSha256" to cookie.sha256Prefix()
        )
    }

    private fun appendLine(line: String) {
        val context = CommonApp.app.applicationContext
        val logTarget = target ?: createTarget(context).also {
            target = it
            it.append(headerLine(it.fileName))
        }
        logTarget.append(line)
    }

    private fun createTarget(context: Context): LogTarget {
        val fileName = "music-listen-together-${fileTimeFormat.format(Date())}.log"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("Unable to create listen-together log in Downloads")
            MediaStoreLogTarget(context, uri, fileName)
        } else {
            @Suppress("DEPRECATION")
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )
            FileLogTarget(file, fileName)
        }
    }

    private fun headerLine(fileName: String): String {
        return GsonUtils.toJson(
            linkedMapOf<String, Any?>(
                "time" to logTimeFormat.format(Date()),
                "epochMs" to System.currentTimeMillis(),
                "sessionId" to sessionId,
                "event" to "log_file_created",
                "fileName" to fileName,
                "app" to "音乐 Music",
                "debug" to true,
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "sdk" to Build.VERSION.SDK_INT
            )
        ) + "\n"
    }

    private fun sanitizeValue(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> value.entries.associate { (key, item) ->
                key.toString() to sanitizeValue(item)
            }
            is Iterable<*> -> value.map { sanitizeValue(it) }
            is Array<*> -> value.map { sanitizeValue(it) }
            else -> value
        }
    }

    private fun String.sha256Prefix(): String {
        if (isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private interface LogTarget {
        val fileName: String
        fun append(line: String)
    }

    private class MediaStoreLogTarget(
        private val context: Context,
        private val uri: Uri,
        override val fileName: String
    ) : LogTarget {
        override fun append(line: String) {
            runCatching {
                context.contentResolver.openOutputStream(uri, "wa")?.use { stream ->
                    stream.write(line.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    private class FileLogTarget(
        private val file: File,
        override val fileName: String
    ) : LogTarget {
        override fun append(line: String) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(line, Charsets.UTF_8)
            }
        }
    }
}
