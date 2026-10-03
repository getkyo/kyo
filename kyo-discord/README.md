<!-- doctest:setup
```scala
import kyo.*

// The running domain: an on-call bot in a team's server. /incident opens an incident in the
// incidents channel with an Acknowledge button, starts a thread for it, and edits the message
// when someone acknowledges.
val token: Discord.Token = Discord.Token.init("MTA0OTI3NjU0MzIxMDk4NzY1.oncall.bot-token-secret").getOrThrow

val config: DiscordConfig = DiscordConfig.init(token, Discord.Intents.Guilds.union(Discord.Intents.GuildMessages)).getOrThrow

val incidents = Discord.ChannelId(1100000000000000001L)
val team      = Discord.GuildId(1000000000000000001L)

// A stand-in for the bot's own storage, kept out of the visible examples.
def nextIncident: Int < Sync = 42
```
-->

# kyo-discord

`kyo-discord` is a client for [Discord's bot API](https://discord.com/developers/docs/intro): the REST API, the Gateway's event stream, and the interactions endpoint. The verbs a bot sends (`Discord.send`, `Discord.edit`, `Discord.react`, and the rest) are functions on the `Discord` object whose rows require the client, `Env[Discord]`, which `Discord.run` provides. `Discord.receive(handler)` connects the provided client to the Gateway and hands each event to your handler, reconnecting and resuming when Discord drops the connection. When the bot has a public HTTPS endpoint, `Discord.Webhook.handler` receives interactions on a kyo-http server instead of the Gateway.

Every verb fails with its own sealed trait, such as `DiscordSendFailure`, whose leaves are exactly the failures that verb can meet. The bot token travels in every request's headers and an interaction's token in the path of its routes, and no failure of the module holds either. Every value Discord bounds is built by an `init` that returns a `Result` naming what is wrong. The module is cross-platform (JVM, Scala.js, Scala Native and WebAssembly) from one shared source set.

A bot that answers `/ping`:

```scala
import Discord.*
import kyo.*

val ping: Unit < (Async & Abort[DiscordReceiveFailure | DiscordInvalidMessageException]) =
    Discord.run(config)(Discord.receive[DiscordInvalidMessageException]([A] =>
        (event: Event[A]) =>
            event match
                case e: Event.Command if e.data.name == "ping" =>
                    Abort.get(Message.Create.init(content = Present("pong"))).map(InteractionResponse.Message(_))
                case other => Event.unhandled(other)
    ))
```

The handler answers each event with the type its kind admits, the `A` of `Event[A]`: `Unit` for a Gateway dispatch, and for an interaction the responses Discord accepts for that kind. Matching a case refines `A`, so the `Event.Command` branch answers an `InteractionResponse.ToCommand`. `Event.unhandled` answers every event the handler does not name: `Unit` for a dispatch, and for an interaction a decline, described in [Answering interactions](#answering-interactions). The sections below build the on-call bot one piece at a time, and [Putting it together](#putting-it-together) combines them.

Every type the module models is nested in `object Discord`: `Discord.Event`, `Discord.Message`, `Discord.ChannelId`, and so on. `import Discord.*` brings the short names into scope, as the examples do. Three of them, `Channel`, `Command` and `Path`, are also kyo types (`kyo.Channel`, `kyo.Command`, `kyo.Path`), so the examples write those in full as `Discord.Channel`, `Discord.Command` and `Discord.Path`. The configurations and the failures are top-level types.

## What kyo-discord does not do

- No voice, and no Gateway compression or ETF encoding: the Gateway speaks JSON.
- One client holds one Gateway connection. `config.shard` names the shard it serves; a bot in enough guilds to need several runs one client per shard.
- No cache. Events carry what Discord sent; a handler that needs a guild's state fetches it or keeps it.
- No deduplication. A dispatch has no id of its own; after a reconnect that could not resume, a new `Event.Ready` marks the gap, and the message, interaction and channel ids the cases carry are how a handler recognizes a repeat.
- No retry unless `config.retry` is set (see [Rate limits and retries](#rate-limits-and-retries)).
- Incoming webhooks (Discord's "Execute Webhook") and the routes the verbs do not cover are reached through `custom`.

## Configuration and the client

A `DiscordConfig` carries the bot's token, from the [developer portal](https://discord.com/developers/applications), and the [intents](https://discord.com/developers/docs/events/gateway#gateway-intents) that choose which events the Gateway sends. Everything else has a default:

```scala
import Discord.*
import kyo.*

val tuned: Result[DiscordInvalidConfigException, DiscordConfig] =
    DiscordConfig.init(
        token,
        Intents.Guilds.union(Intents.GuildMessages),
        interactionDeadline = 2.seconds,
        retry = Present(Schedule.exponentialBackoff(1.second, 2.0, 30.seconds))
    )
```

`Discord.Token.init` accepts 1 to 256 printable ASCII characters other than space, what a header and a JSON string can carry. The token's `toString` is `Discord.Token(<redacted>)`, so a log line cannot show it by accident; its `Schema` writes the token itself, since encoding one is a deliberate act. `DiscordConfig.init` refuses the first setting Discord or kyo-http cannot use with `DiscordInvalidConfigException`: an `interactionDeadline` that is not under Discord's 3 seconds, a non-positive timeout or rate, a `baseUrl` that is not an absolute `http` or `https` URL.

Three intents are privileged: `GuildMembers`, `GuildPresences` and `MessageContent` must be enabled for the application in the developer portal, and the Gateway refuses a connection that asks for one that is not, with `DiscordDisallowedIntentsException`.

`requestTimeout` (10 seconds) bounds every REST call; `transferTimeout` (120 seconds) bounds instead a send with files, which may upload the 25 MiB a message allows. `connectTimeout` bounds opening a connection, and `maxResponseLength` (8 MiB) an answer's body. `maxResponseLength` is never refused: kyo-http holds the bound as an `Int`, so a zero bound becomes one byte and one past `Int.MaxValue` becomes `Int.MaxValue`. `tls` and `transport` are the settings of the module's own HTTP client and of the Gateway connection.

`Discord.run(config)` builds a client for a region and closes it when the region ends; it holds no Gateway connection. A verb's row names the client, so a call outside `run` does not compile where it is run:

```scala
import Discord.*
import kyo.*

val incidentsChannel: Discord.Channel < (Async & Abort[DiscordChannelFailure]) =
    Discord.run(config)(Discord.channel(incidents))
```

A client is also a value, for a lifetime longer than one region. `Discord.init(config)` builds one holding a Gateway session, returned once Discord sent `READY` and closed when the enclosing `Scope` ends; `Discord.initUnscoped(config)` builds one that only `Discord.close(client)` closes. `Discord.run(client)(v)` provides a built client to `v` without closing it, and the two `run`s are the only methods that provide `Env[Discord]`:

```scala
import Discord.*
import kyo.*

val twice: Discord.Channel < (Async & Abort[DiscordInitFailure | DiscordChannelFailure] & Scope) =
    Discord.init(config).map { client =>
        Discord.run(client)(Discord.channel(incidents)).andThen(Discord.run(client)(Discord.channel(incidents)))
    }
```

The client's HTTP client is its own. Nothing of your kyo-http configuration reaches a request that carries the token: no filter you installed runs on it, your TLS and transport settings do not apply (the config's do), no redirect is followed, and a connection you opened to the same server is not reused.

## Receiving events

`Discord.receive(handler)` handles the provided client's Gateway events until the handler fails, a failure ends the session, or it is interrupted. Under `Discord.run(config)` it connects, identifies with the config's intents, and closes the connection with 1000 when it ends: `Discord.run(config)(Discord.receive(handler))` is a bot in one call. On a client from `Discord.init(config)`, which already holds a session, it handles that session's events, starting with `Ready`, and the client calls verbs in between.

The dispatches are `Ready`, `Resumed`, `MessageCreated`, `MessageUpdated`, `MessageDeleted`, `ReactionAdded`, `ReactionRemoved`, `ThreadCreated`, `ThreadUpdated`, `ThreadDeleted`, `ChannelCreated`, `ChannelUpdated`, `ChannelDeleted`, `GuildCreated`, `GuildDeleted`, `MemberJoined`, `MemberLeft` and `TypingStarted`. A kind the model does not declare is `Event.Unknown`, with its name and JSON, so nothing Discord sent is dropped. Interactions are `Command`, `Component`, `Autocomplete`, `ModalSubmit`, and `UnknownInteraction` for a kind the model does not declare.

How the handler runs:

- Each event is handled on its own fiber, so a slow handler never delays the heartbeat or the next event. Order between handlers is not kept; a handler that needs order serializes by channel itself.
- The handler's own effects pass through `receive`'s row. Each handler runs with the state those effects had when `receive` started, and what it does to that state is dropped, since concurrent handlers have no order to join their changes in. That is the `Isolate` in the signature.
- A typed failure of the handler ends `receive` with it and interrupts the other handlers. A panic is logged with the event's type and sequence, never its payload, and the loop goes on: Discord does not redeliver a dispatch, so going on cannot repeat it.

When Discord closes the connection, `DiscordConfig.Reconnect.Resume(backoff)`, the default, reconnects and resumes the session, and Discord replays the events the client missed. The first reconnection after a healthy connection is immediate and each further one waits by `backoff` (from 1 second, doubling to 60). A session Discord will not resume is identified afresh, and the handler sees a new `Ready`. `Reconnect.Off` ends `receive` cleanly instead. A close no reconnection can fix, such as a revoked token (`DiscordAuthenticationFailedException`) or refused intents, ends `receive` with its failure under either policy. Identifies are spaced as Discord's session start limit requires, and a spent daily budget fails with `DiscordSessionStartLimitException` rather than waiting a day.

> **Note:** a bot that receives messages needs `Intents.GuildMessages`, and the content of other users' messages also needs the privileged `MessageContent`. Without it, `message.content` is empty for messages that do not mention the bot.

## Answering interactions

A slash command, a button press, a select, an autocomplete or a modal submission is an interaction, and Discord takes one first answer within 3 seconds. The handler's return value is that answer, which the module posts. Each kind admits its own answers:

| Event | Answer type | Admits |
|---|---|---|
| `Event.Command` | `InteractionResponse.ToCommand` | `Message`, `DeferredMessage`, `Modal` |
| `Event.Component` | `InteractionResponse.ToComponent` | `Message`, `DeferredMessage`, `DeferredUpdate`, `UpdateMessage`, `Modal` |
| `Event.Autocomplete` | `InteractionResponse.ToAutocomplete` | `Autocomplete` |
| `Event.ModalSubmit` | `InteractionResponse.ToModalSubmit` | `Message`, `DeferredMessage`, `DeferredUpdate`, `UpdateMessage` |

The handler is bounded by `config.interactionDeadline`, 2.5 seconds from the event's arrival by default, which leaves half a second for the answer to reach Discord. Past it the handler is interrupted, nothing is posted, and one warn record names the interaction. Work that takes longer answers `DeferredMessage` at once, which shows "thinking", forks the work, and finishes with `Discord.editResponse` or `Discord.followUp` on the interaction's `ref`.

`/incident` opens an incident with an Acknowledge button:

```scala doctest:scope=env:oncall
import Discord.*
import kyo.*

def openIncident(command: Event.Command)
    : InteractionResponse.ToCommand < (Sync & Abort[DiscordInvalidMessageException | DiscordInvalidComponentException]) =
    for
        n   <- nextIncident
        ack <- Abort.get(Component.Button.init(
            Component.Button.Style.Danger,
            label = Present("Acknowledge"),
            customId = Present(s"ack:$n")
        ))
        row <- Abort.get(Component.ActionRow.init(Chunk(ack)))
        msg <- Abort.get(Message.Create.init(content = Present(s"Incident $n opened"), components = Chunk(row)))
    yield InteractionResponse.Message(msg)
```

Pressing the button is an `Event.Component` with the button's `customId`, answered by editing the message it is on:

```scala doctest:scope=env:oncall
import Discord.*
import kyo.*

def acknowledge(press: Event.Component): InteractionResponse.ToComponent < Abort[DiscordInvalidMessageException] =
    val who = press.interaction.invoker.fold("someone")(_.username)
    Abort.get(Message.Edit.init(content = Present(Patch.Set(s"Acknowledged by $who")), components = Present(Chunk.empty)))
        .map(InteractionResponse.UpdateMessage(_))
end acknowledge
```

An interaction the handler does not answer is declined: `Event.unhandled` does it for every interaction the handler does not name, and a handler declines one on purpose with `Abort.fail(Event.Decline)`. A declined interaction gets no answer, so Discord shows it as failed once its 3 seconds pass, the quickest failure the user can see; one warn record names the interaction and its kind. A deferred answer would instead show "thinking" for up to 15 minutes. `Event.Decline` is on the handler's row, not a failure of `receive`.

## Sending messages

`send` posts a `Message.Create` to a channel and answers the `Message` Discord created. `Message.Create.init` checks what Discord would refuse (at most 2000 characters of content, 10 embeds, 5 rows of components, 10 files) and returns a `Result` naming the first limit broken:

```scala doctest:scope=env:oncall
import Discord.*
import kyo.*

def announce(n: Int): Message < (Async & Abort[DiscordSendFailure | DiscordInvalidMessageException] & Env[Discord]) =
    Abort.get(Message.Create.init(content = Present(s"Incident $n: investigating"))).map(Discord.send(incidents, _))
```

A message with files is sent as `multipart/form-data`, each `Discord.File` built by `File.init(name, bytes)`, and is bounded by `transferTimeout`. `edit` takes a `Message.Edit`, whose fields are `Patch.Set`, `Patch.Clear` or absent for unchanged; `delete` removes a message; `message` and `messages` read one or a page (`Message.Page.init(anchor, limit)`, at most 100). `typing` shows "typing" in a channel for a few seconds.

`react` and `unreact` take a `Reaction`, built by `Reaction.unicode("👀")` or `Reaction.custom(id, name)`; both return a `Result`, since an emoji with no name would name a different route.

`startThread` opens a thread on a message, or in a channel from no message, and answers it as a `Channel`:

```scala doctest:scope=env:oncall
import Discord.*
import kyo.*

def threadFor(
    message: Message,
    n: Int
): Discord.Channel < (Async & Abort[DiscordStartThreadFailure | DiscordInvalidThreadException] & Env[Discord]) =
    Abort.get(Thread.Start.init(s"incident-$n")).map(Discord.startThread(incidents, message.id, _))
```

`openDm` opens a direct message channel with a user, `member` reads a guild member, and `channel` reads a channel.

## Commands

A slash command exists once it is registered. `registerCommands(commands)` replaces the application's global commands, and `registerCommands(guild, commands)` replaces one guild's, which take effect at once and suit development. Each answers the commands as registered; registering an empty set removes them all.

```scala doctest:scope=env:oncall
import Discord.*
import kyo.*

val register: Chunk[Discord.Command] < (Async & Abort[DiscordRegisterCommandsFailure | DiscordInvalidCommandException] & Env[Discord]) =
    Abort.get(Discord.Command.Create.init("incident", "Open an incident")).map(c => Discord.registerCommands(team, Chunk(c)))
```

`Discord.Command.Create.init` checks Discord's naming rules (1 to 32 lowercase letters, digits, `-`, `_` and `'`), a description of 1 to 100 characters, at most 25 options, and that required options come first.

## The interactions endpoint

A bot with a public HTTPS URL can have Discord POST each interaction to it instead of the Gateway. `DiscordWebhookConfig` holds the application's public key, from the developer portal, and the path the handler is mounted at. `Discord.Webhook.handler` is a kyo-http handler that verifies each request's signature, decodes the interaction and answers it with your handler's return value:

```scala doctest:scope=env:oncall
import Discord.*
import kyo.*

val endpoint: Result[DiscordException, DiscordWebhookConfig] =
    Discord.PublicKey.init("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        .flatMap(key => DiscordWebhookConfig.init(key, "interactions"))

def serve(webhook: DiscordWebhookConfig): Unit < (Async & Abort[HttpBindException]) =
    Discord.run(config) {
        Discord.Webhook.handler[DiscordInvalidMessageException | DiscordInvalidComponentException](webhook)([A] =>
            (event: Event[A]) =>
                event match
                    case e: Event.Command if e.data.name == "incident" => openIncident(e)
                    case e: Event.Component                            => acknowledge(e)
                    case other                                         => Event.unhandled(other)
        ).map(handler => Scope.run(HttpServer.init(8443, "0.0.0.0")(handler).map(_.await)))
    }
```

The handler requires the client, `Env[Discord]`, and every interaction runs with it. The interactions arrive after `handler` returns, on the server's fibers, so the server is awaited inside the client's `Discord.run` region: a handler taken out of the region holds a closed client, and a callback it posts fails.

`Discord.PublicKey.init` refuses hex that is not a usable Ed25519 key, including the small-order keys that would let a forged signature verify. What the handler answers is Discord's contract:

| Request | Answer |
|---|---|
| a signature header missing, malformed or not verifying | 401, nothing decoded or handled |
| a verified body that is not an interaction | 400 |
| `PING`, Discord checking the endpoint | 200 with `{"type":1}`, the handler not called |
| an answer | 200 with the answer as JSON |
| an answer with files | posted to the callback route, then 202 with no body |
| a declined interaction, a failure, a panic, the deadline passed | 500 |

The checks run cheapest first: the headers, the timestamp's shape, the signature's shape, and only then the Ed25519 verification over the timestamp and the body. Discord sends invalid signatures on purpose and removes an endpoint that accepts one. `Discord.Webhook.verify` and `Discord.Webhook.decode` are the two steps on their own, for a bot that serves the endpoint without kyo-http's routing.

> **Caution:** the handler relies on the server's `maxContentLength` (64 KiB by default) to refuse a large body with 413 before it is verified. Discord documents no freshness window for the signed timestamp, so a recorded request verifies again if replayed; a handler whose effect must not repeat deduplicates by the interaction's id.

## Rate limits and retries

The module follows Discord's [rate limits](https://discord.com/developers/docs/topics/rate-limits) from the headers of every answer: each route's bucket, per channel, guild or webhook, and the calls it has left. A call takes one of the remaining calls before it is sent, so concurrent calls do not overrun a bucket. With none left it waits for the bucket's reset when that fits within the call's timeout, and otherwise fails with `DiscordRouteRateLimitException` naming the wait and the bucket, sending nothing. The client also holds the global limit, `config.globalRateLimit` (50 a second, Discord's default for every bot), which the routes holding an interaction's token are exempt from.

`config.retry` is `Absent` by default, and then nothing is retried. Set to a `Schedule`, it retries what Discord documents as retryable: a 429 after the wait Discord named, and a 502. The wait is the longer of the schedule's step and Discord's, and a 429 naming a wait past `config.retryMaxDelay` (60 seconds) is not retried; its leaf carries the wait. The interaction callback and `GET /gateway/bot` are never retried.

> **Caution:** a retried `send` can be delivered twice, when the first attempt reached Discord and its answer did not.

## Errors

Every verb's failure type names what that verb can meet, and nothing else. `send` fails with `DiscordSendFailure`, which includes `DiscordMissingPermissionsException` and `DiscordUnknownChannelException`, but not `DiscordUnknownEmojiException`, which only a reaction can meet:

```scala doctest:scope=env:oncall
import Discord.*
import kyo.*

def page(message: Message.Create): Unit < (Async & Abort[DiscordSendFailure] & Env[Discord]) =
    Abort.run[DiscordSendFailure](Discord.send(incidents, message)).map {
        case Result.Success(_)                                     => Kyo.unit
        case Result.Failure(e: DiscordMissingPermissionsException) => Log.warn(s"cannot post incidents: ${e.description}")
        case Result.Failure(e: DiscordRouteRateLimitException) => Async.sleep(e.retryAfter.getOrElse(1.second)).andThen(page(message))
        case Result.Failure(e)                                 => Abort.fail(e)
        case Result.Panic(e)                                   => Abort.panic(e)
    }
```

The leaves fall into a few groups:

- **Discord's answers.** `DiscordUnauthorizedException`, `DiscordMissingPermissionsException`, `DiscordMissingAccessException`, the `DiscordUnknown*Exception` leaves (channel, message, emoji, user, member, guild), `DiscordCannotMessageUserException`, `DiscordBlockedByModerationException`, `DiscordMaximumReachedException`, `DiscordThreadAlreadyExistsException`, `DiscordThreadClosedException`, `DiscordPayloadTooLargeException` and `DiscordInteractionExpiredException` each carry the route and Discord's description, with the tokens replaced. `DiscordInvalidFormBodyException` is a body Discord refused, with each refused field and its reason in `fieldErrors`. Any other error is `DiscordOtherApiException`, with its status and Discord's code.
- **Rate limits.** `DiscordRouteRateLimitException`, `DiscordSharedRateLimitException` and `DiscordGlobalRateLimitException` carry the wait Discord asked for; `DiscordBlockedException` is a 429 from Discord's edge, which blocks a client that keeps exceeding the limits.
- **The transport.** `DiscordTransportException` carries the route, a `kind` (`Connect`, `Dns`, `Tls`, `ConnectTimeout`, `Timeout`, `Protocol`, `ConnectionClosed`, `WebSocketHandshake`, `PoolExhausted`, `PayloadTooLarge`), the host and port, and the timeout that ran out. It never holds the request URL. `DiscordRefusedUrlException` is a Gateway URL from Discord that the module refused to open: anything but `wss` on a host.
- **The response.** `DiscordUnexpectedStatusException` for a status that is not Discord's answer, such as a proxy's 502 or a redirect, which the module never follows; `DiscordDecodeException` for a body or frame that does not decode, with which kind of failure, the path and the position, but not the body.
- **The Gateway.** `DiscordAuthenticationFailedException`, `DiscordInvalidShardException`, `DiscordShardingRequiredException`, `DiscordInvalidIntentsException` and `DiscordDisallowedIntentsException` are the close codes no reconnection fixes; `DiscordGatewayClosedException` is any other close after which no reconnection follows; `DiscordSessionStartLimitException` is a spent identify budget.
- **The interactions endpoint.** `DiscordWebhookMissingHeaderException`, `DiscordWebhookMalformedHeaderException` and `DiscordWebhookSignatureMismatchException` from `verify`, `DiscordWebhookDecodeException` from `decode`.

Values Discord would refuse are the failure of their `init`, never on a verb's row: a token, a config, an id from text, a path, a message, a component, a command, a reaction, a thread, an interaction response, a public key or a webhook config.

## Routes the module does not model

`custom` calls any route under the API base with a body and an answer of your own types, each with a `Schema`. Its failures are those every route shares, `DiscordCustomFailure`, and any Discord error code is a `DiscordOtherApiException` carrying it. The route is a `Discord.Path`, which refuses what would change the URL it is placed in:

```scala doctest:scope=env:oncall
import Discord.*
import kyo.*

case class Pins(items: Chunk[Structure.Value]) derives Schema

def pins: Pins < (Async & Abort[DiscordCustomFailure | DiscordInvalidPathException] & Env[Discord]) =
    Abort.get(Discord.Path.init(s"channels/${incidents.value}/messages/pins")).map(Discord.custom[Unit, Pins](HttpMethod.GET, _))
```

## Putting it together

The on-call bot: it registers `/incident`, then opens each incident with the button, starts a thread on the bot's own incident message when the Gateway delivers it, and edits the message when someone acknowledges.

```scala doctest:scope=env:oncall
import Discord.*
import kyo.*

type Invalid = DiscordInvalidMessageException | DiscordInvalidComponentException | DiscordInvalidThreadException

val oncall: Unit < (Async &
    Abort[DiscordReceiveFailure | DiscordRegisterCommandsFailure | DiscordInvalidCommandException | DiscordStartThreadFailure |
        Invalid]) =
    Discord.run(config) {
        register.andThen {
            Discord.receive[DiscordStartThreadFailure | Invalid]([A] =>
                (event: Event[A]) =>
                    event match
                        case e: Event.Command if e.data.name == "incident" => openIncident(e)
                        case e: Event.Component                            => acknowledge(e)
                        case e: Event.MessageCreated if e.message.author.bot && e.message.content.startsWith("Incident ") =>
                            val n = e.message.content.stripPrefix("Incident ").takeWhile(_.isDigit)
                            threadFor(e.message, n.toIntOption.getOrElse(0)).unit
                        case other => Event.unhandled(other)
            )
        }
    }
```

## Testing against Discord

`DiscordLiveTest` runs the module against the real API and Gateway. Every leaf needs `DISCORD_BOT_TOKEN` and is cancelled without it. The leaves that send need `DISCORD_TEST_CHANNEL_ID`, a text channel where the bot can view, send, react, read history, and create and manage threads; the command leaf needs `DISCORD_TEST_GUILD_ID`, a guild the bot joined with the `applications.commands` scope. The suite deletes what it creates.

```sh
DISCORD_BOT_TOKEN=... DISCORD_TEST_CHANNEL_ID=... DISCORD_TEST_GUILD_ID=... sbt 'kyo-discordJVM/testOnly kyo.DiscordLiveTest'
```
