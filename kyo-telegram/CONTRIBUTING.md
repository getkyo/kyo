# Contributing to kyo-telegram

This guide carries what is specific to `kyo-telegram`, a client for the Telegram Bot API. For the repository's rules (effect rows, naming, the safe-by-default tiers, test file naming, the build and test commands, shared cross-platform tests) read the root [CONTRIBUTING.md](../CONTRIBUTING.md) first; this file does not restate them. What follows is the one invariant every change turns on, the layout, the conventions of the request path and the model, the unsafe boundaries, how to extend the surface, the test patterns, and a checklist.

## The headline invariant: the token is in every URL, and in no failure

Telegram authenticates a bot by its token in the request path: every Bot API call goes to `<baseUrl>/bot<token>/<method>` and every file download to `<baseUrl>/file/bot<token>/<path>`. There is no header form. So every URL the module builds is a secret, and anything that holds a URL, a path, or text that can quote either, must not reach a caller, a log line, or an exception message.

The module enforces this in one place, `internal/telegram/BotApi.scala`, and every rule below serves it:

- **The module's own HTTP client, with a complete configuration.** `BotApi.transport` runs every request as `HttpClient.let(client.http)(HttpClient.withConfig(BotApi.requestConfig(...))(request))`, the replacing overload with all twelve `HttpClientConfig` fields stated. kyo-http keeps one ambient client and config, and a caller's filter would log the path, a caller's TLS setting would govern the token's connection, and a pooled connection the caller opened would carry the request. So no filter runs (`autoFilters = false`, `clientFilter = HttpFilter.noop`), TLS is the default, nothing is retried, and the client is the one `Telegram.run` or `Telegram.let` built.
- **Response-based calls only.** Requests go through `HttpClient.postTextResponse`, `postBinaryResponse` and `getBinaryResponse` with `failOnError = false`. kyo-http's `HttpStatusException` holds the URL; a response-based call never raises it, and the module reads the status itself.
- **Redirects off.** `followRedirects = false`, `maxRedirects = 0`. A redirect would re-send the path to a host the module did not choose, and `HttpRedirectLoopException` holds the URL and the chain. A 3xx answer is `TelegramUnexpectedStatusException`.
- **Only absolute http or https URLs are sent.** A request URL is the base URL with the Bot API's path appended to its parsed path (`TelegramConfig.under`), never text joined and parsed again. The base URL is checked at construction (`TelegramConfig.absoluteProblemOf`: an http or https scheme in ASCII case, a host, no Unix socket); `setWebhook` checks its URL the same way. Text Telegram supplies that lands in a path, `getFile`'s `file_path`, passes only as relative segments of `[A-Za-z0-9._-]` (`BotApi.safeFilePath`). A refused URL is `TelegramRefusedUrlException`, with nothing sent and no part of the URL kept. A method name reaches the path only as a `TelegramMethod`: ASCII letters, digits, `.` and `_`, not only dots.
- **No kyo-http cause.** A kyo-http failure is described by `BotApi.describe`, one exhaustive match over kyo-http's sealed hierarchy with no wildcard, as the flat `TelegramTransportException(method, kind, host, port, timeout)`. `host` and `port` are the base URL's. Only a connection that could not be made keeps a cause, kyo-net's `NetException`, which holds a host and port and nothing of the path. A leaf the module's calls cannot produce (a URL or header the module built that kyo-http refused, a redirect, a status or typed-body leaf, a server leaf) is a module bug: `describe` answers `Absent` and `transport` panics with the leaf's class name only, never the exception.
- **No kyo-schema cause.** `TelegramDecodeException` carries which of kyo-schema's failures it was (`TelegramDecodeException.Failure`), the path and the position, never kyo-schema's exception, which quotes the input around the failure and a body can hold users' messages. A value the module's own check refuses (`WireCodec.Rejected`) carries its path and failure kind: `ConstructorRejected` for a URL that does not parse or an update with no kind, `TypeMismatch` for a result other than the `true` a method documents.
- **Text from the peer is redacted.** A Bot API `description` has the token, raw and percent-encoded, and every secret the request sent (`Payload.Fields.secrets`, a webhook's `secret_token`) replaced by `<redacted>` (`BotApi.redact`) before any leaf holds it, since a proxy or a self-hosted server can echo the request. A new call that sends a secret parameter lists it there.
- **The token type cannot leak by rendering.** `TelegramToken` has a redacted `toString` and no `Schema`; `TelegramConfig.toString` renders it redacted.

A new kyo-http leaf fails compilation in `BotApi.describe` until it is placed: to a `Kind` if a call of the module can receive it, otherwise to the `Absent` arm. `TelegramExceptionMembershipTest` produces every failure through its real path and asserts that no message, field or cause holds any part of the token, and `TelegramTest` does the same for a timeout, an oversized body, each redirect, a close after the head and a malformed status line. A change to the request path must keep both green.

## Architecture

Public types live in `kyo`, one file each; internals in `kyo.internal.telegram`, all `private[kyo]`.

| Layer | Files | Role |
|-------|-------|------|
| Client and verbs | `Telegram.scala` | `final class Telegram`, the client: a config and the module's `HttpClient`, with no methods. `object Telegram`: the verbs, each requiring `Env[Telegram]`, and `run` and `let`, which build the client for a region. |
| Webhook | `TelegramWebhook.scala`, `TelegramWebhookConfig.scala` | The kyo-http handler, `verify` (constant time), `decode`; the secret and mount path. |
| Request path | `internal/telegram/BotApi.scala` | The one call path on a config and a client, the complete request config, the transport mapping, the answer-to-leaf mapping. |
| Polling | `internal/telegram/Poller.scala` | `run`'s loop: offset, retry, over the poll's own `HttpClient`. |
| Codec | `internal/telegram/Wire.scala`, `WireCodec.scala` | Wire DTOs, the hand-written decoders and encoders over `Structure.Value`, and every wire name. |
| Formatting | `internal/telegram/Markup.scala` | `TelegramMarkup` rendered to MarkdownV2 and HTML. |
| Uploads | `internal/telegram/Multipart.scala` | `multipart/form-data`, which kyo-http's form codec does not write for files. |
| Model | `TelegramUpdate`, `TelegramMessage`, `TelegramChat`, ..., `TelegramContent`, `TelegramKeyboard`, `TelegramUrl`, ... | Inbound and outbound values. |
| Errors | `TelegramException.scala` | One sealed base, one trait per operation, leaves that mix in the traits of the operations that can meet them. |

The client is a requirement on the row, not a value a caller holds. `Telegram.let(config)` runs `Scope.run` around `HttpClient.init` and `Env.run`, so the client's connections close when the region ends and nothing is process-wide. `Telegram.run` builds the client and a second `HttpClient` for the long poll in the same region, so interrupting `run` closes the poll's connection without waiting on a pool the handler sends through. `HttpHandler` has no lifecycle of its own, so `TelegramWebhook.handler` is an effect in `Scope`: it builds the client once, in the scope the server runs in, and runs every callback under `Env.run` with it.

## Conventions specific to kyo-telegram

### Failures: one trait per operation, and `within`

Every verb's row is `Abort[TelegramXFailure] & Env[Telegram]`, where the sealed trait's leaves are exactly what that verb can meet. `BotApi.Common` is the union every call can meet. `BotApi.call[A, F]` and `acknowledged[F]` take the operation's trait as `F` with a `TypeTest`, and `within[F]` turns an answer whose leaf is not in `F` into `TelegramOtherApiException`, which every trait carries. So `MessageNotModified` on a `send` is `TelegramOtherApiException`, never a leaf `send`'s trait does not list.

`TelegramExceptionMembershipTest` holds the table of which leaf is on which trait. It produces every (operation, leaf) pair through the real path. Adding a leaf or a verb means adding its row there first.

### Answers keyed on descriptions

`BotApi.byCode` chooses `ChatNotFound`, `MessageNotFound`, `MessageNotModified` and `FileTooBig` by matching the whole description, because Telegram documents only `error_code` and `description`. Each string is cited at its match site to the Bot API server function that writes it (`Client.cpp`, at the commit the comment names). A new description-keyed leaf needs the same: the whole string, the function that writes it, and a live test in `TelegramLiveTest` asserting the exact description. A named leaf stands for one `error_code`, so `code` is a fixed `def` and not a constructor field; only `TelegramOtherApiException` carries the code it received.

### No `Schema` on what Telegram sends

The types Telegram sends (updates, messages and everything nested in them) have no `Schema`. Each companion holds `inline given Schema[X] = compiletime.error("X has no Schema: ...")`, sealed parents included, so deriving one fails with a message saying why; `TelegramUpdate`'s is bounded by the root, so it also answers a summon of one case. The module alone reads Telegram's JSON. Outbound values a caller builds (`TelegramContent`, `TelegramKeyboard`, `TelegramSendOptions`, ...) carry no refusal of their own; one that holds an inbound type (a `TelegramUrl`, a `TelegramEntity`, an update `Type`) cannot derive a `Schema`, because that component refuses it. `WireCodec` decodes from and encodes to `Structure.Value` by hand, so a public type's shape is free to differ from the wire (camelCase, `Maybe`, typed ids, enums for Telegram's optional-field unions), and every wire name lives there. Ids (`TelegramId`) keep their `Schema`, since a caller stores them. One test pins the refusal's message, in the test file of the type it summons; the given on each type is the rest of the coverage.

### No data loss inbound

An update of a kind the module does not model is `TelegramUpdate.Unknown` with its id, the name of its kind's field and its raw JSON; so is a known kind missing a field its model requires, or holding a nested message that does not decode. Message content, entity types, chat types and reactions have the same `Unknown` or `Other(name)` fallback, and a text link whose URL is not a `TelegramUrl` is `Other("text_link")`. Only an update without an `update_id`, or with no field naming a kind, fails to decode. A decode failure in `run` ends `run` with the update unconfirmed; in the webhook it is answered 200 and logged by part, never with the body.

### Polling confirms after the handler

`Poller.run` passes `offset = last handled id + 1` only after the handler returned for that update. A typed failure or panic of the handler ends `run` and leaves the update unconfirmed. Do not confirm ahead of the handler, and do not continue past a failed update: the first drops an update the handler never finished, the second receives the same update again for up to 24 hours. `Poller.decide` names every leaf of `TelegramRunFailure` and answers a `RetryDecision`: transport failures, 5xx and rate limits are retried, nothing else, and a new run leaf does not compile until it is decided. The handler runs inline, never forked, so its effects `S` pass through `run` unchanged.

### Validation at construction

`TelegramToken`, `TelegramSecretToken`, `TelegramConfig`, `TelegramUrl`, `TelegramKeyboard.CallbackData`, `TelegramCommand`, `TelegramMethod`, `TelegramWebhookOptions`, `TelegramWebhookConfig` and `TelegramCallbackAnswer` check their values when built and panic with a leaf that gives a position, a length or the offending number, never the text, since the text may be a secret. A value Telegram takes in whole seconds is refused, not rounded, when it has a fraction (`TelegramConfig.wholeSeconds`). Bounds come from the Bot API documentation or the server's source, cited in the scaladoc. A constant built in the `kyo` package, such as `TelegramCallbackAnswer.empty`, passes `Frame.internal`, since the package derives no `Frame` and the constant passes every check.

## Unsafe boundaries

Main code has one unsafe site: `Multipart.encode` wraps the multipart buffer it has just filled with `Span.fromUnsafe`, which is sound because the array is local to the call and never written after. Nothing else in main code uses the unsafe tier; a new site needs the same argument, written at the site with `// Unsafe:`.

Tests use the unsafe tier only where kyo offers no safe form: kyo-net's raw listener in `TelegramExceptionMembershipTest.withClosingPeer`, `TelegramTest.withCountingPeer` and `withClosingAfterHead`, to get a peer that closes or answers malformed bytes; `HttpClient.isPoolClosed` in the test that `let` closes its client; and `TelegramLiveTest.discovered`, a chat found once and kept across the suite's instances. Each is marked `// Unsafe:` with its reason.

## Extension recipes

### Add a verb

Say `pinChatMessage`.

1. **Failure trait.** Add `sealed trait TelegramPinFailure extends TelegramException` and mix it into `BotApi.Common`'s leaves and any answer leaf the method can meet (`ChatNotFound`, `MessageNotFound`, `Forbidden`). Add the trait's row to `TelegramExceptionMembershipTest` and the method to its `methodOf` and `call` maps.
2. **Verb.** Add `def pin(chat, message, ...)(using Frame): Unit < (Async & Abort[TelegramPinFailure] & Env[Telegram])` to `object Telegram`, delegating to `BotApi.acknowledged[TelegramPinFailure]("pinChatMessage", Payload.Fields(...))`. Encode each field with `WireCodec`; use Telegram's snake_case names.
3. **Tests.** In `TelegramTest`, the exact request body against the local Bot API through `local.api`; the membership test then produces every leaf of the new row. Add a live leaf if the method has an observable effect.

For a method a caller needs once, `custom` already exists.

### Add an update kind

1. **Model.** Add a `final case class` extending `TelegramUpdate` with the update id first, and a case to `TelegramUpdate.Type`; add the payload's model type with its `Schema` refusal.
2. **Codec.** Decode it in `WireCodec.decodeUpdate` from the kind's `Structure.Value`, and add its wire name to `updateType` and `updateTypeName`; a missing required field yields `Unknown`, not a failure.
3. **Tests.** In `WireCodecTest`, a real payload to the typed case, and the same payload with a required field removed to `Unknown`.

## Test patterns

- **A local Bot API.** `TelegramTest.withLocal` runs a kyo-http `HttpServer` on an ephemeral port that answers `/bot<token>/<method>` from a queue of replies per method and records each request; `local.api(v)` runs `v` under `Telegram.let` on it. Tests assert the exact request body and the decoded result, with the real request path, codec and error mapping. A method with nothing queued holds the request open until the client goes away, which is how `run` tests end deterministically and how the timeout test waits for its request before advancing virtual time.
- **Handlers.** `TelegramTest.onMessage` is the handler most suites use.
- **Every leaf through its real path.** `TelegramExceptionMembershipTest` produces each (operation, leaf) pair: an answer from the local Bot API, a peer that accepts and closes for the transport leaf, an unreachable port for the connect family, a bad `file_path` or webhook URL for a refusal.
- **A token in a test is built from parts.** A failure's message quotes the source lines around its frame, so a literal on the line near a call can appear in the message; the leak assertions build the token's secret part at a distance (`TelegramTest.tokenSecret`).
- **Retry timing in isolation.** `PollerTest` tests `Poller.retry` under `Clock.withTimeControl`. Through `run`, kyo-http's own timeout sleepers also advance under virtual time, so integration tests of `run` use a zero-delay schedule and assert what is retried, not when.
- **The webhook over HTTP.** `TelegramWebhookTest` mounts the handler on a local server and POSTs with and without the secret header, asserting the status, whether the callback ran, and that a callback's verb reaches the local Bot API.
- **The live suite.** `TelegramLiveTest` runs against the real Bot API when `TELEGRAM_BOT_TOKEN` is set, and cancels every leaf otherwise. The leaves that need no chat run on the token alone; the rest need `TELEGRAM_CHAT_ID`, or with `TELEGRAM_INTERACTIVE` the chat of the next message a person sends, found once through `run` and kept on the companion, since kyo-test builds a suite instance per leaf. It asserts exact descriptions, since the description-keyed leaves depend on them, and deletes every message it sends. With `TELEGRAM_INTERACTIVE`, the interactive leaves each send the person an instruction and wait through `run`, with retries off so a failure is not a hang, for the update it produces.
- Socket leak checks are off in the suites that run a local server: kyo-net reaps a closed connection on the selector's next pass, which the check sees as an open descriptor.
- Tests are in `shared/src/test` and use no platform gate; kyo-http's server runs on every platform.

## Decision checklist

- [ ] Does any new URL, path, body, peer text or kyo-http or kyo-schema exception reach a failure, a log line, or a `toString`? It must not.
- [ ] New request: response-based, `failOnError = false`, through `BotApi.callWith` so it runs on the client's own `HttpClient` under `BotApi.requestConfig`?
- [ ] New URL or path segment from Telegram or from the caller: refused as `TelegramRefusedUrlException` unless it keeps the request absolute http or https on the configured host?
- [ ] New kyo-http leaf: placed in `BotApi.describe`, to a `Kind` or to the module-bug `Absent` arm, no wildcard added?
- [ ] New verb: on `object Telegram` with `Env[Telegram]` on its row, its own failure trait, its row in the membership table, `within[F]` applied by going through `call` or `acknowledged`?
- [ ] New description-keyed leaf: whole-string match, the server function cited, a live test asserting the exact description?
- [ ] New inbound model type: no `Schema` (the refusal given); any model type: camelCase fields, `Maybe` for optional, typed ids, `HttpUrl` or `TelegramUrl` for a URL, an `Unknown` or `Other` fallback for values Telegram may add?
- [ ] `run` touched: still confirms only after the handler returned, and still ends on a handler failure?
- [ ] Validation added: panics with a position or length, never the value?
- [ ] Tests in `shared/src/test`, against the local Bot API, with no real-clock assertions?
