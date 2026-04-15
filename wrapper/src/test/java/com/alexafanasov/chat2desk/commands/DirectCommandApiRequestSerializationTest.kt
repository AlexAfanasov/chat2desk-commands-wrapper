package com.alexafanasov.chat2desk.commands

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

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
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"success\"}"))

            val api = createApi()
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

    private fun createApi(): DirectCommandApi {
        return DirectCommandApi(
            config =
                Chat2DeskCommandsConfig(
                    baseUrl = server.url("/").toString().removeSuffix("/"),
                    apiToken = "token-1",
                ),
        )
    }
}
