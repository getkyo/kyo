# Contributing to kyo-whatsapp

Module-specific guide for kyo-whatsapp. Read the repository-root
[CONTRIBUTING.md](../CONTRIBUTING.md) first: it carries the conventions, naming rules, type
vocabulary, test patterns, and the unsafe-boundary tiers that apply across all of Kyo. This
document records only what is specific to kyo-whatsapp: the client and its own HTTP settings,
the public types as Meta's JSON, the failure tree and how each leaf is reached, the values validated
at construction, the byte-exact webhook contract, the signature primitives it takes from
kyo-crypto and kyo-charset, and the test patterns.

## What kyo-whatsapp is

kyo-whatsapp is the WhatsApp Business Cloud API integration: outbound verbs (`WhatsApp.send`,
`sendTemplate`, `markRead`, `markReadWithTyping`, `custom`, and the `WhatsAppMedia` verbs) and an
inbound webhook (`WhatsApp.Webhook.verificationHandler`, `handler`, `verify`, `decode`). It sits at
the Applications layer and builds on kyo-http: every call goes through an `HttpClient`, and the
webhook handlers are kyo-http `HttpHandler` values mounted on the caller's `HttpServer`.

One public type per file, `WhatsApp`-prefixed, in package `kyo`, so `import kyo.*` reaches the
whole API and each file pairs with its `WhatsAppXTest`. `WhatsAppException.scala` is the one
file holding many public types: the whole failure hierarchy. The internals live in
`kyo.internal.whatsapp` (`Graph`, `Codec`, `Methods`, `Transformers`, `Secret`), each
`private[kyo]`.

## The client

`final class WhatsApp` is the client value: the config and the module's own `HttpClient`,
built only by the module. Every verb is a function on the companion whose row carries
`Env[WhatsApp]`, so a verb used where no client is provided does not compile.
The lifecycle has the shape of every kyo chat module: `init(config)` builds a client closed with
the enclosing `Scope`, `initUnscoped(config)` one closed only by `close(client)`, and
`run(client)(v)` provides a built client to `v`. `run(config)(v)` is `init` and `run(client)`
inside `Scope.run`. The two `run`s are the only methods that provide `Env[WhatsApp]`: a method
that satisfies a pending effect is named `run`. Building a client cannot fail, since inbound
arrives by webhook and the client holds no connection of its own, so `init` has no `Abort`.
`WhatsApp.Webhook.handler(webhook)(f)` requires `Env[WhatsApp]` and hands that client to every
call of `f`, which runs on the server's fibers, so the server is served inside the client's
region.

The client's `HttpClient` is never the caller's. Every request runs under
`HttpClient.let(client.http)(HttpClient.withConfig(config.httpConfig)(...))`,
and `WhatsAppConfig.init` builds `httpConfig` with every field of `HttpClientConfig` set: no
redirects, no retry, no base URL, no filter, default TLS. A caller's filter would see the token,
a caller's base URL would resolve a relative media URL, and a caller's TLS setting would govern
the connection that carries the token. A new field of `HttpClientConfig` gets an explicit value
there. `httpConfig` is built once, by `init`, so the checked kyo-http limits are refused there
as the config's own problem and a request never re-checks them.

`internal/whatsapp/Graph.scala` is the one request path:

- `Graph.call[F]` runs a request, answers the body of a 2xx, and turns a non-2xx into a leaf
  of `F` through `statusFailure`. Every request reads its answer with `failOnError = false`,
  so kyo-http never raises a status failure (its `HttpStatusException` holds the path and the
  body). A new verb uses a response-returning form and goes through `Graph.call`.
- `Graph.call` is also the only retry. With `WhatsAppConfig.retry` set, an answer whose Graph
  code is in `Graph.RetryableCodes`, the codes Meta's error-code reference says to try again on,
  is sent again after the longer of the schedule's delay and the answer's `Retry-After`; a wait
  past `retryMaxDelay` is not taken, and the rate-limit leaf carries it as `retryAfter`. kyo-http's
  own retry stays off, since it would resend without the module seeing the answer. A code joins
  `RetryableCodes` only on Meta's documented advice, quoted in its scaladoc. A retried send can be
  delivered twice, which the README and `WhatsAppConfig`'s scaladoc state.
- `Graph.transport` maps a kyo-http failure to `WhatsAppTransportException` and keeps nothing
  of it but kyo-net's cause of a connection that could not be made, since every kyo-http leaf
  names the request URL (for a pre-signed media URL, a credential).
- `Graph.describe` is exhaustive over kyo-http's `HttpException` with no wildcard, so a leaf
  kyo-http adds fails to compile there. Each arm is either a reachable `Kind` or
  `bug(s"<Leaf> reached a kyo-whatsapp call")` for a leaf no call can produce. A leaf is
  reachable if any input a peer controls can raise it: `HttpMalformedBodyException` (bad
  chunked framing, which a proxy can send) is `Kind.Protocol`, not a bug arm. Move an arm from
  `bug` to a kind only with a test that produces it through a local server.
- A closed connection is `HttpConnectionClosedException`, and its phase picks the kind:
  `BeforeHead` is `Kind.NoResponseHead`, a body cut short (`BodyTruncated`, `TlsTruncated`) is
  `Kind.ConnectionClosed`. Every panic passes through `Graph.transport` unchanged, since a
  typed failure would hide a defect.

## The public types are Meta's JSON

Every public type that is one of Meta's objects has a `Schema` that is the Cloud API's JSON for
it, derived with annotations; there is no second model of the wire. `@rename` names each
snake_case key, `@omit` leaves out what Meta leaves out (`WhenEmpty` for a collection,
`WhenDefault` for a value such as a flow's `mode`), `@alias` reads a key Meta sends under a second
name, and a value Meta wraps in an object is held as that object, a record with its own `Schema`,
read through a method (`WhatsAppStatus.DeliveryIssue.details`).

- A sum is tagged the way Meta tags it: `@discriminator("type")` for a message, an interactive
  object or a template parameter, whose case holds Meta's object for its type in one field named
  for the tag (`Text(text: Text.Body)`, giving `{"type":"text","text":{...}}`); `@tagOnly` for a
  string enum (`WhatsAppStatus.Kind`); `@untagged` where Meta writes one of several keys
  (`WhatsAppMedia.Source`, `id` or `link`). A companion `apply` takes the object's fields.
- An "exactly one of" key that sits beside other keys, such as a media object's `id` or `link`,
  is a sealed union held in one field and flattened: `given Schema[X] = Schema[X].flatten(_.source)`
  in the companion. `Schema.derived[X].flatten` does not compile there; `Schema[X]` does.
- A value whose JSON is not its Scala type's own reads through a field codec in
  `internal/whatsapp/Transformers.scala` (`@transform`): epoch-seconds strings as `Instant`, a
  url as an `HttpUrl` that is absolute `http` or `https`, a file size Meta sends as a number or a
  string, a coordinate Meta sends as a number or a numeric string. Each refuses through
  `transformVia`, so a value that does not fit is a `ConstructorRejectedException` at its path.
- A value with an invariant derives through its `init`: `WhatsAppSendResult` uses
  `Schema.derivedVia(init)`, so an answer with no message is refused by the same check a caller
  meets.
- An inbound item Meta may change keeps the items beside it decoding: a list holds an `Entry`
  sum, `@untagged`, whose `@catchAll` case `Undecodable(raw)` takes any item the typed cases do
  not read (`WhatsAppInboundMessage.Entry`, `WhatsAppStatus.Entry`,
  `WhatsAppWebhookPayload.ChangeEntry`). A sealed case must be in the same file as its parent.
- `WhatsAppNotification` composes Meta's objects and is not one, so it has a `Schema` refusal
  (`inline given [N <: WhatsAppNotification]: Schema[N] = compiletime.error(...)`), bounded so a
  summon of one case is refused too. The payload's `Schema` is `WhatsAppWebhookPayload`'s.

`internal/whatsapp/Methods.scala` holds each call's own request and answer objects (`Send`,
`SendTemplate`, `MarkRead`, `GraphError`, ...), fields named by their wire keys and typed with
the public types; `Send` flattens the message, so its `type` and object sit beside `to`.
`internal/whatsapp/Codec.scala` encodes and decodes them; its functions are pure (`Span[Byte]`
and `Result` in and out, no effect row), and `decodeNotifications` composes the notifications
from a decoded `WhatsAppWebhookPayload`.

Tests compare a type against the JSON Meta documents for it, whole: the example decodes to the
value and the value encodes to the example, compared through `BaseWhatsAppTest.unordered`, since
kyo-schema may write keys in another order. A test that only checks substrings of an encoding is
not enough.

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
  types are right and whose values are not is this leaf too, as `ConstructorRejected`: an empty
  `messages` at the answer, refused by `WhatsAppSendResult.init`, and `{"success": false}` at
  `success`. Do not add a leaf for an answer of the wrong shape. A field needing a stricter rule
  than its JSON shape (`Transformers.FileSize`) refuses through `transformVia`, so it surfaces
  through the same leaf as `ConstructorRejected` at its field.
- `WhatsAppRefusedUrlException(method)`: a media URL that is not an absolute `http` or `https`
  URL on a host in printable ASCII, or a media id that is not one path segment. Nothing of the
  URL or id is copied.
- `WhatsAppRefusedPartException(method, field)`: an upload filename or `MediaType.Other` mime
  outside printable ASCII, or holding `"` or `\`. kyo-http writes both into the multipart part
  head verbatim, so `upload` checks them before it sends; drop the check only once kyo-http
  escapes the part head itself. Nothing of the value is copied.
- A value that cannot be built is the failure of its `init` and belongs to no row:
  `WhatsAppInvalidConfigException`, `WhatsAppInvalidTokenException`,
  `WhatsAppInvalidPathException`, `WhatsAppInvalidWebhookConfigException`. None renders the
  text it refused, only its position or the setting.

`getMessage` renders the source lines around the leaf's `Frame` in development mode. Tests
compare leaves by equality and assert text with `contains`; a test that checks a secret stays
out of a rendering builds the secret away from the construction line (`Seq(...).mkString`),
since those lines are part of the message. A log line uses the leaf's one-line text
(`WhatsAppDecodeException.show`), never `getMessage`.

## Values validated at construction

A checked value is built only by its `init`, which returns `Result[<its leaf>, <value>]`; its
constructor is private and nothing throws, so an invalid one never reaches the wire. Each
check names the reason the value cannot be carried, and bounds nothing the platform does not
document. Each refusal has a test asserting its `Problem`:

- `WhatsAppToken` and `WhatsAppAppSecret` (`Secret.init`): non-empty, printable ASCII without
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

`WhatsAppConfig` and `WhatsAppWebhookConfig` have private constructors, which makes their
`copy` private too: a changed config goes through `init` again. A field value that must refuse
(`Transformers.FileSize`) refuses through kyo-schema's `transformVia`, whose rejection is a
`ConstructorRejectedException`, never by throwing from a reader.

## Unknown inputs decode degenerate, never abort

- An unrecognized message type decodes to `WhatsAppInboundMessage.Unknown(type, payload)`, an
  unrecognized status to `WhatsAppStatus.Kind.Other(value)`, an unrecognized send status to
  `WhatsAppSendResult.Status.Other(value)`, and an unrecognized change field to
  `WhatsAppWebhookPayload.Change.Other(field, payload)`: each a `@catchAll` case that writes the
  value back unchanged.
- A change that does not decode, a `messages` change without `metadata` or whose metadata or
  contacts do not decode, and a message or status that does not decode or whose `timestamp` is
  not ASCII digits within the range of a kyo `Duration` after the epoch (the year 2262) become
  `WhatsAppNotification.Unknown(type, payload, metadata)`, the item's own JSON kept. Each
  message and status decodes on its own, so one that does not decode never takes the rest of
  the POST with it: Meta treats a 200 as delivered and resends nothing. Only a body that is not
  the envelope (not JSON, no `entry`, an entry without `changes`) is a decode failure. No value
  stands for a missing one: no empty phone number id, no zero timestamp.
- `WhatsAppRawJson` holds a raw payload as a `Structure.Value`, and its `Schema` is that JSON; its
  `toString` renders the length only, since the text is the sender's data.

`WhatsApp.Webhook.decode` fails only on a body that is not a notification envelope (including
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

## Signature primitives

The module holds no cryptography or text codec of its own. The signature is
`kyo.crypto.Hmac.verifySha256` over the raw body, with the tag from kyo-data's `Hex.decode`; the
verify token is compared with `kyo.crypto.ConstantTime.isEqual`. Both compare in constant time,
so a tag or token is never compared with `==`. Text becomes bytes through kyo-charset's UTF-8
encoder (`kyo.internal.charset.Utf8.encode`, the one kyo-email and kyo-mime use, since the
public `Charset` only decodes) and bytes become text through `Charset.Utf8.decode`; the JDK's
`getBytes` and `new String` are not used: the encoder turns a lone surrogate into U+FFFD where
`getBytes` writes `?`.

## Exclusions by construction

`WhatsAppMedia.Source` (`ById` or `ByLink`) and `WhatsAppInteractive.Flow.Ref` (`ById` or
`ByName`) are sealed unions, so the Cloud API's "exactly one of two fields" cannot be broken. A
new "exactly one of N" field is a sealed union whose cases each write their one wire key.
`Flow`'s `start` has no default: how a flow starts is the caller's decision.

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
- `WhatsAppWebhookSignatureTest` checks `verify` against the RFC 4231 HMAC-SHA256 vector;
  the vectors of the hash itself are kyo-crypto's.
- Readiness and delivery are witnessed with a `Channel`, never a sleep.
- A retry waits on virtual time (`Clock.withTimeControl`): `WhatsAppRetryTest` advances it a
  second at a time and asserts the step at which the server sees each attempt, a lower bound
  that holds however the fibers interleave. kyo-http's request timeout is a sleep on the same
  clock, so such a test sets a request timeout no step reaches.
- `WhatsAppLiveTest` has one set of leaves for two targets: Meta's Cloud API when
  `KYO_WHATSAPP_TOKEN` is set, otherwise a whaloc container (`WhatsAppLiveServer`, pinned by
  digest, started through kyo-pod). A CI run whose test selection includes kyo-whatsapp pulls
  that digest before the tests (`scripts/fixture-images.sh`) and a leaf never pulls it: where it
  is missing, the leaf fails with the `podman pull` command, so a digest change updates
  `WhatsAppLiveServer.Image` and its entry in `scripts/fixture-images.sh` together. What a person does in the chat is an `Act`: Meta's target
  asks the person for it, the container's target injects it through whaloc's control plane, and
  both assert the same notification. A leaf whaloc does not reproduce passes its reason as
  `realOnly` and is cancelled on the container; it never asserts whaloc's divergence. Its
  10-minute leaf timeout is the module's only real-clock bound, marked `deviation:`.

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
- [ ] A new public type derives a `Schema` that is Meta's JSON, tested whole against Meta's
      documented example; a new inbound type keeps the degenerate fallback.
- [ ] The webhook body stays byte-exact up to `verify`.
- [ ] New tests use a local server or a published vector and compare whole values.
