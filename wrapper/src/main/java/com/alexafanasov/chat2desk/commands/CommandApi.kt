package com.alexafanasov.chat2desk.commands

import com.chat2desk.chat2desk_sdk.IAttachment

interface CommandApi {
    suspend fun sendInboxCommand(
        command: String,
        clientId: String,
        options: InboxOptions = InboxOptions(),
    )

    suspend fun sendOperatorMessage(request: OperatorMessageRequest): OperatorMessageResult

    suspend fun loadMenuCommands(channelId: Long? = null): List<MenuCommand>

    suspend fun uploadAttachment(attachment: IAttachment): InboxAttachment
}
