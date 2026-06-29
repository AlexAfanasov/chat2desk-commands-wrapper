package com.alexafanasov.chat2desk.commands

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class DirectClientEnrichmentApiRequestSerializationTest {
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
    fun resolveStartedClient_postsSdkStartAndParsesClientId() =
        runTest {
            val diagnostics = mutableListOf<String>()
            val responseBody =
                """
                {
                  "client": {
                    "id": 146489656,
                    "clientID": 772337111,
                    "phone": "79991112233",
                    "name": "Jane",
                    "custom_fields": {"1": "premium"}
                  }
                }
                """.trimIndent()
            val expectedResponseLog =
                "sdk start client resolve response: status=200, " +
                    "bodyLength=${responseBody.length}, clientId=772337111"

            server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))

            val api = apiWithLogs(diagnostics::add)

            val result =
                api.resolveStartedClient("""{"client_id":"[chat] abc","client_token":"token"}""")

            val request = server.takeRequest()
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.path).contains("/start?id=widget-token")
            assertThat(request.path).contains("client_key=")
            assertThat(request.getHeader("Authorization")).isNull()
            assertThat(result).isEqualTo(
                PublicClient(
                    id = 772337111L,
                    phone = "79991112233",
                    name = "Jane",
                    customFields = mapOf("1" to "premium"),
                ),
            )
            assertThat(diagnostics)
                .containsAtLeast(
                    "sdk start client resolve request: clientKeyPresent=true, clientKeyLength=49",
                    expectedResponseLog,
                )
                .inOrder()
        }

    @Test
    fun updateClientExternalId_serializesPut() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"success\"}"))

            val api = api()

            api.updateClientExternalId(clientId = 772337111L, externalId = "12345")

            val request = server.takeRequest()
            assertThat(request.method).isEqualTo("PUT")
            assertThat(request.path).isEqualTo("/v1/clients/772337111")
            assertThat(request.getHeader("Authorization")).isEqualTo("token-1")

            val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertThat(json.get("external_id").asString).isEqualTo("12345")
        }

    @Test
    fun updateClientExternalId_acceptsStatusOnlyResponseWithoutFakeClient() =
        runTest {
            val diagnostics = mutableListOf<String>()
            val expectedRequestLog =
                "updateClientExternalId request: clientId=772337111, " +
                    "externalIdPresent=true, externalIdLength=5"

            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"success\"}"))

            val api = apiWithLogs(diagnostics::add)

            val result = api.updateClientExternalId(clientId = 772337111L, externalId = "12345")

            assertThat(result).isNull()
            assertThat(diagnostics)
                .containsAtLeast(
                    expectedRequestLog,
                    "updateClientExternalId response: status=200, bodyLength=20",
                    "public client update parsed: clientId=772337111, clientObjectPresent=false",
                )
                .inOrder()
        }

    private fun api(): DirectClientEnrichmentApi {
        return api(log = null)
    }

    private fun apiWithLogs(log: (String) -> Unit): DirectClientEnrichmentApi {
        return api(log = log)
    }

    private fun api(log: ((String) -> Unit)?): DirectClientEnrichmentApi {
        val baseUrl = server.url("/").toString().removeSuffix("/")
        val baseHost = server.url("/").host

        return DirectClientEnrichmentApi(
            config =
                Chat2DeskCommandsConfig(
                    baseUrl = baseUrl,
                    publicApiToken = "token-1",
                    sdkStartBaseUrl = baseUrl,
                    sdkWidgetToken = "widget-token",
                    requireHttps = false,
                    trustedHostSuffixes = setOf(baseHost),
                    diagnosticsEnabled = log != null,
                    diagnosticsHandler = log,
                ),
        )
    }
}
