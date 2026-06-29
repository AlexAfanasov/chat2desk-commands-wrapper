package com.alexafanasov.chat2desk.commands

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class CommandChat2DeskFactoryAndroidCompatibilityTest {
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

    @Suppress("DEPRECATION")
    @Test
    fun deprecatedOkHttpGsonOverloadAdaptsOkHttpClientIntoKtorTransport() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"client":{"clientID":772337111}}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"success"}"""))

            val delegate = RecordingDelegate()
            val okHttpClient =
                OkHttpClient.Builder()
                    .addInterceptor(markerHeaderInterceptor())
                    .build()
            val wrapper =
                CommandChat2DeskFactory.create(
                    delegate = delegate,
                    config =
                        config(
                            baseUrl = server.url("/").toString().removeSuffix("/"),
                            trustedHost = server.url("/").host,
                        ),
                    httpClient = okHttpClient,
                    gson = Gson(),
                )

            wrapper.start("sdk-client-key")
            wrapper.sendClientParams(name = "Jane", phone = "79991112233", fieldSet = mapOf(1 to "external-1"))

            val startRequest = server.takeRequest()
            val updateRequest = server.takeRequest()

            assertThat(wrapper).isInstanceOf(ICommandChat2Desk::class.java)
            assertThat(delegate.sentClientParams).containsExactly(
                ClientExternalIdContext(name = "Jane", phone = "79991112233", fieldSet = mapOf(1 to "external-1")),
            )
            assertThat(startRequest.getHeader(MARKER_HEADER)).isEqualTo(MARKER_VALUE)
            assertThat(updateRequest.getHeader(MARKER_HEADER)).isEqualTo(MARKER_VALUE)
            assertThat(updateRequest.path).isEqualTo("/v1/clients/772337111")
        }

    private fun markerHeaderInterceptor(): Interceptor {
        return Interceptor { chain ->
            chain.proceed(
                chain.request()
                    .newBuilder()
                    .addHeader(MARKER_HEADER, MARKER_VALUE)
                    .build(),
            )
        }
    }

    private fun config(
        baseUrl: String,
        trustedHost: String,
    ): Chat2DeskCommandsConfig {
        return Chat2DeskCommandsConfig(
            baseUrl = baseUrl,
            publicApiToken = "token-1",
            sdkStartBaseUrl = baseUrl,
            sdkWidgetToken = "widget-token",
            clientExternalIdResolver = { context -> context.fieldSet[1] },
            requireHttps = false,
            trustedHostSuffixes = setOf(trustedHost),
        )
    }

    private companion object {
        const val MARKER_HEADER = "X-Test-OkHttp-Bridge"
        const val MARKER_VALUE = "true"
    }
}
