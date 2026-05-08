package me.wcy.music.net

import com.blankj.utilcode.util.GsonUtils
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URLEncoder

/**
 * Runs the small NeteaseCloudMusicApi compatibility layer inside the app.
 *
 * Retrofit keeps calling public NCMApi-style paths such as song/url/v1, while this interceptor
 * rewrites them to the direct music.163.com /api endpoints that we already verified locally.
 */
class NeteaseDirectInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != MUSIC_HOST || url.encodedPath.startsWith("/api/")) {
            return chain.proceed(request)
        }

        val path = url.encodedPath.trim('/')
        val query = url.queryMap()

        if (path == "login/qr/create") {
            return syntheticJsonResponse(request, createQrCodeJson(query.required("key")))
        }

        if (path == "playlist/track/all") {
            return requestPlaylistTrackAll(chain, request, query)
        }

        val direct = mapDirectRequest(path, query) ?: return chain.proceed(request)
        val mappedRequest = request.newBuilder()
            .url(
                url.newBuilder()
                    .host(direct.host)
                    .encodedPath(direct.path)
                    .query(null)
                    .build()
            )
            .post(direct.data.toFormBody())
            .build()

        val response = chain.proceed(mappedRequest)
        return when (path) {
            "login/qr/key" -> response.wrapBodyAsData()
            "login/status" -> response.wrapBodyAsData()
            "login/qr/check", "login/cellphone" -> response.injectSetCookieToJson()
            else -> response
        }
    }

    private fun requestPlaylistTrackAll(
        chain: Interceptor.Chain,
        originalRequest: Request,
        query: Map<String, String>
    ): Response {
        val playlistId = query.required("id")
        val limit = query["limit"]?.toIntOrNull() ?: 1000
        val offset = query["offset"]?.toIntOrNull() ?: 0

        val detailRequest = originalRequest.newBuilder()
            .url(originalRequest.url.newBuilder().encodedPath("/api/v6/playlist/detail").query(null).build())
            .post(
                mapOf(
                    "id" to playlistId,
                    "n" to "100000",
                    "s" to (query["s"] ?: "8")
                ).toFormBody()
            )
            .build()
        val detailResponse = chain.proceed(detailRequest)
        val detailBody = detailResponse.body?.string().orEmpty()
        detailResponse.close()

        val ids = JsonParser.parseString(detailBody)
            .asJsonObject
            .getAsJsonObject("playlist")
            ?.getAsJsonArray("trackIds")
            ?.drop(offset)
            ?.take(limit)
            ?.mapNotNull { it.asJsonObject.get("id")?.asLong }
            .orEmpty()
        val c = ids.joinToString(prefix = "[", postfix = "]") { """{"id":$it}""" }

        val songRequest = originalRequest.newBuilder()
            .url(originalRequest.url.newBuilder().encodedPath("/api/v3/song/detail").query(null).build())
            .post(mapOf("c" to c).toFormBody())
            .build()
        return chain.proceed(songRequest)
    }

    private fun mapDirectRequest(path: String, query: Map<String, String>): DirectRequest? {
        return when (path) {
            "login/qr/key" -> DirectRequest("/api/login/qrcode/unikey", mapOf("type" to "3"))
            "captcha/sent" -> DirectRequest(
                "/api/sms/captcha/sent",
                mapOf(
                    "ctcode" to (query["ctcode"] ?: "86"),
                    "secrete" to "music_middleuser_pclogin",
                    "cellphone" to query.required("phone")
                )
            )
            "login/cellphone" -> DirectRequest(
                "/api/w/login/cellphone",
                mapOf(
                    "type" to "1",
                    "https" to "true",
                    "phone" to query.required("phone"),
                    "countrycode" to (query["countrycode"] ?: "86"),
                    "captcha" to query.required("captcha"),
                    "remember" to "true"
                )
            )
            "login/qr/check" -> DirectRequest(
                "/api/login/qrcode/client/login",
                mapOf("key" to query.required("key"), "type" to "3")
            )
            "login/status", "user/account" -> DirectRequest("/api/nuser/account/get")
            "recommend/songs" -> DirectRequest("/api/v3/discovery/recommend/songs")
            "recommend/resource" -> DirectRequest("/api/v1/discovery/recommend/resource")
            "song/url/v1" -> DirectRequest(
                "/api/song/enhance/player/url/v1",
                mapOf(
                    "ids" to "[${query.required("id")}]",
                    "level" to (query["level"] ?: "standard"),
                    "encodeType" to "flac"
                ),
                API_HOST
            )
            "lyric" -> DirectRequest(
                "/api/song/lyric",
                mapOf(
                    "id" to query.required("id"),
                    "tv" to "-1",
                    "lv" to "-1",
                    "rv" to "-1",
                    "kv" to "-1",
                    "_nmclfl" to "1"
                )
            )
            "playlist/detail" -> DirectRequest(
                "/api/v6/playlist/detail",
                mapOf("id" to query.required("id"), "n" to "100000", "s" to (query["s"] ?: "8"))
            )
            "song/detail" -> DirectRequest(
                "/api/v3/song/detail",
                mapOf("c" to query.required("c"))
            )
            "artists" -> DirectRequest(
                "/api/artist/${query.required("id")}",
                mapOf("id" to query.required("id"))
            )
            "artist/songs" -> DirectRequest(
                "/api/v1/artist/songs",
                mapOf(
                    "id" to query.required("id"),
                    "private_cloud" to "true",
                    "work_type" to "1",
                    "limit" to (query["limit"] ?: "50"),
                    "offset" to (query["offset"] ?: "0"),
                    "order" to (query["order"] ?: "hot")
                )
            )
            "artist/album" -> DirectRequest(
                "/api/artist/albums/${query.required("id")}",
                mapOf(
                    "limit" to (query["limit"] ?: "30"),
                    "offset" to (query["offset"] ?: "0"),
                    "total" to "true"
                )
            )
            "album" -> DirectRequest(
                "/api/v1/album/${query.required("id")}",
                mapOf("id" to query.required("id"))
            )
            "playlist/hot" -> DirectRequest("/api/playlist/hottags")
            "top/playlist" -> DirectRequest(
                "/api/playlist/list",
                mapOf(
                    "cat" to (query["cat"] ?: "全部"),
                    "order" to (query["order"] ?: "hot"),
                    "limit" to (query["limit"] ?: "50"),
                    "offset" to (query["offset"] ?: "0"),
                    "total" to "true"
                )
            )
            "toplist" -> DirectRequest("/api/toplist")
            "banner" -> DirectRequest(
                "/api/v2/banner/get",
                mapOf("clientType" to bannerClientType(query["type"]))
            )
            "cloudsearch" -> DirectRequest(
                "/api/cloudsearch/pc",
                mapOf(
                    "s" to query.required("keywords"),
                    "type" to (query["type"] ?: "1"),
                    "limit" to (query["limit"] ?: "30"),
                    "offset" to (query["offset"] ?: "0"),
                    "total" to "true"
                )
            )
            "user/playlist" -> DirectRequest(
                "/api/user/playlist",
                mapOf(
                    "uid" to query.required("uid"),
                    "limit" to (query["limit"] ?: "1000"),
                    "offset" to (query["offset"] ?: "0"),
                    "includeVideo" to "true"
                )
            )
            "playlist/subscribe" -> DirectRequest(
                if (query["t"] == "1") "/api/playlist/subscribe" else "/api/playlist/unsubscribe",
                mapOf("id" to query.required("id"))
            )
            "playlist/tracks" -> DirectRequest(
                "/api/playlist/manipulate/tracks",
                mapOf(
                    "op" to (query["op"] ?: "add"),
                    "pid" to query.required("pid"),
                    "trackIds" to "[${query.required("tracks")}]",
                    "imme" to "true"
                )
            )
            "like" -> DirectRequest(
                "/api/song/like",
                mapOf(
                    "trackId" to query.required("id"),
                    "like" to (query["like"] ?: "true"),
                    "alg" to "itembased",
                    "time" to "3"
                )
            )
            "likelist" -> DirectRequest("/api/song/like/get", mapOf("uid" to query.required("uid")))
            "comment/music" -> DirectRequest(
                "/api/v1/resource/comments/R_SO_4_${query.required("id")}",
                mapOf(
                    "rid" to query.required("id"),
                    "limit" to (query["limit"] ?: "20"),
                    "offset" to (query["offset"] ?: "0"),
                    "beforeTime" to (query["before"] ?: "0")
                )
            )
            "playmode/intelligence/list" -> DirectRequest(
                "/api/playmode/intelligence/list",
                mapOf(
                    "songId" to query.required("id"),
                    "type" to "fromPlayOne",
                    "playlistId" to query.required("pid"),
                    "startMusicId" to (query["sid"] ?: query.required("id")),
                    "count" to (query["count"] ?: "20")
                ),
                API_HOST
            )
            else -> null
        }
    }

    private fun Response.injectSetCookieToJson(): Response {
        val bodyString = body?.string().orEmpty()
        val setCookie = headers("set-cookie").joinToString(";") { it.substringBefore(";") }
        val json = runCatching {
            JsonParser.parseString(bodyString).asJsonObject
        }.getOrElse {
            JsonObject().apply { addProperty("raw", bodyString) }
        }
        if (setCookie.isNotEmpty()) {
            json.addProperty("cookie", setCookie)
        }
        return newBuilder()
            .body(GsonUtils.toJson(json).toResponseBody(JSON))
            .build()
    }

    private fun Response.wrapBodyAsData(): Response {
        val bodyString = body?.string().orEmpty()
        val raw = runCatching {
            JsonParser.parseString(bodyString).asJsonObject
        }.getOrElse {
            JsonObject().apply { addProperty("raw", bodyString) }
        }
        val json = JsonObject().apply {
            addProperty("code", raw.get("code")?.asInt ?: code)
            add("data", raw)
        }
        return newBuilder()
            .body(GsonUtils.toJson(json).toResponseBody(JSON))
            .build()
    }

    private fun syntheticJsonResponse(request: Request, json: String): Response {
        return Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(json.toResponseBody(JSON))
            .build()
    }

    private fun createQrCodeJson(key: String): String {
        val qrUrl = "https://music.163.com/login?codekey=${URLEncoder.encode(key, "UTF-8")}"
        return """{"code":200,"data":{"qrurl":"$qrUrl"}}"""
    }

    private fun bannerClientType(type: String?): String {
        return when (type) {
            "1" -> "android"
            "2" -> "iphone"
            "3" -> "ipad"
            else -> "pc"
        }
    }

    private data class DirectRequest(
        val path: String,
        val data: Map<String, String> = emptyMap(),
        val host: String = MUSIC_HOST
    )

    companion object {
        private const val MUSIC_HOST = "music.163.com"
        private const val API_HOST = "interface.music.163.com"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

private fun okhttp3.HttpUrl.queryMap(): Map<String, String> {
    return queryParameterNames.associateWith { queryParameter(it).orEmpty() }
}

private fun Map<String, String>.toFormBody(): FormBody {
    val builder = FormBody.Builder()
    forEach { (key, value) -> builder.add(key, value) }
    return builder.build()
}

private fun Map<String, String>.required(name: String): String {
    return this[name]?.takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("missing required query: $name")
}
