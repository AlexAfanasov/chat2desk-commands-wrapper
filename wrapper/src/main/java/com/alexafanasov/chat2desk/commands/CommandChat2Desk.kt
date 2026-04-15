package com.alexafanasov.chat2desk.commands

import com.chat2desk.chat2desk_sdk.IChat2Desk
import com.chat2desk.chat2desk_sdk.domain.entities.Button

class CommandChat2Desk(
    private val delegate: IChat2Desk,
    private val commands: CommandApi,
) : ICommandChat2Desk, IChat2Desk by delegate {
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
        sendInboxCommand(command = commandText, clientId = clientId)
    }
}
