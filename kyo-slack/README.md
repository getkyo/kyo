<!-- doctest:setup
```scala
import kyo.*

// The running domain: a deploy-bot that lives in #deploys, answers /deploy,
// replies to mentions, and opens a rollback confirmation modal. One config and
// a few concrete ids recur across every example below.
val config = SlackConfig(
    appLevel = SlackToken.AppLevel("xapp-1-..."),
    bot = SlackToken.Bot("xoxb-...")
)

val deploysChannel = SlackId.ChannelId("C-deploys")

// A short placeholder for raw Block Kit JSON. Block Kit is intentionally untyped,
// so a real block array would be noise in these examples.
val rollbackBlocks = "[]"

// Stand-ins for the bot's own work, kept out of the visible examples.
def currentDeployStatus: String < Sync     = "staging is green"
def runDeploy(target: String): Unit < Sync = ()
def rollbackLastDeploy: Unit < Sync        = ()
```
-->

# kyo-slack

`kyo-slack` is a Slack [Socket Mode](https://api.slack.com/apis/socket-mode) client. A Slack app written with it is a single `Slack.connect(config)(handler)` call: you supply a `SlackConfig` carrying an app-level token (it opens the WebSocket) and a bot token (it authenticates the Web API), and a `handler: SlackEnvelope => SlackAck`. Slack streams typed inbound frames into the handler, you pattern-match the one you care about, do your work, and return a `SlackAck`. Returning the value is the acknowledgement: the framework reads the returned `SlackAck` and emits exactly one wire ack for that frame. There is no ack method to call, so you cannot forget to ack and you cannot double-ack.

Web API calls inside the handler (`Slack.chatPostMessage`, `Slack.viewsOpen`, and the rest) take no token argument. The bot token from `config` is bound ambiently around the handler body, so it resolves automatically. The runtime concerns are handled for you: opening the socket, keepalive, reconnecting when Slack rotates the connection, and tearing everything down on exit. `connect` is `Scope`-managed, so the socket and its background fibers close on scope exit or interrupt.

Every operation runs in `< (Async & Abort[SlackException])`, plus `Scope` for `connect`. The module is cross-platform: JVM, Scala.js, and Scala Native, from a single shared source set.

Reply to a mention in one handler:

```scala
import kyo.*

val app: Unit < (Async & Abort[SlackException] & Scope) =
    Slack.connect(config) {
        case SlackEnvelope.EventsApi(_, SlackEvent.AppMention(channel, _, _, _)) =>
            Slack.chatPostMessage(SlackMessage(channel, "Deploy status: staging is green"))
                .andThen(SlackAck.Ack)
        case _ => SlackAck.Ack
    }
```

The sections below build up each piece in the order you meet it. A handler that combines mentions, a slash command, and a rollback modal appears in [Putting it together](#putting-it-together) near the end.

## Connect and reply in a handler

The first thing to write is the connect call, so start there. A `SlackConfig` needs two tokens: the `xapp-` app-level token that opens the socket and the `xoxb-` bot token that signs Web API calls. The handler receives one `SlackEnvelope` and returns one `SlackAck`. Because `connect` is `Scope`-managed, you run it inside a `Scope` (here `Scope.run`); the socket opens, the receive loop runs under the reconnect policy, and everything closes when the scope ends.

```scala
import kyo.*

val deployBot: Unit < (Async & Abort[SlackException]) =
    Scope.run {
        Slack.connect(SlackConfig(SlackToken.AppLevel("xapp-1-..."), SlackToken.Bot("xoxb-..."))) {
            case SlackEnvelope.EventsApi(_, SlackEvent.AppMention(channel, _, _, _)) =>
                Slack.chatPostMessage(SlackMessage(channel, "staging is green"))
                    .andThen(SlackAck.Ack)
            case _ => SlackAck.Ack
        }
    }
```

Three things are happening at once. The match selects the one frame this branch handles (an app mention) and ignores the rest with a catch-all that returns the bare `SlackAck.Ack`. The `Slack.chatPostMessage` call needs no token: the bot token from the config is in scope for the duration of the handler body. And `.andThen(SlackAck.Ack)` sequences the post and then returns the ack value that the framework emits for this frame.

> **Note:** the `connect` signature carries an `Isolate` using-clause that captures the handler's effect environment. It is inferred at the call site, so you write `Slack.connect(config) { ... }` and never name it. The examples here all use that form.

## Acking is the return value

Acking is not an action you perform; it is the value your handler hands back. This is the central rule of the module, so it is worth stating on its own. The handler's return type is `SlackAck`, the framework emits exactly one wire ack per ackable envelope from whatever you return, and there is no public `ack` or `sendAck` method anywhere on `Slack` or `SlackConnection`. A handler that returns something other than a `SlackAck` does not compile, and there is no channel you could call twice, so forgetting and double-acking are both unrepresentable.

`SlackAck` has four shapes. `Ack` is the bare acknowledgement, the common return. The other three carry a payload that rides the acknowledgement:

```scala
import kyo.*

val bare: SlackAck    = SlackAck.Ack
val command: SlackAck = SlackAck.CommandResponse(SlackMessage(deploysChannel, "Deploying..."))
val view: SlackAck    = SlackAck.ViewResponse(SlackAck.ViewAction.Clear)
```

> **Caution:** the handler runs under `config.ackDeadline` (default `3.seconds`). If it has not returned a `SlackAck` within that window, the framework emits the bare `SlackAck.Ack` and cancels the still-running handler, so a late payload ack never goes out. Exactly one ack is emitted per ackable envelope, always. Long-running work therefore belongs in a forked fiber or a delayed `response_url` POST, not inline in the handler body.

When a handler aborts instead of returning, the envelope is left unacked. Slack then re-delivers it, this time with `retryAttempt` and `retryReason` set on the `Meta`, so a transient failure gets a second chance rather than silently dropping the work.

```scala
import kyo.*

val retryAware: SlackEnvelope => SlackAck = {
    case SlackEnvelope.EventsApi(meta, _) if meta.retryAttempt.isDefined =>
        // A re-delivery: meta.retryReason explains why the first attempt did not ack.
        SlackAck.Ack
    case _ => SlackAck.Ack
}
```

Two frames are never acked at all. `Hello` and `Disconnect` carry no `Meta` (no `envelope_id`), so whatever `SlackAck` you return for them is a no-op. You still return one, for uniformity. `Hello` is delivered first and is the clean startup hook:

```scala
import kyo.*

val withStartup: SlackEnvelope => SlackAck < (Async & Abort[SlackException]) = {
    case SlackEnvelope.Hello(numConnections, appId, _) =>
        // Confirm identity once the socket is live. The returned Ack is a no-op for Hello.
        Slack.authTest.map(identity => SlackAck.Ack)
    case _ => SlackAck.Ack
}
```

## The frames you receive

Once you are acking correctly, the next question is what can arrive. The receive loop yields one `SlackEnvelope` at a time, and a total match over its cases is the shape every handler takes. The named cases are `Hello` (connection established), `EventsApi` (an Events API callback), `Interactive` (an interactivity payload), `SlashCommand` (a slash command), and `Disconnect` (Slack rotating or terminating the link). A sixth case, `Unknown`, carries the raw frame for any envelope type the module does not model:

```scala
import kyo.*

val byEnvelope: SlackEnvelope => SlackAck = {
    case SlackEnvelope.EventsApi(meta, event)          => SlackAck.Ack
    case SlackEnvelope.SlashCommand(meta, command)     => SlackAck.Ack
    case SlackEnvelope.Interactive(meta, interaction)  => SlackAck.Ack
    case SlackEnvelope.Hello(_, _, _)                  => SlackAck.Ack
    case SlackEnvelope.Disconnect(_)                   => SlackAck.Ack
    case SlackEnvelope.Unknown(frameType, payloadJson) => SlackAck.Ack
}
```

> **Note:** `Unknown` is the forward-safety case, not an error. An unmodeled or future envelope type decodes to `Unknown` carrying its raw payload string, so no data is lost and no abort is raised. A best-effort decode failure in the receive loop also surfaces as `Unknown`, never as `SlackException`. The only place a decode failure becomes a typed `SlackDecodeException` is `Slack.custom`'s response, covered under [Errors](#errors).

An `EventsApi` frame holds a `SlackEvent`, the Events API event ADT. Its typed cases are `Message`, `AppMention`, `ReactionAdded`, `AppHomeOpened`, and `MemberJoinedChannel`, with the same `Unknown` forward-safety case. A `Message` carries an optional `threadTs`, which is how you tell a top-level message from a threaded reply:

```scala
import kyo.*

val byEvent: SlackEnvelope => SlackAck = {
    case SlackEnvelope.EventsApi(_, SlackEvent.AppMention(channel, user, text, ts)) =>
        SlackAck.Ack
    case SlackEnvelope.EventsApi(_, SlackEvent.Message(channel, user, text, ts, threadTs)) =>
        SlackAck.Ack
    case _ => SlackAck.Ack
}
```

A `SlashCommand` frame holds a `SlackCommand`: the `command` name, the typed `text`, the originating `channel` and `user`, a `triggerId` for opening a modal, and a `responseUrl` for a delayed followup. You match on `command` to route the slash command:

```scala
import kyo.*

val byCommand: SlackEnvelope => SlackAck = {
    case SlackEnvelope.SlashCommand(_, cmd) if cmd.command == "/deploy" =>
        SlackAck.CommandResponse(SlackMessage(cmd.channel, s"Deploying ${cmd.text}..."))
    case _ => SlackAck.Ack
}
```

## Replying with the Web API

When you want the bot to say or change something, you call a Web API method from inside the handler. Every one of these resolves the bot token from the ambient config, so none of them take a token argument; calling one outside a connected handler aborts with `SlackHandshakeException` because there is no token bound. They return typed ids and timestamps, not loose strings.

`Slack.chatPostMessage` posts a message and returns its `SlackTs`. That ts is what you feed back as a `threadTs` to reply in-thread under the message that triggered you:

```scala
import kyo.*

val replyInThread: SlackEnvelope => SlackAck < (Async & Abort[SlackException]) = {
    case SlackEnvelope.EventsApi(_, SlackEvent.Message(channel, _, text, ts, _)) =>
        Slack.chatPostMessage(SlackMessage(channel, s"Saw: $text", threadTs = Present(ts)))
            .andThen(SlackAck.Ack)
    case _ => SlackAck.Ack
}
```

When you need a reply only the triggering user can see, use `chatPostEphemeral`. When you want to edit a message you already posted, use `chatUpdate`, keyed by the `channel` and the `ts` the original post returned:

```scala
import kyo.*

val postThenEdit: SlackTs < (Async & Abort[SlackException]) =
    Slack.chatPostMessage(SlackMessage(deploysChannel, "Deploying staging...")).map { ts =>
        Slack.chatUpdate(deploysChannel, ts, SlackMessage(deploysChannel, "Deploy complete"))
    }

val onlyForUser: SlackTs < (Async & Abort[SlackException]) =
    Slack.chatPostEphemeral(SlackMessage(deploysChannel, "You lack deploy rights"), SlackId.UserId("U-alice"))
```

`Slack.authTest` confirms the bot token and returns a `Slack.Identity` (the bot's `userId`, `teamId`, `botId`, and workspace `url`), which is the typical thing to log on startup.

For the long tail of the Web API that this module does not model directly, `Slack.custom` is the escape hatch. You give it a method name and a request body whose type has a `Schema`, and you ask for a response type that also has a `Schema`:

```scala
import kyo.*

case class ListBody(types: String) derives Schema
case class Conversations(channels: Chunk[String]) derives Schema

val channels: Conversations < (Async & Abort[SlackException]) =
    Slack.custom[ListBody, Conversations]("conversations.list", ListBody("public_channel"))
```

> **Note:** Block Kit blocks on `SlackMessage` and `SlackView` are passed as raw JSON strings (`blocksJson`), not a typed model. Block Kit is large and changes often, so the module treats the block array as opaque JSON rather than mirroring it in types.

## Interactivity: modals and actions

Interactivity arrives as `SlackEnvelope.Interactive`, holding a `SlackInteraction`. This is where the typed ids do real work: a `Shortcut` (or `BlockActions` or `MessageAction`) carries a `triggerId`, and that `triggerId` is exactly what `Slack.viewsOpen` requires to open a modal. The type checker threads the id from the interaction straight into the open call, so you cannot key a modal off the wrong id.

The rollback flow is two interactions. First the shortcut opens a confirmation modal and acks bare:

```scala
import kyo.*

val openRollback: SlackEnvelope => SlackAck < (Async & Abort[SlackException]) = {
    case SlackEnvelope.Interactive(_, SlackInteraction.Shortcut(_, triggerId, "rollback")) =>
        Slack.viewsOpen(triggerId, SlackView(SlackView.Type.Modal, blocksJson = rollbackBlocks))
            .andThen(SlackAck.Ack)
    case _ => SlackAck.Ack
}
```

Then the modal's submission comes back as `ViewSubmission`, and you answer it with a `ViewResponse` carrying a `ViewAction`. The four actions are `Clear` (close the modal stack), `Update` and `Push` (replace or stack a view), and `Errors` (show per-block validation errors keyed by block id):

```scala
import kyo.*

val handleRollback: SlackEnvelope => SlackAck < (Async & Abort[SlackException]) = {
    case SlackEnvelope.Interactive(_, SlackInteraction.ViewSubmission(_, _, _)) =>
        rollbackLastDeploy.andThen(SlackAck.ViewResponse(SlackAck.ViewAction.Clear))
    case _ => SlackAck.Ack
}
```

The other `SlackInteraction` cases are `BlockActions` (a click on a button or other block element, carrying a `Chunk[Action]` of the `actionId`/`blockId`/`value` that fired), `ViewClosed` (the user dismissed a modal), and `MessageAction` (a message-level shortcut). `viewsUpdate` replaces an open view's content by its `ViewId`, and `viewsPublish` publishes a Home tab view for a user.

The acks for these payloads are not uniform, and one of them differs in a way worth calling out.

> **Unlike** `ViewResponse` and `CommandResponse`, which carry their payload inline in the socket acknowledgement, `BlockActionsResponse(message)` emits a bare socket ack plus a separate `response_url` POST. The socket ack itself stays bare; the message you supply is delivered out of band over the response url the engine correlated for that interaction. If you expect a `BlockActionsResponse` to ride the socket ack the way `ViewResponse` does, it will not.

When you are responding to a view submission and the work is itself a view change, use `ViewResponse`. When you are updating the message a button click came from, use `BlockActionsResponse` and let the response-url POST carry it. When you are answering a slash command immediately, use `CommandResponse`.

## Typed ids and tokens

The opaque types in this module exist to make two classes of mistake into compile errors. The first is mixing up identifiers. `SlackId` holds eight opaque types over `String`: `ChannelId`, `UserId`, `TeamId`, `AppId`, `TriggerId`, `EnvelopeId`, `ViewId`, and `Ts` (aliased top-level as `SlackTs`). A `ChannelId` is not assignable where a `TriggerId` or `UserId` is required, so the `triggerId` flowing into `viewsOpen` in the previous section cannot accidentally be a channel:

```scala
import kyo.*

val channel: SlackId.ChannelId = SlackId.ChannelId("C-deploys")
val user: SlackId.UserId       = SlackId.UserId("U-alice")
val raw: String                = channel.value
```

The second is token misuse. `SlackToken.AppLevel` (an `xapp-` token, `connections:write`) opens the socket; `SlackToken.Bot` (an `xoxb-` token) signs the Web API. They are distinct opaque types, so passing a bot token where the app-level token is required, or the reverse, does not compile. A Web API token can never open the socket by mistake. Tokens carry no `Schema` and no secret-rendering `toString`, so they ride the headers and connect body but never a decoded frame or a log line.

## Reconnection and lifecycle

Past the handler, the module owns the connection's life. Socket Mode connections are rotated by Slack periodically; `config.reconnect` decides what happens on a routine disconnect. The default, `Overlap`, brings the fresh connection up live and confirms it before stopping the old one, so no inbound envelope is lost across the rollover (an overlap dedup window suppresses a frame Slack re-pushes onto both sockets). `Immediate` closes the old connection and then opens the new one, accepting a brief gap. `Off` ends the loop cleanly on a routine disconnect:

```scala
import kyo.*

val gapless    = config.copy(reconnect = SlackConfig.Reconnect.Overlap)
val withGap    = config.copy(reconnect = SlackConfig.Reconnect.Immediate)
val stopOnDrop = config.copy(reconnect = SlackConfig.Reconnect.Off)
```

`keepAliveInterval` (default `Present(30.seconds)`) sets the WebSocket ping interval; Socket Mode defines no application keepalive beyond it.

> **Caution:** a `disconnect` whose reason is `link_disabled` is terminal under every reconnect policy. The loop ends with `SlackTerminalException` regardless of whether you chose `Overlap`, `Immediate`, or `Off`. The other `DisconnectReason` values (`Warning`, `RefreshRequested`) are routine rotations the policy handles transparently.

Because `connect` is `Scope`-managed, the socket and its background fibers close on scope exit or interrupt with no teardown call from you. That is the reason `connect` is the default entry point.

## Managing the connection yourself

When the connection has to outlive the lexical block that opens it, for example shared across a longer-lived application boundary, you manage the handle yourself with `connectUnscoped`. This is the one place the `SlackConnection` handle becomes visible. It returns a live connection with no scope-bound teardown, so you are responsible for closing it. Register `Scope.ensure(conn.close)` right after opening, or the socket and its fibers leak on abort:

```scala
import kyo.*

val managed: Unit < (Async & Abort[SlackException] & Scope) =
    Slack.connectUnscoped(config).map { conn =>
        Scope.ensure(conn.close).andThen {
            conn.receive {
                case SlackEnvelope.EventsApi(_, SlackEvent.AppMention(channel, _, _, _)) =>
                    Slack.chatPostMessage(SlackMessage(channel, "online"))
                        .andThen(SlackAck.Ack)
                case _ => SlackAck.Ack
            }
        }
    }
```

`conn.receive` drives the receive loop with the same structural acking and ambient-token binding as `connect`, and the same inferred `Isolate`. `conn.close` is total (it never aborts) and idempotent (a second close is a no-op). When you need the connection's lifetime tied to a scope, use `connect`; when you need to own teardown explicitly, use `connectUnscoped` and ensure the close yourself.

## Errors

Every entry point and Web API call carries `Abort[SlackException]`, a sealed hierarchy of six leaves. You recover by running `Abort.run[SlackException]` over the call and matching the leaf. The two leaves that carry typed fields are the ones worth recovering precisely: `SlackWebApiException` exposes `error`, the Slack error code from an `{"ok":false}` response, and `SlackRateLimitException` exposes `retryAfter`, the parsed `Retry-After` backoff:

```scala
import kyo.*

val recovered: SlackTs < (Async & Abort[SlackException]) =
    Abort.run[SlackException](
        Slack.chatPostMessage(SlackMessage(SlackId.ChannelId("C-bad"), "hi"))
    ).map {
        case Result.Success(ts) =>
            ts
        case Result.Failure(e: SlackException.SlackWebApiException) if e.error == "channel_not_found" =>
            Slack.chatPostMessage(SlackMessage(deploysChannel, "fell back to #deploys"))
        case Result.Failure(e: SlackException.SlackRateLimitException) =>
            Abort.fail(e)
        case Result.Failure(e) =>
            Abort.fail(e)
        case Result.Panic(e) =>
            Abort.panic(e)
    }
```

The remaining leaves name distinct failure modes: `SlackHandshakeException` (a failed connect, a missing wss url, or a Web API call with no ambient token bound), `SlackTransportException` (the socket dropped or a frame failed to send or receive), `SlackDecodeException` (a structural decode failure, raised only at `Slack.custom`'s response, never in the receive loop), and `SlackTerminalException` (the `link_disabled` end-of-link from the previous section).

> **Note:** a malformed inbound frame in the receive loop does not abort. It surfaces as `SlackEnvelope.Unknown` (or `SlackEvent.Unknown` / `SlackInteraction.Unknown`) carrying the raw payload, so the loop keeps running. `SlackDecodeException` is reserved for the one structural-decode site, `Slack.custom`'s typed `Out`.

## Putting it together

A single deploy-bot handler that covers the threads above: it greets on `Hello`, replies to mentions, answers `/deploy`, opens the rollback modal from a shortcut, and clears it on submission.

```scala
import kyo.*

val deployBotApp: Unit < (Async & Abort[SlackException]) =
    Scope.run {
        Slack.connect(config) {
            case SlackEnvelope.Hello(_, _, _) =>
                Slack.authTest.map(_ => SlackAck.Ack)

            case SlackEnvelope.EventsApi(_, SlackEvent.AppMention(channel, _, _, _)) =>
                Slack.chatPostMessage(SlackMessage(channel, "Deploy status: staging is green"))
                    .andThen(SlackAck.Ack)

            case SlackEnvelope.SlashCommand(_, cmd) if cmd.command == "/deploy" =>
                SlackAck.CommandResponse(SlackMessage(cmd.channel, s"Deploying ${cmd.text}..."))

            case SlackEnvelope.Interactive(_, SlackInteraction.Shortcut(_, triggerId, "rollback")) =>
                Slack.viewsOpen(triggerId, SlackView(SlackView.Type.Modal, blocksJson = rollbackBlocks))
                    .andThen(SlackAck.Ack)

            case SlackEnvelope.Interactive(_, SlackInteraction.ViewSubmission(_, _, _)) =>
                rollbackLastDeploy.andThen(SlackAck.ViewResponse(SlackAck.ViewAction.Clear))

            case _ => SlackAck.Ack
        }
    }
```
