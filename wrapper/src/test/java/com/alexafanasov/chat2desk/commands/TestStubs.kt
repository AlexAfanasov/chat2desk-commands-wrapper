package com.alexafanasov.chat2desk.commands

import com.chat2desk.chat2desk_sdk.AttachedFile
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
    var sentClientParams = mutableListOf<ClientExternalIdContext>()
    var eventLog = mutableListOf<String>()

    override suspend fun start(): String {
        noArgStartCalls += 1
        return "client-from-noarg"
    }

    override suspend fun start(clientId: String?): String? {
        startWithClientCalls += clientId
        return clientId
    }

    override suspend fun sendMessage(msg: String) {
        sentMessages += msg
        eventLog += "delegate.sendMessage"
    }

    override suspend fun sendMessage(
        msg: String,
        attachedFile: AttachedFile,
    ) {
        sentMessagesWithAttachment += msg to attachedFile
        eventLog += "delegate.sendMessageWithAttachment"
    }

    override suspend fun sendClientParams(
        name: String,
        phone: String,
        fieldSet: Map<Int, String>,
    ) {
        sentClientParams += ClientExternalIdContext(name = name, phone = phone, fieldSet = fieldSet)
        eventLog += "delegate.sendClientParams"
    }
}

class RecordingClientEnrichmentApi : Chat2DeskClientEnrichmentApi {
    val resolveStartedClientCalls = mutableListOf<String>()
    val updateClientExternalIdCalls = mutableListOf<Pair<Long, String>>()
    val eventLog = mutableListOf<String>()
    var startedClient: PublicClient? = null
    var updatedClient: PublicClient? = null
    var updateClientExternalIdError: Throwable? = null

    override suspend fun resolveStartedClient(clientKey: String): PublicClient? {
        resolveStartedClientCalls += clientKey
        eventLog += "api.resolveStartedClient"
        return startedClient
    }

    override suspend fun updateClientExternalId(
        clientId: Long,
        externalId: String,
    ): PublicClient? {
        eventLog += "api.updateClientExternalId"
        updateClientExternalIdError?.let { throw it }
        updateClientExternalIdCalls += clientId to externalId
        return updatedClient
    }
}
