package com.alexafanasov.chat2desk.commands

interface CommandApi {
    suspend fun sendInboxCommand(
        command: String,
        clientId: String,
        options: InboxOptions = InboxOptions(),
    )

    suspend fun sendOperatorMessage(request: OperatorMessageRequest): OperatorMessageResult

    suspend fun loadMenuCommands(channelId: Long? = null): List<MenuCommand>
}
