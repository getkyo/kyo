# Contributing to kyo-slack

This guide carries the conventions, invariants, and patterns that are specific to
`kyo-slack`, a Slack [Socket Mode](https://api.slack.com/apis/socket-mode) client.
For repo-wide rules (effect-row discipline, naming, the safe-by-default tiers, the
test-file naming rule, the build/test commands, and the cross-platform shared-test
rule) read the root [CONTRIBUTING.md](../CONTRIBUTING.md) first; this file does not
restate them. What follows is the module's own shape: the one contract every change
turns on, the layered internals, the wire-codec and reconnect conventions, the
end-to-end recipes for extending the surface, the test patterns, and a decision
checklist.

## The headline invariant: structural acking

The module's central design contract is that **acknowledgement is a return value, not
an action.** The handler signature is

```
SlackEnvelope => SlackAck < (S & Async & Abort[SlackException])
```

(`Slack.connect`, `SlackConnection.receive`). The handler returns one `SlackAck`, and
the framework emits exactly one wire ack per ackable envelope from that returned value,
at a single ack-emission site. There is no public `ack`, `ackWith`, or `sendAck` method
anywhere on `Slack` or `SlackConnection`. Two failure modes are therefore
unrepresentable:

- **Forgetting to ack is a compile error.** A handler body that produces anything other
  than a `SlackAck` does not typecheck, because the result type forces a `SlackAck`
  (`SlackAckCompileTest`).
- **Double-acking has no channel to call twice.** The only way to acknowledge is to
  return the one value, and the framework emits exactly one frame from it.

The single ack-emission site is `SlackSocketEngine.deliverAndAck` ->
`SlackSocketEngine.emitAck` (`internal/SlackSocketEngine.scala`). Everything about
acking funnels through it:

- **Exactly one ack per ackable envelope.** `emitAck` reads the envelope's
  `Meta.envelopeId`; an envelope with no id (Absent) produces zero acks.
- **`ackDeadline` enforcement.** The handler is raced against `config.ackDeadline`
  (default `3.seconds`) with `Async.raceFirst` in `deliverAndAck`. If the handler
  returns first, its `SlackAck` is emitted; if the deadline fires first, the bare
  `SlackAck.Ack` is emitted and the still-running handler is race-cancelled, so a late
  payload ack never goes out. Either way exactly one ack is emitted. This race is why
  `connect[S]` / `receive[S]` carry the `Isolate[S, Abort[SlackException] & Async, S]`
  using-clause: `Async.raceFirst` requires it to lift the handler's effect environment
  across the race. The clause is auto-derived at call sites, so a caller never names it.
- **A handler that aborts or is interrupted leaves the envelope unacked.** `raceFirst`
  propagates the failure/interrupt, so no stray ack goes out; Slack re-delivers the
  envelope with `retryAttempt` / `retryReason` set on the `Meta`. The ack fires only on
  a clean return.
- **`Hello` and `Disconnect` are delivered but never acked.** They carry no `Meta` (no
  `envelope_id`), so `envelopeId` is Absent and `emitAck` is a no-op; whatever `SlackAck`
  a handler returns for them is ignored. `Hello` is delivered first (the readiness gate),
  which is the clean startup hook.

The four `SlackAck` shapes map to wire acks in `SlackWire.encodeAck`:

- `Ack`: the bare ack frame `{"envelope_id":"<id>"}`.
- `ViewResponse(action)` and `CommandResponse(message)`: the payload rides the socket ack
  inline as native Slack JSON.
- `BlockActionsResponse(message)`: the socket ack itself is **bare**; the message is
  POSTed separately to the interaction's correlated `response_url` (`emitAck` ->
  `SlackWebApi.postResponseUrl`). A payload-bearing ack returned on an envelope that does
  not accept a response payload still emits the bare ack at runtime: the payload is a no-op.

When you change anything on the receive path, preserve the single-site, exactly-once
property. Do not add a second place that puts a frame on `outbound`; route every ack
through `emitAck`.

## Architecture

Only twelve types plus `SlackException` are public; everything else is `private[kyo]`,
layered so each layer depends only on the ones below it.

| Layer | Type | Role |
|-------|------|------|
| Public surface | `Slack`, `SlackConfig`, `SlackConnection`, `SlackAck`, `SlackEnvelope`, `SlackEvent`, `SlackInteraction`, `SlackCommand`, `SlackMessage`, `SlackView`, `SlackId`, `SlackToken`, `SlackException` | The bot author's whole vocabulary. |
| Reconnect | `internal.SlackReconnect` | The single owner of engine lifecycle: constructs, swaps, and closes engines per `SlackConfig.Reconnect`. |
| Engine | `internal.SlackSocketEngine` | The receive loop, the single ack-emission site, the sender/relay fibers, the close coordination. |
| Web API | `internal.SlackWebApi` | The one canonical `request` path plus the ambient bot-token `Local`. |
| Codec | `internal.SlackWire`, `internal.SlackRawJson` | The wire DTOs and the typed-or-raw decode; native Block Kit JSON splicing. |
| Transport seam | `internal.SlackTransport` | A text-frame duplex: `live` over kyo-http, an in-memory backend for tests. |
| Handle carrier | `internal.SlackSocketHandle` | The struct behind the opaque `SlackConnection`. |

Entry points:

- `Slack.connect[S](config)(handler)` is the default: `Scope`-managed, runs the loop
  under the reconnect controller, tears everything down on scope exit. It binds the
  bot-token `Local` (`SlackWebApi.local.let(Present(config.bot))`) around the loop body
  so the handler's Web API calls resolve the token.
- `Slack.connectUnscoped(config)` returns the opaque `SlackConnection` handle for the
  caller to drive with `receive` and tear down with `close`; the caller owns teardown
  (register `Scope.ensure(conn.close)`). `receive` binds the same token `Local` and seeds
  the controller from the already-open engine, so it does not open a duplicate.

Both paths open the socket through `Slack.openEngine`: a `POST` to
`apps.connections.open` with the app-level token returns the wss url
(`SlackWire.decodeConnectionsOpen`), and `SlackSocketEngine.initUnscoped` brings the
engine up over `SlackTransport.transport.use(...)`.

The `SlackTransport` seam is the intersection of what both backends honor: `put` text,
`stream` text, `close`, `connect`, and `onPeerClose`. Socket Mode is text-only, so no
binary frame leaks into the seam. The `live` backend translates kyo-http's
`HttpException` row into the module's `Abort[SlackException]` row, so no `HttpException`
escapes to the caller. `SlackTransport.transport` is a `Local[SlackTransport]` defaulting
to `live`; tests rebind it to an in-memory conduit with `transport.let(...)`.

## Conventions specific to kyo-slack

### Public camelCase, wire snake_case

Public types are idiomatic camelCase (`SlackMessage.threadTs`,
`SlackInteraction.BlockActions.triggerId`). The wire layer uses `private[kyo]` snake_case
DTOs whose field names match the Slack wire verbatim (`envelope_id`, `trigger_id`,
`thread_ts`, `accepts_response_payload`). Those DTOs `derive Schema`, so the derived JSON
keys are the Slack wire keys with zero renaming, and `SlackWire` maps between the two
explicitly. Request-body DTOs live alongside the public methods in `Slack.scala`
(`PostMessageBody`, `ViewBody`, ...); inbound DTOs live in `SlackWire`
(`WireMessage`, `WireBlockActions`, ...).

Slack's interactive payloads are shaped differently from its Events API payloads, and the
DTOs are anchored to the real payloads, not assumed: on an interactive payload `user` and
`channel` are JSON **objects** (`{"id":...}`, read via `WireUserRef` / `WireChannelRef`),
and the `view` of a view_submission/view_closed is **nested** (id at `view.id`, form state
at `view.state`). On an Events API payload `user` is a bare string id. Match the wire when
you add a DTO; do not assume a uniform shape.

### Native JSON splicing for Block Kit and inline ack payloads

Block Kit is intentionally untyped: `SlackMessage.blocksJson`, `SlackView.blocksJson`, and
`SlackView.titleJson` carry it as raw JSON text. The Slack API expects those keys to be a
real JSON **array/object**, not a quoted string, so the raw text is parsed and re-emitted
structurally through `SlackRawJson`. The carrier holds a parsed `Structure.Value` AST and
provides a `Schema` that writes it natively via the Writer protocol; it does **not** use
`Structure.Value`'s derived enum Schema, which would serialize to a tagged shape
(`{"Sequence":{"elements":[...]}}`) that is not Slack's wire format. A malformed raw string
surfaces as a typed `SlackDecodeException` at parse time, never an invalid body on the wire.
The same native-splice path is reused for inline ack payloads (`encodeAck` reuses
`PostMessageBody` / `ViewBody`), so an ack payload is byte-shaped exactly like the
corresponding Web API call.

`SlackRawJson.nestedJson` recovers a free-form nested object (such as
`payload.view.state`) as a native JSON string by navigating the parsed frame AST, because a
typed Schema cannot decode arbitrary JSON.

### No data loss on the inbound path

Every unmodeled or malformed inbound shape is preserved, never dropped or aborted:

- An unmodeled envelope type decodes to `SlackEnvelope.Unknown(type, payloadJson)`.
- An unmodeled or malformed event decodes to `SlackEvent.Unknown(type, eventJson)`.
- An unmodeled or malformed interaction decodes to `SlackInteraction.Unknown(type, payloadJson)`.
- A `DisconnectReason` / `SlackView.Type` value the module does not model is preserved as
  `Unknown(raw)` by a hand-rolled `Schema` (the derivation macro cannot resolve a
  `Frame`-parameterised given, so these `Schema`s are built explicitly).

The receive-loop decode is **best-effort**: it never aborts. A structurally
uncorrelatable frame (not valid JSON, or no recoverable `type`) yields
`SlackWire.Decoded.Skip(reason)`, which the engine logs and skips. The one place a decode
miss becomes a typed `SlackDecodeException` is `Slack.custom`'s typed `Out` (the structural
decode site). Keep this split: do not make the loop abort on a malformed payload, and do not
silence `Slack.custom`'s decode failure.

### Typed ids and tokens

`SlackId` holds eight opaque `String` types (`ChannelId`, `UserId`, `TeamId`, `AppId`,
`TriggerId`, `EnvelopeId`, `ViewId`, `Ts`); each carries a `Schema` (over the string codec)
and a `CanEqual`. They are mutually non-assignable, so a `ChannelId` passed where a
`TriggerId` is required is a compile error. `SlackToken` holds two distinct opaque types:
`AppLevel` (the `xapp-` token that opens the socket) and `Bot` (the `xoxb-` token that signs
the Web API). They are not interchangeable, so a bot token can never open the socket. Tokens
carry **no** `Schema` and **no** secret-rendering `toString`: they ride the `Authorization`
header and the connect body only, never a decoded frame or a log line, and no
`SlackException` message renders a token (a message names the Slack error code and the
offending frame).

### Typed error hierarchy

`SlackException` is a sealed base over `KyoException` with six final leaves, modeled on
`HttpException` (a flat base plus leaves; six leaves do not earn the intermediate
subcategory layer). The leaves: `SlackHandshakeException`, `SlackTransportException`,
`SlackDecodeException`, `SlackWebApiException` (carries the Slack `error` code),
`SlackRateLimitException` (carries the parsed `retryAfter`), `SlackTerminalException`. Every
Web API and connection failure surfaces as one of these in the `Abort` row; no
`HttpException`, no `null`, no masked-success (`ok:true`-on-failure) leaks. Slack signals
API errors as HTTP 200 + `{"ok":false}`, so `SlackWebApi.request` uses `failOnError = false`
and branches on the decoded `ok`; a 429 maps to `SlackRateLimitException`; a transport
`HttpException` is recovered into `SlackTransportException`.

### Safe by default: no unsafe tier

`kyo-slack` has **no** `AllowUnsafe` usage and **no** unsafe-tier surface anywhere in
`shared/src/main`. There is no bridging boundary in this module that needs it: the transport
seam already isolates the kyo-http interaction, and every concurrency primitive
(`Channel`, `Fiber`, `AtomicRef`, `AtomicBoolean`, `Fiber.Promise`) is used through its safe
API. Do not introduce an unsafe-tier method; if you think you need one, the boundary belongs
in kyo-http, not here.

## Reconnect close-coordination (the no-loss invariant)

On a routine `disconnect` (`Warning` / `RefreshRequested`), the connection rolls over per
`SlackConfig.Reconnect`. The controller (`SlackReconnect.Controller`) is the **single owner
of engine lifecycle**: it is the only code that constructs, swaps, and closes engines; the
receive loop reads the active-engine ref but never closes one. The hard guarantee is that
**no inbound frame and no ack is lost across a rollover.** The pieces that deliver it:

- **Overlap brings the new socket up before stopping the old one.** `rotate` (overlap
  = true) opens the new engine and awaits its readiness gate, switches the active ref, then
  drains the old engine and closes it. Both sockets are briefly live, so there is no instant
  where neither reads (no gap). `Immediate` accepts a gap for *new* frames but still drains
  the old engine's already-buffered residue first, because those frames were already
  received and dropping them would lose them.
- **A single idempotent `closeInbound` captures the buffered residue once.** It does a plain
  channel `close` (the variant that returns the buffered residue and fails the loop's pending
  take with `Closed`, needing no consumer) and publishes that residue into the
  `inboundResidue` promise exactly once. Both the relay's raced completion and the
  controller's rotation drain go through it, so they never race a plain `close` against a
  `closeAwaitEmpty` on the same channel. `closeAwaitEmpty` is never used on a path with no
  live consumer.
- **The sender runs on its own fiber, never a race leg.** So an inbound-side event cannot
  interrupt it mid-forward. `drainBufferedInbound` re-delivers the captured residue through
  the same decode + dedup + deliver + ack path, and `closeTransport` flushes the drain's acks
  (`outbound.closeAwaitEmpty` then await `senderDone`, which the sender completes only after
  its last `conn.put` returned) to the still-live old socket **before** closing it. Awaiting
  `senderDone` rather than just `closeAwaitEmpty` is the ordering contract: `closeAwaitEmpty`
  returns once the buffer is empty, but the sender may still be mid-`put`.
- **The overlap dedup is a separate mechanism from the drain.** `OverlapDedup` is a bounded
  rolling seen-`envelope_id` window over two engine generations (prior + current). A
  re-pushed id present in either set is acked but not re-delivered; `advance` rolls the
  window forward at a rotation so it never becomes a lifetime accumulator (an id re-pushed two
  rotations later *is* delivered again). The dedup only suppresses a re-delivered id; it
  cannot replay a silently dropped frame, which is why the residue drain exists as well.
- **An abnormal peer close (transport EOF, no disconnect frame) is distinguished from an
  intentional teardown by `intentionalClose`.** The relay closes `inbound` on its raced
  completion; the loop reads the resulting `Closed` and, if `intentionalClose` is false,
  rotates per policy (or ends under `Off`) rather than hanging.
- **`link_disabled` is terminal under every policy.** The loop aborts with
  `SlackTerminalException` regardless of `Overlap` / `Immediate` / `Off`; it never hangs
  silently on a terminal disconnect.

When you touch reconnect or teardown, keep the controller the sole closer of engines, keep
`closeInbound` the single idempotent residue capture, and keep the sender off the race so the
flush completes.

## Extension recipes

### Add a new `SlackEvent` kind end-to-end

Say Slack ships a `pin_added` event you want typed.

1. **Public leaf.** Add `case class PinAdded(...)` to `SlackEvent`
   (`SlackEvent.scala`) with camelCase fields, `derives Schema, CanEqual`. Use typed
   `SlackId.*` for ids, `Maybe` for optional fields.
2. **Wire DTO.** Add a `WirePinAdded` to `SlackWire` with snake_case fields matching the
   Slack wire (all `Maybe`, defaulting `Absent`), `derives Schema`. Anchor the field names to
   the real `payload.event` shape.
3. **Dispatch.** Add a `case "pin_added" => decodePinAddedEvent(frame, eventJson)` arm to
   `SlackWire.decodeEvent`, and write `decodePinAddedEvent` modeled on
   `decodeMessageEvent`: decode through `SlackPayloadEnvelope[EventLeafEnvelope[WirePinAdded]]`,
   require the mandatory fields, and on a missing field log and fall through to
   `SlackEvent.Unknown("pin_added", eventJson)` (no data loss).
4. **Tests.** Add a decode case to `SlackWireTest` (a real frame -> the typed leaf) and a
   malformed-frame case asserting the `Unknown` fallback. Add a leaf-construction case to
   `SlackEventTest` if the public leaf needs its own round-trip coverage.

No ack changes are needed: an `EventsApi` envelope is already ackable, and the engine acks it
through the existing single site. Add a `SlackInteraction` kind the same way against
`decodeInteraction` and the per-interaction DTOs, capturing a `response_url` only if the kind
carries one (block_actions, message_action).

### Add a Web API method

Say you want `Slack.reactionsAdd`.

1. **Request/response DTOs.** Add `private[kyo] case class ReactionsAddBody(...)` and a
   response DTO to `Slack.scala` with snake_case wire field names (`channel`, `timestamp`,
   `name`, ...), `derives Schema`. If the body carries Block Kit, type the blocks field as
   `Maybe[SlackRawJson]` and parse the raw string with `Slack.parseBlocks` so it splices as a
   native array.
2. **Public method.** Add `def reactionsAdd(...)(using Frame): <Out> < (Async &
   Abort[SlackException])` that delegates to `SlackWebApi.request[ReactionsAddBody, Resp](
   "reactions.add", ReactionsAddBody(...))` and maps the response to typed ids. Give it an
   explicit return type. Do not duplicate the request logic; `request` is the one canonical
   path.
3. **Tests.** Add cases to `SlackWebApiTest` driving `mapResponse` / `decodeOut` directly with
   real values (ok:true, ok:false carrying an `error`, a 429 with `Retry-After`), and a
   `.notNative` round-trip in `SlackWebApiLiveTest` against an in-process server asserting the
   request body shape (native blocks array, snake_case keys).

For a one-off call a contributor does not want to model, `Slack.custom[In, Out]` already
exists; reach for a named method only when the method is part of the module's vocabulary.

## Test patterns

- **Base class.** Every test extends `kyo.test.Test[Any]` (the module `Test` base), never
  ScalaTest directly. Test files follow the 1:1 source-prefix rule (`Slack.scala` ->
  `SlackTest.scala`; `SlackWire.scala` -> `SlackWireTest.scala`); aspect splits keep the
  source prefix (`SlackAckCompileTest`, `SlackIdCompileTest`).
- **Cross-platform conduit, no mocks.** The default test transport is the in-memory
  `SlackTransport` conduit: real Slack wire frames, the real decode/deliver/ack logic, real
  recorded ack frames. `SlackSocketEngineTest`, `SlackReconnectTest`, `SlackWireTest`,
  `SlackWebApiTest`, and `SlackTransportTest` run on all four platforms (JVM, JS, Native,
  Wasm) through it. The conduit keeps its inbound source open after the scripted frames (a
  real WebSocket stays open until close) so the raced sender/receiver does not tear down
  before the test drains the recorded acks; the test closes the conduit explicitly to end the
  loop.
- **Live tests are `.notNative`-gated.** `SlackSocketEngineLiveTest`, `SlackWebApiLiveTest`,
  and `SlackTransportLiveTest` run a real in-process kyo-http WebSocket/HTTP server and
  exercise the real OS socket byte-transport over `SlackTransport.live`. Each leaf is
  `.notNative in { ... }` because the in-process server runs on JVM/JS/Wasm but not Native,
  mirroring how kyo-http gates its own server suite. The cross-platform conduit covers the
  decode/deliver/ack logic on all four platforms; the live tests cover only what the in-memory
  path cannot (the real socket bytes, the real `apps.connections.open` HTTP round-trip).
- **Deterministic latches, no sleep-as-witness.** Timing is driven by `Channel` / `Fiber` /
  `Latch` / `Fiber.Promise` handoffs and bounded takes (`delivered.stream().take(n).run`),
  never `Thread.sleep` or a sleep used as a witness. The reconnect conduit's per-frame `tap`
  releases a latch when the relay pulls a named frame, giving a test a happens-before "the
  residue is buffered" without a sleep. Teardown is asserted as an **observed** event (a
  server-side latch released when the client socket closes), not a log line. Every loop
  terminates deterministically: a routine disconnect ends a leg, a delivery channel drains a
  known count, `link_disabled` aborts.
- **Compile-fail surface.** The structural-acking and typed-id/token contracts are asserted
  with `typeCheckFailure` (`SlackAckCompileTest`, `SlackIdCompileTest`,
  `SlackTokenCompileTest`): a handler returning a non-`SlackAck` does not compile, no public
  `ack`/`sendAck` resolves, a wrong id or token type is rejected.

## Decision checklist for a contributor

- [ ] Does the change keep acking a return value? No new path may put a frame on `outbound`;
      route every ack through `SlackSocketEngine.emitAck`, the single site.
- [ ] If you added a handler-path API, does it carry the `Isolate[S, Abort[SlackException] &
      Async, S]` using-clause so the `ackDeadline` race composes?
- [ ] New public type: camelCase fields, typed `SlackId.*` for ids, `Maybe` for optional,
      `derives Schema, CanEqual`. New wire DTO: snake_case matching the real Slack payload,
      `derives Schema`.
- [ ] New inbound kind: does it fall through to a typed `Unknown` (preserving raw JSON) on an
      unmodeled or malformed payload, never an abort? Does the loop still never abort on a
      decode miss?
- [ ] Block Kit / inline ack payload: spliced as native JSON via `SlackRawJson`, not a quoted
      string and not the tagged `Structure.Value` enum shape?
- [ ] Reconnect/teardown touched: is the controller still the only closer of engines, is
      `closeInbound` still the single idempotent residue capture, and is the sender still off
      the race so the flush completes?
- [ ] Web API method: delegates to the one canonical `SlackWebApi.request`, explicit return
      type, typed `SlackException` on every failure, no `HttpException` leak?
- [ ] No token rendered in any message or log line; no `Schema`/`toString` added to a token
      type.
- [ ] No `AllowUnsafe` and no unsafe-tier method introduced.
- [ ] Tests: extend `kyo.test.Test`, drive the in-memory conduit with real frames (no mocks),
      use deterministic latches (no sleep-as-witness), and `.notNative`-gate only the leaves
      that need the real in-process server.
