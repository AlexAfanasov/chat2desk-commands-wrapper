package com.alexafanasov.chat2desk.commands

interface Chat2DeskClientEnrichmentApi {
    suspend fun resolveStartedClient(clientKey: String): PublicClient?

    suspend fun updateClientExternalId(
        clientId: Long,
        externalId: String,
    ): PublicClient?
}
