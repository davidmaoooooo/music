package me.wcy.music.source

import me.wcy.music.source.quickjs.SourceScriptEngine
import top.wangchenyan.common.CommonApp

/**
 * 音源导入后的可用性自检。
 *
 * 用一个固定的付费歌曲请求播放链接：付费歌曲对音源要求最高（需要音源侧带会员 token），
 * 若连它都能返回链接，说明音源当前可用；返回失败则提示音源可能已失效。
 */
object ThirdPartySourceVerifier {
    /** 固定测试歌曲：付费歌曲（fee=1），对音源权限要求最高。 */
    const val TEST_SONG_ID = 1974443814L
    private const val TEST_SONG_NAME = "Self Love"
    private const val TEST_SONG_ARTIST = "Metro Boomin/Coi Leray"
    private const val TEST_SONG_ALBUM = "METRO BOOMIN PRESENTS SPIDER-MAN: ACROSS THE SPIDER-VERSE"
    private const val TEST_SONG_INTERVAL = 189_000L

    data class VerifyResult(
        val source: ThirdPartySourceInfo,
        val ok: Boolean,
        val message: String
    )

    fun testSongInfo(): ThirdPartyMusicInfo {
        return ThirdPartyMusicInfo(
            id = TEST_SONG_ID,
            name = TEST_SONG_NAME,
            singer = TEST_SONG_ARTIST,
            albumName = TEST_SONG_ALBUM,
            interval = TEST_SONG_INTERVAL
        )
    }

    /**
     * 直接向指定音源请求测试歌曲。
     *
     * 不走 [ThirdPartySourceRuntime] 的回退链路，避免多个音源互相掩盖问题。
     */
    fun verify(source: ThirdPartySourceInfo): VerifyResult {
        ThirdPartySourceDebugLogger.log(
            "verify_start",
            mapOf("sourceId" to source.id, "sourceName" to source.name, "songId" to TEST_SONG_ID)
        )
        val engine = SourceScriptEngine(CommonApp.app, source)
        val load = engine.load()
        if (load.isFailure) {
            engine.destroy()
            val error = load.exceptionOrNull()
            ThirdPartySourceDebugLogger.log(
                "verify_load_failed",
                mapOf("sourceId" to source.id, "errorMessage" to error?.message.orEmpty())
            )
            return VerifyResult(source, false, "脚本加载失败：${error?.message ?: "未知错误"}")
        }
        val result = engine.requestMusicUrl(testSongInfo())
        engine.destroy()
        val url = result.getOrNull()
        return if (!url.isNullOrEmpty()) {
            ThirdPartySourceDebugLogger.logUrl(
                "verify_success",
                url,
                mapOf("sourceId" to source.id)
            )
            VerifyResult(source, true, "音源可用（已通过付费歌曲测试）")
        } else {
            val error = result.exceptionOrNull()
            ThirdPartySourceDebugLogger.log(
                "verify_failed",
                mapOf(
                    "sourceId" to source.id,
                    "errorMessage" to error?.message.orEmpty()
                )
            )
            VerifyResult(
                source,
                false,
                "音源可能已失效：付费歌曲未返回链接（${error?.message ?: "无返回"}）"
            )
        }
    }
}
