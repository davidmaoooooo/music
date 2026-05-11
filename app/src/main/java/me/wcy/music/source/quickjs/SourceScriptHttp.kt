package me.wcy.music.source.quickjs

import com.google.gson.JsonObject
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import me.wcy.music.net.HttpClient
import me.wcy.music.source.ThirdPartySourceDebugLogger
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object SourceScriptHttp {
    private val client by lazy {
        HttpClient.okHttpClient.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun request(url: String, optionsJson: String): JsonObject {
        val startedAt = System.currentTimeMillis()
        val options = runCatching {
            JsonParser.parseString(optionsJson).asJsonObject
        }.getOrDefault(JsonObject())
        val method = options.string("method")?.uppercase().takeUnless { it.isNullOrBlank() } ?: "GET"
        ThirdPartySourceDebugLogger.logUrl(
            "script_http_start",
            url,
            mapOf(
                "method" to method,
                "bodyPresent" to (options.get("body") != null || options.get("form") != null || options.get("formData") != null)
            )
        )
        val requestBuilder = Request.Builder()
            .url(url)
            .headers(options.headers())

        val bodyText = options.bodyText()
        val body = when {
            method == "GET" || method == "HEAD" -> null
            bodyText != null -> bodyText.toRequestBody(options.contentType())
            else -> ByteArray(0).toRequestBody(null)
        }
        if (body == null) {
            requestBuilder.method(method, null)
        } else {
            requestBuilder.method(method, body)
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val elapsedMs = System.currentTimeMillis() - startedAt
                ThirdPartySourceDebugLogger.logUrl(
                    "script_http_response",
                    url,
                    mapOf(
                        "method" to method,
                        "statusCode" to response.code,
                        "elapsedMs" to elapsedMs,
                        "contentType" to response.header("content-type").orEmpty()
                    )
                )
            val bodyString = response.body?.string().orEmpty()
            val headersJson = JsonObject()
            response.headers.names().forEach { name ->
                headersJson.addProperty(name.lowercase(), response.headers(name).joinToString(","))
            }
            return JsonObject().apply {
                addProperty("statusCode", response.code)
                addProperty("statusMessage", response.message)
                add("headers", headersJson)
                add("body", parseBody(bodyString))
            }
            }
        } catch (error: Throwable) {
            ThirdPartySourceDebugLogger.logUrl(
                "script_http_failed",
                url,
                mapOf(
                    "method" to method,
                    "elapsedMs" to (System.currentTimeMillis() - startedAt),
                    "errorType" to error.javaClass.simpleName,
                    "errorMessage" to error.message.orEmpty()
                )
            )
            throw error
        }
    }

    private fun parseBody(body: String): JsonElement {
        val trimmed = body.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("\"")) {
            runCatching {
                return JsonParser.parseString(trimmed)
            }
        }
        return JsonPrimitive(body)
    }

    private fun JsonObject.headers(): Headers {
        val builder = Headers.Builder()
        val headers = getAsJsonObject("headers") ?: return builder.build()
        headers.entrySet().forEach { (name, value) ->
            if (!value.isJsonNull) {
                builder.set(name, value.asString)
            }
        }
        return builder.build()
    }

    private fun JsonObject.bodyText(): String? {
        get("body")?.takeUnless { it.isJsonNull }?.let {
            return if (it.isJsonPrimitive) it.asString else it.toString()
        }
        getAsJsonObject("form")?.let { form ->
            return form.entrySet().joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value.asString, "UTF-8")}"
            }
        }
        getAsJsonObject("formData")?.let { form ->
            return form.entrySet().joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value.asString, "UTF-8")}"
            }
        }
        return null
    }

    private fun JsonObject.contentType() =
        getAsJsonObject("headers")
            ?.entrySet()
            ?.firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value
            ?.asString
            ?.toMediaTypeOrNull()
            ?: get("body")
                ?.takeIf { it.isJsonObject || it.isJsonArray }
                ?.let { "application/json; charset=utf-8".toMediaType() }

    private fun JsonObject.string(name: String): String? {
        return get(name)?.takeUnless { it.isJsonNull }?.asString
    }
}
