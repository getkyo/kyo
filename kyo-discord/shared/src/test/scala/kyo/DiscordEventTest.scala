package kyo

class DiscordEventTest extends kyo.test.Test[Any]:

    import Discord.*

    private def at(epochSecond: Long, nanos: Long): Instant = Instant.of(epochSecond.seconds, nanos.nanos)

    private def dispatch(t: String, d: String): String = s"""{"op":0,"s":42,"t":"$t","d":$d}"""

    private def decode(t: String, d: String): Result[DecodeException, Event[?]] = Json.decode[Event[?]](dispatch(t, d))

    private def decoded(event: Event[?]): Result[DecodeException, Event[?]] = Result.succeed(event)

    private val user    = """{"id":"80351110224678912","username":"nelly","discriminator":"0","global_name":null,"avatar":null}"""
    private val channel =
        """{"id":"1100000000000000001","type":11,"guild_id":"1000000000000000001","name":"t","parent_id":"1100000000000000000",""" +
            """"thread_metadata":{"archived":false,"auto_archive_duration":1440,"archive_timestamp":"2021-04-12T23:40:39.855793+00:00","locked":false}}"""

    "a Gateway dispatch names its event in t and carries it in d" - {

        "READY, with the shard Identify sent as [id, count]" in {
            val d = s"""{"v":10,"user":$user,"guilds":[{"id":"1000000000000000001","unavailable":true}],"session_id":"s",""" +
                """"resume_gateway_url":"wss://gateway-us-east1-b.discord.gg","shard":[1,4],"application":{"id":"1100000000000000000","flags":0}}"""
            assert(decode("READY", d) == decoded(Event.Ready(
                User(UserId(80351110224678912L), "nelly"),
                Chunk(UnavailableGuild(GuildId(1000000000000000001L))),
                Event.Ready.Application(ApplicationId(1100000000000000000L), Present(0)),
                Present(Shard.init(1, 4).getOrThrow)
            )))
        }

        "RESUMED, whose d is null" in {
            assert(decode("RESUMED", "null") == decoded(Event.Resumed))
        }

        "MESSAGE_CREATE and MESSAGE_UPDATE carry the message itself" in {
            val d =
                s"""{"id":"1","channel_id":"2","guild_id":"3","author":$user,"content":"hi","timestamp":"2016-04-30T11:18:25.796000+00:00"}"""
            assert(Chunk(decode("MESSAGE_CREATE", d), decode("MESSAGE_UPDATE", d)).map(_.map {
                case Event.MessageCreated(m) => ("created", m.content, m.guildId, m.timestamp)
                case Event.MessageUpdated(m) => ("updated", m.content, m.guildId, m.timestamp)
                case other                   => (other.toString, "", Absent, Instant.Epoch)
            }) == Chunk(
                Result.succeed(("created", "hi", Present(GuildId(3L)), at(1462015105L, 796000000L))),
                Result.succeed(("updated", "hi", Present(GuildId(3L)), at(1462015105L, 796000000L)))
            ))
        }

        "MESSAGE_DELETE, MESSAGE_REACTION_ADD and MESSAGE_REACTION_REMOVE" in {
            assert(Chunk(
                decode("MESSAGE_DELETE", """{"id":"1","channel_id":"2"}"""),
                decode(
                    "MESSAGE_REACTION_ADD",
                    """{"user_id":"5","channel_id":"2","message_id":"1","guild_id":"3","member":{"roles":[]},""" +
                        """"emoji":{"id":"41771983429993937","name":"LUL","animated":true},"burst":false,"type":0}"""
                ),
                decode(
                    "MESSAGE_REACTION_REMOVE",
                    """{"user_id":"5","channel_id":"2","message_id":"1","emoji":{"id":null,"name":"🔥"},"burst":false,"type":0}"""
                )
            ) == Chunk(
                decoded(Event.MessageDeleted(MessageId(1L), ChannelId(2L))),
                decoded(Event.ReactionAdded(
                    UserId(5L),
                    ChannelId(2L),
                    MessageId(1L),
                    Present(GuildId(3L)),
                    Present(Member()),
                    Emoji.custom(EmojiId(41771983429993937L), "LUL", animated = true)
                )),
                decoded(Event.ReactionRemoved(UserId(5L), ChannelId(2L), MessageId(1L), Absent, Emoji.unicode("🔥")))
            ))
        }

        "THREAD_CREATE, THREAD_DELETE and the channel events" in {
            val thread = Json.decode[Channel](channel).getOrThrow
            assert(Chunk(
                decode("THREAD_CREATE", channel),
                decode("THREAD_UPDATE", channel),
                decode(
                    "THREAD_DELETE",
                    """{"id":"1100000000000000001","guild_id":"1000000000000000001","parent_id":"1100000000000000000","type":11}"""
                ),
                decode("CHANNEL_CREATE", channel),
                decode("CHANNEL_UPDATE", channel),
                decode("CHANNEL_DELETE", channel)
            ) == Chunk(
                decoded(Event.ThreadCreated(thread)),
                decoded(Event.ThreadUpdated(thread)),
                decoded(Event.ThreadDeleted(
                    ChannelId(1100000000000000001L),
                    Present(GuildId(1000000000000000001L)),
                    Present(ChannelId(1100000000000000000L)),
                    Channel.Type.PublicThread
                )),
                decoded(Event.ChannelCreated(thread)),
                decoded(Event.ChannelUpdated(thread)),
                decoded(Event.ChannelDeleted(thread))
            ))
            assert(thread.threadMetadata.map(_.archiveTimestamp) == Present(at(1618270839L, 855793000L)))
        }

        "GUILD_CREATE and GUILD_DELETE" in {
            assert(Chunk(
                decode(
                    "GUILD_CREATE",
                    s"""{"id":"3","name":"Guild","owner_id":"5","member_count":2,"channels":[$channel],"large":false}"""
                ),
                decode("GUILD_DELETE", """{"id":"3","unavailable":true}"""),
                decode("GUILD_DELETE", """{"id":"3"}""")
            ).map(_.map {
                case Event.GuildCreated(g)     => (g.name, g.ownerId.map(_.value), g.channels.size.toLong)
                case Event.GuildDeleted(id, u) => (u.toString, Present(id.value), 0L)
                case other                     => (other.toString, Absent, 0L)
            }) == Chunk(
                Result.succeed(("Guild", Present(5L), 1L)),
                Result.succeed(("true", Present(3L), 0L)),
                Result.succeed(("false", Present(3L), 0L))
            ))
        }

        "GUILD_MEMBER_ADD carries the member with guild_id beside its fields" in {
            val d =
                s"""{"guild_id":"3","user":$user,"nick":"n","roles":["7"],"joined_at":"2015-04-26T06:26:56.936000+00:00","deaf":false,"mute":false}"""
            val joined = decode("GUILD_MEMBER_ADD", d).getOrThrow
            assert(joined match
                case j: Event.MemberJoined =>
                    (j.guildId, j.member) == (
                        GuildId(3L),
                        Member(
                            Present(User(UserId(80351110224678912L), "nelly")),
                            Present("n"),
                            Chunk(RoleId(7L)),
                            Present(at(1430029616L, 936000000L))
                        )
                    )
                case _ => false)
        }

        "GUILD_MEMBER_REMOVE and TYPING_START, whose timestamp is Unix seconds" in {
            assert(Chunk(
                decode("GUILD_MEMBER_REMOVE", s"""{"guild_id":"3","user":$user}"""),
                decode("TYPING_START", """{"channel_id":"2","user_id":"5","timestamp":1462015105}""")
            ) == Chunk(
                decoded(Event.MemberLeft(GuildId(3L), User(UserId(80351110224678912L), "nelly"))),
                decoded(Event.TypingStarted(ChannelId(2L), Absent, UserId(5L), at(1462015105L, 0L)))
            ))
        }

        "a dispatch the model does not declare is Unknown with its name and JSON" in {
            val event = decode("ENTITLEMENT_CREATE", """{"id":"9","sku_id":"8"}""").getOrThrow
            assert(event match
                case Event.Unknown("ENTITLEMENT_CREATE", payload) => payload.value == """{"id":"9","sku_id":"8"}"""
                case _                                            => false)
            assert(event.toString == "Unknown(ENTITLEMENT_CREATE,Discord.RawJson(23 characters))")
        }

        "a dispatch whose payload does not decode is a decode failure, not an Unknown" in {
            assert(decode("MESSAGE_DELETE", """{"channel_id":"2"}""").failure.nonEmpty)
        }
    }

    "INTERACTION_CREATE is decoded by the interaction's integer type" - {

        val common =
            s""""id":"1200000000000000001","application_id":"1100000000000000000","token":"aW50ZXJhY3Rpb24.dG9rZW4","version":1,""" +
                s""""guild_id":"3","channel_id":"2","member":{"user":$user,"roles":[],"permissions":"2147483647"},"app_permissions":"442368","locale":"en-US""""

        "a command, with its data" in {
            val event = decode("INTERACTION_CREATE", s"""{"type":2,$common,"data":{"id":"1","name":"roll","type":1}}""").getOrThrow
            assert(event match
                case Event.Command(i, data) =>
                    (i.ref.application, i.ref.id, i.ref.token.value, i.invoker.map(_.username), data.name) ==
                        (
                            ApplicationId(1100000000000000000L),
                            InteractionId(1200000000000000001L),
                            "aW50ZXJhY3Rpb24.dG9rZW4",
                            Present("nelly"),
                            "roll"
                        )
                case _ => false)
        }

        "a component use, an autocomplete and a modal submission" in {
            val events = Chunk(
                decode("INTERACTION_CREATE", s"""{"type":3,$common,"data":{"custom_id":"pick","component_type":3,"values":["a","b"]}}"""),
                decode(
                    "INTERACTION_CREATE",
                    s"""{"type":4,$common,"data":{"id":"1","name":"roll","type":1,"options":[{"name":"sides","type":4,"value":"1","focused":true}]}}"""
                ),
                decode(
                    "INTERACTION_CREATE",
                    s"""{"type":5,$common,"data":{"custom_id":"form","components":[{"type":1,"components":[{"type":4,"custom_id":"note","value":"hi"}]}]}}"""
                )
            ).map(_.getOrThrow)
            assert(events.map {
                case Event.Component(_, d)    => s"component ${d.customId} ${d.values.mkString(",")}"
                case Event.Autocomplete(_, d) => s"autocomplete ${d.options.map(_.focused).mkString(",")}"
                case Event.ModalSubmit(_, d)  => s"modal ${d.customId} ${d.text("note").getOrElse("?")}"
                case other                    => other.toString
            } == Chunk("component pick a,b", "autocomplete true", "modal form hi"))
        }

        "an interaction kind the model does not declare is UnknownInteraction with its type and JSON" in {
            val event = decode("INTERACTION_CREATE", s"""{"type":1,$common}""").getOrThrow
            assert(event match
                case unknown @ Event.UnknownInteraction(1, payload) =>
                    payload.value.startsWith("""{"type":1""") &&
                    unknown.interaction.map(_.ref.id) == Result.succeed(InteractionId(1200000000000000001L))
                case _ => false)
        }

        "an UnknownInteraction whose JSON lacks the shared fields reports which field and where" in {
            val event = decode("INTERACTION_CREATE", """{"type":9,"id":"1","token":"aW50ZXJhY3Rpb24.dG9rZW4"}""").getOrThrow
            assert(event match
                case unknown: Event.UnknownInteraction =>
                    unknown.interaction.failure.map(e => (e.part, e.failure, e.path)) ==
                        Present((
                            DiscordDecodeException.Part.Interaction,
                            DiscordDecodeException.Failure.MissingField,
                            Chunk("application_id")
                        ))
                case _ => false)
        }

        "the interactions endpoint's bare interaction decodes by the same Schema, without the dispatch around it" in {
            val bare = s"""{"type":2,$common,"data":{"id":"1","name":"roll","type":1}}"""
            assert(Json.decode[Event.InteractionEvent[?]](bare).map {
                case Event.Command(i, d) => (i.ref.id, d.name)
                case other               => (InteractionId(0L), other.toString)
            } == Result.succeed((InteractionId(1200000000000000001L), "roll")))
        }

        "an interaction event encodes back to INTERACTION_CREATE and decodes to itself" in {
            val events = Chunk(
                decode("INTERACTION_CREATE", s"""{"type":2,$common,"data":{"id":"1","name":"roll","type":1}}"""),
                decode("INTERACTION_CREATE", s"""{"type":3,$common,"data":{"custom_id":"pick","component_type":3,"values":["a"]}}"""),
                decode("INTERACTION_CREATE", s"""{"type":1,$common}""")
            ).map(_.getOrThrow)
            val encoded = events.map(e => Json.encode(e))
            assert(encoded.forall(_.startsWith("""{"t":"INTERACTION_CREATE","d":{"type":""")), encoded.toString)
            assert(encoded.map(Json.decode[Event[?]](_)) == events.map(decoded))
        }

        "an interaction token outside Discord's alphabet fails the decode, so no URL is built from it" in {
            val bad = common.replace("aW50ZXJhY3Rpb24.dG9rZW4", "a/b")
            assert(decode("INTERACTION_CREATE", s"""{"type":2,$bad,"data":{"id":"1","name":"roll","type":1}}""").failure.nonEmpty)
        }

        "an interaction renders its token redacted" in {
            val event = decode("INTERACTION_CREATE", s"""{"type":2,$common,"data":{"id":"1","name":"roll","type":1}}""").getOrThrow
            assert(!event.toString.contains("dG9rZW4") && event.toString.contains("Discord.InteractionToken(<redacted>)"))
        }
    }

    "an event encodes to a dispatch and decodes back to itself" in {
        val events: Chunk[Event[?]] = Chunk(
            Event.Resumed,
            Event.MessageDeleted(MessageId(1L), ChannelId(2L), Present(GuildId(3L))),
            Event.MemberJoined(GuildId(3L), nick = Present("n"), roles = Chunk(RoleId(7L))),
            Event.TypingStarted(ChannelId(2L), Absent, UserId(5L), at(1462015105L, 0L)),
            Event.Unknown("X_Y", RawJson(Structure.Value.Record(Chunk("a" -> Structure.Value.Integer(1L)))))
        )
        assert(events.map(e => Json.decode[Event[?]](Json.encode(e))) == events.map(decoded))
    }

    "unhandled answers a dispatch with Unit and declines an interaction" in {
        val typing  = Event.TypingStarted(ChannelId(2L), Absent, UserId(5L), at(1462015105L, 0L))
        val unknown = Event.UnknownInteraction(9, RawJson(Structure.Value.Record(Chunk.empty)))
        Abort.run[Event.Decline](Event.unhandled(typing)).map { plain =>
            Abort.run[Event.Decline](Event.unhandled(unknown)).map { interaction =>
                assert(plain == Result.unit)
                assert(interaction == Result.fail(Event.Decline))
            }
        }
    }

end DiscordEventTest
