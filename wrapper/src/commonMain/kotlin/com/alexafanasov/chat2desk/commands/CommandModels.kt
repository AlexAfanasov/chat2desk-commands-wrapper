package com.alexafanasov.chat2desk.commands

data class Chat2DeskCommandsConfig(
    val baseUrl: String,
    val publicApiToken: String,
    val sdkStartBaseUrl: String = DEFAULT_SDK_START_BASE_URL,
    val sdkWidgetToken: String? = null,
    val clientExternalIdResolver: ((ClientExternalIdContext) -> String?)? = null,
    val enrichClientOnSendClientParams: Boolean = true,
    val enrichmentFailureMode: EnrichmentFailureMode = EnrichmentFailureMode.SWALLOW,
    val enrichmentFailureHandler: ((Throwable) -> Unit)? = null,
    val diagnosticsEnabled: Boolean = false,
    val diagnosticsHandler: ((String) -> Unit)? = null,
    val connectTimeoutMs: Long = 30_000,
    val readTimeoutMs: Long = 30_000,
    val writeTimeoutMs: Long = 30_000,
    val requireHttps: Boolean = true,
    val trustedHostSuffixes: Set<String> = setOf("chat2desk.com"),
)

private const val DEFAULT_SDK_START_BASE_URL = "https://livechatv2.chat2desk.com"

data class ClientExternalIdContext(
    val name: String,
    val phone: String,
    val fieldSet: Map<Int, String>,
    val sdkClientKey: String? = null,
)

enum class EnrichmentFailureMode {
    SWALLOW,
    THROW,
}

data class PublicClient(
    val id: Long,
    val phone: String? = null,
    val name: String? = null,
    val customFields: Map<String, String> = emptyMap(),
)

class Chat2DeskCommandApiException(
    message: String,
    val statusCode: Int,
    val errorBody: String?,
) : RuntimeException(message)
