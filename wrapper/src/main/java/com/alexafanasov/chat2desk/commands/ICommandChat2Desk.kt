package com.alexafanasov.chat2desk.commands

import com.chat2desk.chat2desk_sdk.IChat2Desk
import com.chat2desk.chat2desk_sdk.domain.entities.Button

interface ICommandChat2Desk : IChat2Desk {
    suspend fun sendInboxCommand(
        command: String,
        clientId: String,
        options: InboxOptions = InboxOptions(),
    )

    suspend fun sendOperatorMessage(request: OperatorMessageRequest): OperatorMessageResult

    suspend fun loadMenuCommands(channelId: Long? = null): List<MenuCommand>

    suspend fun sendButton(
        button: Button,
        clientId: String,
    )
}
