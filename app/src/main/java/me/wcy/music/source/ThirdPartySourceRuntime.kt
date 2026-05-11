package me.wcy.music.source

import android.content.Context
import me.wcy.music.source.quickjs.SourceScriptEngine
import me.wcy.music.storage.preference.ConfigPreferences
import top.wangchenyan.common.CommonApp

object ThirdPartySourceRuntime {
    private var engine: SourceScriptEngine? = null
    private var engineSourceId: String = ""

    fun fetchMusicUrl(info: ThirdPartyMusicInfo): Result<String> {
        if (!ConfigPreferences.thirdPartySourceEnabled) {
            ThirdPartySourceDebugLogger.log("runtime_rejected", mapOf("reason" to "feature_disabled"))
            return Result.failure(IllegalStateException("Third-party source is disabled"))
        }

        val source = ThirdPartySourceStore.selected()
        if (source == null) {
            ThirdPartySourceDebugLogger.log("runtime_rejected", mapOf("reason" to "no_enabled_source"))
            return Result.failure(IllegalStateException("No enabled third-party source"))
        }

        ThirdPartySourceDebugLogger.log(
            "runtime_source_selected",
            mapOf(
                "sourceId" to source.id,
                "sourceName" to source.name,
                "fileName" to source.fileName
            )
        )

        val current = ensureEngine(CommonApp.app, source).getOrElse { error ->
            ThirdPartySourceDebugLogger.log(
                "runtime_engine_failed",
                mapOf(
                    "sourceId" to source.id,
                    "errorType" to error.javaClass.simpleName,
                    "errorMessage" to error.message.orEmpty()
                )
            )
            return Result.failure(error)
        }
        return current.requestMusicUrl(info)
    }

    fun clear() {
        engine?.destroy()
        engine = null
        engineSourceId = ""
        ThirdPartySourceDebugLogger.log("runtime_cleared")
    }

    private fun ensureEngine(
        context: Context,
        source: ThirdPartySourceInfo
    ): Result<SourceScriptEngine> {
        val current = engine
        if (current != null && engineSourceId == source.id) {
            ThirdPartySourceDebugLogger.log("engine_reuse", mapOf("sourceId" to source.id))
            return Result.success(current)
        }

        clear()
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
        engine = next
        engineSourceId = source.id
        return Result.success(next)
    }
}
