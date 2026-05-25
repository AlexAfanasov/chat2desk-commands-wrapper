package com.alexafanasov.chat2desk.commands.sample

import com.alexafanasov.chat2desk.commands.Chat2DeskCommandsConfig

object ConsumerSmoke {
    fun createPayload(
        baseUrl: String,
        publicApiToken: String,
    ): Chat2DeskCommandsConfig {
        return Chat2DeskCommandsConfig(
            baseUrl,
            publicApiToken,
        )
    }
}
