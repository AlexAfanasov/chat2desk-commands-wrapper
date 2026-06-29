# chat2desk-commands-wrapper

Kotlin Multiplatform decorator over Chat2Desk SDK (`IChat2Desk`) that enriches
the Chat2Desk Public API client with an external business id after SDK client
params are sent.

## Features

- Drop-in `IChat2Desk` delegation. SDK remains the source of truth for chat,
  messages, attachments, read/delivery state, operator state, and UI.
- No SDK message interception.
- No Public API message routing, history loading, optimistic messages, or polling.
- Post-`sendClientParams` enrichment:
  1. cache SDK `client_key` from `start(...)`;
  2. read Public API client id from SDK `/start` response `client.clientID` or
     `client.client_id`;
  3. call `PUT /v1/clients/{id}` with `{"external_id":"..."}`.

## Installation

```kotlin
repositories {
    maven("https://jitpack.io")
}
```

```kotlin
dependencies {
    implementation("com.github.AlexAfanasov:chat2desk-commands-wrapper:main-SNAPSHOT")
}
```

## KMP setup

The wrapper module is a Kotlin Multiplatform library with Android and iOS
targets. It depends on the root KMP Chat2Desk artifact:

```kotlin
api("com.chat2desk:chat2desk_sdk:1.5.12")
```

Do not use `com.chat2desk:chat2desk_sdk-android` from shared source sets. The
Android-only artifact is not available to iOS/common code.

## Usage

Use the platform's official Chat2Desk SDK instance as the delegate, then wrap it
with `CommandChat2DeskFactory.create(delegate, config)`. Android and iOS use the
same shared wrapper API.

## Android usage

```kotlin
val sdkChat = Chat2Desk.create(settings, context)

val commandsConfig = Chat2DeskCommandsConfig(
    baseUrl = "https://api.chat2desk.com",
    publicApiToken = "<CHAT2DESK_PUBLIC_API_TOKEN>",
    sdkStartBaseUrl = "https://livechatv2.chat2desk.com",
    sdkWidgetToken = "<CHAT2DESK_WIDGET_TOKEN>",
    clientExternalIdResolver = { context ->
        context.fieldSet[42]
    },
    diagnosticsEnabled = BuildConfig.DEBUG,
    diagnosticsHandler = { message ->
        Log.d("Chat2Desk", message)
    },
)

val chat: IChat2Desk = CommandChat2DeskFactory.create(
    delegate = sdkChat,
    config = commandsConfig,
)
```

Call SDK methods normally:

```kotlin
chat.start()
chat.sendClientParams(name = name, phone = phone, fieldSet = fieldSet)
chat.sendMessage("hello")
```

`sendMessage(...)` and attachment sending are delegated to the original SDK.
The wrapper only overrides `start(...)` to cache SDK `client_key` and
`sendClientParams(...)` to enrich `external_id` after the SDK call succeeds.

## iOS usage

Use the same shared API from the generated Kotlin framework. Create the official
Chat2Desk SDK instance on iOS, then wrap it:

```kotlin
val commandsConfig = Chat2DeskCommandsConfig(
    baseUrl = "https://api.chat2desk.com",
    publicApiToken = "<CHAT2DESK_PUBLIC_API_TOKEN>",
    sdkStartBaseUrl = "https://livechatv2.chat2desk.com",
    sdkWidgetToken = "<CHAT2DESK_WIDGET_TOKEN>",
    clientExternalIdResolver = { context ->
        context.fieldSet[42]
    },
)

val chat = CommandChat2DeskFactory.create(
    delegate = sdkChat,
    config = commandsConfig,
)
```

The wrapper does not replace or reimplement the Chat2Desk SDK. It only adds the
same enrichment step around the SDK instance supplied by the app.

## Migration from Android-only wrapper

Most Android callers can keep using:

```kotlin
CommandChat2DeskFactory.create(delegate = sdkChat, config = commandsConfig)
```

The optional OkHttp/Gson factory injection from the Android-only implementation
is preserved for Android source compatibility, but it is deprecated. The
provided `OkHttpClient` is adapted into Ktor's OkHttp engine on Android. The
`Gson` parameter is kept only for source compatibility and is ignored because
the KMP implementation uses kotlinx.serialization. New code should pass a Ktor
`HttpClient` to `CommandChat2DeskFactory.create(...)` or
`ClientEnrichmentApiFactory.createDirect(...)` when custom HTTP transport is
needed.

## Security and diagnostics

Diagnostic logs are disabled by default. Set `diagnosticsEnabled = true` only
for debug builds. Logs never include the Public API token, full phone, full SDK
client key, or full `external_id`.

`requireHttps` and `trustedHostSuffixes` are enforced for `baseUrl` and
`sdkStartBaseUrl` to reduce token leakage risk.

## Known limitations

- Enrichment requires `sdkWidgetToken` and a non-blank SDK `client_key`.
- The wrapper enriches only after successful `sendClientParams(...)`.
- It does not intercept messages, attachments, message history, read/delivery
  state, operator state, connection state, or SDK UI behavior.
