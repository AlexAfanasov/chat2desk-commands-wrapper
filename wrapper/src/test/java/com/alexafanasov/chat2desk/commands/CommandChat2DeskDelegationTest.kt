package com.alexafanasov.chat2desk.commands

import com.chat2desk.chat2desk_sdk.AttachedFile
import com.chat2desk.chat2desk_sdk.domain.entities.Button
import com.chat2desk.chat2desk_sdk.domain.entities.DeliveryStatus
import com.chat2desk.chat2desk_sdk.domain.entities.Message
import com.chat2desk.chat2desk_sdk.domain.entities.MessageType
import com.chat2desk.chat2desk_sdk.domain.entities.ReadStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlinx.datetime.Instant
import org.junit.Test

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
    fun routedSendMessage_usesPublicClientIdDefaultFromClientAndExternalResolver() =
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
            wrapper.sendClientParams(name = "Master", phone = "79991112233", fieldSet = emptyMap())
            wrapper.sendMessage("hello")

            // then
            assertThat(delegate.sentMessages).isEmpty()
            assertThat(commandApi.inboxCommands).hasSize(1)
            assertThat(commandApi.inboxCommands.first().command).isEqualTo("hello")
            assertThat(commandApi.inboxCommands.first().clientId).isEqualTo("146339237")
            assertThat(commandApi.inboxCommands.first().options.externalId).isEqualTo("ext-79991112233")
            assertThat(commandApi.inboxCommands.first().options.fromClient).isEqualTo(InboxClient(phone = "79991112233"))
            assertThat(wrapper.messages.value).hasSize(1)
            assertThat(wrapper.messages.value.first().id).startsWith("local-")
        }

    @Test
    fun routedSendMessage_usesConfiguredFromClient() =
        runTest {
            // given
            val delegate = RecordingDelegate().apply { clientPhone = "sdk-client-key" }
            val commandApi = RecordingCommandApi()
            val wrapper =
                CommandChat2Desk(
                    delegate = delegate,
                    commands = commandApi,
                    config =
                        config(
                            routeSdkSendMessageViaInboxApi = true,
                            fromClientResolver = { InboxClient(phone = "79991112233") },
                        ),
            )

            // when
            wrapper.sendClientParams(name = "Master", phone = "79991112233", fieldSet = emptyMap())
            wrapper.sendMessage("hello")

            // then
            val recorded = commandApi.inboxCommands.first()
            assertThat(recorded.clientId).isEqualTo("146339237")
            assertThat(recorded.options.fromClient).isEqualTo(InboxClient(phone = "79991112233"))
        }

    @Test
    fun routedFetchNewMessages_loadsPublicApiMessages() =
        runTest {
            // given
            val delegate = RecordingDelegate().apply { clientPhone = "sdk-client-key" }
            val commandApi =
                RecordingCommandApi().apply {
                    loadMessagesResult =
                        listOf(
                            Message(
                                id = "m1",
                                realId = 1,
                                read = ReadStatus.UNREAD,
                                status = DeliveryStatus.DELIVERED,
                                text = "hello",
                                type = MessageType.IN,
                                date = Instant.fromEpochSeconds(10),
                            ),
                        )
                }
            val wrapper =
                CommandChat2Desk(
                    delegate = delegate,
                    commands = commandApi,
                    config =
                        config(
                            routeSdkSendMessageViaInboxApi = true,
                            messagesPollingIntervalMs = 0,
                        ),
                )

            // when
            wrapper.sendClientParams(name = "Master", phone = "79991112233", fieldSet = mapOf(4 to "2008484"))
            wrapper.fetchNewMessages()

            // then
            assertThat(commandApi.loadedMessages).hasSize(2)
            assertThat(commandApi.loadedMessages.last().clientId).isEqualTo(146339237)
            assertThat(commandApi.loadedMessages.last().transport).isEqualTo("external")
            assertThat(wrapper.messages.value).hasSize(1)
            assertThat(wrapper.messages.value.first().text).isEqualTo("hello")
        }

    @Test
    fun routedReadAndDeliveryAreNoOps() =
        runTest {
            // given
            val delegate = RecordingDelegate().apply { clientPhone = "sdk-client-key" }
            val wrapper =
                CommandChat2Desk(
                    delegate = delegate,
                    commands = RecordingCommandApi(),
                    config =
                        config(
                            routeSdkSendMessageViaInboxApi = true,
                            messagesPollingIntervalMs = 0,
                        ),
                )

            // when
            wrapper.read()
            wrapper.delivery()
            wrapper.delivery("message-id")

            // then
            assertThat(delegate.readCalls).isEqualTo(0)
            assertThat(delegate.deliveryCalls).isEqualTo(0)
            assertThat(delegate.deliveryIdCalls).isEmpty()
        }

    @Test
    fun routedSendMessage_doesNotReplaceOptimisticMessageByExternalId() =
        runTest {
            // given
            val delegate = RecordingDelegate().apply { clientPhone = "sdk-client-key" }
            val commandApi =
                RecordingCommandApi().apply {
                    loadMessagesResult =
                        listOf(
                            Message(
                                id = "ext-hello",
                                realId = 99,
                                read = ReadStatus.UNREAD,
                                status = DeliveryStatus.DELIVERED,
                                text = "hello from server",
                                type = MessageType.IN,
                                date = Instant.fromEpochSeconds(10),
                            ),
                        )
                }
            val wrapper =
                CommandChat2Desk(
                    delegate = delegate,
                    commands = commandApi,
                    config =
                        config(
                            routeSdkSendMessageViaInboxApi = true,
                            messagesPollingIntervalMs = 0,
                            externalIdResolver = { "ext-hello" },
                        ),
                )

            // when
            wrapper.sendClientParams(name = "Master", phone = "79991112233", fieldSet = mapOf(4 to "2008484"))
            wrapper.sendMessage("hello")

            // then
            val sent = commandApi.inboxCommands.first()
            assertThat(sent.options.fromClient).isEqualTo(InboxClient(phone = "79991112233"))
            assertThat(sent.options.customFields).containsEntry("4", "2008484")
            assertThat(commandApi.loadedMessages).hasSize(2)
            assertThat(wrapper.messages.value).hasSize(2)
            assertThat(wrapper.messages.value.map { it.id }).contains("ext-hello")
            assertThat(wrapper.messages.value.any { it.id.startsWith("local-") }).isTrue()
            assertThat(wrapper.messages.value.first { it.id == "ext-hello" }.realId).isEqualTo(99)
            assertThat(wrapper.messages.value.first { it.id == "ext-hello" }.text).isEqualTo("hello from server")
        }

    @Test
    fun routedSendMessage_resolvesExternalChannelWhenDefaultChannelIdIsNull() =
        runTest {
            // given
            val commandApi = RecordingCommandApi()
            val wrapper =
                CommandChat2Desk(
                    delegate = RecordingDelegate(),
                    commands = commandApi,
                    config =
                        config(
                            routeSdkSendMessageViaInboxApi = true,
                            messagesPollingIntervalMs = 0,
                        ),
                )

            // when
            wrapper.sendClientParams(name = "Master", phone = "79991112233", fieldSet = emptyMap())
            wrapper.sendMessage("hello")

            // then
            assertThat(commandApi.loadedChannelsCalls).isEqualTo(1)
            assertThat(commandApi.inboxCommands.first().options.channelId).isEqualTo(121177)
            assertThat(commandApi.loadedMessages.last().channelId).isEqualTo(121177)
        }

    @Test
    fun routedSendMessage_failsWhenExternalChannelResolutionIsAmbiguous() =
        runTest {
            // given
            var channelFailure: String? = null
            val commandApi =
                RecordingCommandApi().apply {
                    loadChannelsResult =
                        listOf(
                            PublicChannel(id = 121177, transport = "external"),
                            PublicChannel(id = 121178, transport = "external"),
                        )
                }
            val wrapper =
                CommandChat2Desk(
                    delegate = RecordingDelegate(),
                    commands = commandApi,
                    config =
                        config(
                            routeSdkSendMessageViaInboxApi = true,
                            messagesPollingIntervalMs = 0,
                            sdkActiveChannelFailureHandler = { channelFailure = it },
                        ),
                )

            // when
            wrapper.sendClientParams(name = "Master", phone = "79991112233", fieldSet = emptyMap())
            val thrown =
                try {
                    wrapper.sendMessage("hello")
                    null
                } catch (e: Chat2DeskCommandRoutingException) {
                    e
                }

            // then
            assertThat(thrown).isNotNull()
            assertThat(thrown!!.message).contains("ambiguous")
            assertThat(channelFailure).contains("ambiguous")
            assertThat(wrapper.error.value).isSameInstanceAs(thrown)
            assertThat(commandApi.inboxCommands).isEmpty()
        }

    @Test
    fun routedStart_doesNotCallDelegateInPublicApiMode() =
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
            val startedClient = wrapper.start("client-abc")

            // then
            assertThat(startedClient).isEqualTo("client-abc")
            assertThat(delegate.startWithClientCalls).isEmpty()
            assertThat(commandApi.inboxCommands).isEmpty()
        }

    @Test
    fun routedSendClientParams_resolvesClientByPhoneAndInitialFetches() =
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
            val startResult = wrapper.start()
            wrapper.sendClientParams(name = "Master", phone = "79991112233", fieldSet = mapOf(4 to "2008484"))

            // then
            assertThat(startResult).isNull()
            assertThat(delegate.noArgStartCalls).isEqualTo(0)
            assertThat(commandApi.lookedUpClients).containsExactly(PublicClientLookupRequest(phone = "79991112233"))
            assertThat(commandApi.loadedMessages.first().clientId).isEqualTo(146339237)
        }

    @Test
    fun routedSendClientParams_createsClientOnlyWhenAllowed() =
        runTest {
            // given
            val commandApi =
                RecordingCommandApi().apply {
                    findClientResult = null
                    createClientResult = PublicClient(id = 146339999, phone = "79991112233")
                }
            val wrapper =
                CommandChat2Desk(
                    delegate = RecordingDelegate(),
                    commands = commandApi,
                    config =
                        config(
                            routeSdkSendMessageViaInboxApi = true,
                            allowCreatePublicClient = true,
                        ),
                )

            // when
            wrapper.sendClientParams(name = "Master", phone = "79991112233", fieldSet = mapOf(4 to "2008484"))

            // then
            assertThat(commandApi.createdClients).containsExactly(
                PublicClientCreateRequest(
                    name = "Master",
                    phone = "79991112233",
                    customFields = mapOf("4" to "2008484"),
                ),
            )
            assertThat(commandApi.loadedMessages.first().clientId).isEqualTo(146339999)
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
    fun routedSendMessage_swallowsApiFailureByDefault() =
        runTest {
            // given
            var handledError: Chat2DeskCommandApiException? = null
            val delegate = RecordingDelegate()
            val apiError =
                Chat2DeskCommandApiException(
                    message = "bad request",
                    statusCode = 400,
                    errorBody = "{\"message\":\"bad client_id\"}",
                )
            val commandApi =
                RecordingCommandApi().apply {
                    sendInboxCommandError = apiError
                }
            val wrapper =
                CommandChat2Desk(
                    delegate = delegate,
                    commands = commandApi,
                    config =
                        config(
                            routeSdkSendMessageViaInboxApi = true,
                            routedSendFailureHandler = { handledError = it },
                        ),
                )

            // when
            wrapper.sendClientParams(name = "Master", phone = "79991112233", fieldSet = emptyMap())
            wrapper.sendMessage("hello")

            // then
            assertThat(delegate.sentMessages).isEmpty()
            assertThat(commandApi.inboxCommands).isEmpty()
            assertThat(handledError).isSameInstanceAs(apiError)
            assertThat(wrapper.messages.value).isEmpty()
        }

    @Test
    fun routedSendMessage_throwsApiFailureWhenConfigured() =
        runTest {
            // given
            val apiError =
                Chat2DeskCommandApiException(
                    message = "bad request",
                    statusCode = 400,
                    errorBody = "{\"message\":\"bad client_id\"}",
                )
            val delegate = RecordingDelegate()
            val commandApi =
                RecordingCommandApi().apply {
                    sendInboxCommandError = apiError
                }
            val wrapper =
                CommandChat2Desk(
                    delegate = delegate,
                    commands = commandApi,
                    config =
                        config(
                            routeSdkSendMessageViaInboxApi = true,
                            routedSendFailureMode = RoutedSendFailureMode.THROW,
                        ),
                )

            // when
            wrapper.sendClientParams(name = "Master", phone = "79991112233", fieldSet = emptyMap())
            val thrown =
                try {
                    wrapper.sendMessage("hello")
                    null
                } catch (e: Chat2DeskCommandApiException) {
                    e
                }

            // then
            assertThat(thrown).isSameInstanceAs(apiError)
            assertThat(wrapper.messages.value).isEmpty()
        }

    @Test
    fun directSendInboxCommand_stillThrowsApiFailure() =
        runTest {
            // given
            val apiError =
                Chat2DeskCommandApiException(
                    message = "bad request",
                    statusCode = 400,
                    errorBody = "{\"message\":\"bad client_id\"}",
                )
            val delegate = RecordingDelegate().apply { clientPhone = "client-1" }
            val commandApi =
                RecordingCommandApi().apply {
                    sendInboxCommandError = apiError
                }
            val wrapper =
                CommandChat2Desk(
                    delegate = delegate,
                    commands = commandApi,
                    config = config(routeSdkSendMessageViaInboxApi = true),
                )

            // when
            val thrown =
                try {
                    wrapper.sendInboxCommand(command = "hello", clientId = "client-1")
                    null
                } catch (e: Chat2DeskCommandApiException) {
                    e
                }

            // then
            assertThat(thrown).isSameInstanceAs(apiError)
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
                wrapper.sendClientParams(name = "Master", phone = "79991112233", fieldSet = emptyMap())
                wrapper.sendMessage("with attachment", attachedFile)

                // then
                assertThat(commandApi.uploadedAttachments).hasSize(1)
                assertThat(commandApi.inboxCommands).hasSize(1)
                val recorded = commandApi.inboxCommands.first()
                assertThat(recorded.clientId).isEqualTo("146339237")
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
                val delegate = RecordingDelegate()
                val commandApi = RecordingCommandApi()
                val wrapper =
                    CommandChat2Desk(
                        delegate = delegate,
                        commands = commandApi,
                        config = config(routeSdkSendMessageViaInboxApi = true),
                    )

                // when
                wrapper.sendClientParams(name = "Master", phone = "79991112233", fieldSet = emptyMap())
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
                val delegate = RecordingDelegate()
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
                wrapper.sendClientParams(name = "Master", phone = "79991112233", fieldSet = emptyMap())
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
        routedSendFailureMode: RoutedSendFailureMode = RoutedSendFailureMode.SWALLOW,
        routedSendFailureHandler: ((Chat2DeskCommandApiException) -> Unit)? = null,
        externalIdResolver: ((RoutedMessageContext) -> String?)? = null,
        fromClientResolver: ((RoutedMessageContext) -> InboxClient?)? = null,
        deleteUploadedAttachmentOnSuccess: Boolean = false,
        safeDeleteRoots: Set<String> = emptySet(),
        messagesPollingIntervalMs: Long = 0,
        allowCreatePublicClient: Boolean = false,
        sdkActiveChannelFailureHandler: ((String) -> Unit)? = null,
    ): Chat2DeskCommandsConfig {
        return Chat2DeskCommandsConfig(
            baseUrl = "https://api.chat2desk.com",
            apiToken = "public-api-token-1",
            routeSdkSendMessageViaInboxApi = routeSdkSendMessageViaInboxApi,
            routedSendFailureMode = routedSendFailureMode,
            routedSendFailureHandler = routedSendFailureHandler,
            externalIdResolver = externalIdResolver,
            fromClientResolver = fromClientResolver,
            deleteUploadedAttachmentOnSuccess = deleteUploadedAttachmentOnSuccess,
            safeDeleteRoots = safeDeleteRoots,
            messagesPollingIntervalMs = messagesPollingIntervalMs,
            allowCreatePublicClient = allowCreatePublicClient,
            sdkActiveChannelFailureHandler = sdkActiveChannelFailureHandler,
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
