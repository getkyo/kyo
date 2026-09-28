<!-- doctest:setup
```scala
import kyo.*
import kyo.SlackBlock.dsl.*

// The running domain: a deploy-bot that lives in #deploys, answers /deploy,
// replies to mentions, and opens a rollback confirmation modal. One config and
// a few concrete ids recur across every example below.
val config = SlackConfig(
    appLevel = SlackToken.AppLevel("xapp-1-A0DEPLOYBOT-placeholder"),
    bot = SlackToken.Bot("xoxb-deploybot-placeholder")
)

val deploysChannel = SlackId.ChannelId("C-deploys")

// The bot's own user id. A real bot reads it from Slack.authTest inside the loop.
val deployBotUser = SlackId.UserId("U-deploybot")

// A small typed Block Kit layout (built with the dsl) reused by the modal examples below.
val rollbackBlocks: Chunk[SlackBlock] = blocks(section("Roll back the last deploy?"))

// Stand-ins for the bot's own work, kept out of the visible examples.
def currentDeployStatus: String < Sync     = "staging is green"
def runDeploy(target: String): Unit < Sync = ()
def rollbackLastDeploy: Unit < Sync        = ()
```
-->

# kyo-slack

`kyo-slack` is a Slack [Socket Mode](https://api.slack.com/apis/socket-mode) client. A Slack app written with it is a single `Slack.run(config)(handler)` call: you supply a `SlackConfig` carrying an app-level token (it opens the WebSocket) and a bot token (it authenticates the Web API), and a handler. Slack streams typed inbound frames into the handler, you pattern-match the one you care about, do your work, and return what that frame requires: a `SlackAck` for a frame Slack waits to see acknowledged, nothing for one it does not. Returning the value is the acknowledgement: the framework emits exactly one wire ack from the `SlackAck` you return. There is no ack method to call, so you cannot forget to ack and you cannot double-ack. `run` returns `Unit` when the loop ends, and its background form is kyo's own `Fiber.init(Slack.run(config)(handler))`, a fiber that ends with the loop's failure, stops on `interrupt` and is interrupted when its `Scope` closes, beside the handle form of `Slack.init`, `Slack.receive` and `Slack.close` in [Managing the connection yourself](#managing-the-connection-yourself).

Web API calls (`Slack.chatPostMessage`, `Slack.viewsOpen`, and the rest) take no token argument. Each requires the client, `Env[Slack]`, which `run` provides around the handler, so a call there finds the bot token in it, and a call where no client is provided does not compile. The handler runs once per envelope, in arrival order, and must return within `config.ackDeadline` (2.5 seconds by default, inside Slack's 3 second window); work that takes longer goes on a fiber forked from the handler. The connection sends keepalive pings, moves to a new socket when Slack rotates it, and closes when the loop ends or is interrupted.

Every operation runs in `Async` and aborts with its own sealed failure trait, such as `Abort[SlackChatPostMessageFailure]` for `Slack.chatPostMessage`. Every trait extends `SlackException`, so `Abort[SlackException]` holds any of them. The module is cross-platform: JVM, Scala.js, Scala Native, and WebAssembly, from a single shared source set.

Every example below is one deploy bot that lives in #deploys. Its first job is answering a mention with the deploy status:

```scala
import kyo.*

val app = Slack.run(config)([A] =>
    (env: SlackEnvelope[A]) =>
        env match
            case SlackEnvelope.EventsApi(_, SlackEvent.AppMention(channel, _, _, _), _) =>
                Slack.chatPostMessage(SlackMessage(channel, "Deploy status: staging is green"))
                    .andThen(SlackAck.Ack)
            case _: SlackEnvelope.Acknowledged => SlackAck.Ack
            case _: SlackEnvelope.Plain        => Kyo.unit
)
```

> **Note:** `run` infers what the handler can fail with (`E`) and its other effects (`S`) from the handler's body, so you write no type arguments. That holds while nothing gives the `run` expression an expected type. A result type written on the `val`, or `run` passed straight into another call (`.andThen(...)`, `KyoApp`'s `run { ... }`), makes `S` take on that surrounding row, and the `Isolate` that `run` requires for `S` then refuses it. The remedy is the same in each case: bind `run` to a `val` with no written type first, as above, and compose the `val`; or write `Slack.run[E, Any](config)(...)`. `Slack.receive` behaves the same way.

The sections below build up each piece in the order you meet it. A handler that combines mentions, a slash command, and a rollback modal appears in [Putting it together](#putting-it-together) near the end.

## Connect and reply in a handler

A `SlackConfig` needs two tokens: the `xapp-` app-level token that opens the socket and the `xoxb-` bot token that signs Web API calls. The handler is a polymorphic function, `[A] => SlackEnvelope[A] => A < ...`: it receives one `SlackEnvelope[A]` and returns the `A` that envelope requires. `run` opens the socket, runs the receive loop under the reconnect policy, and closes everything it opened when the loop ends.

The match does three things at once. The first case selects the one frame this branch handles (an app mention). The `Slack.chatPostMessage` call needs no token: the client is provided around the handler. And `.andThen(SlackAck.Ack)` sequences the post and then returns the ack value that the framework emits for this frame.

The last two cases are the fallback, and their shape is required. The envelopes are grouped by the answer they take: `SlackEnvelope.Acknowledged` (`EventsApi`, `Interactive`, `SlashCommand`, `Unknown`) takes a `SlackAck`, and `SlackEnvelope.Plain` (`Hello`, `Disconnect`, `UnknownFrame`) takes nothing. Matching either group tells the compiler what `A` is in that branch, so it checks that you return an ack for the one and `Kyo.unit` for the other. A bare `case _` does not say which, and does not compile.

> **Caution:** because the handler calls `Slack.chatPostMessage`, `SlackChatPostMessageFailure` is in the loop's row, and one `channel_not_found` or `not_in_channel` answer ends `run` and stops the bot. To keep the bot up, handle the Web API failure inside the handler and return an ack, as shown under [When a call fails](#when-a-call-fails).

## Acking is the return value

Acking is not an action you perform; it is the value your handler hands back. This is the central rule of the module. For an `Acknowledged` envelope the handler's result type is `SlackAck`, the framework emits exactly one wire ack from whatever you return, and there is no public `ack` or `sendAck` method on the `Slack` object or a `Slack` client. Returning something other than a `SlackAck` for such an envelope, or a `SlackAck` for a `Plain` one, does not compile, and there is no channel you could call twice, so forgetting and double-acking are both unrepresentable.

`SlackAck` has three shapes, each one frame on the socket. `Ack` is the bare acknowledgement, the common return. The other two carry a payload that rides the acknowledgement:

```scala
import kyo.*

val bare: SlackAck    = SlackAck.Ack
val command: SlackAck = SlackAck.CommandResponse(SlackReply("Deploying..."), SlackAck.CommandResponse.Visibility.InChannel)
val view: SlackAck    = SlackAck.ViewResponse(SlackAck.ViewAction.Clear)
```

A `CommandResponse` names no channel: Slack posts it where the command was typed, visible to its author (`Ephemeral`) or to everyone there (`InChannel`). A payload rides the ack only when the envelope accepts one. Slack marks each envelope with `accepts_response_payload`, read as `Meta.acceptsResponsePayload`; when it is `Present(false)` the framework sends the bare ack instead and logs at warn which payload kind it did not send, never its content. When the frame does not say, the payload is sent.

> **Caution:** the handler runs under `config.ackDeadline` (default `2500.millis`, which leaves the ack 500 ms to reach Slack inside its 3 second window). If it has not returned a `SlackAck` within that window, the framework emits the bare `SlackAck.Ack` and cancels the still-running handler, including any Web API call in flight, so a late payload ack never goes out. Exactly one ack is emitted per acknowledgeable envelope, always. Long work therefore belongs on a fiber forked from the handler, which keeps the client, so its Web API calls work.

The loop takes one envelope, runs its handler, acks, and only then takes the next. A slow handler delays every envelope queued behind it, by up to `ackDeadline` each.

When a handler aborts instead of returning, `run` (or `receive`) ends with that failure and the envelope is left unacknowledged. Aborting is how a handler stops the bot on purpose. To keep going past a failing envelope, recover inside the handler and return `SlackAck.Ack`.

A handler that panics (throws, or raises `Abort.panic`) is different: the defect is logged at error with the envelope's type and id, the envelope is left unacked so Slack re-delivers it, and the loop goes on. One poisoned envelope cannot stop the bot. This holds for every frame the handler receives, `Hello` and `Disconnect` included; a `link_disabled` disconnect still ends the loop with `SlackLinkDisabledException` after its handler panics.

A re-delivery carries `retryAttempt` and `retryReason` on its `Meta`. The deploy bot uses that to post the status on the first attempt only:

```scala
import kyo.*

val announceOnce = Slack.run(config)([A] =>
    (env: SlackEnvelope[A]) =>
        env match
            case SlackEnvelope.EventsApi(meta, SlackEvent.AppMention(channel, _, _, _), _) =>
                if meta.retryAttempt.isDefined then SlackAck.Ack
                else
                    Slack.chatPostMessage(SlackMessage(channel, "Deploy status: staging is green"))
                        .andThen(SlackAck.Ack)
            case _: SlackEnvelope.Acknowledged => SlackAck.Ack
            case _: SlackEnvelope.Plain        => Kyo.unit
)
```

This trades a lost reply, when the first attempt failed before posting, for never posting twice. Choose per side effect: a reply is safe to skip, a deploy is not safe to run twice.

`Hello`, `Disconnect` and `UnknownFrame` carry no `envelope_id`, so there is nothing to acknowledge, and their handler is not raced against `ackDeadline`. An `Unknown` carries its frame's `Meta` and is acked like any envelope, so Slack does not deliver it again. `Hello` is delivered first and is the clean startup hook. `Slack.authTest` requires the client like every other Web API method, so it runs there, inside the loop:

```scala
import kyo.*

val withStartup = Slack.run(config)([A] =>
    (env: SlackEnvelope[A]) =>
        env match
            case SlackEnvelope.Hello(_, _, _) =>
                Slack.authTest.map(identity => Log.info(s"deploy bot connected as ${identity.userId.value}"))
            case _: SlackEnvelope.Acknowledged => SlackAck.Ack
            case _: SlackEnvelope.Plain        => Kyo.unit
)
```

## The frames you receive

Once you are acking correctly, the next question is what can arrive. The receive loop yields one `SlackEnvelope` at a time. The acknowledged cases are `EventsApi` (an Events API callback, with Slack's `eventId`), `Interactive` (an interactivity payload), `SlashCommand` (a slash command), and `Unknown`, which carries the raw frame for any envelope type the module does not model. The plain cases are `Hello` (connection established), `Disconnect` (Slack rotating or terminating the link), and `UnknownFrame`, a frame the module does not model that has no `envelope_id`. A match over all seven needs no fallback:

```scala
import kyo.*

val byEnvelope = Slack.run(config)([A] =>
    (env: SlackEnvelope[A]) =>
        env match
            case SlackEnvelope.EventsApi(meta, event, eventId)   => SlackAck.Ack
            case SlackEnvelope.SlashCommand(meta, command)       => SlackAck.Ack
            case SlackEnvelope.Interactive(meta, interaction)    => SlackAck.Ack
            case SlackEnvelope.Unknown(frameType, payload, meta) => SlackAck.Ack
            case SlackEnvelope.Hello(_, _, _)                    => Kyo.unit
            case SlackEnvelope.Disconnect(_)                     => Kyo.unit
            case SlackEnvelope.UnknownFrame(frameType, payload)  => Kyo.unit
)
```

> **Note:** `Unknown` is the forward-safety case, not an error. An envelope of a type the module does not model, and one whose payload lacks a field its type requires, arrives as `Unknown` carrying the raw JSON, or as `UnknownFrame` when it has no `envelope_id`, so no data is lost. A required id Slack sent empty counts as missing: an empty id names nothing. A frame that is not JSON or has no `type` field is logged at warn, by its size or by the decoder's failure class, and skipped; it never reaches the handler. An inbound frame that fails to decode never raises a `SlackException`: `SlackDecodeException` is only for Web API responses, covered under [When a call fails](#when-a-call-fails).

Slack can deliver the same event more than once, after a reconnect or when an ack was late. `meta.envelopeId` names one delivery and is what the ack answers; `eventId` is Slack's `event_id`, "globally unique across all workspaces", and is the id to deduplicate events by. Past the connection rotation covered below, the module does not deduplicate for you. An `events_api` frame without an `event_id` arrives as `Unknown`.

The raw JSON of `SlackEnvelope.Unknown`, `SlackInteraction.Unknown` and `SlackEvent.Unknown` is a `SlackRawJson`, and so is a `ViewSubmission`'s `stateJson`, which holds what the person typed into the modal. A payload can hold credentials: an interaction carries its `response_url`, whose path authorizes posting, and Slack's verification `token`. So a `SlackRawJson` renders only its length, and `value` is the one way to read the text. The deploy bot can log what it skipped without printing either:

```scala
import kyo.*

val logSkipped = Slack.run(config)([A] =>
    (env: SlackEnvelope[A]) =>
        env match
            case SlackEnvelope.Unknown(frameType, payload, _) =>
                // Renders as, for example, "SlackRawJson(412 characters)".
                Log.info(s"deploy bot skipped a $frameType envelope: $payload").andThen(SlackAck.Ack)
            case _: SlackEnvelope.Acknowledged => SlackAck.Ack
            case _: SlackEnvelope.Plain        => Kyo.unit
)
```

An `EventsApi` frame holds a `SlackEvent`, the Events API event ADT. Its typed cases are `Message`, `AppMention`, `ReactionAdded`, `AppHomeOpened`, and `MemberJoinedChannel`, with the same `Unknown` forward-safety case. A `Message` carries an optional `threadTs`, which is how you tell a top-level message from a threaded reply:

```scala
import kyo.*

val byEvent = Slack.run(config)([A] =>
    (env: SlackEnvelope[A]) =>
        env match
            case SlackEnvelope.EventsApi(_, SlackEvent.AppMention(channel, user, text, ts), _) =>
                SlackAck.Ack
            case SlackEnvelope.EventsApi(_, SlackEvent.Message(channel, user, text, ts, threadTs), _) =>
                SlackAck.Ack
            case _: SlackEnvelope.Acknowledged => SlackAck.Ack
            case _: SlackEnvelope.Plain        => Kyo.unit
)
```

> **Note:** a `message` event missing any of `channel`, `user`, `text`, or `ts` (the `message_changed` and `message_deleted` subtypes, for example) arrives as `SlackEvent.Unknown("message", eventJson)`, with the event's raw JSON as a `SlackRawJson` and a warn log, not as `SlackEvent.Message`. Every other event and interaction kind follows the same rule. A field Slack may leave out is a `Maybe` instead: `AppHomeOpened.tab` is `Absent` when the person did not open a tab, and `ViewSubmission.stateJson` when the modal sent no state.

These types model what Slack sends, and only kyo-slack decodes them, from Slack's own frames. None of the inbound types (events, interactions, envelopes, commands, `Slack.Identity`) has a `Schema`; summoning one is a compile error that names the type. Log or store the fields you need.

A `SlashCommand` frame holds a `SlackCommand`: the `command` name, the typed `text`, the originating `channel` and `user`, a `triggerId` for opening a modal, and the `responseUrl` Slack sent, a `Maybe[SlackResponseUrl]` that is `Absent` when Slack sent none. The whole URL is a credential: anyone who holds it can post to the conversation, so a `SlackResponseUrl` renders as `SlackResponseUrl(<redacted>)`, `value` is the one way to read it, and a `SlackCommand` has no `Schema`. The immediate reply is `SlackAck.CommandResponse`; a later one goes through the url, as [Answering through a response_url](#answering-through-a-response_url) shows. You match on `command` to route the slash command:

```scala
import kyo.*

val byCommand = Slack.run(config)([A] =>
    (env: SlackEnvelope[A]) =>
        env match
            case SlackEnvelope.SlashCommand(_, cmd) if cmd.command == "/deploy" =>
                SlackAck.CommandResponse(SlackReply(s"Deploying ${cmd.text}..."), SlackAck.CommandResponse.Visibility.InChannel)
            case _: SlackEnvelope.Acknowledged => SlackAck.Ack
            case _: SlackEnvelope.Plain        => Kyo.unit
)
```

## Replying with the Web API

When you want the bot to say or change something, you call a Web API method. Every one of them requires the client, `Env[Slack]`, and takes its token from it, so none of them take a token argument. The handler runs with the client provided, and so does a fiber forked from it. They return typed ids and timestamps, not loose strings.

`Slack.chatPostMessage` posts a message and returns its `SlackTs`. That ts is what you feed back as a `threadTs` to reply in-thread under the message that triggered you. Slack can send the bot its own posts as `message` events (Bolt's `ignoreSelf` middleware exists to drop them), so a handler that answers messages skips its own user id, or it answers itself. The id is `Slack.authTest`'s `userId`, read in the `Hello` branch above; here `deployBotUser` stands in for it:

```scala
import kyo.*

val replyInThread = Slack.run(config)([A] =>
    (env: SlackEnvelope[A]) =>
        env match
            case SlackEnvelope.EventsApi(_, SlackEvent.Message(channel, user, text, ts, _), _)
                if channel == deploysChannel && user != deployBotUser && text.startsWith("deploy ") =>
                Slack.chatPostMessage(SlackMessage(channel, "Queued, I will post here when it is out", threadTs = Present(ts)))
                    .andThen(SlackAck.Ack)
            case _: SlackEnvelope.Acknowledged => SlackAck.Ack
            case _: SlackEnvelope.Plain        => Kyo.unit
)
```

When you need a reply only the triggering user can see, use `chatPostEphemeral`. When you want to edit a message you already posted, use `chatUpdate`, keyed by the `channel` and the `ts` the original post returned. A verb's row names the client it requires, so a helper built from verbs carries `Env[Slack]` until something provides it:

```scala
import kyo.*

val postThenEdit: SlackTs < (Async & Abort[SlackChatPostMessageFailure | SlackChatUpdateFailure] & Env[Slack]) =
    Slack.chatPostMessage(SlackMessage(deploysChannel, "Deploying staging...")).map { ts =>
        Slack.chatUpdate(deploysChannel, ts, SlackMessage(deploysChannel, "Deploy complete"))
    }

val onlyForUser: SlackTs < (Async & Abort[SlackChatPostEphemeralFailure] & Env[Slack]) =
    Slack.chatPostEphemeral(SlackMessage(deploysChannel, "You lack deploy rights"), SlackId.UserId("U-alice"))
```

Outside the loop, `Slack.let(config)(v)` provides a client to `v` and releases it when `v` ends. It opens no socket, so it cannot fail on its own: it is the form for a job that only calls the Web API, such as a deploy pipeline announcing its result:

```scala
import kyo.*

val announced: SlackTs < (Async & Abort[SlackChatPostMessageFailure]) =
    Slack.let(config)(Slack.chatPostMessage(SlackMessage(deploysChannel, "Deploy of staging finished")))
```

`Slack.authTest` confirms the bot token and returns a `Slack.Identity` (the bot's `userId`, `teamId`, `botId`, and workspace `url`, an `HttpUrl`). An answer missing any of the four fails with `SlackDecodeException`, whose `path` names the field, and so does an empty id or a `url` that is not absolute. Its `userId` is what a handler filters its own messages by.

For the long tail of the Web API that this module does not model directly, `Slack.custom` is the escape hatch. You give it a `SlackMethod` and a request body whose type has a `Schema`, and it answers the response type you ask for, which also has a `Schema`. The deploy bot marks its announcement with a rocket:

```scala
import kyo.*

case class AddReaction(channel: SlackId.ChannelId, timestamp: SlackTs, name: String) derives Schema
case class Reacted(ok: Boolean) derives Schema

val reactionsAdd = SlackMethod("reactions.add")

def markShipped(announcement: SlackTs): Reacted < (Async & Abort[SlackCustomFailure] & Env[Slack]) =
    Slack.custom(reactionsAdd, AddReaction(deploysChannel, announcement, "rocket"))
```

A method name goes into the request url after `config.baseUrl`, so `SlackMethod` accepts only what Slack's method names are made of: ASCII letters, digits, `.` and `_`. Any other character, which would move the bot token to another path or into a query, panics with `SlackInvalidMethodException` naming its position, and so does a name of only dots, which would be a `.` or `..` segment.

`custom` fails only with the codes Slack gives one meaning across its whole Web API: the credential codes, the scope codes, `invalid_arguments`, a rate limit, transport and decode. A method-specific code such as `channel_not_found` on a `custom` call arrives as `SlackOtherApiException` with `code == "channel_not_found"`, not as `SlackChannelNotFoundException`.

Block Kit layouts are typed. `SlackMessage` and `SlackView` carry a `Chunk[SlackBlock]`, built either from the case classes (`SlackBlock.Section`, `SlackBlock.Element.Button`, ...) or, more concisely, from the `SlackBlock.dsl` builders:

```scala
import kyo.*
import kyo.SlackBlock.dsl.*

val panel: Chunk[SlackBlock] = blocks(
    section("*Deploy* `staging`?"),
    divider,
    actions(button("Deploy", "deploy"), button("Cancel", "cancel"))
)
```

The covered surface is the common subset (section, header, divider, context, actions, input, image; button, text input, select). For a block type not modeled, `SlackBlock.Raw(json)` splices one block's raw JSON. The text is parsed when the `Raw` is built, and text that is not an RFC 8259 JSON object panics there with `SlackInvalidRawBlockException`, naming the position and what the reader found, so sending a message never fails on it.

## Interactivity: modals and actions

Interactivity arrives as `SlackEnvelope.Interactive`, holding a `SlackInteraction`. This is where the typed ids do real work: a `Shortcut` (or `BlockActions` or `MessageAction`) carries a `triggerId`, and that `triggerId` is exactly what `Slack.viewsOpen` requires to open a modal. The type checker threads the id from the interaction straight into the open call, so you cannot key a modal off the wrong id.

The rollback flow is two interactions. First the shortcut opens a confirmation modal and acks bare. Then the modal's submission comes back as `ViewSubmission`, and you answer it with a `ViewResponse` carrying a `ViewAction`. The four actions are `Clear` (close the modal stack), `Update` and `Push` (replace or stack a view), and `Errors` (show per-block validation errors keyed by block id). A rollback can outlast the ack deadline, and the deadline would cancel it inline and send the bare ack instead of the `Clear`, so the handler forks it and answers at once:

```scala
import kyo.*

val rollbackFlow = Slack.run(config)([A] =>
    (env: SlackEnvelope[A]) =>
        env match
            case SlackEnvelope.Interactive(_, SlackInteraction.Shortcut(_, triggerId, "rollback")) =>
                Slack.viewsOpen(triggerId, SlackView(SlackView.Type.Modal, blocks = rollbackBlocks))
                    .andThen(SlackAck.Ack)
            case SlackEnvelope.Interactive(_, SlackInteraction.ViewSubmission(_, _, _)) =>
                Fiber.initUnscoped(rollbackLastDeploy)
                    .andThen(SlackAck.ViewResponse(SlackAck.ViewAction.Clear))
            case _: SlackEnvelope.Acknowledged => SlackAck.Ack
            case _: SlackEnvelope.Plain        => Kyo.unit
)
```

The fiber is unscoped because it has to outlive the handler that starts it.

The other `SlackInteraction` cases are `BlockActions` (a click on a button or other block element, carrying a `Chunk[Action]` of the `actionId`/`blockId`/`value` that fired), `ViewClosed` (the user dismissed a modal), and `MessageAction` (a message-level shortcut). `viewsUpdate` replaces an open view's content by its `ViewId`, and `viewsPublish` publishes a Home tab view for a user.

Only `BlockActions` and `MessageAction` payloads carry a `response_url`, as their `responseUrl`, `Present` only when Slack sent one; like a command, neither has a `Schema`. A `BlockActions` from a message also carries that message's `messageTs`. A click is acked like any envelope, and the answer to it goes through its `response_url`, below.

When you are responding to a view submission and the work is itself a view change, use `ViewResponse`. When you are answering a button click, ack it and answer through its `response_url`; to edit the message the click came from from anywhere else, call `chatUpdate` with the click's `channel` and `messageTs`. When you are answering a slash command immediately, use `CommandResponse`.

### Answering through a response_url

Work that outlasts the ack deadline answers later, through the `response_url` of the command or click that started it. Four operations post a `SlackReply`, a `text` and optional `blocks`, to it:

- `Slack.respondEphemeral(url, reply)` posts a message only the person who acted can see.
- `Slack.respondInChannel(url, reply, threadTs)` posts a message everyone in the conversation can see, in a thread when `threadTs` is `Present`.
- `Slack.replaceOriginal(url, reply)` puts the reply in place of the message the interaction came from.
- `Slack.deleteOriginal(url)` deletes that message.

The url is the credential, so these operations send no bot token; they require the client for its HTTP client, so they run under `run`, `let`, or `use` like the Web API verbs. Slack accepts at most five answers through one `response_url`, within 30 minutes of the interaction. Each operation fails with its own trait, like a Web API call. The deploy button acks at once, runs the deploy on its own fiber, and then replaces the panel with the outcome, logging an answer Slack refused:

```scala
import kyo.*

val onDeploy = Slack.run(config)([A] =>
    (env: SlackEnvelope[A]) =>
        env match
            case SlackEnvelope.Interactive(_, click: SlackInteraction.BlockActions) if click.actions.exists(_.actionId.value == "deploy") =>
                click.responseUrl match
                    case Present(url) =>
                        Fiber.initUnscoped(
                            runDeploy("staging").andThen(
                                Abort.recover[SlackReplaceOriginalFailure](e => Log.warn(s"deploy panel not updated: ${e.getMessage}")) {
                                    Slack.replaceOriginal(url, SlackReply("Deployed staging"))
                                }
                            )
                        ).andThen(SlackAck.Ack)
                    case Absent => SlackAck.Ack
            case _: SlackEnvelope.Acknowledged => SlackAck.Ack
            case _: SlackEnvelope.Plain        => Kyo.unit
)
```

Slack documents no body for a successful `response_url` answer, so any 2xx without `{"ok":false}` is a success. A 429 is `SlackRateLimitException`, an `ok:false` body is `SlackOtherApiException` carrying its code (or `SlackRateLimitException` for a rate-limit code), and any other non-2xx is `SlackUnexpectedStatusException`, each under the method name `response_url`. Redirects are not followed, so a redirect is an unexpected status. A code or message in which Slack quotes the url holds `<response_url>` in its place, and one that quotes a token, here or on any Web API call, holds `<redacted>`. A url that is not an absolute http or https url on a host is refused with `SlackRefusedUrlException` before anything is sent.

## Typed ids and tokens

The id and token types in this module exist to make two classes of mistake into compile errors. The first is mixing up identifiers. `SlackId` holds eleven opaque types over `String`: `ChannelId`, `UserId`, `TeamId`, `AppId`, `TriggerId`, `EnvelopeId`, `EventId`, `ViewId`, `BotId`, `ActionId`, and `BlockId`; the message timestamp `SlackTs` is a separate top-level opaque type alongside them. A `ChannelId` is not assignable where a `TriggerId` or `UserId` is required, so the `triggerId` flowing into `viewsOpen` in the previous section cannot accidentally be a channel:

```scala
import kyo.*

val channel: SlackId.ChannelId = SlackId.ChannelId("C-deploys")
val user: SlackId.UserId       = SlackId.UserId("U-alice")
val raw: String                = channel.value
```

The second is token misuse. `SlackToken.AppLevel` (an `xapp-` token, `connections:write`) opens the socket; `SlackToken.Bot` (an `xoxb-` token, or `xoxe.xoxb-` for an app with token rotation) signs the Web API. They are distinct types, so passing a bot token where the app-level token is required (or the reverse), or a plain `String` for either, does not compile. A token is checked when it is built: it must carry its prefix, be at most 255 characters (the length Slack documents), and hold only printable ASCII other than space, which the `Authorization` header carries unchanged. Otherwise it panics with `SlackInvalidTokenException`, naming which kind of token (`token`), the problem and a position, never a character of the token. A token renders as `SlackToken.Bot(<redacted>)` or `SlackToken.AppLevel(<redacted>)`, so printing a `SlackConfig` or logging a token never shows the secret; `value` is the one way to read it:

```scala
import kyo.*

val shown: String = config.toString // SlackConfig(SlackToken.AppLevel(<redacted>),SlackToken.Bot(<redacted>),...)
val secret: String = config.bot.value
```

Tokens carry no `Schema`, so they ride the `Authorization` header and never a decoded frame.

## Reconnection and lifecycle

Past the handler, the module owns the connection's life. Socket Mode connections are rotated by Slack periodically; `config.reconnect` decides what happens on a routine disconnect. The default, `Overlap`, brings the fresh connection up live and confirms it before stopping the old one, so no inbound envelope is lost across the rollover (an overlap dedup window suppresses a frame Slack re-pushes onto both sockets). `Immediate` closes the old connection and then opens the new one, accepting a brief gap. `Off` ends the loop cleanly on a routine disconnect. Under each policy the envelopes the old connection already received are delivered and acked before it closes:

```scala
import kyo.*

val gapless    = config.copy(reconnect = SlackConfig.Reconnect.Overlap)
val withGap    = config.copy(reconnect = SlackConfig.Reconnect.Immediate)
val stopOnDrop = config.copy(reconnect = SlackConfig.Reconnect.Off)
```

> **Caution:** delivery is at least once. Across an `Overlap` rotation, an id Slack re-pushes is acked but not delivered twice. An id whose handler panicked is delivered again. The dedup window covers two connection generations: after two rotations an id falls out of it, and a re-push of that id is delivered again. A handler with side effects should tolerate a repeat, deduplicating an event by its `eventId`; `Meta.retryAttempt` marks a Slack retry.

`keepAliveInterval` (default `Present(30.seconds)`) sets the WebSocket ping interval; Socket Mode defines no application keepalive beyond it. Each Web API request is bounded by `requestTimeout` and its connection by `connectTimeout` (both `10.seconds` by default), and an answer longer than `maxResponseLength` (default `16.mb`) is refused. These and `ackDeadline` must be positive and finite: building a `SlackConfig` with `Duration.Zero` or `Duration.Infinity` in any of them panics with `SlackInvalidConfigException`, naming the setting and the value. `baseUrl` (default `https://slack.com/api`) must be an absolute http or https url on a host in printable ASCII, with no user info, no query and no trailing slash.

The module makes its requests with its own kyo-http client, built for the config and closed with it. Nothing of the caller's kyo-http client or configuration (a filter, a base url, a relaxed TLS setting) reaches a request that carries a token.

> **Caution:** a `disconnect` whose reason is `link_disabled` is terminal under every reconnect policy. The loop ends with `SlackLinkDisabledException` regardless of whether you chose `Overlap`, `Immediate`, or `Off`. The other `DisconnectReason` values (`Warning`, `RefreshRequested`, an `Unknown(raw)` reason, and `Unspecified` for a disconnect that names none) are routine rotations the policy handles transparently.

`run` closes the socket, its background fibers and its HTTP client when the loop ends, fails, or is interrupted, with no teardown call from you. That is the reason `run` is the default entry point.

## Managing the connection yourself

When you want the connection as a value, to drive its loop yourself or hand it to other code, open it with `Slack.init`. It answers the `Slack` client with its Socket Mode connection open, closed when the enclosing `Scope` ends. `Slack.receive(handler)` is the loop on the provided client, and `Slack.use(client)(v)` provides the client to it. `receive` infers its handler's effects like `run`, so the loop is bound to a `val` before it is passed on:

```scala
import kyo.*

val onlineLoop = Slack.receive([A] =>
    (env: SlackEnvelope[A]) =>
        env match
            case SlackEnvelope.EventsApi(_, SlackEvent.AppMention(channel, _, _, _), _) =>
                Slack.chatPostMessage(SlackMessage(channel, "online"))
                    .andThen(SlackAck.Ack)
            case _: SlackEnvelope.Acknowledged => SlackAck.Ack
            case _: SlackEnvelope.Plain        => Kyo.unit
)

val managed = Scope.run(Slack.init(config).map(client => Slack.use(client)(onlineLoop)))
```

`receive` drives the receive loop with the same structural acking, client and outcome policy as `run`, and the same inferred `Isolate`. On a connection already closed it returns at once; on a client from `Slack.let`, which holds no connection, it opens one for its own duration. `Slack.close(client)` closes the connection and the client's HTTP client before the scope ends; it is total (it never aborts) and idempotent (a second close is a no-op).

> **Note:** `init` opens the socket at once, but nothing reads envelopes until `receive` runs. Frames wait unacknowledged in the meantime, so call `receive` promptly or Slack redelivers them.

When no scope describes the connection's lifetime, `Slack.initUnscoped` opens one that nothing closes but your `close`. You own the teardown, so register it with whatever outlives the connection:

```scala
import kyo.*

val longLived: Slack < (Async & Abort[SlackInitFailure]) =
    Slack.initUnscoped(config)

val shutdown: Slack => Unit < Async =
    client => Slack.close(client)
```

## When a call fails

Every failure is a leaf of the sealed `SlackException`, each a case class that compares by its fields. Each operation aborts with its own sealed trait, and a leaf extends the trait of every operation that can produce it, so the row names exactly the leaves you can meet:

| Operation | Row |
|---|---|
| `Slack.init` | `Abort[SlackInitFailure]` |
| `Slack.run` | `Abort[SlackRunFailure \| E]` |
| `Slack.receive` | `Abort[SlackReceiveFailure \| E]` |
| `Slack.authTest` | `Abort[SlackAuthTestFailure]` |
| `Slack.chatPostMessage` | `Abort[SlackChatPostMessageFailure]` |
| `Slack.chatPostEphemeral` | `Abort[SlackChatPostEphemeralFailure]` |
| `Slack.chatUpdate` | `Abort[SlackChatUpdateFailure]` |
| `Slack.viewsOpen` | `Abort[SlackViewsOpenFailure]` |
| `Slack.viewsUpdate` | `Abort[SlackViewsUpdateFailure]` |
| `Slack.viewsPublish` | `Abort[SlackViewsPublishFailure]` |
| `Slack.custom` | `Abort[SlackCustomFailure]` |
| `Slack.respondEphemeral` | `Abort[SlackRespondEphemeralFailure]` |
| `Slack.respondInChannel` | `Abort[SlackRespondInChannelFailure]` |
| `Slack.replaceOriginal` | `Abort[SlackReplaceOriginalFailure]` |
| `Slack.deleteOriginal` | `Abort[SlackDeleteOriginalFailure]` |

`E` on `run` and `receive` is whatever the handler can fail with, inferred from it. A handler that calls `Slack.chatPostMessage` puts `SlackChatPostMessageFailure` in `E`, and a handler failure ends the loop with that failure. When the handler's branches fail with different traits, Scala infers their common parent, so `E` is `SlackException`, as in [Putting it together](#putting-it-together). Give the handler a declared type naming the union when you want the row kept narrow.

Slack answers most failures as `{"ok":false,"error":code}`. Those land under the category `SlackApiException`, which exposes `method`, `code`, and Slack's `messages` (its `response_metadata.messages`, often naming the offending argument). A code a caller can act on has its own leaf on the operations whose Slack documentation lists it: `SlackChannelNotFoundException` on the three `chat` calls, `SlackExpiredTriggerIdException` on `viewsOpen`, `SlackMessageNotFoundException` on `chatUpdate`, and so on. The credential leaves (`SlackInvalidAuthException`, `SlackNotAuthedException`, `SlackTokenRevokedException`, `SlackTokenExpiredException`, `SlackAccountInactiveException`, `SlackNotAllowedTokenTypeException`, and `SlackMissingScopeException` with the `needed` and `provided` scopes) are on every row but the four `response_url` ones, which send no token. Any other code, including a code whose leaf belongs to a different operation, is `SlackOtherApiException`, carrying the code as a string. A rate limit, by HTTP 429 or by the code `ratelimited` or `rate_limited`, is `SlackRateLimitException` on every row, whose `retryAfter` is the delay Slack sent (`Duration.Infinity` if it exceeds what a `Duration` holds), or `Absent` when it sent none it could mean.

A rate limit is not retried for you: the call fails with `SlackRateLimitException`, and waiting `retryAfter` and calling again is the caller's choice. You recover by running `Abort.run` over the call with its trait and matching the leaf you can act on. The deploy bot falls back to #deploys when the requested channel is gone, and waits out a rate limit once:

```scala
import kyo.*

def announce(channel: SlackId.ChannelId): SlackTs < (Async & Abort[SlackChatPostMessageFailure] & Env[Slack]) =
    Abort.run[SlackChatPostMessageFailure](
        Slack.chatPostMessage(SlackMessage(channel, "Deploy of staging started"))
    ).map {
        case Result.Success(ts) =>
            ts
        case Result.Failure(_: SlackChannelNotFoundException) =>
            Slack.chatPostMessage(SlackMessage(deploysChannel, "Deploy of staging started"))
        case Result.Failure(SlackRateLimitException(_, Present(delay))) =>
            Async.sleep(delay).andThen(Slack.chatPostMessage(SlackMessage(channel, "Deploy of staging started")))
        case Result.Failure(e) =>
            Abort.fail(e)
        case Result.Panic(e) =>
            Abort.panic(e)
    }
```

The other leaves name what failed outside Slack's own answer:

- `SlackTransportException`, on every row: the HTTP or WebSocket call failed before an answer could be read. `method` names the call (the Web API method, `apps.connections.open`, `socket-connect`, or `response_url`), `kind` says what failed (`Connect`, `Dns`, `Tls`, `ConnectTimeout`, `Timeout`, `Protocol`, `ConnectionClosed`, `WebSocketHandshake`, `PoolExhausted`, `PayloadTooLarge`, `NoResponseHead`), with the `host` and `port` it went to and the elapsed `timeout` for a timeout. It holds no kyo-http failure, since every one names the request's url and a `response_url`'s path is its credential; for a connection that could not be made, kyo-net's failure, which names only a host and port, is its `cause`.
- `SlackRefusedUrlException`, on the four `response_url` rows and on `init`, `run`, and `receive`: a url Slack supplied (a `response_url`, or the socket url `apps.connections.open` answered) is not an absolute url of its scheme on a host in printable ASCII, so nothing was sent to it.
- `SlackUnexpectedStatusException`, on every row: a non-2xx answer with no Slack body, such as an HTML error page from Slack's edge.
- `SlackDecodeException`, on every row: a response body that did not decode; `part` says whether the `ok`/`error` envelope or the method's result failed, `failure` names kyo-schema's decode leaf (`Parse`, `MissingField`, ...), `path` the field it reports (a missing field's name included), and `position` where a parse stopped. It has no cause: kyo-schema's exceptions quote the body, and Slack's answer can hold a credential. An answer whose types are right and whose values are not is this failure too: an `auth.test` answer with an empty id, and an `apps.connections.open` answer without its url.
- `SlackLinkDisabledException`, on `run` and `receive`: the `link_disabled` end-of-link from the previous section.

A leaf's message names the method and what failed, never a token, a `response_url`, or the wrapped cause's message. Four leaves are never on a row, because they mark a programming mistake at the construction site and are raised as panics: `SlackInvalidRawBlockException`, `SlackInvalidConfigException`, `SlackInvalidTokenException`, and `SlackInvalidMethodException`.

## Putting it together

A single deploy-bot handler that covers the threads above: it greets on `Hello`, replies to mentions, answers `/deploy`, opens the rollback modal from a shortcut, and clears it on submission while the rollback runs on its own fiber. A mention in a channel the bot cannot post to is answered in #deploys instead, so that failure does not stop the bot; any other Web API failure ends it, with `E` inferred as `SlackException`. The loop is bound to a `val` before it is composed with the startup log, as the note under the first example requires:

```scala
import kyo.*

val deployBot = Slack.run(config)([A] =>
    (env: SlackEnvelope[A]) =>
        env match
            case SlackEnvelope.Hello(_, _, _) =>
                Slack.authTest.unit

            case SlackEnvelope.EventsApi(_, SlackEvent.AppMention(channel, _, _, _), _) =>
                Abort.recover[SlackChannelNotFoundException | SlackNotInChannelException] { _ =>
                    Slack.chatPostMessage(SlackMessage(deploysChannel, "Deploy status: staging is green")).unit
                } {
                    Slack.chatPostMessage(SlackMessage(channel, "Deploy status: staging is green")).unit
                }.andThen(SlackAck.Ack)

            case SlackEnvelope.SlashCommand(_, cmd) if cmd.command == "/deploy" =>
                SlackAck.CommandResponse(SlackReply(s"Deploying ${cmd.text}..."), SlackAck.CommandResponse.Visibility.InChannel)

            case SlackEnvelope.Interactive(_, SlackInteraction.Shortcut(_, triggerId, "rollback")) =>
                Slack.viewsOpen(triggerId, SlackView(SlackView.Type.Modal, blocks = rollbackBlocks))
                    .andThen(SlackAck.Ack)

            case SlackEnvelope.Interactive(_, SlackInteraction.ViewSubmission(_, _, _)) =>
                Fiber.initUnscoped(rollbackLastDeploy)
                    .andThen(SlackAck.ViewResponse(SlackAck.ViewAction.Clear))

            case _: SlackEnvelope.Acknowledged => SlackAck.Ack
            case _: SlackEnvelope.Plain        => Kyo.unit
)

val deployBotApp = Log.info("deploy bot starting").andThen(deployBot)
```

## What kyo-slack does not do

- **Events over HTTP.** Frames arrive only over the Socket Mode WebSocket. There is no endpoint for Slack's HTTP request URL delivery, and no signing-secret verification, which only that delivery needs.
- **App installation.** The module takes the tokens as given. It runs no OAuth installation flow, and it does not refresh a rotated `xoxe.xoxb-` token: when one expires, calls fail with `SlackTokenExpiredException`, and the app builds a new client with the new token.
- **Retrying.** A rate-limited call fails with `SlackRateLimitException` carrying Slack's `retryAfter`; waiting and calling again is the caller's choice.
- **Pagination.** Each operation is one request. A cursor-paginated method is called through `Slack.custom`, with the cursor in the request body the caller defines.
- **All of Block Kit.** `SlackBlock` models the common blocks and elements; any other block goes in as `SlackBlock.Raw`, its JSON validated when it is built.
