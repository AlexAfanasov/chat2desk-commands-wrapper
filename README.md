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
val sdkSettings = Settings(
    authToken = "<CHAT2DESK_WIDGET_TOKEN>",
    baseHost = "https://livechatv2.chat2desk.com",
    wsHost = "wss://livechatv2.chat2desk.com",
    storageHost = "https://storage.chat2desk.com/",
)
val sdkChat = Chat2Desk.create(sdkSettings, context)

val commandsConfig = Chat2DeskCommandsConfig.publicApi(
    baseUrl = "https://api.chat2desk.com",
    uploadBaseUrl = "https://api.chat2desk.com",
    publicApiToken = "<CHAT2DESK_PUBLIC_API_TOKEN>",
    defaultTransport = "external",
    maxUploadBytes = 20L * 1024L * 1024L,
    requireHttps = true,
    trustedHostSuffixes = setOf("chat2desk.com"),
    deleteUploadedAttachmentOnSuccess = false,
    safeDeleteRoots = setOf(System.getProperty("java.io.tmpdir")),
    routeSdkSendMessageViaInboxApi = true,
    routedSendFailureMode = RoutedSendFailureMode.SWALLOW,
    routedSendFailureHandler = { error ->
        // Log statusCode and errorBody in debug builds if needed. Never log tokens.
    },
    externalIdResolver = { context -> "client-${context.clientId}" },
)

val chat: ICommandChat2Desk = CommandChat2DeskFactory.create(
    delegate = sdkChat,
    config = commandsConfig,
)

chat.sendInboxCommand(command = "/status", clientId = "123")
```

`Chat2DeskCommandsConfig.apiToken` must be a Public API token for
`https://api.chat2desk.com/v1/...`. It is not the SDK/widget token from
`Settings.authToken`; using the SDK/widget token for Public API requests usually returns
`401` or `403`.

Send button preserving payload fallback behavior:

```kotlin
chat.sendButton(button = buttonFromMessage, clientId = "123")
```

When `routeSdkSendMessageViaInboxApi = true`, wrapper routes `sendMessage(...)` calls through
`POST /v1/messages/inbox` and applies `external_id` from `externalIdResolver`.
This mode treats Chat2Desk Web API as the source of truth and uses the upstream SDK only as
an `IChat2Desk`-compatible surface for host app UI.

The SDK `client_key` is not the same value as the Web API `client_id`.
For `POST /v1/messages/inbox`, the client identity is sent through `from_client`
(`phone` by default from `sendClientParams`, or a custom value from `fromClientResolver`).
For Web API reads such as `GET /v1/messages`, wrapper resolves and caches the Web API
client id via `/v1/clients`.
If routing is enabled and client identity is missing, wrapper fails fast with
`Chat2DeskCommandRoutingException`.
Public API failures from routed `sendMessage(...)` are swallowed by default to preserve SDK-like
send behavior. Set `routedSendFailureMode = RoutedSendFailureMode.THROW` for strict diagnostics.

`external_id` is sent under `extra_data.external_id` as an external business key only.
Wrapper does not use it as a unique message id, merge key, dedupe key, or optimistic-send
correlation key. `POST /v1/messages/inbox` may return only `{"status":"success"}`, so wrapper
immediately adds an optimistic SDK `Message` with a local id and then syncs through
`GET /v1/messages`, where Web API message ids become the source of truth.

`defaultChannelId` is nullable. Leave it `null` when the host app should let wrapper resolve an
external channel through `/v1/channels`. Do not use `0` as a sentinel channel id; wrapper filters
non-positive ids and never sends `channel_id: 0`.

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
