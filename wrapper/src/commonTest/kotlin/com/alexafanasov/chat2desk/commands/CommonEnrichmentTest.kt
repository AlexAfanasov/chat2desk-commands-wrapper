package com.alexafanasov.chat2desk.commands

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CommonEnrichmentTest {
    @Test
    fun externalIdResolverValueUpdatesClient() =
        runTest {
            val api =
                RecordingCommonEnrichmentApi().apply {
                    startedClient = PublicClient(id = 42)
                }
            val enricher =
                Chat2DeskClientEnricher(
                    clientEnrichmentApi = api,
                    config = config(clientExternalIdResolver = { "external-1" }),
                )

            enricher.enrich(context())

            assertEquals(listOf("sdk-client-key"), api.resolveStartedClientCalls)
            assertEquals(listOf(42L to "external-1"), api.updateClientExternalIdCalls)
        }

    @Test
    fun externalIdResolverNullOrBlankSkipsApi() =
        runTest {
            val api = RecordingCommonEnrichmentApi()

            Chat2DeskClientEnricher(
                clientEnrichmentApi = api,
                config = config(clientExternalIdResolver = { null }),
            ).enrich(context())
            Chat2DeskClientEnricher(
                clientEnrichmentApi = api,
                config = config(clientExternalIdResolver = { " " }),
            ).enrich(context())

            assertTrue(api.resolveStartedClientCalls.isEmpty())
            assertTrue(api.updateClientExternalIdCalls.isEmpty())
        }

    @Test
    fun diagnosticsMaskPhoneAndDoNotIncludeSecrets() =
        runTest {
            val diagnostics = mutableListOf<String>()
            val api =
                RecordingCommonEnrichmentApi().apply {
                    startedClient = PublicClient(id = 42)
                }
            val enricher =
                Chat2DeskClientEnricher(
                    clientEnrichmentApi = api,
                    config =
                        config(
                            clientExternalIdResolver = { "very-secret-external-id" },
                            diagnosticsHandler = diagnostics::add,
                        ),
                )

            enricher.enrich(context(phone = "+7 999 111-22-33"))

            val joinedLogs = diagnostics.joinToString(separator = "\n")
            assertTrue(joinedLogs.contains("***2233"))
            assertTrue(joinedLogs.contains("externalIdPresent=true"))
            assertTrue(!joinedLogs.contains("+7 999 111-22-33"))
            assertTrue(!joinedLogs.contains("very-secret-external-id"))
            assertTrue(!joinedLogs.contains("sdk-client-key"))
        }

    @Test
    fun enrichmentFailureSwallowInvokesHandler() =
        runTest {
            val failures = mutableListOf<Throwable>()
            val api =
                RecordingCommonEnrichmentApi().apply {
                    startedClient = PublicClient(id = 42)
                    updateClientExternalIdError = Chat2DeskCommandApiException("boom", 500, "{}")
                }
            val enricher =
                Chat2DeskClientEnricher(
                    clientEnrichmentApi = api,
                    config =
                        config(
                            clientExternalIdResolver = { "external-1" },
                            enrichmentFailureHandler = failures::add,
                        ),
                )

            enricher.enrich(context())

            assertEquals(1, failures.size)
        }

    @Test
    fun enrichmentFailureThrowRethrows() =
        runTest {
            val api =
                RecordingCommonEnrichmentApi().apply {
                    startedClient = PublicClient(id = 42)
                    updateClientExternalIdError = Chat2DeskCommandApiException("boom", 500, "{}")
                }
            val enricher =
                Chat2DeskClientEnricher(
                    clientEnrichmentApi = api,
                    config =
                        config(
                            clientExternalIdResolver = { "external-1" },
                            enrichmentFailureMode = EnrichmentFailureMode.THROW,
                        ),
                )

            assertFailsWith<Chat2DeskCommandApiException> {
                enricher.enrich(context())
            }
        }

    private fun context(phone: String = "79991112233"): ClientExternalIdContext {
        return ClientExternalIdContext(
            name = "Jane",
            phone = phone,
            fieldSet = mapOf(1 to "premium"),
            sdkClientKey = "sdk-client-key",
        )
    }

    private fun config(
        clientExternalIdResolver: ((ClientExternalIdContext) -> String?)? = null,
        enrichmentFailureMode: EnrichmentFailureMode = EnrichmentFailureMode.SWALLOW,
        enrichmentFailureHandler: ((Throwable) -> Unit)? = null,
        diagnosticsHandler: ((String) -> Unit)? = null,
    ): Chat2DeskCommandsConfig {
        return Chat2DeskCommandsConfig(
            baseUrl = "https://api.chat2desk.com",
            publicApiToken = "token-1",
            clientExternalIdResolver = clientExternalIdResolver,
            enrichmentFailureMode = enrichmentFailureMode,
            enrichmentFailureHandler = enrichmentFailureHandler,
            diagnosticsEnabled = diagnosticsHandler != null,
            diagnosticsHandler = diagnosticsHandler,
        )
    }
}

private class RecordingCommonEnrichmentApi : Chat2DeskClientEnrichmentApi {
    val resolveStartedClientCalls = mutableListOf<String>()
    val updateClientExternalIdCalls = mutableListOf<Pair<Long, String>>()
    var startedClient: PublicClient? = null
    var updateClientExternalIdError: Throwable? = null

    override suspend fun resolveStartedClient(clientKey: String): PublicClient? {
        resolveStartedClientCalls += clientKey
        return startedClient
    }

    override suspend fun updateClientExternalId(
        clientId: Long,
        externalId: String,
    ): PublicClient? {
        updateClientExternalIdError?.let { throw it }
        updateClientExternalIdCalls += clientId to externalId
        return null
    }
}
