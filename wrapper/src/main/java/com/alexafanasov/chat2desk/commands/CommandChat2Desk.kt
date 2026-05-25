package com.alexafanasov.chat2desk.commands

import com.chat2desk.chat2desk_sdk.AttachedFile
import com.chat2desk.chat2desk_sdk.IChat2Desk
import com.chat2desk.chat2desk_sdk.datasource.services.ConnectionState
import com.chat2desk.chat2desk_sdk.domain.entities.Attachment
import com.chat2desk.chat2desk_sdk.domain.entities.Button
import com.chat2desk.chat2desk_sdk.domain.entities.CustomField
import com.chat2desk.chat2desk_sdk.domain.entities.DeliveryStatus
import com.chat2desk.chat2desk_sdk.domain.entities.Message
import com.chat2desk.chat2desk_sdk.domain.entities.MessageType
import com.chat2desk.chat2desk_sdk.domain.entities.Operator
import com.chat2desk.chat2desk_sdk.domain.entities.ReadStatus
import com.chat2desk.chat2desk_sdk.utils.SearchOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.util.UUID

class CommandChat2Desk(
    private val delegate: IChat2Desk,
    private val commands: CommandApi,
    private val config: Chat2DeskCommandsConfig,
) : ICommandChat2Desk, IChat2Desk by delegate {
    private var cachedLegacyClientId: String? = null
    private var cachedPublicClient: PublicClient? = null
    private var cachedPublicChannelId: Long? = null
    private var cachedClientName: String? = null
    private var cachedClientPhone: String? = null
    private var cachedCustomFields: Map<String, String> = emptyMap()
    private var diagnosticsVersionLogged = false
    private var pollingJob: Job? = null
    private val wrapperScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val publicApiMessages = MutableStateFlow<List<Message>>(emptyList())
    private val publicApiConnectionStatus = MutableStateFlow<ConnectionState?>(ConnectionState.CLOSED)
    private val publicApiOperator = MutableStateFlow<Operator?>(null)
    private val publicApiError = MutableStateFlow<Throwable?>(null)
    private val publicApiCustomFields = MutableStateFlow<List<CustomField>>(emptyList())

    override val messages: StateFlow<List<Message>> =
        if (isPublicApiMode) publicApiMessages else delegate.messages

    override val connectionStatus: StateFlow<ConnectionState?> =
        if (isPublicApiMode) publicApiConnectionStatus else delegate.connectionStatus

    override val operator: StateFlow<Operator?> =
        if (isPublicApiMode) publicApiOperator else delegate.operator

    override val error: StateFlow<Throwable?> =
        if (isPublicApiMode) publicApiError else delegate.error

    override val clientPhone: String?
        get() = if (isPublicApiMode) cachedClientPhone ?: cachedLegacyClientId else delegate.clientPhone

    override val customFields: StateFlow<List<CustomField>> =
        if (isPublicApiMode) publicApiCustomFields else delegate.customFields

    private val isPublicApiMode: Boolean
        get() = config.routeSdkSendMessageViaInboxApi

    init {
        logDiagnosticsVersion("init")
    }

    override suspend fun flushAll() {
        if (!isPublicApiMode) {
            delegate.flushAll()
            return
        }

        stopPolling()
        cachedLegacyClientId = null
        cachedPublicClient = null
        cachedPublicChannelId = null
        cachedClientName = null
        cachedClientPhone = null
        cachedCustomFields = emptyMap()
        publicApiMessages.value = emptyList()
        publicApiError.value = null
        publicApiConnectionStatus.value = ConnectionState.CLOSED
    }

    override suspend fun start(): String? {
        logDiagnosticsVersion("start")
        if (!isPublicApiMode) return delegate.start()

        publicApiConnectionStatus.value = ConnectionState.CONNECTED
        return clientPhone
    }

    override suspend fun start(clientId: String?): String? {
        logDiagnosticsVersion("start(clientId)")
        if (!isPublicApiMode) return delegate.start(clientId)

        cachedLegacyClientId = clientId?.takeIf { it.isNotBlank() } ?: cachedLegacyClientId
        publicApiConnectionStatus.value = ConnectionState.CONNECTED
        return clientPhone
    }

    override suspend fun stop() {
        if (!isPublicApiMode) {
            delegate.stop()
            return
        }

        stopPolling()
        publicApiConnectionStatus.value = ConnectionState.CLOSED
    }

    override suspend fun fetchMessages() {
        if (!isPublicApiMode) {
            delegate.fetchMessages()
            return
        }

        logDiagnostic("fetchMessages via Public API requested")
        refreshPublicMessages(offset = 0, replace = true)
    }

    override suspend fun fetchMessages(
        loadMore: Boolean?,
        clear: Boolean?,
    ) {
        if (!isPublicApiMode) {
            delegate.fetchMessages(loadMore, clear)
            return
        }

        val shouldLoadMore = loadMore == true
        val offset = if (shouldLoadMore) publicApiMessages.value.size else 0
        refreshPublicMessages(offset = offset, replace = clear == true || !shouldLoadMore)
    }

    override suspend fun fetchNewMessages() {
        if (!isPublicApiMode) {
            delegate.fetchNewMessages()
            return
        }

        refreshPublicMessages(offset = 0, replace = false)
    }

    override suspend fun sendMessage(msg: String) {
        if (!isPublicApiMode) {
            delegate.sendMessage(msg)
            return
        }

        logDiagnostic(
            "sendMessage routed entry: textLength=${msg.length}, " +
                    "cachedPhone=${maskPhone(cachedClientPhone)}, " +
                    "cachedPublicClientId=${cachedPublicClient?.id ?: "-"}, " +
                    "cachedChannelId=${cachedPublicChannelId ?: "-"}",
        )
        val context = createRoutedContext(text = msg, hasAttachment = false, attachmentFileName = null)
        val externalId = resolveExternalId(context)
        runRoutedSend(context = context, externalId = externalId) {
            val publicClient = resolvePublicClientForSend()
            val channelId = resolvePublicChannelId()
            val transport = resolveRoutedTransport()
            val fromClient = resolveFromClient(context)

            logDiagnostic(
                "routed sendMessage via Public API: " +
                        "clientId=${publicClient.id}, " +
                        "channelId=$channelId, " +
                        "transport=$transport, " +
                        "fromClient=${fromClient.diagnosticType()}, " +
                        "hasExternalId=${!externalId.isNullOrBlank()}",
            )

            commands.sendInboxCommand(
                command = msg,
                clientId = publicClient.id.toString(),
                options =
                    InboxOptions(
                        externalId = externalId,
                        fromClient = fromClient,
                        customFields = cachedCustomFields,
                        channelId = channelId,
                        transport = transport,
                    ),
            )
            logDiagnostic("routed sendMessage via Public API succeeded")
        }
    }

    override suspend fun sendMessage(
        msg: String,
        attachedFile: AttachedFile,
    ) {
        if (!isPublicApiMode) {
            delegate.sendMessage(msg, attachedFile)
            return
        }

        val context =
            createRoutedContext(
                text = msg,
                hasAttachment = true,
                attachmentFileName = attachedFile.originalName.takeIf { it.isNotBlank() },
            )

        val externalId = resolveExternalId(context)

        try {
            val publicClient = resolvePublicClientForSend()
            val channelId = resolvePublicChannelId()
            val transport = resolveRoutedTransport()
            val fromClient = resolveFromClient(context)

            val uploadedAttachment = commands.uploadAttachment(attachedFile)

            logDiagnostic(
                "routed sendMessage(attachment) via Public API: " +
                        "clientId=${publicClient.id}, " +
                        "channelId=$channelId, " +
                        "transport=$transport, " +
                        "fromClient=${fromClient.diagnosticType()}, " +
                        "hasExternalId=${!externalId.isNullOrBlank()}, " +
                        "attachmentUrl=${uploadedAttachment.url}",
            )

            commands.sendInboxCommand(
                command = msg,
                clientId = publicClient.id.toString(),
                options =
                    InboxOptions(
                        externalId = externalId,
                        fromClient = fromClient,
                        attachments = listOf(uploadedAttachment),
                        customFields = cachedCustomFields,
                        channelId = channelId,
                        transport = transport,
                    ),
            )
            logDiagnostic("routed sendMessage(attachment) via Public API succeeded")

            tryDeleteAttachmentFile(attachedFile)

            appendOptimisticOutgoingMessage(
                context = context,
                attachment =
                    Attachment(
                        id = 0L,
                        fileSize = attachedFile.fileSize.toInt(),
                        contentType = attachedFile.mimeType,
                        link = uploadedAttachment.url,
                        originalFileName = uploadedAttachment.filename ?: attachedFile.originalName,
                        status = DeliveryStatus.SENT,
                    ),
            )

            refreshPublicMessages(offset = 0, replace = false)

            notifyRoutedSendResult(
                status = RoutedSendStatus.SENT,
                context = context,
                externalId = externalId,
            )
        } catch (e: Chat2DeskCommandApiException) {
            publicApiError.value = e
            logDiagnostic("routed sendMessage(attachment) via Public API failed: ${e.message.orEmpty()}")
            config.routedSendFailureHandler?.invoke(e)

            notifyRoutedSendResult(
                status = RoutedSendStatus.FAILED,
                context = context,
                externalId = externalId,
                error = e,
            )

            if (config.routedSendFailureMode == RoutedSendFailureMode.THROW) {
                throw e
            }
        } catch (e: Chat2DeskCommandRoutingException) {
            publicApiError.value = e
            logDiagnostic("routed sendMessage(attachment) routing failed: ${e.message.orEmpty()}")
            throw e
        }
    }

    override suspend fun resendMessage(message: Message) {
        if (!isPublicApiMode) {
            delegate.resendMessage(message)
            return
        }

        message.text?.takeIf { it.isNotBlank() }?.let { sendMessage(it) }
    }

    override suspend fun read() {
        if (!isPublicApiMode) {
            delegate.read()
            return
        }

        logDiagnostic("suppressed read in Public API messages mode")
    }

    override suspend fun delivery() {
        if (!isPublicApiMode) delegate.delivery()
    }

    override suspend fun delivery(id: String) {
        if (!isPublicApiMode) delegate.delivery(id)
    }

    override suspend fun sendClientParams(
        name: String,
        phone: String,
        fieldSet: Map<Int, String>,
    ) {
        if (!isPublicApiMode) {
            delegate.sendClientParams(name = name, phone = phone, fieldSet = fieldSet)
            return
        }

        cachedClientName = name.takeIf { it.isNotBlank() } ?: cachedClientName
        cachedClientPhone = phone.takeIf { it.isNotBlank() } ?: cachedClientPhone
        cachedCustomFields = fieldSet.mapKeys { it.key.toString() }

        logDiagnostic(
            "sendClientParams routed entry: nameBlank=${name.isBlank()}, " +
                    "phone=${maskPhone(phone)}, " +
                    "fieldKeys=${fieldSet.keys.sorted()}",
        )

        try {
            resolvePublicClientForSend()
            refreshPublicMessages(offset = 0, replace = true)
            startPolling()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            publicApiError.value = e
            logDiagnostic("Public API client resolve failed: ${e.message.orEmpty()}")
            if (e is Chat2DeskCommandApiException) {
                config.routedSendFailureHandler?.invoke(e)
            }
        }
    }

    override suspend fun sendInboxCommand(
        command: String,
        clientId: String,
        options: InboxOptions,
    ) {
        commands.sendInboxCommand(command = command, clientId = clientId, options = options)
    }

    override suspend fun sendOperatorMessage(request: OperatorMessageRequest): OperatorMessageResult {
        return commands.sendOperatorMessage(request)
    }

    override suspend fun loadMenuCommands(channelId: Long?): List<MenuCommand> {
        return commands.loadMenuCommands(channelId)
    }

    override suspend fun sendButton(
        button: Button,
        clientId: String,
    ) {
        val commandText = button.payload?.takeIf { it.isNotBlank() } ?: button.text
        if (!isPublicApiMode) {
            val context =
                RoutedMessageContext(
                    text = commandText,
                    clientId = clientId,
                    hasAttachment = false,
                    attachmentFileName = null,
                )
            commands.sendInboxCommand(
                command = commandText,
                clientId = clientId,
                options = InboxOptions(externalId = resolveExternalId(context)),
            )
            return
        }

        val context =
            RoutedMessageContext(
                text = commandText,
                clientId = cachedClientPhone ?: clientId,
                hasAttachment = false,
                attachmentFileName = null,
            )
        val externalId = resolveExternalId(context)
        runRoutedSend(context = context, externalId = externalId) {
            val publicClient = resolvePublicClientForSend()
            val channelId = resolvePublicChannelId()
            val transport = resolveRoutedTransport()
            val fromClient = resolveFromClient(context)

            commands.sendInboxCommand(
                command = commandText,
                clientId = publicClient.id.toString(),
                options =
                    InboxOptions(
                        externalId = externalId,
                        fromClient = fromClient,
                        customFields = cachedCustomFields,
                        channelId = channelId,
                        transport = transport,
                    ),
            )
        }
    }

    override fun close() {
        if (!isPublicApiMode) {
            delegate.close()
            return
        }

        stopPolling()
        publicApiConnectionStatus.value = ConnectionState.CLOSED
        wrapperScope.cancel()
    }

    override fun fullTextSearch(query: String): List<Message> {
        if (!isPublicApiMode) return delegate.fullTextSearch(query)
        return publicApiMessages.value.filter { it.text?.contains(query, ignoreCase = true) == true }
    }

    override fun searchByQuery(
        query: String,
        options: SearchOptions?,
    ): List<Message> {
        if (!isPublicApiMode) return delegate.searchByQuery(query, options)
        return fullTextSearch(query)
    }

    private fun startPolling() {
        if (config.messagesPollingIntervalMs <= 0) return
        if (pollingJob?.isActive == true) return

        pollingJob =
            wrapperScope.launch {
                while (isActive) {
                    delay(config.messagesPollingIntervalMs)
                    try {
                        refreshPublicMessages(offset = 0, replace = false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        publicApiError.value = e
                        logDiagnostic("Public API messages polling failed: ${e.message.orEmpty()}")
                    }
                }
            }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun refreshPublicMessages(
        offset: Int,
        replace: Boolean,
    ) {
        val publicClient = cachedPublicClient ?: return
        val fetched =
            commands.loadMessages(
                PublicMessagesRequest(
                    offset = offset,
                    limit = config.messagesPageSize,
                    clientId = publicClient.id,
                    channelId = resolvePublicChannelId(),
                    transport = resolveRoutedTransport(),
                ),
            )

        publicApiMessages.value =
            if (replace) {
                sortMessages(fetched)
            } else {
                mergeMessages(primary = fetched, secondary = publicApiMessages.value)
            }

        logDiagnostic(
            "refreshPublicMessages done: replace=$replace, fetched=${fetched.size}, " +
                    "total=${publicApiMessages.value.size}, " +
                    "ids=${publicApiMessages.value.take(10).joinToString { "${it.id}/${it.realId}" }}",
        )
    }

    private fun mergeMessages(
        primary: List<Message>,
        secondary: List<Message>,
    ): List<Message> {
        return sortMessages((primary + secondary).distinctBy { it.id })
    }

    private fun sortMessages(messages: List<Message>): List<Message> {
        return messages.sortedWith(
            compareByDescending<Message> { it.date?.toEpochMilliseconds() ?: 0L }
                .thenByDescending { it.realId },
        )
    }

    private suspend fun resolvePublicClientForSend(): PublicClient {
        cachedPublicClient?.let {
            logDiagnostic("resolvePublicClientForSend: cached id=${it.id}, phone=${maskPhone(it.phone)}")
            return it
        }
        val phone = cachedClientPhone?.takeIf { it.isNotBlank() }
            ?: throw Chat2DeskCommandRoutingException(
                "Public API mode requires client phone. Call sendClientParams(name, phone, fieldSet) before sending.",
            )

        logDiagnostic("resolvePublicClientForSend: lookup by phone=${maskPhone(phone)}")
        val existing = commands.findClientByPhone(PublicClientLookupRequest(phone = phone))
        if (existing != null) {
            cachedPublicClient = existing
            cachedClientPhone = existing.phone?.takeIf { it.isNotBlank() } ?: phone
            if (existing.customFields.isNotEmpty()) {
                cachedCustomFields = cachedCustomFields + existing.customFields
            }
            logDiagnostic(
                "resolvePublicClientForSend: found id=${existing.id}, " +
                        "phone=${maskPhone(existing.phone ?: phone)}, " +
                        "customFields=${existing.customFields.keys.sorted()}",
            )
            return existing
        }

        if (!config.allowCreatePublicClient) {
            throw Chat2DeskCommandRoutingException(
                "Chat2Desk Public API client was not found by phone=$phone and allowCreatePublicClient=false.",
            )
        }

        logDiagnostic("resolvePublicClientForSend: creating client phone=${maskPhone(phone)}")
        return commands.createClient(
            PublicClientCreateRequest(
                name = cachedClientName.orEmpty(),
                phone = phone,
                customFields = cachedCustomFields,
            ),
        ).also { created ->
            cachedPublicClient = created
            cachedClientPhone = created.phone?.takeIf { it.isNotBlank() } ?: phone
            logDiagnostic("resolvePublicClientForSend: created id=${created.id}, phone=${maskPhone(created.phone ?: phone)}")
        }
    }

    private suspend fun resolvePublicChannelId(): Long {
        config.defaultChannelId?.takeIf { it > 0 }?.let {
            logDiagnostic("resolvePublicChannelId: using configured defaultChannelId=$it")
            return it
        }
        cachedPublicChannelId?.let {
            logDiagnostic("resolvePublicChannelId: using cached channelId=$it")
            return it
        }

        val transport = resolveRoutedTransport()
        logDiagnostic("resolvePublicChannelId: loading channels for transport=$transport")
        val channels = commands.loadChannels()
        logDiagnostic(
            "resolvePublicChannelId: loaded channels=" +
                    channels.joinToString { "${it.id}:${it.transport ?: "-"}:${it.name ?: "-"}" },
        )
        val exactMatches =
            channels.filter { candidate ->
                candidate.transport.equals(transport, ignoreCase = true)
            }
        val compatibleMatches =
            if (exactMatches.isNotEmpty()) {
                exactMatches
            } else {
                channels.filter { candidate ->
                    candidate.transport.isNullOrBlank() || transport.isBlank()
                }
            }

        val channel =
            when (compatibleMatches.size) {
                1 -> compatibleMatches.first()
                0 ->
                    throwChannelResolveException(
                        "Chat2Desk Public API channel_id is missing. Set defaultChannelId or make /v1/channels return a $transport channel.",
                    )
                else ->
                    throwChannelResolveException(
                        "Chat2Desk Public API channel_id is ambiguous for transport=$transport. Set defaultChannelId explicitly.",
                    )
            }

        cachedPublicChannelId = channel.id
        config.sdkActiveChannelHandler?.invoke(channel.id)
        logDiagnostic("resolvePublicChannelId: resolved channelId=${channel.id}")
        return channel.id
    }

    private fun throwChannelResolveException(message: String): Nothing {
        val error = Chat2DeskCommandRoutingException(message)
        publicApiError.value = error
        config.sdkActiveChannelFailureHandler?.invoke(message)
        throw error
    }

    private fun createRoutedContext(
        text: String,
        hasAttachment: Boolean,
        attachmentFileName: String?,
    ): RoutedMessageContext {
        val clientId =
            cachedClientPhone
                ?: cachedPublicClient?.id?.toString()
                ?: cachedLegacyClientId
                ?: throw Chat2DeskCommandRoutingException(
                    "Public API mode requires client identity. Call sendClientParams(name, phone, fieldSet) before sending.",
                )
        return RoutedMessageContext(
            text = text,
            clientId = clientId,
            hasAttachment = hasAttachment,
            attachmentFileName = attachmentFileName,
        )
    }

    private fun resolveExternalId(context: RoutedMessageContext): String? {
        val externalId = config.externalIdResolver?.invoke(context)?.takeIf { it.isNotBlank() }
        logDiagnostic("resolveExternalId: value=${externalId ?: "-"}")
        return externalId
    }

    private fun resolveFromClient(context: RoutedMessageContext): InboxClient {
        val fromClient = config.fromClientResolver?.invoke(context)?.takeIf {
            !it.id.isNullOrBlank() || !it.phone.isNullOrBlank()
        } ?: InboxClient(phone = cachedClientPhone)

        logDiagnostic(
            "resolveFromClient: id=${fromClient.id ?: "-"}, phone=${maskPhone(fromClient.phone)}",
        )
        return fromClient
    }

    private fun resolveRoutedTransport(): String {
        return config.defaultTransport?.takeIf { it.isNotBlank() } ?: DEFAULT_ROUTED_TRANSPORT
    }

    private suspend fun runRoutedSend(
        context: RoutedMessageContext,
        externalId: String?,
        send: suspend () -> Unit,
    ) {
        try {
            send()

            appendOptimisticOutgoingMessage(
                context = context,
            )

            refreshPublicMessages(offset = 0, replace = false)

            notifyRoutedSendResult(
                status = RoutedSendStatus.SENT,
                context = context,
                externalId = externalId,
            )
        } catch (e: Chat2DeskCommandApiException) {
            publicApiError.value = e
            logDiagnostic("routed send via Public API failed: ${e.message.orEmpty()}")
            config.routedSendFailureHandler?.invoke(e)
            notifyRoutedSendResult(
                status = RoutedSendStatus.FAILED,
                context = context,
                externalId = externalId,
                error = e,
            )
            if (config.routedSendFailureMode == RoutedSendFailureMode.THROW) {
                throw e
            }
        } catch (e: Chat2DeskCommandRoutingException) {
            publicApiError.value = e
            logDiagnostic("routed send routing failed: ${e.message.orEmpty()}")
            throw e
        }
    }

    private fun notifyRoutedSendResult(
        status: RoutedSendStatus,
        context: RoutedMessageContext,
        externalId: String?,
        error: Chat2DeskCommandApiException? = null,
    ) {
        config.routedSendResultHandler?.invoke(
            RoutedSendResult(
                status = status,
                text = context.text,
                externalId = externalId,
                hasAttachment = context.hasAttachment,
                attachmentFileName = context.attachmentFileName,
                error = error,
            ),
        )
    }

    private fun appendOptimisticOutgoingMessage(
        context: RoutedMessageContext,
        attachment: Attachment? = null,
    ) {
        val now = Clock.System.now()
        val localId = "local-${now.toEpochMilliseconds()}-${UUID.randomUUID()}"

        val optimisticMessage =
            Message(
                id = localId,
                realId = now.toEpochMilliseconds(),
                read = ReadStatus.READ,
                status = DeliveryStatus.SENT,
                text = context.text,
                type = MessageType.IN,
                date = now,
                attachments = attachment?.let(::listOf),
            )

        publicApiMessages.value =
            mergeMessages(
                primary = listOf(optimisticMessage),
                secondary = publicApiMessages.value,
            )
        logDiagnostic("appendOptimisticOutgoingMessage: localId=$localId, realId=${optimisticMessage.realId}")
    }

    private fun tryDeleteAttachmentFile(attachedFile: AttachedFile) {
        if (!config.deleteUploadedAttachmentOnSuccess) return

        val filePath = attachedFile.getFilePath().trim()
        if (filePath.isBlank()) return

        val rawPath = runCatching { Paths.get(filePath).toAbsolutePath().normalize() }.getOrNull() ?: return
        if (!Files.isRegularFile(rawPath, LinkOption.NOFOLLOW_LINKS)) return

        val canonicalTargetPath =
            runCatching { rawPath.toFile().canonicalFile.toPath().normalize() }.getOrNull() ?: return
        val safeRoots =
            config.safeDeleteRoots
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { root ->
                    runCatching { File(root).canonicalFile.toPath().normalize() }.getOrNull()
                }
                .toList()
        if (safeRoots.none { root -> canonicalTargetPath.startsWith(root) }) return

        runCatching {
            Files.deleteIfExists(canonicalTargetPath)
        }
    }

    private fun logDiagnosticsVersion(source: String) {
        if (diagnosticsVersionLogged && source != "init") return
        diagnosticsVersionLogged = true
        logDiagnostic("chat2desk-commands-wrapper version=${WrapperDiagnostics.VERSION}, source=$source")
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

    private companion object {
        const val DEFAULT_ROUTED_TRANSPORT = "external"
    }
}

private fun InboxClient.diagnosticType(): String {
    return when {
        !id.isNullOrBlank() -> "id"
        !phone.isNullOrBlank() -> "phone"
        else -> "empty"
    }
}
