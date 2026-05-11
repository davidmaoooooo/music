package me.wcy.music.source.quickjs

import android.content.Context
import android.util.Log
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.QuickJSContext
import me.wcy.music.source.ThirdPartyMusicInfo
import me.wcy.music.source.ThirdPartySourceDebugLogger
import me.wcy.music.source.ThirdPartySourceInfo
import me.wcy.music.source.ThirdPartySourceStore
import top.wangchenyan.common.utils.GsonUtils
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SourceScriptEngine(
    private val context: Context,
    private val source: ThirdPartySourceInfo
) {
    private val thread = HandlerThread("ThirdPartySourceScript")
    private lateinit var handler: Handler
    private val key = UUID.randomUUID().toString()
    private var jsContext: QuickJSContext? = null
    private var sourceKey = "wy"

    @Volatile
    private var loaded = false

    fun load(): Result<Unit> {
        thread.start()
        handler = Handler(thread.looper)
        return runOnScriptThread(LOAD_TIMEOUT_MS) {
            QuickJSLoader.init()
            val ctx = QuickJSContext.create()
            ctx.setConsole(SourceScriptConsole())
            jsContext = ctx
            createEnv(ctx)
            val preload = context.assets.open("script/user-api-preload.js")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            ctx.evaluate(preload)
            val script = ThirdPartySourceStore.readScript(context, source)
            ctx.getGlobalObject().getJSFunction("lx_setup").call(
                key,
                source.id,
                source.name,
                "",
                "",
                "",
                "",
                script
            )
            ctx.evaluate(script)
            loaded = true
        }
    }

    fun requestMusicUrl(info: ThirdPartyMusicInfo): Result<String> {
        if (!loaded) return Result.failure(IllegalStateException("第三方音源未初始化"))
        val payload = buildMusicUrlPayload(info)
        val requestKey = UUID.randomUUID().toString()
        ThirdPartySourceDebugLogger.log(
            "script_music_url_request",
            mapOf(
                "requestKey" to requestKey,
                "sourceId" to source.id,
                "sourceName" to source.name,
                "sourceKey" to sourceKey,
                "songId" to info.id,
                "name" to info.name,
                "artist" to info.singer,
                "album" to info.albumName,
                "durationMs" to info.interval
            )
        )
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<Result<String>>()
        pendingResponses[requestKey] = PendingResponse(latch, resultRef)
        handler.post {
            runCatching {
                jsContext?.getGlobalObject()
                    ?.getJSFunction("__lx_native__")
                    ?.call(key, "request", GsonUtils.toJson(payloadWithKey(requestKey, payload)))
            }.onFailure { error ->
                pendingResponses.remove(requestKey)
                resultRef.set(Result.failure(error))
                ThirdPartySourceDebugLogger.log(
                    "script_music_url_call_failed",
                    mapOf(
                        "requestKey" to requestKey,
                        "songId" to info.id,
                        "errorType" to error.javaClass.simpleName,
                        "errorMessage" to error.message.orEmpty()
                    )
                )
                latch.countDown()
            }
        }
        if (!latch.await(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            pendingResponses.remove(requestKey)
            ThirdPartySourceDebugLogger.log(
                "script_music_url_timeout",
                mapOf(
                    "requestKey" to requestKey,
                    "songId" to info.id,
                    "timeoutMs" to REQUEST_TIMEOUT_MS
                )
            )
            return Result.failure(IllegalStateException("第三方音源请求超时"))
        }
        val result = resultRef.get() ?: Result.failure(IllegalStateException("第三方音源没有返回结果"))
        result
            .onSuccess { url ->
                ThirdPartySourceDebugLogger.logUrl(
                    "script_music_url_success",
                    url,
                    mapOf("requestKey" to requestKey, "songId" to info.id)
                )
            }
            .onFailure { error ->
                ThirdPartySourceDebugLogger.log(
                    "script_music_url_failed",
                    mapOf(
                        "requestKey" to requestKey,
                        "songId" to info.id,
                        "errorType" to error.javaClass.simpleName,
                        "errorMessage" to error.message.orEmpty()
                    )
                )
            }
        return result
    }

    fun destroy() {
        runCatching {
            if (::handler.isInitialized) {
                val latch = CountDownLatch(1)
                handler.post {
                    runCatching { jsContext?.destroy() }
                    jsContext = null
                    latch.countDown()
                }
                latch.await(1, TimeUnit.SECONDS)
            }
        }
        thread.quitSafely()
        loaded = false
    }

    private fun createEnv(ctx: QuickJSContext) {
        val global = ctx.getGlobalObject()
        global.setProperty("__lx_native_call__") { args ->
            if (args.getOrNull(0) == key) {
                handleNativeCall(args.getOrNull(1)?.toString().orEmpty(), args.getOrNull(2)?.toString().orEmpty())
            }
            null
        }
        global.setProperty("__lx_native_call__utils_str2b64") { args ->
            SourceScriptCrypto.strToBase64(args.getOrNull(0)?.toString().orEmpty())
        }
        global.setProperty("__lx_native_call__utils_b642buf") { args ->
            SourceScriptCrypto.base64ToByteArrayJson(args.getOrNull(0)?.toString().orEmpty())
        }
        global.setProperty("__lx_native_call__utils_str2md5") { args ->
            SourceScriptCrypto.md5(args.getOrNull(0)?.toString().orEmpty())
        }
        global.setProperty("__lx_native_call__utils_aes_encrypt") { args ->
            SourceScriptCrypto.aesEncrypt(
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
                args.getOrNull(2)?.toString().orEmpty(),
                args.getOrNull(3)?.toString().orEmpty()
            )
        }
        global.setProperty("__lx_native_call__utils_rsa_encrypt") { args ->
            SourceScriptCrypto.rsaEncrypt(
                args.getOrNull(0)?.toString().orEmpty(),
                args.getOrNull(1)?.toString().orEmpty(),
                args.getOrNull(2)?.toString().orEmpty()
            )
        }
        global.setProperty("__lx_native_call__set_timeout") { args ->
            val id = args.getOrNull(0)
            val delay = args.getOrNull(1)?.toString()?.toLongOrNull() ?: 0L
            handler.postDelayed({
                runCatching {
                    jsContext?.getGlobalObject()
                        ?.getJSFunction("__lx_native__")
                        ?.call(key, "__set_timeout__", id)
                }
            }, delay)
            null
        }
    }

    private fun handleNativeCall(action: String, data: String) {
        when (action) {
            "init" -> handleInit(data)
            "request" -> handleHttpRequest(data)
            "response" -> handleScriptResponse(data)
            "showUpdateAlert" -> Unit
        }
    }

    private fun handleInit(data: String) {
        val json = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull()
        val status = json?.get("status")?.asBoolean == true
        if (!status) {
            ThirdPartySourceDebugLogger.log(
                "script_init_failed",
                mapOf("errorMessage" to json?.string("errorMessage").orEmpty())
            )
            return
        }
        val sources = json.getAsJsonObject("info")
            ?.getAsJsonObject("sources")
            ?: return
        val keys = sources.keySet()
        sourceKey = if ("wy" in keys) {
            "wy"
        } else {
            keys.firstOrNull().orEmpty().ifBlank { "wy" }
        }
        ThirdPartySourceDebugLogger.log(
            "script_init_success",
            mapOf(
                "sourceId" to source.id,
                "sourceName" to source.name,
                "selectedSourceKey" to sourceKey,
                "availableSourceKeys" to keys.toList()
            )
        )
    }

    private fun handleHttpRequest(data: String) {
        val json = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: return
        val requestKey = json.string("requestKey") ?: return
        val request = json.getAsJsonObject("options")
        val url = json.string("url").orEmpty()
        ThirdPartySourceDebugLogger.logUrl(
            "script_native_http_request",
            url,
            mapOf("requestKey" to requestKey)
        )
        Thread {
            val response = runCatching {
                SourceScriptHttp.request(url, GsonUtils.toJson(request ?: JsonObject()).orEmpty())
            }
            handler.post {
                val payload = JsonObject().apply {
                    addProperty("requestKey", requestKey)
                    if (response.isSuccess) {
                        val responseJson = response.getOrThrow()
                        ThirdPartySourceDebugLogger.logUrl(
                            "script_native_http_response",
                            url,
                            mapOf(
                                "requestKey" to requestKey,
                                "statusCode" to (responseJson.get("statusCode")?.asInt ?: 0)
                            )
                        )
                        add("response", responseJson)
                    } else {
                        val error = response.exceptionOrNull()
                        ThirdPartySourceDebugLogger.logUrl(
                            "script_native_http_failed",
                            url,
                            mapOf(
                                "requestKey" to requestKey,
                                "errorType" to error?.javaClass?.simpleName.orEmpty(),
                                "errorMessage" to error?.message.orEmpty()
                            )
                        )
                        addProperty("error", error?.message ?: "request failed")
                    }
                }
                runCatching {
                    jsContext?.getGlobalObject()
                        ?.getJSFunction("__lx_native__")
                        ?.call(key, "response", GsonUtils.toJson(payload))
                }
            }
        }.start()
    }

    private fun handleScriptResponse(data: String) {
        val json = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: return
        val requestKey = json.string("requestKey") ?: return
        val pending = pendingResponses.remove(requestKey) ?: return
        val status = json.get("status")?.asBoolean == true
        if (!status) {
            val message = json.string("errorMessage") ?: "第三方音源获取失败"
            ThirdPartySourceDebugLogger.log(
                "script_response_failed",
                mapOf(
                    "requestKey" to requestKey,
                    "errorMessage" to message
                )
            )
            pending.result.set(Result.failure(IllegalStateException(message)))
        } else {
            val url = json.getAsJsonObject("result")
                ?.getAsJsonObject("data")
                ?.string("url")
                .orEmpty()
            if (url.startsWith("http")) {
                ThirdPartySourceDebugLogger.logUrl(
                    "script_response_success",
                    url,
                    mapOf("requestKey" to requestKey)
                )
                pending.result.set(Result.success(url))
            } else {
                ThirdPartySourceDebugLogger.log(
                    "script_response_invalid_url",
                    mapOf("requestKey" to requestKey, "urlLength" to url.length)
                )
                pending.result.set(Result.failure(IllegalStateException("第三方音源没有返回有效链接")))
            }
        }
        pending.latch.countDown()
    }

    private fun buildMusicUrlPayload(info: ThirdPartyMusicInfo): JsonObject {
        val musicInfo = JsonObject().apply {
            addProperty("name", info.name)
            addProperty("singer", info.singer)
            addProperty("albumName", info.albumName)
            addProperty("interval", (info.interval / 1000).coerceAtLeast(0))
            addProperty("source", sourceKey)
            addProperty("songmid", info.id.toString())
            addProperty("id", info.id)
            addProperty("hash", info.id.toString())
        }
        return JsonObject().apply {
            addProperty("source", sourceKey)
            addProperty("action", "musicUrl")
            add("info", JsonObject().apply {
                addProperty("type", "320k")
                add("musicInfo", musicInfo)
            })
        }
    }

    private fun payloadWithKey(requestKey: String, payload: JsonObject): JsonObject {
        return JsonObject().apply {
            addProperty("requestKey", requestKey)
            add("data", payload)
        }
    }

    private fun <T> runOnScriptThread(timeoutMs: Long, block: () -> T): Result<T> {
        if (Looper.myLooper() == thread.looper) {
            return runCatching(block)
        }
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<T>>()
        handler.post {
            result.set(runCatching(block))
            latch.countDown()
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            return Result.failure(IllegalStateException("第三方音源脚本执行超时"))
        }
        return result.get()
    }

    private data class PendingResponse(
        val latch: CountDownLatch,
        val result: AtomicReference<Result<String>>
    )

    companion object {
        private const val TAG = "ThirdPartySourceScript"
        private const val LOAD_TIMEOUT_MS = 10_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private val pendingResponses = ConcurrentHashMap<String, PendingResponse>()
    }

    private class SourceScriptConsole : QuickJSContext.Console {
        override fun log(info: String?) {
            write("log", info)
        }

        override fun info(info: String?) {
            write("info", info)
        }

        override fun warn(info: String?) {
            write("warn", info)
        }

        override fun error(info: String?) {
            write("error", info)
        }

        private fun write(level: String, info: String?) {
            val message = info.orEmpty()
            Log.d(TAG, "[$level] $message")
            ThirdPartySourceDebugLogger.log(
                "script_console",
                mapOf(
                    "level" to level,
                    "message" to message.take(1000)
                )
            )
        }
    }
}

private fun JsonObject.string(name: String): String? {
    return get(name)?.takeUnless { it.isJsonNull }?.asString
}
