package com.alexafanasov.chat2desk.commands

import com.chat2desk.chat2desk_sdk.IChat2Desk

class CommandChat2Desk(
    private val delegate: IChat2Desk,
    private val clientEnrichmentApi: Chat2DeskClientEnrichmentApi,
    private val config: Chat2DeskCommandsConfig,
) : ICommandChat2Desk, IChat2Desk by delegate {
    private val clientEnricher =
        Chat2DeskClientEnricher(clientEnrichmentApi = clientEnrichmentApi, config = config)
    private var sdkClientKey: String? = null

    override suspend fun start(): String? {
        config.logDiagnostic(
            "sdk start request: delegateClientPhonePresent=${!delegate.clientPhone.isNullOrBlank()}",
        )
        val clientKey = delegate.start()
        config.logDiagnostic(
            "sdk start response: ${clientKey.presenceAndLength("clientKey")}, " +
                "delegateClientPhonePresent=${!delegate.clientPhone.isNullOrBlank()}",
        )
        sdkClientKey = clientKey
        return clientKey
    }

    override suspend fun start(clientId: String?): String? {
        config.logDiagnostic(
            "sdk start(clientId) request: ${clientId.presenceAndLength("requestedClientId")}, " +
                "delegateClientPhonePresent=${!delegate.clientPhone.isNullOrBlank()}",
        )
        val clientKey = delegate.start(clientId)
        config.logDiagnostic(
            "sdk start(clientId) response: ${clientKey.presenceAndLength("resultClientKey")}, " +
                "delegateClientPhonePresent=${!delegate.clientPhone.isNullOrBlank()}",
        )
        sdkClientKey = clientKey ?: clientId
        return clientKey
    }

    override suspend fun sendClientParams(
        name: String,
        phone: String,
        fieldSet: Map<Int, String>,
    ) {
        config.logDiagnostic(
            "sdk sendClientParams request: namePresent=${name.isNotBlank()}, phone=${phone.maskedPhone()}, " +
                "fieldSetSize=${fieldSet.size}, fieldSetKeys=${fieldSet.keys.sorted()}",
        )
        delegate.sendClientParams(name = name, phone = phone, fieldSet = fieldSet)
        config.logDiagnostic(
            "sdk sendClientParams response: delegateCompleted=true, phone=${phone.maskedPhone()}, " +
                "fieldSetSize=${fieldSet.size}",
        )
        if (!config.enrichClientOnSendClientParams) {
            return
        }

        config.logDiagnostic("client enrichment dispatch: phone=${phone.maskedPhone()}")
        clientEnricher.enrich(
            ClientExternalIdContext(
                name = name,
                phone = phone,
                fieldSet = fieldSet,
                sdkClientKey = sdkClientKey,
            ),
        )
        config.logDiagnostic("client enrichment dispatch finished: phone=${phone.maskedPhone()}")
    }
}
