package com.alexafanasov.chat2desk.commands

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectClientEnrichmentApiCommonTest {
    @Test
    fun resolveStartedClientParsesClientID() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val api =
                apiWithEngine(
                    requests = requests,
                    responseBody =
                        """
                            {
                              "client": {
                                "clientID": 772337111,
                                "phone": "79991112233",
                                "name": "Jane",
                                "custom_fields": {"1": "premium"}
                              }
                            }
                        """.trimIndent(),
                )

            val result = api.resolveStartedClient("client key with spaces")

            assertEquals(
                PublicClient(
                    id = 772337111,
                    phone = "79991112233",
                    name = "Jane",
                    customFields = mapOf("1" to "premium"),
                ),
                result,
            )
            assertEquals("POST", requests.single().method.value)
            assertTrue(requests.single().url.encodedPath.contains("/start"))
            assertTrue(requests.single().url.encodedQuery.contains("id=widget-token"))
            assertTrue(requests.single().url.encodedQuery.contains("client_key="))
        }

    @Test
    fun resolveStartedClientParsesClientIdSnakeCase() =
        runTest {
            val api =
                apiWithEngine(responseBody = """{"client":{"client_id":772337112}}""")

            val result = api.resolveStartedClient("client-key")

            assertEquals(772337112, result?.id)
        }

    @Test
    fun resolveStartedClientWithoutWidgetTokenSkipsRequest() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val api =
                apiWithEngine(
                    requests = requests,
                    sdkWidgetToken = null,
                    responseBody = """{"client":{"clientID":772337111}}""",
                )

            val result = api.resolveStartedClient("client-key")

            assertNull(result)
            assertTrue(requests.isEmpty())
        }

    @Test
    fun updateClientExternalIdSendsPutAndParsesObjectResponse() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val api =
                apiWithEngine(
                    requests = requests,
                    responseBody = """{"data":{"client_id":772337111,"client_phone":"79991112233"}}""",
                )

            val result = api.updateClientExternalId(clientId = 772337111, externalId = "external-1")

            val request = requests.single()
            assertEquals("PUT", request.method.value)
            assertEquals("/v1/clients/772337111", request.url.encodedPath)
            assertEquals("token-1", request.headers[HttpHeaders.Authorization])
            assertEquals("""{"external_id":"external-1"}""", request.bodyText())
            assertEquals(PublicClient(id = 772337111, phone = "79991112233"), result)
        }

    @Test
    fun updateClientExternalIdParsesArrayResponse() =
        runTest {
            val api =
                apiWithEngine(responseBody = """{"data":[{"clientID":772337111}]}""")

            val result = api.updateClientExternalId(clientId = 772337111, externalId = "external-1")

            assertEquals(772337111, result?.id)
        }

    @Test
    fun updateClientExternalIdThrowsOnHttpError() =
        runTest {
            val api =
                apiWithEngine(
                    responseBody = """{"status":"error"}""",
                    statusCode = HttpStatusCode.BadRequest,
                )

            val error =
                assertFailsWith<Chat2DeskCommandApiException> {
                    api.updateClientExternalId(clientId = 772337111, externalId = "external-1")
                }

            assertEquals(400, error.statusCode)
            assertNotNull(error.errorBody)
        }

    @Test
    fun constructorRejectsHttpWhenHttpsRequired() {
        assertFailsWith<IllegalArgumentException> {
            DirectClientEnrichmentApi(
                config =
                    config(
                        baseUrl = "http://api.chat2desk.com",
                        sdkStartBaseUrl = "http://livechatv2.chat2desk.com",
                        requireHttps = true,
                    ),
                httpClient = mockClient(),
            )
        }
    }

    @Test
    fun constructorRejectsUntrustedHost() {
        assertFailsWith<IllegalArgumentException> {
            DirectClientEnrichmentApi(
                config = config(baseUrl = "https://evil.example.org"),
                httpClient = mockClient(),
            )
        }
    }

    private fun apiWithEngine(
        requests: MutableList<HttpRequestData> = mutableListOf(),
        sdkWidgetToken: String? = "widget-token",
        responseBody: String = """{"status":"success"}""",
        statusCode: HttpStatusCode = HttpStatusCode.OK,
    ): DirectClientEnrichmentApi {
        return DirectClientEnrichmentApi(
            config = config(sdkWidgetToken = sdkWidgetToken),
            httpClient =
                HttpClient(MockEngine) {
                    engine {
                        addHandler { request ->
                            requests += request
                            respondJson(responseBody, statusCode)
                        }
                    }
                },
        )
    }

    private fun config(
        baseUrl: String = "https://api.chat2desk.com",
        sdkStartBaseUrl: String = "https://livechatv2.chat2desk.com",
        sdkWidgetToken: String? = "widget-token",
        requireHttps: Boolean = true,
    ): Chat2DeskCommandsConfig {
        return Chat2DeskCommandsConfig(
            baseUrl = baseUrl,
            publicApiToken = "token-1",
            sdkStartBaseUrl = sdkStartBaseUrl,
            sdkWidgetToken = sdkWidgetToken,
            requireHttps = requireHttps,
            trustedHostSuffixes = setOf("chat2desk.com"),
        )
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        statusCode: HttpStatusCode,
    ) =
        respond(
            content = ByteReadChannel(body),
            status = statusCode,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )

    private fun mockClient(): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler {
                    respondJson("""{"status":"success"}""", HttpStatusCode.OK)
                }
            }
        }
    }

    private suspend fun HttpRequestData.bodyText(): String {
        return when (val content = body) {
            is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
            is OutgoingContent.ReadChannelContent -> content.readFrom().readRemaining().readText()
            else -> ""
        }
    }
}
