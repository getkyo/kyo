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
[A] => SlackEnvelope[A] => A < (Async & Abort[E] & Env[Slack])
```

(`Slack.receive`, on the client in the environment), where `E` is whatever the handler can
fail with. `A` is the answer the envelope requires: the four
`SlackEnvelope.Acknowledged` cases are `SlackEnvelope[SlackAck]` and the three `SlackEnvelope.Plain`
cases (`Hello`, `Disconnect`, `UnknownFrame`) are `SlackEnvelope[Unit]`. Matching a case, or a type test on one of
the two sub-traits, refines `A`, so the handler returns one `SlackAck` exactly where Slack waits for
one, and the framework emits exactly one wire ack per ackable envelope from that returned value, at
a single ack-emission site. There is no public `ack`, `ackWith`, or `sendAck` method anywhere on
the `Slack` object or a `Slack` client. Two failure modes are therefore unrepresentable:

- **Forgetting to ack is a compile error.** An arm for an `Acknowledged` case that produces
  anything other than a `SlackAck` does not typecheck, nor does an ack for a `Plain` case, nor a
  bare `case _`, which leaves `A` unrefined. A handler's
  fallback is therefore the two type tests `case _: SlackEnvelope.Acknowledged` and
  `case _: SlackEnvelope.Plain`; keep the sub-traits the exact grouping by answer type.
- **Double-acking has no channel to call twice.** The only way to acknowledge is to
  return the one value, and the framework emits exactly one frame from it.

The single ack-emission site is `SocketEngine.deliverAndAck` ->
`SocketEngine.emitAck` (`internal/slack/SocketEngine.scala`). Everything about
acking funnels through it:

- **Exactly one ack per ackable envelope.** `emitAck` reads an `Acknowledged` envelope's
  `envelopeId`; a `Plain` envelope has none and produces zero acks.
- **`ackDeadline` enforcement.** The handler is raced against `config.ackDeadline`
  (default `2500.millis`, inside Slack's 3 second window) with `Async.raceFirst` in `deliverAndAck`. If the handler
  returns first, its `SlackAck` is emitted; if the deadline fires first, the bare
  `SlackAck.Ack` is emitted and the still-running handler is race-cancelled, so a late
  payload ack never goes out. Either way exactly one ack is emitted. The engine takes the
  handler with an `Isolate[S, Abort[E] & Async, S]`, which `Async.raceFirst` requires to lift
  the handler's effects across the race, and `receive` instantiates it at `S = Any`. The public
  handler row is closed on purpose: kyo's `Abort` is contravariant, so with an open `S` the
  handler's failure fits either `E` or `S`, and under an enclosing `Slack.run` inference puts it
  in `S`, which no `Isolate` lifts. With the row closed, `E` is the handler's own failure wherever
  the `receive` expression sits, so `Slack.run(config)(Slack.receive(handler))` needs no type
  arguments.
- **One outcome policy, in one place.** Every handler invocation goes through
  `SocketEngine.runHandler`, whatever the envelope (ackable, `hello`, `disconnect`, or one
  replayed by a rotation drain), so a new call site cannot drift from the policy:

  | Handler outcome | Acknowledgement | Loop |
  |---|---|---|
  | returns a `SlackAck` in time | that ack | continues |
  | still running at `ackDeadline` | the bare ack; the handler is interrupted | continues |
  | a typed failure `E` | none | ends with that failure |
  | a panic | none; logged at error with the envelope's type and id | continues |
  | an `Interrupted` observed by the handler, or the loop itself interrupted | none | propagates |

  The ack fires only on a return, so Slack redelivers every envelope that failed or panicked,
  with `retryAttempt` / `retryReason` set on the envelope. A typed failure ends the loop because it
  is the handler's declared outcome and its one way to stop the bot on purpose; to go on past a
  failing envelope, the handler recovers itself and returns `SlackAck.Ack`. A panic does not end
  the loop because a defect triggered by one poisoned envelope would otherwise stop the bot
  again on every redelivery. An interruption is never logged as a panic: it is the loop or the
  deadline stopping the handler, not a defect in it. A `link_disabled` disconnect still ends the
  loop with `SlackLinkDisabledException` after its handler panics.
- **The overlap dedup remembers an id only once its ack went out.** A push of an id whose
  handler panicked is delivered again, whether or not Slack reuses the id on a redelivery,
  which Slack does not document.
- **A frame without an `envelope_id` is delivered but never acked.** `Hello` and `Disconnect`
  carry no id, so their answer type is `Unit` and `Slack.answering` hands the engine a bare ack
  it never emits; `UnknownFrame`, the unmodeled frame without an id, is `Plain` for the same
  reason, so no handler is made to produce an ack nothing will send. An `Unknown` has an
  `envelope_id`, carries it and the frame's other keys, and is acked like any envelope, since Slack delivers an unacked
  envelope again. `Hello` is delivered first (the readiness gate), which is the clean startup hook.

The three `SlackAck` shapes map to wire acks in `Wire.encodeAck`, each one frame on the socket:

- `Ack`: the bare ack frame `{"envelope_id":"<id>"}`.
- `ViewResponse(action)` and `CommandResponse(visibility, text, blocks)`: the payload rides the socket
  ack inline as native Slack JSON, when the envelope accepts one (below).

The engine never sends anything but the ack. An answer through a `response_url` is an ordinary
call with a row (`Slack.respondEphemeral` and its three siblings), made by the handler, forked
when it can outlast `ackDeadline`, so the loop never waits on it.

Socket Mode says an envelope's `accepts_response_payload` determines whether its ack can carry a
payload. `emitAck` decides from the envelope's `acceptsResponsePayload`: `Present(false)` sends the bare
ack for a `ViewResponse` or `CommandResponse` and logs at warn which kind it withheld, never the
content; `Present(true)` and `Absent` (a frame that does not say) send the payload the handler
returned. A handler cannot be told at compile time which envelope accepts a payload, so the
runtime decision and its log line are the contract.

`encodeAck` is total. Every payload it renders is already valid: a `SlackBlock.Raw` was parsed
when it was built.

When you change anything on the receive path, preserve the single-site, exactly-once
property. Do not add a second place that puts a frame on `outbound`; route every ack
through `emitAck`. Only `closeTransport` and `closeNow` close `outbound`, and both set
`intentionalClose` first, so an ack put that meets `Closed` means the engine was torn down while
the handler ran: `emitAck` drops that ack, and the loop ends
on its next take of the closed `inbound`. Keep teardown the only closer of `outbound`.

## Architecture

Only the public-surface row below is public. Everything else lives in package
`kyo.internal.slack` and is `private[kyo]`, layered so each layer depends only on the ones
below it. `Slack` is the client class, whose fields are all `private[kyo]`, and its companion
object, where the entry points and every verb live; `SlackTs` is a top-level opaque
message-timestamp id.

| Layer | Type | Role |
|-------|------|------|
| Public surface | `Slack`, `SlackConfig`, `SlackTs`, `SlackMethod`, `SlackAck`, `SlackEnvelope`, `SlackEvent`, `SlackInteraction`, `SlackCommand`, `SlackMessage`, `SlackReply`, `SlackView`, `SlackBlock`, `SlackId`, `SlackToken`, `SlackResponseUrl`, `SlackRawJson`, `SlackException` | The bot author's whole vocabulary. |
| Reconnect | `internal.slack.Reconnect` | The single owner of engine lifecycle: constructs, swaps, and closes engines per `SlackConfig.Reconnect`. |
| Engine | `internal.slack.SocketEngine` | The receive loop, the single ack-emission site, the sender/relay fibers, the close coordination. |
| Web API | `internal.slack.WebApi` | The one canonical `request` path over the client's own `HttpClient`, and the `response_url` POST. |
| Codec | `internal.slack.Wire` | The wire DTOs and the typed-or-raw decode over the frame's `Structure.Value`, read by kyo-schema-json. |
| Transport seam | `internal.slack.Transport` | A text-frame duplex: `live` over the client's kyo-http client; tests supply in-memory conduits. |
| Connection carrier | `internal.slack.Reconnect.Controller` | Held by a client from `init` for its whole life: its active ref names the engine current after every rotation, so each `receive` reads it and `close` closes it. |

The client is the value every verb requires: each verb's row carries `Env[Slack]`, and the verb
reads it with `Env.get[Slack]`. A client holds its config, its own `HttpClient`, the `Transport`
its sockets go through, and, when built by `init`, its Socket Mode connection. Every
request runs under `WebApi.onClient`, which is `HttpClient.let(client.http)` around
`HttpClient.withConfig(SlackConfig.httpConfig(config))`, a complete `HttpClientConfig` with every
field stated (no redirects, no filter, no base url, default TLS), so nothing of the caller's
kyo-http client or configuration reaches a request that carries a token. kyo-http reuses a pooled
connection by address, which is why the module's client is its own rather than the ambient one.

Entry points. A method that satisfies `Env[Slack]` is named `run`, and nothing else provides a
client: `receive` and every verb require `Env[Slack]` from their caller and use that client.

- `Slack.run(config)(v)` builds a client with no connection, runs `v` with it, and closes it when
  `v` ends, under its own `Scope.run`, so `Scope` is not on its row; building it cannot fail. The
  default bot is `Slack.run(config)(Slack.receive(handler))`.
- `Slack.run(client)(v)` runs `v` with a client the caller holds, and leaves it open.
- `Slack.init(config)` answers a client with its connection open, closed when its `Scope` ends;
  it delegates to `Slack.initUnscoped`, whose connection only `Slack.close` ends, per the root
  guide's closeable resource pattern.
- `Slack.receive(handler)` runs the loop on the client in the environment: on one from `init` it
  seeds the controller from the already-open engine, so it does not open a duplicate; on one from
  `run(config)` it opens a connection under the reconnect controller and closes it when the loop
  ends.

Every path opens the socket through `Slack.openEngine`: a `POST` to `apps.connections.open` with
the app-level token, sent and mapped by the same `WebApi.send` as every Web API call, returns the
wss url, and `SocketEngine.initUnscoped` brings the engine up over the client's `Transport`. A url
that is not an absolute wss url on a host in printable ASCII is refused with `SlackRefusedUrlException`
before anything connects. `initUnscoped` returns once its readiness gate completes, and the gate
carries the connection and its sender fiber, so no state after readiness can lack either.

The `Transport` seam is the intersection of what both backends honor: `put` text,
`stream` text, `close`, `connect`, and `onPeerClose`. Socket Mode is text-only, so no
binary frame leaks into the seam. The `live` backend connects under the same `onClient`
configuration and translates kyo-http's `HttpException` row into `Abort[SlackTransportException]`
with the method `socket-connect`, so no `HttpException` escapes to the caller. Tests pass their
conduit to the `private[kyo]` builders `Slack.runOver` and `Slack.initUnscopedOver`, which the
public entry points call with `Transport.live`.

## Conventions specific to kyo-slack

### Public camelCase, wire snake_case

Public types are idiomatic camelCase (`SlackMessage.threadTs`,
`SlackInteraction.BlockActions.triggerId`). The wire layer uses `private[kyo]` snake_case
DTOs whose field names match the Slack wire verbatim (`envelope_id`, `trigger_id`,
`thread_ts`, `accepts_response_payload`). Those DTOs `derive Schema`, so the derived JSON
keys are the Slack wire keys with zero renaming, and `Wire` maps between the two
explicitly. Request-body DTOs live alongside the public methods in `Slack.scala`
(`PostMessageBody`, `ViewBody`, ...); inbound DTOs live in `Wire`
(`WireMessage`, `WireBlockActions`, ...).

Slack's interactive payloads are shaped differently from its Events API payloads, and the
DTOs are anchored to the real payloads, not assumed: on an interactive payload `user` and
`channel` are JSON **objects** (`{"id":...}`, read via `WireUserRef` / `WireChannelRef`),
and the `view` of a view_submission/view_closed is **nested** (id at `view.id`, form state
at `view.state`). On an Events API payload `user` is a bare string id. Match the wire when
you add a DTO; do not assume a uniform shape.

### Native JSON splicing for Block Kit and inline ack payloads

`SlackMessage.blocks` and `SlackView.blocks` are a typed `Chunk[SlackBlock]`, and
`SlackBlock.Raw` carries one block as raw JSON text. The Slack API expects `blocks` to be a
real JSON **array**, not a quoted string, so the layout is rendered, and raw text parsed,
into a `Structure.Value` that the request DTOs carry and kyo-schema writes as native JSON.
`SlackBlock.Raw.init` parses its text and fails with `SlackInvalidRawBlockException` on text
that is not an RFC 8259 JSON object; the constructor is private, so every `Raw` holds a parsed
object, rendering blocks is total and an invalid body never reaches the wire.

Raw text, a caller's block and every inbound frame alike, is read by kyo-schema-json
(`Json.decode[Structure.Value]`), whose reader holds the properties the module depends on:
RFC 8259 strictness (no leading zero, no unescaped control character, no missing or trailing
comma, no trailing content), numbers bounded at 1000 significand digits and an exponent magnitude
of 999999999, nesting bounded at kyo-schema's depth limit, decimals kept exact, and every
rejection a `DecodeException` rather than a panic. The last matters most here: a panic while
reading a frame would end the receive loop, so `Wire.decode` turns any failure into a skip and
tests pin that a frame holding a malformed or unbounded number is skipped. A rejection is
reported through `SlackDecodeException.located`: kyo-schema's leaf kind and the position its
reader stopped at, never the text, which can hold a token or what a user typed. The module
follows its reader where the grammar leaves room, so a lone surrogate escape is refused.
Inline ack payloads render blocks the way the Web API calls do (`encodeAck` builds a
`CommandReplyBody` over `Slack.replyBlocks` and reuses `Slack.ViewBody`), so an ack payload is
shaped exactly like the corresponding Web API call.

`Wire.at` navigates the parsed frame to a nested value; `Wire` encodes a free-form nested
object (such as `payload.view.state`) back to JSON text from it, and it is `Absent` when the
path is.

### No value stands for a missing one

Every wire DTO field is a `Maybe` with no other default, so a DTO decodes whatever Slack left
out, and a missing field is never replaced by `""`, `0` or `false`. The typed case is built in
one `for` over the DTO's fields, in which every id goes through `Wire.id`: an absent id and one
Slack sent as `""` are both no id, because an empty id names nothing. `Wire.typedOr` turns an
`Absent` into the kind's `Unknown` carrying the raw payload, which was taken from the frame
before decoding, so it is always whole. A field Slack may omit is a `Maybe` on the public case
(`AppHomeOpened.tab`, `ViewSubmission.View.state`, an envelope's `acceptsResponsePayload`). A text field
Slack sends empty (a slash command's `text`, a shortcut's `callback_id`) is Slack's value and
stays. On a Web API answer, a required field is a non-`Maybe` field of the response DTO, so
kyo-schema refuses an answer without it and the call fails with
`SlackDecodeException(method, Payload)`. An answer whose types are right and whose values are not
(an id the answer carries empty, a url that is not absolute) is the same leaf, built by the module
after decoding with `ConstructorRejected` at the field. Do not add a leaf for an answer of the
wrong shape.

### No data loss on the inbound path

Every unmodeled or malformed inbound shape is preserved, never dropped or aborted:

- An unmodeled envelope type, an envelope whose payload names no kind, and a slash command
  missing a field decode to `SlackEnvelope.Unknown(type, payload, meta)` when the frame has an
  `envelope_id`, and to `SlackEnvelope.UnknownFrame(type, payload)` when it has none. The
  `envelope_id` goes through `Wire.id` like every id, so an empty one is none and is never acked.
- An unmodeled or malformed event decodes to `SlackEvent.Unknown(type, payload)`, the event's JSON.
- An unmodeled or malformed interaction decodes to `SlackInteraction.Unknown(type, payload)`.
- A `DisconnectReason` the module does not model is preserved as `Unknown(raw)` by
  `Wire.decodeDisconnect`, and a disconnect that names no reason is
  `DisconnectReason.Unspecified`. A `SlackView.Type` the module does not model is preserved as
  `Unknown(raw)` by its hand-rolled `Schema`.

The receive-loop decode is **best-effort**: it never aborts. A structurally
uncorrelatable frame (not valid JSON, or no recoverable `type`) yields
`Wire.Decoded.Skip(reason)`, which the engine logs and skips. A decode miss becomes a typed
`SlackDecodeException` only on a Web API response. Keep this split: do not make the loop abort
on a malformed payload, and do not silence a response's decode failure.

### Typed ids and tokens

`SlackId` holds opaque `String` types (`ChannelId`, `UserId`, `TeamId`, `AppId`,
`TriggerId`, `EnvelopeId`, `EventId`, `ViewId`, `BotId`, `ActionId`, `BlockId`), and the message
timestamp `SlackTs` is a top-level opaque `String` type alongside them; each carries a
`Schema` (over the string codec) and a `CanEqual`. They are mutually non-assignable, so a
`ChannelId` passed where a `TriggerId` is required is a compile error.

`SlackToken` holds two secret classes, `AppLevel` (the `xapp-` token that opens the socket)
and `Bot` (the `xoxb-` token, or the rotated `xoxe.xoxb-`, that signs the Web API). They are not opaque types: an opaque
`String` renders its value wherever a value holding it is printed, so `SlackConfig.toString`
would show both secrets. Each class has a private constructor, a companion `apply`, a `value`
accessor, equality and `hashCode` over the value, a `CanEqual`, **no** `Schema`, and a
`toString` of exactly `SlackToken.AppLevel(<redacted>)` / `SlackToken.Bot(<redacted>)`. They
are not interchangeable, and a `String` is neither, so a bot token can never open the socket.
`init` validates the text against Slack's documented token shape (its prefix, `xapp-`,
`xoxb-` or `xoxe.xoxb-`, then at least one character, at most 255 in all, each printable ASCII
other than space; Slack documents no narrower alphabet) and fails with `SlackInvalidTokenException`,
whose `Problem` names a position or a length and never a character, so a token with a CR, LF or
space never reaches a header.

`SlackMethod` is the same kind of guard for `Slack.custom`: the name goes into the request url
after the base, so `init` accepts only ASCII letters, digits, `.` and `_`, and fails with
`SlackInvalidMethodException` naming the position of any other character.

The only reads of `value` build the `Authorization` header (`Slack.openEngine`,
`WebApi.request`). No `SlackException` message and no `Log` call renders a token, the
`response_url` POST sends none, and the internal holders of a config or token
(`SocketEngine`, `Reconnect.Controller`) keep the default identity `toString`.
kyo-http's exceptions carry no header values and strip a URL's query, but they keep a URL's
path, and a `response_url`'s path is itself a credential. So no kyo-http failure reaches a leaf
on any path. Every request reads the answer with `failOnError = false` (a status failure names
the url) under a config with redirects off (a redirect loop's failure lists every location), and
`WebApi.transportFailure` maps a transport failure to `SlackTransportException`'s typed fields
with an exhaustive match over kyo-http's leaves, with no wildcard, so a kyo-http leaf added
upstream fails that match's compilation until it is classified. Each arm is a reachable `Kind` or
`bug(s"<Leaf> reached a Slack call")`: a leaf is reachable if input a peer controls can raise it
(`HttpMalformedBodyException`, bad chunked framing, is `Protocol`). Move an arm from `bug` to a
kind only with a test that produces it. Every panic passes through untyped, since a typed
failure would hide a defect. `WebApi.respond`, the one path of every `response_url`
POST, also refuses a url that is not an absolute http or https url on a host in printable ASCII
with `SlackRefusedUrlException` before sending (kyo-http would resolve a relative one against a
base url, send a unix-socket one to a local socket, and its parser accepts a non-ASCII host or
path its client then refuses), and replaces the url and its path in a
code or message Slack echoes with `<response_url>`. Every text of an `ok:false` answer, on the
Web API and on a `response_url` alike (the code, `needed`, `provided` and the messages), has both
tokens replaced by `<redacted>`, raw and percent-encoded in upper- and lowercase hex, before it
reaches a leaf, since a peer can echo the request. A leaf keeps a wrapped cause only as its `getCause`, never passed to `KyoException`'s
constructor, which would embed the cause's message, formatted per environment, into a
`getMessage` that states only what failed. No leaf wraps a kyo-http or kyo-schema failure; the
only cause a leaf keeps is kyo-net's.

`SlackResponseUrl` holds the `response_url` of a slash command, a block action and a message
action, as `Maybe[SlackResponseUrl]` on `SlackCommand`, `BlockActions` and `MessageAction`. The
whole URL is the credential, so it has the token classes' shape, with `toString`
`SlackResponseUrl(<redacted>)`. It never gets a `Schema`, hand-written or derived: one would make
every holder serializable with the URL in clear.

### An inbound type's `Schema` is Slack's JSON, or there is none

A public inbound type either derives a `Schema` that reads and writes Slack's own JSON for it, or
has none. The typed `SlackEvent` cases, `SlackEvent.ReactionAdded.Item`, `SlackInteraction.Action`,
`SlackInteraction.ViewClosed`, `SlackInteraction.Shortcut` and `SlackEnvelope.Hello` derive theirs
with `@rename` on each snake_case key. A value Slack wraps in an object is held as that object, a
record with its own derived `Schema` (`SlackInteraction.User`, `ViewClosed.View`,
`Hello.ConnectionInfo`, `Hello.DebugInfo`), and a method reads the value through it (`viewId`,
`appId`, `debugHost`). A `Transformer` reading the nested key instead would have to throw to
reject a missing one, which kyo-schema does not document. A case's `Schema` omits the `type` key,
which is the root's to name.

The sealed roots, their remaining cases, `DisconnectReason`, `SlackCommand` and `Slack.Identity`
have none yet, and the module decodes them through `Wire`. Leaving out `derives Schema` is not
enough: kyo-schema's `Schema.derived` is an inline given that derives a `Schema` for any case
class on demand, in camelCase, a shape that is not Slack's. So each sealed root's companion holds
one bounded given, `inline given [U <: SlackEvent]: Schema[U] = compiletime.error("SlackEvent has
no Schema: ...")`, which is more specific than `derived` and, since a case's implicit scope
includes its root's companion, turns the summon of the root or of a case into that compile error.
A case's own derived given lives in the case's companion, which is more specific still, so it wins
over the root's refusal. A type that is not a case of a root (`DisconnectReason`, `SlackCommand`,
`Slack.Identity`) holds its own `noSchema` given. One `typeCheckFailure` leaf, on the case
`SlackEnvelope.SlashCommand`, pins the message's wording and that the root's bounded given reaches
its cases.
`SlackId.*`, `SlackTs` and the outbound types (`SlackMessage`, `SlackBlock`, `SlackView`) keep
their `Schema`.

A raw payload the module does not model is held as a `SlackRawJson`, in the three `Unknown`
cases: the caller's data, which can hold a `response_url` and Slack's verification `token`. It
has the token classes' shape except its `toString`, which renders the text's length, so the data
stays whole behind `value` and a printed envelope shows none of it. For the same reason, a log
line names an inbound frame by its size, a decoder's failure by its class, and a binary
WebSocket frame by its byte count, never by their text: a decoder's message quotes its input.

A new path with a secret in scope
gets a test that renders its failure, cause chain included, with a secret built so its full
text is absent from the test source (a development-mode message quotes the source lines around
its construction site), and asserts the secret never appears.

### Typed error hierarchy

`SlackException` is a sealed base over `KyoException` with top-level case-class leaves,
modeled on `HttpException`. Each leaf builds its message from its own typed fields, and its
equality is its first parameter list: `Frame` sits in the using-list, and a wrapped cause sits in
a second list (`SlackTransportException(method, kind, host, port, timeout)(val cause:
Maybe[NetException])`), so neither affects equality and a test asserts a failure as the whole leaf.

Each public operation fails with its own sealed trait extending `SlackException`
(`SlackInitFailure`, `SlackReceiveFailure`, `SlackAuthTestFailure`,
`SlackChatPostMessageFailure`, ..., `SlackCustomFailure`), and its row names that trait, never
`SlackException`. A leaf mixes in the trait of every operation that can produce it, so which
leaf an operation can fail with is written once, on the leaf. The private aliases in
`object SlackException` name the recurring sets: `Common` (the leaves every operation can fail
with), `Connect` (opening a Socket Mode connection: init, receive), and `ResponseUrl` (the
four `response_url` operations, `Common` and `SlackRefusedUrlException`). The receive loop itself
fails only with `SlackLinkDisabledException`. `run` has no trait: building a client opens
nothing, so its row adds no failure.
`receive` adds the handler's own failure type `E` to its row
(`Abort[SlackReceiveFailure | E]`), so a handler's failure is neither hidden nor widened.

One intermediate category exists, `SlackApiException`, for Slack's `{"ok":false,"error":code}`
answers: it exposes `method`, `code` and Slack's `messages`. Under it sits a leaf per code a
caller can act on, and `SlackOtherApiException` carrying any other code. `WebApi.leafFor` is the
one map from a code to its leaf, whatever the operation. `WebApi.within[F]` keeps that leaf when it
carries the operation's trait `F` (a `TypeTest` the compiler derives) and otherwise answers
`SlackOtherApiException` carrying the code, so a code whose leaf belongs to another operation
never reaches this one's row. The rule for giving an operation a code's leaf:

- the code is listed in the error table of that method's Slack reference page; and
- the caller's input to that operation can produce it (the token, or an argument the method
  takes). `missing_args` is never an operation's leaf, and `invalid_arguments` is not on the rows
  of `init`, `receive` and `authTest`, whose calls take no caller argument.

The response mapping in `WebApi.mapResponse` is strict and invents nothing:

- a 429 is `SlackRateLimitException`, with `retryAfter` `Absent` unless `Retry-After` is RFC
  9110's `delay-seconds` (ASCII digits only, with optional SP or HTAB around them), and
  `Duration.Infinity` for a delay longer than a `Duration` holds;
- an `ok:true` body on a non-2xx status is `SlackUnexpectedStatusException`: only a 2xx is a
  success (RFC 9110 section 15.3);
- an `ok:true` body on a 2xx whose result does not decode is `SlackDecodeException` with part
  `Payload`;
- an `ok:false` body with an `error` is the code's leaf (or `SlackRateLimitException` for
  `ratelimited` / `rate_limited`), on any status;
- any other body is `SlackDecodeException` with part `Envelope` on a 2xx, and
  `SlackUnexpectedStatusException` otherwise;
- a kyo-http failure is `SlackTransportException`, whose `method` names the call and `kind` what
  failed, never a URL.

No `HttpException` and no `null` reaches a row. A value that can be invalid is built by an `init`
returning a `Result`, with a private constructor and, for `SlackConfig`, a checked setter per
bounded field, so no main source throws; its failure is never a leaf on an operation's row:
`SlackInvalidRawBlockException` (a `SlackBlock.Raw` that is not a JSON object),
`SlackInvalidConfigException` (a zero or infinite `SlackConfig` duration, a response bound out of
range, a base url that is not an absolute http or https url), `SlackInvalidTokenException` and
`SlackInvalidMethodException`. A verb used where no client is provided does not compile, since
`Env[Slack]` is on its row. A path becomes a
panic only with evidence that a correct program cannot reach it; anything the network or the
caller's input can produce is a leaf.

### Safe by default: no unsafe tier

`kyo-slack` has **no** `AllowUnsafe` usage and **no** unsafe-tier surface anywhere in
`shared/src/main`. There is no bridging boundary in this module that needs it: the transport
seam already isolates the kyo-http interaction, and every concurrency primitive
(`Channel`, `Fiber`, `AtomicRef`, `AtomicBoolean`, `Fiber.Promise`) is used through its safe
API. Do not introduce an unsafe-tier method; if you think you need one, the boundary belongs
in kyo-http, not here.

## Reconnect close-coordination (the no-loss invariant)

On a routine `disconnect` (`Warning` / `RefreshRequested`), the connection rolls over per
`SlackConfig.Reconnect`. The controller (`Reconnect.Controller`) is the **single owner
of engine lifecycle**: it is the only code that constructs, swaps, and closes engines; the
receive loop reads the active-engine ref but never closes one. The hard guarantee is that
**no inbound frame and no ack is lost across a rollover.** The pieces that deliver it:

- **Overlap brings the new socket up before stopping the old one.** `rotate` (overlap
  = true) opens the new engine and awaits its readiness gate, switches the active ref, then
  drains the old engine and closes it. Both sockets are briefly live, so there is no instant
  where neither reads (no gap). `Immediate` accepts a gap for *new* frames but still drains
  the old engine's already-buffered residue first, because those frames were already
  received and dropping them would lose them. `Off` stops the loop the same way: it drains the
  residue and runs `closeTransport` before the loop ends, because the teardown that follows is
  `closeNow`, which drops what is still buffered.
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
  It keys on the delivery's `envelope_id`, not on the event: deduplicating events stays with
  the caller, by `EventsApi.eventId` (Slack's `event_id`), which is why every `events_api`
  envelope carries it and one without it is `Unknown`.
- **An abnormal peer close (transport EOF, no disconnect frame) is distinguished from an
  intentional teardown by `intentionalClose`.** The relay closes `inbound` on its raced
  completion; the loop reads the resulting `Closed` and, if `intentionalClose` is false,
  rotates per policy (or ends under `Off`) rather than hanging.
- **`link_disabled` is terminal under every policy.** The loop aborts with
  `SlackLinkDisabledException` regardless of `Overlap` / `Immediate` / `Off`; it never hangs
  silently on a terminal disconnect.

When you touch reconnect or teardown, keep the controller the sole closer of engines, keep
`closeInbound` the single idempotent residue capture, and keep the sender off the race so the
flush completes.

## Extension recipes

### Add a new `SlackEvent` kind end-to-end

Say Slack ships a `pin_added` event you want typed.

1. **Public leaf.** Add `case class PinAdded(...)` to `SlackEvent`
   (`SlackEvent.scala`) with camelCase fields and `derives CanEqual`; `SlackEvent`'s bounded
   given already refuses its `Schema` (see "No public inbound type has a `Schema`"). Use
   typed `SlackId.*` for ids, `Maybe` for optional fields.
2. **Wire DTO.** Add a `WirePinAdded` to `Wire` with snake_case fields matching the
   Slack wire (all `Maybe`, defaulting `Absent`), `derives Schema`. Anchor the field names to
   the real `payload.event` shape.
3. **Dispatch.** Add a `case "pin_added"` arm to `Wire.decodeEvent`, as the `message` arm is
   written: decode with `read[WirePinAdded](tree, "payload", "event")`, build the
   leaf in one `for` over the DTO's fields with every id through `Wire.id`, and pass the result to
   `event(...)`, which turns a missing field into `SlackEvent.Unknown("pin_added", payload)`
   with a warn line (no data loss).
4. **Tests.** Add a decode case to `WireTest` (a real frame -> the typed leaf), a row per
   required field to its table-driven `Unknown` leaves.

No ack changes are needed: an `EventsApi` envelope is already ackable, and the engine acks it
through the existing single site. Add a `SlackInteraction` kind the same way against
`decodeInteraction` and the per-interaction DTOs; if the kind carries a `response_url`, read it
through `Wire.id` into a `responseUrl: Maybe[SlackResponseUrl]` field of the public case, as
`BlockActions` and `MessageAction` do.

### Add a Web API method

Say you want `Slack.reactionsAdd`.

1. **Request/response DTOs.** Add `private[kyo] case class ReactionsAddBody(...)` and a
   response DTO to `Slack.scala` with snake_case wire field names (`channel`, `timestamp`,
   `name`, ...), `derives Schema`. If the body carries Block Kit, type the blocks field as
   `Maybe[Structure.Value]` and render the message's blocks with `Slack.messageBlocks` so they splice
   as a native array.
2. **Failure trait.** Add `sealed trait SlackReactionsAddFailure extends SlackException` under
   "Operation failures" in `SlackException.scala`, and mix it into the `Common` leaves, the
   credential leaves, and every leaf whose code the method's Slack reference page lists,
   applying the rule under "Typed error hierarchy". A code that needs a new leaf gets one under
   `SlackApiException` and an arm in `WebApi.leafFor`.
3. **Public method.** Add `def reactionsAdd(...)(using Frame): <Out> < (Async &
   Abort[SlackReactionsAddFailure] & Env[Slack])` that reads the client with `Env.get[Slack]` and
   delegates to `WebApi.request[ReactionsAddBody, Resp, SlackReactionsAddFailure](client,
   "reactions.add", ReactionsAddBody(...))`, then maps the response to typed ids, as
   `chatPostMessage` does. Give it an explicit return type. Do not duplicate the request logic;
   `request` is the one canonical path.
4. **Tests.** Add the operation to `SlackExceptionMembershipTest`'s table, so every leaf on the
   new row is produced through the method against the local server. Add the operation to `WebApiTest`'s `Verbs` facade, operation list and code
   tables, then assert the request body Slack receives (native blocks array, snake_case keys).

For a one-off call a contributor does not want to model, `Slack.custom` already exists; reach
for a named method only when the method is part of the module's vocabulary.

## Test patterns

- **Base class.** Every test extends `kyo.test.Test[Any]` (the module `Test` base), never
  ScalaTest directly. Test files follow the 1:1 source-prefix rule (`Slack.scala` ->
  `SlackTest.scala`; `internal/slack/Wire.scala` -> `internal/slack/WireTest.scala`); aspect
  splits keep the source prefix (`SlackExceptionMembershipTest`). A suite that
  plays Slack with a local server carries its source's plain test name; the `...LiveTest`
  suffix is reserved for a suite that reaches a real Slack workspace.
- **In-memory conduits for engine mechanics, no mocks.** A test-side `Transport` conduit carries
  real Slack wire frames through the real decode/deliver/ack logic and records the real ack
  frames. It keeps its inbound source open after the scripted frames (a real WebSocket stays
  open until close) so the raced sender/receiver does not tear down before the test drains the
  recorded acks; the test closes the conduit explicitly to end the loop.
- **Local servers for handler outcomes and everything past the socket.** Every handler
  outcome is tested over a local Socket Mode server that records the ack frames it received and
  is asserted on them, exact and in order. The real socket bytes over `Transport.live`, the
  `apps.connections.open` HTTP round trip, Web API request bodies and the `response_url` POST
  run against a kyo-http `HttpServer` on port 0 that plays Slack. These leaves sit in the same
  suites as the conduit leaves, with no platform gate: the in-process server runs on JVM, JS,
  Native and Wasm. Those suites set `leakCheckSockets(false)`, because the NIO transport defers
  a closed channel's fd close to the selector's next `select()`. Teardown drops acks the sender
  has not yet sent, so a test takes an ack at the server before it sends the frame that ends the
  loop.
- **Log lines are asserted through a capturing `Log.Unsafe`** installed with `Log.let`, never
  through a hook in main code. A line that could carry a secret is asserted as exact text, and
  the secret is asserted absent from everything logged, throwables included.
- **A rendering test plants its secret where the input can carry one:** in a `response_url`, in a
  `token`, and in a URL's path. A test of a call whose failure can hold the request's path runs
  against a local server that echoes that path in its body and in a `Location` header.
- **Deterministic latches, no sleep-as-witness.** Timing is driven by `Channel` / `Fiber` /
  `Latch` / `Fiber.Promise` handoffs and bounded takes (`delivered.stream().take(n).run`),
  never `Thread.sleep` or a sleep used as a witness. The reconnect conduit's per-frame `tap`
  releases a latch when the relay pulls a named frame, giving a test a happens-before "the
  residue is buffered" without a sleep. Teardown is asserted as an **observed** event (a
  server-side latch released when the client socket closes), not a log line. Every loop
  terminates deterministically: a routine disconnect ends a leg, a delivery channel drains a
  known count, `link_disabled` aborts.
- **No test restates a declaration.** A contract the types carry (structural acking, typed ids and
  tokens, each operation's row) is enforced by the compiler on every build, so no test
  re-checks it with `typeCheck`, and no test reads back a case list, a trait a type extends, or a
  derived `toString`. `typeCheckFailure` asserts only a compile error whose message the module
  writes itself.
- **One membership table.** `SlackExceptionMembershipTest` holds the one table of which leaf each
  operation can fail with, and every (leaf, operation) pair the table admits is produced through
  the public operation against a local server. A pair no path can produce is a wrong membership:
  remove the trait from the leaf rather than the pair from the test.

## The live suite

`SlackLiveTest` runs the module against a real workspace: connecting, receiving one event,
posting, a Web API error, and whether `chat.postMessage` answers `msg_too_long` for a text past
the 40,000 characters it keeps. It reads three environment variables through `kyo.System.env`,
so it runs on JVM, Scala.js, Scala Native and Wasm:

| Variable | Value |
|---|---|
| `SLACK_APP_TOKEN` | the app-level token (`xapp-`), scope `connections:write` |
| `SLACK_BOT_TOKEN` | the bot token (`xoxb-`), scopes `chat:write` and `channels:history` |
| `SLACK_CHANNEL_ID` | a public channel the bot is a member of |

Without them every leaf is cancelled with a message naming the missing variables, so a normal
run shows the suite as cancelled, never as passed.

The workspace needs an app with Socket Mode enabled, Event Subscriptions enabled with the bot
event `message.channels`, the two tokens above, and the bot invited to the channel. The suite
causes the event it receives: it posts a unique marker to the channel and waits, at most 30
seconds, for the `message` event carrying it. Slack delivers an app's own messages to it (Bolt,
Slack's framework, filters them out by default with `ignoreSelf` for that reason). The suite
deletes every message it posts, with `chat.delete` through `Slack.custom`.

Run it with the variables exported, one platform at a time:

```sh
SLACK_APP_TOKEN=xapp-... SLACK_BOT_TOKEN=xoxb-... SLACK_CHANNEL_ID=C... sbt 'kyo-slackJVM/testOnly kyo.SlackLiveTest'
```

Each scenario the suite runs lives in its companion object, and `SlackTest` runs every one
against a local server standing in for Slack, so the suite's own logic is tested on every run. A
new live leaf follows the same split.

## Decision checklist for a contributor

- [ ] Does the change keep acking a return value? No new path may put a frame on `outbound`;
      route every ack through `SocketEngine.emitAck`, the single site.
- [ ] If you added a handler-path API, does it take the polymorphic handler
      `[A] => SlackEnvelope[A] => A < (Async & Abort[E] & Env[Slack])`, with no open effect
      parameter, and require `Env[Slack]` rather than provide it?
- [ ] Does every method that satisfies `Env[Slack]` carry the name `run`, and no other method
      provide a client to a callback?
- [ ] New verb: `Env[Slack]` on its row, the client read with `Env.get[Slack]`, every request
      through `WebApi.onClient`, never the ambient kyo-http client?
- [ ] New public type: camelCase fields, typed `SlackId.*` for ids, `Maybe` for optional,
      `derives CanEqual`; an inbound type `derives Schema` with `@rename`/`@transform` to Slack's
      keys when kyo-schema's documented constructs reach its shape, else, when it is not a case of
      a sealed root, a `noSchema` given; an outbound one `derives Schema`. New wire DTO: snake_case matching the real Slack
      payload, `derives Schema`.
- [ ] New inbound kind: does it fall through to a typed `Unknown` (preserving raw JSON) on an
      unmodeled or malformed payload, never an abort? Does the loop still never abort on a
      decode miss?
- [ ] Block Kit / inline ack payload: carried as a rendered `Structure.Value`, which kyo-schema
      writes as native JSON, never as a quoted string?
- [ ] Reconnect/teardown touched: is the controller still the only closer of engines, is
      `closeInbound` still the single idempotent residue capture, and is the sender still off
      the race so the flush completes?
- [ ] Web API method: delegates to the one canonical `WebApi.request` typed by its own failure
      trait, explicit return type, no `SlackException` and no `HttpException` on its row, and a
      row in `SlackExceptionMembershipTest`'s table?
- [ ] Failure asserted in a test: compared as the whole leaf (`Result.fail(leaf)`), never by
      type alone or by a message substring?
- [ ] No token rendered in any message, log line or `toString`; `value` read only to build the
      `Authorization` header; no `Schema` on a token type, and its `toString` stays redacted.
- [ ] New public sealed trait or enum: `derives CanEqual` on the type itself, not only its cases.
- [ ] No `AllowUnsafe` and no unsafe-tier method introduced.
- [ ] Tests: extend `kyo.test.Test`, use real frames (no mocks), deterministic latches (no
      sleep-as-witness), a local Socket Mode server for any handler outcome, and a local
      `HttpServer` for anything past the socket, with no platform gate.
- [ ] Handler path touched: does every invocation still go through `SocketEngine.runHandler`, so
      a typed failure ends the loop, a panic is logged and the loop goes on, and an interruption
      propagates?
- [ ] New internal type: in `kyo.internal.slack`, `private[kyo]`, no `Slack` prefix.
