package com.alexafanasov.chat2desk.commands

import com.chat2desk.chat2desk_sdk.IAttachment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class DirectCommandApiIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var api: DirectCommandApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/").toString().removeSuffix("/")
        val baseHost = server.url("/").host

        api =
            DirectCommandApi(
                config =
                    Chat2DeskCommandsConfig(
                        baseUrl = baseUrl,
                        apiToken = "token-1",
                        requireHttps = false,
                        trustedHostSuffixes = setOf(baseHost),
                    ),
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun loadMenuCommands_successfullyParsesResponse() =
        runTest {
            // given
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "status": "success",
                      "data": [
                        {"id": 1, "text": "Root", "command": "/start", "parentID": null, "position": 1},
                        {"id": 2, "text": "Status", "command": "/status", "parentID": 1, "position": 2}
                      ]
                    }
                    """.trimIndent(),
                ),
            )

            // when
            val result = api.loadMenuCommands(channelId = 100)

            // then
            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/v1/scenarios/menu_items?channel_id=100")
            assertThat(result).hasSize(2)
            assertThat(result[1].command).isEqualTo("/status")
            assertThat(result[1].parentId).isEqualTo(1)
        }

    @Test
    fun sendInboxCommand_throwsOnHttpError() =
        runTest {
            // given
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"status\":\"error\"}"))

            // when
            val error =
                try {
                    api.sendInboxCommand(command = "/status", clientId = "1")
                    null
                } catch (e: Chat2DeskCommandApiException) {
                    e
                }

            // then
            assertThat(error).isNotNull()
            assertThat(error!!.statusCode).isEqualTo(400)
        }

    @Test
    fun sendOperatorMessage_throwsOnServerError() =
        runTest {
            // given
            server.enqueue(MockResponse().setResponseCode(500).setBody("{\"status\":\"error\"}"))

            // when
            val error =
                try {
                    api.sendOperatorMessage(OperatorMessageRequest(clientId = "1", text = "hello"))
                    null
                } catch (e: Chat2DeskCommandApiException) {
                    e
                }

            // then
            assertThat(error).isNotNull()
            assertThat(error!!.statusCode).isEqualTo(500)
        }

    @Test
    fun uploadAttachment_throwsOnServerError() =
        runTest {
            // given
            val temporaryFile = File.createTempFile("chat2desk-wrapper-upload", ".txt")
            temporaryFile.writeText("data")
            server.enqueue(MockResponse().setResponseCode(500).setBody("{\"status\":\"error\"}"))

            try {
                // when
                val error =
                    try {
                        api.uploadAttachment(
                            object : IAttachment {
                                override val originalName: String = "file.txt"
                                override val mimeType: String = "text/plain"
                                override val fileSize: Int = temporaryFile.length().toInt()

                                override fun getFilePath(): String = temporaryFile.absolutePath

                                override fun getByteArray(): ByteArray = error("unused")
                            },
                        )
                        null
                    } catch (e: Chat2DeskCommandApiException) {
                        e
                    }

                // then
                assertThat(error).isNotNull()
                assertThat(error!!.statusCode).isEqualTo(500)
            } finally {
                temporaryFile.delete()
            }
        }

    @Test
    fun constructor_rejectsHttpBaseUrlWhenHttpsRequired() {
        // given/when
        val error =
            try {
                DirectCommandApi(
                    config =
                        Chat2DeskCommandsConfig(
                            baseUrl = "http://localhost:8080",
                            apiToken = "token-1",
                            requireHttps = true,
                            trustedHostSuffixes = setOf("localhost"),
                        ),
                )
                null
            } catch (e: IllegalArgumentException) {
                e
            }

        // then
        assertThat(error).isNotNull()
    }

    @Test
    fun constructor_rejectsUntrustedHost() {
        // given/when
        val error =
            try {
                DirectCommandApi(
                    config =
                        Chat2DeskCommandsConfig(
                            baseUrl = "https://evil.example.org",
                            apiToken = "token-1",
                            trustedHostSuffixes = setOf("chat2desk.com"),
                        ),
                )
                null
            } catch (e: IllegalArgumentException) {
                e
            }

        // then
        assertThat(error).isNotNull()
    }

    @Test
    fun usesUploadBaseUrlForUploadAndBaseUrlForV1Requests() =
        runTest {
            // given
            val uploadServer = MockWebServer()
            uploadServer.start()
            val temporaryFile = File.createTempFile("chat2desk-wrapper-upload", ".txt")
            temporaryFile.writeText("data")
            val splitApi =
                DirectCommandApi(
                    config =
                        Chat2DeskCommandsConfig(
                            baseUrl = server.url("/").toString().removeSuffix("/"),
                            uploadBaseUrl = uploadServer.url("/").toString().removeSuffix("/"),
                            apiToken = "token-1",
                            requireHttps = false,
                            trustedHostSuffixes = setOf(server.url("/").host, uploadServer.url("/").host),
                        ),
                )
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"success\"}"))
            uploadServer.enqueue(MockResponse().setResponseCode(200).setBody("{\"file.txt\":\"https://cdn/file.txt\"}"))

            try {
                // when
                splitApi.sendInboxCommand(command = "hello", clientId = "client-1")
                splitApi.uploadAttachment(
                    object : IAttachment {
                        override val originalName: String = "file.txt"
                        override val mimeType: String = "text/plain"
                        override val fileSize: Int = temporaryFile.length().toInt()

                        override fun getFilePath(): String = temporaryFile.absolutePath

                        override fun getByteArray(): ByteArray = error("unused")
                    },
                )

                // then
                val inboxRequest = server.takeRequest()
                val uploadRequest = uploadServer.takeRequest()
                assertThat(inboxRequest.path).isEqualTo("/v1/messages/inbox")
                assertThat(uploadRequest.path).isEqualTo("/upload_attach")
            } finally {
                temporaryFile.delete()
                uploadServer.shutdown()
            }
        }
}
