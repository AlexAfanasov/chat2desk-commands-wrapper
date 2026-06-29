package com.alexafanasov.chat2desk.commands

import com.chat2desk.chat2desk_sdk.IChat2Desk
import io.ktor.client.HttpClient

actual object ClientEnrichmentApiFactory {
    actual fun createDirect(config: Chat2DeskCommandsConfig): Chat2DeskClientEnrichmentApi {
        return createDirect(config = config, httpClient = null)
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
}

actual object CommandChat2DeskFactory {
    actual fun create(
        delegate: IChat2Desk,
        config: Chat2DeskCommandsConfig,
    ): ICommandChat2Desk {
        return create(delegate = delegate, config = config, httpClient = null)
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
}
