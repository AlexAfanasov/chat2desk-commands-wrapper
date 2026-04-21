# chat2desk-commands-wrapper

Android wrapper over Chat2Desk SDK (`IChat2Desk`) with extra commands support compatible with Chat2Desk Public API / Web flows.

## Features

- Decorator over `IChat2Desk` for drop-in compatibility.
- Direct Public API commands client (v1):
  - `POST /v1/messages/inbox`
  - `POST /v1/messages`
  - `GET /v1/scenarios/menu_items`
- Button mapping strategy for commands: `payload` first, fallback to `text`.
- Config is provided by host app (`baseUrl`, `apiToken`, defaults, timeouts).

## Installation (JitPack)

Add JitPack repository:

```kotlin
repositories {
    maven("https://jitpack.io")
}
```

Add dependency:

```kotlin
dependencies {
    implementation 'com.github.AlexAfanasov:chat2desk-commands-wrapper:main-SNAPSHOT'
}
```

## Usage

```kotlin
val sdkChat = Chat2Desk.create(settings, context)

val commandsConfig = Chat2DeskCommandsConfig(
    baseUrl = "https://api.chat2desk.com",
    uploadBaseUrl = "https://api.chat2desk.com",
    apiToken = "<CHAT2DESK_PUBLIC_API_TOKEN>",
    defaultTransport = "external",
    maxUploadBytes = 20L * 1024L * 1024L,
    requireHttps = true,
    trustedHostSuffixes = setOf("chat2desk.com"),
    deleteUploadedAttachmentOnSuccess = false,
    safeDeleteRoots = setOf(System.getProperty("java.io.tmpdir")),
    routeSdkSendMessageViaInboxApi = true,
    externalIdResolver = { context -> "client-${context.clientId}" },
)

val chat: ICommandChat2Desk = CommandChat2DeskFactory.create(
    delegate = sdkChat,
    config = commandsConfig,
)

chat.sendInboxCommand(command = "/status", clientId = "123")
```

Send button preserving payload fallback behavior:

```kotlin
chat.sendButton(button = buttonFromMessage, clientId = "123")
```

When `routeSdkSendMessageViaInboxApi = true`, wrapper routes `sendMessage(...)` calls through
`POST /v1/messages/inbox` and applies `external_id` from `externalIdResolver`.
If routing is enabled and client id is missing, wrapper fails fast with `Chat2DeskCommandRoutingException`.

Attachment routing uses file streaming from local path and enforces `maxUploadBytes`.
By default, uploaded local files are not deleted; enable explicit cleanup with
`deleteUploadedAttachmentOnSuccess = true` and `safeDeleteRoots`.

`requireHttps` + `trustedHostSuffixes` are enforced for both `baseUrl` and `uploadBaseUrl`
to prevent token leakage to untrusted hosts.

CI also runs a consumer smoke build:
1. `:wrapper:publishToMavenLocal`
2. `:sample:assemble` (module consuming `group:wrapper:version` from `mavenLocal`)
## Android Studio Setup

1. Open repository root `chat2desk-commands-wrapper` in Android Studio.
2. Set **Gradle JDK** to JDK 17 (or Embedded JDK 17+) in `Settings -> Build, Execution, Deployment -> Build Tools -> Gradle`.
3. Sync Gradle.
4. Use shared run configs from `.run/`:
   - `Wrapper Verify`
   - `Wrapper Unit Tests`
   - `Wrapper Publish Local`
5. For full release gate run scripts/check-handoff.ps1 (Windows) or scripts/check-handoff.sh (Linux/macOS) from terminal.
