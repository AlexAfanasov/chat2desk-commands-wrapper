# chat2desk-commands-wrapper

Thin Android decorator over Chat2Desk SDK (`IChat2Desk`) that enriches the
Chat2Desk Public API client with an external business id after SDK client params
are sent.

## Features

- Drop-in `IChat2Desk` delegation. SDK remains the source of truth for chat,
  messages, attachments, read/delivery state, operator state, and UI.
- No SDK message interception.
- No Public API message routing, history loading, optimistic messages, or polling.
- Post-`sendClientParams` enrichment:
  1. cache SDK `client_key` from `start(...)`;
  2. read Public API client id from SDK `/start` response `client.clientID`;
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

## Usage

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

Diagnostic logs are disabled by default. Set `diagnosticsEnabled = true` only
for debug builds; logs never include the Public API token, full phone, full SDK
client key, or full `external_id`.

`requireHttps` + `trustedHostSuffixes` are enforced for `baseUrl` and
`sdkStartBaseUrl` to prevent token leakage to untrusted hosts.
