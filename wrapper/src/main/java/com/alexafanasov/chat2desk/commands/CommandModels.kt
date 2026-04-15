package com.alexafanasov.chat2desk.commands

data class Chat2DeskCommandsConfig(
    val baseUrl: String,
    val apiToken: String,
    val connectTimeoutMs: Long = 30_000,
    val readTimeoutMs: Long = 30_000,
    val writeTimeoutMs: Long = 30_000,
    val defaultChannelId: Long? = null,
    val defaultTransport: String? = null,
)

data class InboxAttachment(
    val url: String,
    val filename: String? = null,
)

data class InboxOptions(
    val customFields: Map<String, String> = emptyMap(),
    val clientPhone: String? = null,
    val externalId: String? = null,
    val attachments: List<InboxAttachment> = emptyList(),
    val channelId: Long? = null,
    val transport: String? = null,
)

data class CommandButton(
    val type: String,
    val text: String,
    val payload: String? = null,
    val color: String? = null,
    val url: String? = null,
)

data class Keyboard(
    val buttons: List<CommandButton>,
)

data class OperatorMessageRequest(
    val clientId: String,
    val text: String? = null,
    val type: String = "to_client",
    val attachment: String? = null,
    val attachmentFilename: String? = null,
    val channelId: Long? = null,
    val operatorId: Long? = null,
    val transport: String? = null,
    val openDialog: Boolean? = null,
    val externalId: String? = null,
    val encrypted: Boolean? = null,
    val replyMessageId: Long? = null,
    val inlineButtons: List<CommandButton> = emptyList(),
    val keyboard: Keyboard? = null,
    val interactive: Map<String, Any?>? = null,
)

data class OperatorMessageResult(
    val messageId: Long? = null,
    val channelId: Long? = null,
    val operatorId: Long? = null,
    val transport: String? = null,
    val type: String? = null,
    val clientId: String? = null,
    val dialogId: Long? = null,
    val requestId: Long? = null,
)

data class MenuCommand(
    val id: Long,
    val text: String,
    val command: String? = null,
    val parentId: Long? = null,
    val position: Int? = null,
)

class Chat2DeskCommandApiException(
    message: String,
    val statusCode: Int,
    val errorBody: String?,
) : RuntimeException(message)
