<!-- doctest:setup
```scala
import kyo.*

// The running domain: a customer-support bot for a fictional store, Northwind Supplies.
val config =
    WhatsAppConfig(
        token = WhatsAppToken("EAAG-redacted"),
        phoneNumberId = WhatsAppId.PhoneNumberId("106540352242922")
    )

val webhook =
    WhatsAppWebhookConfig(
        appSecret = WhatsAppAppSecret("my-app-secret"),
        verifyToken = WhatsAppVerifyToken("my-verify-token"),
        path = "hooks/whatsapp"
    )

val customer     = WhatsAppId.WaId("16505551234")
val sentWamid    = WhatsAppId.MessageId("wamid.sent")
val inboundWamid = WhatsAppId.MessageId("wamid.inbound")
val receiptBytes = Span.empty[Byte]
```
-->

# kyo-whatsapp

`kyo-whatsapp` is a client for the [WhatsApp Business Cloud API](https://developers.facebook.com/docs/whatsapp/cloud-api). The verbs a bot calls (`WhatsApp.send`, `WhatsApp.sendTemplate`, `WhatsApp.markRead`, the `WhatsAppMedia` verbs and `WhatsApp.custom`) are functions whose rows require the client, `Env[WhatsApp]`, which `WhatsApp.let(config)` and the webhook handler provide. Inbound messages arrive by webhook: `WhatsAppWebhook.handler` verifies each POST's signature, decodes it into `WhatsAppNotification` values and runs your callback, as a kyo-http `HttpHandler` you mount on your own `HttpServer`.

Every verb fails with its own sealed trait, such as `WhatsAppSendFailure`, whose leaves are exactly the failures that verb can meet. The access token travels in the `Authorization` header of every request, and no failure of the module holds it. The module is cross-platform (JVM, Scala.js, Scala Native and WebAssembly) from one shared source set.

A bot that answers every inbound message, mounted with the registration handshake on your own server:

```scala
val answering =
    WhatsAppWebhook.handler(config, webhook) {
        case message: WhatsAppNotification.InboundMessage =>
            WhatsApp.send(message.from, WhatsAppMessage.Text("Thanks for contacting Northwind!")).unit
        case _ => Kyo.unit
    }.map { handler =>
        HttpServer.init(0, "localhost")(WhatsAppWebhook.verificationHandler(webhook), handler)
    }
```

Inside the callback the verbs need no setup, since the handler provides the client. Anywhere else, `WhatsApp.let(config)` provides one:

```scala
val greeting: WhatsAppSendResult < (Async & Abort[WhatsAppSendFailure]) =
    WhatsApp.let(config) {
        WhatsApp.send(customer, WhatsAppMessage.Text("Thanks for contacting Northwind!"))
    }
```

Like every Kyo computation, `answering` and `greeting` are descriptions: nothing is served or sent until they run inside an `Async` handler. The sections below build the support bot one piece at a time, and [Putting it together](#putting-it-together) combines them.

## What kyo-whatsapp does not do

- It never retries a call. Meta sends no retry delay for a rate limit, in a header or in the error, so a rate limit is a `WhatsAppRateLimitException` leaf for the caller to act on (see [Errors](#errors)).
- It does not deduplicate. Meta redelivers a whole webhook POST that did not get a 200, and a callback that must not act twice deduplicates by the message or status id each notification carries.
- It models messages, templates, read receipts, media and the `messages` webhook field. Template management, phone-number registration and business profiles are reachable through `custom`; other webhook fields arrive as `WhatsAppNotification.Unknown` with their JSON.
- It does not check a downloaded file against the `sha256` of its `MediaInfo`.

## Configuration and the client

A `WhatsAppConfig` carries the access token, the registered phone-number id (a path segment of every messages and media upload URL), the Graph API version (default `v25.0`), the base URL (default `https://graph.facebook.com`), and the request limits: `requestTimeout` and `connectTimeout` (10 seconds each) and `maxResponseLength` (100 MB, the largest media Meta stores).

```scala
val tuned =
    WhatsAppConfig(
        token = WhatsAppToken("EAAG-redacted"),
        phoneNumberId = WhatsAppId.PhoneNumberId("106540352242922"),
        requestTimeout = 30.seconds
    )
```

A config that holds a value the module cannot use is a programming mistake, so building one panics with `WhatsAppInvalidConfigException` naming the setting: a base URL that is not an absolute `http` or `https` URL on a host in printable ASCII, or that has userinfo, a query or a trailing slash; an `apiVersion` other than `v`, digits, a dot and digits; a `phoneNumberId` other than ASCII digits; a zero or infinite timeout; a response bound outside 1 byte to `Int.MaxValue` bytes.

The three credentials each have their own type: `WhatsAppToken` (the access token, sent as the bearer), `WhatsAppAppSecret` (the key of the webhook signature) and `WhatsAppVerifyToken` (the string of the registration handshake). Each panics with `WhatsAppInvalidTokenException` when built from empty text; the token and the app secret also refuse a character a header cannot carry (anything but printable ASCII without space). Each prints as `<redacted>`, so a config that holds one never shows it:

```scala
assert(config.toString.startsWith("WhatsAppConfig(WhatsAppToken(<redacted>),106540352242922,v25.0,"))
```

`WhatsApp.let(config)(v)` builds a client for the duration of `v` and releases it afterwards. The client has its own kyo-http `HttpClient` and runs every request under the module's complete settings, so no filter, base URL, retry or TLS setting of the caller's reaches a request that carries the token. A verb used outside a region that provides a client does not compile.

## Sending messages

The outbound shape is always the same: build one `WhatsAppMessage` and pass it to `WhatsApp.send`. The case you pick selects the Cloud API message type.

```scala
val thanks =
    WhatsApp.let(config) {
        WhatsApp.send(customer, WhatsAppMessage.Text("Thanks for contacting Northwind!"))
    }
```

`send` answers a `WhatsAppSendResult`: the new message's id (`messageId`), the recipient's resolved wa_id (`contactWaId`), and the optional `status`, which is `Accepted`, `HeldForQualityAssessment`, `Paused`, or `Other(value)` for a value Meta adds later.

`WhatsAppMessage.OfInteractive` wraps the `WhatsAppInteractive` cases: `ListMenu`, `Buttons`, `CtaUrl`, `Flow`, `Product` and `ProductList`. The support bot offers two quick-reply buttons:

```scala
val askMenu =
    WhatsApp.let(config) {
        WhatsApp.send(
            customer,
            WhatsAppMessage.OfInteractive(
                WhatsAppInteractive.Buttons(
                    buttons = Chunk(
                        WhatsAppInteractive.ReplyButton("track", "Track my order"),
                        WhatsAppInteractive.ReplyButton("agent", "Talk to an agent")
                    ),
                    body = Present("How can we help?")
                )
            )
        )
    }
```

A `Flow` names its flow by id or by name (`Flow.Ref.ById`, `Flow.Ref.ByName`) and its first step as a required `Flow.Action`: `Navigate(screen, data)` or `DataExchange`, which lets the flow's endpoint choose.

The media cases (`Image`, `Video`, `Document`, `Audio`, `Sticker`) take a `WhatsAppMedia.Source`: `ById` for an asset you uploaded (see [Media](#media)), or `ByLink` with an `HttpUrl` Meta fetches. `Location`, `Contacts` and `Reaction` complete the set. `send` takes an optional `replyTo` that threads the new message under the one it answers:

```scala
val reply =
    WhatsApp.let(config) {
        WhatsApp.send(customer, WhatsAppMessage.Text("Your order ships tomorrow."), replyTo = Present(inboundWamid))
    }
```

`markRead(id)` posts the read receipt for an inbound message, and `markReadWithTyping(id)` also shows a typing indicator while the bot composes a reply.

### Templates

Once the 24-hour customer-service window closes (`WhatsAppWindowClosedException`), a template is the only message Meta delivers, so it carries any conversation the business starts. A `WhatsAppTemplate` names a registered template, a language code, and fills its variables with `Component` values (`Header`, `Body`, `Button`) of typed `Parameter`s:

```scala
val shippingNotice =
    WhatsApp.let(config) {
        WhatsApp.sendTemplate(
            customer,
            WhatsAppTemplate(
                name = "order_shipped",
                language = "en_US",
                components = Chunk(
                    WhatsAppTemplate.Component.Body(
                        Chunk(WhatsAppTemplate.Parameter.Text("Sheena"), WhatsAppTemplate.Parameter.Text("#NW-4815"))
                    )
                )
            )
        )
    }
```

## Media

`WhatsAppMedia.upload` takes the bytes, a `MediaType` and an optional filename, and answers the new `WhatsAppId.MediaId`, which a message references with `Source.ById`:

```scala
val sendReceipt =
    WhatsApp.let(config) {
        WhatsAppMedia.upload(receiptBytes, WhatsAppMedia.MediaType.ImagePng, filename = Present("receipt.png")).map { id =>
            WhatsApp.send(customer, WhatsAppMessage.Image(WhatsAppMedia.Source.ById(id), caption = Present("Your receipt")))
        }
    }
```

A photo a customer sends arrives with a media id. `download(id)` resolves its URL and fetches the bytes; it is `resolveUrl(id)`, which answers a `MediaInfo` (the URL, `mimeType`, `sha256` and `fileSize`), followed by `downloadFrom(info)`:

```scala
val photo =
    WhatsApp.let(config) {
        WhatsAppMedia.download(WhatsAppId.MediaId("media-123"))
    }
```

A media URL is pre-signed and valid for about five minutes. Its query is a credential, so `MediaInfo`'s `toString` renders it as `?<redacted>`, and `downloadFrom` sends nothing to a URL that is not an absolute `http` or `https` URL on a host (`WhatsAppRefusedUrlException`). `delete(id)` removes an uploaded asset.

## Webhooks

Meta registers a webhook with a GET handshake, then POSTs signed notifications to it. A `WhatsAppWebhookConfig` holds what both need: the app secret that keys each POST's `X-Hub-Signature-256`, the verify token Meta echoes in the handshake, and the `path` both routes are mounted at. A path holding `?`, `#`, a space, a control character or non-ASCII can never match a request, so it panics with `WhatsAppInvalidWebhookConfigException`.

`WhatsAppWebhook.verificationHandler(webhook)` answers the handshake: 200 with `hub.challenge` when `hub.verify_token` matches, compared in constant time, and 403 otherwise. `WhatsAppWebhook.handler(config, webhook)(f)` is the POST route. Building it opens a client in the enclosing `Scope`, and every call of `f` runs with that client, so the callback calls the verbs directly:

```scala
val inbound: HttpHandler["body" ~ Span[Byte], Any, WhatsAppMarkReadFailure] < (Async & Scope) =
    WhatsAppWebhook.handler(config, webhook) {
        case message: WhatsAppNotification.InboundMessage => WhatsApp.markRead(message.id)
        case _                                            => Kyo.unit
    }
```

The handler's error type is the callback's own, `WhatsAppMarkReadFailure` here, and nothing of the module's. What the handler answers decides what Meta does next:

- a POST whose signature is missing, malformed or wrong gets 403 and is not decoded;
- a body that is not a notification envelope gets 200 and one log line naming the decode failure, since Meta would redeliver it forever otherwise;
- a callback that fails or panics gets 500, and Meta redelivers the whole POST, so the notifications before the failing one arrive again.

`WhatsAppWebhook.verify(webhook, header, body)` and `WhatsAppWebhook.decode(body)` are the two steps on their own, for a bot that serves the endpoint itself. The signature is computed over the exact bytes Meta sent, which is why the handler reads the body as raw bytes.

A POST decodes to one `WhatsAppNotification` per message and per status:

- `InboundMessage` carries the business number's `Metadata`, the sender, the message id, the `timestamp`, the decoded `Content`, an optional reply `Context`, and the sender's profile name when Meta sends it.
- `StatusUpdate` carries the message id, a `Status` (`Sent`, `Delivered`, `Read`, `Failed`, `Deleted`, or `Other`), the timestamp, the recipient, the optional conversation and pricing, and the `DeliveryIssue`s of a failed message.
- `Unknown(type, payload, metadata)` is a change the module does not model, with its JSON as Meta sent it. A `messages` change without metadata, and a message or status that does not decode or whose timestamp does not parse, is `Unknown` too, holding its own JSON, so no notification carries a value standing for a missing one. Each message and status decodes on its own: one Meta changed the shape of arrives as `Unknown`, and the rest of the POST is still delivered, since Meta treats the 200 as delivered and would not resend it.

`Content` is `Text`, `Media` (with its `Kind`: image, video, audio, document or sticker), `Location`, `Contacts`, `Reaction`, `Button`, `ListReply`, `ButtonReply`, `Order`, `System`, or `Unknown` for a message type the module does not enumerate. A raw payload is a `WhatsAppRawJson`, read through `value`, whose `toString` renders only its length, since the text is the sender's data.

## Errors

`WhatsAppException` is the sealed root, a `KyoException`. No verb fails with the root: each has its own sealed trait, and a leaf mixes in the trait of every verb that can meet it.

| Verb | Row |
|---|---|
| `WhatsApp.send` | `Abort[WhatsAppSendFailure]` |
| `WhatsApp.sendTemplate` | `Abort[WhatsAppSendTemplateFailure]` |
| `WhatsApp.markRead`, `markReadWithTyping` | `Abort[WhatsAppMarkReadFailure]` |
| `WhatsApp.custom` | `Abort[WhatsAppCustomFailure]` |
| `WhatsAppMedia.upload` | `Abort[WhatsAppUploadFailure]` |
| `WhatsAppMedia.resolveUrl` | `Abort[WhatsAppResolveUrlFailure]` |
| `WhatsAppMedia.downloadFrom` | `Abort[WhatsAppDownloadFromFailure]` |
| `WhatsAppMedia.download` | `Abort[WhatsAppDownloadFailure]`, the union of the two above |
| `WhatsAppMedia.delete` | `Abort[WhatsAppDeleteFailure]` |
| `WhatsAppWebhook.verify` | `Result[WhatsAppWebhookVerifyFailure, Unit]` |
| `WhatsAppWebhook.decode` | `Abort[WhatsAppWebhookDecodeFailure]` |

A Graph error answer is a `WhatsAppApiException` leaf with `method`, `code`, `subcode`, `description` (Meta's `message`), `details` (its `error_data.details`, which often says what to do) and `traceId` (the `fbtrace_id` Meta support asks for). The access token's value is replaced by `<redacted>` wherever Meta echoes it. A code has a leaf of its own only on the verbs it can come from; anywhere else, and for any code no leaf names, it is `WhatsAppOtherApiException` carrying the code:

```scala
val outcome =
    Abort.run[WhatsAppSendFailure] {
        WhatsApp.let(config)(WhatsApp.send(customer, WhatsAppMessage.Text("Thanks for contacting Northwind!")))
    }.map {
        case Result.Success(sent)                             => s"sent ${sent.messageId.value}"
        case Result.Failure(_: WhatsAppWindowClosedException) => "outside the 24h window; send a template"
        case Result.Failure(e: WhatsAppRateLimitException)    => s"rate limited (${e.code})"
        case Result.Failure(e: WhatsAppApiException)          => s"Graph error ${e.code}: ${e.description}"
        case Result.Failure(other)                            => other.getMessage
        case Result.Panic(ex)                                 => s"defect: ${ex.getMessage}"
    }
```

The named leaves are the token and permission errors (`WhatsAppTokenExpiredException`, `WhatsAppAccessDeniedException`), four rate limits under `WhatsAppRateLimitException` (the app, the business account, the throughput, and the pair of sender and recipient), the recipient errors (`WhatsAppUndeliverableException`, `WhatsAppSenderIsRecipientException`), `WhatsAppWindowClosedException`, six template errors under `WhatsAppTemplateException` (on `sendTemplate` only), `WhatsAppMediaUploadException`, `WhatsAppInvalidParameterException` and `WhatsAppServiceUnavailableException`.

The failures that are not Graph errors:

- `WhatsAppTransportException(method, kind, host, port, timeout)`: the request failed before its answer could be read. `kind` says how (`Connect`, `Dns`, `Tls`, `ConnectTimeout`, `Timeout`, `Protocol`, `ConnectionClosed`, `PoolExhausted`, `PayloadTooLarge`, `NoResponseHead`). No kyo-http failure is kept, since each names the request URL; kyo-net's cause of a connection that could not be made is the `getCause`.
- `WhatsAppUnexpectedStatusException(method, status)`: a non-2xx answer whose body is not a Graph error, such as a media host's 404. No body text is kept.
- `WhatsAppDecodeException(method, part, failure, path, position)`: an answer or a notification that did not decode, located at `path`. Nothing of the body is kept. An answer whose types are right and whose values are not is this failure too: a send answer with an empty `messages`, and an acknowledgement of `{"success": false}`.
- `WhatsAppRefusedUrlException(method)`: a media URL or media id the module will not send the token to.
- The signature leaves `WhatsAppSignatureMissingException`, `WhatsAppSignatureMalformedException` and `WhatsAppSignatureMismatchException`.

A value that cannot be built panics instead of failing: `WhatsAppInvalidConfigException`, `WhatsAppInvalidTokenException`, `WhatsAppInvalidPathException` and `WhatsAppInvalidWebhookConfigException` belong to no verb's row, and none renders the text it refused.

## Identifiers

The Cloud API has several string ids that are easy to confuse. Each is its own opaque type under `WhatsAppId`: `WabaId` (the business account), `PhoneNumberId` (the business number, a field of the config), `MediaId`, `MessageId` (the WAMID) and `WaId` (a customer's number). A `WaId` passed where a `MessageId` is expected does not compile. The WAMID `send` answers is the value `Reaction`, `markRead` and `replyTo` take, and the `id` of an inbound message flows into a reply with no parsing:

```scala
val react =
    WhatsApp.let(config) {
        WhatsApp.send(customer, WhatsAppMessage.Reaction(sentWamid, "👍"))
    }
```

## Endpoints the module does not model

`WhatsApp.custom` calls any Graph endpoint under `{baseUrl}/{apiVersion}/`, with the bearer token, and decodes the answer as your type. The path is a `WhatsAppPath`: relative segments of `A-Z a-z 0-9 . _ -`, anything else panicking with `WhatsAppInvalidPathException`, since a `?` or `..` would move the token to another URL. Query parameters go in `query`, which the module percent-encodes. Each method is sent as itself: POST, PUT and PATCH send `body` as JSON, and every other method sends no body.

```scala
case class PhoneNumberInfo(verified_name: String, quality_rating: String) derives Schema

val info =
    WhatsApp.let(config) {
        WhatsApp.custom[Unit, PhoneNumberInfo](
            HttpMethod.GET,
            WhatsAppPath(config.phoneNumberId.value),
            query = Seq("fields" -> "verified_name,quality_rating")
        )
    }
```

`custom` names only the Graph errors that mean the same on every endpoint; any other code is `WhatsAppOtherApiException`.

## Putting it together

The support bot: the handshake and the notification route mounted on one server, marking each inbound text read and answering it, and downloading any photo a customer sends.

```scala
def reply(message: WhatsAppNotification.InboundMessage): Unit < (Async & Abort[WhatsAppMarkReadFailure | WhatsAppSendFailure | WhatsAppDownloadFailure] & Env[WhatsApp]) =
    message.content match
        case WhatsAppNotification.Content.Text(body) =>
            WhatsApp.markReadWithTyping(message.id)
                .andThen(WhatsApp.send(message.from, WhatsAppMessage.Text(s"You said: $body"), replyTo = Present(message.id)).unit)
        case media: WhatsAppNotification.Content.Media =>
            WhatsAppMedia.download(media.id).unit
        case _ => Kyo.unit

val bot =
    WhatsAppWebhook.handler(config, webhook) {
        case message: WhatsAppNotification.InboundMessage => reply(message)
        case _                                            => Kyo.unit
    }.map { handler =>
        HttpServer.init(0, "localhost")(WhatsAppWebhook.verificationHandler(webhook), handler)
    }
```
