<!-- doctest:setup
```scala
import kyo.*

// The running domain: a customer-support bot for a fictional store, Northwind Supplies.
val config =
    WhatsAppConfig.init(
        token = WhatsAppToken.init("EAAG-redacted").getOrThrow,
        phoneNumberId = WhatsAppId.PhoneNumberId("106540352242922")
    ).getOrThrow

val webhook =
    WhatsAppWebhookConfig.init(
        appSecret = WhatsAppAppSecret.init("my-app-secret").getOrThrow,
        verifyToken = WhatsAppVerifyToken.init("my-verify-token").getOrThrow,
        path = "hooks/whatsapp"
    ).getOrThrow

val customer     = WhatsAppId.WaId("16505551234")
val sentWamid    = WhatsAppId.MessageId("wamid.sent")
val inboundWamid = WhatsAppId.MessageId("wamid.inbound")
val receiptBytes = Span.empty[Byte]
```
-->

# kyo-whatsapp

`kyo-whatsapp` is a client for the [WhatsApp Business Cloud API](https://developers.facebook.com/docs/whatsapp/cloud-api). The verbs a bot calls (`WhatsApp.send`, `WhatsApp.sendTemplate`, `WhatsApp.markRead`, the `WhatsAppMedia` verbs and `WhatsApp.custom`) are functions whose rows require the client, `Env[WhatsApp]`, which `WhatsApp.run(config)` provides. Inbound messages arrive by webhook: `WhatsAppWebhook.handler` verifies each POST's signature, decodes it into `WhatsAppNotification` values and runs your callback on that client, as a kyo-http `HttpHandler` you mount on your own `HttpServer` inside the client's region.

Every verb fails with its own sealed trait, such as `WhatsAppSendFailure`, whose leaves are exactly the failures that verb can meet. The access token travels in the `Authorization` header of every request, and no failure of the module holds it. The module is cross-platform (JVM, Scala.js, Scala Native and WebAssembly) from one shared source set.

A bot that answers every inbound message, mounted with the registration handshake on your own server:

```scala
val answering =
    WhatsApp.run(config) {
        WhatsAppWebhook.handler(webhook) {
            case WhatsAppNotification.Message(_, _, message: WhatsAppInboundMessage.Common) =>
                WhatsApp.send(message.from, WhatsAppMessage.Text("Thanks for contacting Northwind!")).unit
            case _ => Kyo.unit
        }.map { handler =>
            HttpServer.init(0, "localhost")(WhatsAppWebhook.verificationHandler(webhook), handler).map(_.await)
        }
    }
```

Inside the callback the verbs need no setup: they run on the client of the `WhatsApp.run` region the handler was built in. The server is awaited inside that region, since the region's end closes the client. Outside a webhook, `WhatsApp.run(config)` provides a client the same way:

```scala
val greeting: WhatsAppSendResult < (Async & Abort[WhatsAppSendFailure]) =
    WhatsApp.run(config) {
        WhatsApp.send(customer, WhatsAppMessage.Text("Thanks for contacting Northwind!"))
    }
```

Like every Kyo computation, `answering` and `greeting` are descriptions: nothing is served or sent until they run inside an `Async` handler. The sections below build the support bot one piece at a time, and [Putting it together](#putting-it-together) combines them.

## What kyo-whatsapp does not do

- It retries a call only when the config asks it to (see [Retrying](#retrying)); otherwise a rate limit is a `WhatsAppRateLimitException` leaf for the caller to act on (see [Errors](#errors)).
- It does not deduplicate. Meta redelivers a whole webhook POST that did not get a 200, and a callback that must not act twice deduplicates by the message or status id each notification carries.
- It models messages, templates, read receipts, media and the `messages` webhook field. Template management, phone-number registration and business profiles are reachable through `custom`; other webhook fields arrive as `WhatsAppNotification.Unknown` with their JSON.
- It does not check a downloaded file against the `sha256` of its `MediaInfo`.

## Configuration and the client

A `WhatsAppConfig` carries the access token, the registered phone-number id (a path segment of every messages and media upload URL), the Graph API version (default `v25.0`), the base URL (default `https://graph.facebook.com`), and the request limits: `requestTimeout` and `connectTimeout` (10 seconds each) and `maxResponseLength` (100 MB, the largest media Meta stores).

A config and each credential are built by `init`, which returns a `Result` holding the value or the failure naming what is wrong with it:

```scala
val tuned: WhatsAppConfig < Abort[WhatsAppInvalidTokenException | WhatsAppInvalidConfigException] =
    for
        token  <- Abort.get(WhatsAppToken.init("EAAG-redacted"))
        config <- Abort.get(WhatsAppConfig.init(token, WhatsAppId.PhoneNumberId("106540352242922"), requestTimeout = 30.seconds))
    yield config
```

`WhatsAppConfig.init` fails with `WhatsAppInvalidConfigException` naming the setting the module cannot use: a base URL that is not an absolute `http` or `https` URL on a host in printable ASCII, or that has userinfo, a query or a trailing slash; an `apiVersion` other than `v`, digits, a dot and digits; a `phoneNumberId` other than ASCII digits; a zero or infinite timeout or `retryMaxDelay`. `maxResponseLength` is never refused: kyo-http holds the bound as an `Int`, so a zero bound becomes one byte and one past `Int.MaxValue` becomes `Int.MaxValue`. A config has no `copy`, so a changed one goes through `init` and is checked again.

```scala
assert(
    WhatsAppConfig.init(config.token, config.phoneNumberId, apiVersion = "latest") ==
        Result.fail(WhatsAppInvalidConfigException(WhatsAppInvalidConfigException.Problem.ApiVersion))
)
```

The three credentials each have their own type: `WhatsAppToken` (the access token, sent as the bearer), `WhatsAppAppSecret` (the key of the webhook signature) and `WhatsAppVerifyToken` (the string of the registration handshake). Each `init` fails with `WhatsAppInvalidTokenException` on empty text; the token and the app secret also refuse a character a header cannot carry (anything but printable ASCII without space), naming its position and never the text. Each prints as `<redacted>`, so a config that holds one never shows it:

```scala
assert(config.toString.startsWith("WhatsAppConfig(WhatsAppToken(<redacted>),106540352242922,v25.0,"))
```

`WhatsApp.run(config)(v)` builds a client for the duration of `v` and releases it afterwards. The client has its own kyo-http `HttpClient` and runs every request under the module's complete settings, so no filter, base URL, retry or TLS setting of the caller's reaches a request that carries the token. A verb used outside a region that provides a client does not compile.

A client is also a value, for a lifetime longer than one region. `WhatsApp.init(config)` builds one closed when the enclosing `Scope` ends, `WhatsApp.initUnscoped(config)` one that only `WhatsApp.close(client)` closes, and `WhatsApp.run(client)(v)` provides a built client to `v` without closing it. Building a client cannot fail: it opens no connection until a verb sends a request.

```scala
val twice: Unit < (Async & Abort[WhatsAppSendFailure] & Scope) =
    WhatsApp.init(config).map { client =>
        WhatsApp.run(client)(WhatsApp.send(customer, WhatsAppMessage.Text("Your order shipped."))).andThen(
            WhatsApp.run(client)(WhatsApp.send(customer, WhatsAppMessage.Text("It arrives Friday.")))
        ).unit
    }
```

### Retrying

By default a call is sent once. With `retry` set to a `Schedule`, a call whose answer is a Graph error Meta's error-code reference tells a caller to try again on (codes 2, 4, 80007, 130429, 131000, 131016, 131056 and 133004) is sent again, after the schedule's delay or the answer's `Retry-After`, whichever is longer, until the schedule ends. Any other answer is the result at once.

```scala
val patient: Result[WhatsAppInvalidConfigException, WhatsAppConfig] =
    WhatsAppConfig.init(
        config.token,
        config.phoneNumberId,
        retry = Present(Schedule.exponentialBackoff(1.second, 2.0, 30.seconds).take(4)),
        retryMaxDelay = 60.seconds
    )
```

No wait exceeds `retryMaxDelay` (60 seconds by default): an answer whose `Retry-After` asks for longer is the result at once, a rate-limit leaf whose `retryAfter` carries the wait, so the caller decides whether to wait that long.

A retried send can be delivered twice. An error answer does not prove the first attempt's message was not accepted, and the retry sends it again with nothing Meta can match it by. Retry `send` and `sendTemplate` only when a duplicate message is acceptable.

## Sending messages

The outbound shape is always the same: build one `WhatsAppMessage` and pass it to `WhatsApp.send`. The case you pick selects the Cloud API message type.

```scala
val thanks =
    WhatsApp.run(config) {
        WhatsApp.send(customer, WhatsAppMessage.Text("Thanks for contacting Northwind!"))
    }
```

`send` answers a `WhatsAppSendResult`: the new message's id (`messageId`), the recipient's resolved wa_id (`contactWaId`), and the optional `status`, which is `Accepted`, `HeldForQualityAssessment`, `Paused`, or `Other(value)` for a value Meta adds later.

`WhatsAppMessage.OfInteractive` wraps the `WhatsAppInteractive` cases: `ListMenu`, `Buttons`, `CtaUrl`, `Flow`, `Product` and `ProductList`. The support bot offers two quick-reply buttons:

```scala
val askMenu =
    WhatsApp.run(config) {
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

A `Flow` names its flow by id or by name (`Flow.Ref.ById`, `Flow.Ref.ByName`), how it starts as a `Flow.Start` (`Navigate(screen, data)`, or `DataExchange`, which lets the flow's endpoint choose the screen), and its `Flow.Mode` (`Published` by default, or `Draft`).

The media cases (`Image`, `Video`, `Document`, `Audio`, `Sticker`) take a `WhatsAppMedia.Source`: `ById` for an asset you uploaded (see [Media](#media)), or `ByLink` with an `HttpUrl` Meta fetches; `Audio` also marks a voice note. `Location`, `Contacts` and `Reaction` complete the set. Each case holds the Cloud API's object for its type, a record whose `Schema` is that object, so `Json.encode` of a message is the JSON Meta documents; a companion `apply` takes the fields, as above, and methods read them. `send` takes an optional `replyTo` that threads the new message under the one it answers:

```scala
val reply =
    WhatsApp.run(config) {
        WhatsApp.send(customer, WhatsAppMessage.Text("Your order ships tomorrow."), replyTo = Present(inboundWamid))
    }
```

`markRead(id)` posts the read receipt for an inbound message, and `markReadWithTyping(id)` also shows a typing indicator while the bot composes a reply.

### Templates

Once the 24-hour customer-service window closes (`WhatsAppWindowClosedException`), a template is the only message Meta delivers, so it carries any conversation the business starts. A `WhatsAppTemplate` names a registered template, a language code, and fills its variables with `Component` values (`Header`, `Body`, `Button`) of typed `Parameter`s:

```scala
val shippingNotice =
    WhatsApp.run(config) {
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
    WhatsApp.run(config) {
        WhatsAppMedia.upload(receiptBytes, WhatsAppMedia.MediaType.ImagePng, filename = Present("receipt.png")).map { id =>
            WhatsApp.send(customer, WhatsAppMessage.Image(WhatsAppMedia.Source.ById(id), caption = Present("Your receipt")))
        }
    }
```

The filename and a `MediaType.Other` mime go into the multipart part head, so each must be printable ASCII without `"` or `\`. A filename taken from a customer's document, which can hold any text, fails with `WhatsAppRefusedPartException` before anything is sent rather than reaching the head as it is.

A photo a customer sends arrives with a media id. `download(id)` resolves its URL and fetches the bytes; it is `resolveUrl(id)`, which answers a `MediaInfo` (the URL, `mimeType`, `sha256` and `fileSize`), followed by `downloadFrom(info)`:

```scala
val photo =
    WhatsApp.run(config) {
        WhatsAppMedia.download(WhatsAppId.MediaId("media-123"))
    }
```

A media URL is pre-signed and valid for about five minutes. Its query is a credential, so `MediaInfo.url` is a `WhatsAppMediaUrl`, whose `toString` renders the query as `?<redacted>` and whose `value` is the full `HttpUrl`; `downloadFrom` sends nothing to a URL that is not an absolute `http` or `https` URL on a host (`WhatsAppRefusedUrlException`). `delete(id)` removes an uploaded asset.

## Webhooks

Meta registers a webhook with a GET handshake, then POSTs signed notifications to it. A `WhatsAppWebhookConfig` holds what both need: the app secret that keys each POST's `X-Hub-Signature-256`, the verify token Meta echoes in the handshake, and the `path` both routes are mounted at. A path holding `?`, `#`, a space, a control character or non-ASCII can never match a request, so `WhatsAppWebhookConfig.init` refuses it with `WhatsAppInvalidWebhookConfigException`.

`WhatsAppWebhook.verificationHandler(webhook)` answers the handshake: 200 with `hub.challenge` when `hub.verify_token` matches, compared in constant time, and 403 otherwise. `WhatsAppWebhook.handler(webhook)(f)` is the POST route. It requires the client, `Env[WhatsApp]`, and every call of `f` runs with that client, so the callback calls the verbs directly. A handler taken out of its `WhatsApp.run` region holds a closed client, and every verb its callback calls fails:

```scala
val inbound: HttpHandler["body" ~ Span[Byte], Any, WhatsAppMarkReadFailure] < Env[WhatsApp] =
    WhatsAppWebhook.handler(webhook) {
        case WhatsAppNotification.Message(_, _, message: WhatsAppInboundMessage.Common) => WhatsApp.markRead(message.id)
        case _                                                                          => Kyo.unit
    }
```

The handler's error type is the callback's own, `WhatsAppMarkReadFailure` here, and nothing of the module's. What the handler answers decides what Meta does next:

- a POST whose signature is missing, malformed or wrong gets 403 and is not decoded;
- a body that is not a notification envelope gets 200 and one log line naming the decode failure, since Meta would redeliver it forever otherwise;
- a callback that fails or panics gets 500, and Meta redelivers the whole POST, so the notifications before the failing one arrive again.

`WhatsAppWebhook.verify(webhook, header, body)` and `WhatsAppWebhook.decode(body)` are the two steps on their own, for a bot that serves the endpoint itself. The signature is computed over the exact bytes Meta sent, which is why the handler reads the body as raw bytes.

A POST decodes to one `WhatsAppNotification` per message and per status:

- `Message(metadata, contact, message)` carries the business number's `Metadata`, the sender's profile from the change's `contacts` when Meta sends one, and the `WhatsAppInboundMessage`.
- `Status(metadata, status)` carries the `WhatsAppStatus` of a message the business sent.
- `Unknown(type, payload, metadata)` is what the module does not model or cannot decode, by its change's field name, with its JSON as Meta sent it: a change of another webhook field, a `messages` change with neither messages nor statuses, a change that does not decode, and a message or status that does not decode, such as one whose timestamp is not epoch seconds. No notification carries a value standing for a missing one. Each message and status decodes on its own: one Meta changed the shape of arrives as `Unknown`, and the rest of the POST is still delivered, since Meta treats the 200 as delivered and would not resend it.

`WhatsAppInboundMessage` is Meta's message object, tagged by `type`: `Text`, `Image`, `Video`, `Audio`, `Document`, `Sticker`, `Location`, `Contacts`, `Reaction`, `Button`, `Interactive` (a `ButtonReply` or a `ListReply`), `Order`, `System`, or `Unknown` for a type the module does not enumerate, which keeps the message as Meta sent it. Every typed case is a `WhatsAppInboundMessage.Common`, with the sender (`from`), the message `id`, the `timestamp` and the `context` of a reply, and holds Meta's object for its type under the key the type names: the five media cases hold a `Media` (`id`, `mimeType`, `sha256`, and the optional `caption`, `filename` and `voice`), and methods such as `Text.body` read the fields.

`WhatsAppStatus` is Meta's status object: the message `id`, its `status` (`Sent`, `Delivered`, `Read`, `Failed`, `Deleted`, or `Other` for a value Meta adds later), the timestamp, the recipient, the optional conversation and pricing, and the `DeliveryIssue`s of a failed message. A `DeliveryIssue` holds Meta's `error_data` object, and `details` reads its explanation.

Each of these types, and `WhatsAppWebhookPayload`, the POST body itself, has a `Schema` that is Meta's JSON for it, so `Json.decode[WhatsAppWebhookPayload]` reads a POST as Meta sends it, with an item that does not decode kept as its JSON. A notification composes Meta's objects and is not one of them, so it has no `Schema`. A raw payload is a `WhatsAppRawJson`: `json` is the payload as a `Structure.Value` and `value` its JSON text, its `Schema` is that JSON, and its `toString` renders only its length, since the text is the sender's data.

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
        WhatsApp.run(config)(WhatsApp.send(customer, WhatsAppMessage.Text("Thanks for contacting Northwind!")))
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
- `WhatsAppDecodeException(method, part, failure, path, position)`: an answer or a notification that did not decode, located at `path`. Nothing of the body is kept. An answer whose types are right and whose values are not is this failure too, as `ConstructorRejected`: a send answer with an empty `messages`, and an acknowledgement of `{"success": false}`.
- `WhatsAppRefusedUrlException(method)`: a media URL or media id the module will not send the token to.
- `WhatsAppRefusedPartException(method, field)`: an upload `filename`, or a `MediaType.Other` mime, that is not printable ASCII or holds a `"` or `\`, refused before anything is sent because it is written into the multipart part head. Nothing of the value is kept.
- The signature leaves `WhatsAppSignatureMissingException`, `WhatsAppSignatureMalformedException` and `WhatsAppSignatureMismatchException`.

A value that cannot be built is the failure of its `init`, not of a verb: `WhatsAppInvalidConfigException`, `WhatsAppInvalidTokenException`, `WhatsAppInvalidPathException` and `WhatsAppInvalidWebhookConfigException` belong to no verb's row, and none renders the text it refused.

## Identifiers

The Cloud API has several string ids that are easy to confuse. Each is its own opaque type under `WhatsAppId`: `WabaId` (the business account), `PhoneNumberId` (the business number, a field of the config), `MediaId`, `MessageId` (the WAMID) and `WaId` (a customer's number). A `WaId` passed where a `MessageId` is expected does not compile. The WAMID `send` answers is the value `Reaction`, `markRead` and `replyTo` take, and the `id` of an inbound message flows into a reply with no parsing:

```scala
val react =
    WhatsApp.run(config) {
        WhatsApp.send(customer, WhatsAppMessage.Reaction(sentWamid, "👍"))
    }
```

## Endpoints the module does not model

`WhatsApp.custom` calls any Graph endpoint under `{baseUrl}/{apiVersion}/`, with the bearer token, and decodes the answer as your type. The path is a `WhatsAppPath`, built by `WhatsAppPath.init`: relative segments of `A-Z a-z 0-9 . _ -`, anything else refused with `WhatsAppInvalidPathException`, since a `?` or `..` would move the token to another URL. Query parameters go in `query`, a kyo-http `HttpQueryParams`, percent-encoded on the way out. Each method is sent as itself: POST, PUT and PATCH send `body` as JSON, and every other method sends no body.

```scala
case class PhoneNumberInfo(verified_name: String, quality_rating: String) derives Schema

val info =
    WhatsApp.run(config) {
        Abort.get(WhatsAppPath.init(config.phoneNumberId.value)).map { path =>
            WhatsApp.custom[Unit, PhoneNumberInfo](
                HttpMethod.GET,
                path,
                query = HttpQueryParams.init("fields" -> "verified_name,quality_rating")
            )
        }
    }
```

`custom` names only the Graph errors that mean the same on every endpoint; any other code is `WhatsAppOtherApiException`.

## Putting it together

The support bot: the handshake and the notification route mounted on one server, marking each inbound text read and answering it, and downloading any photo a customer sends.

```scala
def reply(message: WhatsAppInboundMessage)
    : Unit < (Async & Abort[WhatsAppMarkReadFailure | WhatsAppSendFailure | WhatsAppDownloadFailure] & Env[WhatsApp]) =
    message match
        case text: WhatsAppInboundMessage.Text =>
            WhatsApp.markReadWithTyping(text.id)
                .andThen(WhatsApp.send(text.from, WhatsAppMessage.Text(s"You said: ${text.body}"), replyTo = Present(text.id)).unit)
        case photo: WhatsAppInboundMessage.Image =>
            WhatsAppMedia.download(photo.image.id).unit
        case _ => Kyo.unit

val bot =
    WhatsApp.run(config) {
        WhatsAppWebhook.handler(webhook) {
            case WhatsAppNotification.Message(_, _, message) => reply(message)
            case _                                           => Kyo.unit
        }.map { handler =>
            HttpServer.init(0, "localhost")(WhatsAppWebhook.verificationHandler(webhook), handler).map(_.await)
        }
    }
```
