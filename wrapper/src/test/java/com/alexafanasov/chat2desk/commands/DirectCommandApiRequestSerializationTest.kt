package com.alexafanasov.chat2desk.commands

import com.chat2desk.chat2desk_sdk.IAttachment
import com.chat2desk.chat2desk_sdk.domain.entities.MessageType
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DirectCommandApiRequestSerializationTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sendInboxCommand_serializesExpectedFields() =
        runTest {
            // given
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"success\"}"))

            val api = createApi()
            // when
            api.sendInboxCommand(
                command = "/status",
                clientId = "123",
                options =
                    InboxOptions(
                        customFields = mapOf("1" to "android", "2" to "premium"),
                        clientPhone = "79991112233",
                        externalId = "ext-1",
                        attachments =
                            listOf(
                                InboxAttachment(
                                    url = "https://example.com/file.pdf",
                                    filename = "file.pdf",
                                ),
                            ),
                    ),
            )

            // then
            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/v1/messages/inbox")
            assertThat(request.getHeader("Authorization")).isEqualTo("public-api-token-1")

            val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertThat(json.get("body").asString).isEqualTo("/status")
            assertThat(json.has("client_id")).isFalse()
            assertThat(json.get("client_phone").asString).isEqualTo("79991112233")
            assertThat(json.getAsJsonObject("from_client").get("phone").asString).isEqualTo("79991112233")
            assertThat(json.getAsJsonObject("extra_data").get("external_id").asString).isEqualTo("ext-1")
            assertThat(json.get("attachment").asString).isEqualTo("https://example.com/file.pdf")
            assertThat(json.get("attachment_filename").asString).isEqualTo("file.pdf")
            assertThat(json.getAsJsonObject("custom_fields").get("1").asString).isEqualTo("android")
        }

    @Test
    fun sendInboxCommand_fallsBackToFromClientIdFromClientIdArgument() =
        runTest {
            // given
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"success\"}"))

            val api = createApi()

            // when
            api.sendInboxCommand(command = "hello", clientId = "external-client-1")

            // then
            val request = server.takeRequest()
            val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertThat(json.has("client_id")).isFalse()
            assertThat(json.getAsJsonObject("from_client").get("id").asString).isEqualTo("external-client-1")
        }

    @Test
    fun sendInboxCommand_serializesFromClientInsteadOfClientIdWhenProvided() =
        runTest {
            // given
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"success\"}"))

            val api = createApi()
            // when
            api.sendInboxCommand(
                command = "hello",
                clientId = "sdk-client-key",
                options =
                    InboxOptions(
                        fromClient = InboxClient(phone = "79991112233"),
                        externalId = "ext-1",
                        channelId = 42,
                        transport = "external",
                    ),
            )

            // then
            val request = server.takeRequest()
            val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertThat(json.has("client_id")).isFalse()
            assertThat(json.get("channel_id").asLong).isEqualTo(42)
            assertThat(json.get("transport").asString).isEqualTo("external")
            assertThat(json.getAsJsonObject("extra_data").get("external_id").asString).isEqualTo("ext-1")
            assertThat(json.getAsJsonObject("from_client").get("phone").asString).isEqualTo("79991112233")
        }

    @Test
    fun sendInboxCommand_serializesOnlyOneFromClientIdentifier() =
        runTest {
            // given
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"success\"}"))

            val api = createApi()

            // when
            api.sendInboxCommand(
                command = "hello",
                clientId = "ignored-public-client-id",
                options =
                    InboxOptions(
                        fromClient = InboxClient(id = "external-client-1", phone = "79991112233"),
                    ),
            )

            // then
            val request = server.takeRequest()
            val fromClient =
                JsonParser.parseString(request.body.readUtf8())
                    .asJsonObject
                    .getAsJsonObject("from_client")
            assertThat(fromClient.get("id").asString).isEqualTo("external-client-1")
            assertThat(fromClient.has("phone")).isFalse()
        }

    @Test
    fun sendOperatorMessage_serializesButtonsPayloadAndKeyboard() =
        runTest {
            // given
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "status": "success",
                      "data": {
                        "message_id": 99,
                        "client_id": "321"
                      }
                    }
                    """.trimIndent(),
                ),
            )

            val api = createApi()
            // when
            api.sendOperatorMessage(
                OperatorMessageRequest(
                    clientId = "321",
                    text = "Choose option",
                    inlineButtons =
                        listOf(
                            CommandButton(type = "reply", text = "A", payload = "payload-a"),
                        ),
                    keyboard =
                        Keyboard(
                            buttons =
                                listOf(
                                    CommandButton(type = "reply", text = "B", payload = "payload-b"),
                                ),
                        ),
                ),
            )

            // then
            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/v1/messages")

            val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertThat(json.get("client_id").asString).isEqualTo("321")
            assertThat(json.get("text").asString).isEqualTo("Choose option")

            val inline = json.getAsJsonArray("inline_buttons")
            assertThat(inline.size()).isEqualTo(1)
            assertThat(inline[0].asJsonObject.get("payload").asString).isEqualTo("payload-a")

            val keyboardButtons = json.getAsJsonObject("keyboard").getAsJsonArray("buttons")
            assertThat(keyboardButtons[0].asJsonObject.get("payload").asString).isEqualTo("payload-b")
        }

    @Test
    fun uploadAttachment_serializesMultipartAndParsesMapResponse() =
        runTest {
            // given
            val temporaryFile = File.createTempFile("chat2desk-wrapper-upload", ".txt")
            temporaryFile.writeText("data")
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {"photo.jpg":"https://cdn.chat2desk.com/photo.jpg"}
                    """.trimIndent(),
                ),
            )

            try {
                val api = createApi()
                // when
                val result =
                    api.uploadAttachment(
                        FakeAttachment(
                            originalName = "photo.jpg",
                            mimeType = "image/jpeg",
                            fileSize = temporaryFile.length().toInt(),
                            filePath = temporaryFile.absolutePath,
                            throwOnGetByteArray = true,
                        ),
                    )

                // then
                val request = server.takeRequest()
                assertThat(request.path).isEqualTo("/upload_attach")
                assertThat(request.getHeader("Content-Type")).contains("multipart/form-data")
                val body = request.body.readUtf8()
                assertThat(body).contains("name=\"widget_token\"")
                assertThat(body).contains("public-api-token-1")
                assertThat(body).contains("name=\"attachments[]\"")
                assertThat(body).contains("filename=\"photo.jpg\"")
                assertThat(result.url).isEqualTo("https://cdn.chat2desk.com/photo.jpg")
                assertThat(result.filename).isEqualTo("photo.jpg")
            } finally {
                temporaryFile.delete()
            }
        }

    @Test
    fun loadMessages_serializesQueryAndMapsMessages() =
        runTest {
            // given
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "data": [
                        {
                          "id": 11,
                          "type": "from_client",
                          "body": "hello",
                          "created": "2026-05-21T10:44:31 UTC",
                          "status": "delivered",
                          "read": true,
                          "extra_data": {"external_id": "pm-msg-1"},
                          "attachments": [
                            {
                              "id": 7,
                              "link": "/uploads/photo.jpg",
                              "content_type": "image/jpeg",
                              "original_file_name": "photo.jpg",
                              "file_size": 123
                            }
                          ]
                        },
                        {
                          "id": 12,
                          "type": "to_client",
                          "text": "answer",
                          "created_at": 1779367456
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )

            val api = createApi()

            // when
            val result =
                api.loadMessages(
                    PublicMessagesRequest(
                        offset = 10,
                        limit = 25,
                        clientId = 146339237,
                        channelId = 121177,
                        transport = "external",
                    ),
                )

            // then
            val request = server.takeRequest()
            assertThat(request.requestUrl!!.encodedPath).isEqualTo("/v1/messages")
            assertThat(request.requestUrl!!.queryParameter("offset")).isEqualTo("10")
            assertThat(request.requestUrl!!.queryParameter("limit")).isEqualTo("25")
            assertThat(request.requestUrl!!.queryParameter("client_id")).isEqualTo("146339237")
            assertThat(request.requestUrl!!.queryParameter("channel_id")).isEqualTo("121177")
            assertThat(request.requestUrl!!.queryParameter("transport")).isEqualTo("external")

            assertThat(result).hasSize(2)
            assertThat(result[0].id).isEqualTo("public-11")
            assertThat(result[0].realId).isEqualTo(11)
            assertThat(result[0].type).isEqualTo(MessageType.IN)
            assertThat(result[0].text).isEqualTo("hello")
            assertThat(result[0].date!!.toEpochMilliseconds()).isEqualTo(1779360271000)
            assertThat(result[0].attachments).hasSize(1)
            assertThat(result[0].attachments!!.first().link).isEqualTo("https://storage.chat2desk.com/uploads/photo.jpg")
            assertThat(result[1].id).isEqualTo("public-12")
            assertThat(result[1].type).isEqualTo(MessageType.OUT)
            assertThat(result[1].text).isEqualTo("answer")
        }

    @Test
    fun loadMessages_doesNotUseExternalIdAsMessageIdentity() =
        runTest {
            // given
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "data": [
                        {
                          "id": 11,
                          "type": "from_client",
                          "text": "first",
                          "extra_data": {"external_id": "master-1"}
                        },
                        {
                          "id": 12,
                          "type": "from_client",
                          "text": "second",
                          "extra_data": {"external_id": "master-1"}
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )

            val api = createApi()

            // when
            val result = api.loadMessages(PublicMessagesRequest())

            // then
            assertThat(result.map { it.id }).containsExactly("public-11", "public-12").inOrder()
            assertThat(result.map { it.realId }).containsExactly(11L, 12L).inOrder()
            assertThat(result.map { it.text }).containsExactly("first", "second").inOrder()
        }

    @Test
    fun sendInboxCommand_omitsZeroDefaultChannelId() =
        runTest {
            // given
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"success\"}"))

            val api = createApi(defaultChannelId = 0L)

            // when
            api.sendInboxCommand(
                command = "hello",
                clientId = "sdk-client-key",
                options = InboxOptions(fromClient = InboxClient(phone = "79991112233")),
            )

            // then
            val request = server.takeRequest()
            val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertThat(json.has("channel_id")).isFalse()
        }

    @Test
    fun findClientByPhone_serializesQueryAndParsesClient() =
        runTest {
            // given
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "data": [
                        {
                          "id": 146339237,
                          "phone": "79090090009",
                          "name": "Test Master",
                          "custom_fields": {"4": "2008484"}
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )
            val api = createApi()

            // when
            val result = api.findClientByPhone(PublicClientLookupRequest(phone = "79090090009"))

            // then
            val request = server.takeRequest()
            assertThat(request.requestUrl!!.encodedPath).isEqualTo("/v1/clients")
            assertThat(request.requestUrl!!.queryParameter("phone")).isEqualTo("79090090009")
            assertThat(result!!.id).isEqualTo(146339237)
            assertThat(result.customFields).containsEntry("4", "2008484")
        }

    @Test
    fun createClient_serializesOnlyWhenExplicitlyCalled() =
        runTest {
            // given
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "data": {
                        "id": 146339238,
                        "phone": "79090090009"
                      }
                    }
                    """.trimIndent(),
                ),
            )
            val api = createApi()

            // when
            val result =
                api.createClient(
                    PublicClientCreateRequest(
                        name = "Test Master",
                        phone = "79090090009",
                        customFields = mapOf("4" to "2008484"),
                    ),
                )

            // then
            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/v1/clients")
            val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertThat(json.get("name").asString).isEqualTo("Test Master")
            assertThat(json.get("phone").asString).isEqualTo("79090090009")
            assertThat(json.getAsJsonObject("custom_fields").get("4").asString).isEqualTo("2008484")
            assertThat(result.id).isEqualTo(146339238)
        }

    @Test
    fun loadChannels_parsesChannels() =
        runTest {
            // given
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """
                    {
                      "data": [
                        {"id": 121177, "transport": "external", "status": "online"}
                      ]
                    }
                    """.trimIndent(),
                ),
            )
            val api = createApi()

            // when
            val result = api.loadChannels()

            // then
            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/v1/channels")
            assertThat(result).containsExactly(PublicChannel(id = 121177, transport = "external", status = "online"))
        }

    @Test
    fun uploadAttachment_rejectsOversizedAttachmentBeforeRequest() =
        runTest {
            // given
            val api = createApi(maxUploadBytes = 2)

            // when
            val error =
                try {
                    api.uploadAttachment(
                        FakeAttachment(
                            originalName = "big.bin",
                            mimeType = "application/octet-stream",
                            fileSize = 3,
                            filePath = "C:/tmp/not-used",
                        ),
                    )
                    null
                } catch (e: IllegalArgumentException) {
                    e
                }

            // then
            assertThat(error).isNotNull()
            assertThat(server.requestCount).isEqualTo(0)
        }

    @Test
    fun uploadAttachment_rejectsMissingFilePath() =
        runTest {
            // given
            val api = createApi()

            // when
            val error =
                try {
                    api.uploadAttachment(
                        FakeAttachment(
                            originalName = "missing.txt",
                            mimeType = "text/plain",
                            fileSize = 10,
                            filePath = "C:/definitely-missing/missing.txt",
                        ),
                    )
                    null
                } catch (e: IllegalArgumentException) {
                    e
                }

            // then
            assertThat(error).isNotNull()
            assertThat(server.requestCount).isEqualTo(0)
        }

    @Test
    fun uploadAttachment_rejectsNonRegularFilePath() =
        runTest {
            // given
            val tempDir = Files.createTempDirectory("chat2desk-wrapper-dir").toFile()
            val api = createApi()

            try {
                // when
                val error =
                    try {
                        api.uploadAttachment(
                            FakeAttachment(
                                originalName = "folder",
                                mimeType = "application/octet-stream",
                                fileSize = 1,
                                filePath = tempDir.absolutePath,
                            ),
                        )
                        null
                    } catch (e: IllegalArgumentException) {
                        e
                    }

                // then
                assertThat(error).isNotNull()
                assertThat(server.requestCount).isEqualTo(0)
            } finally {
                tempDir.deleteRecursively()
            }
        }

    private fun createApi(
        maxUploadBytes: Long = 20L * 1024L * 1024L,
        defaultChannelId: Long? = null,
    ): DirectCommandApi {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        val baseHost = server.url("/").host
        return DirectCommandApi(
            config =
                Chat2DeskCommandsConfig.publicApi(
                    baseUrl = baseUrl,
                    publicApiToken = "public-api-token-1",
                    maxUploadBytes = maxUploadBytes,
                    defaultChannelId = defaultChannelId,
                    requireHttps = false,
                    trustedHostSuffixes = setOf(baseHost),
                ),
        )
    }

    private data class FakeAttachment(
        override val originalName: String,
        override val mimeType: String,
        override val fileSize: Int,
        private val filePath: String,
        private val throwOnGetByteArray: Boolean = false,
    ) : IAttachment {
        override fun getFilePath(): String = filePath

        override fun getByteArray(): ByteArray {
            if (throwOnGetByteArray) {
                error("getByteArray must not be called")
            }
            return byteArrayOf()
        }
    }
}
