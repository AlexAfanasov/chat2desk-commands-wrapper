package com.alexafanasov.chat2desk.commands

import com.chat2desk.chat2desk_sdk.AttachedFile
import com.chat2desk.chat2desk_sdk.IAttachment
import com.chat2desk.chat2desk_sdk.IChat2Desk
import com.chat2desk.chat2desk_sdk.datasource.services.ConnectionState
import com.chat2desk.chat2desk_sdk.domain.entities.CustomField
import com.chat2desk.chat2desk_sdk.domain.entities.Message
import com.chat2desk.chat2desk_sdk.domain.entities.Operator
import com.chat2desk.chat2desk_sdk.utils.SearchOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

open class NoOpChat2Desk : IChat2Desk {
    override val messages: StateFlow<List<Message>> = MutableStateFlow(emptyList())
    override val connectionStatus: StateFlow<ConnectionState?> = MutableStateFlow(null)
    override val operator: StateFlow<Operator?> = MutableStateFlow(null)
    override val error: StateFlow<Throwable?> = MutableStateFlow(null)
    override var clientPhone: String? = null
    override val customFields: StateFlow<List<CustomField>> = MutableStateFlow(emptyList())

    override suspend fun flushAll() = Unit

    override suspend fun start(): String? = null

    override suspend fun start(clientId: String?): String? = clientId

    override suspend fun stop() = Unit

    override suspend fun fetchMessages() = Unit

    override suspend fun fetchMessages(
        loadMore: Boolean?,
        clear: Boolean?,
    ) = Unit

    override suspend fun fetchNewMessages() = Unit

    override suspend fun sendMessage(msg: String) = Unit

    override suspend fun sendMessage(
        msg: String,
        attachedFile: AttachedFile,
    ) = Unit

    override suspend fun resendMessage(message: Message) = Unit

    override suspend fun read() = Unit

    override suspend fun delivery() = Unit

    override suspend fun delivery(id: String) = Unit

    override suspend fun sendClientParams(
        name: String,
        phone: String,
        fieldSet: Map<Int, String>,
    ) = Unit

    override fun close() = Unit

    override fun fullTextSearch(query: String): List<Message> = emptyList()

    override fun searchByQuery(
        query: String,
        options: SearchOptions?,
    ): List<Message> = emptyList()
}

class RecordingDelegate : NoOpChat2Desk() {
    var noArgStartCalls = 0
    var startWithClientCalls = mutableListOf<String?>()
    var sentMessages = mutableListOf<String>()
    var sentMessagesWithAttachment = mutableListOf<Pair<String, AttachedFile>>()
    var readCalls = 0
    var deliveryCalls = 0
    var deliveryIdCalls = mutableListOf<String>()
    var sendClientParamsCalls = mutableListOf<Triple<String, String, Map<Int, String>>>()

    override suspend fun start(): String? {
        noArgStartCalls += 1
        return "client-from-noarg"
    }

    override suspend fun start(clientId: String?): String? {
        startWithClientCalls += clientId
        return clientId
    }

    override suspend fun sendMessage(msg: String) {
        sentMessages += msg
    }

    override suspend fun sendMessage(
        msg: String,
        attachedFile: AttachedFile,
    ) {
        sentMessagesWithAttachment += msg to attachedFile
    }

    override suspend fun read() {
        readCalls += 1
    }

    override suspend fun delivery() {
        deliveryCalls += 1
    }

    override suspend fun delivery(id: String) {
        deliveryIdCalls += id
    }

    override suspend fun sendClientParams(
        name: String,
        phone: String,
        fieldSet: Map<Int, String>,
    ) {
        sendClientParamsCalls += Triple(name, phone, fieldSet)
    }
}

class RecordingCommandApi : CommandApi {
    data class RecordedInboxCommand(
        val command: String,
        val clientId: String,
        val options: InboxOptions,
    )

    val inboxCommands = mutableListOf<RecordedInboxCommand>()
    val operatorMessages = mutableListOf<OperatorMessageRequest>()
    val uploadedAttachments = mutableListOf<IAttachment>()
    val loadedMessages = mutableListOf<PublicMessagesRequest>()
    val lookedUpClients = mutableListOf<PublicClientLookupRequest>()
    val createdClients = mutableListOf<PublicClientCreateRequest>()
    var loadedChannelsCalls = 0
    var loadMessagesResult: List<Message> = emptyList()
    var findClientResult: PublicClient? = PublicClient(id = 146339237, phone = "79991112233")
    var createClientResult: PublicClient = PublicClient(id = 146339238, phone = "79991112233")
    var loadChannelsResult: List<PublicChannel> = listOf(PublicChannel(id = 121177, transport = "external"))
    var sendInboxCommandError: Chat2DeskCommandApiException? = null
    var uploadAttachmentResult: InboxAttachment =
        InboxAttachment(
            url = "https://cdn.chat2desk.local/attachment.jpg",
            filename = "attachment.jpg",
        )

    override suspend fun sendInboxCommand(
        command: String,
        clientId: String,
        options: InboxOptions,
    ) {
        sendInboxCommandError?.let { throw it }
        inboxCommands += RecordedInboxCommand(command = command, clientId = clientId, options = options)
    }

    override suspend fun sendOperatorMessage(request: OperatorMessageRequest): OperatorMessageResult {
        operatorMessages += request
        return OperatorMessageResult(messageId = 1L)
    }

    override suspend fun loadMenuCommands(channelId: Long?): List<MenuCommand> {
        return listOf(MenuCommand(id = 1, text = "Root", command = "/start"))
    }

    override suspend fun uploadAttachment(attachment: IAttachment): InboxAttachment {
        uploadedAttachments += attachment
        return uploadAttachmentResult
    }

    override suspend fun loadMessages(request: PublicMessagesRequest): List<Message> {
        loadedMessages += request
        return loadMessagesResult
    }

    override suspend fun findClientByPhone(request: PublicClientLookupRequest): PublicClient? {
        lookedUpClients += request
        return findClientResult
    }

    override suspend fun createClient(request: PublicClientCreateRequest): PublicClient {
        createdClients += request
        return createClientResult
    }

    override suspend fun loadChannels(): List<PublicChannel> {
        loadedChannelsCalls += 1
        return loadChannelsResult
    }
}
