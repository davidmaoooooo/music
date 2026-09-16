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
                lastError = error
                null
            } ?: continue

            val result = engine.requestMusicUrl(info)
            val url = result.getOrNull()
            if (!url.isNullOrEmpty()) {
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

    fun clear() {
        val snapshot = synchronized(engines) {
            val values = engines.values.toList()
            engines.clear()
            values
        }
        snapshot.forEach { it.destroy() }
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
}
