package com.alexafanasov.chat2desk.commands

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class DirectCommandApiIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var api: DirectCommandApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        api =
            DirectCommandApi(
                config =
                    Chat2DeskCommandsConfig(
                        baseUrl = server.url("/").toString().removeSuffix("/"),
                        apiToken = "token-1",
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

            val result = api.loadMenuCommands(channelId = 100)

            val request = server.takeRequest()
            assertThat(request.path).isEqualTo("/v1/scenarios/menu_items?channel_id=100")
            assertThat(result).hasSize(2)
            assertThat(result[1].command).isEqualTo("/status")
            assertThat(result[1].parentId).isEqualTo(1)
        }

    @Test
    fun sendInboxCommand_throwsOnHttpError() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"status\":\"error\"}"))

            val error =
                try {
                    api.sendInboxCommand(command = "/status", clientId = "1")
                    null
                } catch (e: Chat2DeskCommandApiException) {
                    e
                }

            assertThat(error).isNotNull()
            assertThat(error!!.statusCode).isEqualTo(400)
        }

    @Test
    fun sendOperatorMessage_throwsOnServerError() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500).setBody("{\"status\":\"error\"}"))

            val error =
                try {
                    api.sendOperatorMessage(OperatorMessageRequest(clientId = "1", text = "hello"))
                    null
                } catch (e: Chat2DeskCommandApiException) {
                    e
                }

            assertThat(error).isNotNull()
            assertThat(error!!.statusCode).isEqualTo(500)
        }
}
