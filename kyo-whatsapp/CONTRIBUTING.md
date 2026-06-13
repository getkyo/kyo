# Contributing to kyo-whatsapp

Module-specific guide for kyo-whatsapp. Read the repository-root
[CONTRIBUTING.md](../CONTRIBUTING.md) first: it carries the conventions, naming
rules, type vocabulary, test patterns, and the unsafe-boundary tiers that apply
across all of Kyo. This document records only what is specific to kyo-whatsapp: the
public-vs-wire split that keeps the Cloud API JSON shape out of the public surface,
the total typed error tree, the pure cross-platform HMAC that fixes the crypto
platform story, the byte-exact webhook contract, the unrepresentable-by-construction
exclusions, and the real-server test pattern.

## What kyo-whatsapp is

kyo-whatsapp is the WhatsApp Business Cloud API integration: an outbound client
(`WhatsApp.send`, `sendTemplate`, `markRead`, the `WhatsAppMedia` namespace, `custom`) and an
inbound webhook surface (`WhatsAppWebhook.verificationHandler`, `verifySignature`, `decode`,
`handler`). It sits at the Applications layer of the stack and builds on kyo-http: every
outbound call goes through `HttpClient`, and the webhook handlers are kyo-http
`HttpHandler` values mounted on an `HttpServer`. Every outbound call reads an ambient
`WhatsAppConfig` bound by `WhatsApp.let` and returns a typed value or aborts with a
typed `WhatsAppError`; the effect row is `Async & Abort[WhatsAppError]` throughout.

The structure is one public type per file, `WhatsApp`-prefixed, in package `kyo`,
matching the kyo-slack layout: `WhatsAppConfig`, `WhatsAppMessage`, `WhatsAppTemplate`,
`WhatsAppInteractive`, `WhatsAppContact`, `WhatsAppMedia`, `WhatsAppNotification`,
`WhatsAppWebhook`, `WhatsAppSendResult`, `WhatsAppId`, and `WhatsAppError` are each a
sibling top-level type, so `import kyo.*` reaches the whole API and each type pairs 1:1
with its `WhatsAppXTest`. The `object WhatsApp` itself is the facade of verbs only (`let`,
`use`, `send`, `sendTemplate`, `markRead`, `markReadWithTyping`, `custom`) plus the private
config-binding helpers. The wire/codec/HMAC internals live in package
`kyo.internal.whatsapp` (`Wire`, `Codec`, `Hmac`), each `private[kyo]` so the package-`kyo`
surface can reach them while they stay off the published surface. When you add a new public
type, give it its own `WhatsAppX.scala` file in package `kyo`; when you add an internal
helper, put it in `kyo.internal.whatsapp` and mark it `private[kyo]`.

`WhatsApp` deliberately has NO `run`/`init`: it is a stateless outbound client with no
resource of its own to open, and inbound is HTTP webhooks returned as `HttpHandler` values
by `WhatsAppWebhook` (mounted on the `HttpServer` resource, which IS the lifecycle resource).
The `let`/`use` ambient-context pair is the matching kyo convention for a stateless client,
mirroring `HttpClient.let`; do not add a `run`/`init` lifecycle that does not exist.

## The public-vs-wire split

The public ADTs (`WhatsAppMessage`, `WhatsAppInteractive`, `WhatsAppTemplate`,
`WhatsAppContact`, `WhatsAppMedia.Source`, `WhatsAppNotification`) are ergonomic and do
NOT mirror the Cloud API JSON shape. The internal `Wire` DTO layer
(`internal/whatsapp/Wire.scala`, package `kyo.internal.whatsapp`) is the 1:1 mirror of that
shape: one flat-product case class per Cloud API JSON object, snake_case field names matching
the JSON keys, every DTO `private[kyo]`. `internal/whatsapp/Codec.scala` is the only place the
two layers meet; the public types never carry a wire concern.

- The codec functions are PURE: `encodeSend`, `encodeTemplate`, `encodeMarkRead`,
  `decodeSendResult`, `decodeMediaInfo`, `decodeNotifications`, `mapError`, `mapCode`,
  `mapTransportPanic` take and return plain values (`Span[Byte]`, `Result`,
  `WhatsAppError`) with NO effect row. The effect row appears only at the `WhatsApp`,
  `Media`, and `Webhook` call sites that wrap the codec around an `HttpClient` call.
- A `Maybe[T] = Absent` wire field is OMITTED from the produced JSON (no spurious `null`
  key), and a `Present(v)` emits the inline value. So a `Wire.SendEnvelope` whose body
  carries exactly one populated `Maybe` field (set by `Codec.fill`, one per `WhatsAppMessage`
  variant) serializes to precisely the nested `type`-keyed sibling shape the Cloud API
  expects. The `Absent`-is-omitted contract is load-bearing: it is what makes the flat
  envelope produce the correct nested wire JSON.
- When you add a new outbound message shape, add the public ADT variant, the matching
  `Wire` DTO (snake_case keys), and the `Codec` mapping; do not push a wire field onto a
  public type and do not give a public type a JSON-key name.

## Error model

Every client call aborts on the one typed channel `Abort[WhatsAppError]`.
`WhatsAppError` is a `sealed abstract class ...(using Frame) extends KyoException(message, cause)`,
mirroring the kyo-http `HttpException` and kyo-slack `SlackException` precedents (a sealed
abstract root extending `KyoException`, subcategory abstract classes by failure mode, a leaf
per failure mode with typed fields). Each leaf and subcategory threads `(using Frame)` so the
constructing call site is captured for the source-located message; `KyoException` is
`private[kyo]` and this hierarchy is in package `kyo`, so the base constructor is reachable.
Because `KyoException` takes constructor parameters, the singleton leaves are zero-arg case
classes (`AuthError.TokenExpired()`, `WindowClosed()`, `SignatureError.Missing()`, and so
on), not `case object`s. The `Frame` lives in a `(using ...)` clause, so it is excluded from
the derived `equals`/`CanEqual`: two `TokenExpired()` values are equal regardless of frame.

`WhatsAppError.Cloud` is a `case class derives CanEqual` that ALSO extends `KyoException`. It
is embedded in `WhatsAppNotification.StatusUpdate.errors` (a `Chunk[Cloud]`), but no `Schema`
is derived over it: that field only `derives CanEqual`, and the wire-layer error DTO
(`internal.whatsapp.Wire.ErrorDto`) is the `Schema`-bearing type, mapped to `Cloud` manually
in `Codec.decodeStatus`. So `Cloud` extending `KyoException` (which is `NoStackTrace` and
carries a `Frame`) does not affect any `Schema` derivation.

- `Codec.mapError` is TOTAL over the documented Graph error-code table. Every listed
  `error.code` maps to its named leaf: `AuthError` (`TokenExpired` / `AccessDenied`),
  `RateLimited` (with a `Scope` of `PhoneNumber` / `Waba` / `Throughput`),
  `RecipientError` (`Undeliverable` / `SenderEqualsRecipient`), `WindowClosed`,
  `TemplateError`, `MediaError`, `InvalidParameter`, `ServiceUnavailable`. The code-to-leaf
  table lives in `Codec.mapCode`.
- `WhatsAppError.Cloud` is the typed TOTAL FALLBACK for any code `mapCode` does not name.
  It carries the full decoded envelope (`code`, `subcode`, `errorType`, `message`,
  `details`, `fbtraceId`), so an unmapped code still surfaces every field. A new Graph
  code never drops to a silent failure and never collapses onto a misleading wrong leaf:
  it surfaces as `Cloud` with the raw code preserved.
- `WhatsAppError.Transport` wraps a non-Graph `HttpException` so a connect/timeout/closed
  failure is a typed value, never a silent drop. Its `cause` is a `Throwable`: an
  `HttpException` when `mapError` sees a non-status protocol failure, or a raw
  `java.io.IOException` when `mapTransportPanic` lifts a connection close that the HTTP
  layer surfaced as an untyped throwable. `mapTransportPanic` maps an `IOException` (or
  subtype) to `Transport` and RE-PANICS any other throwable so a genuine defect is not
  swallowed.
- `WhatsAppError.DecodeError` is the distinct leaf for a structurally unparseable
  response or webhook envelope. A decode/parse failure is never masked as a success
  response; it reaches the caller as a typed value. `DecodeError` is distinct from an
  unknown discriminator, which decodes to a degenerate case (see the next section), not a
  failure.
- A new code-to-leaf mapping is added in `mapCode`, and `mapError`/`mapTransportPanic`
  stay total by construction (the `Cloud` and re-panic arms catch everything else). Do not
  add a partial match that can fall through to a silent drop.

## Unknown discriminators decode degenerate, never abort

The inbound and response decoders are total over recognized shapes AND forward-compatible
over unrecognized ones. An unknown discriminator string is a DEGENERATE case, not a
failure:

- An unrecognized inbound message `type` decodes to `WhatsAppNotification.Content.Unknown(messageType, raw)`.
- An unrecognized `statuses[].status` decodes to `WhatsAppNotification.Status.Other(value)`.
- An unrecognized `changes[].field` or an entry that yields no messages/statuses decodes
  to `WhatsAppNotification.Unsupported(field, raw)`.
- An unrecognized outbound `message_status` decodes to `WhatsAppSendResult.Status.Other(value)`;
  an unrecognized inbound MIME maps to `WhatsAppMedia.MediaType.Other(mime)`.

So a Cloud API type that ships after this release does not crash a running webhook; it
arrives as a degenerate value carrying its raw JSON for logging. `WhatsAppWebhook.decode` aborts
with `DecodeError` ONLY on a structurally unparseable envelope, never on an unknown
discriminator. When you enumerate a new type, add the recognized case alongside the
degenerate fallback; never replace the fallback with a partial match that can abort.

## Pure cross-platform HMAC (the crypto platform decision)

`internal/whatsapp/Hmac.scala` is a pure-Scala SHA-256 (FIPS 180-4) and HMAC-SHA256 (RFC 2104),
integer arithmetic only, `@tailrec` loops. It exists because `java.security.MessageDigest`
and `javax.crypto.Mac` are absent on Scala Native and JS; it mirrors the kyo-http `Sha1`
precedent (`kyo-http/shared/src/main/scala/kyo/internal/util/Sha1.scala`). This is the
single reason the whole module is `shared/src` with NO platform split: a per-platform
crypto shim (JVM `javax.crypto.Mac`, JS WebCrypto, Native OpenSSL bindings) would be a
platform divergence that fails the cross-platform exception bar, so the pure
implementation is the design floor, not an optimization to revisit.

- `Hmac.constantTimeEquals` compares the FULL byte width regardless of where a mismatch
  occurs (no early exit on the first differing byte), so the expected digest is not leaked
  through a timing side channel. Unequal lengths return false but still iterate the longer
  length.
- This is not a general-purpose crypto facility; it is the irreducible minimum the
  `X-Hub-Signature-256` check needs. Do not grow it into one, and do not replace it with a
  platform-specific digest.

## Webhook contract: byte-exact body, signature over raw bytes

The webhook signature is computed over the EXACT bytes Meta sent. This is the headline
load-bearing invariant of the inbound path:

- `WhatsAppWebhook.handler` reads the request body as raw bytes (`bodyBinary`,
  `req.fields.body: Span[Byte]`) precisely so the HMAC sees what arrived on the wire. A
  transform that re-decodes or re-serializes the body before hashing changes the bytes and
  breaks verification. Any change to the handler keeps the body byte-exact between
  transport and the `verifySignature` call.
- `WhatsAppWebhook.verifySignature` is TOTAL and PURE: it returns a
  `Result[WhatsAppError.SignatureError, Unit]` with no effect row and never throws. It takes
  `(using Frame)` only because each `SignatureError` leaf carries a `Frame`; it performs no
  effect and no network. `Success(())` on a matching `sha256=`-prefixed lowercase-hex
  HMAC-SHA256 over the body; `Failure(SignatureError.Missing())` on an absent header;
  `Failure(SignatureError.Malformed())` on a missing `sha256=` prefix or a non-hex /
  odd-length remainder; `Failure(SignatureError.Mismatch())` on a constant-time-compare
  mismatch.
- `WhatsAppWebhook.verificationHandler` is the GET handshake: it echoes `hub.challenge` as a text
  body with 200 on a `hub.verify_token` match, 403 otherwise.
- `WhatsAppWebhook.handler` responds 200 even when decode fails on a structurally broken change,
  logging the decode error and skipping it rather than aborting. Meta retries a non-200
  response, so acking a payload it cannot parse prevents a poison message from being
  redelivered indefinitely. A SIGNATURE failure is the one case that returns 403, since
  that is an authentication failure, not a malformed-but-authentic payload. Note the
  asymmetry: `handler` swallows a `DecodeError` to a 200-with-log, while standalone
  `WhatsAppWebhook.decode` still surfaces `DecodeError` for the same bytes. Preserve both halves
  of that contract together.

## Unrepresentable-by-construction exclusions

Two Cloud API "exactly one of two fields" constraints are enforced at the type level so
the ambiguous-reference error cannot leave the process:

- `WhatsAppMedia.Source` is a sealed union of `ById(id)` and `ByLink(link)`. The Cloud API
  rejects a media reference that sets both an id and a link, or neither; the union makes
  both-or-neither unrepresentable, so a caller picks exactly one at construction.
- `WhatsAppInteractive.Flow.Ref` is a sealed union of `ById(flowId)` and `ByName(flowName)`, the
  same shape for the `flow_id`-XOR-`flow_name` exclusivity.

When you model a new "exactly one of N" Cloud API field, use a sealed union, not two
optional fields. The codec then writes exactly one wire key per case
(`Codec.mediaBody`, the `Flow.Ref` match in `interactiveBody`); a two-optional-field model
would let "both" and "neither" through to the wire.

## Identifiers are mutually non-interchangeable

The five Cloud API string ids live under `WhatsAppId` as distinct opaque type aliases for
`String` (`WabaId`, `PhoneNumberId`, `MediaId`, `MessageId`, `WaId`). The consumer wa_id is
`WhatsAppId.WaId` (not `WhatsAppId.WhatsAppId`, which would self-collide). Passing a
`WhatsAppId.WaId` where a `WhatsAppId.MessageId` is expected is a compile error, not a silent
wrong call to the Graph API. Each exposes `apply(String)` for construction and a `value`
extension for reading the underlying string, and derives `CanEqual` and `Schema`. The `Schema`
is derived via `Schema.stringSchema.transform[T](apply)(_.value)` (not `summon[Schema[String]]`),
so the resulting `Schema` is typed at the opaque type rather than reusing the `String` schema
by coincidence of opacity; it still serializes to and from a bare JSON string. A new id-shaped
string is a new opaque type under `WhatsAppId`, never a bare `String` parameter.

## Config binding

`WhatsAppConfig` (token, `phoneNumberId`, `apiVersion` default `"v25.0"`, `baseUrl`
default `"https://graph.facebook.com"`) is an immutable value with fluent copy-setters
that shadow each field (`config.token("...")` returns a copy). It is bound as ambient
state by `WhatsApp.let(config) { ... }`, mirroring `HttpClient.let`; nesting is allowed and
an inner binding shadows the outer. `WhatsApp.use` is the reader every verb calls.

`WhatsAppConfig` overrides `toString` to MASK the bearer token (it renders `token=***`), so
the secret never leaks into a log line, an exception message, or a REPL echo. The other three
fields render in full. When you add a field to the config, keep the masking `toString` in sync
so it never spills the token alongside the new field.

Calling any client method with no bound config falls back to a default `WhatsAppConfig` loaded from
`kyo.StaticFlag` values (kyo-config). The `Absent` branch of `WhatsApp.use` calls
`defaultConfig`, which reads four StaticFlags declared in the private `WhatsApp.flags` object:
`token` (default `""`), `phoneNumberId` (default `""`), `apiVersion` (default `"v25.0"`), and
`baseUrl` (default `"https://graph.facebook.com"`). StaticFlag derives each property key from
its object FQN, so the keys are `kyo.WhatsApp.flags.token`, `kyo.WhatsApp.flags.phoneNumberId`,
`kyo.WhatsApp.flags.apiVersion`, and `kyo.WhatsApp.flags.baseUrl` (env vars
`KYO_WHATSAPP_FLAGS_TOKEN`, `KYO_WHATSAPP_FLAGS_PHONENUMBERID`, `KYO_WHATSAPP_FLAGS_APIVERSION`,
`KYO_WHATSAPP_FLAGS_BASEURL`). An unset token yields an empty token, so the Graph call returns
a typed `WhatsAppError` auth failure rather than crashing the process. Bind an explicit config
with `WhatsApp.let(config) { ... }` whenever the per-call values differ from the flag defaults;
the StaticFlag fallback is the unbound default, never a substitute for an explicit `let` when
one is needed.

## Cross-platform layout

Source is `shared/src` ONLY. The module cross-builds JVM, JS, Scala Native, and Wasm with
no `jvm/`, `js/`, `native/`, or `wasm/` leaf for the public surface; the API is identical
on all four. The pure HMAC (above) is what makes a platform split unnecessary, so adding
one needs the same justification the root cross-platform exception bar demands.

Hosting a webhook SERVER is the one capability with a platform constraint, inherited from
kyo-http's `HttpServer` (JS needs a Node.js runtime; Native needs OpenSSL for TLS). The
outbound client and `verifySignature` host no server and need only kyo-http's client
backend, available on every target. That constraint is kyo-http's, not a kyo-whatsapp
source split.

## Tests

Tests use `BaseWhatsAppTest` (it extends `kyo.test.Test[Any]` and sets a 60-second
`HttpClient` timeout via `aroundLeaf`) and live in `shared/src/test` in package `kyo`. Each
public type pairs 1:1 with a `WhatsAppXTest` (`WhatsAppConfig.scala` with `WhatsAppConfigTest`,
`WhatsAppError.scala` with `WhatsAppErrorTest`, and so on); `WhatsAppWebhook` splits by aspect
into `WhatsAppWebhookSignatureTest` and `WhatsAppWebhookDecodeTest` alongside the server-level
`WhatsAppWebhookTest`. The suite uses NO mocks; three patterns carry it, and the platform split
below is deliberate:

- **Real-server round-trips, tagged `.notNative`.** `WhatsAppTest`, `WhatsAppCustomTest`,
  `WhatsAppWebhookTest`, and the upload/download/resolve/delete cases in `WhatsAppMediaTest` stand up a real
  kyo-http `HttpServer.init(0, "localhost")` and drive it with a real `HttpClient`, observing
  the genuine byte-exact request/response. Every one of these carries the `.notNative` tag, so
  they run on JVM, JS, and Wasm but are skipped on Scala Native: hosting the loopback
  `HttpServer` is the one capability with a Native constraint (see "Cross-platform layout"),
  so the server round-trip is exercised on the three targets that run it and not faked on the
  one that does not. The webhook body round-trip (the HMAC-over-wire-bytes invariant) is
  verified through a real POST with a known body, not asserted in isolation. A new
  server-touching test carries the same `.notNative` tag.
- **Published crypto vectors, all four platforms.** `internal/whatsapp/HmacTest` asserts `Hmac.sha256`
  against the published NIST SHA-256 vectors and `Hmac.hmacSha256` against the RFC-4231
  HMAC-SHA256 vectors. This, with the pure codec/wire/decode/signature/construction/config
  suites, is what runs on Native (the ~36 Native cases), and it is the cross-platform
  correctness witness for the pure implementation. Keep the pure-logic coverage free of any
  `.notNative` tag so Native keeps exercising it.
- **Compile-error checks for the type-level exclusions.** `WhatsAppMediaTest` and `WhatsAppInteractiveTest`
  use `typeCheckFailure` to assert that constructing a `WhatsAppMedia.Source` or an
  `WhatsAppInteractive.Flow.Ref` with both fields does not compile, so the
  unrepresentable-by-construction invariant is a tested property, not a comment.

Drive any new concurrency or readiness in a test with a deterministic async construct (a
`Channel` rendezvous, as `WhatsAppWebhookTest` does to capture decoded notifications), never a
sleep standing in for a server-ready or message-received witness.

## Pre-submission checklist (kyo-whatsapp-specific)

- [ ] A new outbound shape adds the public ADT variant, the snake_case `Wire` DTO, and the
      `Codec` mapping; no wire field leaks onto a public type.
- [ ] `Codec.mapError` / `mapCode` stay total: a new code maps to a named leaf or falls to
      `Cloud`, never to a silent drop or a wrong leaf.
- [ ] A new inbound discriminator adds the recognized case AND keeps the degenerate
      fallback (`Content.Unknown` / `Status.Other` / `Unsupported`); decode aborts only on
      a structurally unparseable envelope.
- [ ] The webhook body stays byte-exact between `bodyBinary` and `verifySignature`; no
      re-decode or re-serialize before the HMAC.
- [ ] No platform-specific crypto shim; the pure `Hmac` stays in `shared/src` and the
      digest compare stays constant-time.
- [ ] A new "exactly one of N" Cloud API field is a sealed union, not two optional fields.
- [ ] A new id-shaped string is an opaque type under `WhatsAppId` (deriving `Schema` via
      `Schema.stringSchema.transform`), not a bare `String`.
- [ ] New tests use `BaseWhatsAppTest` and no mocks (a real server round-trip or a published
      vector); a server-touching test carries `.notNative` (JVM/JS/Wasm), pure-logic stays
      untagged (all four platforms); a readiness witness uses a deterministic async construct.
- [ ] No platform `jvm/`/`js/`/`native/`/`wasm/` leaf for the public surface without the
      root cross-platform exception-bar justification.
