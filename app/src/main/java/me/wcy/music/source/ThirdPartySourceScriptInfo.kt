package me.wcy.music.source

/**
 * 解析落雪音乐音源脚本头部的注释块元数据。
 *
 * 脚本通过 globalThis.lx.currentScriptInfo 读取这些字段，部分脚本会依赖 name/description
 * 做自校验，因此必须按脚本声明解析，而不能用文件名代替。
 */
object ThirdPartySourceScriptInfo {
    // 与落雪音乐 lx-music-mobile 的 INFO_NAMES 长度限制保持一致
    private const val MAX_NAME = 24
    private const val MAX_DESCRIPTION = 36
    private const val MAX_VERSION = 36
    private const val MAX_AUTHOR = 56
    private const val MAX_HOMEPAGE = 1024

    private val commentRegex = Regex("^/\\*[\\s\\S]+?\\*/")
    private val fieldRegex = Regex("^\\s?\\*\\s?@(\\w+)\\s(.+)$", RegexOption.MULTILINE)

    fun parse(script: String): ThirdPartySourceInfo {
        val comment = commentRegex.find(script)?.value.orEmpty()
        val fields = mutableMapOf<String, String>()
        fieldRegex.findAll(comment).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2].trim()
            if (key in SUPPORTED_KEYS && value.isNotEmpty()) {
                fields[key] = value
            }
        }
        return ThirdPartySourceInfo(
            name = fields["name"].orEmpty().truncate(MAX_NAME),
            description = fields["description"].orEmpty().truncate(MAX_DESCRIPTION),
            version = fields["version"].orEmpty().truncate(MAX_VERSION),
            author = fields["author"].orEmpty().truncate(MAX_AUTHOR),
            homepage = fields["homepage"].orEmpty().truncate(MAX_HOMEPAGE)
        )
    }

    private fun String.truncate(max: Int): String {
        return if (length > max) substring(0, max) + "..." else this
    }

    private val SUPPORTED_KEYS = setOf("name", "description", "version", "author", "homepage")
}
