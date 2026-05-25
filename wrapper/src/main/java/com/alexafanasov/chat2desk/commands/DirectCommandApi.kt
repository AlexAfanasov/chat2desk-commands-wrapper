package com.alexafanasov.chat2desk.commands

import com.chat2desk.chat2desk_sdk.IAttachment
import com.chat2desk.chat2desk_sdk.domain.entities.Attachment
import com.chat2desk.chat2desk_sdk.domain.entities.DeliveryStatus
import com.chat2desk.chat2desk_sdk.domain.entities.Message
import com.chat2desk.chat2desk_sdk.domain.entities.MessageType
import com.chat2desk.chat2desk_sdk.domain.entities.ReadStatus
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.TimeUnit
import kotlinx.datetime.Instant

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

    private val apiBaseUrl = config.baseUrl.trimEnd('/')
    private val uploadBaseUrl = config.uploadBaseUrl.trimEnd('/')
    private val trustedHostSuffixes =
        config.trustedHostSuffixes
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()

    init {
        require(apiBaseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(uploadBaseUrl.isNotBlank()) { "uploadBaseUrl must not be blank" }
        require(config.apiToken.isNotBlank()) { "apiToken must not be blank" }
        require(trustedHostSuffixes.isNotEmpty()) { "trustedHostSuffixes must not be empty" }

        validateEndpointUrl(url = apiBaseUrl, fieldName = "baseUrl")
        validateEndpointUrl(url = uploadBaseUrl, fieldName = "uploadBaseUrl")
    }

    override suspend fun sendInboxCommand(
        command: String,
        clientId: String,
        options: InboxOptions,
    ) {
        val fromClient = resolveInboxClient(clientId = clientId, options = options)

        val payload =
            linkedMapOf<String, Any?>(
                "body" to command,
            )

        val resolvedChannel = positiveChannelId(options.channelId ?: config.defaultChannelId)
        val resolvedTransport = options.transport

        if (resolvedChannel != null) payload["channel_id"] = resolvedChannel
        if (!resolvedTransport.isNullOrBlank()) payload["transport"] = resolvedTransport
        if (!options.clientPhone.isNullOrBlank()) payload["client_phone"] = options.clientPhone

        payload["from_client"] = toFromClientMap(fromClient)

        if (!options.externalId.isNullOrBlank()) {
            payload["extra_data"] = mapOf("external_id" to options.externalId)
        }

        if (options.customFields.isNotEmpty()) {
            payload["custom_fields"] = options.customFields
        }

        val firstAttachment = options.attachments.firstOrNull()
        if (firstAttachment != null) {
            val normalizedAttachmentUrl = normalizeAttachmentUrl(firstAttachment.url)
            payload["attachment"] = normalizedAttachmentUrl
            firstAttachment.filename?.takeIf { it.isNotBlank() }?.let {
                payload["attachment_filename"] = it
            }
        }

        logDiagnostic("sendInboxCommand request: ${payload.safePayloadSummary()}")

        val rawResponse = post(path = "/v1/messages/inbox", payload = payload)

        logDiagnostic("sendInboxCommand response: ${rawResponse.compactForLog()}")
    }

    override suspend fun sendOperatorMessage(request: OperatorMessageRequest): OperatorMessageResult {
        val payload =
            linkedMapOf<String, Any?>(
                "client_id" to request.clientId,
                "type" to request.type,
            )

        val resolvedChannel = positiveChannelId(request.channelId ?: config.defaultChannelId)
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
        val resolvedChannelId = positiveChannelId(channelId ?: config.defaultChannelId)
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

    override suspend fun loadMessages(request: PublicMessagesRequest): List<Message> {
        val url =
            resolveApiUrl("/v1/messages").toHttpUrlOrNull()
                ?: throw IllegalArgumentException("baseUrl is not a valid URL")

        val builder =
            url.newBuilder()
                .addQueryParameter("offset", request.offset.coerceAtLeast(0).toString())
                .addQueryParameter("limit", request.limit.coerceAtLeast(1).toString())

        request.clientId?.let { builder.addQueryParameter("client_id", it.toString()) }
        positiveChannelId(request.channelId)?.let { builder.addQueryParameter("channel_id", it.toString()) }
        request.transport?.takeIf { it.isNotBlank() }?.let {
            builder.addQueryParameter("transport", it)
        }

        val builtUrl = builder.build()

        logDiagnostic(
            "loadMessages request: path=${builtUrl.encodedPath}, query=${builtUrl.encodedQuery}"
        )

        val httpRequest =
            Request.Builder()
                .url(builtUrl)
                .addHeader("Authorization", config.apiToken)
                .get()
                .build()

        val rawResponse = execute(httpRequest)

        logDiagnostic("loadMessages response: ${rawResponse.compactForLog()}")

        val parsed = parseMessages(rawResponse)

        logDiagnostic(
            "loadMessages parsed: count=${parsed.size}, " +
                    "ids=${parsed.take(10).joinToString { it.id }}, " +
                    "types=${parsed.take(10).joinToString { it.type.name }}"
        )

        return parsed
    }

    override suspend fun findClientByPhone(request: PublicClientLookupRequest): PublicClient? {
        val phone = request.phone.trim()
        require(phone.isNotBlank()) { "phone must not be blank" }

        logDiagnostic("findClientByPhone request: phone=${maskPhone(phone)}")
        val url =
            resolveApiUrl("/v1/clients").toHttpUrlOrNull()
                ?: throw IllegalArgumentException("baseUrl is not a valid URL")
        val httpRequest =
            Request.Builder()
                .url(url.newBuilder().addQueryParameter("phone", phone).build())
                .addHeader("Authorization", config.apiToken)
                .get()
                .build()

        val clients = parseClients(execute(httpRequest))
        val matched = clients.firstOrNull { client ->
            client.phone?.digitsOnly() == phone.digitsOnly()
        } ?: clients.firstOrNull()

        logDiagnostic(
            "findClientByPhone parsed: count=${clients.size}, " +
                    "matchedId=${matched?.id ?: "-"}, " +
                    "matchedPhone=${maskPhone(matched?.phone)}",
        )
        return matched
    }

    override suspend fun createClient(request: PublicClientCreateRequest): PublicClient {
        logDiagnostic(
            "createClient request: nameBlank=${request.name.isBlank()}, " +
                    "phone=${maskPhone(request.phone)}, " +
                    "customFields=${request.customFields.keys.sorted()}",
        )
        val payload =
            linkedMapOf<String, Any?>(
                "phone" to request.phone,
            )
        if (request.name.isNotBlank()) payload["name"] = request.name
        if (request.customFields.isNotEmpty()) payload["custom_fields"] = request.customFields

        val rawResponse = post(path = "/v1/clients", payload = payload)
        val parsed = parseClients(rawResponse)
        logDiagnostic("createClient parsed: count=${parsed.size}, firstId=${parsed.firstOrNull()?.id ?: "-"}")
        return parsed.firstOrNull()
            ?: throw Chat2DeskCommandApiException(
                message = "Chat2Desk Public API create client response did not contain client id",
                statusCode = 200,
                errorBody = rawResponse,
            )
    }

    override suspend fun loadChannels(): List<PublicChannel> {
        logDiagnostic("loadChannels request: /v1/channels")
        val rawResponse = get("/v1/channels")
        logDiagnostic("loadChannels response: ${rawResponse.compactForLog()}")
        val parsed = parseChannels(rawResponse)
        logDiagnostic(
            "loadChannels parsed: count=${parsed.size}, " +
                    "channels=${parsed.joinToString { "${it.id}:${it.transport ?: "-"}:${it.name ?: "-"}" }}",
        )
        return parsed
    }

    override suspend fun uploadAttachment(attachment: IAttachment): InboxAttachment {
        val attachmentSize = attachment.fileSize.toLong()
        require(attachmentSize > 0) { "Attachment size must be greater than zero" }
        require(attachmentSize <= config.maxUploadBytes) {
            "Attachment size $attachmentSize exceeds maxUploadBytes=${config.maxUploadBytes}"
        }

        val attachmentPath = attachment.getFilePath().trim()
        require(attachmentPath.isNotBlank()) { "Attachment file path must not be blank" }

        val file = File(attachmentPath)
        require(file.exists()) { "Attachment file does not exist: $attachmentPath" }
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Attachment file path is not a regular file: $attachmentPath"
        }

        val fileName =
            attachment.originalName
                .takeIf { it.isNotBlank() }
                ?: file.name.takeIf { it.isNotBlank() }
                ?: "attachment"
        val contentType = attachment.mimeType.toMediaTypeOrNull()
        val uploadBody =
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("widget_token", resolveUploadToken())
                .addFormDataPart(
                    "attachments[]",
                    fileName.replace(" ", "_"),
                    file.asRequestBody(contentType),
                )
                .build()
        val httpRequest =
            Request.Builder()
                .url(resolveUploadUrl("/upload_attach"))
                .post(uploadBody)
                .build()
        val rawResponse = execute(httpRequest)

        logDiagnostic("uploadAttachment response: ${rawResponse.compactForLog()}")

        val uploadedPath = extractUploadContentPath(rawResponse)
        val normalizedUrl = normalizeAttachmentUrl(uploadedPath)

        logDiagnostic(
            "uploadAttachment parsed: uploadedPath=$uploadedPath, normalizedUrl=$normalizedUrl"
        )

        return InboxAttachment(
            url = normalizedUrl,
            filename = attachment.originalName.takeIf { it.isNotBlank() },
        )
    }

    private suspend fun post(
        path: String,
        payload: Map<String, Any?>,
    ): String {
        val body = gson.toJson(payload).toRequestBody(JSON_MEDIA_TYPE)
        val request =
            Request.Builder()
                .url(resolveApiUrl(path))
                .addHeader("Authorization", config.apiToken)
                .post(body)
                .build()

        return execute(request)
    }

    private suspend fun get(path: String): String {
        val request =
            Request.Builder()
                .url(resolveApiUrl(path))
                .addHeader("Authorization", config.apiToken)
                .get()
                .build()

        return execute(request)
    }

    private suspend fun execute(request: Request): String {
        return withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            logDiagnostic(
                "http request: method=${request.method}, " +
                        "path=${request.url.encodedPath}, " +
                        "query=${request.url.encodedQuery ?: "-"}",
            )
            client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                val durationMs = System.currentTimeMillis() - startedAt

                logDiagnostic(
                    "http response: method=${request.method}, " +
                            "path=${request.url.encodedPath}, " +
                            "code=${response.code}, " +
                            "durationMs=$durationMs, " +
                            "bodyLength=${rawBody.length}",
                )

                if (!response.isSuccessful) {
                    val diagnosticMessage =
                        buildHttpErrorMessage(
                            statusCode = response.code,
                            rawBody = rawBody,
                        )
                    throw Chat2DeskCommandApiException(
                        message = diagnosticMessage,
                        statusCode = response.code,
                        errorBody = rawBody,
                    )
                }

                rawBody
            }
        }
    }

    private fun resolveApiUrl(path: String): String {
        return resolveUrl(apiBaseUrl, path)
    }

    private fun resolveUploadUrl(path: String): String {
        return resolveUrl(resolveUploadBaseUrl(), path)
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

    private fun resolveUploadBaseUrl(): String {
        return config.sdkStartBaseUrl?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: uploadBaseUrl
    }

    private fun resolveUploadToken(): String {
        return config.sdkWidgetToken?.takeIf { it.isNotBlank() } ?: config.apiToken
    }

    private fun extractDataObject(rawResponse: String): JsonObject? {
        val root = gson.fromJson(rawResponse, JsonObject::class.java)
        val data = root.get("data") ?: return null
        return data.takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun extractUploadContentPath(rawResponse: String): String {
        val root = gson.fromJson(rawResponse, JsonObject::class.java)
        val firstEntry = checkNotNull(root.entrySet().firstOrNull()) { "upload_attach response is empty" }
        check(!firstEntry.value.isJsonNull && firstEntry.value.isJsonPrimitive) {
            "upload_attach response has invalid format"
        }

        var contentPath = firstEntry.value.asString
        val decodedContentPath = runCatching { gson.fromJson(contentPath, String::class.java) }.getOrNull()
        if (!decodedContentPath.isNullOrBlank()) {
            contentPath = decodedContentPath
        }
        check(contentPath.isNotBlank()) { "upload_attach response has blank path" }

        return contentPath
    }

    private fun parseMessages(rawResponse: String): List<Message> {
        val root = runCatching { JsonParser.parseString(rawResponse) }.getOrNull() ?: return emptyList()
        val messagesArray =
            when {
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject -> root.asJsonObject.messageArray()
                else -> null
            } ?: return emptyList()

        return messagesArray.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.toSdkMessage()
        }
    }

    private fun parseClients(rawResponse: String): List<PublicClient> {
        val root = runCatching { JsonParser.parseString(rawResponse) }.getOrNull() ?: return emptyList()
        val clientsArray =
            when {
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject -> root.asJsonObject.arrayValue("data", "clients", "items", "result")
                else -> null
            }

        if (clientsArray != null) {
            return clientsArray.mapNotNull { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.toPublicClientOrNull()
            }
        }

        return root.takeIf { it.isJsonObject }?.asJsonObject?.toPublicClientOrNull()?.let(::listOf).orEmpty()
    }

    private fun parseChannels(rawResponse: String): List<PublicChannel> {
        val root = runCatching { JsonParser.parseString(rawResponse) }.getOrNull() ?: return emptyList()
        val channelsArray =
            when {
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject -> root.asJsonObject.arrayValue("data", "channels", "items", "result")
                else -> null
            } ?: return emptyList()

        return channelsArray.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = obj.longValue("id") ?: obj.longValue("channel_id") ?: return@mapNotNull null
            PublicChannel(
                id = id,
                transport = obj.stringValue("transport") ?: obj.firstStringValue("transports") ?: obj.stringValue("type"),
                status = obj.stringValue("status"),
                name = obj.stringValue("name"),
            )
        }
    }

    private fun JsonObject.toPublicClientOrNull(): PublicClient? {
        val nestedData = get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        if (nestedData != null) return nestedData.toPublicClientOrNull()

        val nestedClient = get("client")?.takeIf { it.isJsonObject }?.asJsonObject
        if (nestedClient != null) return nestedClient.toPublicClientOrNull()

        val id = longValue("id") ?: longValue("client_id") ?: return null
        return PublicClient(
            id = id,
            phone = stringValue("phone") ?: stringValue("client_phone"),
            name = stringValue("name"),
            customFields = customFieldsMap(),
        )
    }

    private fun JsonObject.customFieldsMap(): Map<String, String> {
        val value = get("custom_fields")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
        return value.entrySet().mapNotNull { entry ->
            val fieldValue = entry.value.takeUnless { it.isJsonNull }?.safeStringValue() ?: return@mapNotNull null
            entry.key to fieldValue
        }.toMap()
    }

    private fun JsonObject.arrayValue(vararg names: String): JsonArray? {
        return names.firstNotNullOfOrNull { name ->
            val value = get(name) ?: return@firstNotNullOfOrNull null
            when {
                value.isJsonArray -> value.asJsonArray
                value.isJsonObject -> value.asJsonObject.arrayValue(*names)
                else -> null
            }
        }
    }

    private fun JsonObject.messageArray(): JsonArray? {
        val candidates = listOf("data", "messages", "items", "result")
        return candidates.firstNotNullOfOrNull { name ->
            val value = get(name) ?: return@firstNotNullOfOrNull null
            when {
                value.isJsonArray -> value.asJsonArray
                value.isJsonObject -> value.asJsonObject.messageArray()
                else -> null
            }
        }
    }

    private fun JsonObject.toSdkMessage(): Message {
        val realId = longValue("id") ?: longValue("message_id") ?: longValue("real_id") ?: 0L
        val localId =
            stringValue("remote_id")
                ?: stringValue("uuid")
                ?: "public-$realId"

        return Message(
            id = localId,
            realId = realId,
            read = readStatus(),
            status = deliveryStatus(),
            text = stringValue("body") ?: stringValue("text") ?: stringValue("message").orEmpty(),
            type = messageType(),
            date = messageInstant(),
            attachments = attachments(),
        )
    }

    private fun positiveChannelId(channelId: Long?): Long? {
        return channelId?.takeIf { it > 0 }
    }

    private fun JsonObject.firstStringValue(name: String): String? {
        val array = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return array.firstOrNull()?.safeStringValue()
    }

    private fun JsonObject.messageType(): MessageType {
        val rawType =
            stringValue("type")
                ?: stringValue("message_type")
                ?: stringValue("from")
                ?: stringValue("direction")

        return when (rawType?.lowercase()) {
            // Для Chat2Desk SDK UI это "сообщение клиента", то есть наше сообщение в клиентском чате.
            "from_client", "client", "in", "inbox", "incoming" -> MessageType.IN

            // Для Chat2Desk SDK UI это сообщение оператора / в сторону клиента.
            "to_client", "operator", "out", "outbox", "outgoing" -> MessageType.OUT
            "system" -> MessageType.SYSTEM
            "autoreply", "auto" -> MessageType.AUTO

            else ->
                when (intValue("type") ?: intValue("message_type")) {
                    MessageType.IN.type -> MessageType.IN
                    MessageType.OUT.type -> MessageType.OUT
                    MessageType.AUTO.type -> MessageType.AUTO
                    MessageType.SYSTEM.type -> MessageType.SYSTEM
                    else -> MessageType.OUT
                }
        }
    }

    private fun JsonObject.readStatus(): ReadStatus {
        val value = get("read") ?: get("is_read") ?: return ReadStatus.UNREAD
        return when {
            value.isJsonPrimitive && value.asJsonPrimitive.isBoolean && value.asBoolean -> ReadStatus.READ
            value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asInt > 0 -> ReadStatus.READ
            value.isJsonPrimitive && value.asString.equals("true", ignoreCase = true) -> ReadStatus.READ
            value.isJsonPrimitive && value.asString.equals("read", ignoreCase = true) -> ReadStatus.READ
            else -> ReadStatus.UNREAD
        }
    }

    private fun JsonObject.deliveryStatus(): DeliveryStatus {
        val rawStatus = stringValue("status")?.lowercase()
        return when (rawStatus) {
            "sending", "pending" -> DeliveryStatus.SENDING
            "sent" -> DeliveryStatus.SENT
            "delivered", "read", "success" -> DeliveryStatus.DELIVERED
            "failed", "error", "not_delivered", "undelivered" -> DeliveryStatus.NOT_DELIVERED
            else ->
                when (intValue("status")) {
                    DeliveryStatus.SENDING.status -> DeliveryStatus.SENDING
                    DeliveryStatus.SENT.status -> DeliveryStatus.SENT
                    DeliveryStatus.DELIVERED.status -> DeliveryStatus.DELIVERED
                    DeliveryStatus.NOT_DELIVERED.status -> DeliveryStatus.NOT_DELIVERED
                    else -> DeliveryStatus.DELIVERED
                }
        }
    }

    private fun JsonObject.messageInstant(): Instant? {
        val raw =
            get("created_at")
                ?: get("date")
                ?: get("sent_at")
                ?: get("created")
                ?: get("time")
                ?: return null

        return when {
            raw.isJsonPrimitive && raw.asJsonPrimitive.isNumber -> {
                val value = raw.asLong
                if (value > EPOCH_MILLIS_THRESHOLD) {
                    Instant.fromEpochMilliseconds(value)
                } else {
                    Instant.fromEpochSeconds(value)
                }
            }
            raw.isJsonPrimitive -> parseInstant(raw.asString)
            else -> null
        }
    }

    private fun JsonObject.attachments(): List<Attachment>? {
        val result = mutableListOf<Attachment>()

        val singleAttachment = stringValue("attachment") ?: stringValue("attachment_url")
        if (!singleAttachment.isNullOrBlank()) {
            result +=
                Attachment(
                    id = longValue("attachment_id") ?: 0L,
                    fileSize = intValue("attachment_size") ?: intValue("file_size") ?: 0,
                    contentType =
                        stringValue("attachment_content_type")
                            ?: stringValue("content_type")
                            ?: guessContentType(singleAttachment),
                    link = normalizeAttachmentUrl(singleAttachment),
                    originalFileName = stringValue("attachment_filename") ?: stringValue("file_name"),
                    status = DeliveryStatus.DELIVERED,
                )
        }

        val array = get("attachments")?.takeIf { it.isJsonArray }?.asJsonArray
        array?.forEach { element ->
            element.toAttachmentOrNull()?.let(result::add)
        }

        return result.takeIf { it.isNotEmpty() }
    }

    private fun JsonElement.toAttachmentOrNull(): Attachment? {
        val obj = takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val link =
            obj.stringValue("link")
                ?: obj.stringValue("url")
                ?: obj.stringValue("attachment")
                ?: obj.stringValue("path")
                ?: return null

        return Attachment(
            id = obj.longValue("id") ?: obj.longValue("attachment_id") ?: 0L,
            fileSize = obj.intValue("file_size") ?: obj.intValue("size") ?: 0,
            contentType =
                obj.stringValue("content_type")
                    ?: obj.stringValue("contentType")
                    ?: obj.stringValue("mime_type")
                    ?: guessContentType(link),
            link = normalizeAttachmentUrl(link),
            originalFileName =
                obj.stringValue("original_file_name")
                    ?: obj.stringValue("originalFileName")
                    ?: obj.stringValue("filename")
                    ?: obj.stringValue("file_name"),
            status = DeliveryStatus.DELIVERED,
        )
    }

    private fun normalizeAttachmentUrl(rawUrl: String): String {
        if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) return rawUrl
        val storageBase = config.sdkStorageBaseUrl?.trimEnd('/').orEmpty()
        return if (storageBase.isBlank()) rawUrl else "$storageBase/${rawUrl.trimStart('/')}"
    }

    private fun parseInstant(value: String): Instant? {
        val normalized = value.trim().replace(" UTC", "Z")
        return runCatching { Instant.parse(normalized) }.getOrNull()
            ?: value.toLongOrNull()?.let { numeric ->
                if (numeric > EPOCH_MILLIS_THRESHOLD) {
                    Instant.fromEpochMilliseconds(numeric)
                } else {
                    Instant.fromEpochSeconds(numeric)
                }
            }
    }

    private fun guessContentType(url: String): String {
        val lowercase = url.lowercase()
        return when {
            lowercase.endsWith(".jpg") || lowercase.endsWith(".jpeg") -> "image/jpeg"
            lowercase.endsWith(".png") -> "image/png"
            lowercase.endsWith(".gif") -> "image/gif"
            lowercase.endsWith(".webp") -> "image/webp"
            lowercase.endsWith(".pdf") -> "application/pdf"
            else -> "application/octet-stream"
        }
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

    private fun resolveInboxClient(
        clientId: String,
        options: InboxOptions,
    ): InboxClient {
        options.fromClient?.takeIf {
            !it.id.isNullOrBlank() || !it.phone.isNullOrBlank()
        }?.let { return it }

        options.clientPhone?.takeIf { it.isNotBlank() }?.let { phone ->
            return InboxClient(phone = phone)
        }

        return InboxClient(id = clientId)
    }

    private fun toFromClientMap(client: InboxClient): Map<String, String> {
        val payload = linkedMapOf<String, String>()
        val id = client.id?.takeIf { it.isNotBlank() }
        val phone = client.phone?.filter(Char::isDigit)?.takeIf { it.isNotBlank() }
        if (id != null) {
            payload["id"] = id
        } else if (phone != null) {
            payload["phone"] = phone
        }
        return payload
    }

    private fun JsonObject.stringValue(name: String): String? {
        val value = get(name) ?: return null
        return value.safeStringValue()
    }

    private fun JsonObject.longValue(name: String): Long? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return runCatching { value.asLong }.getOrNull()
    }

    private fun JsonObject.intValue(name: String): Int? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return runCatching { value.asInt }.getOrNull()
    }

    private fun JsonElement.safeStringValue(): String? {
        if (isJsonNull) return null
        return runCatching { asString }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val EPOCH_MILLIS_THRESHOLD = 10_000_000_000L

        fun buildHttpErrorMessage(
            statusCode: Int,
            rawBody: String,
        ): String {
            val baseMessage = "Chat2Desk Public API request failed with status $statusCode"
            val trimmedBody = rawBody.trim()
            return if (trimmedBody.isBlank()) {
                baseMessage
            } else {
                "$baseMessage: $trimmedBody"
            }
        }
    }

    private fun logDiagnostic(message: String) {
        config.diagnosticsHandler?.invoke(message)
    }

    private fun maskPhone(value: String?): String {
        if (value.isNullOrBlank()) return "-"
        val digits = value.filter(Char::isDigit)
        return when {
            digits.length <= 4 -> "***"
            else -> "***${digits.takeLast(4)}"
        }
    }

    private fun String.compactForLog(limit: Int = 1200): String {
        return replace("\n", "")
            .replace("\r", "")
            .take(limit)
    }

    private fun Map<String, Any?>.safePayloadSummary(): String {
        val fromClient = this["from_client"] as? Map<*, *>
        val extraData = this["extra_data"] as? Map<*, *>
        val customFields = this["custom_fields"] as? Map<*, *>

        return buildString {
            append("bodyLength=")
            append((this@safePayloadSummary["body"] as? String)?.length ?: 0)

            append(", channel_id=")
            append(this@safePayloadSummary["channel_id"] ?: "-")

            append(", transport=")
            append(this@safePayloadSummary["transport"] ?: "-")

            append(", from_client.id=")
            append(fromClient?.get("id") ?: "-")

            append(", from_client.phone=")
            append(maskPhone(fromClient?.get("phone") as? String))

            append(", hasExtraData=")
            append(extraData != null)

            append(", extra_data.external_id=")
            append(extraData?.get("external_id") ?: "-")

            append(", customFieldsKeys=")
            append(customFields?.keys?.joinToString(prefix = "[", postfix = "]") ?: "[]")

            append(", hasAttachment=")
            append(this@safePayloadSummary.containsKey("attachment"))

            append(", attachment=")
            append(this@safePayloadSummary["attachment"] ?: "-")

            append(", attachment_filename=")
            append(this@safePayloadSummary["attachment_filename"] ?: "-")
        }
    }
}

private fun String.digitsOnly(): String = filter(Char::isDigit)
