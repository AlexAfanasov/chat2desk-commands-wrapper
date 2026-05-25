package com.alexafanasov.chat2desk.commands

data class Chat2DeskCommandsConfig(
    val baseUrl: String,
    /**
     * Chat2Desk Public API token for /v1 endpoints.
     *
     * This is not the SDK/widget token used by com.chat2desk.chat2desk_sdk.Settings.authToken.
     * Passing the SDK/widget token to Public API endpoints usually returns 401 or 403.
     */
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
    val allowCreatePublicClient: Boolean = false,
    val routedSendFailureMode: RoutedSendFailureMode = RoutedSendFailureMode.SWALLOW,
    val routedSendFailureHandler: ((Chat2DeskCommandApiException) -> Unit)? = null,
    val externalIdResolver: ((RoutedMessageContext) -> String?)? = null,
    val fromClientResolver: ((RoutedMessageContext) -> InboxClient?)? = null,
    val sdkStartBaseUrl: String? = null,
    val sdkWidgetToken: String? = null,
    val sdkStorageBaseUrl: String? = DEFAULT_SDK_STORAGE_BASE_URL,
    val messagesPageSize: Int = DEFAULT_MESSAGES_PAGE_SIZE,
    val messagesPollingIntervalMs: Long = DEFAULT_MESSAGES_POLLING_INTERVAL_MS,
    val sdkActiveChannelHandler: ((Long) -> Unit)? = null,
    val sdkActiveChannelFailureHandler: ((String) -> Unit)? = null,
    val diagnosticsHandler: ((String) -> Unit)? = null,
    val routedSendResultHandler: ((RoutedSendResult) -> Unit)? = null,
) {
    companion object {
        fun publicApi(
            baseUrl: String,
            publicApiToken: String,
            uploadBaseUrl: String = baseUrl,
            connectTimeoutMs: Long = 30_000,
            readTimeoutMs: Long = 30_000,
            writeTimeoutMs: Long = 30_000,
            defaultChannelId: Long? = null,
            defaultTransport: String? = null,
            maxUploadBytes: Long = DEFAULT_MAX_UPLOAD_BYTES,
            deleteUploadedAttachmentOnSuccess: Boolean = false,
            safeDeleteRoots: Set<String> = defaultSafeDeleteRoots(),
            requireHttps: Boolean = true,
            trustedHostSuffixes: Set<String> = setOf("chat2desk.com"),
            routeSdkSendMessageViaInboxApi: Boolean = false,
            allowCreatePublicClient: Boolean = false,
            routedSendFailureMode: RoutedSendFailureMode = RoutedSendFailureMode.SWALLOW,
            routedSendFailureHandler: ((Chat2DeskCommandApiException) -> Unit)? = null,
            externalIdResolver: ((RoutedMessageContext) -> String?)? = null,
            fromClientResolver: ((RoutedMessageContext) -> InboxClient?)? = null,
            sdkStartBaseUrl: String? = null,
            sdkWidgetToken: String? = null,
            sdkStorageBaseUrl: String? = DEFAULT_SDK_STORAGE_BASE_URL,
            messagesPageSize: Int = DEFAULT_MESSAGES_PAGE_SIZE,
            messagesPollingIntervalMs: Long = DEFAULT_MESSAGES_POLLING_INTERVAL_MS,
            sdkActiveChannelHandler: ((Long) -> Unit)? = null,
            sdkActiveChannelFailureHandler: ((String) -> Unit)? = null,
            diagnosticsHandler: ((String) -> Unit)? = null,
            routedSendResultHandler: ((RoutedSendResult) -> Unit)? = null,
        ): Chat2DeskCommandsConfig =
            Chat2DeskCommandsConfig(
                baseUrl = baseUrl,
                apiToken = publicApiToken,
                uploadBaseUrl = uploadBaseUrl,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                writeTimeoutMs = writeTimeoutMs,
                defaultChannelId = defaultChannelId,
                defaultTransport = defaultTransport,
                maxUploadBytes = maxUploadBytes,
                deleteUploadedAttachmentOnSuccess = deleteUploadedAttachmentOnSuccess,
                safeDeleteRoots = safeDeleteRoots,
                requireHttps = requireHttps,
                trustedHostSuffixes = trustedHostSuffixes,
                routeSdkSendMessageViaInboxApi = routeSdkSendMessageViaInboxApi,
                allowCreatePublicClient = allowCreatePublicClient,
                routedSendFailureMode = routedSendFailureMode,
                routedSendFailureHandler = routedSendFailureHandler,
                externalIdResolver = externalIdResolver,
                fromClientResolver = fromClientResolver,
                sdkStartBaseUrl = sdkStartBaseUrl,
                sdkWidgetToken = sdkWidgetToken,
                sdkStorageBaseUrl = sdkStorageBaseUrl,
                messagesPageSize = messagesPageSize,
                messagesPollingIntervalMs = messagesPollingIntervalMs,
                sdkActiveChannelHandler = sdkActiveChannelHandler,
                sdkActiveChannelFailureHandler = sdkActiveChannelFailureHandler,
                diagnosticsHandler = diagnosticsHandler,
                routedSendResultHandler = routedSendResultHandler,
            )
    }
}

enum class RoutedSendFailureMode {
    SWALLOW,
    THROW,
}

private fun defaultSafeDeleteRoots(): Set<String> {
    val tempDir = System.getProperty("java.io.tmpdir")?.trim().orEmpty()
    return if (tempDir.isBlank()) emptySet() else setOf(tempDir)
}

private const val ONE_MEBIBYTE_IN_BYTES = 1024L * 1024L
private const val DEFAULT_MAX_UPLOAD_SIZE_MEBIBYTES = 20L
private const val DEFAULT_MAX_UPLOAD_BYTES = DEFAULT_MAX_UPLOAD_SIZE_MEBIBYTES * ONE_MEBIBYTE_IN_BYTES
const val DEFAULT_MESSAGES_PAGE_SIZE = 50
const val DEFAULT_MESSAGES_POLLING_INTERVAL_MS = 5_000L
const val DEFAULT_SDK_STORAGE_BASE_URL = "https://storage.chat2desk.com/"

data class PublicMessagesRequest(
    val offset: Int = 0,
    val limit: Int = DEFAULT_MESSAGES_PAGE_SIZE,
    val clientId: Long? = null,
    val channelId: Long? = null,
    val transport: String? = null,
)

data class PublicClientLookupRequest(
    val phone: String,
)

data class PublicClientCreateRequest(
    val name: String,
    val phone: String,
    val customFields: Map<String, String> = emptyMap(),
)

data class PublicClient(
    val id: Long,
    val phone: String? = null,
    val name: String? = null,
    val customFields: Map<String, String> = emptyMap(),
)

data class PublicChannel(
    val id: Long,
    val transport: String? = null,
    val status: String? = null,
    val name: String? = null,
)

data class RoutedMessageContext(
    val text: String,
    /**
     * SDK client_key exposed by the upstream SDK as IChat2Desk.clientPhone.
     */
    val clientId: String,
    val hasAttachment: Boolean,
    val attachmentFileName: String?,
)

enum class RoutedSendStatus {
    SENT,
    FAILED,
}

data class RoutedSendResult(
    val status: RoutedSendStatus,
    val text: String,
    val externalId: String?,
    val hasAttachment: Boolean,
    val attachmentFileName: String?,
    val error: Chat2DeskCommandApiException? = null,
)

data class InboxAttachment(
    val url: String,
    val filename: String? = null,
)

data class InboxClient(
    val id: String? = null,
    val phone: String? = null,
)

data class InboxOptions(
    val customFields: Map<String, String> = emptyMap(),
    val clientPhone: String? = null,
    val fromClient: InboxClient? = null,
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
