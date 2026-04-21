package com.alexafanasov.chat2desk.commands

import com.chat2desk.chat2desk_sdk.AttachedFile
import com.chat2desk.chat2desk_sdk.IChat2Desk
import com.chat2desk.chat2desk_sdk.domain.entities.Button
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

class CommandChat2Desk(
    private val delegate: IChat2Desk,
    private val commands: CommandApi,
    private val config: Chat2DeskCommandsConfig,
) : ICommandChat2Desk, IChat2Desk by delegate {
    private var cachedClientId: String? = null

    override suspend fun start(): String? {
        val startedClientId = delegate.start()
        cacheClientId(startedClientId ?: delegate.clientPhone)
        return startedClientId
    }

    override suspend fun start(clientId: String?): String? {
        val startedClientId = delegate.start(clientId)
        cacheClientId(startedClientId ?: clientId ?: delegate.clientPhone)
        return startedClientId
    }

    override suspend fun sendInboxCommand(
        command: String,
        clientId: String,
        options: InboxOptions,
    ) {
        commands.sendInboxCommand(command = command, clientId = clientId, options = options)
    }

    override suspend fun sendOperatorMessage(request: OperatorMessageRequest): OperatorMessageResult {
        return commands.sendOperatorMessage(request)
    }

    override suspend fun loadMenuCommands(channelId: Long?): List<MenuCommand> {
        return commands.loadMenuCommands(channelId)
    }

    override suspend fun sendMessage(msg: String) {
        if (!config.routeSdkSendMessageViaInboxApi) {
            delegate.sendMessage(msg)
            return
        }

        val clientId = resolveRoutingClientId()
        val externalId =
            resolveExternalId(
                RoutedMessageContext(
                    text = msg,
                    clientId = clientId,
                    hasAttachment = false,
                    attachmentFileName = null,
                ),
            )

        commands.sendInboxCommand(
            command = msg,
            clientId = clientId,
            options = InboxOptions(externalId = externalId),
        )
    }

    override suspend fun sendMessage(
        msg: String,
        attachedFile: AttachedFile,
    ) {
        if (!config.routeSdkSendMessageViaInboxApi) {
            delegate.sendMessage(msg, attachedFile)
            return
        }

        val clientId = resolveRoutingClientId()
        val externalId =
            resolveExternalId(
                RoutedMessageContext(
                    text = msg,
                    clientId = clientId,
                    hasAttachment = true,
                    attachmentFileName = attachedFile.originalName.takeIf { it.isNotBlank() },
                ),
            )
        val uploadedAttachment = commands.uploadAttachment(attachedFile)
        tryDeleteAttachmentFile(attachedFile)

        commands.sendInboxCommand(
            command = msg,
            clientId = clientId,
            options = InboxOptions(externalId = externalId, attachments = listOf(uploadedAttachment)),
        )
    }

    override suspend fun sendButton(
        button: Button,
        clientId: String,
    ) {
        val commandText = button.payload?.takeIf { it.isNotBlank() } ?: button.text
        val externalId =
            resolveExternalId(
                RoutedMessageContext(
                    text = commandText,
                    clientId = clientId,
                    hasAttachment = false,
                    attachmentFileName = null,
                ),
            )
        sendInboxCommand(
            command = commandText,
            clientId = clientId,
            options = InboxOptions(externalId = externalId),
        )
    }

    private fun resolveRoutingClientId(): String {
        delegate.clientPhone?.takeIf { it.isNotBlank() }?.let { clientId ->
            cacheClientId(clientId)
            return clientId
        }

        cachedClientId?.takeIf { it.isNotBlank() }?.let { clientId ->
            return clientId
        }

        throw Chat2DeskCommandRoutingException(
            "sendMessage routing is enabled, but clientId is missing. Call start(...) first.",
        )
    }

    private fun resolveExternalId(context: RoutedMessageContext): String? {
        return config.externalIdResolver?.invoke(context)?.takeIf { it.isNotBlank() }
    }

    private fun cacheClientId(clientId: String?) {
        if (!clientId.isNullOrBlank()) {
            cachedClientId = clientId
        }
    }

    private fun tryDeleteAttachmentFile(attachedFile: AttachedFile) {
        if (!config.deleteUploadedAttachmentOnSuccess) return

        val filePath = attachedFile.getFilePath().trim()
        if (filePath.isBlank()) return

        val rawPath = runCatching { Paths.get(filePath).toAbsolutePath().normalize() }.getOrNull() ?: return
        if (!Files.isRegularFile(rawPath, LinkOption.NOFOLLOW_LINKS)) return

        val canonicalTargetPath =
            runCatching { rawPath.toFile().canonicalFile.toPath().normalize() }.getOrNull() ?: return
        val safeRoots =
            config.safeDeleteRoots
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .mapNotNull { root ->
                    runCatching { File(root).canonicalFile.toPath().normalize() }.getOrNull()
                }
                .toList()
        if (safeRoots.none { root -> canonicalTargetPath.startsWith(root) }) return

        runCatching {
            Files.deleteIfExists(canonicalTargetPath)
        }
    }
}
