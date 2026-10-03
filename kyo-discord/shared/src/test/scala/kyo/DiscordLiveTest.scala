package kyo

import java.nio.charset.StandardCharsets.UTF_8

/** The module against the real Discord API and Gateway. Every leaf needs `DISCORD_BOT_TOKEN`, a bot's token from the developer portal,
  * read through `kyo.System.env` so the suite runs on every platform, and is cancelled without it.
  *
  * The leaves that send need `DISCORD_TEST_CHANNEL_ID`, a text channel the bot can view, send in, react in, read the history of and
  * start and manage threads in. The command leaf needs `DISCORD_TEST_GUILD_ID`, a guild the bot was added to with the
  * `applications.commands` scope. Without them those leaves are cancelled with a message naming the variable; the others still run.
  *
  * The suite deletes every message, thread and command it creates.
  *
  * The failure leaves assert whole values, descriptions included: the description is Discord's text, which the documentation does
  * not list.
  */
class DiscordLiveTest extends kyo.test.Test[Any]:

    // The suite shares one bot and one channel, so its leaves run one at a time; kyo-net reaps closed connections on the selector's
    // next pass, which the socket leak check sees as an open descriptor.
    override def config = super.config.leakCheckSockets(false).sequential

    // deviation: a leaf waits on the real API and Gateway, which no virtual clock can stand in for. This per-leaf bound is the only
    // real-clock limit in the module's tests; no assertion reads elapsed time.
    override def timeout: Duration = 2.minutes

    import Discord.*
    // kyo's channel, over the wildcard's `Discord.Channel`.
    import kyo.Channel

    private def bot(intents: Intents = Intents.Guilds)(using Frame): DiscordConfig < Sync =
        System.env[String]("DISCORD_BOT_TOKEN").map {
            case Absent     => cancel("DISCORD_BOT_TOKEN must be set: the live Discord suite needs a bot")
            case Present(t) => DiscordConfig.init(Token.init(t).getOrThrow, intents).getOrThrow
        }

    private def testChannel(using Frame): ChannelId < Sync =
        System.env[String]("DISCORD_TEST_CHANNEL_ID").map {
            case Absent     => cancel("DISCORD_TEST_CHANNEL_ID must be set: this leaf sends to a channel")
            case Present(t) => ChannelId.parse(t).getOrThrow
        }

    private def testGuild(using Frame): GuildId < Sync =
        System.env[String]("DISCORD_TEST_GUILD_ID").map {
            case Absent     => cancel("DISCORD_TEST_GUILD_ID must be set: this leaf registers commands in a guild")
            case Present(t) => GuildId.parse(t).getOrThrow
        }

    private def text(content: String)(using Frame): Message.Create = Message.Create.init(content = Present(content)).getOrThrow

    "GET /gateway/bot answers a wss URL, at least one shard and the session start budget" in {
        bot().map(config => Discord.run(config)(Discord.gateway)).map { info =>
            assert(info.url.startsWith("wss://"))
            assert(info.shards >= 1)
            assert(info.sessionStartLimit.total >= info.sessionStartLimit.remaining)
            assert(info.sessionStartLimit.maxConcurrency >= 1)
        }
    }

    "a token Discord does not accept fails with the unauthorized leaf" in {
        bot().map { config =>
            val forged = config.copy(token = Token.init(config.token.value.reverse).getOrThrow)
            Abort.run[DiscordGatewayInfoFailure](Discord.run(forged)(Discord.gateway)).map { result =>
                assert(result.failure == Present(DiscordUnauthorizedException("GET /gateway/bot", Code(0), "401: Unauthorized")))
            }
        }
    }

    "a channel that does not exist fails with the unknown channel leaf" in {
        bot().map { config =>
            Abort.run[DiscordChannelFailure](Discord.run(config)(Discord.channel(ChannelId(1L)))).map { result =>
                assert(result.failure == Present(DiscordUnknownChannelException("GET /channels/{channel.id}", "Unknown Channel")))
            }
        }
    }

    "a message is sent, read back, edited, reacted to, listed and deleted, after which it is unknown" in {
        bot().map { config =>
            testChannel.map { channel =>
                Discord.run(config) {
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
                        _       <- Discord.react(channel, sent.id, fire)
                        _       <- Discord.unreact(channel, sent.id, fire)
                        around  <- Discord.messages(channel, Message.Page.init(Message.Page.Anchor.Around(sent.id), 5).getOrThrow)
                        _       <- Discord.delete(channel, sent.id)
                        deleted <- Abort.run[DiscordMessageFailure](Discord.message(channel, sent.id))
                    yield
                        assert((read.id, read.content, read.author.bot) == (sent.id, "kyo-discord live: sent", true))
                        assert((edited.content, edited.editedTimestamp.isDefined) == ("kyo-discord live: edited", true))
                        assert(around.map(_.id).contains(sent.id))
                        assert(deleted.failure ==
                            Present(DiscordUnknownMessageException("GET /channels/{channel.id}/messages/{message.id}", "Unknown Message")))
                    end for
                }
            }
        }
    }

    "a message with a file arrives with the attachment named and sized" in {
        val file = File.init("kyo.txt", Span.from("kyo-discord".getBytes(UTF_8)), description = Present("live")).getOrThrow
        bot().map { config =>
            testChannel.map { channel =>
                Discord.run(config) {
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
        bot().map { config =>
            testChannel.map { channel =>
                Discord.run(config) {
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

    "guild commands registered are answered with their ids, and registering none removes them" in {
        bot().map { config =>
            testGuild.map { guild =>
                Discord.run(config) {
                    val roll = Command.Create.init("kyo-live-roll", "kyo-discord live suite").getOrThrow
                    for
                        registered <- Discord.registerCommands(guild, Chunk(roll))
                        cleared    <- Discord.registerCommands(guild, Chunk.empty)
                    yield
                        assert(registered.map(c => (c.name, c.guildId)) == Chunk(("kyo-live-roll", Present(guild))))
                        assert(cleared == Chunk.empty)
                    end for
                }
            }
        }
    }

    "init reaches Ready on the real Gateway, and receive delivers the bot's own message" in {
        bot(Intents.Guilds.union(Intents.GuildMessages)).map { config =>
            testChannel.map { channel =>
                Discord.init(config).map { discord =>
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

end DiscordLiveTest
