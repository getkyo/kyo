<!-- doctest:setup
```scala
import kyo.*
import kyo.whatsapp.*

val config =
    WhatsApp.Config(
        token = "EAAG...redacted",
        phoneNumberId = Id.PhoneNumberId("106540352242922")
    )

val customer    = Id.WhatsAppId("16505551234")
val appSecret   = "my-app-secret"
val verifyToken = "my-verify-token"

val sentWamid    = Id.MessageId("wamid.sent")
val inboundWamid = Id.MessageId("wamid.inbound")
val receiptBytes = Span.empty[Byte]
```
-->

# kyo-whatsapp

Kyo's client for the WhatsApp Business Cloud API. You bind a `WhatsApp.Config` once, then call a verb: `WhatsApp.send` to push an outbound message, `Media.upload`/`download` to move attachments, and `Webhook.handler` to receive inbound notifications. Every call reads the ambient config, issues an authenticated HTTP request through [kyo-http](../kyo-http/README.md), and returns a typed value or aborts with a typed `WhatsAppError`.

There are two workflows, and both share one `Config` and one `Abort[WhatsAppError]` channel:

- **Outbound**: bind the config with `WhatsApp.let(config) { ... }` and call `send`, `sendTemplate`, `markRead`, the `Media` namespace, or `custom`.
- **Inbound**: Meta POSTs signed webhook notifications to an endpoint you host. `Webhook.handler(appSecret) { notification => ... }` verifies the HMAC-SHA256 signature, decodes the body into typed `Notification` values, and runs your callback, all as a kyo-http `HttpHandler` you mount on an `HttpServer`.

The `Message` and `Notification` ADTs, the `WhatsAppError` leaves, and the opaque `Id` types are the vocabulary these two workflows speak; they are introduced where each workflow reaches for them.

A single outbound send, the smallest useful thing the module does:

```scala
WhatsApp.let(config) {
    WhatsApp.send(customer, Message.Text("Thanks for contacting Northwind!"))
}
```

The examples throughout build one running thread: a customer-support bot for a fictional store, Northwind Supplies. The shared values `config`, `customer`, `appSecret`, and `verifyToken` are the seeds; each section reuses them rather than inventing a new fixture.

> **Note:** the `send` call above is a `SendResult < (Async & Abort[WhatsAppError])` value. Like every Kyo computation it is a description, not an action: it type-checks and composes without performing the HTTP request until it is run inside an `Async` handler. The examples below construct these values without forcing them.

## Quick start

Add the dependency to your `build.sbt`:

```scala doctest:expect=skipped
libraryDependencies += "io.getkyo" %% "kyo-whatsapp" % "<latest version>"
```

A `WhatsApp.Config` carries the bearer token, the registered phone-number id (the Cloud API path segment), the Graph API version (default `"v25.0"`), and the base URL (default `"https://graph.facebook.com"`). It is an immutable value:

```scala
val supportConfig =
    WhatsApp.Config(
        token = "EAAG...redacted",
        phoneNumberId = Id.PhoneNumberId("106540352242922")
    )
```

The examples that follow reuse a `config` value of this shape.

The setters are fluent copy methods that shadow the fields, so `config.token("newToken")` returns an updated copy rather than reading the field. Use them to point a base `Config` at a different API version or a sandbox host:

```scala
val staging = config.apiVersion("v22.0").baseUrl("https://graph.facebook.com")
```

`WhatsApp.let(config) { ... }` binds the config as ambient state for a region of computation; every client call inside reads it. `WhatsApp.use` is the reader that each verb calls under the hood:

```scala
val greeting =
    WhatsApp.let(config) {
        WhatsApp.send(customer, Message.Text("Thanks for contacting Northwind!"))
    }
```

`send` returns a `SendResult` carrying the new message WAMID (`messageId`), the resolved recipient wa_id (`contactWaId`), and the optional Cloud API `message_status`:

```scala
val sent: WhatsApp.SendResult < (Async & Abort[WhatsAppError]) =
    WhatsApp.let(config) {
        WhatsApp.send(customer, Message.Text("Thanks for contacting Northwind!"))
    }
```

The `status` field is `Maybe[SendResult.Status]`, where `Status` is `Accepted`, `HeldForQualityAssessment`, `Paused`, or `Other(value)` for a `message_status` string the API may add later.

> **Caution:** calling any client method without a bound config panics with an `IllegalStateException`. A missing binding is a programming error, not a domain failure, so it surfaces as a `Result.Panic` at the effect boundary rather than as an `Abort[WhatsAppError]` leaf. Always wrap client calls in `WhatsApp.let(config) { ... }`.

## Sending messages

Once the config is bound, the outbound shape is always the same: build one `Message` value, pass it to `WhatsApp.send`. The value you pick selects the Cloud API message `type`; the codec handles the wire encoding. The variants grow from plain text to interactive to media-bearing.

### Text

```scala
val thanks =
    WhatsApp.let(config) {
        WhatsApp.send(customer, Message.Text("Thanks for contacting Northwind!"))
    }
```

`Message.Text` takes an optional `previewUrl` flag (default `false`) that enables the link preview when the body contains a URL.

### Interactive messages

`Message.OfInteractive` wraps the `Interactive` ADT, which models the six interactive sub-shapes the Cloud API supports: `ListMenu`, `Buttons`, `CtaUrl`, `Flow`, `Product`, and `ProductList`. Each carries the shared optional `body`, `header`, and `footer` alongside its own fields. The support bot offers two quick-reply buttons:

```scala doctest:scope=inherited
val menu =
    Message.OfInteractive(
        Interactive.Buttons(
            buttons = Chunk(
                Interactive.ReplyButton("track", "Track my order"),
                Interactive.ReplyButton("agent", "Talk to an agent")
            ),
            body = Present("How can we help?")
        )
    )

val askMenu =
    WhatsApp.let(config) {
        WhatsApp.send(customer, menu)
    }
```

A `ListMenu` groups `Row` values into `Section`s behind a single button label; `CtaUrl` renders one URL-opening button; `Flow` launches a WhatsApp Flow (see the [Flow reference](#flow-references) below); `Product` and `ProductList` surface catalog items. The `Interactive.Header` nested ADT (`Text`, `Media`, `Document`) is shared across the variants that accept a header.

### Media, location, contacts, and reactions

The media-bearing variants (`Image`, `Video`, `Document`, `Audio`, `Sticker`) each carry a `Media.Source`, which references an asset you have already uploaded or a public link (see [Media](#media)). `Image`, `Video`, and `Document` add an optional caption; `Document` adds an optional filename:

```scala
val brochure =
    WhatsApp.let(config) {
        WhatsApp.send(
            customer,
            Message.Document(
                source = Media.Source.ByLink("https://northwind.example/catalog.pdf"),
                caption = Present("Our Spring catalog"),
                filename = Present("northwind-spring.pdf")
            )
        )
    }
```

`Message.Location` carries latitude/longitude with an optional name and address. `Message.Contacts` shares one or more `Contact` cards (the same `Contact` type appears inbound, in [Receiving messages](#receiving-messages-webhooks)). `Message.Reaction` reacts to a prior message by its `Id.MessageId`:

```scala
val ack =
    WhatsApp.let(config) {
        WhatsApp.send(customer, Message.Reaction(sentWamid, "👍"))
    }
```

The `sentWamid` here is an `Id.MessageId`: exactly the value a prior `SendResult.messageId` produced, or the `id` on an inbound message. See [Identifiers](#identifiers) for how the WAMID threads from one call into the next.

### Replying to a message

`send` takes an optional `replyTo: Maybe[Id.MessageId]` that sets the Cloud API reply context, so the recipient sees the new message threaded under the one it answers:

```scala
val reply =
    WhatsApp.let(config) {
        WhatsApp.send(
            customer,
            Message.Text("Your order ships tomorrow."),
            replyTo = Present(inboundWamid)
        )
    }
```

### Acknowledging inbound messages

`markRead` posts the read receipt for an inbound message; `markReadWithTyping` does the same and shows a typing indicator while you compose a reply. Both consume the inbound message's `Id.MessageId` and return `Unit`:

```scala
val acknowledge =
    WhatsApp.let(config) {
        WhatsApp.markReadWithTyping(inboundWamid)
    }
```

These are the lightweight verbs the webhook callback reaches for; the [Receiving messages](#receiving-messages-webhooks) section closes the loop by combining `markRead` with a `send`.

## Templates

Inside the 24-hour customer-service window, any `Message` is fair game. Once that window closes (`WhatsAppError.WindowClosed`), a template is the only message class the Cloud API will deliver, so it is the vehicle for any proactive, business-initiated conversation. When you are answering a customer who just messaged you, use `send`; when you are reaching out first (an order update, a shipping notice), use `sendTemplate`.

A `Template` names a registered template, supplies a BCP-47 language code, and fills the template's variable slots with `Component` values. Each `Component` (`Header`, `Body`, `Button`) carries a chunk of typed `Parameter`s (`Text`, `Currency`, `DateTime`, `Image`, `Video`, `Document`, `Payload`):

```scala
val shippingNotice =
    WhatsApp.let(config) {
        WhatsApp.sendTemplate(
            customer,
            Template(
                name = "order_shipped",
                language = "en_US",
                components = Chunk(
                    Template.Component.Body(
                        Chunk(
                            Template.Parameter.Text("Sheena"),
                            Template.Parameter.Text("#NW-4815")
                        )
                    )
                )
            )
        )
    }
```

`sendTemplate` shares the `SendResult` return shape and the `replyTo` parameter with `send`. The `Template.ButtonSubType` enum (`QuickReply`, `Url`, `CopyCode`, `Flow`) identifies a button slot when a `Component.Button` fills one.

## Media

Reach for the `Media` namespace when a message carries a file you control rather than a public link. The lifecycle is: `upload` an asset to get an `Id.MediaId`, embed it in a message via `Media.Source.ById`, and `download` assets a customer sends you. All five operations require a bound config and abort with a typed `WhatsAppError`.

### Uploading and referencing

`upload` takes the bytes, a typed `MediaType`, and an optional filename, and returns the assigned `Id.MediaId`. `MediaType` is the MIME vocabulary (`ImageJpeg`, `ImagePng`, `AudioOgg`, `VideoMp4`, `DocumentPdf`, and the rest), each exposing a `.mime` string, plus `Other(mime)` for a type the enumeration does not name:

```scala
val uploadAndSend =
    WhatsApp.let(config) {
        Media.upload(receiptBytes, Media.MediaType.ImagePng, filename = Present("receipt.png")).map { id =>
            WhatsApp.send(customer, Message.Image(Media.Source.ById(id), caption = Present("Your receipt")))
        }
    }
```

`Media.Source` is a sealed union of `ById(id)` and `ByLink(link)`.

> **Note:** the Cloud API rejects a media reference that sets both an id and a link, or neither. `Media.Source` makes both-or-neither unrepresentable: you construct exactly one case, so the ambiguous-reference error cannot leave the process. The same id-XOR-name shape appears on [Flow references](#flow-references).

### Downloading

A customer's inbound photo arrives as a `Notification.Content.Media` carrying an `Id.MediaId`. To fetch the bytes, `download(id)` resolves the temporary URL and fetches in one step:

```scala
val photo =
    WhatsApp.let(config) {
        Media.download(Id.MediaId("media-123"))
    }
```

`download` is the fusion of two lower-level operations. `resolveUrl(id)` returns a `MediaInfo` (the temporary URL plus `mimeType`, `sha256`, and `fileSize`), and `downloadFrom(info)` fetches the bytes from that URL:

```scala
val viaInfo =
    WhatsApp.let(config) {
        Media.resolveUrl(Id.MediaId("media-123")).map(Media.downloadFrom)
    }
```

> **Caution:** the URL in a `MediaInfo` is valid for roughly five minutes. `downloadFrom` on a `MediaInfo` you cached past that window fails. Prefer `download(id)`, which resolves a fresh URL on every call; reach for the split `resolveUrl` / `downloadFrom` pair only when you genuinely need the metadata (the SHA-256, the size) before deciding whether to fetch.

`delete(id)` removes an uploaded asset and returns `Unit`.

## Receiving messages (webhooks)

The inbound direction is a workflow, not a single call: you host an HTTP endpoint, Meta registers it through a GET handshake, then POSTs signed notifications to it. The `Webhook` namespace gives you the GET handshake handler, the signature check, the typed decoder, and a fused POST handler that wires all three together. Each piece is a kyo-http value you mount on an `HttpServer`.

### Registration handshake

When you register a webhook in the Meta dashboard, Meta issues a GET with a `hub.challenge` it expects echoed back. `Webhook.verificationHandler(verifyToken)` answers that handshake, returning 200 with the challenge on a token match and 403 otherwise:

```scala doctest:scope=inherited
val verification = Webhook.verificationHandler(verifyToken)
```

### Verifying signatures

Every inbound POST carries an `X-Hub-Signature-256` header: an HMAC-SHA256 of the raw body keyed by your app secret. `Webhook.verifySignature` checks it. It is pure and total, returning a `Result[WhatsAppError.SignatureError, Unit]` with no effect row and no network, so you can call it directly:

```scala
val ok =
    Webhook.verifySignature(
        appSecret = "Jefe",
        signatureHeader = Present("sha256=5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"),
        body = Span.from("what do ya want for nothing?".getBytes("UTF-8"))
    )
// ok == Result.unit
```

A bad signature surfaces the specific reason rather than a bare boolean:

```scala
val tampered =
    Webhook.verifySignature(
        appSecret = "Jefe",
        signatureHeader = Present("sha256=" + "0" * 64),
        body = Span.from("what do ya want for nothing?".getBytes("UTF-8"))
    )
// tampered == Result.fail(WhatsAppError.SignatureError.Mismatch)
```

`SignatureError` has three cases: `Missing` (no header), `Malformed` (no `sha256=` prefix or a non-hex remainder), and `Mismatch` (a valid header that does not match the body).

> **Note:** the HMAC is a pure-Scala implementation with a constant-time compare, not a wrapper over `javax.crypto.Mac`. That is why `verifySignature` runs identically on JVM, JS, Native, and Wasm, where the JDK crypto class is absent, and why it never throws.

> **Caution:** the signature is computed over the exact bytes Meta sent. The fused handler reads the request body as raw bytes (`bodyBinary`) precisely so the HMAC sees what arrived on the wire. A transform that re-decodes or re-serializes the body before hashing changes the bytes and breaks verification.

### Decoding notifications

`Webhook.decode(body)` parses the verified bytes into a `Chunk[Notification]`, one per `entry[].changes[]` element. A `Notification` is `InboundMessage`, `StatusUpdate`, or `Unsupported`, and all three carry a `Metadata`. An `InboundMessage` carries the sender, the message id, a timestamp, the decoded `Content`, an optional reply `Context`, and the sender's profile name when present.

`decode` aborts with `WhatsAppError.DecodeError` only on a structurally broken envelope. A type it does not recognize is not an error:

> **Note:** unknown inputs decode to degenerate cases, never an abort. An unrecognized message type becomes `Content.Unknown(messageType, raw)`, an unrecognized status becomes `Status.Other(value)`, and an unrecognized change field or shape becomes `Notification.Unsupported(field, raw)`. A Cloud API type that ships after this release does not crash a running webhook; it arrives as a degenerate case you can log and skip. The outbound side carries the same policy with `SendResult.Status.Other` and `Media.MediaType.Other`.

### The fused handler and the reply loop

`Webhook.handler(appSecret) { notification => ... }` fuses the whole inbound path into one POST `HttpHandler`: it reads the byte-exact body, verifies the signature (403 on failure), decodes, runs your callback per `Notification`, and responds 200. The callback runs in the `Async & Abort[WhatsAppError]` row, so it can issue outbound calls, which is where the support bot closes its loop, marking the inbound message read and echoing a reply:

```scala doctest:scope=inherited
val webhook =
    Webhook.handler(appSecret) {
        case Notification.InboundMessage(_, from, id, _, Notification.Content.Text(body), _, _) =>
            WhatsApp.let(config) {
                WhatsApp.markRead(id)
                    .andThen(WhatsApp.send(from, Message.Text(s"You said: $body")).unit)
            }
        case _ => ()
    }
```

The callback rebinds the config with `WhatsApp.let(config)` because the handler itself does not bind one; the panic-on-unbound rule from [Quick start](#quick-start) applies inside the callback the same as anywhere.

> **Note:** `handler` responds 200 even when decode fails on a structurally broken change, logging and skipping it rather than aborting. Meta retries a non-200 response, so acking a payload it cannot parse prevents a poison message from being redelivered indefinitely. A signature failure is the one case that returns 403, since that is an authentication failure, not a malformed-but-authentic payload.

Mount both handlers on a kyo-http `HttpServer`:

```scala doctest:expect=skipped
val serve = HttpServer.init(0, "localhost")(verification, webhook)
```

The `Content` ADT enumerates the inbound payload shapes: `Text`, `Media`, `Location`, `Contacts`, `Reaction`, `Button`, `ListReply`, `ButtonReply`, `Order`, `System`, and `Unknown`. A `StatusUpdate` reports delivery progress (`Sent`, `Delivered`, `Read`, `Failed`, `Deleted`, `Other`) and carries any delivery-level `WhatsAppError.Cloud` errors in its `errors` field.

## Errors

Every client call shares one failure channel: `Abort[WhatsAppError]`. `WhatsAppError` is a sealed abstract class extending `Exception`, the same sealed-root shape as kyo-http's `HttpException`. You pattern-match on the leaf you care about and let `Cloud` catch the rest:

```scala
val result =
    Abort.run[WhatsAppError] {
        WhatsApp.let(config) {
            WhatsApp.send(customer, Message.Text("Thanks for contacting Northwind!"))
        }
    }.map {
        case Result.Success(sent)                                           => s"sent ${sent.messageId.value}"
        case Result.Failure(WhatsAppError.WindowClosed)                     => "outside the 24h window; send a template"
        case Result.Failure(WhatsAppError.RateLimited(_, _, scope))         => s"rate limited on $scope"
        case Result.Failure(WhatsAppError.Cloud(code, _, _, message, _, _)) => s"graph error $code: $message"
        case Result.Failure(other)                                          => s"failed: ${other.getMessage}"
        case Result.Panic(ex)                                               => s"panic: ${ex.getMessage}"
    }
```

The Graph API error codes map to named leaves: `AuthError` (`TokenExpired`/`AccessDenied`), `RateLimited` (with a `Scope` of `PhoneNumber`/`Waba`/`Throughput`), `RecipientError` (`Undeliverable`/`SenderEqualsRecipient`), `WindowClosed`, `TemplateError` (`DoesNotExist`/`ParamCountMismatch`/`ParamFormatMismatch`/`ContentPolicy`/`Paused`/`TextTooLong`), `MediaError` (`DownloadFailed`/`UploadFailed`), `InvalidParameter`, and `ServiceUnavailable`.

> **Note:** `Cloud` is the total fallback. Any Graph API code the typed leaves do not name surfaces as `Cloud`, carrying the full decoded envelope (`code`, `subcode`, `errorType`, `message`, `details`, `fbtraceId`), so an unmapped code still gives you every field to diagnose with. `Transport` wraps a non-Graph connect or timeout failure as a typed value rather than dropping it.

> **Unlike** a decode that masquerades as success, a structurally unparseable response surfaces as its own leaf. `DecodeError` is distinct from a Graph error and distinct from the degenerate webhook cases: it means the bytes themselves could not be parsed, never a recognized error code and never a silently dropped failure.

## Identifiers

The Cloud API has several string ids that are easy to confuse: the business account, the phone number, a media asset, a message, and a consumer's number. kyo-whatsapp models each as a distinct opaque type under `Id`, so they are mutually non-interchangeable:

- `Id.WabaId`: the WhatsApp Business Account id (the `entry[].id` in a webhook envelope).
- `Id.PhoneNumberId`: the registered business phone-number id (the send-endpoint path segment, the field in `Config`).
- `Id.MediaId`: an uploaded media asset.
- `Id.MessageId`: the WAMID, returned by `send` and present on every inbound message.
- `Id.WhatsAppId`: a consumer wa_id (the `to` outbound, the `from` inbound).

Each provides `apply(String)` to construct and a `.value` extension to read the underlying string:

```scala
val phoneId = Id.PhoneNumberId("106540352242922")
val raw     = phoneId.value
```

> **Caution:** passing a `WhatsAppId` where a `MessageId` is expected is a compile error, not a silent wrong call to the Graph API. The opaque types catch the mix-up before the request leaves the process.

This is what lets the WAMID thread losslessly through the support bot's conversation. `send` returns a `SendResult` whose `messageId` is an `Id.MessageId`, and that is exactly the value `Message.Reaction(messageId, ...)`, `WhatsApp.markRead(messageId)`, and `send(..., replyTo = Present(messageId))` consume. A WAMID that arrives on an inbound `Notification.InboundMessage.id` flows directly into the reply call with no parsing in between.

## Putting it together

The full support-bot thread: the bound config, an outbound greeting and quick-reply menu, the webhook that marks an inbound text read and echoes it back, and a media download for a photo the customer sends.

```scala doctest:scope=inherited
// Outbound: greet, then offer two quick-reply buttons.
val greet =
    WhatsApp.let(config) {
        WhatsApp.send(customer, Message.Text("Thanks for contacting Northwind!"))
    }

val offerMenu =
    WhatsApp.let(config) {
        WhatsApp.send(
            customer,
            Message.OfInteractive(
                Interactive.Buttons(
                    buttons = Chunk(
                        Interactive.ReplyButton("track", "Track my order"),
                        Interactive.ReplyButton("agent", "Talk to an agent")
                    ),
                    body = Present("How can we help?")
                )
            )
        )
    }

// Inbound: verify, decode, mark read, reply; download any photo sent.
val bot =
    Webhook.handler(appSecret) {
        case Notification.InboundMessage(_, from, id, _, Notification.Content.Text(body), _, _) =>
            WhatsApp.let(config) {
                WhatsApp.markReadWithTyping(id)
                    .andThen(WhatsApp.send(from, Message.Text(s"You said: $body")).unit)
            }
        case Notification.InboundMessage(_, _, _, _, Notification.Content.Media(_, mediaId, _, _, _, _, _), _, _) =>
            WhatsApp.let(config)(Media.download(mediaId).unit)
        case _ => ()
    }

val register = Webhook.verificationHandler(verifyToken)
```

```scala doctest:expect=skipped
// Mount the GET handshake and the POST handler on a server.
val server = HttpServer.init(0, "localhost")(register, bot)
```

## Cross-platform notes

kyo-whatsapp cross-builds for JVM, JavaScript, Scala Native, and Wasm. The API is identical on all four; there are no platform-specific public sources.

The outbound client (`send`, `sendTemplate`, `markRead`, the `Media` operations, `custom`) and the pure pieces (`Webhook.verifySignature`, `Webhook.decode`) run on every platform. The signature check is the notable one: because the HMAC-SHA256 is implemented in pure Scala rather than over `javax.crypto.Mac`, it behaves the same on Native and JS where that JDK class is unavailable.

Hosting a webhook *server* is the one capability with a platform constraint, because it relies on kyo-http's `HttpServer`:

- **JVM**: no additional setup.
- **JavaScript**: the server backend requires a Node.js runtime.
- **Native**: requires OpenSSL on the system when serving over TLS.

The outbound client and `verifySignature` do not host a server and carry none of these constraints; they need only kyo-http's client backend, which is available on every target.

## The `custom` escape hatch

For a Graph API endpoint this module does not yet type, `WhatsApp.custom` issues an authenticated request and decodes the response through its `Schema`. You supply the `HttpMethod`, a path appended to `baseUrl/apiVersion` (with the leading slash), and an optional body; GET and DELETE send no body, POST/PUT/PATCH send the body when present:

```scala
case class PhoneNumberInfo(verified_name: String, quality_rating: String) derives Schema

val info =
    WhatsApp.let(config) {
        WhatsApp.custom[Unit, PhoneNumberInfo](
            HttpMethod.GET,
            s"/${config.phoneNumberId.value}"
        )
    }
```

Errors map through the same typed `WhatsAppError` envelope as the typed methods, so a `custom` call participates in the same `Abort[WhatsAppError]` channel.

## A note on derived instances

Every public ADT in this module `derives CanEqual`, so the types are pattern-matchable and directly comparable in tests. The `Id` types and the webhook metadata types (`Notification.Metadata`, `Context`, `Conversation`, `Pricing`) also `derives Schema`, so they serialize transparently wherever a `Schema` is needed.

## Flow references

The `Interactive.Flow` variant launches a WhatsApp Flow. Its `ref` field is an `Interactive.Flow.Ref`, a sealed union of `ById(flowId)` and `ByName(flowName)`:

```scala
val signup =
    Interactive.Flow(
        token = "flow-token-abc",
        ref = Interactive.Flow.Ref.ById("1234567890"),
        cta = "Sign up",
        body = Present("Join Northwind rewards")
    )
```

> **Unlike** a single field that could hold either an id or a name, the `Ref` union sets exactly one. The same construction-time guarantee that `Media.Source` gives the media reference, `Flow.Ref` gives the flow reference: the ambiguous-lookup error cannot be expressed. The `Flow.Action` (`Navigate`/`DataExchange`) and `Flow.Mode` (`Draft`/`Published`) types complete the flow's configuration.
