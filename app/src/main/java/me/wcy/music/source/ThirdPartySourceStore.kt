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

    fun selected(): ThirdPartySourceInfo? {
        val selectedId = ConfigPreferences.thirdPartySourceSelectedId
        return list().firstOrNull { it.id == selectedId && it.enabled }
    }

    fun scriptFile(context: Context, source: ThirdPartySourceInfo): File {
        return File(sourceDir(context), source.fileName)
    }

    fun importSource(context: Context, uri: Uri): ThirdPartySourceInfo {
        val rawName = queryDisplayName(context, uri)
        val name = rawName.substringBeforeLast('.').ifBlank { "第三方音源" }
        val id = UUID.randomUUID().toString()
        val fileName = "$id.js"
        val target = File(sourceDir(context), fileName)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取音源文件" }
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        val next = list().map { it.copy(enabled = false) } + ThirdPartySourceInfo(
            id = id,
            name = name,
            fileName = fileName,
            importTime = System.currentTimeMillis(),
            enabled = true
        )
        saveList(next)
        ConfigPreferences.thirdPartySourceSelectedId = id
        ThirdPartySourceRuntime.clear()
        return next.last()
    }

    fun setEnabled(id: String) {
        val next = list().map { item ->
            item.copy(enabled = item.id == id)
        }
        saveList(next)
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
            val first = next.firstOrNull()
            ConfigPreferences.thirdPartySourceSelectedId = first?.id.orEmpty()
            saveList(next.map { it.copy(enabled = it.id == first?.id) })
        }
        ThirdPartySourceRuntime.clear()
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
