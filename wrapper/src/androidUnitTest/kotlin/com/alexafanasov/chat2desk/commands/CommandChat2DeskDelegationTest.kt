package com.alexafanasov.chat2desk.commands

import com.chat2desk.chat2desk_sdk.AttachedFile
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test
import java.io.File

class CommandChat2DeskDelegationTest {
    @Suppress("DEPRECATION")
    @Test
    fun factory_acceptsDeprecatedOkHttpGsonCompatibilityOverload() {
        val wrapped =
            CommandChat2DeskFactory.create(
                delegate = RecordingDelegate(),
                config = config(),
                httpClient = OkHttpClient(),
                gson = Gson(),
            )

        assertThat(wrapped).isInstanceOf(ICommandChat2Desk::class.java)
    }

    @Test
    fun sendMessage_delegatesToSdkAndDoesNotCallEnrichmentApi() =
        runTest {
            val delegate = RecordingDelegate()
            val enrichmentApi = RecordingClientEnrichmentApi()
            val wrapper = createWrapper(delegate = delegate, enrichmentApi = enrichmentApi)

            val startResult = wrapper.start("client-abc")
            wrapper.sendMessage("hello")

            assertThat(startResult).isEqualTo("client-abc")
            assertThat(delegate.startWithClientCalls).containsExactly("client-abc")
            assertThat(delegate.sentMessages).containsExactly("hello")
            assertThat(enrichmentApi.resolveStartedClientCalls).isEmpty()
            assertThat(enrichmentApi.updateClientExternalIdCalls).isEmpty()
        }

    @Test
    fun sendMessageWithAttachment_delegatesToSdk() =
        runTest {
            val temporaryFile = File.createTempFile("chat2desk-wrapper-test", ".txt")
            temporaryFile.writeText("hello")

            try {
                val attachedFile =
                    createAttachedFile(
                        file = temporaryFile,
                        originalName = "photo.jpg",
                        mimeType = "image/jpeg",
                    )
                val delegate = RecordingDelegate()
                val enrichmentApi = RecordingClientEnrichmentApi()
                val wrapper = createWrapper(delegate = delegate, enrichmentApi = enrichmentApi)

                wrapper.sendMessage("with attachment", attachedFile)

                assertThat(delegate.sentMessagesWithAttachment).containsExactly("with attachment" to attachedFile)
                assertThat(enrichmentApi.resolveStartedClientCalls).isEmpty()
                assertThat(enrichmentApi.updateClientExternalIdCalls).isEmpty()
                assertThat(temporaryFile.exists()).isTrue()
            } finally {
                temporaryFile.delete()
            }
        }

    @Test
    fun diagnosticsHandler_isSilentWhenDiagnosticsDisabled() =
        runTest {
            val diagnostics = mutableListOf<String>()
            val wrapper =
                createWrapper(
                    delegate = RecordingDelegate(),
                    enrichmentApi = RecordingClientEnrichmentApi(),
                    config =
                        Chat2DeskCommandsConfig(
                            baseUrl = "https://api.chat2desk.com",
                            publicApiToken = "token-1",
                            diagnosticsEnabled = false,
                            diagnosticsHandler = diagnostics::add,
                        ),
                )

            wrapper.start("sdk-client-key")

            assertThat(diagnostics).isEmpty()
        }

    @Test
    fun sendClientParams_delegatesToSdkThenUpdatesExternalId() =
        runTest {
            val diagnostics = mutableListOf<String>()
            val delegate = RecordingDelegate()
            val enrichmentApi =
                RecordingClientEnrichmentApi().apply {
                    startedClient = PublicClient(id = 772337111L, phone = "79991112233", name = "Jane")
                }
            val wrapper =
                createWrapper(
                    delegate = delegate,
                    enrichmentApi = enrichmentApi,
                    config = config(clientExternalIdResolver = { "12345" }, diagnosticsHandler = diagnostics::add),
                )

            wrapper.start("sdk-client-key")
            wrapper.sendClientParams(name = "Jane", phone = "79991112233", fieldSet = mapOf(1 to "premium"))

            assertThat(delegate.sentClientParams)
                .containsExactly(ClientExternalIdContext("Jane", "79991112233", mapOf(1 to "premium")))
            assertThat(delegate.eventLog + enrichmentApi.eventLog)
                .containsAtLeast("delegate.sendClientParams", "api.resolveStartedClient", "api.updateClientExternalId")
                .inOrder()
            assertThat(enrichmentApi.resolveStartedClientCalls).containsExactly("sdk-client-key")
            assertThat(enrichmentApi.updateClientExternalIdCalls).containsExactly(772337111L to "12345")
            assertThat(diagnostics)
                .containsAtLeast(
                    "sdk sendClientParams request: namePresent=true, phone=***2233, " +
                        "fieldSetSize=1, fieldSetKeys=[1]",
                    "sdk sendClientParams response: delegateCompleted=true, phone=***2233, fieldSetSize=1",
                    "client enrichment dispatch: phone=***2233",
                    "client enrichment resolver result: externalIdPresent=true, externalIdLength=5",
                    "client enrichment start-client resolve start: clientKeyPresent=true, clientKeyLength=14",
                    "client enrichment start-client resolve result: clientId=772337111",
                    "client enrichment update start: clientId=772337111, " +
                        "externalIdPresent=true, externalIdLength=5",
                    "client enrichment succeeded: clientId=772337111",
                    "client enrichment dispatch finished: phone=***2233",
                )
                .inOrder()
        }

    @Test
    fun sendClientParams_noExternalId_doesNotCallEnrichmentApiUpdate() =
        runTest {
            val diagnostics = mutableListOf<String>()
            val delegate = RecordingDelegate()
            val enrichmentApi = RecordingClientEnrichmentApi()
            val wrapper =
                createWrapper(
                    delegate = delegate,
                    enrichmentApi = enrichmentApi,
                    config = config(clientExternalIdResolver = { " " }, diagnosticsHandler = diagnostics::add),
                )

            wrapper.sendClientParams(name = "Jane", phone = "79991112233", fieldSet = emptyMap())

            assertThat(delegate.sentClientParams).hasSize(1)
            assertThat(enrichmentApi.resolveStartedClientCalls).isEmpty()
            assertThat(enrichmentApi.updateClientExternalIdCalls).isEmpty()
            assertThat(diagnostics).contains("client enrichment skipped: external id blank, phone=***2233")
        }

    @Test
    fun sendClientParams_missingSdkClientKey_skipsUpdate() =
        runTest {
            val diagnostics = mutableListOf<String>()
            val enrichmentApi = RecordingClientEnrichmentApi()
            val wrapper =
                createWrapper(
                    delegate = RecordingDelegate(),
                    enrichmentApi = enrichmentApi,
                    config = config(clientExternalIdResolver = { "12345" }, diagnosticsHandler = diagnostics::add),
                )

            wrapper.sendClientParams(name = "Jane", phone = "79991112233", fieldSet = emptyMap())

            assertThat(enrichmentApi.resolveStartedClientCalls).isEmpty()
            assertThat(enrichmentApi.updateClientExternalIdCalls).isEmpty()
            assertThat(diagnostics).contains("client enrichment skipped: sdk client key missing")
        }

    @Test
    fun sendClientParams_startResponseClientMissing_skipsUpdate() =
        runTest {
            val diagnostics = mutableListOf<String>()
            val enrichmentApi = RecordingClientEnrichmentApi()
            val wrapper =
                createWrapper(
                    delegate = RecordingDelegate(),
                    enrichmentApi = enrichmentApi,
                    config = config(clientExternalIdResolver = { "12345" }, diagnosticsHandler = diagnostics::add),
                )

            wrapper.start("sdk-client-key")
            wrapper.sendClientParams(name = "Jane", phone = "79991112233", fieldSet = mapOf(2 to "gold"))

            assertThat(enrichmentApi.resolveStartedClientCalls).containsExactly("sdk-client-key")
            assertThat(enrichmentApi.updateClientExternalIdCalls).isEmpty()
            assertThat(diagnostics).contains("client enrichment skipped: start response client id missing")
        }

    @Test
    fun sendClientParams_publicApiFailureSwallowedByDefault() =
        runTest {
            val diagnostics = mutableListOf<String>()
            val failures = mutableListOf<Throwable>()
            val delegate = RecordingDelegate()
            val enrichmentApi =
                RecordingClientEnrichmentApi().apply {
                    startedClient = PublicClient(id = 772337111L)
                    updateClientExternalIdError = Chat2DeskCommandApiException("boom", 500, "{}")
                }
            val wrapper =
                createWrapper(
                    delegate = delegate,
                    enrichmentApi = enrichmentApi,
                    config =
                        config(
                            clientExternalIdResolver = { "12345" },
                            enrichmentFailureHandler = failures::add,
                            diagnosticsHandler = diagnostics::add,
                        ),
                )

            wrapper.start("sdk-client-key")
            wrapper.sendClientParams(name = "Jane", phone = "79991112233", fieldSet = emptyMap())

            assertThat(delegate.sentClientParams).hasSize(1)
            assertThat(failures).hasSize(1)
            assertThat(diagnostics)
                .contains("client enrichment failed: type=Chat2DeskCommandApiException, message=boom")
        }

    @Test
    fun sendClientParams_publicApiFailureThrowsWhenConfigured() =
        runTest {
            val delegate = RecordingDelegate()
            val enrichmentApi =
                RecordingClientEnrichmentApi().apply {
                    startedClient = PublicClient(id = 772337111L)
                    updateClientExternalIdError = Chat2DeskCommandApiException("boom", 500, "{}")
                }
            val wrapper =
                createWrapper(
                    delegate = delegate,
                    enrichmentApi = enrichmentApi,
                    config =
                        config(
                            clientExternalIdResolver = { "12345" },
                            enrichmentFailureMode = EnrichmentFailureMode.THROW,
                        ),
                )

            wrapper.start("sdk-client-key")
            val thrown =
                try {
                    wrapper.sendClientParams(name = "Jane", phone = "79991112233", fieldSet = emptyMap())
                    null
                } catch (e: Chat2DeskCommandApiException) {
                    e
                }

            assertThat(thrown).isNotNull()
            assertThat(delegate.sentClientParams).hasSize(1)
        }

    @Test
    fun sendClientParams_sameClientAndExternalId_skipsDuplicatePut() =
        runTest {
            val enrichmentApi =
                RecordingClientEnrichmentApi().apply {
                    startedClient = PublicClient(id = 772337111L)
                }
            val wrapper =
                createWrapper(
                    delegate = RecordingDelegate(),
                    enrichmentApi = enrichmentApi,
                    config = config(clientExternalIdResolver = { "12345" }),
                )

            wrapper.start("sdk-client-key")
            wrapper.sendClientParams(name = "Jane", phone = "79991112233", fieldSet = emptyMap())
            wrapper.sendClientParams(name = "Jane", phone = "79991112233", fieldSet = emptyMap())

            assertThat(enrichmentApi.resolveStartedClientCalls).hasSize(2)
            assertThat(enrichmentApi.updateClientExternalIdCalls).containsExactly(772337111L to "12345")
        }

    private fun createWrapper(
        delegate: RecordingDelegate,
        enrichmentApi: RecordingClientEnrichmentApi,
        config: Chat2DeskCommandsConfig = config(),
    ): CommandChat2Desk {
        return CommandChat2Desk(
            delegate = delegate,
            clientEnrichmentApi = enrichmentApi,
            config = config,
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

    private fun createAttachedFile(
        file: File,
        originalName: String,
        mimeType: String,
    ): AttachedFile {
        val constructor =
            AttachedFile::class.java.getDeclaredConstructor(
                File::class.java,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
            )
        constructor.isAccessible = true
        return constructor.newInstance(file, originalName, mimeType, file.length().toInt())
    }
}
