<!-- doctest:setup
```scala
import kyo.*

// The running domain: a standup bot in a team's group chat. It answers /standup with a
// question and two buttons, records each answer, and edits its question to show the tally.
val config = Telegram.Token.init("123456:ABC-standup_bot_token").flatMap(TelegramConfig.init(_)).getOrThrow

val team = Telegram.Chat.Target.Id(Telegram.ChatId(-1001234567890L))

// Stand-ins for the bot's own storage, kept out of the visible examples.
def recordAnswer(user: Telegram.User, answer: String): Unit < Sync = ()
def tally: String < Sync                                           = "2 done, 1 blocked"
```
-->

# kyo-telegram

`kyo-telegram` is a client for the [Telegram Bot API](https://core.telegram.org/bots/api). `Telegram.run(config)` builds the client and provides it to a region. Inside it, `Telegram.receive(handler)` long-polls Telegram for updates and hands each one to your handler, in order. The verbs a bot sends (`Telegram.send`, `Telegram.edit`, `Telegram.answerCallback`, and the rest) are functions on the `Telegram` object whose rows require the client, `Env[Telegram]`, as `receive` does. When the bot has a public HTTPS endpoint, `Telegram.Webhook.handler` receives the same updates on a kyo-http server instead.

Every verb fails with its own sealed trait, such as `TelegramSendFailure`, whose leaves are exactly the failures that verb can meet. The bot token travels in the path of every request, and no failure of the module holds it. The module is cross-platform (JVM, Scala.js, Scala Native and WebAssembly) from one shared source set.

A bot that answers every text message:

```scala
import kyo.*

val echo: Unit < (Async & Abort[TelegramReceiveFailure | TelegramSendFailure]) =
    Telegram.run(config) {
        Telegram.receive {
            case Telegram.Update.Message(_, message) =>
                message.text.fold(Kyo.unit)(text => Telegram.send(message.chat.target, Telegram.Content.text(s"You said: $text")).unit)
            case _ => Kyo.unit
        }
    }
```

The handler is a function from `Telegram.Update` to `Unit`. Its own failures (`TelegramSendFailure` here) and any other effects it has pass through to `receive`'s row. `receive` polls until it is interrupted or fails; `Fiber.init(Telegram.run(config)(Telegram.receive(handler)))` runs it in the background, where the fiber ends with the loop's failure, `interrupt` stops polling, and the enclosing scope closes it. The sections below build the standup bot one piece at a time. [Putting it together](#putting-it-together) combines them into one handler.

Every type the module models is nested in `object Telegram`: `Telegram.Update`, `Telegram.Message`, `Telegram.Content`, and so on. The examples write them in full; `import Telegram.*` brings the short names into scope when you prefer them. The configurations and the failures are top-level types.

## What kyo-telegram does not do

- It does not retry a verb unless asked. `receive` retries its own polling under `config.retrySchedule`. A verb's rate limit is `TelegramRateLimitException` with the delay Telegram sent, for the caller to act on (see [Errors](#errors)), unless `config.retry` is set: then a verb whose answer is Telegram's flood control (`retry_after`, the one retry the Bot API documents) is sent again after that delay, while the schedule allows another attempt. A delay longer than `config.retryMaxDelay` (60 seconds by default) is not waited: the verb fails with `TelegramRateLimitException` at once. A retried send can be delivered twice, when Telegram carried out the first attempt.
- It does not deduplicate. Telegram redelivers an update it did not see confirmed, and a handler that must not act twice deduplicates by `update.id`.
- It models messages, edits, callback queries, reactions, membership changes, files, commands and webhooks. Inline mode, payments, stickers, polls and business messages arrive as `Telegram.Update.Unknown` with their JSON, and their methods are reachable through `custom`.
- `setWebhook` does not upload a self-signed certificate.
- It does not fetch a file from a local Bot API server, which answers `getFile` with a path on its own disk.

## Configuration and the client

A `TelegramConfig` carries the bot's token, which [BotFather](https://core.telegram.org/bots#how-do-i-create-a-bot) gives you, and the settings of polling and requests. Everything but the token has a default. Both are built with `init`, which answers a `Result`: the value, or the typed failure naming what Telegram would refuse.

```scala
import kyo.*

val tuned: Result[TelegramInvalidTokenException | TelegramInvalidConfigException, TelegramConfig] =
    Telegram.Token.init("123456:ABC-standup_bot_token").flatMap { token =>
        TelegramConfig.init(
            token,
            pollTimeout = 50.seconds,
            allowedUpdates = Present(Chunk(Telegram.Update.Type.Message, Telegram.Update.Type.CallbackQuery))
        )
    }

val tunedMe: Telegram.User < (Async & Abort[TelegramInvalidTokenException | TelegramInvalidConfigException | TelegramGetMeFailure]) =
    Abort.get(tuned).map(config => Telegram.run(config)(Telegram.getMe))
```

`Telegram.Token.init` checks the token: at most 80 characters from `A-Z a-z 0-9 _ : -`, with a colon. Any other character would change the URL the token is placed in, so a malformed token fails with `TelegramInvalidTokenException` rather than reaching Telegram. The token's `toString` is `Telegram.Token(<redacted>)`, so it cannot end up in a log line by accident. Its `Schema` writes the token itself, so encoding one is a deliberate act, and reading text that cannot be a token fails the decode.

`TelegramConfig.init` checks its settings the same way: `pollTimeout` a whole number of seconds, `pollLimit` from 1 to 100, `requestTimeout`, `transferTimeout` and `connectTimeout` positive, `maxResponseLength` from one byte to `Int.MaxValue` bytes (20 MiB by default, which covers the largest file Telegram serves), and `baseUrl` an absolute `http` or `https` `HttpUrl` on a host, with no user info, no query, no trailing slash after a path, and only printable ASCII characters. `baseUrl` points the bot at a [local Bot API server](https://core.telegram.org/bots/api#using-a-local-bot-api-server) when you run one.

Two timeouts bound a call. `requestTimeout` (10 seconds) bounds every API call. `transferTimeout` (120 seconds) bounds the calls that move a file's bytes: `download`, and a send that uploads a file. At 120 seconds a 20 MB download, the most the Bot API serves, needs about 1.4 Mbit/s, and a 50 MB upload, the most it accepts for a document, about 3.4 Mbit/s. `tls` and `transport` set the TLS and byte-transport settings of the module's own HTTP clients, for example to trust a self-hosted Bot API server's certificate.

`Telegram.run(config)` builds the client for a region and closes it when the region ends. A verb's row names the client, so a program that calls one outside `run` does not compile where it is run:

```scala
import kyo.*

val me: Telegram.User < (Async & Abort[TelegramGetMeFailure]) =
    Telegram.run(config)(Telegram.getMe)
```

To hold a client across several regions, build it with `Telegram.init(config)`, which closes it when the enclosing `Scope` ends, and provide it with `Telegram.run(client)`, which leaves it open. Building a client opens no connection, so it cannot fail. `Telegram.initUnscoped` builds one that only `Telegram.close` closes, for a lifetime no `Scope` describes:

```scala
import kyo.*

val twice: (Telegram.User, Telegram.User) < (Async & Scope & Abort[TelegramGetMeFailure]) =
    Telegram.init(config).map { client =>
        Telegram.run(client)(Telegram.getMe).map(first => Telegram.run(client)(Telegram.getMe).map((first, _)))
    }
```

The client's HTTP client is its own. Nothing of your kyo-http configuration reaches a request that carries the token: no filter you installed runs on it, your TLS and transport settings do not apply (the config's do), no redirect is followed, nothing is retried, and a connection you opened to the same server is not reused.

## Receiving updates

`receive` calls `getUpdates` in a loop and gives each `Telegram.Update` to the handler. The cases are `Message`, `EditedMessage`, `ChannelPost`, `EditedChannelPost`, `CallbackQuery`, `MyChatMember`, `ChatMember`, `MessageReaction`, and `Unknown` for the kinds the module does not model. Each carries the update's `id`. `Unknown` keeps the name of its kind, when the update names one, and the update's raw JSON, so nothing Telegram sent is dropped. A known kind whose payload does not decode is `Unknown` too, and `receive` confirms an `Unknown` update like any other.

A message's `content` is `Text`, `Photo`, `Document`, `Audio`, `Video`, `Voice`, `Location`, or `Unknown`. `message.text` answers the text or a media caption, which is what most command handling needs:

```scala doctest:scope=env:standup
import kyo.*

def isStandupCommand(message: Telegram.Message): Boolean = message.text.exists(_.startsWith("/standup"))
```

The order of confirmation is what makes `receive` safe to stop at any point. Telegram keeps an update until the bot asks for updates past its id, and `receive` does that only after the handler has returned for it. So:

- Updates are handled one at a time, in the order Telegram sent them.
- A handler that fails, with a typed failure or a panic, ends `receive` with that failure, and the update stays unconfirmed. The next `receive` gets it again.
- Interrupting `receive` stops polling and closes the poll's connection; the update being handled is not confirmed.

The handler may carry effects of its own beyond `Async`, `Abort` and the client, and `receive` passes them through on its row.

Transport failures, server errors and rate limits are retried under `config.retrySchedule` (exponential from 1 second, capped at 60 seconds), waiting the delay Telegram sent when it sent one. Failures that retrying cannot change, such as `TelegramUnauthorizedException` for a revoked token, end `receive` at once.

> **Note:** Telegram sends some kinds only when they are asked for by name: reactions (`Telegram.Update.Type.MessageReaction`) and other members' changes (`ChatMember`) never arrive with `allowedUpdates` left `Absent`. And Telegram delivers updates either by polling or by webhook, never both: while a webhook is set, `receive` fails with `TelegramConflictException`, and `Telegram.deleteWebhook()` switches the bot back to polling.

## Sending messages

`send` takes a target chat, a `Telegram.Content` and optional `Telegram.SendOptions`, and answers the `Telegram.Message` Telegram created. A target is a chat id or, for a public channel or group, its `@username`:

```scala
import kyo.*

val remind: Telegram.Message < (Async & Abort[TelegramSendFailure] & Env[Telegram]) =
    Telegram.send(team, Telegram.Content.text("Standup in 5 minutes"), Telegram.SendOptions(silent = true))

val toChannel = Telegram.Chat.Target.Username("kyo_announcements")
```

Content is text, a photo, a document, audio, video, a voice note or a location. A file is sent by the id of one Telegram already has, by an `HttpUrl` Telegram fetches, or as an upload of bytes, which the module sends as `multipart/form-data`:

```scala
import kyo.*

def chart(png: Span[Byte]): Telegram.Content =
    Telegram.Content.Photo(
        Telegram.InputFile.Upload("burndown.png", png, Present("image/png")),
        caption = Present(Telegram.Text("This sprint's burndown"))
    )
```

`Telegram.sendChatAction(team, Telegram.ChatAction.Typing)` shows "typing..." while the bot prepares a slow answer, `Telegram.setReaction` reacts to a message with an emoji or a custom emoji (a bot cannot send a paid reaction, so `Reaction.Sendable` has none), and `Telegram.delete` removes one.

## Formatting

Telegram formats text written in MarkdownV2 or HTML, and each has characters that must be escaped wherever they appear. `Telegram.Markup` is a tree of styled parts that the module renders into either, escaping every part as its position requires, so user-supplied text cannot break the formatting:

```scala
import kyo.*
import kyo.Telegram.Markup.*

def question(name: String): Telegram.Text =
    Telegram.Text.MarkdownV2(of(
        Bold(Text("Standup")),
        Text(s" for $name (2024-05-01): what's your status?")
    ))
```

The styles are `Bold`, `Italic`, `Underline`, `Strikethrough`, `Spoiler`, `Code`, `Pre` (a code block, with a language), `Link`, `Mention` (a link to a user by id) and `Blockquote`. `Telegram.Text.Html(markup)` renders the same tree as HTML. `Telegram.Text.Plain(text, entities)` sends text with the formatting given as entities by offset, the form Telegram itself uses on received messages.

## Keyboards and callback queries

A message can carry a keyboard. An inline keyboard sits under the message; pressing a callback button sends the bot a `CallbackQuery` update with the button's data. The data is at most 64 bytes, counted in UTF-8, so `InlineButton.callback` answers a `Result` that fails with `TelegramInvalidCallbackDataException` for a longer value. A URL button takes a `Telegram.Url`, whose `init` accepts `http`, `https` and Telegram's own `tg://` links:

```scala doctest:scope=env:standup
import kyo.*

val answers: Result[TelegramInvalidCallbackDataException | TelegramInvalidUrlException, Telegram.Keyboard] =
    for
        done    <- Telegram.Keyboard.InlineButton.callback("Done", "standup:done")
        blocked <- Telegram.Keyboard.InlineButton.callback("Blocked", "standup:blocked")
        board   <- Telegram.Url.init("https://example.com/board")
    yield Telegram.Keyboard.inline(Seq(done, blocked), Seq(Telegram.Keyboard.InlineButton.Url("Board", board)))
```

A button can also carry a `style`, which colors it: `Style.Danger` (red), `Style.Success` (green) or `Style.Primary` (blue). A reply keyboard (`Telegram.Keyboard.reply`) replaces the user's keyboard with buttons that send their text; `Remove` takes it away, and `ForceReply` opens a reply to the bot's message.

A callback query must be answered, or the user's client shows a spinner on the button until it times out. `answerCallback` answers it, optionally with a notification of up to 200 characters or an alert. The query's `message` is the message the button was under, from which `chat` and the message id come for an edit:

```scala doctest:scope=env:standup
import kyo.*

type AnswerFailure = TelegramInvalidCallbackAnswerException | TelegramAnswerCallbackFailure | TelegramEditFailure

def onAnswer(query: Telegram.CallbackQuery): Unit < (Async & Abort[AnswerFailure] & Env[Telegram]) =
    val answer = query.data.fold("")(_.stripPrefix("standup:"))
    val ack    = Telegram.CallbackAnswer.init(text = Present(s"Recorded: $answer"))
    recordAnswer(query.from, answer).andThen {
        Abort.get(ack).map(Telegram.answerCallback(query.id, _)).andThen {
            query.message match
                case Present(Telegram.CallbackQuery.Source.Accessible(message)) =>
                    tally.map(t =>
                        Telegram.edit(message.chat.target, message.id, Telegram.Edit.Text(Telegram.Text(s"Standup: $t"))).unit
                    )
                case Present(Telegram.CallbackQuery.Source.Inaccessible(_, _)) | Absent => Kyo.unit
        }
    }
end onAnswer
```

`Telegram.Edit` changes a message's text, a media message's caption, or only its inline keyboard. An edit that changes nothing fails with `TelegramMessageNotModifiedException`, which a bot that re-renders on every click usually ignores.

## Files

A received file is known by its `Telegram.FileId`. `getFile` looks up its download path, and `download` fetches its bytes:

```scala
import kyo.*

def fetch(file: Telegram.FileId): Span[Byte] < (Async & Abort[TelegramGetFileFailure | TelegramDownloadFailure] & Env[Telegram]) =
    Telegram.getFile(file).map(Telegram.download)
```

The Bot API serves files of at most 20 MB this way, and a path stays valid for at least an hour. A file with no path fails `download` with `TelegramNoFilePathException`. The path Telegram sends is appended after the token, so `download` accepts only relative segments of letters, digits, `.`, `_` and `-`, and fails with `TelegramRefusedUrlException` otherwise, sending nothing.

## Webhooks

A bot with a public HTTPS URL can have Telegram POST each update to it instead of polling. A `TelegramWebhookConfig` holds the secret Telegram sends in every request's `X-Telegram-Bot-Api-Secret-Token` header and the path the handler is mounted at. `Telegram.setWebhook` registers the public URL with that secret, and `Telegram.Webhook.handler` is a kyo-http handler that checks the secret, decodes the update and runs your callback with the client of the `Telegram.run` region it was built in, so the callback calls the verbs directly:

```scala doctest:scope=env:standup
import kyo.*

val webhook: Result[TelegramInvalidTokenException | TelegramInvalidWebhookConfigException, TelegramWebhookConfig] =
    Telegram.SecretToken.init("standup-webhook-secret").flatMap(TelegramWebhookConfig.init(_, "telegram"))

type WebhookConfigFailure = TelegramInvalidTokenException | TelegramInvalidWebhookConfigException

val server: HttpServer < (Async & Scope & Abort[WebhookConfigFailure | HttpBindException | HttpRouteException] & Env[Telegram]) =
    Abort.get(webhook).map { webhook =>
        Telegram.Webhook.handler[TelegramSendFailure](webhook) {
            case Telegram.Update.Message(_, message) if isStandupCommand(message) =>
                Telegram.send(team, Telegram.Content.text("Standup time")).unit
            case _ => Kyo.unit
        }.map(handler => HttpServer.init(8443, "0.0.0.0")(handler))
    }

val serving: Unit < (Async & Abort[WebhookConfigFailure | HttpBindException | HttpRouteException]) =
    Telegram.run(config)(Scope.run(server.map(_.await)))

val register: Unit < (Async & Abort[WebhookConfigFailure | TelegramSetWebhookFailure | HttpException] & Env[Telegram]) =
    Abort.get(webhook).map { webhook =>
        Abort.get(HttpUrl.parse("https://bot.example.com/telegram")).map(url => Telegram.setWebhook(url, webhook))
    }
```

What the handler answers decides what Telegram does next:

| Request | Answer | What Telegram does |
|---|---|---|
| no secret header, or a wrong one | 403, nothing decoded | nothing, when the request was not Telegram's; a 403 to Telegram's own request, after a secret changed, is retried like any failed delivery |
| a body that is not an update | 200, a warning logged | nothing: it would never decode |
| the callback succeeds | 200 | nothing |
| the callback fails or panics | 500 | delivers the update again, a limited number of times |

The secret is compared in constant time. `Telegram.Webhook.verify` and `Telegram.Webhook.decode` are the two steps on their own, for a bot that serves the endpoint without kyo-http's routing. `setWebhook` refuses a URL that is not absolute `http` or `https` on a host with `TelegramRefusedUrlException`, sending nothing. Building the handler requires the client, and every delivery's callback runs on it, so the server belongs inside the `Telegram.run` region, which keeps the client open while the server serves.

> **Caution:** Telegram keeps up to 40 deliveries open at once (`Telegram.WebhookOptions.maxConnections`, 1 to 100), so callbacks run concurrently, unlike `receive`'s handler. A callback that fails on purpose so Telegram redelivers also makes kyo-http log `unhandled handler error` at ERROR.

## Errors

Every verb's failure type names what that verb can meet, and nothing else. `send` fails with `TelegramSendFailure`, which includes `TelegramChatNotFoundException` and `TelegramForbiddenException` (the user blocked the bot) but not `TelegramMessageNotModifiedException`, which only an edit can meet. So a match over a verb's failures is checked against the right set:

```scala
import kyo.*

def announce(text: String): Unit < (Async & Abort[TelegramSendFailure] & Env[Telegram]) =
    Abort.run[TelegramSendFailure](Telegram.send(team, Telegram.Content.text(text))).map {
        case Result.Success(_)                             => Kyo.unit
        case Result.Failure(e: TelegramForbiddenException) => Log.warn(s"removed from the team chat: ${e.description}")
        case Result.Failure(e: TelegramMigratedException)  => Log.warn(s"the group moved to ${e.chat.value}")
        case Result.Failure(e: TelegramRateLimitException) =>
            Async.sleep(e.retryAfter.getOrElse(1.second)).andThen(announce(text))
        case Result.Failure(e) => Abort.fail(e)
        case Result.Panic(e)   => Abort.panic(e)
    }
```

The leaves fall into a few groups:

- **Telegram's answers.** `TelegramUnauthorizedException`, `TelegramForbiddenException`, `TelegramConflictException`, `TelegramChatNotFoundException`, `TelegramMessageNotFoundException`, `TelegramMessageNotModifiedException`, `TelegramFileTooBigException`, and `TelegramMigratedException` (a group became a supergroup, with the new chat id) each carry the method and Telegram's description, with the token redacted from it; each stands for one `error_code`, which its `code` returns. Any other error is `TelegramOtherApiException`, which also carries the `code` it received.
- **Rate limits.** `TelegramRateLimitException.retryAfter` is the delay Telegram asked for, when it sent one.
- **The transport.** `TelegramTransportException` carries the method, a `kind` (a connection, TLS or DNS failure, a timeout, a closed connection, an oversized body, and the like), the host and port the request went to, and the timeout that ran out. It never holds the request URL, which holds the token. `TelegramRefusedUrlException` is a URL the module refused to send to: a file path or a webhook URL, as above.
- **The response.** `TelegramUnexpectedStatusException` for a status that is not a Bot API answer, such as a proxy's 502 or a redirect, which the module never follows; `TelegramDecodeException` for a body that does not decode, including a method documented to answer `true` that answered anything else, with which kind of failure, the path and the position, but not the body.

A value Telegram would refuse is refused where it is built, never on a verb's row: each such type's `init` answers a `Result` that fails with its own leaf (`TelegramInvalidTokenException`, `TelegramInvalidConfigException`, `TelegramInvalidUrlException`, `TelegramInvalidCallbackDataException`, `TelegramInvalidCommandException`, `TelegramInvalidMethodException`, `TelegramInvalidWebhookOptionsException`, `TelegramInvalidWebhookConfigException` or `TelegramInvalidCallbackAnswerException`). `Abort.get` lifts it into a computation's row.

## Methods the module does not model

`custom` calls any Bot API method with parameters and a result of your own types, each with a `Schema`. The failures are those of the request path, as `TelegramCustomFailure`. The method is a `Telegram.Method`, whose `init` accepts ASCII letters, digits, `.` and `_`, and not a name of only dots: the name goes into the request path right after the token, and a `/` or `?` in it would send the token somewhere else.

```scala
import kyo.*

case class ChatParam(chat_id: Long) derives Schema

val members: Int < (Async & Abort[TelegramInvalidMethodException | TelegramCustomFailure] & Env[Telegram]) =
    Abort.get(Telegram.Method.init("getChatMemberCount")).map { method =>
        Telegram.custom[ChatParam, Int](method, ChatParam(-1001234567890L))
    }
```

## Putting it together

The standup bot: `/standup` posts the question with the two buttons, and each answer is recorded, acknowledged and shown in the edited question. `setCommands` takes a `Telegram.Command.Menu`, whose `init` refuses more than the 100 commands Telegram accepts, and sets it for Telegram's default scope unless a `scope` is given.

```scala doctest:scope=env:standup
import kyo.*

val standupBot =
    for
        command  <- Abort.get(Telegram.Command.init("standup", "Start today's standup"))
        menu     <- Abort.get(Telegram.Command.Menu.init(command))
        keyboard <- Abort.get(answers)
        _        <- Telegram.run(config) {
            Telegram.setCommands(menu).andThen {
                Telegram.receive {
                    case Telegram.Update.Message(_, message) if isStandupCommand(message) =>
                        Telegram.send(
                            message.chat.target,
                            Telegram.Content.Text(Telegram.Text("Standup: what's your status?")),
                            Telegram.SendOptions(keyboard = Present(keyboard))
                        ).unit
                    case Telegram.Update.CallbackQuery(_, query) => onAnswer(query)
                    case _                                       => Kyo.unit
                }
            }
        }
    yield ()
```
