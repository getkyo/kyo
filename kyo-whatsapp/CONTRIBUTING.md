# Contributing to kyo-whatsapp

Module-specific guide for kyo-whatsapp. Read the repository-root
[CONTRIBUTING.md](../CONTRIBUTING.md) first: it carries the conventions, naming rules, type
vocabulary, test patterns, and the unsafe-boundary tiers that apply across all of Kyo. This
document records only what is specific to kyo-whatsapp: the client and its own HTTP settings,
the public-vs-wire split, the failure tree and how each leaf is reached, the values validated
at construction, the byte-exact webhook contract, the pure cross-platform HMAC, and the test
patterns.

## What kyo-whatsapp is

kyo-whatsapp is the WhatsApp Business Cloud API integration: outbound verbs (`WhatsApp.send`,
`sendTemplate`, `markRead`, `markReadWithTyping`, `custom`, and the `WhatsAppMedia` verbs) and an
inbound webhook (`WhatsAppWebhook.verificationHandler`, `handler`, `verify`, `decode`). It sits at
the Applications layer and builds on kyo-http: every call goes through an `HttpClient`, and the
webhook handlers are kyo-http `HttpHandler` values mounted on the caller's `HttpServer`.

One public type per file, `WhatsApp`-prefixed, in package `kyo`, so `import kyo.*` reaches the
whole API and each file pairs with its `WhatsAppXTest`. `WhatsAppException.scala` is the one
file holding many public types: the whole failure hierarchy. The internals live in
`kyo.internal.whatsapp` (`Graph`, `Codec`, `Wire`, `Hmac`, `Secret`), each `private[kyo]`.

## The client

`final class WhatsApp` is the client value: the config and the module's own `HttpClient`,
built only by the module. Every verb is a function on the companion whose row carries
`Env[WhatsApp]`, so a verb used where no client is provided does not compile.
`WhatsApp.let(config)(v)` builds a client for `v` inside `Scope.run`, and
`WhatsAppWebhook.handler(config, webhook)(f)` builds one in the enclosing `Scope` and provides
it to every call of `f`. There is no `run` and no `init`: inbound arrives by webhook and the
client holds no connection of its own. Do not add a lifecycle the platform does not have.

The client's `HttpClient` is never the caller's. Every request runs under
`HttpClient.let(client.http)(HttpClient.withConfig(WhatsAppConfig.httpConfig(config))(...))`,
and `httpConfig` sets every field of `HttpClientConfig`: no redirects, no retry, no base URL,
no filter, default TLS. A caller's filter would see the token, a caller's base URL would
resolve a relative media URL, and a caller's TLS setting would govern the connection that
carries the token. A new field of `HttpClientConfig` gets an explicit value there.

`internal/whatsapp/Graph.scala` is the one request path:

- `Graph.call[F]` runs a request, answers the body of a 2xx, and turns a non-2xx into a leaf
  of `F` through `statusFailure`. Every request reads its answer with `failOnError = false`,
  so kyo-http never raises a status failure (its `HttpStatusException` holds the path and the
  body). A new verb uses a response-returning form and goes through `Graph.call`.
- `Graph.transport` maps a kyo-http failure to `WhatsAppTransportException` and keeps nothing
  of it but kyo-net's cause of a connection that could not be made, since every kyo-http leaf
  names the request URL (for a pre-signed media URL, a credential).
- `Graph.describe` is exhaustive over kyo-http's `HttpException` with no wildcard, so a leaf
  kyo-http adds fails to compile there. Each arm is either a reachable `Kind` or
  `bug(s"<Leaf> reached a kyo-whatsapp call")` for a leaf no call can produce. A leaf is
  reachable if any input a peer controls can raise it: `HttpMalformedBodyException` (bad
  chunked framing, which a proxy can send) is `Kind.Protocol`, not a bug arm. Move an arm from
  `bug` to a kind only with a test that produces it through a local server.
- kyo-http's HTTP/1 client completes a response it never read with a panic of an anonymous
  `IOException("connection closed") with NoStackTrace`. `Graph.transport` matches exactly that
  shape and message as `Kind.NoResponseHead`; every other panic passes through, since a typed
  failure would hide a defect. The match is on shape and message because kyo-http exports no
  type for that failure.

## The public-vs-wire split

The public ADTs (`WhatsAppMessage`, `WhatsAppInteractive`, `WhatsAppTemplate`,
`WhatsAppContact`, `WhatsAppMedia.Source`, `WhatsAppNotification`) do not mirror the Cloud API
JSON. `internal/whatsapp/Wire.scala` is the 1:1 mirror: one case class per JSON object,
snake_case fields. `internal/whatsapp/Codec.scala` is the only place the two meet, and its
functions are pure (`Span[Byte]` and `Result` in and out, no effect row).

- An `Absent` wire field is omitted from the JSON, not written as `null`. `Wire.SendEnvelope`
  carries one populated `Maybe` per message type (set by `Codec.fill`), which is what produces
  the Cloud API's `type`-keyed shape. Keep that contract when adding a message type.
- A new outbound shape adds the public case, the `Wire` DTO and the `Codec` mapping; no wire
  field goes on a public type.
- A `Schema` refusal (`inline given Schema[T] = compiletime.error(...)`) belongs on an inbound
  type only (`WhatsAppNotification` and its parts, `WhatsAppMedia.MediaInfo`,
  `WhatsAppSendResult`, `WhatsAppContact`): the module decodes those through its wire types, and
  a caller deriving a `Schema` for one would read a shape the module does not promise. An
  outbound type has no refusal.

## Error model

`WhatsAppException` is a `sealed abstract class ...(message)(using Frame) extends KyoException`.
Every leaf is a top-level `WhatsApp...Exception` in `WhatsAppException.scala`, and builds its
message from its own typed fields. The `Frame` is in a `using` clause, so it is outside the
derived equality.

No verb fails with the base. Each verb has one sealed trait, `WhatsApp<Verb>Failure`, and a
leaf mixes in the trait of every verb that can produce it. `WhatsAppDownloadFailure` is the
supertrait of `WhatsAppResolveUrlFailure` and `WhatsAppDownloadFromFailure` and no leaf mixes it
in directly, so `download`'s row is exactly the union of its two steps. The table of
memberships lives in `WhatsAppExceptionMembershipTest`, which produces every admitted
(verb, leaf) pair through the real client; its `kindOf` matches the hierarchy exhaustively, so
a new leaf fails to compile there until the table names it.

- Graph errors are `WhatsAppApiException` leaves with `method`, `code`, `subcode`,
  `description`, `details` and `traceId`. `Graph.leafFor` holds the code table. A leaf that
  stands for one code fixes `code`; a leaf grouping several codes with one remedy carries the
  code it received. `Graph.within` keeps the named leaf only when it carries the verb's trait,
  and otherwise answers `WhatsAppOtherApiException` with the same fields. A category class
  (`WhatsAppRateLimitException`, `WhatsAppTemplateException`) exists where callers act on the
  group. Meta echoes the request in some messages, so `leafFor` replaces the token's value in
  `description` and `details`.
- `WhatsAppTransportException(method, kind, host, port, timeout)(cause)`: `Kind` declares only
  kinds a call can meet. `cause` is kyo-net's failure, outside equality and returned by
  `getCause` rather than passed to `KyoException`, whose `getMessage` would append it.
- `WhatsAppUnexpectedStatusException(method, status)`: a non-2xx whose body is not a Graph
  error. No body text is kept.
- `WhatsAppDecodeException(method, part, failure, path, position)`: a body kyo-schema rejected.
  `WhatsAppDecodeException.of` matches kyo-schema's sealed `DecodeException` with no wildcard.
  Nothing of the body is kept, since kyo-schema's exceptions quote the input. An answer whose
  types are right and whose values are not is this leaf too, at the field: an empty `messages`
  is `MissingField` at `messages`, and `{"success": false}` is `ConstructorRejected` at
  `success`. Do not add a leaf for an answer of the wrong shape. A wire type needing a stricter
  rule than its JSON shape (`Wire.FileSize`) raises kyo-schema's `ParseException` or
  `RangeException` from its reader, so it surfaces through the same leaf.
- `WhatsAppRefusedUrlException(method)`: a media URL that is not an absolute `http` or `https`
  URL on a host in printable ASCII, or a media id that is not one path segment. Nothing of the
  URL or id is copied.
- A value that cannot be built panics at construction and belongs to no row:
  `WhatsAppInvalidConfigException`, `WhatsAppInvalidTokenException`,
  `WhatsAppInvalidPathException`, `WhatsAppInvalidWebhookConfigException`. None renders the
  text it refused, only its position or the setting.

`getMessage` renders the source lines around the leaf's `Frame` in development mode. Tests
compare leaves by equality and assert text with `contains`; a test that checks a secret stays
out of a rendering builds the secret away from the construction line (`Seq(...).mkString`),
since those lines are part of the message. A log line uses the leaf's one-line text
(`WhatsAppDecodeException.show`), never `getMessage`.

## Values validated at construction

A value the module cannot use panics when built, so an invalid one never reaches the wire.
Each check names the reason the value cannot be carried, and bounds nothing the platform does
not document:

- `WhatsAppToken` and `WhatsAppAppSecret` (`Secret.check`): non-empty, printable ASCII without
  space. Meta documents no alphabet and no length, and an app access token is
  `{app-id}|{app-secret}`. A CR or LF would end the `Authorization` header.
- `WhatsAppVerifyToken`: non-empty only, since an empty token would accept any handshake. The
  module never sends it; it compares what Meta echoes from the dashboard field, which has no
  documented bound.
- `WhatsAppConfig`: see its scaladoc. `WhatsAppConfig.absoluteProblemOf` is also the media URL
  refusal, so the base URL and a media URL follow one rule.
- `WhatsAppPath` (the `custom` path): relative segments of `A-Z a-z 0-9 . _ -`, no `.` or `..`
  segment. A `?`, `#`, `%` or `..` would move the token to another URL.
- `WhatsAppWebhookConfig.path`: no `?`, `#`, space, control character or non-ASCII. kyo-http's
  router splits the literal on `/` and matches request paths split before `?`, so such a path
  never matches.

`WhatsAppConfig` has no setters; `copy` runs the same validation.

## Unknown inputs decode degenerate, never abort

- An unrecognized message type decodes to `Content.Unknown(type, payload)`; an unrecognized
  status to `Status.Other(value)`; an unrecognized send status to
  `WhatsAppSendResult.Status.Other(value)`.
- An unrecognized change field, a `messages` change without `metadata` or whose metadata or
  contacts do not decode, and a message or status that does not decode or whose `timestamp` is
  not ASCII digits within the range of an `Instant` decode to
  `WhatsAppNotification.Unknown(type, payload, metadata)`, the item's own JSON kept. Each
  message and status decodes on its own, so one that does not decode never takes the rest of
  the POST with it: Meta treats a 200 as delivered and resends nothing. Only a body that is not
  the envelope (not JSON, no `entry`) is a decode failure. No value stands for a missing one:
  no empty phone number id, no zero timestamp.
- `WhatsAppRawJson` holds a raw payload; its `toString` renders the length only, since the text
  is the sender's data.

`WhatsAppWebhook.decode` fails only on a body that is not a notification envelope (including
one without `entry`, or an entry without `changes`). A new message type adds the recognized
case and keeps the fallback.

## Webhook contract: byte-exact body, signature over raw bytes

- `handler` reads the body as raw bytes (`bodyBinary`) so the HMAC sees what arrived. Nothing
  re-decodes or re-serializes the body before `verify`.
- `verify` is total and pure: `Result[WhatsAppWebhookVerifyFailure, Unit]`, the digest compared
  in constant time.
- `verificationHandler` compares the echoed verify token in constant time over its UTF-8 bytes.
- `handler` answers 403 to a signature failure without decoding, and 200 plus one log line to a
  body that does not decode, since Meta redelivers a non-200 forever. `decode` on the same
  bytes still fails; keep both halves together.
- A callback that fails or panics is not caught: the server answers 500 and Meta redelivers the
  whole POST. Deduplication is the caller's, stated in `handler`'s scaladoc.
- `handler`'s error type is the callback's `E` and nothing of the module's.

## Pure cross-platform HMAC

`internal/whatsapp/Hmac.scala` is a pure-Scala SHA-256 and HMAC-SHA256, following kyo-http's
`Sha1`. `java.security.MessageDigest` and `javax.crypto.Mac` are absent on Scala Native and JS,
and a per-platform shim would be a platform split, so the pure implementation is the design.
The digest and the verify token are compared with kyo-data's `Span.constantTimeEquals`, which
walks the whole width whatever the first difference and returns early only on unequal lengths.
`Hmac` is the minimum the signature check needs; do not grow it into a crypto facility.

## Exclusions by construction

`WhatsAppMedia.Source` (`ById` or `ByLink`) and `WhatsAppInteractive.Flow.Ref` (`ById` or
`ByName`) are sealed unions, so the Cloud API's "exactly one of two fields" cannot be broken. A
new "exactly one of N" field is a sealed union, and the codec writes one wire key per case.
`Flow.action` has no default: a flow's first step is the caller's decision.

## Identifiers

The five string ids are opaque types under `WhatsAppId` (`WabaId`, `PhoneNumberId`, `MediaId`,
`MessageId`, `WaId`), each with `apply`, `value`, `CanEqual`, and a `Schema` built with
`Schema.stringSchema.transform`. A new id-shaped string is a new opaque type there.

## Cross-platform layout

Source is `shared/src` only; the module builds for JVM, JS, Scala Native and Wasm. Hosting the
webhook server carries kyo-http's `HttpServer` constraints (Node.js on JS, OpenSSL for TLS on
Native); the verbs need only kyo-http's client.

## Tests

Tests extend `BaseWhatsAppTest` and live in `shared/src/test`, with no platform gate.

- Tests test the features: a verb is tested by calling it against a local
  `HttpServer.init(0, "localhost")` and comparing the whole answer or leaf by equality. A
  transport condition kyo-http's server cannot produce (a close before the head, bad chunked
  framing) uses the raw listener in `WhatsAppTest.sendAgainstClosingServer`.
- No `typeCheck` or `typeCheckFailure` pins a signature or a membership: the code that uses a
  signature proves it compiles, and the membership test produces each pair at run time. A
  `typeCheckFailure` stays only where a user could write the rejected code and the rejection is
  the module's, asserting the module's own message (`WhatsAppNotificationTest`'s `Schema`
  refusal).
- `internal/whatsapp/HmacTest` checks the NIST SHA-256 and RFC 4231 HMAC-SHA256 vectors.
- Readiness and delivery are witnessed with a `Channel`, never a sleep.

## Pre-submission checklist (kyo-whatsapp-specific)

- [ ] A new verb goes through `Graph.call` with `failOnError = false`, has its own failure
      trait, and a row in the membership table produced through the real client.
- [ ] A kyo-http leaf is a reachable `Kind` or a `bug` arm, decided by whether a peer can raise
      it; `Graph.describe` stays without a wildcard.
- [ ] A new failure is a top-level `WhatsApp...Exception` whose message comes from its fields
      and holds no URL, body or secret.
- [ ] An answer of the wrong shape is a `WhatsAppDecodeException` at its field.
- [ ] A construction check bounds only what the platform documents or what the wire cannot
      carry.
- [ ] A new inbound type keeps the degenerate fallback, and has a `Schema` refusal; an outbound
      type has none.
- [ ] The webhook body stays byte-exact up to `verify`.
- [ ] New tests use a local server or a published vector and compare whole values.
