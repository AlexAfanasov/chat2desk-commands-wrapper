package com.alexafanasov.chat2desk.commands

data class Chat2DeskCommandsConfig(
    val baseUrl: String,
    val apiToken: String,
    val uploadBaseUrl: String = baseUrl,
    val connectTimeoutMs: Long = 30_000,
    val readTimeoutMs: Long = 30_000,
    val writeTimeoutMs: Long = 30_000,
    val defaultChannelId: Long? = null,
    val defaultTransport: String? = null,
    val maxUploadBytes: Long = DEFAULT_MAX_UPLOAD_BYTES,
    val deleteUploadedAttachmentOnSuccess: Boolean = false,
    val safeDeleteRoots: Set<String> = defaultSafeDeleteRoots(),
    val requireHttps: Boolean = true,
    val trustedHostSuffixes: Set<String> = setOf("chat2desk.com"),
    val routeSdkSendMessageViaInboxApi: Boolean = false,
    val externalIdResolver: ((RoutedMessageContext) -> String?)? = null,
)

private fun defaultSafeDeleteRoots(): Set<String> {
    val tempDir = System.getProperty("java.io.tmpdir")?.trim().orEmpty()
    return if (tempDir.isBlank()) emptySet() else setOf(tempDir)
}

private const val ONE_MEBIBYTE_IN_BYTES = 1024L * 1024L
private const val DEFAULT_MAX_UPLOAD_SIZE_MEBIBYTES = 20L
private const val DEFAULT_MAX_UPLOAD_BYTES = DEFAULT_MAX_UPLOAD_SIZE_MEBIBYTES * ONE_MEBIBYTE_IN_BYTES

data class RoutedMessageContext(
    val text: String,
    val clientId: String,
    val hasAttachment: Boolean,
    val attachmentFileName: String?,
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

class Chat2DeskCommandRoutingException(
    message: String,
) : RuntimeException(message)
