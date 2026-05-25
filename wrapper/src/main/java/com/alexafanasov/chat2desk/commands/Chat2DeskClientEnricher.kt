package com.alexafanasov.chat2desk.commands

import kotlinx.coroutines.CancellationException

class Chat2DeskClientEnricher(
    private val clientEnrichmentApi: Chat2DeskClientEnrichmentApi,
    private val config: Chat2DeskCommandsConfig,
) {
    private var lastEnrichedClientExternalId: String? = null

    @Suppress("TooGenericExceptionCaught")
    suspend fun enrich(context: ClientExternalIdContext) {
        val externalId = resolveExternalId(context) ?: return

        try {
            logDiagnostic(
                "client enrichment request: phone=${context.phone.maskedPhone()}, " +
                    "externalIdPresent=true, externalIdLength=${externalId.length}",
            )

            val publicClient = resolveStartedClient(context) ?: return
            updateExternalIdIfNeeded(publicClient = publicClient, externalId = externalId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            config.enrichmentFailureHandler?.invoke(e)
            logDiagnostic(
                "client enrichment failed: type=${e::class.java.simpleName}, message=${e.message.orEmpty()}",
            )

            if (config.enrichmentFailureMode == EnrichmentFailureMode.THROW) {
                throw e
            }
        }
    }

    private fun resolveExternalId(context: ClientExternalIdContext): String? {
        if (!config.enrichClientOnSendClientParams) {
            logDiagnostic("client enrichment skipped: disabled")
            return null
        }

        val resolver = config.clientExternalIdResolver
        if (resolver == null) {
            logDiagnostic("client enrichment skipped: external id resolver missing")
            return null
        }

        val externalId = resolver.invoke(context)?.takeIf { it.isNotBlank() }
        logDiagnostic(
            "client enrichment resolver result: externalIdPresent=${externalId != null}, " +
                "externalIdLength=${externalId?.length ?: 0}",
        )
        if (externalId == null) {
            logDiagnostic(
                "client enrichment skipped: external id blank, phone=${context.phone.maskedPhone()}",
            )
        }
        return externalId
    }

    private suspend fun resolveStartedClient(context: ClientExternalIdContext): PublicClient? {
        val clientKey = context.sdkClientKey?.takeIf { it.isNotBlank() }
        if (clientKey == null) {
            logDiagnostic("client enrichment skipped: sdk client key missing")
            return null
        }

        logDiagnostic(
            "client enrichment start-client resolve start: ${clientKey.presenceAndLength("clientKey")}",
        )
        val publicClient = clientEnrichmentApi.resolveStartedClient(clientKey)
        if (publicClient == null) {
            logDiagnostic("client enrichment skipped: start response client id missing")
            return null
        }
        logDiagnostic("client enrichment start-client resolve result: clientId=${publicClient.id}")
        return publicClient
    }

    private suspend fun updateExternalIdIfNeeded(
        publicClient: PublicClient,
        externalId: String,
    ) {
        val dedupeKey = "${publicClient.id}:$externalId"
        if (lastEnrichedClientExternalId == dedupeKey) {
            logDiagnostic("client enrichment skipped: already updated clientId=${publicClient.id}")
            return
        }

        logDiagnostic(
            "client enrichment update start: clientId=${publicClient.id}, " +
                "externalIdPresent=true, externalIdLength=${externalId.length}",
        )
        clientEnrichmentApi.updateClientExternalId(
            clientId = publicClient.id,
            externalId = externalId,
        )

        lastEnrichedClientExternalId = dedupeKey
        logDiagnostic("client enrichment succeeded: clientId=${publicClient.id}")
    }

    private fun logDiagnostic(message: String) {
        config.logDiagnostic(message)
    }
}
