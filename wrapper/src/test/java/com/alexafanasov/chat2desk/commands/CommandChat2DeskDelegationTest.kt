package com.alexafanasov.chat2desk.commands

import com.chat2desk.chat2desk_sdk.AttachedFile
import com.chat2desk.chat2desk_sdk.domain.entities.Button
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

class CommandChat2DeskDelegationTest {
    @Test
    fun sendButton_prefersPayloadAndFallsBackToTextAndUsesResolver() =
        runTest {
            // given
            val delegate = RecordingDelegate()
            val commandApi = RecordingCommandApi()
            val wrapper =
                CommandChat2Desk(
                    delegate = delegate,
                    commands = commandApi,
                    config =
                        config(
                            externalIdResolver = { context -> "ext-${context.text}" },
                        ),
                )

            // when
            wrapper.sendButton(
                button = Button(type = "reply", text = "Text-1", payload = "Payload-1"),
                clientId = "100",
            )
            wrapper.sendButton(
                button = Button(type = "reply", text = "Text-2", payload = ""),
                clientId = "100",
            )

            // then
            assertThat(commandApi.inboxCommands[0].command).isEqualTo("Payload-1")
            assertThat(commandApi.inboxCommands[0].options.externalId).isEqualTo("ext-Payload-1")
            assertThat(commandApi.inboxCommands[1].command).isEqualTo("Text-2")
            assertThat(commandApi.inboxCommands[1].options.externalId).isEqualTo("ext-Text-2")
        }

    @Test
    fun delegatesStandardIChat2DeskCallsWhenRoutingDisabled() =
        runTest {
            // given
            val delegate = RecordingDelegate()
            val commandApi = RecordingCommandApi()
            val wrapper = CommandChat2Desk(delegate = delegate, commands = commandApi, config = config())

            // when
            val startResult = wrapper.start("client-abc")
            wrapper.sendMessage("hello")

            // then
            assertThat(startResult).isEqualTo("client-abc")
            assertThat(delegate.startWithClientCalls).containsExactly("client-abc")
            assertThat(delegate.sentMessages).containsExactly("hello")
            assertThat(commandApi.inboxCommands).isEmpty()
        }

    @Test
    fun routedSendMessage_usesDelegateClientPhoneAndExternalResolver() =
        runTest {
            // given
            val delegate = RecordingDelegate().apply { clientPhone = "client-1" }
            val commandApi = RecordingCommandApi()
            val wrapper =
                CommandChat2Desk(
                    delegate = delegate,
                    commands = commandApi,
                    config =
                        config(
                            routeSdkSendMessageViaInboxApi = true,
                            externalIdResolver = { context -> "ext-${context.clientId}" },
                        ),
                )

            // when
            wrapper.sendMessage("hello")

            // then
            assertThat(delegate.sentMessages).isEmpty()
            assertThat(commandApi.inboxCommands).hasSize(1)
            assertThat(commandApi.inboxCommands.first().command).isEqualTo("hello")
            assertThat(commandApi.inboxCommands.first().clientId).isEqualTo("client-1")
            assertThat(commandApi.inboxCommands.first().options.externalId).isEqualTo("ext-client-1")
        }

    @Test
    fun routedSendMessage_usesCachedStartClientIdWhenDelegateClientPhoneMissing() =
        runTest {
            // given
            val delegate = RecordingDelegate()
            val commandApi = RecordingCommandApi()
            val wrapper =
                CommandChat2Desk(
                    delegate = delegate,
                    commands = commandApi,
                    config = config(routeSdkSendMessageViaInboxApi = true),
                )

            // when
            wrapper.start("client-abc")
            wrapper.sendMessage("hello")

            // then
            assertThat(commandApi.inboxCommands).hasSize(1)
            assertThat(commandApi.inboxCommands.first().clientId).isEqualTo("client-abc")
        }

    @Test
    fun routedSendMessage_failsFastWhenClientIdMissing() =
        runTest {
            // given
            val delegate = RecordingDelegate()
            val commandApi = RecordingCommandApi()
            val wrapper =
                CommandChat2Desk(
                    delegate = delegate,
                    commands = commandApi,
                    config = config(routeSdkSendMessageViaInboxApi = true),
                )

            // when
            val thrown =
                try {
                    wrapper.sendMessage("hello")
                    null
                } catch (e: Chat2DeskCommandRoutingException) {
                    e
                }

            // then
            assertThat(thrown).isNotNull()
            assertThat(delegate.sentMessages).isEmpty()
            assertThat(commandApi.inboxCommands).isEmpty()
        }

    @Test
    fun routedSendMessageWithAttachment_uploadsRoutesAndDeletesLocalFile() =
        runTest {
            // given
            val temporaryFile = File.createTempFile("chat2desk-wrapper-test", ".txt")
            temporaryFile.writeText("hello")
            val temporaryParent =
                checkNotNull(temporaryFile.parentFile) { "Temporary file parent directory is missing" }

            try {
                val attachedFile =
                    createAttachedFile(
                        file = temporaryFile,
                        originalName = "photo.jpg",
                        mimeType = "image/jpeg",
                    )
                val delegate = RecordingDelegate().apply { clientPhone = "client-42" }
                val commandApi =
                    RecordingCommandApi().apply {
                        uploadAttachmentResult =
                            InboxAttachment(
                                url = "https://cdn.example.com/photo.jpg",
                                filename = "photo.jpg",
                            )
                    }
                val wrapper =
                    CommandChat2Desk(
                        delegate = delegate,
                        commands = commandApi,
                        config =
                            config(
                                routeSdkSendMessageViaInboxApi = true,
                                externalIdResolver = { "ext-attach" },
                                deleteUploadedAttachmentOnSuccess = true,
                                safeDeleteRoots = setOf(temporaryParent.canonicalPath),
                            ),
                    )

                // when
                wrapper.sendMessage("with attachment", attachedFile)

                // then
                assertThat(commandApi.uploadedAttachments).hasSize(1)
                assertThat(commandApi.inboxCommands).hasSize(1)
                val recorded = commandApi.inboxCommands.first()
                assertThat(recorded.clientId).isEqualTo("client-42")
                assertThat(recorded.options.externalId).isEqualTo("ext-attach")
                assertThat(recorded.options.attachments).hasSize(1)
                assertThat(recorded.options.attachments.first().url).isEqualTo("https://cdn.example.com/photo.jpg")
                assertThat(temporaryFile.exists()).isFalse()
                assertThat(delegate.sentMessagesWithAttachment).isEmpty()
            } finally {
                if (temporaryFile.exists()) {
                    temporaryFile.delete()
                }
            }
        }

    @Test
    fun routedSendMessageWithAttachment_keepsFileByDefault() =
        runTest {
            // given
            val temporaryFile = File.createTempFile("chat2desk-wrapper-test", ".txt")
            temporaryFile.writeText("hello")

            try {
                val attachedFile =
                    createAttachedFile(
                        file = temporaryFile,
                        originalName = "photo.jpg",
                        mimeType = "image/jpeg",
                    )
                val delegate = RecordingDelegate().apply { clientPhone = "client-42" }
                val commandApi = RecordingCommandApi()
                val wrapper =
                    CommandChat2Desk(
                        delegate = delegate,
                        commands = commandApi,
                        config = config(routeSdkSendMessageViaInboxApi = true),
                    )

                // when
                wrapper.sendMessage("with attachment", attachedFile)

                // then
                assertThat(commandApi.uploadedAttachments).hasSize(1)
                assertThat(temporaryFile.exists()).isTrue()
            } finally {
                if (temporaryFile.exists()) {
                    temporaryFile.delete()
                }
            }
        }

    @Test
    fun routedSendMessageWithAttachment_keepsFileOutsideSafeDeleteRoots() =
        runTest {
            // given
            val temporaryFile = File.createTempFile("chat2desk-wrapper-test", ".txt")
            temporaryFile.writeText("hello")
            val temporaryParent =
                checkNotNull(temporaryFile.parentFile) { "Temporary file parent directory is missing" }
            val unrelatedRoot = File(temporaryParent, "not-safe-root").absolutePath

            try {
                val attachedFile =
                    createAttachedFile(
                        file = temporaryFile,
                        originalName = "photo.jpg",
                        mimeType = "image/jpeg",
                    )
                val delegate = RecordingDelegate().apply { clientPhone = "client-42" }
                val commandApi = RecordingCommandApi()
                val wrapper =
                    CommandChat2Desk(
                        delegate = delegate,
                        commands = commandApi,
                        config =
                            config(
                                routeSdkSendMessageViaInboxApi = true,
                                deleteUploadedAttachmentOnSuccess = true,
                                safeDeleteRoots = setOf(unrelatedRoot),
                            ),
                    )

                // when
                wrapper.sendMessage("with attachment", attachedFile)

                // then
                assertThat(commandApi.uploadedAttachments).hasSize(1)
                assertThat(temporaryFile.exists()).isTrue()
            } finally {
                if (temporaryFile.exists()) {
                    temporaryFile.delete()
                }
            }
        }

    private fun config(
        routeSdkSendMessageViaInboxApi: Boolean = false,
        externalIdResolver: ((RoutedMessageContext) -> String?)? = null,
        deleteUploadedAttachmentOnSuccess: Boolean = false,
        safeDeleteRoots: Set<String> = emptySet(),
    ): Chat2DeskCommandsConfig {
        return Chat2DeskCommandsConfig(
            baseUrl = "https://api.chat2desk.com",
            apiToken = "token-1",
            routeSdkSendMessageViaInboxApi = routeSdkSendMessageViaInboxApi,
            externalIdResolver = externalIdResolver,
            deleteUploadedAttachmentOnSuccess = deleteUploadedAttachmentOnSuccess,
            safeDeleteRoots = safeDeleteRoots,
        )
    }

    private fun createAttachedFile(
        file: File,
        originalName: String,
        mimeType: String,
    ): AttachedFile {
        val constructor =
            AttachedFile::class.java.getDeclaredConstructor(
                File::class.java,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
            )
        constructor.isAccessible = true
        return constructor.newInstance(file, originalName, mimeType, file.length().toInt())
    }
}
