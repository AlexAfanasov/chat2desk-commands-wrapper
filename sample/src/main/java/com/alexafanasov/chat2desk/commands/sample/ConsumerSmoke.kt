package com.alexafanasov.chat2desk.commands.sample

import com.alexafanasov.chat2desk.commands.Chat2DeskCommandsConfig
import com.alexafanasov.chat2desk.commands.InboxOptions

object ConsumerSmoke {
    fun createPayload(baseUrl: String, apiToken: String): Pair<Chat2DeskCommandsConfig, InboxOptions> {
        val config =
            Chat2DeskCommandsConfig(
                baseUrl = baseUrl,
                apiToken = apiToken,
            )
        val options = InboxOptions(externalId = "consumer-smoke")
        return config to options
    }
}
