<!-- doctest:setup
```scala
import kyo.*

// The running domain: an incident bot in a team channel.
enum Severity derives Schema, CanEqual:
    case Low, High, Critical

case class Incident(
    id: String,
    title: String,
    severity: Severity,
    reference: Teams.ConversationReference,
    acknowledgedBy: Maybe[Teams.Account]
) derives Schema

// What the card's Acknowledge button carries back to the bot.
case class Ack(incident: String) derives Schema

val appId: Teams.AppId  = Teams.AppId.init("8a3c2b1d-5e6f-4a7b-9c8d-0e1f2a3b4c5d").getOrThrow
val config: TeamsConfig =
    TeamsConfig.init(appId, TeamsConfig.Credential.Secret(Teams.ClientSecret.init("app-password").getOrThrow)).getOrThrow
val webhook: TeamsWebhookConfig     = TeamsWebhookConfig.init("api/messages").getOrThrow
val cardVersion: Teams.Card.Version = Teams.Card.Version.init("1.5").getOrThrow

// Stand-ins for the bot's own storage and parsing.
def saveIncident(incident: Incident): Unit < Async    = ()
def loadIncident(id: String): Maybe[Incident] < Async = Absent
def forgetIncident(id: String): Unit < Async          = ()
def incidentTitle(text: String): Maybe[String]        =
    val at = text.indexOf("/incident ")
    if at < 0 then Absent else Present(text.substring(at + "/incident ".length).trim)
def readWorkloadToken: String < Async = "eyJhbGciOiJSUzI1NiJ9.e30.c2ln"
```
-->

# kyo-teams

A Teams bot has two halves, and kyo-teams gives each one a single entry point. Inbound, the Bot Connector posts Activities (a message, an edit, a reaction, a member joining, a card button press) to a webhook on the bot's own kyo-http server. `Teams.Webhook.handler` mounts that route. Before your code runs, it verifies the caller's signed token against Microsoft's published keys and checks that the token was issued for the channel and service URL the body names. It then decodes the body into a `Teams.Activity` and passes it to your function. Verification cannot be turned off. The Activity's type parameter is the answer the bot owes, so if you forget to answer a card action, or return an answer for an Activity that takes none, the code does not compile.

Outbound, the bot talks to Teams through verbs on the `Teams` companion: `send`, `reply`, `edit`, `delete`, `typing`, `createConversation`, `members`, plus `custom` for any route the module does not model. Each verb runs inside a region opened by `Teams.run(config)`, and the webhook is served inside one too, so both halves share one client and its cached token. The module gets the bot's access token, caches it, and attaches it to the request. It sends that token only to a service host on the config's allowlist.

What joins the two halves is the `Teams.ConversationReference`. Every Activity you receive carries one (`activity.reference`), and so does every conversation you create. Its JSON is the Bot Framework's own `ConversationReference`, so you can store it and send to that conversation later, after a restart. That is how proactive messages work.

Every failure is a typed leaf of `TeamsException`, and each verb's row names only the leaves that verb can actually return (`TeamsSendFailure`, `TeamsReplyFailure`, ...). The values Teams would reject (ids, credentials, service URLs, paths, card versions, page sizes, the config itself) are refused up front by an `init` that returns a `Result`. The module targets JVM, JavaScript, Scala Native and Wasm from one shared source set.

A bot that answers every message in its conversation:

```scala
val route = Teams.Webhook.handler(webhook) {
    [A] => (activity: Teams.Activity[A]) =>
        activity match
            case message: Teams.Activity.Message =>
                Teams.reply(message, Teams.Message.Create.text("Incident noted.")).unit
            case _: Teams.Activity.AdaptiveCardAction =>
                Teams.Card.ActionResponse.ShowMessage("Acknowledged.")
            case _: Teams.Activity.OtherInvoke =>
                Teams.InvokeResponse(HttpStatus.NotImplemented)
            case _: Teams.Activity.Plain => Kyo.unit
}
```

The examples below build an incident bot. A user posts `/incident db down` in a team channel. The bot answers with an Adaptive Card that has an Acknowledge button, stores the conversation's reference under the incident's id, swaps the card in place when someone acknowledges, and later posts the resolution from outside the webhook. [Putting it together](#putting-it-together) shows the whole bot in one place.

## Receiving Activities

The webhook is a kyo-http route. It refuses any delivery whose token fails verification, then hands your function an Activity typed by the answer it owes. This section builds the config, mounts the route, and writes the first handler.

### A config

Before the bot can verify a delivery or send a message, it needs to know who it is. A `TeamsConfig` holds the bot's Microsoft app id and the credential that obtains its outbound token. Every value is validated by an `init` that returns a `Result`, so a config is built as a `Result` too:

```scala
val built: Result[TeamsException, TeamsConfig] =
    for
        appId  <- Teams.AppId.init("8a3c2b1d-5e6f-4a7b-9c8d-0e1f2a3b4c5d")
        secret <- Teams.ClientSecret.init("app-password")
        config <- TeamsConfig.init(appId, TeamsConfig.Credential.Secret(secret))
    yield config
```

The app id is the audience every inbound token must name. `Credential.Secret` holds the client secret of the bot's app registration. Microsoft recommends a federated credential or a managed identity over a secret where the deployment allows one; [Credentials](#credentials) says which deployment takes which, and [Configuring the client](#configuring-the-client) covers the other settings, all of which have defaults.

The rest of this README uses a `config: TeamsConfig` built this way.

### Mounting the webhook

`Teams.Webhook.handler` takes a `TeamsWebhookConfig`, which holds the path the route is mounted at, and your function. It returns an `HttpHandler` for kyo-http's `HttpServer`, and it requires `Env[Teams]`, so the bot is served inside `Teams.run`:

```scala
val serving: Unit < (Async & Scope & Abort[HttpBindException]) =
    Teams.run(config) {
        Teams.Webhook.handler(webhook) {
            [A] => (activity: Teams.Activity[A]) =>
                activity match
                    case _: Teams.Activity.AdaptiveCardAction => Teams.Card.ActionResponse.ShowMessage("Not yet.")
                    case _: Teams.Activity.OtherInvoke        => Teams.InvokeResponse(HttpStatus.NotImplemented)
                    case _: Teams.Activity.Plain              => Kyo.unit
        }.map(handler => HttpServer.init(HttpServerConfig.default.port(3978))(handler).map(_.await))
    }
```

`webhook` is built with `TeamsWebhookConfig.init("api/messages")`. A leading slash is optional. A character no request path can carry (`?`, `#`, a space, anything outside ASCII) is refused with `TeamsInvalidWebhookConfigException`, since it would mount a route no request reaches. The config holds no key: the Bot Framework signs with keys the module fetches from its OpenID metadata, and the bounds of that verification are settings of `TeamsConfig`.

Every delivery is verified with the client `Teams.run` built (its HTTP pool, token cache and signing-key cache), and your function runs with that same client as its `Env[Teams]`. The region closes the client when it ends, so the server lives inside it, here until it stops.

The handler's type parameter `E` is what your function may fail with, and it is inferred. A failure of `f` that escapes it is answered with 500, so `E` is the set of failures you chose not to handle.

Each delivery gets one of these answers:

| Outcome | Status |
|---|---|
| The token does not verify (missing, malformed, expired, wrong issuer or audience, bad signature) | 401 |
| The signing key does not endorse the Activity's channel | 403 |
| The signing keys cannot be fetched or read | 503 |
| The body is not an Activity | 400 |
| Your function fails or panics | 500 |
| An invoke your function answered | 200, with the answer as JSON |
| Any other Activity | 200, no body |

The 503 tells the Bot Connector that the delivery may be genuine, so it may send it again.

> **Note:** No option skips verification. Microsoft's guidance is that "Implementers shouldn't expose a way to disable validation". The server's `maxContentLength` bounds the body before verification runs.

Verification reads the body as well as the `Authorization` header. The token binds two of the Activity's fields: its `channelId`, which the signing key must endorse and which must be `msteams`, and its `serviceUrl`, which must equal the token's `serviceUrl` claim exactly. A delivery from a Bot Framework channel other than Teams is refused with `TeamsUnsupportedChannelException`.

### The Activity type and the answer it owes

Your function has the type `[A] => Teams.Activity[A] => A < (Async & Abort[E] & Env[Teams])`. That is a Scala 3 polymorphic function: it works for every `A`, and `A` is the answer the Activity owes. `Teams.Activity[A]` is a sealed type whose cases fix `A`:

- `Teams.Activity.AdaptiveCardAction` is an `Activity[Teams.Card.ActionResponse]`: a user pressed an Adaptive Card's `Action.Execute` button, and the bot answers with the card or message to show next.
- `Teams.Activity.OtherInvoke` is an `Activity[Teams.InvokeResponse]`: an invoke the module does not model, answered with a raw status and body.
- Every other case is an `Activity[Unit]`, answered with 200 and no body. `Teams.Activity.Plain` is the union of those cases.

Matching on a case refines `A` inside that branch. In the `AdaptiveCardAction` branch the compiler knows `A` is a `Card.ActionResponse`, so returning `Kyo.unit` there is a type error, and so is returning a card response from a `Message` branch. `case _: Teams.Activity.Plain => Kyo.unit` is the fallback for everything answered with 200 alone, and the three cases above together make the match exhaustive.

`Teams.Activity.Answer` is the union `Unit | Card.ActionResponse | InvokeResponse`. The Activity's `Schema` is given at `Teams.Activity[Teams.Activity.Answer]`, which is the type `Teams.Webhook.decode` returns.

### What arrives

The modelled cases are `Message`, `MessageUpdate`, `MessageDelete`, `MessageReaction`, `ConversationUpdate`, `InstallationUpdate`, `Typing`, and the two invokes. Each holds the fields every Activity shares in `common` (`Teams.Activity.Common`):

| Field | Meaning |
|---|---|
| `id` | the Activity's `ActivityId` |
| `timestamp` | when Teams sent it, in UTC |
| `serviceUrl` | the Bot Connector base URL for this conversation |
| `channel` | the Activity's `channelId`, a `Teams.BotChannel` (`MsTeams`) |
| `sender` | its `from`, a `Teams.Account` |
| `conversation` | a `Teams.ConversationAccount`, with `conversationType` `Personal`, `GroupChat` or `Channel` |
| `recipient` | the bot's own `Teams.Account` |
| `locale` | the client's locale |
| `entities` | mentions (`Teams.Entity.Mention`) and other metadata |
| `channelData` | the Teams part: tenant, team, channel, and the event a `ConversationUpdate` reports |

> **Note:** `Teams.BotChannel` (the Activity's `channelId`, `MsTeams`) is not `Teams.ChannelId` (a channel of a team, in `channelData.channel.id`). `BotChannel.Other` appears only in a reference you built or stored, never in a verified delivery.

The incident bot reacts to two of them. A `Message` whose text holds `/incident` opens an incident:

```scala
def onMessage(message: Teams.Activity.Message): Unit < Async =
    message.text.flatMap(incidentTitle).map { title =>
        val id = message.common.id.value
        loadIncident(id).map {
            case Present(_) => Kyo.unit
            case Absent     => saveIncident(Incident(id, title, Severity.High, message.reference, Absent))
        }
    }.getOrElse(Kyo.unit)
```

A `ConversationUpdate` whose `membersAdded` includes the bot itself (an account with the id of `common.recipient`) means the bot was just added to a team, and the bot answers it with a welcome, shown in [Conversation verbs](#conversation-verbs).

In a channel, a message to the bot holds the mention as markup (`<at>IncidentBot</at> /incident db down`), and the mention itself is in `common.entities`. A `MessageReaction` names its reactions as `Teams.Reaction.Kind` (`Like`, `Heart`, `Laugh`, and so on) and the bot's message it reacted to in `replyToId`. Teams sends no content of that message.

An Activity type the module does not model arrives as `Teams.Activity.Unknown`, with its `type` and its whole JSON. Microsoft adds Activity types over time, and an unknown one is an `Activity[Unit]` like any other notification.

> **Caution:** Teams may deliver the same Activity more than once, and the module does not deduplicate for you. Deduplicate by `common.id`. The incident bot keys each incident by the id of the message that opened it, so a redelivered `/incident` message finds the incident already stored.

## Answering in a conversation

Every outbound verb takes a `ConversationReference`, and every received Activity gives you one. Replying to a message right now and posting to the same conversation tomorrow are the same call with the same kind of argument.

### The reference

To answer anything, a verb needs to know where: which service URL to call, which conversation, as which bot, and in reply to which Activity. `activity.reference` carries all of that: the `serviceUrl`, the `conversation`, the bot and the user, the Activity's id, and the channel. It is defined on `Teams.Activity.Addressed`, which every modelled case extends.

> **Note:** `Activity.Unknown` and `Activity.OtherInvoke` do not extend `Addressed`. They keep only their raw JSON, so there is no `reference` to answer them with, and `Teams.reply(activity, ...)` does not accept them.

### Composing a message

A plain reply needs only `Teams.Message.Create.text`, and a card reply `Create.card`. Paging someone needs more: a mention that notifies them, the `summary` a notification shows, and an `importance`. The full `Teams.Message.Create` constructor takes those, along with a `textFormat` and `attachments`:

```scala
def page(incident: Incident, onCall: Teams.Account): Teams.Message.Create =
    val tag = s"<at>${onCall.name.getOrElse("on-call")}</at>"
    Teams.Message.Create(
        text = Present(s"$tag please look at ${incident.title}"),
        entities = Chunk(Teams.Entity.Mention(onCall, Present(tag))),
        summary = Present(s"Incident: ${incident.title}"),
        importance = Present(Teams.Importance.High)
    )
end page
```

A mention needs both parts: the `<at>...</at>` markup in the text and a `Teams.Entity.Mention` naming the account with the same markup. The markup marks where the mention sits in the text, and the entity says which account it is, since a display name does not identify one; Teams matches the two by that text.

### Conversation verbs

Inside the handler, your function runs with the client of the `Teams.run` region serving it as its `Env[Teams]`, so the verbs need no client argument:

```scala
def acknowledgeReceipt(message: Teams.Activity.Message)
    : Unit < (Async & Abort[TeamsTypingFailure | TeamsReplyFailure] & Env[Teams]) =
    for
        _ <- Teams.typing(message.reference)
        _ <- Teams.reply(message, Teams.Message.Create.text("Opening an incident."))
    yield ()
```

`Teams.reply` has two forms. `reply(activity, message)` replies to a received Activity in its own conversation. `reply(ref, to, message)` replies to the Activity `to` in the conversation `ref` names. `Teams.send(ref, message)` posts at the end of the conversation instead. The welcome the incident bot posts when it is added to a team is a `send` to the `ConversationUpdate`'s own reference:

```scala doctest:scope=env:bot
def welcome(update: Teams.Activity.ConversationUpdate): Unit < (Async & Abort[TeamsSendFailure] & Env[Teams]) =
    if update.membersAdded.exists(_.id == update.common.recipient.id) then
        Teams.send(update.reference, Teams.Message.Create.text("Post /incident followed by a title to open an incident.")).unit
    else Kyo.unit
```

The row tracks the operation: `TeamsSendFailure` is the trait every failure `send` can return extends. `typing` fails with `TeamsTypingFailure`, `reply` with `TeamsReplyFailure`, and so on. [Handling failures](#handling-failures) covers what to do with each.

> **Note:** In a conversation without threads (a one-on-one or group chat), `reply` behaves like `send` and appends the message at the end.

`send` and `reply` answer the `ActivityId` Teams gave the new message. Keep it to change or remove that message later with `edit` and `delete`, which only work on the bot's own messages. The incident bot keeps the status message's `ActivityId` alongside the incident, and edits the same message as the investigation progresses rather than posting a new one each time:

```scala
def statusUpdate(incident: Incident, status: Teams.ActivityId): Unit < (Async & Abort[TeamsEditFailure] & Env[Teams]) =
    Teams.edit(incident.reference, status, Teams.Message.Create.text(s"Mitigated: ${incident.title}")).unit
```

Once the resolution card has replaced it, the status message adds nothing, so the bot deletes it:

```scala
def clearStatus(incident: Incident, status: Teams.ActivityId): Unit < (Async & Abort[TeamsDeleteFailure] & Env[Teams]) =
    Teams.delete(incident.reference, status).unit
```

## Adaptive Cards and invokes

The incident bot's main message is an Adaptive Card with an Acknowledge button. Cards are plain case classes. A button that uses `Action.Execute` comes back to the webhook as an invoke, and the type system requires the handler to answer it.

### Building a card

`Teams.Card` holds a `version`, the `body` elements, the `actions`, and an optional `refresh`. The elements are `TextBlock`, `Image`, `Container`, `ColumnSet` (of `Card.Column`), `FactSet` (of `Card.Fact`), `ActionSet`, and the inputs `InputText`, `InputNumber`, `InputDate`, `InputToggle` and `InputChoiceSet` (of `Card.Choice`). The actions are `Execute`, `Submit`, `OpenUrl`, `ShowCard` and `ToggleVisibility`. `TextBlock` takes the styles in `Card.Style` (`Size`, `Weight`, `Color`).

```scala doctest:scope=env:bot
def incidentCard(incident: Incident): Teams.Card =
    Teams.Card(
        version = cardVersion,
        body = Chunk(
            Teams.Card.Element.TextBlock(
                incident.title,
                size = Present(Teams.Card.Style.Size.Large),
                weight = Present(Teams.Card.Style.Weight.Bolder)
            ),
            Teams.Card.Element.FactSet(Chunk(
                Teams.Card.Fact("Severity", incident.severity.toString),
                Teams.Card.Fact("Acknowledged by", incident.acknowledgedBy.flatMap(_.name).getOrElse("nobody yet"))
            ))
        ),
        actions =
            if incident.acknowledgedBy.nonEmpty then Chunk.empty
            else
                Chunk(Teams.Card.Action.Execute(
                    title = Present("Acknowledge"),
                    verb = Present("ack"),
                    data = Present(Teams.RawJson(Structure.encode(Ack(incident.id))))
                ))
    )
```

`cardVersion` is a `Teams.Card.Version`, built with `Teams.Card.Version.init("1.5")`, which accepts `major.minor`. A card goes into a message with `Teams.Message.Create.card(incidentCard(incident))`.

> **Note:** Microsoft documents two minimum versions for `Action.Execute` and `refresh`: 1.4 in the Universal Action Model documentation and 1.5 in the Teams documentation. `Card` has no default version, so you choose one.

An action's `data` is a `Teams.RawJson`, a JSON value kept as is. Here it is built from `Ack` through `Structure.encode`, and read back the same way when the button is pressed.

### Answering Action.Execute

When a user presses the button, the webhook receives a `Teams.Activity.AdaptiveCardAction`. Its `value` is a `Request` holding the `action` as the card defined it (with its `verb` and `data`) and the `trigger`: `Trigger.Manual` for a click, `Trigger.Automatic` for a refresh. The answer is a `Teams.Card.ActionResponse`, the card or message the client shows next:

```scala doctest:scope=env:bot
def acknowledge(action: Teams.Activity.AdaptiveCardAction): Teams.Card.ActionResponse < Async =
    action.value.action.data.map(raw => Structure.decode[Ack](raw.json)) match
        case Present(Result.Success(ack)) =>
            loadIncident(ack.incident).map {
                case Present(incident) =>
                    val acked = incident.copy(acknowledgedBy = Present(action.common.sender))
                    saveIncident(acked).andThen(Teams.Card.ActionResponse.ShowCard(incidentCard(acked)))
                case Absent =>
                    Teams.Card.ActionResponse.ShowMessage("This incident is already closed.")
            }
        case _ =>
            Teams.Card.ActionResponse.BadRequest(Teams.Card.ActionResponse.Failure("BadData", "The button carried no incident."))
```

`ShowCard` replaces the card in place for the user who pressed it, so the Acknowledge button disappears and the facts show who acknowledged. The other answers are `ShowMessage`, `BadRequest` (400), `PreconditionFailed` (412, a failed single sign-on), and `LoginRequest` (401, with an OAuth card). The webhook writes each in a 200 reply with the `statusCode` and `type` the Universal Action Model gives it.

In the handler, this is the `AdaptiveCardAction` branch:

```scala doctest:scope=env:bot
val acking = Teams.Webhook.handler(webhook) {
    [A] => (activity: Teams.Activity[A]) =>
        activity match
            case action: Teams.Activity.AdaptiveCardAction => acknowledge(action)
            case _: Teams.Activity.OtherInvoke             => Teams.InvokeResponse(HttpStatus.NotImplemented)
            case _: Teams.Activity.Plain                   => Kyo.unit
}
```

### Refreshing a card

A card can refresh itself when certain users view it, so each of them sees an up-to-date version. `Teams.Card.Refresh.init(action, userIds)` takes the `Action.Execute` to run and at most 60 user ids (`Card.Refresh.MaxUsers`); more is a `TeamsInvalidCardException`. The refresh arrives as an `AdaptiveCardAction` whose trigger is `Trigger.Automatic`:

```scala
def withRefresh(card: Teams.Card, viewers: Chunk[Teams.UserId]): Result[TeamsInvalidCardException, Teams.Card] =
    Teams.Card.Refresh.init(Teams.Card.Action.Execute(verb = Present("refresh")), viewers)
        .map(refresh => card.copy(refresh = Present(refresh)))
```

### Action.Submit

`Action.Execute` is an invoke with a typed answer. The older `Action.Submit` is not: Teams posts the card's input values as an ordinary `Message` whose `value` holds them. This is Adaptive Cards' own `Action.Submit` behavior: it gathers the input fields and merges them with the button's `data`, as long as `data` is a JSON object (a `data` that is not an object is sent without the inputs merged in). A form that adds a note to an incident carries the incident's id in `data` as a `NoteTarget` and the note in an `InputText`, and the bot decodes both into one `Note` record:

```scala
case class NoteTarget(incident: String) derives Schema
case class Note(incident: String, note: String) derives Schema

def noteForm(incident: Incident): Chunk[Teams.Card.Element] = Chunk(
    Teams.Card.Element.InputText("note", label = Present("Add a note"), isMultiline = Present(true)),
    Teams.Card.Element.ActionSet(Chunk(Teams.Card.Action.Submit(
        title = Present("Save"),
        data = Present(Teams.RawJson(Structure.encode(NoteTarget(incident.id))))
    )))
)

def submittedNote(message: Teams.Activity.Message): Maybe[Note] =
    message.value.flatMap(raw => Structure.decode[Note](raw.json).toMaybe)
```

This is the same round trip as `Action.Execute`: `RawJson.json` is the `Structure.Value` tree to decode with `Structure.decode`. `RawJson.value` is the same JSON as text.

### Other invokes

Message extensions, dialogs and sign-in steps arrive as `Teams.Activity.OtherInvoke`, with the invoke's `name` and its whole JSON as `payload`. The answer is a `Teams.InvokeResponse`: an HTTP status and an optional JSON body, as that invoke's own documentation gives them. `InvokeResponse.ok(body)` is status 200 with a body:

```scala
def otherInvoke(invoke: Teams.Activity.OtherInvoke): Teams.InvokeResponse =
    if invoke.name == "composeExtension/query" then Teams.InvokeResponse.ok(invoke.payload)
    else Teams.InvokeResponse(HttpStatus.NotImplemented)
```

> **Note:** `InvokeResponse` is the HTTP status and body of the reply, not a JSON document. The webhook writes `status` as the reply's status line and `body` as its body.

## Proactive messaging

A bot speaks first when an incident resolves, a timer fires, or someone else's system calls it. No Activity is in hand at that point, so the bot needs a reference it stored earlier and a client of its own.

### Storing a reference

To post the resolution after a restart, the bot must keep the incident's reference somewhere that survives one. `Teams.ConversationReference` has a `Schema` whose JSON is the Bot Framework's `ConversationReference`, so a record holding one derives a `Schema` as usual. `Incident` does exactly that, and stores the reference of the message that opened it:

```scala
def persist(incident: Incident): String =
    Json.encode(incident)

def restore(json: String): Result[DecodeException, Incident] =
    Json.decode[Incident](json)
```

A reference decoded from storage carries its `serviceUrl`, which is where the token will be sent. Every verb checks that URL against the config's allowlist before attaching the token (see [Service hosts](#service-hosts-and-sovereign-clouds)).

### A client outside the handler

Code outside the webhook (a scheduler, a callback from the monitoring system) has no `Env[Teams]` unless it runs inside the region that serves the webhook. `Teams.run(config)` opens a region that provides one to the verbs, and closes the client when the region ends. Nothing is fetched until the first verb needs a token:

```scala
def resolve(id: String): Unit < (Async & Abort[TeamsSendFailure]) =
    Teams.run(config) {
        loadIncident(id).map {
            case Present(incident) =>
                Teams.send(incident.reference, Teams.Message.Create.text(s"Resolved: ${incident.title}")).unit
            case Absent => Kyo.unit
        }
    }
```

> **Note:** Each `Teams.run` region builds its own client, with its own HTTP pool, token cache and key cache, so two regions do not share a token. Run the bot's proactive work inside the region that serves the webhook, or in one long-lived region of its own, rather than one region per message.

When the client should outlive one region, hold it as a value. `Teams.init(config)` builds one for the enclosing `Scope` and closes it when the scope ends, and `Teams.run(client)` provides it to a computation, as often as needed; every run on it shares its token cache and key cache. `Teams.run(config)` is exactly `init` and `run(client)` in one `Scope`:

```scala
def page(reference: Teams.ConversationReference): Unit < (Async & Scope & Abort[TeamsSendFailure]) =
    Teams.init(config).map { teams =>
        Teams.run(teams)(Teams.send(reference, Teams.Message.Create.text("Paging the on-call engineer."))).andThen(
            Teams.run(teams)(Teams.send(reference, Teams.Message.Create.text("Escalating.")))
        ).unit
    }
```

A client whose lifetime no `Scope` describes comes from `Teams.initUnscoped(config)` and is closed with `Teams.close(client)`, which closes its HTTP client without waiting for requests in flight. `close` is idempotent, and a verb on a closed client fails with `TeamsTransportException`.

### Finding whom to address

To page the on-call engineer, the bot first needs their `Teams.Account`, and the conversation's roster has it. `Teams.members(ref)` reads the first page of 200 members, and `Teams.member(ref, user)` reads one member by `UserId`. A page request is a `Teams.Member.Page`. `Member.Page.first` is the default page, and `Member.Page.init(size, continuation)` asks for `size` members (50 to 500, the bounds Teams documents) after a continuation token. The answer is a `Teams.Member.Paged` holding `members` and, when there are more, `continuationToken`:

```scala
def roster(
    ref: Teams.ConversationReference,
    page: Teams.Member.Page
): Chunk[Teams.Account] < (Async & Abort[TeamsMembersFailure | TeamsInvalidPageException] & Env[Teams]) =
    Teams.members(ref, page).map { paged =>
        paged.continuationToken match
            case Absent         => paged.members
            case Present(token) =>
                Abort.get(Teams.Member.Page.init(Teams.Member.Page.MaxSize, Present(token)))
                    .map(next => roster(ref, next).map(paged.members ++ _))
    }
```

`Member.Page` has a private constructor, so an out-of-range size is a `TeamsInvalidPageException` from `init` rather than a 400 from Teams.

### Starting a conversation

To page the on-call engineer directly, the bot starts a one-on-one chat. `Teams.createConversation(serviceUrl, create)` takes a `Teams.Conversation.Create` (the Bot Framework's `ConversationParameters`: the bot, the members, the tenant, optional `channelData` and first `activity`) and answers the new conversation's reference, ready for `send`:

```scala
def pageDirectly(
    incident: Incident,
    onCall: Teams.Account
): Unit < (Async & Abort[TeamsCreateConversationFailure | TeamsSendFailure] & Env[Teams]) =
    val create = Teams.Conversation.Create(
        bot = incident.reference.bot,
        members = Chunk(onCall),
        tenantId = incident.reference.conversation.tenantId
    )
    Teams.createConversation(incident.reference.serviceUrl, create).map { direct =>
        Teams.send(direct, Teams.Message.Create.text(s"You are paged for ${incident.title}")).unit
    }
end pageDirectly
```

For a new thread in a team channel, set `isGroup`, put the channel in `channelData`, and pass the first message as `activity`.

## Handling failures

A failed call needs a decision: forget the incident, wait and try again, fix the deployment, or report a bug. Every failure is a leaf of the sealed `TeamsException`, and each operation has its own failure trait (`TeamsSendFailure`, `TeamsReplyFailure`, `TeamsMembersFailure`, `TeamsWebhookVerifyFailure`, ...). A leaf extends the trait of every operation that can produce it, so a verb's row names exactly what you can meet, and a match on it covers the situations that verb can actually be in.

### When the conversation is gone

A stored reference can outlive its conversation: the channel was deleted, or the bot was removed from the team. `send` then fails with `TeamsConversationNotFoundException`, and the bot should forget the incident instead of retrying forever. `Abort.run` with the operation's trait gives a `Result` to match on:

```scala
def announce(incident: Incident): Unit < (Async & Abort[TeamsSendFailure] & Env[Teams]) =
    Abort.run[TeamsSendFailure](Teams.send(incident.reference, Teams.Message.Create.text(s"Still open: ${incident.title}"))).map {
        case Result.Success(_)                                     => Kyo.unit
        case Result.Failure(_: TeamsConversationNotFoundException) => forgetIncident(incident.id)
        case Result.Failure(other)                                 => Abort.fail(other)
        case Result.Panic(ex)                                      => Abort.panic(ex)
    }
```

The Bot Connector answers a refused call with an `ErrorResponse` body, which becomes a `TeamsApiException` leaf holding the `method`, `status`, `code`, `description` and `operationId` (the correlation id Microsoft support asks for). The leaves group by what the bot does next:

| Leaf | Meaning | What to do |
|---|---|---|
| `TeamsConversationNotFoundException`, `TeamsBotNotInConversationException`, `TeamsNotInstalledException` | the conversation is gone, or the bot is no longer in it or installed for it | forget the stored reference |
| `TeamsConversationBlockedByUserException`, `TeamsMessageWritesBlockedException` | the user blocked, muted or uninstalled the bot | stop writing to that user |
| `TeamsActivityNotFoundException` | the message to reply to, update or delete no longer exists | forget its `ActivityId` |
| `TeamsPreconditionFailedException` | a concurrent operation on the conversation conflicted | send again; [Retry](#retry) does this for you when `TeamsConfig.retry` is set (`Absent` by default) |
| `TeamsBadArgumentException`, `TeamsMessageTooLargeException` | the request as built is not one the Bot Connector accepts | change the message; sending it again fails the same way |
| `TeamsBotNotRegisteredException`, `TeamsBotDisabledByAdminException`, `TeamsNotEnoughPermissionsException`, `TeamsInvalidBotApiHostException` | the registration, the tenant's administrator, or the service URL does not allow the call | fix the deployment; no retry succeeds |
| `TeamsOtherApiException` | any other code, kept with its status, code and description | read `code` and `description` |

> **Note:** A named leaf appears only on the operations that can receive it. The same code on any other operation becomes `TeamsOtherApiException`. A `BadArgument` from `typing`, for example, arrives as `TeamsOtherApiException`.

### When the bot is throttled or the network fails

A bot that posts in bursts meets a 429, and the leaf is `TeamsRateLimitException` with the `retryAfter` delay Teams asked for. Nothing is retried unless the config has a `retry` schedule; [Retry](#retry) covers it, and covers the 502, 503 and 504 answers too.

A failure before any answer is a `TeamsTransportException`, whose `kind` says how (`Connect`, `Dns`, `Tls`, `ConnectTimeout`, `Timeout`, `Protocol`, `ConnectionClosed`, `PoolExhausted`, `PayloadTooLarge`), with the `host`, `port` and the `timeout` that ran out. Two leaves mean an answer arrived that the module could not read: `TeamsUnexpectedStatusException` is a non-2xx answer with none of the error bodies Microsoft documents (an HTML page from a proxy), and `TeamsDecodeException` is an answer that did not decode, with the `part` (`Response`, `ErrorBody`, `Token`, `Metadata`, `Keys`), kyo-schema's `failure`, its `path` and `position`.

`TeamsRefusedUrlException` means the module sent nothing: the service URL's origin is not on the config's allowlist. If the host is one you trust, such as a test peer, list it in [`serviceHosts`](#service-hosts-and-sovereign-clouds).

### When the credential is refused

A wrong app id, secret or tenant shows up on the first verb, as `TeamsTokenRejectedException`: the identity platform refused the token request. It holds the RFC 6749 `code` (`InvalidClient`, `InvalidGrant`, ...), the `errorCodes`, and the `traceId` and `correlationId` Microsoft support asks for. `TeamsUnsupportedTokenTypeException` is a token whose type is not `Bearer`. A federated credential whose assertion could not be obtained is `TeamsCredentialException`, holding the `cause` your assertion computation reported (see [Credentials](#credentials)). Every verb's trait includes all three, and none is retried.

### When a delivery is refused

The webhook answers a delivery it refuses with the status in [the table under Mounting the webhook](#mounting-the-webhook). When you serve the route yourself, `Teams.Webhook.verify` fails with a `TeamsAuthenticationException` leaf that names the check that failed: a missing header or a malformed token (`TeamsMalformedTokenException`, with a `Problem` naming the part or claim), a wrong issuer or audience, an expired token, an unknown key or a signature mismatch, a missing endorsement, a `serviceUrl` that does not match its claim, or a channel other than Teams.

On genuine traffic, two of them point at the config rather than at an attacker. `TeamsWrongAudienceException` means the config's `appId` is not the registration the Bot Connector delivers for. `TeamsWrongIssuerException` or `TeamsUnknownKeyException` from a sovereign cloud or a test peer means `issuer` or `openIdMetadataUrl` still name the public cloud.

A body that is not an Activity is a `TeamsWebhookDecodeException`. It extends `TeamsWebhookVerifyFailure` as well as `TeamsWebhookDecodeFailure`, because verification reads the body's `channelId` and `serviceUrl` too.

### When a value is refused

A value Teams would reject never reaches Teams: its `init` refuses it with a leaf naming what is wrong through a `Problem` enum. `TeamsInvalidConfigException` covers the config, `TeamsInvalidWebhookConfigException` the webhook's, `TeamsInvalidTokenException` a credential, `TeamsInvalidIdException` an id, and `TeamsInvalidServiceUrlException`, `TeamsInvalidPathException`, `TeamsInvalidCardException` and `TeamsInvalidPageException` the rest. `TeamsConfig.init` names the first unusable setting, in declaration order.

Teams assigns every id and documents no length for one, so each id type refuses only an empty value. The ids are opaque types, so one kind cannot be passed where another is expected: `Teams.AppId`, `TenantId`, `ConversationId`, `ActivityId`, `UserId`, `ChannelId`, `TeamId` and `AadObjectId`. Each has `init`, `value`, a `Schema` and `CanEqual`.

Refusals are values, not effects, so they are easy to assert on:

```scala doctest:expect=runs
val conversation = Teams.ConversationId.init("19:abc@thread.skype")
assert(conversation.map(_.value) == Result.succeed("19:abc@thread.skype"))

val empty = Teams.UserId.init("")
assert(empty == Result.fail(TeamsInvalidIdException(TeamsInvalidIdException.Id.User, TeamsInvalidIdException.Problem.Empty)))

val bigPage = Teams.Member.Page.init(1000)
assert(bigPage == Result.fail(TeamsInvalidPageException(TeamsInvalidPageException.Problem.Size(1000, 50, 500))))

val noHosts = TeamsConfig.init(appId, config.credential, serviceHosts = Chunk.empty)
assert(noHosts == Result.fail(TeamsInvalidConfigException(TeamsInvalidConfigException.Problem.NoServiceHosts)))
```

### Logging a failure safely

A failure can go to a log as it is: no leaf holds a credential. The client secret travels only in a token request's body, and a managed identity's header and the access token only in a header. Text kept from Microsoft (an API leaf's `description`) has the access token and the credential's text replaced by `<redacted>`. The decode leaves name the failure, its path and its position, and quote nothing from the input, since an answer can hold a token or a user's text.

The credential types render as `<redacted>` too, so a rendered `TeamsConfig` never shows a secret:

```scala doctest:expect=runs
val secret = Teams.ClientSecret.init("app-password").getOrThrow
assert(secret.toString == "Teams.ClientSecret(<redacted>)")
```

`Teams.ClientAssertion` and `Teams.IdentityHeader` behave the same way, and `Teams.RawJson` renders as `Teams.RawJson(<redacted>)`, because it can hold what a user wrote. A credential's `Schema` does write the secret text, so encoding one is a deliberate act.

## Configuring the client

A bot that leaves Azure's public cloud, runs without a stored secret, posts in bursts, or is tested against a local peer needs settings other than the defaults. `TeamsConfig.init` takes every such setting, with a default, after the app id and the credential. Each one describes either the Microsoft cloud the bot talks to or a bound on that conversation. The module never inherits kyo-http settings from the caller: every request runs on the module's own `HttpClient`, built from `tls` and `transport`, so no client filter, `withConfig`, redirect or shared connection of yours reaches a request that carries a credential.

### Credentials

The credential follows from where the bot runs. On App Service or Azure Functions with a user-assigned managed identity, use `Credential.ManagedIdentity`: there is no secret to store or rotate. It speaks only the App Service and Azure Functions endpoint protocol, not the VM or AKS instance metadata endpoint, so a bot running as an AKS workload needs a different credential. Use `Credential.Federated` for a workload identity the app registration trusts, such as AKS workload identity or a CI job that posts to Teams. Use `Credential.Secret` where neither is available, such as on a developer's machine.

`Credential.Federated` obtains a `Teams.ClientAssertion` (a signed token from that identity provider) by running a computation before each token request, so a short-lived assertion is always fresh. The computation may fail only with `TeamsCredentialException`, which every outbound verb's failure trait includes, so it maps its own failure into one, passing it as the `cause`:

```scala
val federated: Result[TeamsInvalidConfigException, TeamsConfig] =
    TeamsConfig.init(
        appId,
        TeamsConfig.Credential.Federated(
            readWorkloadToken.map(token =>
                Abort.get(Teams.ClientAssertion.init(token).mapFailure(failure => TeamsCredentialException(failure)))
            )
        )
    )

def notify(cfg: TeamsConfig, incident: Incident): Teams.ActivityId < (Async & Abort[TeamsSendFailure]) =
    Teams.run(cfg)(Teams.send(incident.reference, Teams.Message.Create.text("Paged.")))
```

The `cause` is a `Throwable` or a `String`. A `Throwable` cause is the leaf's `getCause`, and its message names only the cause's class. A `String` cause is kept in the message, bounded to 200 printable ASCII characters.

`Credential.ManagedIdentity(endpoint, header)` uses the token endpoint App Service and Azure Functions run for a user-assigned managed identity: the `IDENTITY_ENDPOINT` the platform sets, and the `IDENTITY_HEADER` value as a `Teams.IdentityHeader`. The identity's client id is the config's `appId`.

```scala
val managed: Result[TeamsException | HttpException, TeamsConfig] =
    for
        endpoint <- HttpUrl.parse("http://localhost:42356/msi/token")
        header   <- Teams.IdentityHeader.init("value-of-IDENTITY_HEADER")
        cfg      <- TeamsConfig.init(appId, TeamsConfig.Credential.ManagedIdentity(endpoint, header))
    yield cfg
```

`tenant` picks which tenant issues the token: `Tenant.MultiTenant` (the default, the Bot Framework's own) for a multitenant registration, or `Tenant.SingleTenant(tenantId)` for a single-tenant one.

### Service hosts and sovereign clouds

A bot in a sovereign cloud, or one tested against a local peer, calls a host the default allowlist may not hold. `serviceHosts` lists the origins the token may be sent to. The default, `TeamsConfig.ServiceHosts`, is the https origins Microsoft documents for the public cloud, GCC, GCC High and DoD. A call whose service URL has any other origin fails with `TeamsRefusedUrlException` before a token is attached, because anyone holding the token may act as the bot. Matching compares scheme, host and exact port, so a local test peer or another cloud has to be listed explicitly:

```scala
val withLocalPeer: Result[TeamsException | HttpException, TeamsConfig] =
    for
        peer <- HttpUrl.parse("http://localhost:3979/")
        cfg  <- TeamsConfig.init(appId, config.credential, serviceHosts = TeamsConfig.ServiceHosts :+ peer)
    yield cfg
```

A plain-http origin is used only when you list it.

> **Note:** `Teams.ServiceUrl.init` and its `Schema` check only the URL's shape: absolute http or https, a host, and no user info, query, fragment or Unix socket. A well-formed URL can still be one the module refuses. A stored reference therefore decodes fine and fails at the verb if its host is not on the allowlist.

`loginUrl`, `scope`, `openIdMetadataUrl` and `issuer` default to the public cloud's values (`TeamsConfig.LoginServer`, `BotFrameworkScope`, `OpenIdMetadata`, `BotFrameworkIssuer`). A sovereign cloud or a local test peer replaces them with its own.

### Retry

A bot that posts in bursts will be throttled, and a busy conversation answers some calls with a conflict. `retry` is `Absent` by default, so a failure reaches you as it happened. Given a `Schedule`, a Bot Connector call answered 412, 429, 502, 503 or 504 (the statuses Microsoft says to retry) is sent again under it:

```scala
val retrying: Result[TeamsInvalidConfigException, TeamsConfig] =
    TeamsConfig.init(
        appId,
        config.credential,
        retry = Present(Schedule.exponentialBackoff(1.second, 2.0, 30.seconds).take(5)),
        retryMaxDelay = 60.seconds
    )
```

Each wait is the longer of the schedule's delay and the answer's `Retry-After`, capped at `retryMaxDelay`. When `Retry-After` asks for more than `retryMaxDelay`, the call fails right away with `TeamsRateLimitException` carrying the requested delay. Token requests and key fetches are never retried.

### Token and key caching

The bot needs no token handling of its own. The outbound token is fetched on the first verb that needs it, shared by concurrent callers, and renewed `tokenRefreshMargin` (5 minutes) before it expires. A failed fetch caches nothing, so the next call tries again.

The webhook's signing keys are refetched after `keysMaxAge` (24 hours). An inbound token signed by an unknown key id also triggers a fetch, but only once `keysMinRefresh` (1 hour) has passed since the last one. Before then the unknown key fails without a request, so a burst of forged tokens cannot cause fetches. A failed fetch keeps the previous key set. `clockSkew` (5 minutes) widens an inbound token's validity on both sides, and `maxTokenLength` bounds a token before it is parsed.

### Limits and transport

A slow network, a large roster, or a proxy between the bot and Microsoft may need bounds or a transport other than the defaults. `requestTimeout` and `connectTimeout` (10 seconds each) bound each request. `maxResponseLength` and `keysMaxResponseLength` (4 MiB each) bound the bodies the module reads; kyo-http's limit is an `Int`, so zero reads as 1 byte and a size past `Int.MaxValue` bytes as `Int.MaxValue`. `tls` and `transport` are the `HttpTlsConfig` and `HttpTransportConfig` of the module's own client.

## Escape hatches

The Bot Framework is larger than what the module models, and Microsoft extends it over time. Every unmodelled part stays reachable as raw JSON or a raw route.

### Unmodelled routes

`Teams.custom` calls any Bot Connector route under a service URL with the bot's token. The path is a `Teams.Path` of segments, built with `Teams.Path.init`, and the method a `Teams.Method` (`Get`, `Post`, `Put`, `Patch`, `Delete`). This reads the members who received one activity:

```scala
def recipients(
    ref: Teams.ConversationReference,
    activity: Teams.ActivityId
): Chunk[Teams.Account] < (Async & Abort[TeamsCustomFailure | TeamsInvalidPathException] & Env[Teams]) =
    Abort.get(Teams.Path.init("v3", "conversations", ref.conversation.id.value, "activities", activity.value, "members"))
        .map(path => Teams.custom[Unit, Chunk[Teams.Account]](ref.serviceUrl, Teams.Method.Get, path))
```

Teams ids hold `:`, `@` and `;`, so each segment is percent-encoded and no segment can add another segment, a query or a fragment. `init` refuses an empty path, an empty segment, and `.` or `..`.

> **Note:** `custom` fails only with the leaves that mean the same thing on every route. A route-specific code such as `ConversationNotFound` arrives as `TeamsOtherApiException`. An empty 2xx answer is decoded as JSON `null`, so `Out` must accept `null`.

### Unmodelled JSON

Every sum type has a catch-all case that keeps what the module does not model: `Activity.Unknown` and `Activity.OtherInvoke`, `Entity.Other`, `Attachment.Other`, `Card.Element.Other` and `Card.Action.Other` hold the whole JSON as a `Teams.RawJson`, and the enums of names (`TextFormat`, `Importance`, `Reaction.Kind`, `ChannelData.Event`, ...) have an `Other(name)`. Each encodes back to what Teams sent, so a card element the module does not model can be built from its JSON:

```scala
def badge(text: String): Result[DecodeException, Teams.Card.Element] =
    Json.decode[Teams.Card.Element](s"""{"type":"Badge","text":"$text"}""")
```

### Serving the route yourself

`Teams.Webhook.verify` and `Teams.Webhook.decode` are the handler's two steps. Serve the route yourself when the bot's HTTP server is not kyo-http's `HttpServer`, or when the bot must hold the verified raw body before decoding it, for example to queue it for another process. `verify` needs a client in the environment for the signing keys, which a `Teams.run` region provides:

```scala
def accept(
    authorization: Maybe[String],
    body: Span[Byte]
): Teams.Activity[Teams.Activity.Answer] <
    (Async & Abort[TeamsWebhookVerifyFailure | TeamsWebhookDecodeFailure] & Env[Teams]) =
    Teams.Webhook.verify(authorization, body).andThen(Teams.Webhook.decode(body))
```

> **Caution:** `decode` does no verification. Call it only on a body `verify` accepted.

## Putting it together

The incident bot in one place, built from the pieces above: `welcome` from [Conversation verbs](#conversation-verbs), and `incidentCard` and `acknowledge` from [Adaptive Cards and invokes](#adaptive-cards-and-invokes). The webhook welcomes the team when the bot is added, opens incidents from `/incident` messages by replying with the card, and answers the Acknowledge button by swapping the card. A separate `Teams.run` region posts the resolution later from the stored reference, and forgets an incident whose conversation is gone.

```scala doctest:scope=env:bot
def open(message: Teams.Activity.Message, title: String): Unit < (Async & Abort[TeamsReplyFailure] & Env[Teams]) =
    val id = message.common.id.value
    loadIncident(id).map {
        case Present(_) => Kyo.unit // a redelivery of a message already handled
        case Absent     =>
            val incident = Incident(id, title, Severity.High, message.reference, Absent)
            saveIncident(incident).andThen(Teams.reply(message, Teams.Message.Create.card(incidentCard(incident))).unit)
    }
end open

val bot: Unit < (Async & Scope & Abort[HttpBindException]) =
    Teams.run(config) {
        Teams.Webhook.handler(webhook) {
            [A] => (activity: Teams.Activity[A]) =>
                activity match
                    case message: Teams.Activity.Message =>
                        message.text.flatMap(incidentTitle).map(title => open(message, title)).getOrElse(Kyo.unit)
                    case update: Teams.Activity.ConversationUpdate => welcome(update)
                    case action: Teams.Activity.AdaptiveCardAction => acknowledge(action)
                    case _: Teams.Activity.OtherInvoke             => Teams.InvokeResponse(HttpStatus.NotImplemented)
                    case _: Teams.Activity.Plain                   => Kyo.unit
        }.map(handler => HttpServer.init(HttpServerConfig.default.port(3978))(handler).map(_.await))
    }

def resolved(id: String): Unit < (Async & Abort[TeamsSendFailure]) =
    Teams.run(config) {
        loadIncident(id).map {
            case Present(incident) =>
                Abort.run[TeamsSendFailure](Teams.send(incident.reference, Teams.Message.Create.text(s"Resolved: ${incident.title}")))
                    .map {
                        case Result.Failure(_: TeamsConversationNotFoundException) => Kyo.unit
                        case result                                                => Abort.get(result).unit
                    }
                    .andThen(forgetIncident(id))
            case Absent => Kyo.unit
        }
    }
```

## What kyo-teams does not do

- **Certificate credentials.** Microsoft's identity platform also accepts a client assertion signed with a certificate the app registration holds. The module does not support one, since it would need RSA signing, which kyo-crypto does not provide. A deployment that would use a certificate uses `Credential.Federated` or `Credential.ManagedIdentity` instead, the two the module supports that Microsoft recommends over a secret.
- **Other Bot Framework channels.** A registration can enable Web Chat, Direct Line and others, but the webhook accepts only deliveries whose channel is Teams and refuses the rest with `TeamsUnsupportedChannelException`.
- **Typed file attachments.** An attachment other than an Adaptive Card, a file a user shared included, arrives as `Teams.Attachment.Other` with its content type and JSON, and a message can send one the same way.

## Platform setup

The module builds for JVM, JavaScript, Scala Native and Wasm from one shared source set, with no platform-specific API. For JVM, JavaScript and Native, the platform setup is kyo-http's: on JavaScript the server needs a Node.js runtime, and a Scala Native build needs the kyo FFI plugin and OpenSSL, as described in [kyo-http's Cross-Platform section](../kyo-http/README.md#cross-platform). Wasm runs on the same Node.js runtime as JavaScript, built with support for the WebAssembly exception-handling opcodes the Wasm backend emits.
