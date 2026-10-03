# Contributing to kyo-telegram

This guide carries what is specific to `kyo-telegram`, a client for the Telegram Bot API. For the repository's rules (effect rows, naming, the safe-by-default tiers, test file naming, the build and test commands, shared cross-platform tests) read the root [CONTRIBUTING.md](../CONTRIBUTING.md) first; this file does not restate them. What follows is the one invariant every change turns on, the layout, the conventions of the request path and the model, the unsafe boundaries, how to extend the surface, the test patterns, and a checklist.

## The headline invariant: the token is in every URL, and in no failure

Telegram authenticates a bot by its token in the request path: every Bot API call goes to `<baseUrl>/bot<token>/<method>` and every file download to `<baseUrl>/file/bot<token>/<path>`. There is no header form. So every URL the module builds is a secret, and anything that holds a URL, a path, or text that can quote either, must not reach a caller, a log line, or an exception message.

The module enforces this in one place, `internal/telegram/BotApi.scala`, and every rule below serves it:

- **The module's own HTTP client, with a complete configuration.** `BotApi.transport` runs every request as `HttpClient.let(client.http)(HttpClient.withConfig(BotApi.requestConfig(...))(request))`, the replacing overload with all twelve `HttpClientConfig` fields stated. kyo-http keeps one ambient client and config, and a caller's filter would log the path, a caller's TLS setting would govern the token's connection, and a pooled connection the caller opened would carry the request. So no filter runs (`autoFilters = false`, `clientFilter = HttpFilter.noop`), TLS and the transport are `TelegramConfig`'s (`tls`, `transport`, also passed to both clients' `HttpClient.init`), nothing is retried, and the client is the one `Telegram.run` built.
- **Response-based calls only.** Requests go through `HttpClient.postTextResponse`, `postBinaryResponse` and `getBinaryResponse` with `failOnError = false`. kyo-http's `HttpStatusException` holds the URL; a response-based call never raises it, and the module reads the status itself.
- **Redirects off.** `followRedirects = false`, `maxRedirects = 0`. A redirect would re-send the path to a host the module did not choose, and `HttpRedirectLoopException` holds the URL and the chain. A 3xx answer is `TelegramUnexpectedStatusException`.
- **Only absolute http or https URLs are sent.** A request URL is the base URL with the Bot API's path appended to its parsed path (`TelegramConfig.under`), never text joined and parsed again. The base URL is checked at construction (`TelegramConfig.absoluteProblemOf`: an http or https scheme in ASCII case, a host, no Unix socket); `setWebhook` checks its URL the same way. Text Telegram supplies that lands in a path, `getFile`'s `file_path`, passes only as relative segments of `[A-Za-z0-9._-]` (`BotApi.safeFilePath`). A refused URL is `TelegramRefusedUrlException`, with nothing sent and no part of the URL kept. A method name reaches the path only as a `Telegram.Method`: ASCII letters, digits, `.` and `_`, not only dots.
- **No kyo-http cause.** A kyo-http failure is described by `BotApi.describe`, one exhaustive match over kyo-http's sealed hierarchy with no wildcard, as the flat `TelegramTransportException(method, kind, host, port, timeout)`. `host` and `port` are the base URL's. Only a connection that could not be made keeps a cause, kyo-net's `NetException`, which holds a host and port and nothing of the path. A leaf the module's calls cannot produce (a URL or header the module built that kyo-http refused, a redirect, a status or typed-body leaf, a server leaf) is a module bug: `describe` answers `Absent` and `transport` panics with the leaf's class name only, never the exception.
- **No kyo-schema cause.** `TelegramDecodeException` carries which of kyo-schema's failures it was (`TelegramDecodeException.Failure`), the path and the position, never kyo-schema's exception, which quotes the input around the failure and a body can hold users' messages. A value the module's own check refuses (`BotApi.Rejected`) carries its path and failure kind: `ConstructorRejected` for an envelope missing `error_code` or `description`, `TypeMismatch` for a result other than the `true` a method documents.
- **Text from the peer is redacted.** A Bot API `description` has the token, raw and percent-encoded, and every secret the request sent (`Payload.Body`'s `secrets`, a webhook's `secret_token`) replaced by `<redacted>` (`BotApi.redact`) before any leaf holds it, since a proxy or a self-hosted server can echo the request. A new call that sends a secret parameter lists it there.
- **The token type cannot leak by rendering.** `Telegram.Token` and `Telegram.SecretToken` have a redacted `toString`, and `TelegramConfig.toString` renders the token redacted. Their `Schema` reads and writes the secret itself, since serializing is the caller's explicit act.

A new kyo-http leaf fails compilation in `BotApi.describe` until it is placed: to a `Kind` if a call of the module can receive it, otherwise to the `Absent` arm. `TelegramExceptionMembershipTest` produces every failure through its real path and asserts that no message, field or cause holds any part of the token, and `TelegramTest` does the same for a timeout, an oversized body, each redirect, a close after the head and a malformed status line. A change to the request path must keep both green.

## Architecture

The package `kyo` holds only `Telegram`, `TelegramConfig`, `TelegramWebhookConfig` and the exception hierarchy. Every other public type is nested in `object Telegram`, in `Telegram.scala`, and nothing is exported back to the package. Internals are in `kyo.internal.telegram`, all `private[kyo]`.

| Layer | Files | Role |
|-------|-------|------|
| Client, verbs and model | `Telegram.scala` | `final class Telegram`, the client: a config and the module's `HttpClient`, with no methods. `object Telegram`: `run`, which builds the client and provides it to a region, the verbs and `receive`, each requiring `Env[Telegram]`, and every inbound and outbound model type. |
| Webhook | `Telegram.scala` (`Telegram.Webhook`), `TelegramWebhookConfig.scala` | The kyo-http handler, `verify` (constant time), `decode`; the secret and mount path. |
| Request path | `internal/telegram/BotApi.scala` | The one call path on a config and a client, the complete request config, the transport mapping, the answer-to-leaf mapping. |
| Polling | `internal/telegram/Poller.scala` | `receive`'s loop: offset, retry, over the poll's own `HttpClient`. |
| Request bodies | `internal/telegram/Request.scala` | One record per Bot API method, whose schema is that method's JSON body. |
| Field encodings | `internal/telegram/WireField.scala` | The field transforms for Telegram's encodings: Unix seconds, file sizes, URLs, the reply target. |
| Formatting | `internal/telegram/Markup.scala` | `Telegram.Markup` rendered to MarkdownV2 and HTML. |
| Uploads | `internal/telegram/Multipart.scala` | `multipart/form-data`, which kyo-http's form codec does not write for files. |
| Errors | `TelegramException.scala` | One sealed base, one trait per operation, leaves that mix in the traits of the operations that can meet them. |

The client is a requirement on the row. Only `run` satisfies it: `Telegram.run(client)` is `Env.run`, and `Telegram.run(config)` runs `Scope.run` around `Telegram.init` and `run(client)`, so the client's connections close when the region ends and nothing is process-wide. `init` is `Scope.acquireRelease(initUnscoped)(close)`, the lifecycle shape kyo-slack and kyo-discord share; building a client opens no connection, so unlike theirs `init` has no failure. Every verb, `receive` and the webhook handler require `Env[Telegram]` and build no client: a method that satisfies an effect is named `run`. `Telegram.receive` opens a second `HttpClient` for the long poll in a `Scope.run` of its own, so interrupting `receive` closes the poll's connection without waiting on a pool the handler sends through. `Telegram.Webhook.handler` takes the client from `Env` when it is built and runs every callback under `Env.run` with it, so its server lives inside the caller's `Telegram.run` region.

## Conventions specific to kyo-telegram

### Failures: one trait per operation, and `within`

Every verb's row is `Abort[TelegramXFailure] & Env[Telegram]`, where the sealed trait's leaves are exactly what that verb can meet. `BotApi.Common` is the union every call can meet. `BotApi.call[A, F]` and `acknowledged[F]` take the operation's trait as `F` with a `TypeTest`, and `within[F]` turns an answer whose leaf is not in `F` into `TelegramOtherApiException`, which every trait carries. So `MessageNotModified` on a `send` is `TelegramOtherApiException`, never a leaf `send`'s trait does not list.

`TelegramExceptionMembershipTest` holds the table of which leaf is on which trait. It produces every (operation, leaf) pair through the real path. Adding a leaf or a verb means adding its row there first.

### Answers keyed on descriptions

`BotApi.byCode` chooses `ChatNotFound`, `MessageNotFound`, `MessageNotModified` and `FileTooBig` by matching the whole description, because Telegram documents only `error_code` and `description`. Each string is cited at its match site to the Bot API server function that writes it (`Client.cpp`, at the commit the comment names). A new description-keyed leaf needs the same: the whole string, the function that writes it, and a live test in `TelegramLiveTest` asserting the exact description. A named leaf stands for one `error_code`, so `code` is a fixed `def` and not a constructor field; only `TelegramOtherApiException` carries the code it received.

### Reading and writing Telegram's JSON

Every mapping between the model and Telegram's JSON is declared on the type, and no JSON is written or read by hand. A record names its fields in snake_case through `WireField.snakeCase`, or field by field with `@rename`; an optional field Telegram omits is `@omit`; Telegram's encodings (Unix seconds, file sizes, URLs) are `@transform(WireField.X)`. Where the public shape differs from the wire, the schema says how:

- **One of Telegram's optional-field unions** is an `@untagged()` sum, whose decode takes the first case whose required fields are present. The case order is the contract, set by the Bot API's backward-compatibility rules: a venue also sets `location`, an animation `document`, a live photo `photo`, so the older case comes first and the newer object reads as it. A `@catchAll()` case last receives what no case read, including a known case that failed to decode.
- **A field whose cases sit beside the parent's own fields** (a message's content, an entity's kind, a callback answer) is lifted into the parent with `Schema[A].flatten(_.field)`; an optional record (a media caption) flattens the same way and writes nothing when absent.
- **A tagged object** (`Entity.Kind`, `Reaction`, `Command.Scope`) is `@discriminator("type")` with `renameAllVariants(SnakeCase)`; a bare string enum is `@tagOnly()`.
- **A check on a decoded value** (an inaccessible message's `date` of 0, a callback data length) is `transformVia` over the plain schema, so a refusal is a decode failure the untagged sum can fall through.

Each request body is a record in `Request.scala`, built from the verb's arguments, with the public option records (`SendOptions`, `WebhookOptions`, `CallbackAnswer`) flattened into it. Ids and tokens carry a `Schema` of the bare value, since a caller stores them.

### Opaque types in `object Telegram`

An opaque type nested in `Telegram` is declared in an object of its own and aliased (`type ChatId = ChatId.Value`), as `Render.Rendered` is. Declared in `Telegram`'s template, it would be transparent across the whole object, where every `Tag` whose type mentions its underlying type is refused (`[Tag.opaque.collapsed]`, root CONTRIBUTING). `Schema.derived` of a record in `Telegram` then cannot summon the record's `Fields` and silently derives without them, so it encodes its renames and decodes none of them.

### No data loss inbound

An update of a kind the module does not model is `Telegram.Update.Unknown` with its id, the name of its kind's field when it has one, and its raw JSON; so is a known kind whose payload does not decode. Message content, entity types, chat types, reactions and inline buttons have the same `Unknown` or `Other` fallback, holding the whole object as Telegram sent it, which is written back unchanged. A message's content that does not decode as the case its fields name (a text link whose URL is not a `Telegram.Url`, a negative file size) is `Content.Unknown` with the whole message. Only an update without an integer `update_id`, or a value that is not an object, fails to decode. A decode failure in `receive` ends `receive` with the update unconfirmed; in the webhook it is answered 200 and logged by part, never with the body.

### Polling confirms after the handler

`Poller.receive` passes `offset = last handled id + 1` only after the handler returned for that update. A typed failure or panic of the handler ends `receive` and leaves the update unconfirmed. Do not confirm ahead of the handler, and do not continue past a failed update: the first drops an update the handler never finished, the second receives the same update again for up to 24 hours. `Poller.decide` names every leaf of `TelegramReceiveFailure` and answers a `RetryDecision`: transport failures, 5xx and rate limits are retried, nothing else, and a new receive leaf does not compile until it is decided. The handler runs inline, never forked, so its effects `S` pass through `receive` unchanged.

### Validation through `init`

`Telegram.Token`, `Telegram.SecretToken`, `TelegramConfig`, `Telegram.Url`, `Telegram.Keyboard.CallbackData`, `Telegram.Command`, `Telegram.Command.Menu`, `Telegram.Method`, `Telegram.WebhookOptions`, `TelegramWebhookConfig` and `Telegram.CallbackAnswer` are built only by `init`, which answers `Result[<its leaf>, A]`; nothing throws. A case class among them has a `private[kyo]` constructor, so its `apply` and `copy` are not public. The leaf gives a position, a length or the offending number, never the text, since the text may be a secret. A type that also has a `Schema` validates on read through `transformVia` over the same check, so a decode of a refused value is a `ConstructorRejectedException`. A value Telegram takes in whole seconds is refused, not rounded, when it has a fraction (`TelegramConfig.wholeSeconds`). Bounds come from the Bot API documentation or the server's source, cited in the scaladoc. A constant that passes every check, such as `Telegram.CallbackAnswer.empty`, is built with `new` in its companion.

## Unsafe boundaries

Main code does not use the unsafe tier: `Multipart.encode` joins its pieces with `Span.concat`, and text becomes bytes through kyo-charset's `Utf8`. A new unsafe site needs its argument written at the site with `// Unsafe:`.

Tests use the unsafe tier only where kyo offers no safe form: kyo-net's raw listener in `TelegramExceptionMembershipTest.withClosingPeer`, `TelegramTest.withCountingPeer` and `withClosingAfterHead`, to get a peer that closes or answers malformed bytes; `HttpClient.isPoolClosed` in the test that `run` closes its client; and `TelegramLiveTest.discovered`, a chat found once and kept across the suite's instances. Each is marked `// Unsafe:` with its reason.

## Extension recipes

### Add a verb

Say `pinChatMessage`.

1. **Failure trait.** Add `sealed trait TelegramPinFailure extends TelegramException` and mix it into `BotApi.Common`'s leaves and any answer leaf the method can meet (`ChatNotFound`, `MessageNotFound`, `Forbidden`). Add the trait's row to `TelegramExceptionMembershipTest` and the method to its `methodOf` and `call` maps.
2. **Body.** Add `final case class PinChatMessage(chatId: Telegram.Chat.Target, messageId: Telegram.MessageId, @omit disableNotification: Maybe[Boolean])` to `Request.scala`, with `given Schema[PinChatMessage] = WireField.snakeCase(Schema.derived[PinChatMessage])`.
3. **Verb.** Add `def pin(chat, message, ...)(using Frame): Unit < (Async & Abort[TelegramPinFailure] & Env[Telegram])` to `object Telegram`, delegating to `BotApi.acknowledged[TelegramPinFailure]("pinChatMessage", Payload.of(Request.PinChatMessage(...)))`. A body that uploads files uses `Payload.withUploads`.
4. **Tests.** In `TelegramTest`, the exact request body against the local Bot API through `local.api`; the membership test then produces every leaf of the new row. Add a live leaf if the method has an observable effect.

For a method a caller needs once, `custom` already exists.

### Add an update kind

1. **Model.** Add a `final case class` extending `Telegram.Update` in its companion, before `Unknown`, with `@rename("update_id") id` first and the payload `@rename`d to its kind's key, and a case to `Telegram.Update.Type`; add the payload's model type, with its `Schema`, to `object Telegram`. The untagged `Update` then reads the kind, and a payload that does not decode falls to `Unknown`.
2. **Tests.** In `TelegramUpdateTest`, a real payload from the Bot API documentation both ways, and the kind with a payload that does not decode to `Unknown` with its type.

## Test patterns

- **A local Bot API.** `TelegramTest.withLocal` runs a kyo-http `HttpServer` on an ephemeral port that answers `/bot<token>/<method>` from a queue of replies per method and records each request; `local.api(v)` runs `v` under `Telegram.run` on it. Tests assert the exact request body and the decoded result, with the real request path, codec and error mapping. A method with nothing queued holds the request open until the client goes away, which is how `receive` tests end deterministically and how the timeout test waits for its request before advancing virtual time.
- **Handlers.** `TelegramTest.onMessage` is the handler most suites use.
- **Every leaf through its real path.** `TelegramExceptionMembershipTest` produces each (operation, leaf) pair: an answer from the local Bot API, a peer that accepts and closes for the transport leaf, an unreachable port for the connect family, a bad `file_path` or webhook URL for a refusal.
- **A token in a test is built from parts.** A failure's message quotes the source lines around its frame, so a literal on the line near a call can appear in the message; the leak assertions build the token's secret part at a distance (`TelegramTest.tokenSecret`).
- **No leaf on the real clock.** Every fixture that starts a server or a peer runs it and its leaf under `Clock.withTimeControl`. The connect, request and poll deadlines the default config arms never fire unless a leaf advances time, and a leaf that advances opens its own `withTimeControl`, which reuses the fixture's control. A deadline test advances only once its request is in flight: `Async.timeout` registers the sleeper before it forks the request, so the deadline exists by the time the local server sees the request.
- **Retry timing.** `PollerTest` tests `Poller.retry` and `BotApiTest` tests `BotApi.nextAttempt` under `Clock.withTimeControl`. Advancing a fixture's clock past a retry delay also advances kyo-http's request deadlines, so most integration tests use a zero delay (`Async.sleep` completes a zero duration without the clock) and assert what is retried, not when. The one leaf that asserts a real wait (`TelegramTest`, the second attempt sent only once `retry_after` has passed) sets `requestTimeout` and `connectTimeout` far beyond the wait and advances one virtual second at a time until the second request arrives.
- **Round trips against the Bot API's payloads.** Each model type's test decodes the documentation's JSON to the expected value and encodes the value back, compared as a tree with sorted keys (`TelegramTest.Fixtures.wire`). A payload with fields the module does not model asserts the decode ignores them and the encode drops them. `TelegramTest.Fixtures` holds the users and chats the payloads share.
- **The per-leaf timeout is the safety net.** Every suite runs under kyo-test's per-leaf cap, 120 s from `TestBase.timeout` (a bare `RunConfig` has none). Only a hang reaches it, and no leaf asserts on it.
- **The webhook over HTTP.** `TelegramWebhookTest` mounts the handler on a local server inside a `Telegram.run` region and POSTs with and without the secret header, asserting the status, whether the callback ran, and that a callback's verb reaches the local Bot API.
- **The live suite.** `TelegramLiveTest` runs against the real Bot API when `TELEGRAM_BOT_TOKEN` is set, and cancels every leaf otherwise. The leaves that need no chat run on the token alone; the rest need `TELEGRAM_CHAT_ID`, or with `TELEGRAM_INTERACTIVE` the chat of the next message a person sends, found once through `receive` and kept on the companion, since kyo-test builds a suite instance per leaf. It asserts exact descriptions, since the description-keyed leaves depend on them, and deletes every message it sends. With `TELEGRAM_INTERACTIVE`, the interactive leaves each send the person an instruction and wait through `receive`, with retries off so a failure is not a hang, for the update it produces. Its `override def timeout` of 10 minutes is the one real-clock deviation in the module's tests: a leaf waits on the real Bot API, and an interactive one for a person.
- Socket leak checks are off in the suites that run a local server: kyo-net reaps a closed connection on the selector's next pass, which the check sees as an open descriptor.
- Tests are in `shared/src/test` and use no platform gate; kyo-http's server runs on every platform.

## Decision checklist

- [ ] Does any new URL, path, body, peer text or kyo-http or kyo-schema exception reach a failure, a log line, or a `toString`? It must not.
- [ ] New request: response-based, `failOnError = false`, through `BotApi.callWith` so it runs on the client's own `HttpClient` under `BotApi.requestConfig`?
- [ ] New URL or path segment from Telegram or from the caller: refused as `TelegramRefusedUrlException` unless it keeps the request absolute http or https on the configured host?
- [ ] New kyo-http leaf: placed in `BotApi.describe`, to a `Kind` or to the module-bug `Absent` arm, no wildcard added?
- [ ] New verb: on `object Telegram` with `Env[Telegram]` on its row, its own failure trait, its row in the membership table, `within[F]` applied by going through `call` or `acknowledged`?
- [ ] New description-keyed leaf: whole-string match, the server function cited, a live test asserting the exact description?
- [ ] Any model type: camelCase fields, `Maybe` for optional, typed ids, `HttpUrl` or `Telegram.Url` for a URL, an `Unknown` or `Other` fallback for values Telegram may add, nested in `object Telegram`, and any opaque type in an object of its own?
- [ ] `receive` touched: still confirms only after the handler returned, and still ends on a handler failure?
- [ ] New public method: satisfies no effect unless it is named `run`; a method that needs the client requires `Env[Telegram]` rather than building one?
- [ ] Validation added: an `init` answering a `Result` whose leaf holds a position or length, never the value, and the `Schema` refusing the same through `transformVia`?
- [ ] Tests in `shared/src/test`, against the local Bot API, with no real-clock assertions?
