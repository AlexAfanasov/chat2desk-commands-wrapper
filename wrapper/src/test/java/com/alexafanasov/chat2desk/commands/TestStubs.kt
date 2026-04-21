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
}
