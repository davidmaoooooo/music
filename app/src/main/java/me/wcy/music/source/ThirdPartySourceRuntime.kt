package me.wcy.music.source

import android.content.Context
import me.wcy.music.source.quickjs.SourceScriptEngine
import me.wcy.music.storage.preference.ConfigPreferences
import top.wangchenyan.common.CommonApp

object ThirdPartySourceRuntime {
    /**
     * 按音源缓存脚本引擎。
     *
     * 多个音源依次回退时，重复加载 QuickJS 脚本开销较大，
     * 这里为每个音源保留一个已初始化的引擎。
     */
    private val engines = LinkedHashMap<String, SourceScriptEngine>()

    /** 记录音源最近一次失败时间，用于短期冷却。 */
    private val failedAtMap = mutableMapOf<String, Long>()

    private fun cachedEngine(sourceId: String): SourceScriptEngine? =
        synchronized(engines) { engines[sourceId] }

    private fun cacheEngine(sourceId: String, engine: SourceScriptEngine) {
        synchronized(engines) { engines[sourceId] = engine }
    }

    fun fetchMusicUrl(info: ThirdPartyMusicInfo): Result<String> {
        if (!ConfigPreferences.thirdPartySourceEnabled) {
            ThirdPartySourceDebugLogger.log("runtime_rejected", mapOf("reason" to "feature_disabled"))
            return Result.failure(IllegalStateException("Third-party source is disabled"))
        }

        val sources = ThirdPartySourceStore.enabledSources()
        if (sources.isEmpty()) {
            ThirdPartySourceDebugLogger.log("runtime_rejected", mapOf("reason" to "no_enabled_source"))
            return Result.failure(IllegalStateException("No enabled third-party source"))
        }

        ThirdPartySourceDebugLogger.log(
            "runtime_sources_selected",
            mapOf(
                "count" to sources.size,
                "sourceIds" to sources.map { it.id },
                "sourceNames" to sources.map { it.name }
            )
        )

        var lastError: Throwable? = null
        for ((index, source) in sources.withIndex()) {
            if (isCoolingDown(source.id)) {
                ThirdPartySourceDebugLogger.log(
                    "runtime_source_cooldown_skip",
                    mapOf("index" to index, "sourceId" to source.id, "sourceName" to source.name)
                )
                continue
            }

            ThirdPartySourceDebugLogger.log(
                "runtime_source_try",
                mapOf(
                    "index" to index,
                    "sourceId" to source.id,
                    "sourceName" to source.name,
                    "fileName" to source.fileName
                )
            )

            val engine = ensureEngine(CommonApp.app, source).getOrElse { error ->
                ThirdPartySourceDebugLogger.log(
                    "runtime_engine_failed",
                    mapOf(
                        "sourceId" to source.id,
                        "errorType" to error.javaClass.simpleName,
                        "errorMessage" to error.message.orEmpty()
                    )
                )
                markFailed(source.id)
                lastError = error
                null
            } ?: continue

            val result = engine.requestMusicUrl(info)
            val url = result.getOrNull()
            if (!url.isNullOrEmpty()) {
                clearFailure(source.id)
                if (index > 0) {
                    ThirdPartySourceDebugLogger.log(
                        "runtime_source_fallback_success",
                        mapOf("index" to index, "sourceId" to source.id)
                    )
                }
                return Result.success(url)
            }

            val error = result.exceptionOrNull()
            lastError = error
            // 拿不到链接的音源进入冷却，后续播放直接跳过，避免反复等待
            markFailed(source.id)
            ThirdPartySourceDebugLogger.log(
                "runtime_source_failed",
                mapOf(
                    "index" to index,
                    "sourceId" to source.id,
                    "sourceName" to source.name,
                    "errorType" to error?.javaClass?.simpleName.orEmpty(),
                    "errorMessage" to error?.message.orEmpty()
                )
            )
        }

        ThirdPartySourceDebugLogger.log(
            "runtime_all_sources_failed",
            mapOf(
                "count" to sources.size,
                "errorMessage" to lastError?.message.orEmpty()
            )
        )
        return Result.failure(
            lastError ?: IllegalStateException("所有第三方音源都没有返回有效链接")
        )
    }

    /**
     * 音源失效后短期跳过。
     *
     * 音源服务端异常时每次请求都要等超时，切换歌曲会明显卡顿；
     * 这里记录失败时间，冷却期内直接跳到下一个音源。
     */
    private fun isCoolingDown(sourceId: String): Boolean {
        val failedAt = synchronized(failedAtMap) { failedAtMap[sourceId] } ?: return false
        if (System.currentTimeMillis() - failedAt > FAILURE_COOLDOWN_MS) {
            synchronized(failedAtMap) { failedAtMap.remove(sourceId) }
            return false
        }
        return true
    }

    private fun markFailed(sourceId: String) {
        synchronized(failedAtMap) { failedAtMap[sourceId] = System.currentTimeMillis() }
    }

    private fun clearFailure(sourceId: String) {
        synchronized(failedAtMap) { failedAtMap.remove(sourceId) }
    }

    fun clear() {
        val snapshot = synchronized(engines) {
            val values = engines.values.toList()
            engines.clear()
            values
        }
        snapshot.forEach { it.destroy() }
        synchronized(failedAtMap) { failedAtMap.clear() }
        ThirdPartySourceDebugLogger.log("runtime_cleared")
    }

    /** 释放某个音源的引擎（音源被停用或删除时调用）。 */
    fun clear(sourceId: String) {
        val engine = synchronized(engines) { engines.remove(sourceId) }
        engine?.destroy()
    }

    private fun ensureEngine(
        context: Context,
        source: ThirdPartySourceInfo
    ): Result<SourceScriptEngine> {
        cachedEngine(source.id)?.let { cached ->
            ThirdPartySourceDebugLogger.log("engine_reuse", mapOf("sourceId" to source.id))
            return Result.success(cached)
        }

        val next = SourceScriptEngine(context.applicationContext, source)
        ThirdPartySourceDebugLogger.log(
            "engine_load_start",
            mapOf(
                "sourceId" to source.id,
                "sourceName" to source.name,
                "fileName" to source.fileName
            )
        )

        val load = next.load()
        if (load.isFailure) {
            next.destroy()
            val error = load.exceptionOrNull()
            ThirdPartySourceDebugLogger.log(
                "engine_load_failed",
                mapOf(
                    "sourceId" to source.id,
                    "errorType" to error?.javaClass?.simpleName.orEmpty(),
                    "errorMessage" to error?.message.orEmpty()
                )
            )
            return Result.failure(error ?: IllegalStateException("Third-party source init failed"))
        }

        ThirdPartySourceDebugLogger.log("engine_load_success", mapOf("sourceId" to source.id))
        cacheEngine(source.id, next)
        return Result.success(next)
    }

    /** 音源失败后的冷却时长。 */
    private const val FAILURE_COOLDOWN_MS = 60_000L
}
