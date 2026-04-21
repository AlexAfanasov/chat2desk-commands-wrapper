package com.alexafanasov.chat2desk.commands

import com.chat2desk.chat2desk_sdk.IAttachment
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
            assertThat(request.getHeader("Authorization")).isEqualTo("token-1")

            val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertThat(json.get("text").asString).isEqualTo("/status")
            assertThat(json.get("client_id").asString).isEqualTo("123")
            assertThat(json.get("client_phone").asString).isEqualTo("79991112233")
            assertThat(json.get("external_id").asString).isEqualTo("ext-1")
            assertThat(json.get("attachment").asString).isEqualTo("https://example.com/file.pdf")
            assertThat(json.get("attachment_filename").asString).isEqualTo("file.pdf")
            assertThat(json.getAsJsonObject("custom_fields").get("1").asString).isEqualTo("android")
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
                assertThat(body).contains("token-1")
                assertThat(body).contains("name=\"attachments[]\"")
                assertThat(body).contains("filename=\"photo.jpg\"")
                assertThat(result.url).isEqualTo("https://cdn.chat2desk.com/photo.jpg")
                assertThat(result.filename).isEqualTo("photo.jpg")
            } finally {
                temporaryFile.delete()
            }
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

    private fun createApi(maxUploadBytes: Long = 20L * 1024L * 1024L): DirectCommandApi {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        val baseHost = server.url("/").host
        return DirectCommandApi(
            config =
                Chat2DeskCommandsConfig(
                    baseUrl = baseUrl,
                    apiToken = "token-1",
                    maxUploadBytes = maxUploadBytes,
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
