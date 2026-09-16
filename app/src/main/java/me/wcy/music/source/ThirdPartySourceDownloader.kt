package me.wcy.music.source

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 下载在线音源脚本。
 *
 * 单独使用一个 OkHttpClient，避免复用主客户端的拦截器（网易 Cookie / UA 注入、
 * 大容量磁盘缓存等）影响第三方站点请求。
 */
object ThirdPartySourceDownloader {
    private const val MAX_SCRIPT_BYTES = 4 * 1024 * 1024

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun download(url: String): String {
        val normalized = normalizeUrl(url)
        require(normalized.startsWith("http://") || normalized.startsWith("https://")) {
            "链接必须以 http:// 或 https:// 开头"
        }
        ThirdPartySourceDebugLogger.logUrl("source_download_start", normalized)
        val request = Request.Builder()
            .url(normalized)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    ThirdPartySourceDebugLogger.logUrl(
                        "source_download_failed",
                        normalized,
                        mapOf("statusCode" to response.code)
                    )
                    throw IllegalStateException("下载失败，HTTP ${response.code}")
                }
                val body = response.body
                    ?: throw IllegalStateException("下载失败，响应内容为空")
                val bytes = body.bytes()
                if (bytes.isEmpty()) {
                    throw IllegalStateException("下载失败，脚本内容为空")
                }
                if (bytes.size > MAX_SCRIPT_BYTES) {
                    throw IllegalStateException("脚本过大（${bytes.size / 1024}KB），已超过限制")
                }
                val script = bytes.toString(Charsets.UTF_8)
                ThirdPartySourceDebugLogger.logUrl(
                    "source_download_success",
                    normalized,
                    mapOf("statusCode" to response.code, "bytes" to bytes.size)
                )
                script
            }
        } catch (error: IllegalStateException) {
            throw error
        } catch (error: Throwable) {
            ThirdPartySourceDebugLogger.logUrl(
                "source_download_error",
                normalized,
                mapOf(
                    "errorType" to error.javaClass.simpleName,
                    "errorMessage" to error.message.orEmpty()
                )
            )
            throw IllegalStateException("下载失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    /** 从链接中提取一个可读的兜底名称。 */
    fun nameFromUrl(url: String): String {
        val normalized = normalizeUrl(url)
        val path = normalized.substringBefore('?').substringBefore('#')
        val segment = path.trimEnd('/').substringAfterLast('/')
        return segment.substringBeforeLast('.').ifBlank { "第三方音源" }
    }

    private fun normalizeUrl(url: String): String {
        return url.trim().trim('"').trim('\'').trim()
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"
}
