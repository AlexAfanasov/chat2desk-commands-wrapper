package com.alexafanasov.chat2desk.commands.sample

import com.alexafanasov.chat2desk.commands.Chat2DeskCommandsConfig
import com.alexafanasov.chat2desk.commands.InboxOptions

object ConsumerSmoke {
    fun createPayload(
        baseUrl: String,
        publicApiToken: String,
    ): Pair<Chat2DeskCommandsConfig, InboxOptions> {
        val config =
            Chat2DeskCommandsConfig(
                baseUrl = baseUrl,
                apiToken = publicApiToken,
            )
        val options = InboxOptions(externalId = "consumer-smoke")
        return config to options
    }
}
