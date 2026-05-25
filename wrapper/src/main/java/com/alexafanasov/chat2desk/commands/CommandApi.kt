package com.alexafanasov.chat2desk.commands

import com.chat2desk.chat2desk_sdk.IAttachment
import com.chat2desk.chat2desk_sdk.domain.entities.Message

interface CommandApi {
    suspend fun sendInboxCommand(
        command: String,
        clientId: String,
        options: InboxOptions = InboxOptions(),
    )

    suspend fun sendOperatorMessage(request: OperatorMessageRequest): OperatorMessageResult

    suspend fun loadMenuCommands(channelId: Long? = null): List<MenuCommand>

    suspend fun uploadAttachment(attachment: IAttachment): InboxAttachment

    suspend fun loadMessages(request: PublicMessagesRequest): List<Message>

    suspend fun findClientByPhone(request: PublicClientLookupRequest): PublicClient?

    suspend fun createClient(request: PublicClientCreateRequest): PublicClient

    suspend fun loadChannels(): List<PublicChannel>
}
