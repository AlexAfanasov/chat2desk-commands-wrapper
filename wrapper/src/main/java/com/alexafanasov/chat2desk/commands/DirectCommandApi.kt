package com.alexafanasov.chat2desk.commands

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class DirectCommandApi(
    private val config: Chat2DeskCommandsConfig,
    httpClient: OkHttpClient? = null,
    private val gson: Gson = Gson(),
) : CommandApi {
    private val client: OkHttpClient =
        httpClient ?: OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(config.writeTimeoutMs, TimeUnit.MILLISECONDS)
            .build()

    private val baseUrl = config.baseUrl.trimEnd('/')

    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(config.apiToken.isNotBlank()) { "apiToken must not be blank" }
    }

    override suspend fun sendInboxCommand(
        command: String,
        clientId: String,
        options: InboxOptions,
    ) {
        val payload =
            linkedMapOf<String, Any?>(
                "text" to command,
                "client_id" to clientId,
            )

        val resolvedChannel = options.channelId ?: config.defaultChannelId
        val resolvedTransport = options.transport ?: config.defaultTransport

        if (resolvedChannel != null) payload["channel_id"] = resolvedChannel
        if (!resolvedTransport.isNullOrBlank()) payload["transport"] = resolvedTransport
        if (!options.clientPhone.isNullOrBlank()) payload["client_phone"] = options.clientPhone
        if (!options.externalId.isNullOrBlank()) payload["external_id"] = options.externalId
        if (options.customFields.isNotEmpty()) payload["custom_fields"] = options.customFields

        val firstAttachment = options.attachments.firstOrNull()
        if (firstAttachment != null) {
            payload["attachment"] = firstAttachment.url
            if (!firstAttachment.filename.isNullOrBlank()) {
                payload["attachment_filename"] = firstAttachment.filename
            }
        }

        post(path = "/v1/messages/inbox", payload = payload)
    }

    override suspend fun sendOperatorMessage(request: OperatorMessageRequest): OperatorMessageResult {
        val payload =
            linkedMapOf<String, Any?>(
                "client_id" to request.clientId,
                "type" to request.type,
            )

        val resolvedChannel = request.channelId ?: config.defaultChannelId
        val resolvedTransport = request.transport ?: config.defaultTransport

        if (!request.text.isNullOrBlank()) payload["text"] = request.text
        if (!request.attachment.isNullOrBlank()) payload["attachment"] = request.attachment
        if (!request.attachmentFilename.isNullOrBlank()) payload["attachment_filename"] = request.attachmentFilename
        if (resolvedChannel != null) payload["channel_id"] = resolvedChannel
        if (request.operatorId != null) payload["operator_id"] = request.operatorId
        if (!resolvedTransport.isNullOrBlank()) payload["transport"] = resolvedTransport
        if (request.openDialog != null) payload["open_dialog"] = request.openDialog
        if (!request.externalId.isNullOrBlank()) payload["external_id"] = request.externalId
        if (request.encrypted != null) payload["encrypted"] = request.encrypted
        if (request.replyMessageId != null) payload["reply_message_id"] = request.replyMessageId
        if (request.inlineButtons.isNotEmpty()) {
            payload["inline_buttons"] = request.inlineButtons.map(::toButtonMap)
        }
        request.keyboard?.let { keyboard ->
            payload["keyboard"] = mapOf("buttons" to keyboard.buttons.map(::toButtonMap))
        }
        request.interactive?.let { interactive ->
            payload["interactive"] = interactive
        }

        val rawResponse = post(path = "/v1/messages", payload = payload)
        val data = extractDataObject(rawResponse)

        return OperatorMessageResult(
            messageId = data?.longValue("message_id"),
            channelId = data?.longValue("channel_id"),
            operatorId = data?.longValue("operator_id"),
            transport = data?.stringValue("transport"),
            type = data?.stringValue("type"),
            clientId = data?.stringValue("client_id"),
            dialogId = data?.longValue("dialog_id"),
            requestId = data?.longValue("request_id"),
        )
    }

    override suspend fun loadMenuCommands(channelId: Long?): List<MenuCommand> {
        val resolvedChannelId = channelId ?: config.defaultChannelId
        val path =
            if (resolvedChannelId != null) {
                "/v1/scenarios/menu_items?channel_id=$resolvedChannelId"
            } else {
                "/v1/scenarios/menu_items"
            }

        val rawResponse = get(path)
        val root = gson.fromJson(rawResponse, JsonObject::class.java)
        val data = root.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

        return data.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val obj = item.asJsonObject
            val id = obj.longValue("id") ?: return@mapNotNull null
            val text = obj.stringValue("text").orEmpty()

            MenuCommand(
                id = id,
                text = text,
                command = obj.stringValue("command"),
                parentId = obj.longValue("parentID") ?: obj.longValue("parent_id"),
                position = obj.intValue("position"),
            )
        }
    }

    private suspend fun post(
        path: String,
        payload: Map<String, Any?>,
    ): String {
        val body = gson.toJson(payload).toRequestBody(JSON_MEDIA_TYPE)
        val request =
            Request.Builder()
                .url(resolveUrl(path))
                .addHeader("Authorization", config.apiToken)
                .post(body)
                .build()

        return execute(request)
    }

    private suspend fun get(path: String): String {
        val request =
            Request.Builder()
                .url(resolveUrl(path))
                .addHeader("Authorization", config.apiToken)
                .get()
                .build()

        return execute(request)
    }

    private suspend fun execute(request: Request): String {
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    throw Chat2DeskCommandApiException(
                        message = "Chat2Desk Public API request failed with status ${response.code}",
                        statusCode = response.code,
                        errorBody = rawBody,
                    )
                }

                rawBody
            }
        }
    }

    private fun resolveUrl(path: String): String {
        return if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            "$baseUrl$path"
        }
    }

    private fun extractDataObject(rawResponse: String): JsonObject? {
        val root = gson.fromJson(rawResponse, JsonObject::class.java)
        val data = root.get("data") ?: return null
        return data.takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun toButtonMap(button: CommandButton): Map<String, String> {
        val payload =
            linkedMapOf<String, String>(
                "type" to button.type,
                "text" to button.text,
            )

        if (!button.payload.isNullOrBlank()) payload["payload"] = button.payload
        if (!button.color.isNullOrBlank()) payload["color"] = button.color
        if (!button.url.isNullOrBlank()) payload["url"] = button.url

        return payload
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

    private fun JsonObject.intValue(name: String): Int? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return value.asInt
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
