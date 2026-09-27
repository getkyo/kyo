<!-- doctest:setup
```scala
import kyo.*

// The running domain: a standup bot in a team's group chat. It answers /standup with a
// question and two buttons, records each answer, and edits its question to show the tally.
val config = TelegramConfig(TelegramToken("123456:ABC-standup_bot_token"))

val team = TelegramChat.Target.Id(TelegramId.ChatId(-1001234567890L))

// Stand-ins for the bot's own storage, kept out of the visible examples.
def recordAnswer(user: TelegramUser, answer: String): Unit < Sync = ()
def tally: String < Sync                                          = "2 done, 1 blocked"
```
-->

# kyo-telegram

`kyo-telegram` is a client for the [Telegram Bot API](https://core.telegram.org/bots/api). `Telegram.run(config)(handler)` long-polls Telegram for updates and hands each one to your handler, in order. The verbs a bot sends (`Telegram.send`, `Telegram.edit`, `Telegram.answerCallback`, and the rest) are functions on the `Telegram` object whose rows require the client, `Env[Telegram]`, which `run`, `Telegram.let` and the webhook handler provide. When the bot has a public HTTPS endpoint, `TelegramWebhook.handler` receives the same updates on a kyo-http server instead.

Every verb fails with its own sealed trait, such as `TelegramSendFailure`, whose leaves are exactly the failures that verb can meet. The bot token travels in the path of every request, and no failure of the module holds it. The module is cross-platform (JVM, Scala.js, Scala Native and WebAssembly) from one shared source set.

A bot that answers every text message:

```scala
import kyo.*

val echo: Unit < (Async & Abort[TelegramRunFailure | TelegramSendFailure]) =
    Telegram.run(config) {
        case TelegramUpdate.Message(_, message) =>
            message.text.fold(Kyo.unit)(text => Telegram.send(message.chat.target, TelegramContent.text(s"You said: $text")).unit)
        case _ => Kyo.unit
    }
```

The handler is a function from `TelegramUpdate` to `Unit`. Its own failures (`TelegramSendFailure` here) and any other effects it has pass through to `run`'s row. `run` polls until it is interrupted or fails; `Fiber.init(Telegram.run(config)(handler))` runs it in the background, where the fiber ends with the loop's failure, `interrupt` stops polling, and the enclosing scope closes it. The sections below build the standup bot one piece at a time. [Putting it together](#putting-it-together) combines them into one handler.

## What kyo-telegram does not do

- It never retries a verb. Only `run` retries its own polling, under `config.retrySchedule`; a verb's rate limit is `TelegramRateLimitException` with the delay Telegram sent, for the caller to act on (see [Errors](#errors)).
- It does not deduplicate. Telegram redelivers an update it did not see confirmed, and a handler that must not act twice deduplicates by `update.id`.
- It models messages, edits, callback queries, reactions, membership changes, files, commands and webhooks. Inline mode, payments, stickers, polls and business messages arrive as `TelegramUpdate.Unknown` with their JSON, and their methods are reachable through `custom`.
- `setWebhook` does not upload a self-signed certificate.
- It does not fetch a file from a local Bot API server, which answers `getFile` with a path on its own disk.

## Configuration and the client

A `TelegramConfig` carries the bot's token, which [BotFather](https://core.telegram.org/bots#how-do-i-create-a-bot) gives you, and the settings of polling and requests. Everything but the token has a default.

```scala
import kyo.*

val tuned = TelegramConfig(
    TelegramToken("123456:ABC-standup_bot_token"),
    pollTimeout = 50.seconds,
    allowedUpdates = Present(Chunk(TelegramUpdate.Type.Message, TelegramUpdate.Type.CallbackQuery))
)
```

`TelegramToken` checks the token when it is built: at most 80 characters from `A-Z a-z 0-9 _ : -`, with a colon. Any other character would change the URL the token is placed in, so a malformed token panics with `TelegramInvalidTokenException` at construction rather than reaching Telegram. The token's `toString` is `TelegramToken(<redacted>)`, and it has no `Schema`, so it cannot end up in a log line or an encoded payload by accident.

`TelegramConfig` checks its settings the same way: `pollTimeout` a whole number of seconds, `pollLimit` from 1 to 100, `requestTimeout` and `connectTimeout` positive, `maxResponseLength` from one byte to `Int.MaxValue` bytes (20 MiB by default, which covers the largest file Telegram serves), and `baseUrl` an absolute `http` or `https` `HttpUrl` on a host, with no user info, no query, no trailing slash after a path, and only printable ASCII characters. `baseUrl` points the bot at a [local Bot API server](https://core.telegram.org/bots/api#using-a-local-bot-api-server) when you run one.

`Telegram.let(config)` builds the client for a region and closes it when the region ends. A verb's row names the client, so a program that calls one outside `run` or `let` does not compile where it is run:

```scala
import kyo.*

val me: TelegramUser < (Async & Abort[TelegramGetMeFailure]) =
    Telegram.let(config)(Telegram.getMe)
```

The client's HTTP client is its own. Nothing of your kyo-http configuration reaches a request that carries the token: no filter you installed runs on it, your TLS settings do not apply, no redirect is followed, nothing is retried, and a connection you opened to the same server is not reused.

## Receiving updates

`run` calls `getUpdates` in a loop and gives each `TelegramUpdate` to the handler. The cases are `Message`, `EditedMessage`, `ChannelPost`, `EditedChannelPost`, `CallbackQuery`, `MyChatMember`, `ChatMember`, `MessageReaction`, and `Unknown` for the kinds the module does not model. Each carries the update's `id`. `Unknown` keeps the name of its kind and the update's raw JSON, so nothing Telegram sent is dropped.

A message's `content` is `Text`, `Photo`, `Document`, `Audio`, `Video`, `Voice`, `Location`, or `Unknown`. `message.text` answers the text or a media caption, which is what most command handling needs:

```scala doctest:scope=env:standup
import kyo.*

def isStandupCommand(message: TelegramMessage): Boolean = message.text.exists(_.startsWith("/standup"))
```

The order of confirmation is what makes `run` safe to stop at any point. Telegram keeps an update until the bot asks for updates past its id, and `run` does that only after the handler has returned for it. So:

- Updates are handled one at a time, in the order Telegram sent them.
- A handler that fails, with a typed failure or a panic, ends `run` with that failure, and the update stays unconfirmed. The next `run` receives it again.
- Interrupting `run` stops polling and closes the client; the update being handled is not confirmed.

The handler may carry effects of its own beyond `Async`, `Abort` and the client, and `run` passes them through on its row.

Transport failures, server errors and rate limits are retried under `config.retrySchedule` (exponential from 1 second, capped at 60 seconds), waiting the delay Telegram sent when it sent one. Failures that retrying cannot change, such as `TelegramUnauthorizedException` for a revoked token, end `run` at once.

> **Note:** Telegram sends some kinds only when they are asked for by name: reactions (`TelegramUpdate.Type.MessageReaction`) and other members' changes (`ChatMember`) never arrive with `allowedUpdates` left `Absent`. And Telegram delivers updates either by polling or by webhook, never both: while a webhook is set, `run` fails with `TelegramConflictException`, and `Telegram.deleteWebhook()` switches the bot back to polling.

## Sending messages

`send` takes a target chat, a `TelegramContent` and optional `TelegramSendOptions`, and answers the `TelegramMessage` Telegram created. A target is a chat id or, for a public channel or group, its `@username`:

```scala
import kyo.*

val remind: TelegramMessage < (Async & Abort[TelegramSendFailure] & Env[Telegram]) =
    Telegram.send(team, TelegramContent.text("Standup in 5 minutes"), TelegramSendOptions(silent = true))

val toChannel = TelegramChat.Target.Username("kyo_announcements")
```

Content is text, a photo, a document, audio, video, a voice note or a location. A file is sent by the id of one Telegram already has, by an `HttpUrl` Telegram fetches, or as an upload of bytes, which the module sends as `multipart/form-data`:

```scala
import kyo.*

def chart(png: Span[Byte]): TelegramContent =
    TelegramContent.Photo(
        TelegramInputFile.Upload("burndown.png", png, Present("image/png")),
        caption = Present(TelegramText("This sprint's burndown"))
    )
```

`Telegram.sendChatAction(team, TelegramChatAction.Typing)` shows "typing..." while the bot prepares a slow answer, `Telegram.setReaction` reacts to a message, and `Telegram.delete` removes one.

## Formatting

Telegram formats text written in MarkdownV2 or HTML, and each has characters that must be escaped wherever they appear. `TelegramMarkup` is a tree of styled parts that the module renders into either, escaping every part as its position requires, so user-supplied text cannot break the formatting:

```scala
import kyo.*
import kyo.TelegramMarkup.*

def question(name: String): TelegramText =
    TelegramText.MarkdownV2(of(
        Bold(Text("Standup")),
        Text(s" for $name (2024-05-01): what's your status?")
    ))
```

The styles are `Bold`, `Italic`, `Underline`, `Strikethrough`, `Spoiler`, `Code`, `Pre` (a code block, with a language), `Link`, `Mention` (a link to a user by id) and `Blockquote`. `TelegramText.Html(markup)` renders the same tree as HTML. `TelegramText.Plain(text, entities)` sends text with the formatting given as entities by offset, the form Telegram itself uses on received messages.

## Keyboards and callback queries

A message can carry a keyboard. An inline keyboard sits under the message; pressing a callback button sends the bot a `CallbackQuery` update with the button's data. The data is at most 64 bytes, counted in UTF-8, and a longer value panics with `TelegramInvalidCallbackDataException` when the button is built. A URL button takes a `TelegramUrl`, which accepts `http`, `https` and Telegram's own `tg://` links:

```scala doctest:scope=env:standup
import kyo.*

val answers: TelegramKeyboard = TelegramKeyboard.inline(
    Seq(
        TelegramKeyboard.InlineButton.callback("Done", "standup:done"),
        TelegramKeyboard.InlineButton.callback("Blocked", "standup:blocked")
    ),
    Seq(TelegramKeyboard.InlineButton.Url("Board", TelegramUrl("https://example.com/board")))
)
```

A reply keyboard (`TelegramKeyboard.reply`) replaces the user's keyboard with buttons that send their text; `Remove` takes it away, and `ForceReply` opens a reply to the bot's message.

A callback query must be answered, or the user's client shows a spinner on the button until it times out. `answerCallback` answers it, optionally with a notification of up to 200 characters or an alert. The query's `message` is the message the button was under, from which `chat` and the message id come for an edit:

```scala doctest:scope=env:standup
import kyo.*

def onAnswer(query: TelegramCallbackQuery): Unit < (Async & Abort[TelegramAnswerCallbackFailure | TelegramEditFailure] & Env[Telegram]) =
    val answer = query.data.fold("")(_.stripPrefix("standup:"))
    recordAnswer(query.from, answer).andThen {
        Telegram.answerCallback(query.id, TelegramCallbackAnswer(text = Present(s"Recorded: $answer"))).andThen {
            query.message match
                case Present(TelegramCallbackQuery.Source.Accessible(message)) =>
                    tally.map(t => Telegram.edit(message.chat.target, message.id, TelegramEdit.Text(TelegramText(s"Standup: $t"))).unit)
                case Present(TelegramCallbackQuery.Source.Inaccessible(_, _)) | Absent => Kyo.unit
        }
    }
```

`TelegramEdit` changes a message's text, a media message's caption, or only its inline keyboard. An edit that changes nothing fails with `TelegramMessageNotModifiedException`, which a bot that re-renders on every click usually ignores.

## Files

A received file is known by its `FileId`. `getFile` looks up its download path, and `download` fetches its bytes:

```scala
import kyo.*

def fetch(file: TelegramId.FileId): Span[Byte] < (Async & Abort[TelegramGetFileFailure | TelegramDownloadFailure] & Env[Telegram]) =
    Telegram.getFile(file).map(Telegram.download)
```

The Bot API serves files of at most 20 MB this way, and a path stays valid for at least an hour. A file with no path fails `download` with `TelegramNoFilePathException`. The path Telegram sends is appended after the token, so `download` accepts only relative segments of letters, digits, `.`, `_` and `-`, and fails with `TelegramRefusedUrlException` otherwise, sending nothing.

## Webhooks

A bot with a public HTTPS URL can have Telegram POST each update to it instead of polling. A `TelegramWebhookConfig` holds the secret Telegram sends in every request's `X-Telegram-Bot-Api-Secret-Token` header and the path the handler is mounted at. `Telegram.setWebhook` registers the public URL with that secret, and `TelegramWebhook.handler` is a kyo-http handler that checks the secret, decodes the update and runs your callback with a client built from the config, so the callback calls the verbs directly:

```scala doctest:scope=env:standup
import kyo.*

val webhook = TelegramWebhookConfig(TelegramSecretToken("standup-webhook-secret"), "telegram")

val server: HttpServer < (Async & Scope & Abort[HttpBindException]) =
    TelegramWebhook.handler[TelegramSendFailure](config, webhook) {
        case TelegramUpdate.Message(_, message) if isStandupCommand(message) =>
            Telegram.send(team, TelegramContent.text("Standup time")).unit
        case _ => Kyo.unit
    }.map(handler => HttpServer.init(8443, "0.0.0.0")(handler))

val register: Unit < (Async & Abort[TelegramSetWebhookFailure | HttpException] & Env[Telegram]) =
    Abort.get(HttpUrl.parse("https://bot.example.com/telegram")).map(url => Telegram.setWebhook(url, webhook))
```

What the handler answers decides what Telegram does next:

| Request | Answer | What Telegram does |
|---|---|---|
| no secret header, or a wrong one | 403, nothing decoded | nothing, when the request was not Telegram's; a 403 to Telegram's own request, after a secret changed, is retried like any failed delivery |
| a body that is not an update | 200, a warning logged | nothing: it would never decode |
| the callback succeeds | 200 | nothing |
| the callback fails or panics | 500 | delivers the update again, a limited number of times |

The secret is compared in constant time. `TelegramWebhook.verify` and `TelegramWebhook.decode` are the two steps on their own, for a bot that serves the endpoint without kyo-http's routing. `setWebhook` refuses a URL that is not absolute `http` or `https` on a host with `TelegramRefusedUrlException`, sending nothing. Building the handler opens its client once, in the enclosing `Scope`, the one the server runs in; every delivery's callback runs on that client, and it closes with the `Scope`.

> **Caution:** Telegram keeps up to 40 deliveries open at once (`TelegramWebhookOptions.maxConnections`, 1 to 100), so callbacks run concurrently, unlike `run`'s handler. A callback that fails on purpose so Telegram redelivers also makes kyo-http log `unhandled handler error` at ERROR.

## Errors

Every verb's failure type names what that verb can meet, and nothing else. `send` fails with `TelegramSendFailure`, which includes `TelegramChatNotFoundException` and `TelegramForbiddenException` (the user blocked the bot) but not `TelegramMessageNotModifiedException`, which only an edit can meet. So a match over a verb's failures is checked against the right set:

```scala
import kyo.*

def announce(text: String): Unit < (Async & Abort[TelegramSendFailure] & Env[Telegram]) =
    Abort.run[TelegramSendFailure](Telegram.send(team, TelegramContent.text(text))).map {
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

Values Telegram would refuse panic when they are built, never on a verb's row: a malformed token, config, URL, callback data, command, method name, webhook option, webhook path or callback answer.

## Methods the module does not model

`custom` calls any Bot API method with parameters and a result of your own types, each with a `Schema`. The failures are those of the request path, as `TelegramCustomFailure`. The method is a `TelegramMethod`, which accepts ASCII letters, digits, `.` and `_`, and not a name of only dots: the name goes into the request path right after the token, and a `/` or `?` in it would send the token somewhere else.

```scala
import kyo.*

case class ChatParam(chat_id: Long) derives Schema

val members: Int < (Async & Abort[TelegramCustomFailure] & Env[Telegram]) =
    Telegram.custom[ChatParam, Int](TelegramMethod("getChatMemberCount"), ChatParam(-1001234567890L))
```

The types Telegram sends (updates, messages, chats, users and the rest) have no `Schema` on purpose: the module alone reads Telegram's JSON, so a type such as `TelegramMessage` cannot be encoded with a shape Telegram did not define.

## Putting it together

The standup bot: `/standup` posts the question with the two buttons, and each answer is recorded, acknowledged and shown in the edited question.

```scala doctest:scope=env:standup
import kyo.*

val standupBot =
    Telegram.let(config)(Telegram.setCommands(Seq(TelegramCommand("standup", "Start today's standup")))).andThen {
        Telegram.run(config) {
            case TelegramUpdate.Message(_, message) if isStandupCommand(message) =>
                Telegram.send(
                    message.chat.target,
                    TelegramContent.Text(TelegramText("Standup: what's your status?")),
                    TelegramSendOptions(keyboard = Present(answers))
                ).unit
            case TelegramUpdate.CallbackQuery(_, query) => onAnswer(query)
            case _                                      => Kyo.unit
        }
    }
```
