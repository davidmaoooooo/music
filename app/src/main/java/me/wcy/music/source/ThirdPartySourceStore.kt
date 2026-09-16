package me.wcy.music.source

import android.content.Context
import android.net.Uri
import me.wcy.music.storage.preference.ConfigPreferences
import top.wangchenyan.common.utils.GsonUtils
import java.io.File
import java.util.UUID

object ThirdPartySourceStore {
    private const val DIR_NAME = "third_party_sources"

    fun list(): List<ThirdPartySourceInfo> {
        val json = ConfigPreferences.thirdPartySourceListJson
        if (json.isBlank()) return emptyList()
        return runCatching {
            GsonUtils.fromJsonList(json, ThirdPartySourceInfo::class.java)
        }.getOrNull().orEmpty()
    }

    /**
     * 已启用的音源，按启用顺序排列。
     *
     * 播放时从第一个开始请求，失败后依次回退到下一个。
     */
    fun enabledSources(): List<ThirdPartySourceInfo> {
        return list().filter { it.enabled }
    }

    /** 当前优先使用的音源，仅用于界面展示。 */
    fun selected(): ThirdPartySourceInfo? {
        val selectedId = ConfigPreferences.thirdPartySourceSelectedId
        val enabled = enabledSources()
        return enabled.firstOrNull { it.id == selectedId } ?: enabled.firstOrNull()
    }

    fun scriptFile(context: Context, source: ThirdPartySourceInfo): File {
        return File(sourceDir(context), source.fileName)
    }

    fun importSource(context: Context, uri: Uri): ThirdPartySourceInfo {
        val rawName = queryDisplayName(context, uri)
        val fallbackName = rawName.substringBeforeLast('.').ifBlank { "第三方音源" }
        val id = UUID.randomUUID().toString()
        val fileName = "$id.js"
        val script = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: throw IllegalArgumentException("无法读取音源文件")
        return addSource(context, script, fallbackName, id, fileName)
    }

    /** 通过链接导入音源脚本。 */
    fun importSourceFromUrl(context: Context, url: String): ThirdPartySourceInfo {
        val script = ThirdPartySourceDownloader.download(url)
        val fallbackName = ThirdPartySourceDownloader.nameFromUrl(url)
        val id = UUID.randomUUID().toString()
        val fileName = "$id.js"
        return addSource(context, script, fallbackName, id, fileName)
    }

    private fun addSource(
        context: Context,
        script: String,
        fallbackName: String,
        id: String,
        fileName: String
    ): ThirdPartySourceInfo {
        val target = File(sourceDir(context), fileName)
        target.writeText(script, Charsets.UTF_8)
        val scriptInfo = ThirdPartySourceScriptInfo.parse(script)
        val next = list().filterNot { it.id == id } + ThirdPartySourceInfo(
            id = id,
            name = scriptInfo.name.ifBlank { fallbackName },
            description = scriptInfo.description,
            version = scriptInfo.version,
            author = scriptInfo.author,
            homepage = scriptInfo.homepage,
            fileName = fileName,
            importTime = System.currentTimeMillis(),
            enabled = true
        )
        saveList(next)
        ConfigPreferences.thirdPartySourceSelectedId = id
        ThirdPartySourceRuntime.clear()
        return next.last()
    }

    /** 启用/停用单个音源，多个音源可以同时启用。 */
    fun setEnabled(id: String, enabled: Boolean = true) {
        val next = list().map { item ->
            if (item.id == id) item.copy(enabled = enabled) else item
        }
        saveList(next)
        val enabledList = next.filter { it.enabled }
        if (enabledList.none { it.id == ConfigPreferences.thirdPartySourceSelectedId }) {
            ConfigPreferences.thirdPartySourceSelectedId = enabledList.firstOrNull()?.id.orEmpty()
        }
        if (!enabled) {
            // 停用后释放该音源的脚本引擎，避免继续占用内存
            ThirdPartySourceRuntime.clear(id)
        }
    }

    /** 把指定音源提到最前，作为优先请求的音源。 */
    fun setPriority(id: String) {
        val current = list()
        val target = current.firstOrNull { it.id == id } ?: return
        val reordered = listOf(target.copy(enabled = true)) +
            current.filterNot { it.id == id }
        saveList(reordered)
        ConfigPreferences.thirdPartySourceSelectedId = id
        ThirdPartySourceRuntime.clear()
    }

    fun remove(context: Context, id: String) {
        val current = list()
        val target = current.firstOrNull { it.id == id }
        if (target != null) {
            scriptFile(context, target).delete()
        }
        val next = current.filterNot { it.id == id }
        saveList(next)
        if (ConfigPreferences.thirdPartySourceSelectedId == id) {
            ConfigPreferences.thirdPartySourceSelectedId = next.firstOrNull { it.enabled }?.id.orEmpty()
        }
        ThirdPartySourceRuntime.clear(id)
    }

    fun readScript(context: Context, source: ThirdPartySourceInfo): String {
        return scriptFile(context, source).readText(Charsets.UTF_8)
    }

    private fun saveList(list: List<ThirdPartySourceInfo>) {
        ConfigPreferences.thirdPartySourceListJson = GsonUtils.toJson(list).orEmpty()
    }

    private fun sourceDir(context: Context): File {
        return File(context.filesDir, DIR_NAME).apply { mkdirs() }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)
                } else {
                    uri.lastPathSegment
                }
            }
        }.getOrNull().orEmpty().ifBlank { "第三方音源" }
    }
}
