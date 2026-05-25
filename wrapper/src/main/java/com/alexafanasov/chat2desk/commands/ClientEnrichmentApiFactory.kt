package com.alexafanasov.chat2desk.commands

import com.chat2desk.chat2desk_sdk.IChat2Desk
import com.google.gson.Gson
import okhttp3.OkHttpClient

object ClientEnrichmentApiFactory {
    fun createDirect(
        config: Chat2DeskCommandsConfig,
        httpClient: OkHttpClient? = null,
        gson: Gson = Gson(),
    ): Chat2DeskClientEnrichmentApi {
        return DirectClientEnrichmentApi(
            config = config,
            httpClient = httpClient,
            gson = gson,
        )
    }
}

object CommandChat2DeskFactory {
    fun create(
        delegate: IChat2Desk,
        config: Chat2DeskCommandsConfig,
        httpClient: OkHttpClient? = null,
        gson: Gson = Gson(),
    ): ICommandChat2Desk {
        val clientEnrichmentApi =
            ClientEnrichmentApiFactory.createDirect(
                config = config,
                httpClient = httpClient,
                gson = gson,
            )

        return CommandChat2Desk(delegate = delegate, clientEnrichmentApi = clientEnrichmentApi, config = config)
    }
}
