package com.alexafanasov.chat2desk.commands

import com.chat2desk.chat2desk_sdk.IChat2Desk
import io.ktor.client.HttpClient

expect object ClientEnrichmentApiFactory {
    fun createDirect(config: Chat2DeskCommandsConfig): Chat2DeskClientEnrichmentApi

    fun createDirect(
        config: Chat2DeskCommandsConfig,
        httpClient: HttpClient?,
    ): Chat2DeskClientEnrichmentApi
}

expect object CommandChat2DeskFactory {
    fun create(
        delegate: IChat2Desk,
        config: Chat2DeskCommandsConfig,
    ): ICommandChat2Desk

    fun create(
        delegate: IChat2Desk,
        config: Chat2DeskCommandsConfig,
        httpClient: HttpClient?,
    ): ICommandChat2Desk
}
