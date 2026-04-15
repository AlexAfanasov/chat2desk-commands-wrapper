package com.alexafanasov.chat2desk.commands

import com.chat2desk.chat2desk_sdk.domain.entities.Button
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CommandChat2DeskDelegationTest {
    @Test
    fun sendButton_prefersPayloadAndFallsBackToText() =
        runTest {
            val delegate = RecordingDelegate()
            val commandApi = RecordingCommandApi()
            val wrapper = CommandChat2Desk(delegate = delegate, commands = commandApi)

            wrapper.sendButton(
                button = Button(type = "reply", text = "Text-1", payload = "Payload-1"),
                clientId = "100",
            )
            wrapper.sendButton(
                button = Button(type = "reply", text = "Text-2", payload = ""),
                clientId = "100",
            )

            assertThat(commandApi.inboxCommands[0].first).isEqualTo("Payload-1")
            assertThat(commandApi.inboxCommands[1].first).isEqualTo("Text-2")
        }

    @Test
    fun delegatesStandardIChat2DeskCalls() =
        runTest {
            val delegate = RecordingDelegate()
            val wrapper = CommandChat2Desk(delegate = delegate, commands = RecordingCommandApi())

            val startResult = wrapper.start("client-abc")
            wrapper.sendMessage("hello")

            assertThat(startResult).isEqualTo("client-abc")
            assertThat(delegate.startWithClientCalls).containsExactly("client-abc")
            assertThat(delegate.sentMessages).containsExactly("hello")
        }
}
