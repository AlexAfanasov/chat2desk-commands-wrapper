package com.alexafanasov.chat2desk.commands

import com.chat2desk.chat2desk_sdk.IChat2Desk
import com.google.gson.Gson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient

actual object ClientEnrichmentApiFactory {
    actual fun createDirect(config: Chat2DeskCommandsConfig): Chat2DeskClientEnrichmentApi {
        return createDirect(config = config, httpClient = null as HttpClient?)
    }

    actual fun createDirect(
        config: Chat2DeskCommandsConfig,
        httpClient: HttpClient?,
    ): Chat2DeskClientEnrichmentApi {
        return DirectClientEnrichmentApi(
            config = config,
            httpClient = httpClient,
        )
    }

    @Deprecated(
        message = "Use createDirect(config, httpClient: io.ktor.client.HttpClient?) instead. " +
            "The OkHttpClient parameter is adapted through Ktor OkHttp engine on Android; Gson is ignored.",
        replaceWith = ReplaceWith("createDirect(config = config, httpClient = null)"),
    )
    fun createDirect(
        config: Chat2DeskCommandsConfig,
        httpClient: OkHttpClient? = null,
        gson: Gson = Gson(),
    ): Chat2DeskClientEnrichmentApi {
        @Suppress("UNUSED_VARIABLE")
        val ignoredGson = gson
        return createDirect(config = config, httpClient = httpClient.toKtorHttpClient())
    }
}

actual object CommandChat2DeskFactory {
    actual fun create(
        delegate: IChat2Desk,
        config: Chat2DeskCommandsConfig,
    ): ICommandChat2Desk {
        return create(delegate = delegate, config = config, httpClient = null as HttpClient?)
    }

    actual fun create(
        delegate: IChat2Desk,
        config: Chat2DeskCommandsConfig,
        httpClient: HttpClient?,
    ): ICommandChat2Desk {
        val clientEnrichmentApi =
            ClientEnrichmentApiFactory.createDirect(
                config = config,
                httpClient = httpClient,
            )

        return CommandChat2Desk(delegate = delegate, clientEnrichmentApi = clientEnrichmentApi, config = config)
    }

    @Deprecated(
        message = "Use create(delegate, config, httpClient: io.ktor.client.HttpClient?) instead. " +
            "The OkHttpClient parameter is adapted through Ktor OkHttp engine on Android; Gson is ignored.",
        replaceWith = ReplaceWith("create(delegate = delegate, config = config, httpClient = null)"),
    )
    fun create(
        delegate: IChat2Desk,
        config: Chat2DeskCommandsConfig,
        httpClient: OkHttpClient? = null,
        gson: Gson = Gson(),
    ): ICommandChat2Desk {
        @Suppress("UNUSED_VARIABLE")
        val ignoredGson = gson
        return create(delegate = delegate, config = config, httpClient = httpClient.toKtorHttpClient())
    }
}

private fun OkHttpClient?.toKtorHttpClient(): HttpClient? {
    return this?.let { preconfiguredOkHttpClient ->
        HttpClient(OkHttp) {
            engine {
                preconfigured = preconfiguredOkHttpClient
            }
        }
    }
}
