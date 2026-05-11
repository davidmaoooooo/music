package me.wcy.music.net.datasource

object NeteaseSoundQuality {
    private val levels = listOf(
        "standard",
        "higher",
        "exhigh",
        "lossless",
        "hires",
        "jyeffect",
        "sky",
        "dolby",
        "jymaster"
    )

    fun normalize(value: String): String {
        return value.takeIf { it in levels } ?: "standard"
    }

    fun fallbackLevels(preferred: String): List<String> {
        val start = levels.indexOf(normalize(preferred)).coerceAtLeast(0)
        val result = mutableListOf(levels[start])
        var index = start
        while (index > 0) {
            index = (index - 2).coerceAtLeast(0)
            result += levels[index]
        }
        if (result.last() != "standard") {
            result += "standard"
        }
        return result.distinct()
    }

    fun accepts(requested: String, returned: String): Boolean {
        val requestedIndex = levels.indexOf(normalize(requested))
        val returnedIndex = levels.indexOf(normalize(returned))
        return returnedIndex >= requestedIndex
    }
}
