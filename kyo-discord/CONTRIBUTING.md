# Contributing to kyo-discord

This guide carries what is specific to `kyo-discord`, a client for Discord's bot API: REST, the Gateway, and the interactions endpoint. For the repository's rules (effect rows, naming, the safe-by-default tiers, test file naming, the build and test commands, shared cross-platform tests) read the root [CONTRIBUTING.md](../CONTRIBUTING.md) first; this file does not restate them. What follows is the invariant every change turns on, the layout, the conventions of each layer, the unsafe boundaries, how to extend the surface, the test patterns, and a checklist.

## The headline invariant: two secrets travel, and no failure holds either

The bot token is in the `Authorization: Bot <token>` header of every REST call and in the Identify and Resume frames of every Gateway connection. An interaction's token is in the path of its callback and of every follow-up route (`/interactions/{id}/{token}/callback`, `/webhooks/{application.id}/{token}/...`). So every request header set and every interaction URL the module builds is a secret, and anything that holds a header, a URL, a path, or text that can quote one must not reach a caller, a log line, or an exception message.

The REST side enforces this in one place, `internal/discord/Rest.scala`:

- **The module's own HTTP client, with a complete configuration.** `Rest.transport` runs every request as `HttpClient.let(http)(HttpClient.withConfig(Rest.requestConfig(config, timeout))(request))`, the replacing overload with every `HttpClientConfig` field stated: no filter (`autoFilters = false`, `clientFilter = HttpFilter.noop`), no redirect (`followRedirects = false`, `maxRedirects = 0`), no kyo-http retry, and the config's `tls` and `transport`. A caller's filter would log the path, a caller's TLS setting would govern the token's connection, and a redirect would re-send the headers to a host the module did not choose.
- **Response-based calls only.** `Rest.send` reads the status itself; kyo-http's `HttpStatusException` holds the URL and is never raised.
- **No kyo-http cause.** `Rest.describe` maps kyo-http's sealed hierarchy, with no wildcard arm, to the flat `DiscordTransportException(method, kind, host, port, timeout)`, where `method` is the route template (`POST /channels/{channel.id}/messages`), never the path, and `custom` names itself `"<METHOD> custom"`, since a caller's path may hold a webhook token. Only a connection that could not be made keeps kyo-net's cause, which holds a host and port. A leaf a REST call cannot produce is a module bug, raised with its class name only.
- **No kyo-schema cause.** `DiscordDecodeException` carries which kyo-schema failure it was, the path and the position, never kyo-schema's exception, which quotes the input.
- **Text from the peer is redacted.** Discord's `message`, each `errors` entry and a Gateway close reason have the bot token and the call's interaction token replaced (`Rest.redact`) before any leaf holds them.
- **The token types cannot leak by rendering.** `Discord.Token` and `Discord.InteractionToken` have a redacted `toString`; their `Schema` writes the value, since encoding one is the caller's act. An interaction token is checked at decode (`[A-Za-z0-9._-]`, at most 512 characters), so every URL built from it parses.

The Gateway side holds the same line: `Gateway.connectFailure` maps a refused upgrade to `Kind.WebSocketHandshake` and every other connect failure through `Rest.describe`, and no frame is logged or kept by a failure. A dispatch that does not decode is logged with its name and sequence, never its payload.

`DiscordTest` produces the transport leaves through their real paths and asserts with `DiscordTest.rendered` that no message, field or cause holds either token; the secrets are built from parts (`DiscordTest.tokenSecret`, `interactionSecret`), since a failure's message quotes the source lines around its frame. A change to the request path keeps those tests green.

## Architecture

The package `kyo` holds `Discord`, `DiscordConfig`, `DiscordWebhookConfig` and the exception hierarchy. Every other public type is nested in `object Discord`, in `Discord.scala`. Internals are in `kyo.internal.discord`, all `private[kyo]`.

| Layer | Files | Role |
|-------|-------|------|
| Client, verbs, model | `Discord.scala` | `final class Discord`, the client (config, `HttpClient`, `RateLimits`, the cached application id, an optional `Session`), with no public methods. `object Discord`: `run`, `init`, `initUnscoped`, `close`, `receive`, the verbs, `Webhook`, and every model type. |
| Request path | `internal/discord/Rest.scala` | The one call path, the complete request config, retry, the answer-to-leaf mapping, `redact`. |
| Rate limits | `internal/discord/RateLimits.scala` | The bucket map from response headers and the global `Meter`. |
| Uploads | `internal/discord/Multipart.scala` | `payload_json` and `files[n]` as `multipart/form-data`. |
| One connection | `internal/discord/Gateway.scala` | Hello, the heartbeat fiber and its ACK check, Identify or Resume, the read loop, close-code classification. |
| Session policy | `internal/discord/Session.scala` | Reconnect or end, resume or identify, the identify budget and spacing, the peer URL check, backoff. |
| Receive loop | `internal/discord/Dispatch.scala` | A fiber per event, the interaction deadline, declines, the callback. |
| Endpoint check | `internal/discord/Signature.scala` | The request check order of the interactions endpoint. |
| Wire | `internal/discord/Frames.scala`, `WireField.scala` | Frames no public type represents, and the field transforms Discord's encodings need. |
| Errors | `DiscordException.scala` | One sealed base, one trait per operation, leaves mixing in the traits of the operations that can meet them. |

The client is a requirement on the row, and only a method named `run` satisfies it: a method that satisfies a pending effect is named `run`. `Discord.run(config)` builds a client in a `Scope` around `Env.run`, with no Gateway session; `Discord.run(client)` is `Env.run` on a built one. `Discord.init` opens a session and returns once `READY` arrived. `receive` is the one Gateway entry point: it runs `Dispatch.run` on the provided client's session, opening one for its own duration on a client without. `Discord.Webhook.handler` requires `Env[Discord]` and holds that client for the server's fibers, so the server is served inside the client's region.

## Conventions specific to kyo-discord

### Failures: one trait per operation, and `within`

Every verb's row is `Abort[DiscordXFailure] & Env[Discord]`. `Rest.Common` is the union every call can meet. `Rest.call[Out, F]` and `acknowledged[F]` take the operation's trait with a `TypeTest`, and `Rest.within` turns a leaf outside `F` into `DiscordOtherApiException`, which every trait carries. `Rest.leafFor` chooses a leaf by Discord's JSON error code; the interaction-expired check (10015, 50027 on a route holding an interaction token) runs before the 401 check, because Discord answers 50027 with 401.

### Construction returns `Result`

Every value Discord bounds is built by `init` returning `Result[DiscordInvalidXException, X]`, never by a panicking constructor: tokens, the config, ids from text, paths, messages, components, commands, reactions, threads, interaction responses, the public key, the webhook config. The leaf's `Problem` names a position, a length or a count, never the text. A bound is cited to the Discord documentation in the scaladoc. The case class constructor is `private[kyo]`, so a caller cannot skip `init`.

### Opaque types in `object Discord`

An opaque type nested in `Discord` is declared in an object of its own and aliased (`type ChannelId = ChannelId.Value`). Declared in `Discord`'s template it would be transparent across the whole facade, and `Schema.derived` of a record there loses its field list and encodes renames it cannot decode.

### Events and answers

`Discord.Event[A]` carries its answer type: `Unit` for a dispatch, the admissible `InteractionResponse` union for each interaction kind. The handler type `[A] => Event[A] => A < (Async & Abort[E | Event.Decline] & Env[Discord] & S)` is the same for `run`, `receive` and `Webhook.handler`. A pattern on a case refines `A`, so a handler names its cases and ends with `Event.unhandled`, which answers `()` for a dispatch and declines an interaction. A decline is an `Abort[Event.Decline]`, handled in `Dispatch.within`: the Gateway posts nothing and the endpoint replies 500. It is never a panic or a failure of `run`.

A dispatch kind the model does not declare is `Event.Unknown` with its name and JSON; an interaction kind is `Event.UnknownInteraction`. Adding a case to `Event` means adding it to the union in `Dispatch.handle` and in `Event.unhandled`, both exhaustive with no wildcard.

### The receive loop forks; the read loop never waits

`Gateway.run`'s read loop handles control frames in place and puts dispatches on the session's queue with an unwaited put, so a slow handler never delays a heartbeat ACK. `Dispatch.run` forks each event's handler with `isolate.capture` and `isolate.isolate`, so order between handlers is not kept and a handler's changes to `S` are dropped. A handler's typed failure goes through a one-slot failures `Channel`, which the loop races against the next event; the loop does not race a `Promise.get`, because `Async.raceFirst` interrupting that leg would complete the promise itself.

### Time under test control

`Gateway.timer(d)` answers a completed fiber for a non-positive `d`: a zero `Clock.sleep` never fires under `Clock.withTimeControl`. Every wait in the Gateway, the session policy and the deadline goes through it. Each reconnect wait is armed before it is logged ("reconnecting in N milliseconds"), so a test that reads the log line can advance the clock and reach the reconnection.

### The interactions endpoint

`Signature.verify` runs the checks cheapest first, each refusal returning before the next: both headers, the timestamp as 1 to 20 ASCII digits, the signature as 128 hex characters, then `Ed25519.verify` over the timestamp's bytes followed by the raw body. Do not reorder; a forged request must reach the scalar multiplications only when well formed. The handler's route declares a binary response body, since kyo-http writes a body only through the route's response definition, and the 202 for an answer sent through the callback is a plain response, because a halted 202 carries kyo-http's JSON error body.

## Unsafe boundaries

Main code has two sites, each marked `// Unsafe:` with its argument. `Multipart.encode` wraps the buffer it has just filled with `Span.fromUnsafe`, sound because the array never escapes but as the span. `Session.open` hands deliveries to the events channel with `events.unsafe.putFiber`, an unwaited put that keeps the Gateway reader from blocking on the queue; the channel keeps pending puts in order. A new site needs the same written argument.

Tests use the unsafe tier only where kyo offers no safe form: kyo-net's raw listener in `DiscordTest.withCountingPeer`, `closedPort` and `withClosingAfterHead`; `HttpClient`'s pool flag in the tests of when `run` closes its client; and the `LogCapture` logger in `DiscordGatewayTest`, whose methods run outside the effect system.

## Extension recipes

### Add a REST verb

Say `pin(channel, message)`.

1. **Failure trait.** Add `sealed trait DiscordPinFailure extends DiscordException` and mix it into the shared leaves and each answer leaf the route can meet (`DiscordUnknownMessageException`, `DiscordMissingPermissionsException`).
2. **Verb.** Add `def pin(channel: ChannelId, message: MessageId)(using Frame): Unit < (Async & Abort[DiscordPinFailure] & Env[Discord])` to `object Discord`, delegating to `Rest.acknowledged[DiscordPinFailure](Rest.Call(HttpMethod.PUT, "/channels/{channel.id}/pins/{message.id}", Rest.path(...)))`. The route template is Discord's, with its placeholders: it names the call in every failure and keys its rate-limit bucket.
3. **Tests.** In `DiscordTest`, the method, path, headers and body against the local API, and each answer leaf through the real path.

For a route a caller needs once, `custom` exists.

### Add a dispatch

1. **Model.** Add a `final case class` extending `Event.Plain` in `object Event`, with its `Schema` in snake_case, and its Gateway name in `Event`'s `variantNames`.
2. **Exhaustive sites.** Add it to the plain-dispatch unions in `Dispatch.handle` and `Event.unhandled`.
3. **Tests.** In `DiscordEventTest`, a real payload decoded and encoded back.

## Test patterns

- **A local API.** `DiscordTest.withLocal` serves `/api/v10` on an ephemeral port from per-route reply queues and records each request; `local.api(v)` runs `v` under `Discord.run(config)`. A route with nothing queued holds its request open, which is how timeout and pool tests wait deterministically.
- **A local Gateway.** `DiscordGatewayTest.withGateway` serves `GET /gateway/bot`, the callback route and the Gateway WebSocket over TLS (`wss`), scripted per test through `Conn`. `LogCapture` collects log lines, so a test waits on a reconnect line before advancing the clock, or on one decline record per interaction.
- **The interactions endpoint.** `DiscordWebhookTest` mounts the handler on a local server and POSTs bodies signed with RFC 8032 TEST 1's key. kyo-crypto only verifies, so the signatures were computed once with the JDK's Ed25519 and are embedded; a new fixture body needs its signature computed the same way.
- **No leaf on the real clock.** Every fixture runs under `Clock.withTimeControl`; deadlines fire only when a leaf advances time, after its request is in flight. The global rate limiter never refills under time control, so a test that needs more than `globalRateLimit` calls in flight raises it.
- **Handlers run concurrently.** A test that records events from several handlers must not assert their order; it sends the next event after the previous handler ran, or compares sets.
- **The live suite.** `DiscordLiveTest` runs against Discord when `DISCORD_BOT_TOKEN` is set and cancels every leaf otherwise; the sending leaves need `DISCORD_TEST_CHANNEL_ID` and the command leaf `DISCORD_TEST_GUILD_ID`. It deletes what it creates. Its per-leaf bound of 2 minutes is the one real-clock deviation in the module's tests.
- Socket leak checks are off in the suites that run a local server: kyo-net reaps a closed connection on the selector's next pass, which the check sees as an open descriptor.
- Tests are in `shared/src/test` with no platform gate.

## Decision checklist

- [ ] Does any new header, URL, path, body, frame, peer text, or kyo-http or kyo-schema exception reach a failure, a log line or a `toString`? It must not.
- [ ] New REST call: through `Rest.call` or `acknowledged`, with its own failure trait, its route template as Discord writes it, and its interaction token in `Call.interaction` when the path holds one?
- [ ] New kyo-http leaf: placed in `Rest.describe`, no wildcard added?
- [ ] New model type: camelCase fields, `Maybe` for optional, typed ids, an `Unknown` fallback where Discord may add values, nested in `object Discord`, any opaque type in its own object?
- [ ] New bounded value: built by `init` returning a `Result`, the bound cited, the problem naming a position or length, never the text?
- [ ] New event case: added to `Dispatch.handle` and `Event.unhandled`?
- [ ] Gateway or session change: every wait through `Gateway.timer`, armed before it is logged; the read loop still never waits on a handler?
- [ ] Endpoint change: the check order of `Signature.verify` kept?
- [ ] Tests in `shared/src/test`, against the local API, Gateway or endpoint, with no real-clock assertions?
