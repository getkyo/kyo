package kyo

import java.nio.charset.StandardCharsets.UTF_8
import kyo.internal.Platform

/** The module against a Discord: the real API and Gateway when `DISCORD_BOT_TOKEN` is set, a [[DiscordLiveServer]] (Spacebar, an
  * AGPL-3.0 reimplementation of Discord's backend, run and never distributed) when it is not. The variables are read through
  * `kyo.System.env`, so the suite runs on every platform.
  *
  * On Discord, `DISCORD_BOT_TOKEN` is a bot's token from the developer portal. The leaves that send need `DISCORD_TEST_CHANNEL_ID`, a
  * text channel the bot can view, send in, react in, read the history of and start and manage threads in, and the command leaves need
  * `DISCORD_TEST_GUILD_ID`, a guild the bot was added to with the `applications.commands` scope; without them those leaves are
  * cancelled naming the variable. On the container, the leaves share one server, on every platform but Windows, whose container
  * daemon cannot serve the Linux image; the server's person owns the bot and a guild with one text channel.
  *
  * The suite deletes every message, thread and command it creates.
  *
  * The leaves after the `Interactive` separator hold a Gateway session, send the person an instruction in the test channel, and wait
  * through `Discord.receive` for the event their action produces. On Discord they run only when `DISCORD_INTERACTIVE` is set: they
  * identify with the privileged `MESSAGE_CONTENT` intent, which must be enabled for the application in the developer portal, and the
  * bot needs Manage Messages and Send Messages in Threads in the channel, since the suite deletes the person's messages it asked for,
  * except a direct message, which Discord lets only its author delete. On the container the suite does what the person would through
  * Spacebar's API with the person's token, and the same assertions hold.
  *
  * The failure leaves assert whole values, descriptions included: the description is Discord's text, which the documentation does not
  * list. A leaf whose behaviour Spacebar does not reproduce asserts Discord's, and is cancelled on the container with the difference.
  */
class DiscordLiveTest extends kyo.test.Test[Any]:

    // The real target shares one bot and one channel, and container leaves contend on one daemon, so leaves run one at a time across
    // every suite of the process.
    override def config = super.config.sequential.globallySequential(true)

    // deviation: a leaf waits on the real API and Gateway, a container starting (the first one builds Spacebar's image), or a person
    // acting, which no virtual clock can stand in for. This per-leaf bound is the only real-clock limit in the module's tests; no
    // assertion reads elapsed time.
    override def timeout: Duration = 10.minutes

    // The fixture's own calls go to the container's TLS terminator, whose certificate the leaf's client must trust as the module's does.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        super.aroundLeaf(HttpClient.init(defaultTlsConfig = DiscordLiveServer.Tls).flatMap(client => HttpClient.let(client)(body)))

    import Discord.*
    // kyo's channel, over the wildcard's `Discord.Channel`.
    import kyo.Channel

    private enum Target derives CanEqual:
        case Real(token: Token)
        case Emulator(server: DiscordLiveServer)

        def config(intents: Intents)(using Frame): DiscordConfig =
            this match
                case Real(token)      => DiscordConfig.init(token, intents).getOrThrow
                case Emulator(server) => server.config(intents)

        def channel(using Frame): ChannelId < Sync =
            this match
                case Real(_)          => variable("DISCORD_TEST_CHANNEL_ID", "this leaf sends to a channel")(ChannelId.parse(_).getOrThrow)
                case Emulator(server) => server.channel

        def guild(using Frame): GuildId < Sync =
            this match
                case Real(_) => variable("DISCORD_TEST_GUILD_ID", "this leaf registers commands in a guild")(GuildId.parse(_).getOrThrow)
                case Emulator(server) => server.guild
    end Target

    /** What starting the container target can fail with. */
    private type Setup = ContainerException | FileSystemException | HttpException

    private def variable[A](name: String, why: String)(parse: String => A)(using Frame): A < Sync =
        System.env[String](name).map {
            case Absent     => cancel(s"$name must be set: $why")
            case Present(t) => parse(t)
        }

    /** The Discord a leaf runs against. `realOnly` is why Spacebar cannot stand in for this leaf, which is then cancelled before a
      * container starts. A leaf that ends in error prints the container's log.
      */
    private def target(realOnly: Maybe[String] = Absent)(using Frame): Target < (Async & Scope & Abort[Setup]) =
        System.env[String]("DISCORD_BOT_TOKEN").map {
            case Present(t) => Target.Real(Token.init(t).getOrThrow)
            case Absent     =>
                realOnly match
                    case Present(reason) => cancel(s"Spacebar differs from Discord: $reason; set DISCORD_BOT_TOKEN to run it on Discord")
                    case Absent if Platform.isWindows =>
                        cancel("Spacebar does not run on Windows: its container daemon cannot serve the Linux image")
                    case Absent =>
                        DiscordLiveServer.init.map { server =>
                            Scope.ensure {
                                case Present(error) =>
                                    server.postMortem.map(log => Console.printLineErr(s"the leaf ended with $error; Spacebar log:\n$log"))
                                case Absent => Kyo.unit
                            }.andThen(Target.Emulator(server))
                        }
                end match
        }

    private def text(content: String)(using Frame): Message.Create = Message.Create.init(content = Present(content)).getOrThrow

    private val SpacebarUploads =
        "Spacebar answers a multipart send whose attachments name their file by index, as Discord documents, with a 500 (Unhandled attachment)"

    private val SpacebarGatewayAuth = "Spacebar answers GET /gateway/bot without checking the token, so a forged one succeeds"

    private val SpacebarUnknown =
        "Spacebar answers an unknown channel or message with code 404 \"<entity> could not be found\", not Discord's 10003 \"Unknown Channel\" or 10008 \"Unknown Message\""

    private val SpacebarThreadDelete = "Spacebar deletes a thread's starter message with the thread, which Discord keeps"

    private val SpacebarDirectMessage =
        "Spacebar subscribes the bot's Gateway session to a new direct message channel only when the person's first message reopens it, and that message is not delivered to the bot"

    private val SpacebarCommands =
        "Spacebar's command bulk overwrite validates options against a narrower schema than Discord's and answers the request body, without the command ids"

    "GET /gateway/bot answers a wss URL, at least one shard and the session start budget" in {
        target().map(t => Discord.run(t.config(Intents.Guilds))(Discord.gateway)).map { info =>
            assert(info.url.startsWith("wss://"))
            assert(info.shards >= 1)
            assert(info.sessionStartLimit.total >= info.sessionStartLimit.remaining)
            assert(info.sessionStartLimit.maxConcurrency >= 1)
        }
    }

    "a token Discord does not accept fails with the unauthorized leaf" in {
        target(Present(SpacebarGatewayAuth)).map { t =>
            val config = t.config(Intents.Guilds)
            val forged = config.copy(token = Token.init(config.token.value.reverse).getOrThrow)
            Abort.run[DiscordGatewayInfoFailure](Discord.run(forged)(Discord.gateway)).map { result =>
                assert(
                    result.failure == Present(DiscordUnauthorizedException("GET /gateway/bot", Code(0), "401: Unauthorized")),
                    s"got: $result"
                )
            }
        }
    }

    "a channel that does not exist fails with the unknown channel leaf" in {
        target(Present(SpacebarUnknown)).map { t =>
            Abort.run[DiscordChannelFailure](Discord.run(t.config(Intents.Guilds))(Discord.channel(ChannelId(1L)))).map { result =>
                assert(
                    result.failure == Present(DiscordUnknownChannelException("GET /channels/{channel.id}", "Unknown Channel")),
                    s"got: $result"
                )
            }
        }
    }

    "a message is sent, read back, edited, reacted to, listed and deleted" in {
        target().map { t =>
            t.channel.map { channel =>
                Discord.run(t.config(Intents.Guilds)) {
                    for
                        sent   <- Discord.send(channel, text("kyo-discord live: sent"))
                        _      <- Discord.typing(channel)
                        read   <- Discord.message(channel, sent.id)
                        edited <- Discord.edit(
                            channel,
                            sent.id,
                            Message.Edit.init(content = Present(Patch.Set("kyo-discord live: edited"))).getOrThrow
                        )
                        fire = Reaction.unicode("🔥").getOrThrow
                        _      <- Discord.react(channel, sent.id, fire)
                        _      <- Discord.unreact(channel, sent.id, fire)
                        around <- Discord.messages(channel, Message.Page.init(Message.Page.Anchor.Around(sent.id), 5).getOrThrow)
                        _      <- Discord.delete(channel, sent.id)
                    yield
                        assert((read.id, read.content, read.author.bot) == (sent.id, "kyo-discord live: sent", true))
                        assert((edited.content, edited.editedTimestamp.isDefined) == ("kyo-discord live: edited", true))
                        assert(around.map(_.id).contains(sent.id))
                    end for
                }
            }
        }
    }

    "a deleted message fails with the unknown message leaf" in {
        target(Present(SpacebarUnknown)).map { t =>
            t.channel.map { channel =>
                Discord.run(t.config(Intents.Guilds)) {
                    for
                        sent    <- Discord.send(channel, text("kyo-discord live: deleted"))
                        _       <- Discord.delete(channel, sent.id)
                        deleted <- Abort.run[DiscordMessageFailure](Discord.message(channel, sent.id))
                    yield assert(
                        deleted.failure ==
                            Present(DiscordUnknownMessageException("GET /channels/{channel.id}/messages/{message.id}", "Unknown Message")),
                        s"got: $deleted"
                    )
                }
            }
        }
    }

    "a message with a file arrives with the attachment named and sized" in {
        val file = File.init("kyo.txt", Span.from("kyo-discord".getBytes(UTF_8)), description = Present("live")).getOrThrow
        target(Present(SpacebarUploads)).map { t =>
            t.channel.map { channel =>
                Discord.run(t.config(Intents.Guilds)) {
                    Discord.send(channel, Message.Create.init(content = Present("kyo-discord live: file"), files = Chunk(file)).getOrThrow)
                        .map { sent =>
                            Discord.delete(channel, sent.id).andThen {
                                assert(sent.attachments.map(a => (a.filename, a.size)) == Chunk(("kyo.txt", 11L)))
                            }
                        }
                }
            }
        }
    }

    "a thread started on a message is a public thread under the channel" in {
        target(Present(SpacebarThreadDelete)).map { t =>
            t.channel.map { channel =>
                Discord.run(t.config(Intents.Guilds)) {
                    for
                        sent   <- Discord.send(channel, text("kyo-discord live: thread"))
                        thread <- Discord.startThread(channel, sent.id, Thread.Start.init("kyo-discord live").getOrThrow)
                        _ <- Discord.custom[Unit, Structure.Value](HttpMethod.DELETE, Path.init(s"channels/${thread.id.value}").getOrThrow)
                        _ <- Discord.delete(channel, sent.id)
                    yield assert((thread.`type`, thread.parentId, thread.name) ==
                        (Discord.Channel.Type.PublicThread, Present(channel), Present("kyo-discord live")))
                }
            }
        }
    }

    "guild commands set are answered with their ids, and setting none removes them" in {
        target(Present(SpacebarCommands)).map { t =>
            t.guild.map { guild =>
                Discord.run(t.config(Intents.Guilds)) {
                    val roll = Command.Create.init("kyo-live-roll", "kyo-discord live suite").getOrThrow
                    for
                        registered <- Discord.setCommands(guild, Chunk(roll))
                        cleared    <- Discord.setCommands(guild, Chunk.empty)
                    yield
                        assert(registered.map(c => (c.name, c.guildId)) == Chunk(("kyo-live-roll", Present(guild))))
                        assert(cleared == Chunk.empty)
                    end for
                }
            }
        }
    }

    "init reaches Ready on the Gateway, and receive delivers the bot's own message" in {
        target().map { t =>
            t.channel.map { channel =>
                Discord.init(t.config(Intents.Guilds.union(Intents.GuildMessages))).map { discord =>
                    Discord.run(discord) {
                        Discord.send(channel, text("kyo-discord live: gateway")).map { sent =>
                            Channel.init[Message](1).map { seen =>
                                Fiber.initUnscoped(Discord.receive[Closed]([A] =>
                                    (event: Event[A]) =>
                                        event match
                                            case e: Event.MessageCreated if e.message.id == sent.id =>
                                                seen.put(e.message).andThen(DiscordGatewayTest.answer[A](e))
                                            case other => DiscordGatewayTest.answer[A](other)
                                )).map { loop =>
                                    seen.take.map { delivered =>
                                        loop.interrupt.andThen(Discord.delete(channel, sent.id)).andThen {
                                            assert((delivered.channelId, delivered.content) == (channel, "kyo-discord live: gateway"))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Interactive: a person acts in Discord, and receive delivers what they did ---

    /** An interactive leaf's target, its test channel and guild, and every event the leaf's Gateway session delivered. */
    private case class Live(target: Target, channel: ChannelId, guild: GuildId, events: Channel[Event[?]])

    /** The intents every interactive leaf identifies with: each names events a leaf waits for. */
    private val watched: Intents =
        Intents.Guilds.union(Intents.GuildMessages).union(Intents.GuildMessageReactions).union(Intents.DirectMessages)
            .union(Intents.MessageContent)

    private val noAnswer: [A] => Event[A] => Maybe[A] = [A] => (_: Event[A]) => Maybe.empty[A]

    /** Runs `v` on a Gateway session whose handler hands every event to `Live.events` and answers it with `respond`, or as
      * `DiscordGatewayTest.answer` does when `respond` is `Absent`. A failure that ends `receive` ends the leaf with it. On Discord the
      * leaf runs only when `DISCORD_INTERACTIVE` is set, since a person must act.
      */
    private def interactive[R](respond: [A] => Event[A] => Maybe[A] = noAnswer, realOnly: Maybe[String] = Absent)(
        v: Live => R < (Async & Abort[DiscordException | Closed | Setup] & Env[Discord])
    )(using Frame): R < (Async & Scope & Abort[DiscordException | Closed | Setup]) =
        target(realOnly).map { t =>
            val needsPerson: Boolean < Sync = t match
                case Target.Real(_)     => System.env[String]("DISCORD_INTERACTIVE").map(_.isEmpty)
                case Target.Emulator(_) => false
            needsPerson.map { absent =>
                if absent then cancel("DISCORD_INTERACTIVE not set: this leaf waits for a person to act in Discord")
                else
                    t.channel.map { channel =>
                        Discord.init(t.config(watched)).map { discord =>
                            Discord.run(discord)(Discord.channel(channel)).map(_.guildId).map {
                                case Absent => cancel("DISCORD_TEST_CHANNEL_ID must name a guild channel: these leaves run in a guild")
                                case Present(guild) =>
                                    Channel.init[Event[?]](1024).map { events =>
                                        Fiber.initUnscoped(Discord.run(discord)(Discord.receive[Closed]([A] =>
                                            (event: Event[A]) =>
                                                events.put(event).andThen(respond(event).getOrElse(DiscordGatewayTest.answer[A](event)))
                                        ))).map { loop =>
                                            Async.raceFirst[DiscordException | Closed | Setup, R, Any](
                                                Discord.run(discord)(v(Live(t, channel, guild, events))),
                                                loop.get.andThen(Abort.panic(new IllegalStateException("receive ended before the leaf")))
                                            ).map(result => loop.interrupt.andThen(result))
                                        }
                                    }
                            }
                        }
                    }
            }
        }

    /** The first event `pick` selects, skipping every other. */
    private def next[R](l: Live)(pick: Event[?] => Maybe[R])(using Frame): R < (Async & Abort[Closed]) =
        l.events.take.map { event =>
            pick(event) match
                case Present(r) => r
                case Absent     => next(l)(pick)
        }

    /** A message a person sent in `channel` after `prompt`. */
    private def fromPerson(channel: ChannelId, prompt: Message)(m: Message): Boolean =
        m.channelId == channel && !m.author.bot && m.timestamp >= prompt.timestamp

    /** What the person does after an instruction: on Discord the instruction asks for it, on the container it is done with their token. */
    private enum Act derives CanEqual:
        case Says(content: String)
        case Replies(content: String)
        case Reacts(emoji: String)
        case EditsThenDeletes(before: String, after: String)
        case Presses(customId: String)
        case Runs(command: CommandId, name: String, option: String, value: String)
        case MessagesBot(content: String)
    end Act

    /** Sends `content` as an instruction to the person in `channel`, who then does `act`. Answers the instruction. */
    private def ask(l: Live, channel: ChannelId, content: String, act: Act, components: Chunk[Component] = Chunk.empty)(using
        Frame
    ): Message < (Async & Abort[DiscordSendFailure | Setup] & Env[Discord]) =
        Discord.send(channel, Message.Create.init(content = Present(s"kyo-discord live: $content"), components = components).getOrThrow)
            .map { prompt =>
                l.target match
                    case Target.Real(_)          => prompt
                    case Target.Emulator(server) =>
                        val acted = act match
                            case Act.Says(said)                         => server.says(channel, said).unit
                            case Act.Replies(said)                      => server.says(channel, said, replyTo = Present(prompt.id)).unit
                            case Act.Reacts(emoji)                      => server.reacts(channel, prompt.id, emoji)
                            case Act.Presses(id)                        => server.presses(channel, prompt.id, id)
                            case Act.MessagesBot(dm)                    => server.messagesBot(dm).unit
                            case Act.Runs(command, name, option, value) => server.runs(channel, command, name, option, value)
                            case Act.EditsThenDeletes(before, after)    =>
                                server.says(channel, before).map(id =>
                                    server.edits(channel, id, after).andThen(server.deletes(channel, id))
                                )
                        acted.andThen(prompt)
            }

    private def cleanUp(channel: ChannelId, messages: MessageId*)(using
        Frame
    ): Unit < (Async & Abort[DiscordDeleteFailure] & Env[Discord]) =
        Kyo.foreachDiscard(messages)(Discord.delete(channel, _))

    "receive delivers a message a person sends after the prompt, with its author, content and channel" in {
        interactive() { l =>
            for
                prompt  <- ask(l, l.channel, "send the word kyo in this channel", Act.Says("kyo"))
                message <- next(l) {
                    case Event.MessageCreated(m) if fromPerson(l.channel, prompt)(m) => Present(m)
                    case _                                                           => Absent
                }
                _ <- cleanUp(l.channel, prompt.id, message.id)
            yield assert(
                (message.channelId, message.guildId, message.author.bot, message.content, message.`type`) ==
                    (l.channel, Present(l.guild), false, "kyo", Message.Type.Default)
            )
        }
    }

    "a reply arrives as a Reply referencing the message it answers" in {
        interactive() { l =>
            for
                prompt <- ask(l, l.channel, "reply to this message with the word yes", Act.Replies("yes"))
                reply  <- next(l) {
                    case Event.MessageCreated(m) if fromPerson(l.channel, prompt)(m) => Present(m)
                    case _                                                           => Absent
                }
                _ <- cleanUp(l.channel, prompt.id, reply.id)
            yield assert(
                (reply.content, reply.`type`, reply.messageReference.flatMap(_.messageId)) ==
                    ("yes", Message.Type.Reply, Present(prompt.id))
            )
        }
    }

    "a reaction a person adds to the bot's message arrives as ReactionAdded with the emoji" in {
        interactive() { l =>
            for
                prompt   <- ask(l, l.channel, "react to this message with 🔥", Act.Reacts("🔥"))
                reaction <- next(l) {
                    case e: Event.ReactionAdded if e.messageId == prompt.id => Present(e)
                    case _                                                  => Absent
                }
                _ <- cleanUp(l.channel, prompt.id)
                got = (reaction.channelId, reaction.guildId, reaction.emoji, reaction.member.flatMap(_.user).map(_.bot))
            yield assert(got == (l.channel, Present(l.guild), Emoji.unicode("🔥"), Present(false)), s"got: $got")
        }
    }

    "an edit a person makes arrives as MessageUpdated with its new content, and their delete as MessageDeleted" in {
        interactive() { l =>
            for
                prompt <- ask(
                    l,
                    l.channel,
                    "send the word before, edit that message to say after, then delete it",
                    Act.EditsThenDeletes("before", "after")
                )
                edited <- next(l) {
                    case Event.MessageUpdated(m) if fromPerson(l.channel, prompt)(m) => Present(m)
                    case _                                                           => Absent
                }
                deleted <- next(l) {
                    case e: Event.MessageDeleted if e.id == edited.id => Present(e)
                    case _                                            => Absent
                }
                _ <- cleanUp(l.channel, prompt.id)
            yield assert(
                (edited.content, edited.editedTimestamp.isDefined, deleted) ==
                    ("after", true, Event.MessageDeleted(edited.id, l.channel, Present(l.guild)))
            )
        }
    }

    "a message a person sends in a thread the bot started arrives with the thread as its channel" in {
        interactive(realOnly = Present(SpacebarThreadDelete)) { l =>
            for
                prompt      <- Discord.send(l.channel, text("kyo-discord live: a thread follows"))
                thread      <- Discord.startThread(l.channel, prompt.id, Thread.Start.init("kyo-discord live").getOrThrow)
                instruction <- ask(l, thread.id, "reply in this thread with the word yes", Act.Says("yes"))
                message     <- next(l) {
                    case Event.MessageCreated(m) if fromPerson(thread.id, instruction)(m) => Present(m)
                    case _                                                                => Absent
                }
                _ <- Discord.custom[Unit, Structure.Value](HttpMethod.DELETE, Path.init(s"channels/${thread.id.value}").getOrThrow)
                _ <- cleanUp(l.channel, prompt.id)
            yield assert(
                (message.channelId, message.guildId, message.author.bot, message.content, thread.parentId) ==
                    (thread.id, Present(l.guild), false, "yes", Present(l.channel))
            )
        }
    }

    "a button press arrives as Component with its custom id, and the UpdateMessage answer edits the message" in {
        val buttons = Chunk[Component](Component.ActionRow.init(Chunk(
            Component.Button.init(Component.Button.Style.Primary, label = Present("Yes"), customId = Present("kyo-live:yes")).getOrThrow,
            Component.Button.init(Component.Button.Style.Secondary, label = Present("No"), customId = Present("kyo-live:no")).getOrThrow
        )).getOrThrow)
        val pressed = Message.Edit.init(
            content = Present(Patch.Set("kyo-discord live: you pressed Yes")),
            components = Present(Chunk.empty)
        ).getOrThrow
        val respond: [A] => Event[A] => Maybe[A] = [A] =>
            (event: Event[A]) =>
                event match
                    case e: Event.Component if e.data.customId == "kyo-live:yes" => Present(InteractionResponse.UpdateMessage(pressed))
                    case _                                                       => Absent
        interactive(respond) { l =>
            for
                prompt <- ask(l, l.channel, "press Yes", Act.Presses("kyo-live:yes"), buttons)
                press  <- next(l) {
                    case e: Event.Component if e.interaction.message.map(_.id) == Present(prompt.id) => Present(e)
                    case _                                                                           => Absent
                }
                updated <- next(l) {
                    case Event.MessageUpdated(m) if m.id == prompt.id => Present(m)
                    case _                                            => Absent
                }
                _ <- cleanUp(l.channel, prompt.id)
                got = (
                    press.data.customId,
                    press.data.componentType,
                    press.interaction.channelId,
                    press.interaction.invoker.map(_.bot),
                    updated.content,
                    updated.components
                )
            yield assert(
                got == ("kyo-live:yes", 2, Present(l.channel), Present(false), "kyo-discord live: you pressed Yes", Chunk.empty),
                s"got: $got"
            )
        }
    }

    "a slash command a person invokes arrives as Command with its options, and its Message answer is posted" in {
        val echo = Command.Create.init(
            "kyo-live-echo",
            "kyo-discord live suite",
            options = Chunk(Command.Option.init(Command.Option.Type.String, "word", "the word to echo", required = true).getOrThrow)
        ).getOrThrow
        def said(data: Command.Data): String =
            data.options.collectFirst { case Command.Data.Option("word", _, Present(Command.Value.Text(w)), _, _) => w }.getOrElse("")
        val respond: [A] => Event[A] => Maybe[A] = [A] =>
            (event: Event[A]) =>
                event match
                    case e: Event.Command if e.data.name == "kyo-live-echo" =>
                        Present(InteractionResponse.Message(text(s"kyo-discord live: you said ${said(e.data)}")))
                    case _ => Absent
        interactive(respond, Present(SpacebarCommands)) { l =>
            for
                registered <- Discord.setCommands(l.guild, Chunk(echo))
                prompt     <- ask(
                    l,
                    l.channel,
                    "run the command /kyo-live-echo here with the word kyo",
                    Act.Runs(registered.head.id, "kyo-live-echo", "word", "kyo")
                )
                command <- next(l) {
                    case e: Event.Command if e.data.name == "kyo-live-echo" => Present(e)
                    case _                                                  => Absent
                }
                answer <- next(l) {
                    case Event.MessageCreated(m) if m.channelId == l.channel && m.`type` == Message.Type.ChatInputCommand => Present(m)
                    case _                                                                                                => Absent
                }
                _       <- Discord.deleteResponse(command.interaction.ref)
                cleared <- Discord.setCommands(l.guild, Chunk.empty)
                _       <- cleanUp(l.channel, prompt.id)
            yield assert(
                (command.data.options, command.data.guildId, command.interaction.invoker.map(_.bot), answer.content, cleared) ==
                    (
                        Chunk(Command.Data.Option("word", Command.Option.Type.String, Present(Command.Value.Text("kyo")))),
                        Present(l.guild),
                        Present(false),
                        "kyo-discord live: you said kyo",
                        Chunk.empty
                    )
            )
        }
    }

    "a direct message a person sends the bot arrives with no guild, in a DM channel the bot can answer in" in {
        interactive(realOnly = Present(SpacebarDirectMessage)) { l =>
            for
                prompt  <- ask(l, l.channel, "send this bot a direct message with the word kyo", Act.MessagesBot("kyo"))
                message <- next(l) {
                    case Event.MessageCreated(m) if m.guildId.isEmpty && !m.author.bot && m.timestamp >= prompt.timestamp => Present(m)
                    case _                                                                                                => Absent
                }
                dm    <- Discord.channel(message.channelId)
                reply <- Discord.send(message.channelId, text("kyo-discord live: received"))
                _     <- cleanUp(message.channelId, reply.id)
                _     <- cleanUp(l.channel, prompt.id)
            yield assert((message.content, dm.`type`, reply.channelId) == ("kyo", Discord.Channel.Type.Dm, message.channelId))
        }
    }

end DiscordLiveTest
