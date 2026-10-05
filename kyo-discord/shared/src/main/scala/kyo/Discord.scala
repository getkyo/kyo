package kyo

import kyo.crypto.Ed25519
import kyo.internal.discord.Dispatch
import kyo.internal.discord.Gateway
import kyo.internal.discord.RateLimits
import kyo.internal.discord.Rest
import kyo.internal.discord.Session
import kyo.internal.discord.Signature
import kyo.internal.discord.WireField

/** A client of Discord's bot API: the REST API, the Gateway's event stream, and the interactions endpoint.
  *
  * `Discord.run(config)(v)` builds a client for the duration of `v` and releases its HTTP client afterwards. `init` builds one holding
  * a Gateway session, closed with the enclosing `Scope`, `initUnscoped` one closed only by `close`, and `run(client)(v)` provides a built
  * client to `v`; the two `run`s are the only methods that provide `Env[Discord]`. `receive(handler)` handles the provided client's
  * Gateway events. Every verb is a function on the companion that takes the client from `Env[Discord]` and fails with its own trait,
  * such as [[kyo.DiscordSendFailure]]; the client value has no public methods.
  *
  * The companion holds every public type of the module other than the configs and the exceptions: the ids, the secrets, the
  * application's public key and the path `custom` takes. `import Discord.*` brings the short names into scope. Every value Discord
  * bounds is built by `init`, which returns a `Result` naming what is wrong instead of throwing; ids are built from a `Long` unchecked,
  * since every 64-bit value is a snowflake, and from text with `parse`.
  *
  * Each opaque type is declared in an object of its own, the one scope where it is transparent. Declared in the companion, it would be
  * transparent across the whole facade, and `Schema.derived` of a record there would then lose its field list and encode renames it
  * cannot decode.
  *
  * IMPORTANT: the bot token travels in every request's headers and an interaction's token in the path of its routes. The client's HTTP
  * client is its own, with a complete configuration: no redirect is followed, no filter runs, TLS and transport come from the config,
  * and nothing of the caller's kyo-http configuration applies. No failure of this module holds either token.
  *
  * @see
  *   [[kyo.DiscordConfig]] what a client is built from
  * @see
  *   [[kyo.DiscordException]] the failures
  */
final class Discord private[kyo] (
    private[kyo] val config: DiscordConfig,
    private[kyo] val http: HttpClient,
    private[kyo] val limits: RateLimits,
    private[kyo] val application: AtomicRef[Maybe[Discord.ApplicationId]],
    // Present on a client built by `init`, which holds a Gateway session; `receive` on any other opens its own.
    private[kyo] val session: Maybe[Session]
)

object Discord:

    // --- Regions ---

    /** Builds a client from `config` for the duration of `v`, closing its HTTP client afterwards. It holds no Gateway connection: a
      * `receive` inside `v` opens one for its own duration.
      */
    def run[A, S](config: DiscordConfig)(v: A < (S & Env[Discord]))(using Frame): A < (S & Async) =
        Scope.run(client(config).map(discord => run(discord)(v)))

    /** Provides `client` to `v`. The client stays open; it is closed by its `Scope` (`init`) or by `close` (`initUnscoped`). */
    def run[A, S](client: Discord)(v: A < (S & Env[Discord]))(using Frame): A < S =
        Env.run(client)(v)

    /** A client holding a Gateway session, returned once the session reached Ready and closed when the enclosing `Scope` ends. Events
      * that arrive before `receive` runs wait for it, Ready first.
      */
    def init(config: DiscordConfig)(using Frame): Discord < (Async & Abort[DiscordInitFailure] & Scope) =
        Scope.acquireRelease(initUnscoped(config))(close)

    /** A client holding a Gateway session, which nothing closes but the caller's `close`. Prefer `init`. */
    def initUnscoped(config: DiscordConfig)(using Frame): Discord < (Async & Abort[DiscordInitFailure]) =
        open(config)

    /** Closes `client`: its Gateway session with 1000, then its HTTP client, without waiting for requests in flight. Idempotent. */
    def close(client: Discord)(using Frame): Unit < Async =
        client.session.fold(Kyo.unit)(_.close).andThen(client.limits.close).andThen(client.http.closeNow)

    /** Handles the events of the provided client's Gateway session with `handler` until a failure, the handler's failure, or
      * interruption ends it. On a client built by `init` it runs on the held session, which stays the caller's to close; on one built by
      * `run(config)` it opens a session and closes it with 1000 when it ends. Under `DiscordConfig.Reconnect.Off` it also ends,
      * cleanly, on a close it would otherwise have reconnected from. `Discord.run(config)(Discord.receive(handler))` is a bot in one
      * call.
      *
      * Each event is handled on its own fiber, with the client provided, so the handler calls the verbs directly; order between
      * handlers is not kept. A dispatch is answered with `Unit`. An interaction is answered with what its kind admits, which the module
      * posts as the callback, bounded by `config.interactionDeadline`: past it the handler is interrupted and nothing is posted. Long
      * work answers `DeferredMessage` at once and forks the work, which then calls `editResponse` or `followUp`. A handler names the
      * events it handles and ends with `case other => Event.unhandled(other)`, which declines the interactions it did not name.
      *
      * The handler's typed failure ends `receive` with it and interrupts the other handlers; [[kyo.Discord.Event.Decline]] is not a
      * failure but the choice to leave an interaction unanswered. A panic is logged with the event's type and sequence and the loop goes
      * on.
      */
    // The handler's row has no open effect parameter. Kyo's `Abort` is contravariant, so with one the handler's failure fits
    // either `E` or that parameter, and under an enclosing `Discord.run` inference picks the parameter: `E` becomes Nothing and
    // the failure lands in effects the loop cannot isolate across the fibers that run the handler.
    def receive[E](
        handler: [A] => Event[A] => A < (Async & Abort[E | Event.Decline] & Env[Discord])
    )(using Frame): Unit < (Async & Abort[DiscordReceiveFailure | E] & Env[Discord]) =
        Env.use[Discord] { discord =>
            discord.session match
                case Present(session) => Dispatch.run[E, Any](discord, session, handler)
                case Absent           =>
                    Scope.run(Scope.acquireRelease(Session.open(discord))(_.close).map(session =>
                        Dispatch.run[E, Any](discord, session, handler)
                    ))
        }

    private[kyo] def client(config: DiscordConfig)(using Frame): Discord < (Async & Scope) =
        // kyo-http's pool defaults stay: 100 connections to one host is more concurrency than a bot's calls reach unless it forks that
        // many at once, and the 60-second idle close matches the idle timeout proxies and load balancers default to.
        HttpClient.init(defaultTlsConfig = config.tls, transportConfig = config.transport).map { http =>
            RateLimits.init(config.globalRateLimit).map { limits =>
                AtomicRef.init(Maybe.empty[ApplicationId]).map(new Discord(config, http, limits, _, Absent))
            }
        }

    /** A client whose Gateway session reached Ready; on a failure everything it opened is closed. */
    private def open(config: DiscordConfig)(using Frame): Discord < (Async & Abort[Gateway.Failure]) =
        for
            http        <- HttpClient.initUnscoped(defaultTlsConfig = config.tls, transportConfig = config.transport)
            limits      <- RateLimits.initUnscoped(config.globalRateLimit)
            application <- AtomicRef.init(Maybe.empty[ApplicationId])
            rest = new Discord(config, http, limits, application, Absent)
            opened  <- Abort.run[Gateway.Failure](Session.open(rest))
            _       <- if opened.isSuccess then Kyo.unit else limits.close.andThen(http.closeNow)
            session <- Abort.get(opened)
        yield new Discord(config, http, limits, application, Present(session))

    // --- Messages ---

    /** Sends `message` to `channel` and answers the message Discord created. With files, the request is `multipart/form-data`,
      * bounded by `transferTimeout` instead of `requestTimeout`. A reply is a send whose message references the original:
      * `Message.Create.init(..., reference = Present(Message.Reference.reply(original)))`.
      */
    def send(channel: ChannelId, message: Message.Create)(using Frame): Message < (Async & Abort[DiscordSendFailure] & Env[Discord]) =
        Rest.call[Message, DiscordSendFailure](Rest.Call(
            HttpMethod.POST,
            "/channels/{channel.id}/messages",
            Rest.path("channels", snowflake(channel.value), "messages"),
            body = Rest.messageBody(message, message.files)
        ))

    /** Edits `message` in `channel` and answers it as edited. */
    def edit(channel: ChannelId, message: MessageId, edit: Message.Edit)(using
        Frame
    ): Message < (Async & Abort[DiscordEditFailure] & Env[Discord]) =
        Rest.call[Message, DiscordEditFailure](Rest.Call(
            HttpMethod.PATCH,
            "/channels/{channel.id}/messages/{message.id}",
            Rest.path("channels", snowflake(channel.value), "messages", snowflake(message.value)),
            body = Rest.Body.Json(Json.encode(edit))
        ))

    /** Deletes `message` from `channel`. */
    def delete(channel: ChannelId, message: MessageId)(using Frame): Unit < (Async & Abort[DiscordDeleteFailure] & Env[Discord]) =
        Rest.acknowledged[DiscordDeleteFailure](Rest.Call(
            HttpMethod.DELETE,
            "/channels/{channel.id}/messages/{message.id}",
            Rest.path("channels", snowflake(channel.value), "messages", snowflake(message.value))
        ))

    /** Adds the bot's `reaction` to `message`. */
    def react(channel: ChannelId, message: MessageId, reaction: Reaction)(using
        Frame
    ): Unit < (Async & Abort[DiscordReactFailure] & Env[Discord]) =
        Rest.acknowledged[DiscordReactFailure](Rest.Call(
            HttpMethod.PUT,
            "/channels/{channel.id}/messages/{message.id}/reactions/{emoji}/@me",
            Rest.path("channels", snowflake(channel.value), "messages", snowflake(message.value), "reactions", reaction.segment, "@me")
        ))

    /** Removes the bot's `reaction` from `message`. */
    def unreact(channel: ChannelId, message: MessageId, reaction: Reaction)(using
        Frame
    ): Unit < (Async & Abort[DiscordUnreactFailure] & Env[Discord]) =
        Rest.acknowledged[DiscordUnreactFailure](Rest.Call(
            HttpMethod.DELETE,
            "/channels/{channel.id}/messages/{message.id}/reactions/{emoji}/@me",
            Rest.path("channels", snowflake(channel.value), "messages", snowflake(message.value), "reactions", reaction.segment, "@me")
        ))

    /** Shows the bot typing in `channel`, for about 10 seconds or until it sends a message there. */
    def typing(channel: ChannelId)(using Frame): Unit < (Async & Abort[DiscordTypingFailure] & Env[Discord]) =
        Rest.acknowledged[DiscordTypingFailure](Rest.Call(
            HttpMethod.POST,
            "/channels/{channel.id}/typing",
            Rest.path("channels", snowflake(channel.value), "typing")
        ))

    /** The message `id` in `channel`. */
    def message(channel: ChannelId, id: MessageId)(using Frame): Message < (Async & Abort[DiscordMessageFailure] & Env[Discord]) =
        Rest.call[Message, DiscordMessageFailure](Rest.Call(
            HttpMethod.GET,
            "/channels/{channel.id}/messages/{message.id}",
            Rest.path("channels", snowflake(channel.value), "messages", snowflake(id.value))
        ))

    /** A page of `channel`'s messages, newest first. */
    def messages(channel: ChannelId, page: Message.Page)(using
        Frame
    ): Chunk[Message] < (Async & Abort[DiscordMessagesFailure] & Env[Discord]) =
        val anchor: Seq[(String, String)] = page.anchor match
            case Message.Page.Anchor.Latest          => Seq.empty
            case Message.Page.Anchor.Around(message) => Seq("around" -> snowflake(message.value))
            case Message.Page.Anchor.Before(message) => Seq("before" -> snowflake(message.value))
            case Message.Page.Anchor.After(message)  => Seq("after" -> snowflake(message.value))
        Rest.call[Chunk[Message], DiscordMessagesFailure](Rest.Call(
            HttpMethod.GET,
            "/channels/{channel.id}/messages",
            Rest.path("channels", snowflake(channel.value), "messages"),
            query = anchor :+ ("limit" -> page.limit.toString)
        ))
    end messages

    // --- Channels, threads and members ---

    /** The channel `id`. */
    def channel(id: ChannelId)(using Frame): Channel < (Async & Abort[DiscordChannelFailure] & Env[Discord]) =
        Rest.call[Channel, DiscordChannelFailure](Rest.Call(
            HttpMethod.GET,
            "/channels/{channel.id}",
            Rest.path("channels", snowflake(id.value))
        ))

    /** Starts `thread` from `message` in `channel` and answers the thread. */
    def startThread(channel: ChannelId, message: MessageId, thread: Thread.Start)(using
        Frame
    ): Channel < (Async & Abort[DiscordStartThreadFailure] & Env[Discord]) =
        startThread(
            "/channels/{channel.id}/messages/{message.id}/threads",
            Rest.path("channels", snowflake(channel.value), "messages", snowflake(message.value), "threads"),
            thread
        )

    /** Starts `thread` in `channel`, from no message, and answers the thread. */
    def startThread(channel: ChannelId, thread: Thread.Start)(using
        Frame
    ): Channel < (Async & Abort[DiscordStartThreadFailure] & Env[Discord]) =
        startThread("/channels/{channel.id}/threads", Rest.path("channels", snowflake(channel.value), "threads"), thread)

    private def startThread(route: String, path: String, thread: Thread.Start)(using
        Frame
    ): Channel < (Async & Abort[DiscordStartThreadFailure] & Env[Discord]) =
        Rest.call[Channel, DiscordStartThreadFailure](Rest.Call(HttpMethod.POST, route, path, body = Rest.Body.Json(Json.encode(thread))))

    /** The direct-message channel with `user`, opened if it was not. */
    def openDm(user: UserId)(using Frame): Channel < (Async & Abort[DiscordOpenDmFailure] & Env[Discord]) =
        Rest.call[Channel, DiscordOpenDmFailure](Rest.Call(
            HttpMethod.POST,
            "/users/@me/channels",
            Rest.path("users", "@me", "channels"),
            body = Rest.Body.Json(Json.encode(kyo.internal.discord.Frames.CreateDm(user)))
        ))

    /** `user`'s membership of `guild`. */
    def member(guild: GuildId, user: UserId)(using Frame): Member < (Async & Abort[DiscordMemberFailure] & Env[Discord]) =
        Rest.call[Member, DiscordMemberFailure](Rest.Call(
            HttpMethod.GET,
            "/guilds/{guild.id}/members/{user.id}",
            Rest.path("guilds", snowflake(guild.value), "members", snowflake(user.value))
        ))

    // --- Commands ---

    /** Sets the application's global commands to `commands`, replacing all of them, and answers them as registered: Discord's
      * bulk-overwrite endpoint (`interactions/application-commands.mdx`, "Bulk Overwrite Global Application Commands"), so an empty
      * `commands` removes every one. The application is resolved once per client with `GET /applications/@me`.
      */
    def setCommands(commands: Chunk[Command.Create])(using
        Frame
    ): Chunk[Command] < (Async & Abort[DiscordSetCommandsFailure] & Env[Discord]) =
        applicationId.map { app =>
            setCommands(
                "/applications/{application.id}/commands",
                Rest.path("applications", snowflake(app.value), "commands"),
                commands
            )
        }

    /** Sets `guild`'s commands to `commands`, replacing all of them, and answers them as registered: Discord's bulk-overwrite endpoint
      * for a guild (`interactions/application-commands.mdx`, "Bulk Overwrite Guild Application Commands").
      */
    def setCommands(guild: GuildId, commands: Chunk[Command.Create])(using
        Frame
    ): Chunk[Command] < (Async & Abort[DiscordSetCommandsFailure] & Env[Discord]) =
        applicationId.map { app =>
            setCommands(
                "/applications/{application.id}/guilds/{guild.id}/commands",
                Rest.path("applications", snowflake(app.value), "guilds", snowflake(guild.value), "commands"),
                commands
            )
        }

    private def setCommands(route: String, path: String, commands: Chunk[Command.Create])(using
        Frame
    ): Chunk[Command] < (Async & Abort[DiscordSetCommandsFailure] & Env[Discord]) =
        Rest.call[Chunk[Command], DiscordSetCommandsFailure](Rest.Call(
            HttpMethod.PUT,
            route,
            path,
            body = Rest.Body.Json(Json.encode(commands))
        ))

    /** The application's id, read once per client from `GET /applications/@me` (`resources/application.mdx`, "Get Current
      * Application") and kept: concurrent first calls may each read it, and every read answers the same id.
      */
    private def applicationId(using Frame): ApplicationId < (Async & Abort[DiscordSetCommandsFailure] & Env[Discord]) =
        Env.use[Discord] { discord =>
            discord.application.get.map {
                case Present(id) => id
                case Absent      =>
                    Rest.call[kyo.internal.discord.Frames.CurrentApplication, DiscordSetCommandsFailure](
                        Rest.Call(HttpMethod.GET, "/applications/@me", Rest.path("applications", "@me"))
                    ).map(app => discord.application.set(Present(app.id)).andThen(app.id))
            }
        }

    // --- Interactions ---

    /** Sends a follow-up message to `interaction` and answers it. Valid for 15 minutes after the interaction. */
    def followUp(interaction: Interaction.Ref, message: Message.Create)(using
        Frame
    ): Message < (Async & Abort[DiscordFollowUpFailure] & Env[Discord]) =
        Rest.call[Message, DiscordFollowUpFailure](Rest.Call(
            HttpMethod.POST,
            "/webhooks/{application.id}/{interaction.token}",
            Rest.path("webhooks", snowflake(interaction.application.value), interaction.token.value),
            body = Rest.messageBody(message, message.files),
            interaction = Present(interaction.token)
        ))

    /** Edits `interaction`'s answer, such as the message a `DeferredMessage` stands for, and answers it as edited. */
    def editResponse(interaction: Interaction.Ref, edit: Message.Edit)(using
        Frame
    ): Message < (Async & Abort[DiscordEditResponseFailure] & Env[Discord]) =
        Rest.call[Message, DiscordEditResponseFailure](Rest.Call(
            HttpMethod.PATCH,
            "/webhooks/{application.id}/{interaction.token}/messages/@original",
            Rest.path("webhooks", snowflake(interaction.application.value), interaction.token.value, "messages", "@original"),
            body = Rest.Body.Json(Json.encode(edit)),
            interaction = Present(interaction.token)
        ))

    /** Deletes `interaction`'s answer. */
    def deleteResponse(interaction: Interaction.Ref)(using Frame): Unit < (Async & Abort[DiscordDeleteResponseFailure] & Env[Discord]) =
        Rest.acknowledged[DiscordDeleteResponseFailure](Rest.Call(
            HttpMethod.DELETE,
            "/webhooks/{application.id}/{interaction.token}/messages/@original",
            Rest.path("webhooks", snowflake(interaction.application.value), interaction.token.value, "messages", "@original"),
            interaction = Present(interaction.token)
        ))

    // --- The Gateway ---

    /** The Gateway's URL, the recommended shard count and the session start budget (`events/gateway.mdx`, "Get Gateway Bot"). */
    def gateway(using Frame): GatewayInfo < (Async & Abort[DiscordGatewayInfoFailure] & Env[Discord]) =
        Rest.call[GatewayInfo, DiscordGatewayInfoFailure](Rest.Call(HttpMethod.GET, "/gateway/bot", Rest.path("gateway", "bot")))

    // --- Escape hatch ---

    /** Calls any route under the API base: `method` to `path`, with `query` percent-encoded and `body` encoded as JSON when present,
      * and the answer decoded as `Out` (an empty answer decodes as JSON `null`, which `Unit` accepts). Only the failures every route
      * shares are told apart; any Discord error code is a [[kyo.DiscordOtherApiException]] carrying it.
      */
    def custom[In: Schema, Out: Schema](
        method: HttpMethod,
        path: Path,
        query: Seq[(String, String)] = Seq.empty,
        body: Maybe[In] = Absent
    )(using Frame): Out < (Async & Abort[DiscordCustomFailure] & Env[Discord]) =
        // The route a failure names is not the path, which may hold a webhook token.
        Rest.call[Out, DiscordCustomFailure](Rest.Call(
            method,
            "custom",
            "/" + path.value,
            query,
            body.fold(Rest.Body.Empty)(b => Rest.Body.Json(Json.encode(b)))
        ))

    private def snowflake(value: Long): String = WireField.renderSnowflake(value)

    // --- Ids ---

    /** A guild, Discord's server. A snowflake: see [[kyo.Discord.ChannelId]] for what every id here shares. */
    type GuildId = GuildId.Value
    object GuildId:
        opaque type Value = Long
        def apply(value: Long): GuildId                                                  = value
        def parse(text: String)(using Frame): Result[DiscordInvalidIdException, GuildId] = WireField.parseSnowflake(text)
        extension (self: GuildId) def value: Long                                        = self
        extension (self: GuildId) def createdAt: Instant                                 = WireField.snowflakeCreatedAt(self)
        given Schema[GuildId]            = WireField.snowflakeSchema.transform[GuildId](apply)(_.value)
        given CanEqual[GuildId, GuildId] = CanEqual.derived
    end GuildId

    /** A channel: a guild text channel, a thread, or a direct message.
      *
      * Like every id here it is a snowflake: 64 bits sent as a decimal string, read and written unsigned because the timestamp in bits
      * 63 to 22 sets the top bit from 2084. `createdAt` is that timestamp. The `Schema` is the decimal string, and text that is not one
      * fails the decode. `apply` takes any `Long`; `parse` refuses text that is not a decimal snowflake.
      */
    type ChannelId = ChannelId.Value
    object ChannelId:
        opaque type Value = Long
        def apply(value: Long): ChannelId                                                  = value
        def parse(text: String)(using Frame): Result[DiscordInvalidIdException, ChannelId] = WireField.parseSnowflake(text)
        extension (self: ChannelId) def value: Long                                        = self
        extension (self: ChannelId) def createdAt: Instant                                 = WireField.snowflakeCreatedAt(self)
        given Schema[ChannelId]              = WireField.snowflakeSchema.transform[ChannelId](apply)(_.value)
        given CanEqual[ChannelId, ChannelId] = CanEqual.derived
    end ChannelId

    /** A user or a bot. A snowflake, as [[kyo.Discord.ChannelId]]. */
    type UserId = UserId.Value
    object UserId:
        opaque type Value = Long
        def apply(value: Long): UserId                                                  = value
        def parse(text: String)(using Frame): Result[DiscordInvalidIdException, UserId] = WireField.parseSnowflake(text)
        extension (self: UserId) def value: Long                                        = self
        extension (self: UserId) def createdAt: Instant                                 = WireField.snowflakeCreatedAt(self)
        given Schema[UserId]           = WireField.snowflakeSchema.transform[UserId](apply)(_.value)
        given CanEqual[UserId, UserId] = CanEqual.derived
    end UserId

    /** A message, unique inside its channel. A snowflake, as [[kyo.Discord.ChannelId]]. */
    type MessageId = MessageId.Value
    object MessageId:
        opaque type Value = Long
        def apply(value: Long): MessageId                                                  = value
        def parse(text: String)(using Frame): Result[DiscordInvalidIdException, MessageId] = WireField.parseSnowflake(text)
        extension (self: MessageId) def value: Long                                        = self
        extension (self: MessageId) def createdAt: Instant                                 = WireField.snowflakeCreatedAt(self)
        given Schema[MessageId]              = WireField.snowflakeSchema.transform[MessageId](apply)(_.value)
        given CanEqual[MessageId, MessageId] = CanEqual.derived
    end MessageId

    /** A guild role. A snowflake, as [[kyo.Discord.ChannelId]]. */
    type RoleId = RoleId.Value
    object RoleId:
        opaque type Value = Long
        def apply(value: Long): RoleId                                                  = value
        def parse(text: String)(using Frame): Result[DiscordInvalidIdException, RoleId] = WireField.parseSnowflake(text)
        extension (self: RoleId) def value: Long                                        = self
        extension (self: RoleId) def createdAt: Instant                                 = WireField.snowflakeCreatedAt(self)
        given Schema[RoleId]           = WireField.snowflakeSchema.transform[RoleId](apply)(_.value)
        given CanEqual[RoleId, RoleId] = CanEqual.derived
    end RoleId

    /** A custom emoji. A snowflake, as [[kyo.Discord.ChannelId]]; a unicode emoji has no id. */
    type EmojiId = EmojiId.Value
    object EmojiId:
        opaque type Value = Long
        def apply(value: Long): EmojiId                                                  = value
        def parse(text: String)(using Frame): Result[DiscordInvalidIdException, EmojiId] = WireField.parseSnowflake(text)
        extension (self: EmojiId) def value: Long                                        = self
        extension (self: EmojiId) def createdAt: Instant                                 = WireField.snowflakeCreatedAt(self)
        given Schema[EmojiId]            = WireField.snowflakeSchema.transform[EmojiId](apply)(_.value)
        given CanEqual[EmojiId, EmojiId] = CanEqual.derived
    end EmojiId

    /** An application, the bot's owner in the developer portal. A snowflake, as [[kyo.Discord.ChannelId]]. */
    type ApplicationId = ApplicationId.Value
    object ApplicationId:
        opaque type Value = Long
        def apply(value: Long): ApplicationId                                                  = value
        def parse(text: String)(using Frame): Result[DiscordInvalidIdException, ApplicationId] = WireField.parseSnowflake(text)
        extension (self: ApplicationId) def value: Long                                        = self
        extension (self: ApplicationId) def createdAt: Instant                                 = WireField.snowflakeCreatedAt(self)
        given Schema[ApplicationId]                  = WireField.snowflakeSchema.transform[ApplicationId](apply)(_.value)
        given CanEqual[ApplicationId, ApplicationId] = CanEqual.derived
    end ApplicationId

    /** An interaction: a command, a component use, an autocomplete or a modal submission. A snowflake, as [[kyo.Discord.ChannelId]]. */
    type InteractionId = InteractionId.Value
    object InteractionId:
        opaque type Value = Long
        def apply(value: Long): InteractionId                                                  = value
        def parse(text: String)(using Frame): Result[DiscordInvalidIdException, InteractionId] = WireField.parseSnowflake(text)
        extension (self: InteractionId) def value: Long                                        = self
        extension (self: InteractionId) def createdAt: Instant                                 = WireField.snowflakeCreatedAt(self)
        given Schema[InteractionId]                  = WireField.snowflakeSchema.transform[InteractionId](apply)(_.value)
        given CanEqual[InteractionId, InteractionId] = CanEqual.derived
    end InteractionId

    /** An application command. A snowflake, as [[kyo.Discord.ChannelId]]. */
    type CommandId = CommandId.Value
    object CommandId:
        opaque type Value = Long
        def apply(value: Long): CommandId                                                  = value
        def parse(text: String)(using Frame): Result[DiscordInvalidIdException, CommandId] = WireField.parseSnowflake(text)
        extension (self: CommandId) def value: Long                                        = self
        extension (self: CommandId) def createdAt: Instant                                 = WireField.snowflakeCreatedAt(self)
        given Schema[CommandId]              = WireField.snowflakeSchema.transform[CommandId](apply)(_.value)
        given CanEqual[CommandId, CommandId] = CanEqual.derived
    end CommandId

    /** A message attachment. A snowflake, as [[kyo.Discord.ChannelId]]. */
    type AttachmentId = AttachmentId.Value
    object AttachmentId:
        opaque type Value = Long
        def apply(value: Long): AttachmentId                                                  = value
        def parse(text: String)(using Frame): Result[DiscordInvalidIdException, AttachmentId] = WireField.parseSnowflake(text)
        extension (self: AttachmentId) def value: Long                                        = self
        extension (self: AttachmentId) def createdAt: Instant                                 = WireField.snowflakeCreatedAt(self)
        given Schema[AttachmentId]                 = WireField.snowflakeSchema.transform[AttachmentId](apply)(_.value)
        given CanEqual[AttachmentId, AttachmentId] = CanEqual.derived
    end AttachmentId

    /** A webhook. A snowflake, as [[kyo.Discord.ChannelId]]. */
    type WebhookId = WebhookId.Value
    object WebhookId:
        opaque type Value = Long
        def apply(value: Long): WebhookId                                                  = value
        def parse(text: String)(using Frame): Result[DiscordInvalidIdException, WebhookId] = WireField.parseSnowflake(text)
        extension (self: WebhookId) def value: Long                                        = self
        extension (self: WebhookId) def createdAt: Instant                                 = WireField.snowflakeCreatedAt(self)
        given Schema[WebhookId]              = WireField.snowflakeSchema.transform[WebhookId](apply)(_.value)
        given CanEqual[WebhookId, WebhookId] = CanEqual.derived
    end WebhookId

    // --- Codes and Gateway settings ---

    /** Discord's JSON error code, the `code` of an error body (`topics/opcodes-and-status-codes.mdx`, "JSON Error Codes"). */
    type Code = Code.Value
    object Code:
        opaque type Value = Int
        def apply(value: Int): Code           = value
        extension (self: Code) def value: Int = self
        given Schema[Code]                    = Schema.intSchema.transform[Code](apply)(_.value)
        given CanEqual[Code, Code]            = CanEqual.derived
    end Code

    /** The Gateway intents a connection subscribes to, a bit set (`events/gateway.mdx`, "List of Intents").
      *
      * A connection receives the events of the intents it names and no others. Three intents are privileged and must also be enabled
      * for the application in the developer portal: [[kyo.Discord.Intents.GuildMembers]], [[kyo.Discord.Intents.GuildPresences]] and
      * [[kyo.Discord.Intents.MessageContent]]. Naming one that is not enabled closes the Gateway with 4014, the failure
      * [[kyo.DiscordDisallowedIntentsException]].
      *
      * Combine intents with `union`. The `Schema` is the integer Identify sends.
      */
    type Intents = Intents.Value
    object Intents:
        opaque type Value = Long

        def apply(bits: Long): Intents = bits

        /** No intent. */
        val Empty: Intents = 0L

        val Guilds: Intents                      = 1L << 0
        val GuildMembers: Intents                = 1L << 1
        val GuildModeration: Intents             = 1L << 2
        val GuildExpressions: Intents            = 1L << 3
        val GuildIntegrations: Intents           = 1L << 4
        val GuildWebhooks: Intents               = 1L << 5
        val GuildInvites: Intents                = 1L << 6
        val GuildVoiceStates: Intents            = 1L << 7
        val GuildPresences: Intents              = 1L << 8
        val GuildMessages: Intents               = 1L << 9
        val GuildMessageReactions: Intents       = 1L << 10
        val GuildMessageTyping: Intents          = 1L << 11
        val DirectMessages: Intents              = 1L << 12
        val DirectMessageReactions: Intents      = 1L << 13
        val DirectMessageTyping: Intents         = 1L << 14
        val MessageContent: Intents              = 1L << 15
        val GuildScheduledEvents: Intents        = 1L << 16
        val AutoModerationConfiguration: Intents = 1L << 20
        val AutoModerationExecution: Intents     = 1L << 21
        val GuildMessagePolls: Intents           = 1L << 24
        val DirectMessagePolls: Intents          = 1L << 25

        extension (self: Intents)
            def value: Long                       = self
            def union(other: Intents): Intents    = Intents(self.value | other.value)
            def contains(other: Intents): Boolean = (self.value & other.value) == other.value
        end extension

        given Schema[Intents]            = Schema.longSchema.transform[Intents](apply)(_.value)
        given CanEqual[Intents, Intents] = CanEqual.derived
    end Intents

    /** The shard a connection serves, sent in Identify as `[shard_id, num_shards]` (`events/gateway.mdx`, "Sharding").
      *
      * A bot in many guilds splits its Gateway traffic over `count` connections; the one with `id` receives the events of the guilds
      * whose `(guild_id >> 22) % count` is `id`. A client runs one shard; starting several is the caller's.
      *
      * IMPORTANT: `init` refuses an `id` outside `0 until count` with [[kyo.DiscordInvalidConfigException]].
      */
    final case class Shard private (id: Int, count: Int) derives CanEqual:
        private[kyo] def show: String = s"[$id, $count]"

    object Shard:
        /** The shard `id` of `count`, or a [[kyo.DiscordInvalidConfigException]] when not `0 <= id < count`. */
        def init(id: Int, count: Int)(using Frame): Result[DiscordInvalidConfigException, Shard] =
            if id >= 0 && id < count then Result.succeed(new Shard(id, count))
            else Result.fail(DiscordInvalidConfigException(DiscordInvalidConfigException.Problem.Shard(id, count)))
    end Shard

    // --- Secrets ---

    /** The bot token that authenticates every REST call and the Gateway's Identify, as the developer portal issues it.
      *
      * It travels in the `Authorization: Bot <token>` header (`reference.mdx`, "Authentication") and in Identify's `token` field. The
      * class keeps it from printing: `toString` renders `Discord.Token(<redacted>)`, so a `DiscordConfig`, a log line or an assertion
      * message never shows it, and `value` is the only way to read it. Tokens compare by value. The `Schema` reads and writes the
      * token's text, so encoding one is an explicit act that writes the secret.
      *
      * IMPORTANT: Discord documents neither an alphabet nor a length for the token, so `init` checks only what a header and a JSON
      * string carry: at least one character of printable ASCII other than space. Anything else fails with
      * [[kyo.DiscordInvalidTokenException]]. A token of that shape that Discord does not know is the call's
      * [[kyo.DiscordUnauthorizedException]].
      *
      * @see
      *   [[kyo.DiscordConfig]] where the token is held
      * @see
      *   [[kyo.Discord.InteractionToken]] an interaction's token, a different kind
      */
    final class Token private (val value: String):
        override def equals(other: Any): Boolean =
            other match
                case that: Token => value == that.value
                case _           => false
        override def hashCode: Int    = value.hashCode
        override def toString: String = "Discord.Token(<redacted>)"
    end Token

    object Token:

        /** The token `value`, or a [[kyo.DiscordInvalidTokenException]] when a header could not carry it. */
        def init(value: String)(using Frame): Result[DiscordInvalidTokenException, Token] =
            problemOf(value) match
                case Present(problem) => Result.fail(DiscordInvalidTokenException(DiscordInvalidTokenException.Token.Bot, problem))
                case Absent           => Result.succeed(new Token(value))

        given CanEqual[Token, Token] = CanEqual.derived

        given Schema[Token] =
            Schema.stringSchema.transformVia(text => init(text))(_.value)

        private def problemOf(value: String): Maybe[DiscordInvalidTokenException.Problem] =
            import DiscordInvalidTokenException.Problem
            if value.isEmpty then Present(Problem.Empty)
            else
                val bad = value.indexWhere(c => c <= ' ' || c > '~')
                if bad >= 0 then Present(Problem.Character(bad)) else Absent
            end if
        end problemOf
    end Token

    /** The token of one interaction, which authorizes its answer and the follow-ups and edits made with it for 15 minutes
      * (`interactions/receiving-and-responding.mdx`, "Followup Messages").
      *
      * Discord puts it in the path of the callback and of every `/webhooks/{application.id}/{interaction.token}` route, so it is a
      * secret that travels in a path. `toString` renders `Discord.InteractionToken(<redacted>)`, `value` is the only way to read it, and
      * tokens compare by value. The `Schema` reads and writes the token's text.
      *
      * IMPORTANT: `init` and the `Schema` accept one or more characters of `A-Z a-z 0-9 . _ -` and refuse anything else with
      * [[kyo.DiscordInvalidTokenException]], so every URL the module builds with a token parses and no token can add a path segment, a
      * query or an escape.
      */
    final class InteractionToken private (val value: String):
        override def equals(other: Any): Boolean =
            other match
                case that: InteractionToken => value == that.value
                case _                      => false
        override def hashCode: Int    = value.hashCode
        override def toString: String = "Discord.InteractionToken(<redacted>)"
    end InteractionToken

    object InteractionToken:

        /** The token `value`, or a [[kyo.DiscordInvalidTokenException]] when it is empty or holds a character outside `A-Z a-z 0-9 . _ -`. */
        def init(value: String)(using Frame): Result[DiscordInvalidTokenException, InteractionToken] =
            problemOf(value) match
                case Present(problem) => Result.fail(DiscordInvalidTokenException(DiscordInvalidTokenException.Token.Interaction, problem))
                case Absent           => Result.succeed(new InteractionToken(value))

        given CanEqual[InteractionToken, InteractionToken] = CanEqual.derived

        given Schema[InteractionToken] =
            Schema.stringSchema.transformVia(text => init(text))(_.value)

        private def problemOf(value: String): Maybe[DiscordInvalidTokenException.Problem] =
            import DiscordInvalidTokenException.Problem
            if value.isEmpty then Present(Problem.Empty)
            else
                val bad = value.indexWhere(c =>
                    !((c >= 'A' && c <= 'Z') ||
                        (c >= 'a' && c <= 'z') ||
                        (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-')
                )
                if bad >= 0 then Present(Problem.Character(bad)) else Absent
            end if
        end problemOf
    end InteractionToken

    /** The application's Ed25519 public key, which verifies the signature on every request to the interactions endpoint.
      *
      * The developer portal shows it as 64 hex characters. It is not a secret, so it is not redacted: `toString` renders the hex.
      * `init` decodes the hex once and holds kyo-crypto's key, so no request decodes it again.
      *
      * IMPORTANT: `init` refuses text that is not hex and bytes kyo-crypto does not accept as a key, among them every small-order
      * point, under which a signature with `S = 0` verifies for every message. The failure keeps kyo-crypto's reason
      * (`DiscordInvalidPublicKeyException.Problem.Key(KeyFailure.SmallOrder)`), so an operator sees which rule the key broke.
      *
      * @see
      *   [[kyo.DiscordWebhookConfig]] where the key is held
      */
    final class PublicKey private (private[kyo] val key: Ed25519.VerificationKey):
        /** The key's 32 bytes as 64 lowercase hex characters. */
        def hex: String = kyo.Hex.encode(key.bytes)

        override def equals(other: Any): Boolean =
            other match
                case that: PublicKey => hex == that.hex
                case _               => false
        override def hashCode: Int    = hex.hashCode
        override def toString: String = s"Discord.PublicKey($hex)"
    end PublicKey

    object PublicKey:

        /** The key `hex` names, or a [[kyo.DiscordInvalidPublicKeyException]] saying why it is not one. */
        def init(hex: String)(using Frame): Result[DiscordInvalidPublicKeyException, PublicKey] =
            import DiscordInvalidPublicKeyException.Problem
            kyo.Hex.decode(hex) match
                case Result.Success(bytes) =>
                    Ed25519.VerificationKey.fromBytes(bytes) match
                        case Result.Success(key) => Result.succeed(new PublicKey(key))
                        case Result.Failure(f)   => Result.fail(DiscordInvalidPublicKeyException(Problem.Key(f)))
                        case Result.Panic(t)     => Result.panic(t)
                case Result.Failure(f) => Result.fail(DiscordInvalidPublicKeyException(Problem.Hex(f)))
                case Result.Panic(t)   => Result.panic(t)
            end match
        end init

        given CanEqual[PublicKey, PublicKey] = CanEqual.derived
    end PublicKey

    // --- Custom routes ---

    /** A route under the API base, for `custom`: relative segments joined by single slashes, such as `guilds/123/roles` or
      * `users/@me`.
      *
      * A segment is one or more of `A-Z a-z 0-9 . _ - @`, and neither `.` nor `..`. `@` stays because Discord's own routes name the
      * current user and the original response with it (`users/@me`, `messages/@original`); RFC 3986 allows it in a segment, and it
      * cannot move the request. Everything else that could change where the request goes, a `?` starting a query, a `#`, a `%`
      * escape, a dot segment, a leading or doubled `/`, is refused by `init` with [[kyo.DiscordInvalidPathException]], never stripped.
      */
    type Path = Path.Value
    object Path:
        opaque type Value = String

        /** The path `text`, or a [[kyo.DiscordInvalidPathException]] naming the first position that cannot be in one. */
        def init(text: String)(using Frame): Result[DiscordInvalidPathException, Path] =
            problemOf(text) match
                case Present(problem) => Result.fail(DiscordInvalidPathException(problem))
                case Absent           => Result.succeed(text)

        extension (self: Path) def value: String = self

        given CanEqual[Path, Path] = CanEqual.derived

        private def problemOf(text: String): Maybe[DiscordInvalidPathException.Problem] =
            import DiscordInvalidPathException.Problem
            def segmentChar(c: Char): Boolean =
                (c >= 'A' && c <= 'Z') ||
                    (c >= 'a' && c <= 'z') ||
                    (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-' || c == '@'
            @scala.annotation.tailrec
            def loop(start: Int): Maybe[Problem] =
                val end = text.indexOf('/', start) match
                    case -1 => text.length
                    case at => at
                // An empty segment: a leading or doubled `/` sits at `start`, a trailing one just before the end.
                if end == start then Present(Problem.Character(if start == text.length then start - 1 else start))
                else
                    val bad = text.substring(start, end).indexWhere(c => !segmentChar(c))
                    if bad >= 0 then Present(Problem.Character(start + bad))
                    else
                        val segment = text.substring(start, end)
                        if segment == "." || segment == ".." then Present(Problem.DotSegment(start))
                        else if end == text.length then Absent
                        else loop(end + 1)
                    end if
                end if
            end loop
            if text.isEmpty then Present(Problem.Empty) else loop(0)
        end problemOf
    end Path

    // --- The model ---
    //
    // Every type below has a Schema that is Discord's JSON: snake_case keys, an absent optional field omitted on write, keys the model
    // does not declare ignored on read.

    /** The JSON of a payload the model does not declare, such as an event kind added after this module. Rendered by its length only,
      * since it may hold a user's message.
      */
    final class RawJson private (private[kyo] val json: Structure.Value, text: String):
        /** The payload as JSON text. */
        def value: String = text

        override def equals(other: Any): Boolean =
            other match
                case that: RawJson => json == that.json
                case _             => false
        override def hashCode: Int    = json.hashCode
        override def toString: String = s"Discord.RawJson(${value.length} characters)"
    end RawJson

    object RawJson:
        /** The payload `json`. */
        def apply(json: Structure.Value)(using Frame): RawJson =
            new RawJson(json, Json.encode(json)(using Structure.Value.valueSchema, summon[Frame]))
        given CanEqual[RawJson, RawJson] = CanEqual.derived

        // Dynamic by definition: the payload of a kind the model does not declare, so its shape is whatever Discord sent.
        given Schema[RawJson] =
            Structure.Value.valueSchema.transformVia[Result[String, RawJson], RawJson](json => Result.succeed(RawJson(json)))(_.json)
    end RawJson

    /** A field of an edit that is left as it is (`Absent`), cleared (`Present(Patch.Clear)`, written as `null`), or set
      * (`Present(Patch.Set(value))`).
      *
      * Discord clears an edit's field with `null` ("All parameters to this endpoint are optional and nullable", `resources/message.mdx`,
      * "Edit Message"), so an edit needs three states where a `Maybe` has two. `Maybe[Maybe[A]]` cannot carry them: kyo-schema omits a
      * nested `Absent` like an outer one. The outer `Maybe` decides whether the field is written, `Patch` what it holds.
      *
      * Note: an edit is only written. Read back, a `null` field is `Absent`, like a missing one.
      */
    enum Patch[+A] derives CanEqual:
        case Clear
        case Set(value: A)
    end Patch

    object Patch:
        given [A](using inner: Schema[A]): Schema[Patch[A]] =
            Schema.init[Patch[A]](
                writeFn = (patch, writer) =>
                    patch match
                        case Patch.Set(value) => inner.serializeWrite(value, writer)
                        case _                =>
                            writer.nil()
                ,
                readFn = reader => if reader.isNil() then Patch.Clear else Patch.Set(inner.serializeRead(reader)),
                // Not Optional: a written null must reach the wire, where an Optional field's null is omitted.
                structure = inner.structure
            )
    end Patch

    /** A Discord user or bot (`resources/user.mdx`, "User Object"). `discriminator` is `"0"` for a user on the unique-username
      * system; `globalName` is the display name when set; `avatar` is the avatar's hash.
      */
    final case class User(
        id: UserId,
        username: String,
        discriminator: String = "0",
        globalName: Maybe[String] = Absent,
        avatar: Maybe[String] = Absent,
        bot: Boolean = false
    ) derives CanEqual

    object User:
        given Schema[User] = Schema.derived[User].renameAllFields(Schema.NameCase.SnakeCase)

    /** A user's membership of a guild (`resources/guild.mdx`, "Guild Member Object"). `user` is absent on a member attached to a
      * message event; `permissions` is the member's computed permission set as Discord's decimal string, present on an interaction.
      */
    final case class Member(
        user: Maybe[User] = Absent,
        nick: Maybe[String] = Absent,
        roles: Chunk[RoleId] = Chunk.empty,
        joinedAt: Maybe[Instant] = Absent,
        permissions: Maybe[String] = Absent
    ) derives CanEqual

    object Member:
        given Schema[Member] = Schema.derived[Member].renameAllFields(Schema.NameCase.SnakeCase)

    /** A channel: a guild text or voice channel, a category, a thread, or a direct message (`resources/channel.mdx`, "Channel
      * Object"). `threadMetadata` is present on a thread.
      */
    final case class Channel(
        id: ChannelId,
        `type`: Channel.Type,
        guildId: Maybe[GuildId] = Absent,
        name: Maybe[String] = Absent,
        topic: Maybe[String] = Absent,
        parentId: Maybe[ChannelId] = Absent,
        nsfw: Boolean = false,
        threadMetadata: Maybe[Channel.ThreadMetadata] = Absent
    ) derives CanEqual

    object Channel:
        given Schema[Channel] = Schema.derived[Channel].renameAllFields(Schema.NameCase.SnakeCase)

        /** A channel's kind, Discord's integer (`resources/channel.mdx`, "Channel Types"); a kind added later keeps its number. */
        type Type = Type.Value
        object Type:
            opaque type Value = Int
            def apply(value: Int): Type           = value
            extension (self: Type) def value: Int = self
            val GuildText: Type                   = 0
            val Dm: Type                          = 1
            val GuildVoice: Type                  = 2
            val GroupDm: Type                     = 3
            val GuildCategory: Type               = 4
            val GuildAnnouncement: Type           = 5
            val AnnouncementThread: Type          = 10
            val PublicThread: Type                = 11
            val PrivateThread: Type               = 12
            val GuildStageVoice: Type             = 13
            val GuildDirectory: Type              = 14
            val GuildForum: Type                  = 15
            val GuildMedia: Type                  = 16
            given Schema[Type]                    = Schema.intSchema.transform[Type](apply)(_.value)
            given CanEqual[Type, Type]            = CanEqual.derived
        end Type

        /** A thread's state (`resources/channel.mdx`, "Thread Metadata Object"). `autoArchiveDuration` is in minutes. */
        final case class ThreadMetadata(
            archived: Boolean,
            autoArchiveDuration: Int,
            archiveTimestamp: Instant,
            locked: Boolean,
            invitable: Maybe[Boolean] = Absent
        ) derives CanEqual

        object ThreadMetadata:
            given Schema[ThreadMetadata] = Schema.derived[ThreadMetadata].renameAllFields(Schema.NameCase.SnakeCase)
    end Channel

    /** A guild as the Gateway sends it when it becomes available (`events/gateway-events.mdx`, "Guild Create"). Only the fields the
      * module models; the rest of Discord's guild object is ignored on read.
      */
    final case class Guild(
        id: GuildId,
        name: String,
        icon: Maybe[String] = Absent,
        ownerId: Maybe[UserId] = Absent,
        memberCount: Maybe[Int] = Absent,
        channels: Chunk[Channel] = Chunk.empty
    ) derives CanEqual

    object Guild:
        given Schema[Guild] = Schema.derived[Guild].renameAllFields(Schema.NameCase.SnakeCase)

    /** A guild named in Ready before its data arrives (`resources/guild.mdx`, "Unavailable Guild Object"). */
    final case class UnavailableGuild(id: GuildId, unavailable: Boolean = true) derives CanEqual

    object UnavailableGuild:
        given Schema[UnavailableGuild] = Schema.derived[UnavailableGuild]

    /** An emoji as a reaction or a component carries it (`resources/emoji.mdx`, partial "Emoji Object"): a unicode emoji has a `name`
      * and no `id`; a custom emoji has an `id`, and a `name` unless it was deleted.
      */
    final case class Emoji(id: Maybe[EmojiId] = Absent, name: Maybe[String] = Absent, animated: Boolean = false) derives CanEqual

    object Emoji:
        /** The unicode emoji `name`, such as `"🔥"`. */
        def unicode(name: String): Emoji = Emoji(Absent, Present(name))

        /** The custom emoji `id` named `name`. */
        def custom(id: EmojiId, name: String, animated: Boolean = false): Emoji = Emoji(Present(id), Present(name), animated)

        given Schema[Emoji] = Schema.derived[Emoji]
    end Emoji

    /** An emoji `react` and `unreact` can name. Discord names a reaction's emoji in the request path, a unicode emoji by its name and a
      * custom one as `name:id` ("To use custom emoji, you must encode it in the format name:id", `resources/message.mdx`, "Create
      * Reaction"), so the emoji must have a name.
      *
      * IMPORTANT: every constructor refuses an emoji with no name or an empty one with [[kyo.DiscordInvalidReactionException]]: its path
      * segment would be empty, which names a different route. Discord sends a deleted custom emoji with no name.
      */
    final case class Reaction private (emoji: Emoji) derives CanEqual:
        private[kyo] def segment: String =
            val name = emoji.name.getOrElse("")
            kyo.internal.discord.Rest.encodeSegment(emoji.id.fold(name)(id => s"$name:${WireField.renderSnowflake(id.value)}"))
    end Reaction

    object Reaction:
        /** The reaction with `emoji`, or a [[kyo.DiscordInvalidReactionException]] when the emoji has no name. */
        def init(emoji: Emoji)(using Frame): Result[DiscordInvalidReactionException, Reaction] =
            if emoji.name.exists(_.nonEmpty) then Result.succeed(new Reaction(emoji))
            else Result.fail(DiscordInvalidReactionException(DiscordInvalidReactionException.Problem.NoName))

        /** The unicode emoji `name`, such as `"🔥"`. */
        def unicode(name: String)(using Frame): Result[DiscordInvalidReactionException, Reaction] = init(Emoji.unicode(name))

        /** The custom emoji `id` named `name`. */
        def custom(id: EmojiId, name: String, animated: Boolean = false)(using Frame): Result[DiscordInvalidReactionException, Reaction] =
            init(Emoji.custom(id, name, animated))
    end Reaction

    /** A file attached to a message (`resources/message.mdx`, "Attachment Object"). `url` and `proxyUrl` are Discord's CDN, which the
      * module never fetches.
      */
    final case class Attachment(
        id: AttachmentId,
        filename: String,
        size: Long,
        @kyo.schema.transform(WireField.Url) url: HttpUrl,
        @kyo.schema.transform(WireField.Url) proxyUrl: HttpUrl,
        contentType: Maybe[String] = Absent,
        description: Maybe[String] = Absent,
        height: Maybe[Int] = Absent,
        width: Maybe[Int] = Absent
    ) derives CanEqual

    object Attachment:
        given Schema[Attachment] = Schema.derived[Attachment].renameAllFields(Schema.NameCase.SnakeCase)

    /** A file to upload with a message, sent as a `files[n]` part of a `multipart/form-data` body (`reference.mdx`, "Uploading
      * Files"). An embed or the message refers to it as `attachment://<name>`.
      *
      * IMPORTANT: `init` refuses a name of more than 1024 characters or holding `"`, `\`, `/` or a control character, which a
      * multipart part's head or a file system cannot carry, and a description of more than 1024 characters (the attachment's alt
      * text bound), with [[kyo.DiscordInvalidMessageException]].
      */
    final case class File private (name: String, bytes: Span[Byte], contentType: Maybe[kyo.mime.MediaType], description: Maybe[String])

    object File:

        /** The file `name` holding `bytes`, or a [[kyo.DiscordInvalidMessageException]] naming what Discord would refuse. */
        def init(
            name: String,
            bytes: Span[Byte],
            contentType: Maybe[kyo.mime.MediaType] = Absent,
            description: Maybe[String] = Absent
        )(using Frame): Result[DiscordInvalidMessageException, File] =
            import DiscordInvalidMessageException.Problem
            val nameLength = name.codePointCount(0, name.length)
            val bad        = name.indexWhere(c => c < ' ' || c == 0x7f || c == '"' || c == '\\' || c == '/')
            if nameLength == 0 || nameLength > 1024 then Result.fail(DiscordInvalidMessageException(Problem.FileName(nameLength)))
            else if bad >= 0 then Result.fail(DiscordInvalidMessageException(Problem.FileNameCharacter(bad)))
            else
                val descriptionLength: Maybe[Int] = description.map(d => d.codePointCount(0, d.length)).filter(_ > 1024)
                descriptionLength match
                    case Present(length) => Result.fail(DiscordInvalidMessageException(Problem.FileDescription(length, 1024)))
                    case Absent          => Result.succeed(new File(name, bytes, contentType, description))
            end if
        end init

        given CanEqual[File, File] = CanEqual.derived

        // A file is a multipart part, never JSON: `Message.Create.files` is transient, and its derivation still needs this Schema.
        // Writing the name and refusing every read keeps a file's bytes out of any JSON.
        private[kyo] given Schema[File] =
            Schema.stringSchema.transformVia[Result[String, File], File](_ => Result.fail("a file is sent as a multipart part"))(_.name)
    end File

    /** A rich embed (`resources/message.mdx`, "Embed Object"). Its URLs are text, since Discord takes `attachment://<name>` beside
      * http and https.
      *
      * IMPORTANT: `init` refuses an embed past Discord's limits ("Embed Limits"): a title of more than 256 characters, a description
      * of more than 4096, more than 25 fields, a field name of 1 to 256 and a value of 1 to 1024, a footer of more than 2048, an author
      * name of more than 256, and a color outside 0 to 0xFFFFFF. The 6000-character total across a message's embeds is checked where
      * the embeds are put in a message.
      */
    final case class Embed private[kyo] (
        title: Maybe[String] = Absent,
        description: Maybe[String] = Absent,
        url: Maybe[String] = Absent,
        timestamp: Maybe[Instant] = Absent,
        color: Maybe[Int] = Absent,
        footer: Maybe[Embed.Footer] = Absent,
        image: Maybe[Embed.Media] = Absent,
        thumbnail: Maybe[Embed.Media] = Absent,
        author: Maybe[Embed.Author] = Absent,
        @kyo.schema.omit(kyo.schema.omit.WhenEmpty) fields: Chunk[Embed.Field] = Chunk.empty
    ) derives CanEqual:

        /** The characters this embed counts toward a message's 6000: title, description, field names and values, footer and author. */
        def characters: Int =
            def n(s: String): Int = s.codePointCount(0, s.length)
            title.fold(0)(n) + description.fold(0)(n) + fields.foldLeft(0)((sum, f) => sum + n(f.name) + n(f.value)) +
                footer.fold(0)(f => n(f.text)) + author.fold(0)(a => n(a.name))
        end characters
    end Embed

    object Embed:

        /** The embed, or a [[kyo.DiscordInvalidMessageException]] naming the first limit it breaks. */
        def init(
            title: Maybe[String] = Absent,
            description: Maybe[String] = Absent,
            url: Maybe[String] = Absent,
            timestamp: Maybe[Instant] = Absent,
            color: Maybe[Int] = Absent,
            footer: Maybe[Footer] = Absent,
            image: Maybe[Media] = Absent,
            thumbnail: Maybe[Media] = Absent,
            author: Maybe[Author] = Absent,
            fields: Chunk[Field] = Chunk.empty
        )(using Frame): Result[DiscordInvalidMessageException, Embed] =
            import DiscordInvalidMessageException.Problem
            def n(s: String): Int            = s.codePointCount(0, s.length)
            val fieldProblem: Maybe[Problem] =
                Maybe.fromOption(fields.zipWithIndex.collectFirst {
                    case (f, i) if n(f.name) < 1 || n(f.name) > 256    => Problem.EmbedFieldName(i, n(f.name), 256)
                    case (f, i) if n(f.value) < 1 || n(f.value) > 1024 => Problem.EmbedFieldValue(i, n(f.value), 1024)
                })
            val problem: Maybe[Problem] =
                title.map(n).filter(_ > 256).map(Problem.EmbedTitle(_, 256))
                    .orElse(description.map(n).filter(_ > 4096).map(Problem.EmbedDescription(_, 4096)))
                    .orElse(if fields.size > 25 then Present(Problem.EmbedFieldCount(fields.size, 25)) else Absent)
                    .orElse(fieldProblem)
                    .orElse(footer.map(f => n(f.text)).filter(_ > 2048).map(Problem.EmbedFooter(_, 2048)))
                    .orElse(author.map(a => n(a.name)).filter(_ > 256).map(Problem.EmbedAuthor(_, 256)))
                    .orElse(color.filter(c => c < 0 || c > 0xffffff).map(Problem.EmbedColor(_)))
            problem match
                case Present(p) => Result.fail(DiscordInvalidMessageException(p))
                case Absent     =>
                    Result.succeed(new Embed(title, description, url, timestamp, color, footer, image, thumbnail, author, fields))
            end match
        end init

        given Schema[Embed] = Schema.derived[Embed].renameAllFields(Schema.NameCase.SnakeCase)

        /** An embed's footer. */
        final case class Footer(text: String, iconUrl: Maybe[String] = Absent) derives CanEqual
        object Footer:
            given Schema[Footer] = Schema.derived[Footer].renameAllFields(Schema.NameCase.SnakeCase)

        /** An embed's image or thumbnail; `height` and `width` are Discord's, ignored when sent. */
        final case class Media(url: String, height: Maybe[Int] = Absent, width: Maybe[Int] = Absent) derives CanEqual
        object Media:
            given Schema[Media] = Schema.derived[Media]

        /** An embed's author line. */
        final case class Author(name: String, url: Maybe[String] = Absent, iconUrl: Maybe[String] = Absent) derives CanEqual
        object Author:
            given Schema[Author] = Schema.derived[Author].renameAllFields(Schema.NameCase.SnakeCase)

        /** One field of an embed. */
        final case class Field(name: String, value: String, inline: Boolean = false) derives CanEqual
        object Field:
            given Schema[Field] = Schema.derived[Field]
    end Embed

    /** Which mentions in a message's content notify their targets (`resources/message.mdx`, "Allowed Mentions Object").
      *
      * `parse` names the kinds parsed from the content; `users` and `roles` list the ids allowed explicitly; `repliedUser` notifies the
      * author of a replied-to message. `AllowedMentions.none` notifies nobody, the safe choice for content a user wrote.
      *
      * IMPORTANT: `init` refuses more than 100 users or roles, and a kind both parsed and listed, which Discord refuses, with
      * [[kyo.DiscordInvalidMessageException]].
      */
    final case class AllowedMentions private[kyo] (
        parse: Chunk[AllowedMentions.Kind],
        users: Chunk[UserId],
        roles: Chunk[RoleId],
        repliedUser: Boolean
    ) derives CanEqual

    object AllowedMentions:

        /** The allowed mentions, or a [[kyo.DiscordInvalidMessageException]] naming what Discord would refuse. */
        def init(
            parse: Chunk[Kind] = Chunk.empty,
            users: Chunk[UserId] = Chunk.empty,
            roles: Chunk[RoleId] = Chunk.empty,
            repliedUser: Boolean = false
        )(using Frame): Result[DiscordInvalidMessageException, AllowedMentions] =
            import DiscordInvalidMessageException.Problem
            val problem: Maybe[Problem] =
                if users.size > 100 then Present(Problem.MentionCount("users", users.size, 100))
                else if roles.size > 100 then Present(Problem.MentionCount("roles", roles.size, 100))
                else if users.nonEmpty && parse.contains(Kind.Users) then Present(Problem.MentionConflict("users"))
                else if roles.nonEmpty && parse.contains(Kind.Roles) then Present(Problem.MentionConflict("roles"))
                else Absent
            problem match
                case Present(p) => Result.fail(DiscordInvalidMessageException(p))
                case Absent     => Result.succeed(new AllowedMentions(parse, users, roles, repliedUser))
        end init

        /** Mentions notify nobody. */
        val none: AllowedMentions = new AllowedMentions(Chunk.empty, Chunk.empty, Chunk.empty, false)

        // `parse` is always written: an empty list means "parse nothing", where an absent one means Discord's default of everything.
        given Schema[AllowedMentions] = Schema.derived[AllowedMentions].renameAllFields(Schema.NameCase.SnakeCase)

        /** A kind of mention parsed from content: role, user, or `@everyone` and `@here`. */
        enum Kind derives CanEqual:
            case Roles, Users, Everyone

        object Kind:
            given Schema[Kind] =
                Schema.derived[Kind].tagOnly.variantNames("Roles" -> "roles", "Users" -> "users", "Everyone" -> "everyone")
        end Kind
    end AllowedMentions

    /** A message (`resources/message.mdx`, "Message Object"), as a REST call answers it and as `MESSAGE_CREATE` and `MESSAGE_UPDATE`
      * carry it, where `guildId` and `member` are added for a guild message.
      *
      * Without the `MESSAGE_CONTENT` intent, `content`, `embeds`, `attachments` and `components` are empty on a message the bot was not
      * mentioned in.
      */
    final case class Message(
        id: MessageId,
        channelId: ChannelId,
        guildId: Maybe[GuildId] = Absent,
        author: User,
        member: Maybe[Member] = Absent,
        content: String = "",
        timestamp: Instant,
        editedTimestamp: Maybe[Instant] = Absent,
        tts: Boolean = false,
        mentionEveryone: Boolean = false,
        mentions: Chunk[User] = Chunk.empty,
        mentionRoles: Chunk[RoleId] = Chunk.empty,
        attachments: Chunk[Attachment] = Chunk.empty,
        embeds: Chunk[Embed] = Chunk.empty,
        components: Chunk[Component] = Chunk.empty,
        pinned: Boolean = false,
        webhookId: Maybe[WebhookId] = Absent,
        `type`: Message.Type = Message.Type.Default,
        flags: Message.Flags = Message.Flags.Empty,
        messageReference: Maybe[Message.Reference] = Absent,
        applicationId: Maybe[ApplicationId] = Absent,
        thread: Maybe[Channel] = Absent
    ) derives CanEqual

    object Message:
        given Schema[Message] = Schema.derived[Message].renameAllFields(Schema.NameCase.SnakeCase)

        /** A message's kind, Discord's integer (`resources/message.mdx`, "Message Types"); a kind added later keeps its number. */
        type Type = Type.Value
        object Type:
            opaque type Value = Int
            def apply(value: Int): Type           = value
            extension (self: Type) def value: Int = self
            val Default: Type                     = 0
            val Reply: Type                       = 19
            val ChatInputCommand: Type            = 20
            val ThreadStarterMessage: Type        = 21
            val ContextMenuCommand: Type          = 23
            given Schema[Type]                    = Schema.intSchema.transform[Type](apply)(_.value)
            given CanEqual[Type, Type]            = CanEqual.derived
        end Type

        /** A message's flags, a bit set (`resources/message.mdx`, "Message Flags"). Combine flags with `union`. */
        type Flags = Flags.Value
        object Flags:
            opaque type Value = Int
            def apply(bits: Int): Flags      = bits
            val Empty: Flags                 = 0
            val Crossposted: Flags           = 1 << 0
            val IsCrosspost: Flags           = 1 << 1
            val SuppressEmbeds: Flags        = 1 << 2
            val HasThread: Flags             = 1 << 5
            val Ephemeral: Flags             = 1 << 6
            val Loading: Flags               = 1 << 7
            val SuppressNotifications: Flags = 1 << 12
            val IsVoiceMessage: Flags        = 1 << 13
            val IsComponentsV2: Flags        = 1 << 15

            /** The flags a message can be sent with, on a channel or as an interaction's answer. */
            private[kyo] val Sendable: Int =
                SuppressEmbeds.value | Ephemeral.value | SuppressNotifications.value | IsVoiceMessage.value | IsComponentsV2.value

            extension (self: Flags)
                def value: Int                      = self
                def union(other: Flags): Flags      = Flags(self.value | other.value)
                def contains(other: Flags): Boolean = (self.value & other.value) == other.value
            end extension
            given Schema[Flags]          = Schema.intSchema.transform[Flags](apply)(_.value)
            given CanEqual[Flags, Flags] = CanEqual.derived
        end Flags

        /** The message a message replies to or forwards (`resources/message.mdx`, "Message Reference Object"). */
        final case class Reference(
            messageId: Maybe[MessageId] = Absent,
            channelId: Maybe[ChannelId] = Absent,
            guildId: Maybe[GuildId] = Absent,
            failIfNotExists: Maybe[Boolean] = Absent
        ) derives CanEqual

        object Reference:
            /** A reply to `message`, sent without the reply when the message no longer exists. */
            def reply(message: MessageId): Reference = Reference(messageId = Present(message), failIfNotExists = Present(false))

            given Schema[Reference] = Schema.derived[Reference].renameAllFields(Schema.NameCase.SnakeCase)
        end Reference

        /** A message to send (`resources/message.mdx`, "Create Message"), as `send`, `followUp` and an interaction's answer take it.
          * `files` go as the parts of a multipart body; the rest is the JSON.
          *
          * IMPORTANT: `init` refuses a message Discord would refuse, with [[kyo.DiscordInvalidMessageException]]: no content, embed,
          * component or file; content of more than 2000 characters; more than 10 embeds or more than 6000 characters across them; more
          * than 5 top-level components; more than 10 files; a flag outside `SuppressEmbeds`, `Ephemeral`, `SuppressNotifications`,
          * `IsVoiceMessage` and `IsComponentsV2` (`Ephemeral` applies only to an interaction's answer and follow-ups).
          */
        final case class Create private[kyo] (
            content: Maybe[String] = Absent,
            tts: Boolean = false,
            @kyo.schema.omit(kyo.schema.omit.WhenEmpty) embeds: Chunk[Embed] = Chunk.empty,
            allowedMentions: Maybe[AllowedMentions] = Absent,
            messageReference: Maybe[Reference] = Absent,
            @kyo.schema.omit(kyo.schema.omit.WhenEmpty) components: Chunk[Component] = Chunk.empty,
            flags: Maybe[Flags] = Absent,
            @kyo.schema.transient() files: Chunk[File] = Chunk.empty
        ) derives CanEqual

        object Create:

            /** The message, or a [[kyo.DiscordInvalidMessageException]] naming the first limit it breaks. */
            def init(
                content: Maybe[String] = Absent,
                embeds: Chunk[Embed] = Chunk.empty,
                components: Chunk[Component] = Chunk.empty,
                files: Chunk[File] = Chunk.empty,
                allowedMentions: Maybe[AllowedMentions] = Absent,
                reference: Maybe[Reference] = Absent,
                flags: Maybe[Flags] = Absent,
                tts: Boolean = false
            )(using Frame): Result[DiscordInvalidMessageException, Create] =
                import DiscordInvalidMessageException.Problem
                val problem =
                    if content.isEmpty && embeds.isEmpty && components.isEmpty && files.isEmpty then Present(Problem.Empty)
                    else
                        contentProblem(content.map(Patch.Set(_)))
                            .orElse(embedsProblem(embeds))
                            .orElse(if components.size > 5 then Present(Problem.ComponentCount(components.size, 5)) else Absent)
                            .orElse(if files.size > 10 then Present(Problem.FileCount(files.size, 10)) else Absent)
                            .orElse(flags.filter(f => (f.value & ~Flags.Sendable) != 0).map(f => Problem.Flags(f.value)))
                problem match
                    case Present(p) => Result.fail(DiscordInvalidMessageException(p))
                    case Absent     =>
                        Result.succeed(new Create(content, tts, embeds, allowedMentions, reference, components, flags, files))
                end match
            end init

            given Schema[Create] = Schema.derived[Create].renameAllFields(Schema.NameCase.SnakeCase)
        end Create

        /** An edit of a message (`resources/message.mdx`, "Edit Message"). A field left `Absent` stays as it is. `content` is cleared
          * with `Present(Patch.Clear)`; `embeds` and `components` are cleared with an empty chunk.
          *
          * IMPORTANT: `init` refuses an edit past the limits of [[kyo.Discord.Message.Create]], with
          * [[kyo.DiscordInvalidMessageException]]; only `SuppressEmbeds` and `IsComponentsV2` can be set as flags.
          */
        final case class Edit private[kyo] (
            content: Maybe[Patch[String]] = Absent,
            embeds: Maybe[Chunk[Embed]] = Absent,
            flags: Maybe[Flags] = Absent,
            allowedMentions: Maybe[AllowedMentions] = Absent,
            components: Maybe[Chunk[Component]] = Absent
        ) derives CanEqual

        object Edit:

            /** The edit, or a [[kyo.DiscordInvalidMessageException]] naming the first limit it breaks. */
            def init(
                content: Maybe[Patch[String]] = Absent,
                embeds: Maybe[Chunk[Embed]] = Absent,
                components: Maybe[Chunk[Component]] = Absent,
                allowedMentions: Maybe[AllowedMentions] = Absent,
                flags: Maybe[Flags] = Absent
            )(using Frame): Result[DiscordInvalidMessageException, Edit] =
                import DiscordInvalidMessageException.Problem
                val problem =
                    contentProblem(content)
                        .orElse(embeds.flatMap(embedsProblem))
                        .orElse(components.filter(_.size > 5).map(c => Problem.ComponentCount(c.size, 5)))
                        .orElse(flags.filter(f => (f.value & ~(Flags.SuppressEmbeds.value | Flags.IsComponentsV2.value)) != 0)
                            .map(f => Problem.Flags(f.value)))
                problem match
                    case Present(p) => Result.fail(DiscordInvalidMessageException(p))
                    case Absent     => Result.succeed(new Edit(content, embeds, flags, allowedMentions, components))
            end init

            given Schema[Edit] = Schema.derived[Edit].renameAllFields(Schema.NameCase.SnakeCase)
        end Edit

        /** Which messages `messages` reads (`resources/message.mdx`, "Get Channel Messages"): the latest, or those around, before or
          * after a message, at most `limit` of them.
          *
          * IMPORTANT: `init` refuses a `limit` outside 1 to 100 with [[kyo.DiscordInvalidMessageException]].
          */
        final case class Page private (anchor: Page.Anchor, limit: Int) derives CanEqual

        object Page:
            /** Where a page starts. */
            enum Anchor derives CanEqual:
                case Latest
                case Around(message: MessageId)
                case Before(message: MessageId)
                case After(message: MessageId)
            end Anchor

            /** The page, or a [[kyo.DiscordInvalidMessageException]] when `limit` is outside 1 to 100. */
            def init(anchor: Anchor = Anchor.Latest, limit: Int = 50)(using Frame): Result[DiscordInvalidMessageException, Page] =
                if limit >= 1 && limit <= 100 then Result.succeed(new Page(anchor, limit))
                else Result.fail(DiscordInvalidMessageException(DiscordInvalidMessageException.Problem.PageLimit(limit)))
        end Page

        private def contentProblem(content: Maybe[Patch[String]]): Maybe[DiscordInvalidMessageException.Problem] =
            content match
                case Present(Patch.Set(text)) =>
                    val length = text.codePointCount(0, text.length)
                    if length > 2000 then Present(DiscordInvalidMessageException.Problem.ContentLength(length, 2000)) else Absent
                case _ => Absent

        private def embedsProblem(embeds: Chunk[Embed]): Maybe[DiscordInvalidMessageException.Problem] =
            val total = embeds.foldLeft(0)(_ + _.characters)
            if embeds.size > 10 then Present(DiscordInvalidMessageException.Problem.EmbedCount(embeds.size, 10))
            else if total > 6000 then Present(DiscordInvalidMessageException.Problem.EmbedTotal(total, 6000))
            else Absent
        end embedsProblem
    end Message

    /** An interactive message component (`components/reference.mdx`): an action row holding buttons or a select, a button, a select
      * menu, or a modal's text input. Discord tags each kind by an integer `type`, which is each case's `@tagNumber`. A kind the model
      * does not declare is [[kyo.Discord.Component.Other]], kept whole.
      *
      * IMPORTANT: each kind's `init` refuses what Discord would, with [[kyo.DiscordInvalidComponentException]]: a custom id outside 1
      * to 100 characters, a label or placeholder past its bound, a button without its target, a select's values outside its options,
      * a row that is not 1 to 5 buttons or one other component.
      */
    @kyo.schema.discriminator("type")
    sealed trait Component derives CanEqual

    object Component:

        /** A row of 1 to 5 buttons or one select (type 1). */
        @kyo.schema.tagNumber(1)
        final case class ActionRow private[kyo] (components: Chunk[Component]) extends Component

        object ActionRow:
            /** The row, or a [[kyo.DiscordInvalidComponentException]] when it is not 1 to 5 buttons or one other component. */
            def init(components: Chunk[Component])(using Frame): Result[DiscordInvalidComponentException, ActionRow] =
                val buttons = components.count(_.isInstanceOf[Button])
                val valid   = components.nonEmpty && ((buttons == components.size && buttons <= 5) || components.size == 1)
                if valid then Result.succeed(new ActionRow(components))
                else Result.fail(DiscordInvalidComponentException(DiscordInvalidComponentException.Problem.RowContents(components.size)))
            end init
            given Schema[ActionRow] = Schema.derived[ActionRow]
        end ActionRow

        /** A button (type 2). A link button (`Style.Link`) opens `url` and sends no interaction; any other sends `customId`. */
        @kyo.schema.tagNumber(2)
        final case class Button private[kyo] (
            style: Button.Style,
            label: Maybe[String] = Absent,
            emoji: Maybe[Emoji] = Absent,
            customId: Maybe[String] = Absent,
            url: Maybe[String] = Absent,
            disabled: Boolean = false
        ) extends Component

        object Button:
            /** The button, or a [[kyo.DiscordInvalidComponentException]] naming what Discord would refuse. */
            def init(
                style: Style,
                label: Maybe[String] = Absent,
                emoji: Maybe[Emoji] = Absent,
                customId: Maybe[String] = Absent,
                url: Maybe[String] = Absent,
                disabled: Boolean = false
            )(using Frame): Result[DiscordInvalidComponentException, Button] =
                import DiscordInvalidComponentException.Problem
                val link    = style == Style.Link
                val problem =
                    if link != url.nonEmpty || link == customId.nonEmpty then Present(Problem.ButtonTarget)
                    else
                        customIdProblem(customId)
                            .orElse(url.map(length).filter(l => l < 1 || l > 512).map(Problem.Url(_)))
                            .orElse(label.map(length).filter(_ > 80).map(Problem.Label(_, 80)))
                problem match
                    case Present(p) => Result.fail(DiscordInvalidComponentException(p))
                    case Absent     => Result.succeed(new Button(style, label, emoji, customId, url, disabled))
            end init

            /** A button's look and target, Discord's integer (`components/reference.mdx`, "Button Styles"). */
            type Style = Style.Value
            object Style:
                opaque type Value = Int
                def apply(value: Int): Style           = value
                extension (self: Style) def value: Int = self
                val Primary: Style                     = 1
                val Secondary: Style                   = 2
                val Success: Style                     = 3
                val Danger: Style                      = 4
                val Link: Style                        = 5
                given Schema[Style]                    = Schema.intSchema.transform[Style](apply)(_.value)
                given CanEqual[Style, Style]           = CanEqual.derived
            end Style

            given Schema[Button] = Schema.derived[Button].renameAllFields(Schema.NameCase.SnakeCase)
        end Button

        /** A select of 1 to 25 developer-defined options (type 3). */
        @kyo.schema.tagNumber(3)
        final case class StringSelect private[kyo] (
            customId: String,
            options: Chunk[SelectOption],
            placeholder: Maybe[String] = Absent,
            minValues: Maybe[Int] = Absent,
            maxValues: Maybe[Int] = Absent,
            disabled: Boolean = false
        ) extends Component

        object StringSelect:
            /** The select, or a [[kyo.DiscordInvalidComponentException]] naming what Discord would refuse. */
            def init(
                customId: String,
                options: Chunk[SelectOption],
                placeholder: Maybe[String] = Absent,
                minValues: Maybe[Int] = Absent,
                maxValues: Maybe[Int] = Absent,
                disabled: Boolean = false
            )(using Frame): Result[DiscordInvalidComponentException, StringSelect] =
                import DiscordInvalidComponentException.Problem
                val problem =
                    customIdProblem(Present(customId))
                        .orElse(if options.isEmpty || options.size > 25 then Present(Problem.OptionCount(options.size)) else Absent)
                        .orElse(placeholderProblem(placeholder))
                        .orElse(valuesProblem(minValues, maxValues, options.size))
                problem match
                    case Present(p) => Result.fail(DiscordInvalidComponentException(p))
                    case Absent     => Result.succeed(new StringSelect(customId, options, placeholder, minValues, maxValues, disabled))
            end init
            given Schema[StringSelect] = Schema.derived[StringSelect].renameAllFields(Schema.NameCase.SnakeCase)
        end StringSelect

        /** One option of a [[kyo.Discord.Component.StringSelect]]: `label` shown, `value` sent, each 1 to 100 characters. */
        final case class SelectOption private[kyo] (
            label: String,
            value: String,
            description: Maybe[String] = Absent,
            emoji: Maybe[Emoji] = Absent,
            default: Boolean = false
        ) derives CanEqual

        object SelectOption:
            /** The option, or a [[kyo.DiscordInvalidComponentException]] when a text is outside its bound. */
            def init(
                label: String,
                value: String,
                description: Maybe[String] = Absent,
                emoji: Maybe[Emoji] = Absent,
                default: Boolean = false
            )(using Frame): Result[DiscordInvalidComponentException, SelectOption] =
                import DiscordInvalidComponentException.Problem
                val problem =
                    if length(label) < 1 || length(label) > 100 then Present(Problem.OptionText("label", length(label)))
                    else if length(value) < 1 || length(value) > 100 then Present(Problem.OptionText("value", length(value)))
                    else description.map(length).filter(_ > 100).map(Problem.OptionText("description", _))
                problem match
                    case Present(p) => Result.fail(DiscordInvalidComponentException(p))
                    case Absent     => Result.succeed(new SelectOption(label, value, description, emoji, default))
            end init
            given Schema[SelectOption] = Schema.derived[SelectOption]
        end SelectOption

        /** A modal's text field (type 4): `Style.Short` for one line, `Style.Paragraph` for several. In a submitted modal, `value` is
          * what the user wrote.
          */
        @kyo.schema.tagNumber(4)
        final case class TextInput private[kyo] (
            customId: String,
            style: TextInput.Style = TextInput.Style.Short,
            label: Maybe[String] = Absent,
            minLength: Maybe[Int] = Absent,
            maxLength: Maybe[Int] = Absent,
            required: Boolean = true,
            value: Maybe[String] = Absent,
            placeholder: Maybe[String] = Absent
        ) extends Component

        object TextInput:
            /** The text input, or a [[kyo.DiscordInvalidComponentException]] naming what Discord would refuse. */
            def init(
                customId: String,
                style: Style,
                label: Maybe[String] = Absent,
                minLength: Maybe[Int] = Absent,
                maxLength: Maybe[Int] = Absent,
                required: Boolean = true,
                value: Maybe[String] = Absent,
                placeholder: Maybe[String] = Absent
            )(using Frame): Result[DiscordInvalidComponentException, TextInput] =
                import DiscordInvalidComponentException.Problem
                val min     = minLength.getOrElse(0)
                val max     = maxLength.getOrElse(4000)
                val problem =
                    customIdProblem(Present(customId))
                        .orElse(label.map(length).filter(_ > 45).map(Problem.Label(_, 45)))
                        .orElse(if min < 0 || min > 4000 || max < 1 || max > 4000 || min > max then Present(Problem.TextLength(min, max))
                        else Absent)
                        .orElse(value.map(length).filter(_ > 4000).map(Problem.Value(_)))
                        .orElse(placeholder.map(length).filter(_ > 100).map(Problem.Placeholder(_, 100)))
                problem match
                    case Present(p) => Result.fail(DiscordInvalidComponentException(p))
                    case Absent     =>
                        Result.succeed(new TextInput(customId, style, label, minLength, maxLength, required, value, placeholder))
                end match
            end init

            /** One line or several, Discord's integer (`components/reference.mdx`, "Text Input Styles"). */
            type Style = Style.Value
            object Style:
                opaque type Value = Int
                def apply(value: Int): Style           = value
                extension (self: Style) def value: Int = self
                val Short: Style                       = 1
                val Paragraph: Style                   = 2
                given Schema[Style]                    = Schema.intSchema.transform[Style](apply)(_.value)
                given CanEqual[Style, Style]           = CanEqual.derived
            end Style

            given Schema[TextInput] = Schema.derived[TextInput].renameAllFields(Schema.NameCase.SnakeCase)
        end TextInput

        /** A select whose options Discord fills with the guild's users (type 5). */
        @kyo.schema.tagNumber(5)
        final case class UserSelect private[kyo] (
            customId: String,
            placeholder: Maybe[String] = Absent,
            minValues: Maybe[Int] = Absent,
            maxValues: Maybe[Int] = Absent,
            disabled: Boolean = false
        ) extends Component

        object UserSelect:
            /** The select, or a [[kyo.DiscordInvalidComponentException]] naming what Discord would refuse. */
            def init(
                customId: String,
                placeholder: Maybe[String] = Absent,
                minValues: Maybe[Int] = Absent,
                maxValues: Maybe[Int] = Absent,
                disabled: Boolean = false
            )(using Frame): Result[DiscordInvalidComponentException, UserSelect] =
                entitySelect(
                    customId,
                    placeholder,
                    minValues,
                    maxValues
                )(new UserSelect(customId, placeholder, minValues, maxValues, disabled))
            given Schema[UserSelect] = Schema.derived[UserSelect].renameAllFields(Schema.NameCase.SnakeCase)
        end UserSelect

        /** A select whose options Discord fills with the guild's roles (type 6). */
        @kyo.schema.tagNumber(6)
        final case class RoleSelect private[kyo] (
            customId: String,
            placeholder: Maybe[String] = Absent,
            minValues: Maybe[Int] = Absent,
            maxValues: Maybe[Int] = Absent,
            disabled: Boolean = false
        ) extends Component

        object RoleSelect:
            /** The select, or a [[kyo.DiscordInvalidComponentException]] naming what Discord would refuse. */
            def init(
                customId: String,
                placeholder: Maybe[String] = Absent,
                minValues: Maybe[Int] = Absent,
                maxValues: Maybe[Int] = Absent,
                disabled: Boolean = false
            )(using Frame): Result[DiscordInvalidComponentException, RoleSelect] =
                entitySelect(
                    customId,
                    placeholder,
                    minValues,
                    maxValues
                )(new RoleSelect(customId, placeholder, minValues, maxValues, disabled))
            given Schema[RoleSelect] = Schema.derived[RoleSelect].renameAllFields(Schema.NameCase.SnakeCase)
        end RoleSelect

        /** A select whose options Discord fills with the guild's users and roles (type 7). */
        @kyo.schema.tagNumber(7)
        final case class MentionableSelect private[kyo] (
            customId: String,
            placeholder: Maybe[String] = Absent,
            minValues: Maybe[Int] = Absent,
            maxValues: Maybe[Int] = Absent,
            disabled: Boolean = false
        ) extends Component

        object MentionableSelect:
            /** The select, or a [[kyo.DiscordInvalidComponentException]] naming what Discord would refuse. */
            def init(
                customId: String,
                placeholder: Maybe[String] = Absent,
                minValues: Maybe[Int] = Absent,
                maxValues: Maybe[Int] = Absent,
                disabled: Boolean = false
            )(using Frame): Result[DiscordInvalidComponentException, MentionableSelect] =
                entitySelect(customId, placeholder, minValues, maxValues)(
                    new MentionableSelect(customId, placeholder, minValues, maxValues, disabled)
                )
            given Schema[MentionableSelect] =
                Schema.derived[MentionableSelect].renameAllFields(Schema.NameCase.SnakeCase)
        end MentionableSelect

        /** A select whose options Discord fills with the guild's channels (type 8); `channelTypes` narrows them. */
        @kyo.schema.tagNumber(8)
        final case class ChannelSelect private[kyo] (
            customId: String,
            placeholder: Maybe[String] = Absent,
            minValues: Maybe[Int] = Absent,
            maxValues: Maybe[Int] = Absent,
            disabled: Boolean = false,
            @kyo.schema.omit(kyo.schema.omit.WhenEmpty) channelTypes: Chunk[Channel.Type] = Chunk.empty
        ) extends Component

        object ChannelSelect:
            /** The select, or a [[kyo.DiscordInvalidComponentException]] naming what Discord would refuse. */
            def init(
                customId: String,
                placeholder: Maybe[String] = Absent,
                minValues: Maybe[Int] = Absent,
                maxValues: Maybe[Int] = Absent,
                disabled: Boolean = false,
                channelTypes: Chunk[Channel.Type] = Chunk.empty
            )(using Frame): Result[DiscordInvalidComponentException, ChannelSelect] =
                entitySelect(customId, placeholder, minValues, maxValues)(
                    new ChannelSelect(customId, placeholder, minValues, maxValues, disabled, channelTypes)
                )
            given Schema[ChannelSelect] = Schema.derived[ChannelSelect].renameAllFields(Schema.NameCase.SnakeCase)
        end ChannelSelect

        /** A component of a kind the model does not declare, such as a Components V2 layout; `payload` is its JSON, `type` included. */
        @kyo.schema.catchAll()
        final case class Other(`type`: Int, payload: RawJson) extends Component

        given Schema[Component] = Schema.derived[Component]

        private def entitySelect[A](customId: String, placeholder: Maybe[String], min: Maybe[Int], max: Maybe[Int])(
            select: => A
        )(using Frame): Result[DiscordInvalidComponentException, A] =
            val problem: Maybe[DiscordInvalidComponentException.Problem] =
                customIdProblem(Present(customId)).orElse(placeholderProblem(placeholder)).orElse(valuesProblem(min, max, 25))
            problem match
                case Present(p) => Result.fail(DiscordInvalidComponentException(p))
                case Absent     => Result.succeed(select)
        end entitySelect

        private def length(text: String): Int = text.codePointCount(0, text.length)

        private def customIdProblem(customId: Maybe[String]): Maybe[DiscordInvalidComponentException.Problem] =
            customId.map(length).filter(l => l < 1 || l > 100).map(DiscordInvalidComponentException.Problem.CustomId(_))

        private def placeholderProblem(placeholder: Maybe[String]): Maybe[DiscordInvalidComponentException.Problem] =
            placeholder.map(length).filter(_ > 150).map(DiscordInvalidComponentException.Problem.Placeholder(_, 150))

        private def valuesProblem(min: Maybe[Int], max: Maybe[Int], options: Int): Maybe[DiscordInvalidComponentException.Problem] =
            val lo = min.getOrElse(1)
            val hi = max.getOrElse(1)
            if lo < 0 || lo > 25 || hi < 1 || hi > 25 || lo > hi || hi > options then
                Present(DiscordInvalidComponentException.Problem.Values(lo, hi))
            else Absent
        end valuesProblem
    end Component

    /** An application command as Discord answers it (`interactions/application-commands.mdx`, "Application Command Object"). */
    final case class Command(
        id: CommandId,
        applicationId: ApplicationId,
        guildId: Maybe[GuildId] = Absent,
        name: String,
        description: String = "",
        `type`: Command.Type = Command.Type.ChatInput,
        options: Chunk[Command.Option] = Chunk.empty,
        defaultMemberPermissions: Maybe[String] = Absent,
        nsfw: Boolean = false
    ) derives CanEqual

    object Command:
        given Schema[Command] = Schema.derived[Command].renameAllFields(Schema.NameCase.SnakeCase)

        /** A command's kind, Discord's integer: a slash command, or a user or message context-menu command. */
        type Type = Type.Value
        object Type:
            opaque type Value = Int
            def apply(value: Int): Type           = value
            extension (self: Type) def value: Int = self
            val ChatInput: Type                   = 1
            val User: Type                        = 2
            val Message: Type                     = 3
            given Schema[Type]                    = Schema.intSchema.transform[Type](apply)(_.value)
            given CanEqual[Type, Type]            = CanEqual.derived
        end Type

        /** A value Discord types by the option it belongs to: text, an integer, a number or a boolean. Its JSON is the bare value. */
        enum Value derives CanEqual:
            case Text(value: String)
            case Integer(value: Long)
            case Number(value: Double)
            case Bool(value: Boolean)
        end Value

        object Value:
            // Each variant is its bare JSON value, so the sum is untagged; a JSON integer is both an Integer and a Number, and the
            // declaration order reads it as an Integer.
            given Schema[Value.Text]    = Schema.stringSchema.transform[Value.Text](new Value.Text(_))(_.value)
            given Schema[Value.Integer] = Schema.longSchema.transform[Value.Integer](new Value.Integer(_))(_.value)
            given Schema[Value.Number]  = Schema.doubleSchema.transform[Value.Number](new Value.Number(_))(_.value)
            given Schema[Value.Bool]    = Schema.booleanSchema.transform[Value.Bool](new Value.Bool(_))(_.value)
            given Schema[Value]         = Schema.derived[Value].untagged.unionAmbiguity(Schema.UnionAmbiguity.FirstMatch)
        end Value

        /** A parameter, subcommand or subcommand group of a command (`interactions/application-commands.mdx`, "Application Command
          * Option Structure").
          *
          * IMPORTANT: `init` refuses a name outside 1 to 32 lowercase letters, digits, `-`, `_` and `'`, a description outside 1 to 100
          * characters, more than 25 choices or nested options, choices beside autocomplete, string length bounds outside 0 to 6000, and
          * a required nested option after an optional one, with [[kyo.DiscordInvalidCommandException]].
          */
        final case class Option private[kyo] (
            `type`: Option.Type,
            name: String,
            description: String,
            required: Boolean = false,
            @kyo.schema.omit(kyo.schema.omit.WhenEmpty) choices: Chunk[Choice] = Chunk.empty,
            @kyo.schema.omit(kyo.schema.omit.WhenEmpty) options: Chunk[Option] = Chunk.empty,
            @kyo.schema.omit(kyo.schema.omit.WhenEmpty) channelTypes: Chunk[Channel.Type] = Chunk.empty,
            minValue: Maybe[Value] = Absent,
            maxValue: Maybe[Value] = Absent,
            minLength: Maybe[Int] = Absent,
            maxLength: Maybe[Int] = Absent,
            autocomplete: Boolean = false
        ) derives CanEqual

        object Option:
            /** The option, or a [[kyo.DiscordInvalidCommandException]] naming what Discord would refuse. */
            def init(
                `type`: Type,
                name: String,
                description: String,
                required: Boolean = false,
                choices: Chunk[Choice] = Chunk.empty,
                options: Chunk[Option] = Chunk.empty,
                channelTypes: Chunk[Channel.Type] = Chunk.empty,
                minValue: Maybe[Value] = Absent,
                maxValue: Maybe[Value] = Absent,
                minLength: Maybe[Int] = Absent,
                maxLength: Maybe[Int] = Absent,
                autocomplete: Boolean = false
            )(using Frame): Result[DiscordInvalidCommandException, Option] =
                import DiscordInvalidCommandException.Problem
                val minL    = minLength.getOrElse(0)
                val maxL    = maxLength.getOrElse(6000)
                val problem =
                    nameProblem(name)
                        .orElse(descriptionProblem(description, 100))
                        .orElse(if choices.size > 25 then Present(Problem.ChoiceCount(choices.size)) else Absent)
                        .orElse(if options.size > 25 then Present(Problem.OptionCount(options.size)) else Absent)
                        .orElse(if choices.nonEmpty && autocomplete then Present(Problem.ChoicesWithAutocomplete(name)) else Absent)
                        .orElse(if minL < 0 || minL > 6000 || maxL < 1 || maxL > 6000 || minL > maxL then
                            Present(Problem.StringLength(minL, maxL))
                        else Absent)
                        .orElse(orderProblem(options))
                problem match
                    case Present(p) => Result.fail(DiscordInvalidCommandException(p))
                    case Absent     =>
                        Result.succeed(new Option(
                            `type`,
                            name,
                            description,
                            required,
                            choices,
                            options,
                            channelTypes,
                            minValue,
                            maxValue,
                            minLength,
                            maxLength,
                            autocomplete
                        ))
                end match
            end init

            /** An option's kind, Discord's integer (`interactions/application-commands.mdx`, "Application Command Option Type"). */
            type Type = Type.Value
            object Type:
                opaque type Value = Int
                def apply(value: Int): Type           = value
                extension (self: Type) def value: Int = self
                val Subcommand: Type                  = 1
                val SubcommandGroup: Type             = 2
                val String: Type                      = 3
                val Integer: Type                     = 4
                val Boolean: Type                     = 5
                val User: Type                        = 6
                val Channel: Type                     = 7
                val Role: Type                        = 8
                val Mentionable: Type                 = 9
                val Number: Type                      = 10
                val Attachment: Type                  = 11
                given Schema[Type]                    = Schema.intSchema.transform[Type](apply)(_.value)
                given CanEqual[Type, Type]            = CanEqual.derived
            end Type

            given Schema[Option] = Schema.derived[Option].renameAllFields(Schema.NameCase.SnakeCase)
        end Option

        /** One choice of an option, or an autocomplete suggestion: `name` shown (1 to 100 characters), `value` sent. */
        final case class Choice private[kyo] (name: String, value: Value) derives CanEqual

        object Choice:
            /** The choice, or a [[kyo.DiscordInvalidCommandException]] when a text is outside its bound. */
            def init(name: String, value: Value)(using Frame): Result[DiscordInvalidCommandException, Choice] =
                import DiscordInvalidCommandException.Problem
                val nameLength              = name.codePointCount(0, name.length)
                val problem: Maybe[Problem] =
                    if nameLength < 1 || nameLength > 100 then Present(Problem.ChoiceName(nameLength))
                    else
                        value match
                            case Value.Text(text) if text.codePointCount(0, text.length) > 100 =>
                                Present(Problem.ChoiceValue(text.codePointCount(0, text.length)))
                            case _ => Absent
                problem match
                    case Present(p) => Result.fail(DiscordInvalidCommandException(p))
                    case Absent     => Result.succeed(new Choice(name, value))
            end init
            given Schema[Choice] = Schema.derived[Choice]
        end Choice

        /** A command to register (`interactions/application-commands.mdx`, "Create Global Application Command"), as `setCommands`
          * takes it. A user or message command has no description and no options.
          *
          * IMPORTANT: `init` refuses what [[kyo.Discord.Command.Option]]'s `init` does for the command's own name and description, a
          * user or message command with a description or options, and more than 25 options, with
          * [[kyo.DiscordInvalidCommandException]].
          */
        final case class Create private[kyo] (
            name: String,
            description: String = "",
            `type`: Type = Type.ChatInput,
            @kyo.schema.omit(kyo.schema.omit.WhenEmpty) options: Chunk[Option] = Chunk.empty,
            defaultMemberPermissions: Maybe[String] = Absent,
            nsfw: Boolean = false
        ) derives CanEqual

        object Create:
            /** The command, or a [[kyo.DiscordInvalidCommandException]] naming what Discord would refuse. */
            def init(
                name: String,
                description: String = "",
                `type`: Type = Type.ChatInput,
                options: Chunk[Option] = Chunk.empty,
                defaultMemberPermissions: Maybe[String] = Absent,
                nsfw: Boolean = false
            )(using Frame): Result[DiscordInvalidCommandException, Create] =
                import DiscordInvalidCommandException.Problem
                val chatInput = `type` == Type.ChatInput
                val problem   =
                    (if chatInput then nameProblem(name) else contextNameProblem(name))
                        .orElse(
                            if chatInput then descriptionProblem(description, 100)
                            else if description.nonEmpty || options.nonEmpty then Present(Problem.NotChatInput)
                            else Absent
                        )
                        .orElse(if options.size > 25 then Present(Problem.OptionCount(options.size)) else Absent)
                        .orElse(orderProblem(options))
                problem match
                    case Present(p) => Result.fail(DiscordInvalidCommandException(p))
                    case Absent     => Result.succeed(new Create(name, description, `type`, options, defaultMemberPermissions, nsfw))
            end init
            given Schema[Create] = Schema.derived[Create].renameAllFields(Schema.NameCase.SnakeCase)
        end Create

        /** The command an interaction invoked (`interactions/receiving-and-responding.mdx`, "Application Command Data Structure").
          * `targetId` is the snowflake of the user (`Type.User`) or message (`Type.Message`) a context-menu command targets; build the
          * id with `UserId(_)` or `MessageId(_)` by the command's type.
          */
        final case class Data(
            id: CommandId,
            name: String,
            `type`: Type = Type.ChatInput,
            options: Chunk[Data.Option] = Chunk.empty,
            resolved: Maybe[Resolved] = Absent,
            guildId: Maybe[GuildId] = Absent,
            @kyo.schema.transform(WireField.MaybeSnowflake) targetId: Maybe[Long] = Absent
        ) derives CanEqual

        object Data:
            given Schema[Data] = Schema.derived[Data].renameAllFields(Schema.NameCase.SnakeCase)

            /** A parameter the user filled in, or a subcommand with its own options; `focused` marks the one being autocompleted. */
            final case class Option(
                name: String,
                `type`: Command.Option.Type,
                value: Maybe[Value] = Absent,
                options: Chunk[Option] = Chunk.empty,
                focused: Boolean = false
            ) derives CanEqual

            object Option:
                given Schema[Option] = Schema.derived[Option]
        end Data

        private def nameProblem(name: String): Maybe[DiscordInvalidCommandException.Problem] =
            import DiscordInvalidCommandException.Problem
            val length = name.codePointCount(0, name.length)
            if length < 1 || length > 32 then Present(Problem.NameLength(length))
            else
                val bad = name.indexWhere(c =>
                    !((Character.isLetterOrDigit(c) && !Character.isUpperCase(c)) || c == '-' || c == '_' || c == '\'')
                )
                if bad >= 0 then Present(Problem.NameCharacter(bad)) else Absent
            end if
        end nameProblem

        private def contextNameProblem(name: String): Maybe[DiscordInvalidCommandException.Problem] =
            val length = name.codePointCount(0, name.length)
            if length < 1 || length > 32 then Present(DiscordInvalidCommandException.Problem.NameLength(length)) else Absent

        private def descriptionProblem(description: String, max: Int): Maybe[DiscordInvalidCommandException.Problem] =
            val length = description.codePointCount(0, description.length)
            if length < 1 || length > max then Present(DiscordInvalidCommandException.Problem.DescriptionLength(length, max)) else Absent

        private def orderProblem(options: Chunk[Option]): Maybe[DiscordInvalidCommandException.Problem] =
            val firstOptional = options.indexWhere(!_.required)
            if firstOptional < 0 then Absent
            else
                Maybe.fromOption(options.drop(firstOptional).find(_.required))
                    .map(o => DiscordInvalidCommandException.Problem.RequiredOrder(o.name))
            end if
        end orderProblem
    end Command

    /** The users, members, channels and attachments an interaction refers to by id (`interactions/receiving-and-responding.mdx`,
      * "Resolved Data Structure").
      */
    final case class Resolved(
        users: Map[UserId, User] = Map.empty,
        members: Map[UserId, Member] = Map.empty,
        channels: Map[ChannelId, Channel] = Map.empty,
        attachments: Map[AttachmentId, Attachment] = Map.empty
    ) derives CanEqual

    object Resolved:
        given Schema[Resolved] = Schema.derived[Resolved]

    /** Threads: a thread itself is a [[kyo.Discord.Channel]] whose `threadMetadata` is present. */
    object Thread:

        /** A thread to start (`resources/channel.mdx`, "Start Thread from Message" and "Start Thread without Message").
          *
          * IMPORTANT: `init` refuses a name outside 1 to 100 characters, an archive duration other than 60, 1440, 4320 or 10080
          * minutes, a slow mode outside 0 to 21600 seconds, and a type that is not a thread's, with
          * [[kyo.DiscordInvalidThreadException]].
          */
        final case class Start private[kyo] (
            name: String,
            autoArchiveDuration: Maybe[Int] = Absent,
            `type`: Maybe[Channel.Type] = Absent,
            invitable: Maybe[Boolean] = Absent,
            rateLimitPerUser: Maybe[Int] = Absent
        ) derives CanEqual

        object Start:
            /** The thread, or a [[kyo.DiscordInvalidThreadException]] naming what Discord would refuse. */
            def init(
                name: String,
                autoArchiveDuration: Maybe[Int] = Absent,
                `type`: Maybe[Channel.Type] = Absent,
                invitable: Maybe[Boolean] = Absent,
                rateLimitPerUser: Maybe[Int] = Absent
            )(using Frame): Result[DiscordInvalidThreadException, Start] =
                import DiscordInvalidThreadException.Problem
                val length      = name.codePointCount(0, name.length)
                val threadTypes = Chunk(Channel.Type.AnnouncementThread, Channel.Type.PublicThread, Channel.Type.PrivateThread)
                val problem     =
                    if length < 1 || length > 100 then Present(Problem.NameLength(length))
                    else
                        autoArchiveDuration.filter(d => !Chunk(60, 1440, 4320, 10080).contains(d)).map(Problem.AutoArchiveDuration(_))
                            .orElse(rateLimitPerUser.filter(s => s < 0 || s > 21600).map(Problem.RateLimitPerUser(_)))
                            .orElse(`type`.filter(t => !threadTypes.contains(t)).map(t => Problem.ThreadType(t.value)))
                problem match
                    case Present(p) => Result.fail(DiscordInvalidThreadException(p))
                    case Absent     => Result.succeed(new Start(name, autoArchiveDuration, `type`, invitable, rateLimitPerUser))
            end init
            given Schema[Start] = Schema.derived[Start].renameAllFields(Schema.NameCase.SnakeCase)
        end Start
    end Thread

    /** What `GET /gateway/bot` answers (`events/gateway.mdx`, "Get Gateway Bot"): the Gateway's URL, the recommended shard count, and
      * the session start budget.
      */
    final case class GatewayInfo(url: String, shards: Int, sessionStartLimit: GatewayInfo.SessionStartLimit) derives CanEqual

    object GatewayInfo:
        given Schema[GatewayInfo] = Schema.derived[GatewayInfo].renameAllFields(Schema.NameCase.SnakeCase)

        /** The Identify budget: `remaining` of `total` until `resetAfter`, at most `maxConcurrency` per 5 seconds. */
        final case class SessionStartLimit(
            total: Int,
            remaining: Int,
            @kyo.schema.transform(WireField.Millis) resetAfter: Duration,
            maxConcurrency: Int
        ) derives CanEqual

        object SessionStartLimit:
            given Schema[SessionStartLimit] =
                Schema.derived[SessionStartLimit].renameAllFields(Schema.NameCase.SnakeCase)
        end SessionStartLimit
    end GatewayInfo

    // --- Interactions ---

    /** An interaction as Discord sends it (`interactions/receiving-and-responding.mdx`, "Interaction Object"), the fields every kind
      * shares. `member` is present in a guild and `user` in a direct message; `message` is the message a component was on.
      * `appPermissions` is the bot's permission set where the interaction happened, as Discord's decimal string.
      */
    final case class Interaction(
        id: InteractionId,
        applicationId: ApplicationId,
        token: InteractionToken,
        guildId: Maybe[GuildId] = Absent,
        channelId: Maybe[ChannelId] = Absent,
        member: Maybe[Member] = Absent,
        user: Maybe[User] = Absent,
        locale: Maybe[String] = Absent,
        guildLocale: Maybe[String] = Absent,
        appPermissions: Maybe[String] = Absent,
        context: Maybe[Int] = Absent,
        message: Maybe[Message] = Absent
    ) derives CanEqual:
        /** What the interaction's follow-ups and edits are made with. */
        def ref: Interaction.Ref = Interaction.Ref(applicationId, id, token)

        /** Who invoked it: the member's user in a guild, the user in a direct message. */
        def invoker: Maybe[User] = member.flatMap(_.user).orElse(user)
    end Interaction

    object Interaction:
        given Schema[Interaction] = Schema.derived[Interaction].renameAllFields(Schema.NameCase.SnakeCase)

        /** An interaction's application, id and token: what `followUp`, `editResponse` and `deleteResponse` address it by. The token is
          * redacted when rendered.
          */
        final case class Ref(application: ApplicationId, id: InteractionId, token: InteractionToken) derives CanEqual

        /** The component a user used (`interactions/receiving-and-responding.mdx`, "Message Component Data Structure"): its
          * `customId` and kind, and the values chosen in a select.
          */
        final case class ComponentData(
            customId: String,
            componentType: Int,
            values: Chunk[String] = Chunk.empty,
            resolved: Maybe[Resolved] = Absent
        ) derives CanEqual

        object ComponentData:
            given Schema[ComponentData] = Schema.derived[ComponentData].renameAllFields(Schema.NameCase.SnakeCase)

        /** A submitted modal (`interactions/receiving-and-responding.mdx`, "Modal Submit Data Structure"). `components` is the JSON of
          * what the modal held, whose layout Discord may nest in action rows or labels; `text` and `selected` find a field by its
          * custom id at any depth.
          */
        final case class ModalData(customId: String, components: Chunk[RawJson] = Chunk.empty) derives CanEqual:

            /** The text the user wrote in the text input `customId`. */
            def text(customId: String): Maybe[String] =
                find(customId).flatMap(key(_, "value")) match
                    case Present(Structure.Value.Str(s)) => Present(s)
                    case _                               => Absent

            /** The values the user chose in the select `customId`. */
            def selected(customId: String): Chunk[String] =
                find(customId).flatMap(key(_, "values")) match
                    case Present(Structure.Value.Sequence(values)) => values.collect { case Structure.Value.Str(s) => s }
                    case _                                         => Chunk.empty

            private def key(component: Structure.Value, name: String): Maybe[Structure.Value] =
                component match
                    case Structure.Value.Record(fields) => Maybe.fromOption(fields.collectFirst { case (`name`, v) => v })
                    case _                              => Absent

            private def find(id: String): Maybe[Structure.Value] =
                def search(value: Structure.Value): Maybe[Structure.Value] =
                    value match
                        case Structure.Value.Record(fields) =>
                            if fields.exists((k, v) => k == "custom_id" && v == Structure.Value.Str(id)) then Present(value)
                            else firstOf(fields.map(_._2))
                        case Structure.Value.Sequence(elements) => firstOf(elements)
                        case _                                  => Absent
                def firstOf(values: Chunk[Structure.Value]): Maybe[Structure.Value] =
                    values.foldLeft(Absent: Maybe[Structure.Value])((found, v) => found.orElse(search(v)))
                firstOf(components.map(_.json))
            end find
        end ModalData

        object ModalData:
            given Schema[ModalData] = Schema.derived[ModalData].renameAllFields(Schema.NameCase.SnakeCase)
    end Interaction

    /** An answer to an interaction (`interactions/receiving-and-responding.mdx`, "Interaction Response Object"): `{type, data}`, the
      * integer `type` each case's `@tagNumber` and `data` the case.
      *
      * Discord admits different answers per interaction kind, so each kind's handler returns its own union and a wrong pairing does
      * not compile: a command takes [[kyo.Discord.InteractionResponse.ToCommand]], a component
      * [[kyo.Discord.InteractionResponse.ToComponent]], an autocomplete only [[kyo.Discord.InteractionResponse.ToAutocomplete]], a
      * modal submission [[kyo.Discord.InteractionResponse.ToModalSubmit]], which a modal is not part of.
      *
      * Long work answers `DeferredMessage` (or `DeferredUpdate` for a component) at once and edits the answer later with
      * `editResponse`.
      */
    @kyo.schema.adjacent("type", "data")
    sealed trait InteractionResponse derives CanEqual

    object InteractionResponse:
        /** An answer to a slash or context-menu command. */
        type ToCommand = Message | DeferredMessage | Modal

        /** An answer to a component use. */
        type ToComponent = Message | DeferredMessage | DeferredUpdate.type | UpdateMessage | Modal

        /** An answer to an autocomplete. */
        type ToAutocomplete = Autocomplete

        /** An answer to a modal submission. */
        type ToModalSubmit = Message | DeferredMessage | DeferredUpdate.type | UpdateMessage

        /** A message in the channel (callback type 4). */
        @kyo.schema.tagNumber(4)
        final case class Message(message: Discord.Message.Create) extends InteractionResponse
        object Message:
            given Schema[Message] = summon[Schema[Discord.Message.Create]].transform(Message(_))(_.message)

        /** "Thinking", with the message to follow by `editResponse` (type 5); `ephemeral` shows it to the invoker only. */
        @kyo.schema.tagNumber(5)
        final case class DeferredMessage(
            @kyo.schema.rename("flags") @kyo.schema.transform(EphemeralFlag) @kyo.schema.omit(kyo.schema.omit.WhenDefault)
            ephemeral: Boolean = false
        ) extends InteractionResponse

        /** `ephemeral` as Discord's `flags` integer, whose bit 64 is `Message.Flags.Ephemeral`. */
        private[kyo] object EphemeralFlag extends kyo.schema.Transformer.Of[Boolean](
                Schema.intSchema.transform[Boolean](flags => (flags & Discord.Message.Flags.Ephemeral.value) != 0)(ephemeral =>
                    if ephemeral then Discord.Message.Flags.Ephemeral.value else 0
                )
            )

        /** Acknowledged, with the component's message to be edited later (type 6). */
        @kyo.schema.tagNumber(6)
        case object DeferredUpdate extends InteractionResponse

        /** An edit of the message the component was on (type 7). */
        @kyo.schema.tagNumber(7)
        final case class UpdateMessage(edit: Discord.Message.Edit) extends InteractionResponse
        object UpdateMessage:
            given Schema[UpdateMessage] = summon[Schema[Discord.Message.Edit]].transform(UpdateMessage(_))(_.edit)

        /** Suggestions for the focused option (type 8). */
        @kyo.schema.tagNumber(8)
        final case class Autocomplete private[kyo] (choices: Chunk[Command.Choice]) extends InteractionResponse

        object Autocomplete:
            /** The suggestions, or a [[kyo.DiscordInvalidInteractionResponseException]] for more than 25. */
            def init(choices: Chunk[Command.Choice])(using Frame): Result[DiscordInvalidInteractionResponseException, Autocomplete] =
                if choices.size <= 25 then Result.succeed(new Autocomplete(choices))
                else
                    Result.fail(DiscordInvalidInteractionResponseException(
                        DiscordInvalidInteractionResponseException.Problem.ChoiceCount(choices.size)
                    ))
        end Autocomplete

        /** A popup form (type 9): `customId` comes back with the submission, `title` at most 45 characters, 1 to 5 components. */
        @kyo.schema.tagNumber(9)
        final case class Modal private[kyo] (customId: String, title: String, components: Chunk[Component]) extends InteractionResponse

        object Modal:
            /** The modal, or a [[kyo.DiscordInvalidInteractionResponseException]] naming what Discord would refuse. */
            def init(customId: String, title: String, components: Chunk[Component])(using
                Frame
            ): Result[DiscordInvalidInteractionResponseException, Modal] =
                import DiscordInvalidInteractionResponseException.Problem
                val idLength                = customId.codePointCount(0, customId.length)
                val titleLength             = title.codePointCount(0, title.length)
                val problem: Maybe[Problem] =
                    if idLength < 1 || idLength > 100 then Present(Problem.CustomId(idLength))
                    else if titleLength < 1 || titleLength > 45 then Present(Problem.Title(titleLength))
                    else if components.isEmpty || components.size > 5 then Present(Problem.ComponentCount(components.size))
                    else Absent
                problem match
                    case Present(p) => Result.fail(DiscordInvalidInteractionResponseException(p))
                    case Absent     => Result.succeed(new Modal(customId, title, components))
            end init
            given Schema[Modal] = Schema.derived[Modal].renameAllFields(Schema.NameCase.SnakeCase)
        end Modal

        given Schema[InteractionResponse] = Schema.derived[InteractionResponse]
    end InteractionResponse

    // --- Events ---

    /** What the bot receives: a Gateway dispatch, or an interaction from the Gateway or the interactions endpoint.
      *
      * `A` is what the handler answers with: `Unit` for every [[kyo.Discord.Event.Plain]] dispatch, and for an interaction the answer
      * type its kind admits (`Event.Command` takes an [[kyo.Discord.InteractionResponse.ToCommand]]), which the module posts as the
      * callback. A dispatch has no id of its own; the ids the cases carry (message, interaction, channel) are how a handler tells a
      * repeat after a reconnect.
      *
      * As a Gateway dispatch it is `{t, d}`: `t` names the case and `d` carries it. Every interaction is one name,
      * `INTERACTION_CREATE`, so the interaction cases are one sum, [[kyo.Discord.Event.InteractionEvent]], told apart by the
      * interaction's integer `type`.
      *
      * A dispatch kind the model does not declare is [[kyo.Discord.Event.Unknown]] with its name and JSON; an interaction kind is
      * [[kyo.Discord.Event.UnknownInteraction]].
      */
    sealed trait Event[A]

    object Event:

        given CanEqual[Event[?], Event[?]] = CanEqual.derived

        /** A Gateway dispatch the handler answers with `Unit`. */
        type Plain = Event[Unit]

        /** The session is ready (`READY`): the bot's user, the guilds it is in (their data follows as `GuildCreated`), its
          * application, and the shard. After a reconnect that could not resume, a new `Ready` marks a gap in delivery.
          */
        final case class Ready(user: User, guilds: Chunk[UnavailableGuild], application: Ready.Application, shard: Maybe[Shard])
            extends Plain

        object Ready:
            /** The application's id and flags. */
            final case class Application(id: ApplicationId, flags: Maybe[Int] = Absent) derives CanEqual
            object Application:
                given Schema[Application] = Schema.derived[Application]

            // `shard` is the `[shard_id, num_shards]` pair Identify sent.
            private given Schema[Shard] =
                summon[Schema[Chunk[Int]]].transformVia[Result[String, Shard], Shard] {
                    case Chunk(id, count) => Shard.init(id, count).mapFailure(_.getMessage)
                    case other            => Result.fail(s"a shard is two integers; got ${other.size}")
                }(s => Chunk(s.id, s.count))

            given Schema[Ready] = Schema.derived[Ready]
        end Ready

        /** The session resumed (`RESUMED`) and the missed events were replayed before it. */
        case object Resumed extends Plain

        /** A message was sent (`MESSAGE_CREATE`). */
        final case class MessageCreated(message: Message) extends Plain
        object MessageCreated:
            given Schema[MessageCreated] = summon[Schema[Message]].transform(MessageCreated(_))(_.message)

        /** A message was edited (`MESSAGE_UPDATE`). */
        final case class MessageUpdated(message: Message) extends Plain
        object MessageUpdated:
            given Schema[MessageUpdated] = summon[Schema[Message]].transform(MessageUpdated(_))(_.message)

        /** A message was deleted (`MESSAGE_DELETE`). */
        final case class MessageDeleted(id: MessageId, channelId: ChannelId, guildId: Maybe[GuildId] = Absent) extends Plain
        object MessageDeleted:
            given Schema[MessageDeleted] = Schema.derived[MessageDeleted].renameAllFields(Schema.NameCase.SnakeCase)

        /** A user reacted to a message (`MESSAGE_REACTION_ADD`). */
        final case class ReactionAdded(
            userId: UserId,
            channelId: ChannelId,
            messageId: MessageId,
            guildId: Maybe[GuildId] = Absent,
            member: Maybe[Member] = Absent,
            emoji: Emoji
        ) extends Plain
        object ReactionAdded:
            given Schema[ReactionAdded] = Schema.derived[ReactionAdded].renameAllFields(Schema.NameCase.SnakeCase)

        /** A user removed a reaction (`MESSAGE_REACTION_REMOVE`). */
        final case class ReactionRemoved(
            userId: UserId,
            channelId: ChannelId,
            messageId: MessageId,
            guildId: Maybe[GuildId] = Absent,
            emoji: Emoji
        ) extends Plain
        object ReactionRemoved:
            given Schema[ReactionRemoved] = Schema.derived[ReactionRemoved].renameAllFields(Schema.NameCase.SnakeCase)

        /** A thread was created, or the bot was added to one (`THREAD_CREATE`). */
        final case class ThreadCreated(channel: Channel) extends Plain
        object ThreadCreated:
            given Schema[ThreadCreated] = summon[Schema[Channel]].transform(ThreadCreated(_))(_.channel)

        /** A thread changed (`THREAD_UPDATE`). */
        final case class ThreadUpdated(channel: Channel) extends Plain
        object ThreadUpdated:
            given Schema[ThreadUpdated] = summon[Schema[Channel]].transform(ThreadUpdated(_))(_.channel)

        /** A thread was deleted (`THREAD_DELETE`): Discord sends only its id, guild, parent and type. */
        final case class ThreadDeleted(
            id: ChannelId,
            guildId: Maybe[GuildId] = Absent,
            parentId: Maybe[ChannelId] = Absent,
            `type`: Channel.Type
        ) extends Plain
        object ThreadDeleted:
            given Schema[ThreadDeleted] = Schema.derived[ThreadDeleted].renameAllFields(Schema.NameCase.SnakeCase)

        /** A channel was created (`CHANNEL_CREATE`). */
        final case class ChannelCreated(channel: Channel) extends Plain
        object ChannelCreated:
            given Schema[ChannelCreated] = summon[Schema[Channel]].transform(ChannelCreated(_))(_.channel)

        /** A channel changed (`CHANNEL_UPDATE`). */
        final case class ChannelUpdated(channel: Channel) extends Plain
        object ChannelUpdated:
            given Schema[ChannelUpdated] = summon[Schema[Channel]].transform(ChannelUpdated(_))(_.channel)

        /** A channel was deleted (`CHANNEL_DELETE`). */
        final case class ChannelDeleted(channel: Channel) extends Plain
        object ChannelDeleted:
            given Schema[ChannelDeleted] = summon[Schema[Channel]].transform(ChannelDeleted(_))(_.channel)

        /** A guild became available, or the bot joined it (`GUILD_CREATE`). */
        final case class GuildCreated(guild: Guild) extends Plain
        object GuildCreated:
            given Schema[GuildCreated] = summon[Schema[Guild]].transform(GuildCreated(_))(_.guild)

        /** A guild went offline (`unavailable` true), or the bot left or was removed from it (`GUILD_DELETE`). */
        final case class GuildDeleted(id: GuildId, unavailable: Boolean = false) extends Plain
        object GuildDeleted:
            given Schema[GuildDeleted] = Schema.derived[GuildDeleted]

        /** A user joined a guild (`GUILD_MEMBER_ADD`); needs the privileged `GuildMembers` intent. Discord sends the member's fields
          * with `guild_id` beside them, so the case is that flat record; `member` gathers the member's fields.
          */
        final case class MemberJoined(
            guildId: GuildId,
            user: Maybe[User] = Absent,
            nick: Maybe[String] = Absent,
            roles: Chunk[RoleId] = Chunk.empty,
            joinedAt: Maybe[Instant] = Absent
        ) extends Plain:
            /** The member who joined. */
            def member: Member = Member(user, nick, roles, joinedAt)
        end MemberJoined

        object MemberJoined:
            given Schema[MemberJoined] = Schema.derived[MemberJoined].renameAllFields(Schema.NameCase.SnakeCase)

        /** A user left or was removed from a guild (`GUILD_MEMBER_REMOVE`); needs the privileged `GuildMembers` intent. */
        final case class MemberLeft(guildId: GuildId, user: User) extends Plain
        object MemberLeft:
            given Schema[MemberLeft] = Schema.derived[MemberLeft].renameAllFields(Schema.NameCase.SnakeCase)

        /** A user started typing (`TYPING_START`). */
        final case class TypingStarted(
            channelId: ChannelId,
            guildId: Maybe[GuildId] = Absent,
            userId: UserId,
            @kyo.schema.transform(WireField.UnixSeconds) timestamp: Instant,
            member: Maybe[Member] = Absent
        ) extends Plain
        object TypingStarted:
            given Schema[TypingStarted] = Schema.derived[TypingStarted].renameAllFields(Schema.NameCase.SnakeCase)

        /** A dispatch kind the model does not declare: its name (`t`) and its JSON (`d`). */
        @kyo.schema.catchAll()
        final case class Unknown(`type`: String, payload: RawJson) extends Plain

        /** An interaction (`INTERACTION_CREATE`, or a request to the interactions endpoint): the interaction's fields with its integer
          * `type` (`interactions/receiving-and-responding.mdx`, "Interaction Type") and its `data`. A `PING` (1) is the endpoint's to
          * answer and is not an event; it decodes as `UnknownInteraction`.
          */
        sealed trait InteractionEvent[A] extends Event[A]

        object InteractionEvent:
            given Schema[InteractionEvent[?]] = Schema.derived[InteractionEvent[?]].discriminator("type")

        /** A slash or context-menu command was invoked; the answer is a [[kyo.Discord.InteractionResponse.ToCommand]]. */
        @kyo.schema.tagNumber(2)
        final case class Command(interaction: Interaction, data: Discord.Command.Data)
            extends InteractionEvent[InteractionResponse.ToCommand]
        object Command:
            given Schema[Command] = Schema[Command].flatten(_.interaction)

        /** A button or select was used; the answer is a [[kyo.Discord.InteractionResponse.ToComponent]]. */
        @kyo.schema.tagNumber(3)
        final case class Component(interaction: Interaction, data: Interaction.ComponentData)
            extends InteractionEvent[InteractionResponse.ToComponent]
        object Component:
            given Schema[Component] = Schema[Component].flatten(_.interaction)

        /** An option is being typed; the answer is suggestions, a [[kyo.Discord.InteractionResponse.Autocomplete]]. */
        @kyo.schema.tagNumber(4)
        final case class Autocomplete(interaction: Interaction, data: Discord.Command.Data)
            extends InteractionEvent[InteractionResponse.ToAutocomplete]
        object Autocomplete:
            given Schema[Autocomplete] = Schema[Autocomplete].flatten(_.interaction)

        /** A modal was submitted; the answer is a [[kyo.Discord.InteractionResponse.ToModalSubmit]]. */
        @kyo.schema.tagNumber(5)
        final case class ModalSubmit(interaction: Interaction, data: Interaction.ModalData)
            extends InteractionEvent[InteractionResponse.ToModalSubmit]
        object ModalSubmit:
            given Schema[ModalSubmit] = Schema[ModalSubmit].flatten(_.interaction)

        /** An interaction kind the model does not declare: its `type` and its JSON. Any answer is admitted. */
        @kyo.schema.catchAll()
        final case class UnknownInteraction(`type`: Int, payload: RawJson) extends InteractionEvent[InteractionResponse]:
            /** The fields every interaction shares, read from `payload`: what the answer is addressed by. */
            def interaction(using Frame): Result[DiscordDecodeException, Interaction] =
                summon[Schema[Interaction]].fromStructureValue(payload.json)
                    .mapFailure(DiscordDecodeException("receive", DiscordDecodeException.Part.Interaction, _))
        end UnknownInteraction

        /** A handler's choice to leave an interaction unanswered, as `Abort.fail(Discord.Event.Decline)`.
          *
          * Discord takes an interaction's first answer only "within 3 seconds of receiving the event. If the 3 second deadline is
          * exceeded, the token will be invalidated" (`interactions/receiving-and-responding.mdx`, "Interaction Callback"), and the
          * user's client then shows the interaction as failed; the documentation does not give that text. Declining is the quickest
          * failure the user can see: on the Gateway nothing is posted, and the interactions endpoint replies 500 at once. One warn
          * record names the interaction and its kind. A deferred answer would instead show "thinking" until the deferral lapses.
          *
          * A dispatch takes no answer, so declining one is the same as answering it with `Unit`.
          */
        sealed trait Decline derives CanEqual
        case object Decline extends Decline

        /** The answer for an event the handler does not handle: `Unit` for a dispatch, [[Decline]] for an interaction. A handler names
          * the events it handles and ends with `case other => Discord.Event.unhandled(other)`.
          */
        def unhandled[A](event: Event[A])(using Frame): A < Abort[Decline] =
            event match
                case _: InteractionEvent[?] => Abort.fail(Decline)
                case _: (Ready | Resumed.type | MessageCreated | MessageUpdated | MessageDeleted | ReactionAdded | ReactionRemoved |
                        ThreadCreated | ThreadUpdated | ThreadDeleted | ChannelCreated | ChannelUpdated | ChannelDeleted | GuildCreated |
                        GuildDeleted | MemberJoined | MemberLeft | TypingStarted | Unknown) => ()

        // The Gateway names dispatches in SCREAMING_SNAKE_CASE (`events/gateway-events.mdx`, "Receive Events").
        given Schema[Event[?]] = Schema.derived[Event[?]].adjacent("t", "d").variantNames(
            "Ready"            -> "READY",
            "Resumed"          -> "RESUMED",
            "MessageCreated"   -> "MESSAGE_CREATE",
            "MessageUpdated"   -> "MESSAGE_UPDATE",
            "MessageDeleted"   -> "MESSAGE_DELETE",
            "ReactionAdded"    -> "MESSAGE_REACTION_ADD",
            "ReactionRemoved"  -> "MESSAGE_REACTION_REMOVE",
            "ThreadCreated"    -> "THREAD_CREATE",
            "ThreadUpdated"    -> "THREAD_UPDATE",
            "ThreadDeleted"    -> "THREAD_DELETE",
            "ChannelCreated"   -> "CHANNEL_CREATE",
            "ChannelUpdated"   -> "CHANNEL_UPDATE",
            "ChannelDeleted"   -> "CHANNEL_DELETE",
            "GuildCreated"     -> "GUILD_CREATE",
            "GuildDeleted"     -> "GUILD_DELETE",
            "MemberJoined"     -> "GUILD_MEMBER_ADD",
            "MemberLeft"       -> "GUILD_MEMBER_REMOVE",
            "TypingStarted"    -> "TYPING_START",
            "InteractionEvent" -> "INTERACTION_CREATE"
        )
    end Event

    // --- The interactions endpoint ---

    /** Receiving interactions at the bot's own HTTPS endpoint (`interactions/overview.mdx`, "Receiving an Interaction"): an
      * `HttpHandler` the bot mounts on its `HttpServer`, which Discord POSTs every interaction to once the developer portal names the
      * endpoint's URL. It replaces the Gateway for interactions only; dispatches still need `receive`.
      *
      * `handler` checks each request's signature against the application's public key, decodes the interaction, and answers it with
      * what `f` returns, under the same `interactionDeadline` as `receive`. `verify` and `decode` are the two steps on their own, for a
      * bot that serves the endpoint itself.
      *
      * The replies are Discord's contract. A request whose signature headers are missing, malformed or do not verify gets 401, and is
      * neither decoded nor handled: Discord sends such requests on purpose and removes an endpoint that accepts one. A verified body
      * that is not an interaction gets 400. A `PING` gets 200 with `{"type":1}` and never reaches `f`. `f`'s answer is the 200 reply
      * as JSON; an answer with files cannot be one, so it is posted to the callback route as multipart and the reply is 202 with no
      * body, as Discord asks of an answer sent that way. `f` past the deadline is interrupted, and that, `f`'s failure, its panic,
      * an interaction `f` declines with [[kyo.Discord.Event.Decline]] and a callback that failed all get 500.
      *
      * IMPORTANT: a body past the server's `maxContentLength` is refused with 413 before the handler runs, which keeps the
      * verification away from large bodies. Discord documents no freshness window for the signed timestamp, so a recorded request
      * replays: it verifies again and runs `f` again. `f` deduplicates by the interaction's id where running twice matters.
      *
      * `handler` requires `Env[Discord]`, and every interaction's `f` runs with that client provided. `f` runs on the server's fibers,
      * after `handler` returns, so the server is served inside the client's region: `Discord.run(config)(Discord.Webhook.handler(webhook)
      * (f).map(h => HttpServer.init(...)(h).map(_.await)))`. A handler taken out of the region holds a closed client.
      *
      * @see
      *   [[kyo.DiscordWebhookConfig]] the public key and the route's path
      * @see
      *   [[kyo.Discord.receive]] the Gateway, which also delivers interactions
      */
    object Webhook:

        /** The header Discord sends the signature in, 128 hex characters. */
        inline val SignatureHeader = "X-Signature-Ed25519"

        /** The header Discord sends the signed timestamp in, in seconds. */
        inline val TimestampHeader = "X-Signature-Timestamp"

        /** What a verified request carries: the endpoint check Discord answers itself, or an interaction for the handler. */
        enum Delivery derives CanEqual:
            /** Discord checking the endpoint (`type` 1), answered with `{"type":1}`. */
            case Ping

            /** An interaction: one of the [[kyo.Discord.Event.InteractionEvent]] cases. */
            case Event(event: Discord.Event[?])
        end Delivery

        /** An `HttpHandler` for POSTs at `webhook.path` that verifies, decodes and answers each interaction with `f`, with the caller's
          * client provided to `f`. The handler holds that client, so it is served within the client's region.
          */
        def handler[E](webhook: DiscordWebhookConfig)(
            f: [A] => Discord.Event[A] => A < (Async & Abort[E | Event.Decline] & Env[Discord])
        )(using Frame): HttpHandler["body" ~ Span[Byte], "body" ~ Span[Byte], E] < Env[Discord] =
            Env.use[Discord] { discord =>
                HttpRoute.postRaw(webhook.path).request(_.bodyBinary).response(_.bodyBinary).handler[E] { req =>
                    Clock.now.map { arrivedAt =>
                        val body = req.fields.body
                        verify(webhook, req.headers.get(SignatureHeader), req.headers.get(TimestampHeader), body) match
                            case Result.Success(_) =>
                                // `f` runs after the decode result is matched, outside this Abort.run, so a failure of its own is never
                                // taken for the module's decode failure.
                                Abort.run[DiscordWebhookDecodeFailure](decode(body)).map {
                                    case Result.Success(Delivery.Ping)         => json(PongJson)
                                    case Result.Success(Delivery.Event(event)) => answer(discord, arrivedAt, event, f)
                                    case Result.Failure(_)                     => HttpResponse.halt(HttpResponse.badRequest)
                                    case Result.Panic(t)                       => Abort.panic(t)
                                }
                            case Result.Failure(_) => HttpResponse.halt(HttpResponse.unauthorized)
                            case Result.Panic(t)   => Abort.panic(t)
                        end match
                    }
                }
            }

        /** Checks a request's `X-Signature-Ed25519` and `X-Signature-Timestamp` headers against `webhook.publicKey`, over the
          * timestamp followed by the raw body; each refusal names the header and what is wrong with it, never its value.
          */
        def verify(webhook: DiscordWebhookConfig, signature: Maybe[String], timestamp: Maybe[String], body: Span[Byte])(using
            Frame
        ): Result[DiscordWebhookVerifyFailure, Unit] =
            Signature.verify(webhook.publicKey, signature, timestamp, body)

        /** Decodes a verified request's body into what it delivers. */
        def decode(body: Span[Byte])(using Frame): Delivery < Abort[DiscordWebhookDecodeFailure] =
            summon[Schema[Event.InteractionEvent[?]]].decode[Json](body) match
                case Result.Success(e: Event.UnknownInteraction) if e.`type` == PingType => Delivery.Ping
                case Result.Success(event)                                               => Delivery.Event(event)
                case Result.Failure(cause)                                               =>
                    val e = DiscordDecodeException(DecodeMethod, DiscordDecodeException.Part.Interaction, cause)
                    Abort.fail(DiscordWebhookDecodeException(e.part, e.failure, e.path, e.position))
                case Result.Panic(t) => Abort.panic(t)

        private def answer[E](
            discord: Discord,
            arrivedAt: Instant,
            event: Discord.Event[?],
            f: [A] => Discord.Event[A] => A < (Async & Abort[E | Event.Decline] & Env[Discord])
        )(using Frame): HttpResponse["body" ~ Span[Byte]] < (Async & Abort[E | HttpResponse.Halt]) =
            event match
                case e: Event.InteractionEvent[?] =>
                    val pending = Dispatch.interaction[E, Any](e, f)
                    Dispatch.within[E, Any](discord, arrivedAt, pending).map {
                        case Absent          => HttpResponse.halt(HttpResponse.serverError)
                        case Present(answer) =>
                            Rest.answerBody(answer) match
                                case Rest.Body.Json(text) => json(text)
                                case _                    => callback(discord, pending, answer)
                    }
                case other => bug(s"the interactions endpoint decoded a ${other.getClass.getSimpleName}")

        private def callback(discord: Discord, pending: Dispatch.Pending[Nothing], answer: InteractionResponse)(using
            Frame
        ): HttpResponse["body" ~ Span[Byte]] < (Async & Abort[HttpResponse.Halt]) =
            pending.ref match
                case Absent =>
                    Log.warn(s"Discord: interaction (${pending.kind}) carries no token to answer with.")
                        .andThen(HttpResponse.halt(HttpResponse.serverError))
                case Present(ref) =>
                    Abort.run[DiscordException](Discord.run(discord)(Rest.callback(ref, answer))).map {
                        // Not a halt: kyo-http writes an error body on a halted 202, and Discord asks for none.
                        case Result.Success(_) => HttpResponse.accepted(Span.empty[Byte])
                        case Result.Failure(e) =>
                            Log.warn(
                                s"Discord: the answer to interaction ${pending.id} (${pending.kind}) failed: ${e.getClass.getSimpleName}."
                            )
                                .andThen(HttpResponse.halt(HttpResponse.serverError))
                        case Result.Panic(t) => Abort.panic(t)
                    }

        private def json(text: String)(using Frame): HttpResponse["body" ~ Span[Byte]] =
            HttpResponse.ok(Span.from(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))).setHeader("Content-Type", "application/json")

        private inline val PingType     = 1
        private inline val PongJson     = """{"type":1}"""
        private inline val DecodeMethod = "interactions endpoint"

    end Webhook

end Discord
