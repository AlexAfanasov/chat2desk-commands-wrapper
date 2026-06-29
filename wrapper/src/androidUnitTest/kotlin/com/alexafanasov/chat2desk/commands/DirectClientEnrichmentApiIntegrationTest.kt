package com.alexafanasov.chat2desk.commands

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class DirectClientEnrichmentApiIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var api: DirectClientEnrichmentApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/").toString().removeSuffix("/")
        val baseHost = server.url("/").host

        api =
            DirectClientEnrichmentApi(
                config =
                    Chat2DeskCommandsConfig(
                        baseUrl = baseUrl,
                        publicApiToken = "token-1",
                        sdkStartBaseUrl = baseUrl,
                        sdkWidgetToken = "widget-token",
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
    fun updateClientExternalId_throwsOnHttpError() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"status\":\"error\"}"))

            val error =
                try {
                    api.updateClientExternalId(clientId = 772337111L, externalId = "12345")
                    null
                } catch (e: Chat2DeskCommandApiException) {
                    e
                }

            assertThat(error).isNotNull()
            assertThat(error!!.statusCode).isEqualTo(400)
        }

    @Test
    fun constructor_rejectsHttpBaseUrlWhenHttpsRequired() {
        val error =
            try {
                DirectClientEnrichmentApi(
                    config =
                        Chat2DeskCommandsConfig(
                            baseUrl = "http://localhost:8080",
                            publicApiToken = "token-1",
                            sdkStartBaseUrl = "http://localhost:8080",
                            requireHttps = true,
                            trustedHostSuffixes = setOf("localhost"),
                        ),
                )
                null
            } catch (e: IllegalArgumentException) {
                e
            }

        assertThat(error).isNotNull()
    }

    @Test
    fun constructor_rejectsUntrustedHost() {
        val error =
            try {
                DirectClientEnrichmentApi(
                    config =
                        Chat2DeskCommandsConfig(
                            baseUrl = "https://evil.example.org",
                            publicApiToken = "token-1",
                            trustedHostSuffixes = setOf("chat2desk.com"),
                        ),
                )
                null
            } catch (e: IllegalArgumentException) {
                e
            }

        assertThat(error).isNotNull()
    }
}
