package com.alexafanasov.chat2desk.commands

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLParserException
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class DirectClientEnrichmentApi(
    private val config: Chat2DeskCommandsConfig,
    httpClient: HttpClient? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : Chat2DeskClientEnrichmentApi {
    private val client: HttpClient =
        httpClient ?: HttpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = config.connectTimeoutMs
                requestTimeoutMillis = config.readTimeoutMs
                socketTimeoutMillis = config.writeTimeoutMs
            }
        }

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
                payload = JsonObject(mapOf("external_id" to JsonPrimitive(externalId))),
            )
        val parsedClient = parsePublicClient(rawResponse)
        config.logDiagnostic(
            "public client update parsed: clientId=$clientId, clientObjectPresent=${parsedClient != null}",
        )
        return parsedClient
    }

    private suspend fun postEmptySdkStart(path: String): ApiResponse {
        val response = client.post(resolveUrl(sdkStartBaseUrl, path)) { setBody("") }
        return response.toApiResponse()
    }

    private suspend fun put(
        path: String,
        payload: JsonObject,
    ): String {
        val response =
            client.put(resolveUrl(apiBaseUrl, path)) {
                header(HttpHeaders.Authorization, config.publicApiToken)
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }.toApiResponse()

        config.logDiagnostic(
            "updateClientExternalId response: status=${response.statusCode}, bodyLength=${response.body.length}",
        )
        return response.body
    }

    private suspend fun io.ktor.client.statement.HttpResponse.toApiResponse(): ApiResponse {
        val rawBody = bodyAsText()
        val statusCode = status.value

        if (statusCode !in HTTP_SUCCESS_RANGE) {
            throw Chat2DeskCommandApiException(
                message = "Chat2Desk client enrichment request failed with status $statusCode",
                statusCode = statusCode,
                errorBody = rawBody,
            )
        }

        return ApiResponse(statusCode = statusCode, body = rawBody)
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
        val parsedUrl =
            try {
                Url(url)
            } catch (e: URLParserException) {
                throw IllegalArgumentException("$fieldName is not a valid URL", e)
            }

        if (config.requireHttps) {
            require(parsedUrl.protocol.name == "https") { "$fieldName must use https scheme" }
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
        val root = json.parseToJsonElement(rawResponse).jsonObject
        val client = root.objectValue("client") ?: return null
        return parseClient(client)
    }

    private fun parsePublicClient(rawResponse: String): PublicClient? {
        val root = json.parseToJsonElement(rawResponse).jsonObject
        val data = root["data"] ?: return null
        return when (data) {
            is JsonObject -> parseClient(data)
            else -> {
                val array =
                    runCatching {
                        data.jsonArray
                    }.getOrNull()
                array?.firstOrNull()?.let(::parseClient)
            }
        }
    }

    private fun parseClient(element: JsonElement): PublicClient? {
        val obj =
            runCatching {
                element.jsonObject
            }.getOrNull() ?: return null
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
        if (value is JsonNull) return null
        return value.jsonPrimitive.contentOrNull
    }

    private fun JsonObject.longValue(name: String): Long? {
        val value = get(name) ?: return null
        if (value is JsonNull) return null
        return value.jsonPrimitive.longOrNull
    }

    private fun JsonObject.objectValue(name: String): JsonObject? {
        val value = get(name) ?: return null
        if (value is JsonNull) return null
        return runCatching { value.jsonObject }.getOrNull()
    }

    private fun JsonObject.toStringMap(): Map<String, String> {
        return entries.associate { (key, value) ->
            key to
                when (value) {
                    is JsonNull -> ""
                    is JsonPrimitive -> value.contentOrNull.orEmpty()
                    else -> value.toString()
                }
        }
    }

    private fun String.urlEncode(): String {
        return encodeURLParameter()
    }

    private data class ApiResponse(
        val statusCode: Int,
        val body: String,
    )

    private companion object {
        val HTTP_SUCCESS_RANGE = 200..299
    }
}
