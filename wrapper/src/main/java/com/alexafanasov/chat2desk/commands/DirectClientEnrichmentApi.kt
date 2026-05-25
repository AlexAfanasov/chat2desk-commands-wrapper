package com.alexafanasov.chat2desk.commands

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class DirectClientEnrichmentApi(
    private val config: Chat2DeskCommandsConfig,
    httpClient: OkHttpClient? = null,
    private val gson: Gson = Gson(),
) : Chat2DeskClientEnrichmentApi {
    private val client: OkHttpClient =
        httpClient ?: OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(config.writeTimeoutMs, TimeUnit.MILLISECONDS)
            .build()

    private val apiBaseUrl = config.baseUrl.trimEnd('/')
    private val sdkStartBaseUrl = config.sdkStartBaseUrl.trimEnd('/')
    private val trustedHostSuffixes =
        config.trustedHostSuffixes
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

    init {
        require(apiBaseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(sdkStartBaseUrl.isNotBlank()) { "sdkStartBaseUrl must not be blank" }
        require(config.publicApiToken.isNotBlank()) { "publicApiToken must not be blank" }
        require(trustedHostSuffixes.isNotEmpty()) { "trustedHostSuffixes must not be empty" }

        validateEndpointUrl(url = apiBaseUrl, fieldName = "baseUrl")
        validateEndpointUrl(url = sdkStartBaseUrl, fieldName = "sdkStartBaseUrl")
    }

    override suspend fun resolveStartedClient(clientKey: String): PublicClient? {
        require(clientKey.isNotBlank()) { "clientKey must not be blank" }

        val widgetToken = config.sdkWidgetToken?.takeIf { it.isNotBlank() }
        if (widgetToken == null) {
            config.logDiagnostic("sdk start client resolve skipped: sdkWidgetToken missing")
            return null
        }

        config.logDiagnostic("sdk start client resolve request: ${clientKey.presenceAndLength("clientKey")}")
        val response =
            postEmptySdkStart(
                path = "/start?id=${widgetToken.urlEncode()}&client_key=${clientKey.urlEncode()}",
            )
        val client = parseStartedClient(response.body)
        config.logDiagnostic(
            "sdk start client resolve response: status=${response.statusCode}, " +
                "bodyLength=${response.body.length}, clientId=${client?.id}",
        )
        return client
    }

    override suspend fun updateClientExternalId(
        clientId: Long,
        externalId: String,
    ): PublicClient? {
        require(clientId > 0) { "clientId must be positive" }
        require(externalId.isNotBlank()) { "externalId must not be blank" }

        config.logDiagnostic(
            "updateClientExternalId request: clientId=$clientId, externalIdPresent=true, " +
                "externalIdLength=${externalId.length}",
        )
        val rawResponse =
            put(
                path = "/v1/clients/$clientId",
                payload = mapOf("external_id" to externalId),
            )
        val parsedClient = parsePublicClient(rawResponse)
        config.logDiagnostic(
            "public client update parsed: clientId=$clientId, clientObjectPresent=${parsedClient != null}",
        )
        return parsedClient
    }

    private suspend fun postEmptySdkStart(path: String): ApiResponse {
        val request =
            Request.Builder()
                .url(resolveUrl(sdkStartBaseUrl, path))
                .post("".toRequestBody(null))
                .build()

        return executeWithMetadata(request)
    }

    private suspend fun put(
        path: String,
        payload: Map<String, Any?>,
    ): String {
        val body = gson.toJson(payload).toRequestBody(JSON_MEDIA_TYPE)
        val request =
            Request.Builder()
                .url(resolveUrl(apiBaseUrl, path))
                .addHeader("Authorization", config.publicApiToken)
                .put(body)
                .build()

        val response = executeWithMetadata(request)
        config.logDiagnostic(
            "updateClientExternalId response: status=${response.statusCode}, bodyLength=${response.body.length}",
        )
        return response.body
    }

    private suspend fun executeWithMetadata(request: Request): ApiResponse {
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    throw Chat2DeskCommandApiException(
                        message = "Chat2Desk client enrichment request failed with status ${response.code}",
                        statusCode = response.code,
                        errorBody = rawBody,
                    )
                }

                ApiResponse(statusCode = response.code, body = rawBody)
            }
        }
    }

    private fun resolveUrl(
        targetBaseUrl: String,
        path: String,
    ): String {
        return if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            "$targetBaseUrl$path"
        }
    }

    private fun validateEndpointUrl(
        url: String,
        fieldName: String,
    ) {
        val parsedUrl = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("$fieldName is not a valid URL")
        if (config.requireHttps) {
            require(parsedUrl.isHttps) { "$fieldName must use https scheme" }
        }

        require(isTrustedHost(parsedUrl.host)) {
            "$fieldName host '${parsedUrl.host}' is not allowed by trustedHostSuffixes"
        }
    }

    private fun isTrustedHost(host: String): Boolean {
        val lowercaseHost = host.lowercase()
        return trustedHostSuffixes.any { suffix ->
            lowercaseHost == suffix || lowercaseHost.endsWith(".$suffix")
        }
    }

    private fun parseStartedClient(rawResponse: String): PublicClient? {
        val root = gson.fromJson(rawResponse, JsonObject::class.java)
        val client = root.objectValue("client") ?: return null
        return parseClient(client)
    }

    private fun parsePublicClient(rawResponse: String): PublicClient? {
        val root = gson.fromJson(rawResponse, JsonObject::class.java)
        val data = root.get("data") ?: return null
        return when {
            data.isJsonArray -> data.asJsonArray.firstOrNull()?.let(::parseClient)
            data.isJsonObject -> parseClient(data)
            else -> null
        }
    }

    private fun parseClient(element: JsonElement): PublicClient? {
        if (!element.isJsonObject) return null

        val obj = element.asJsonObject
        val id =
            obj.longValue("clientID")
                ?: obj.longValue("client_id")
                ?: return null
        return PublicClient(
            id = id,
            phone = obj.stringValue("phone") ?: obj.stringValue("client_phone"),
            name = obj.stringValue("name") ?: obj.stringValue("assigned_name"),
            customFields = obj.objectValue("custom_fields")?.toStringMap().orEmpty(),
        )
    }

    private fun JsonObject.stringValue(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return value.asString
    }

    private fun JsonObject.longValue(name: String): Long? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return value.asLong
    }

    private fun JsonObject.objectValue(name: String): JsonObject? {
        val value = get(name) ?: return null
        if (value.isJsonNull || !value.isJsonObject) return null
        return value.asJsonObject
    }

    private fun JsonObject.toStringMap(): Map<String, String> {
        return entrySet().associate { (key, value) ->
            key to
                when {
                    value.isJsonNull -> ""
                    value.isJsonPrimitive -> value.asString
                    else -> value.toString()
                }
        }
    }

    private fun String.urlEncode(): String {
        return URLEncoder.encode(this, Charsets.UTF_8.name())
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private data class ApiResponse(
        val statusCode: Int,
        val body: String,
    )
}
