package kyo

class DiscordMessageTest extends kyo.test.Test[Any]:

    import Discord.*
    import DiscordInvalidMessageException.Problem

    private def at(epochSecond: Long, nanos: Long): Instant = Instant.of(epochSecond.seconds, nanos.nanos)

    // A guild message as MESSAGE_CREATE carries it: guild_id and member added, keys the model does not declare included.
    private val messageJson =
        """{
          |"id":"1209876543210987654","channel_id":"1100000000000000001","guild_id":"1000000000000000001",
          |"author":{"id":"80351110224678912","username":"nelly","discriminator":"0","global_name":"Nelly","avatar":"8342729096ea3675442027381ff50dfe","bot":false},
          |"member":{"roles":["1000000000000000009"],"joined_at":"2015-04-26T06:26:56.936000+00:00","deaf":false,"mute":false},
          |"content":"hello <@80351110224678912>","timestamp":"2016-04-30T11:18:25.796000+00:00","edited_timestamp":null,
          |"tts":false,"mention_everyone":false,"mentions":[{"id":"80351110224678912","username":"nelly","discriminator":"0","global_name":null,"avatar":null}],
          |"mention_roles":[],"attachments":[{"id":"1111111111111111111","filename":"a.png","size":1234,"url":"https://cdn.discordapp.com/attachments/1/2/a.png?ex=1","proxy_url":"https://media.discordapp.net/attachments/1/2/a.png","content_type":"image/png","height":10,"width":20}],
          |"embeds":[{"title":"T","type":"rich","description":"D","color":16711680,"fields":[{"name":"n","value":"v","inline":true}],"footer":{"text":"f"}}],
          |"components":[{"type":1,"components":[{"type":2,"style":1,"label":"Go","custom_id":"go"}]}],
          |"pinned":false,"type":19,"flags":4,"nonce":"x1",
          |"message_reference":{"type":0,"message_id":"1209876543210987600","channel_id":"1100000000000000001","guild_id":"1000000000000000001"}
          |}""".stripMargin

    "a message decodes with Discord's ids, offset timestamps, embeds, attachments, components and reference" in {
        val message = Json.decode[Message](messageJson).getOrThrow
        assert((
            message.id,
            message.channelId,
            message.guildId,
            message.author.globalName,
            message.member.map(_.joinedAt),
            message.timestamp,
            message.editedTimestamp,
            message.mentions.map(_.username),
            message.attachments.map(a => (a.filename, a.size, a.url.host, a.contentType)),
            message.embeds.map(e => (e.title, e.color, e.fields.map(_.inline), e.footer.map(_.text))),
            message.components,
            message.`type`,
            message.flags.contains(Message.Flags.SuppressEmbeds),
            message.messageReference.flatMap(_.messageId)
        ) == (
            MessageId(1209876543210987654L),
            ChannelId(1100000000000000001L),
            Present(GuildId(1000000000000000001L)),
            Present("Nelly"),
            Present(Present(at(1430029616L, 936000000L))),
            at(1462015105L, 796000000L),
            Absent,
            Chunk("nelly"),
            Chunk(("a.png", 1234L, "cdn.discordapp.com", Present("image/png"))),
            Chunk((Present("T"), Present(16711680), Chunk(true), Present("f"))),
            Chunk(Component.ActionRow(Chunk(Component.Button(Component.Button.Style.Primary, Present("Go"), customId = Present("go"))))),
            Message.Type.Reply,
            true,
            Present(MessageId(1209876543210987600L))
        ))
    }

    "a decoded message encodes and decodes back to itself" in {
        val message = Json.decode[Message](messageJson).getOrThrow
        assert(Json.decode[Message](Json.encode(message)) == Result.succeed(message))
    }

    "a message with only the required fields decodes, the rest at their defaults" in {
        val json =
            """{"id":"1","channel_id":"2","author":{"id":"3","username":"u"},"timestamp":"2016-04-30T11:18:25+00:00"}"""
        assert(Json.decode[Message](json).map(m => (m.content, m.embeds, m.components, m.flags, m.`type`, m.author.bot)) ==
            Result.succeed(("", Chunk.empty, Chunk.empty, Message.Flags.Empty, Message.Type.Default, false)))
    }

    "Message.Create" - {

        "writes Discord's JSON: the files are left to the multipart parts, empty embeds and components omitted" in {
            val file   = File.init("a.txt", Span.fromUnsafe("x".getBytes("UTF-8"))).getOrThrow
            val create = Message.Create.init(
                content = Present("hi"),
                files = Chunk(file),
                allowedMentions = Present(AllowedMentions.none),
                reference = Present(Message.Reference.reply(MessageId(5L)))
            ).getOrThrow
            assert(Json.encode(create) ==
                """{"content":"hi","tts":false,"allowed_mentions":{"parse":[],"users":[],"roles":[],"replied_user":false},""" +
                """"message_reference":{"message_id":"5","fail_if_not_exists":false}}""")
            assert(create.files == Chunk(file))
        }

        "an empty allowed-mentions list is written, since an absent one means Discord's default of parsing everything" in {
            val create = Message.Create.init(content = Present("@everyone"), allowedMentions = Present(AllowedMentions.none)).getOrThrow
            assert(Json.encode(create).contains(""""parse":[]"""))
        }

        "init refuses what Discord would" in {
            val embed    = Embed.init(description = Present("d" * 4000)).getOrThrow
            val button   = Component.Button.init(Component.Button.Style.Primary, customId = Present("b")).getOrThrow
            val row      = Component.ActionRow.init(Chunk(button)).getOrThrow
            val file     = File.init("f", Span.empty[Byte]).getOrThrow
            val refusals = Chunk(
                Message.Create.init(),
                Message.Create.init(content = Present("c" * 2001)),
                Message.Create.init(embeds = Chunk.fill(11)(Embed.init(title = Present("t")).getOrThrow)),
                Message.Create.init(embeds = Chunk(embed, embed)),
                Message.Create.init(components = Chunk.fill(6)(row)),
                Message.Create.init(files = Chunk.fill(11)(file)),
                Message.Create.init(content = Present("x"), flags = Present(Message.Flags.Crossposted))
            )
            assert(refusals.map(_.failure.map(_.problem)) == Chunk(
                Present(Problem.Empty),
                Present(Problem.ContentLength(2001, 2000)),
                Present(Problem.EmbedCount(11, 10)),
                Present(Problem.EmbedTotal(8000, 6000)),
                Present(Problem.ComponentCount(6, 5)),
                Present(Problem.FileCount(11, 10)),
                Present(Problem.Flags(1))
            ))
            assert(refusals(
                1
            ).failure.exists(_.getMessage.contains("Discord message is not usable: content has 2001 characters, more than 2000.")))
        }

        "content is counted in characters, not UTF-16 units" in {
            // 2000 emoji: 4000 UTF-16 units, 2000 characters.
            val emoji = "🔥" * 2000
            assert(Message.Create.init(content = Present(emoji)).isSuccess)
            assert(Message.Create.init(content = Present(emoji + "!")).failure.map(_.problem) == Present(Problem.ContentLength(2001, 2000)))
        }

        "the flags a message is sent with, Ephemeral for an interaction's answer among them, are accepted" in {
            val flags = Message.Flags.SuppressEmbeds.union(Message.Flags.Ephemeral).union(Message.Flags.SuppressNotifications)
            assert(Message.Create.init(content = Present("x"), flags = Present(flags)).map(_.flags) == Result.succeed(Present(flags)))
        }
    }

    "Message.Edit" - {

        "a field left Absent is not written, Patch.Clear writes null, Patch.Set the value, and an empty chunk clears a list" in {
            assert(Json.encode(Message.Edit.init().getOrThrow) == "{}")
            assert(Json.encode(Message.Edit.init(content = Present(Patch.Clear)).getOrThrow) == """{"content":null}""")
            assert(Json.encode(Message.Edit.init(content = Present(Patch.Set("new"))).getOrThrow) == """{"content":"new"}""")
            assert(Json.encode(Message.Edit.init(embeds = Present(Chunk.empty), components = Present(Chunk.empty)).getOrThrow) ==
                """{"embeds":[],"components":[]}""")
        }

        "init refuses content and embeds past the limits, and a flag other than SuppressEmbeds and IsComponentsV2" in {
            assert(Chunk(
                Message.Edit.init(content = Present(Patch.Set("c" * 2001))),
                Message.Edit.init(components =
                    Present(Chunk.fill(6)(Component.ActionRow.init(Chunk(
                        Component.Button.init(Component.Button.Style.Primary, customId = Present("b")).getOrThrow
                    )).getOrThrow))
                ),
                Message.Edit.init(flags = Present(Message.Flags.Ephemeral))
            ).map(_.failure.map(_.problem)) == Chunk(
                Present(Problem.ContentLength(2001, 2000)),
                Present(Problem.ComponentCount(6, 5)),
                Present(Problem.Flags(64))
            ))
            assert(Message.Edit.init(flags = Present(Message.Flags.SuppressEmbeds)).isSuccess)
        }
    }

    "Message.Page has a limit of 1 to 100" in {
        assert(Message.Page.init(Message.Page.Anchor.Before(MessageId(9L)), 100).map(p => (p.anchor, p.limit)) ==
            Result.succeed((Message.Page.Anchor.Before(MessageId(9L)), 100)))
        assert(Chunk(0, 101).map(n => Message.Page.init(limit = n).failure.map(_.problem)) ==
            Chunk(Present(Problem.PageLimit(0)), Present(Problem.PageLimit(101))))
    }

    "Embed" - {

        "init refuses each limit of Embed Limits" in {
            assert(Chunk(
                Embed.init(title = Present("t" * 257)),
                Embed.init(description = Present("d" * 4097)),
                Embed.init(fields = Chunk.fill(26)(Embed.Field("n", "v"))),
                Embed.init(fields = Chunk(Embed.Field("n", "v"), Embed.Field("", "v"))),
                Embed.init(fields = Chunk(Embed.Field("n", "v" * 1025))),
                Embed.init(footer = Present(Embed.Footer("f" * 2049))),
                Embed.init(author = Present(Embed.Author("a" * 257))),
                Embed.init(color = Present(0x1000000))
            ).map(_.failure.map(_.problem)) == Chunk(
                Present(Problem.EmbedTitle(257, 256)),
                Present(Problem.EmbedDescription(4097, 4096)),
                Present(Problem.EmbedFieldCount(26, 25)),
                Present(Problem.EmbedFieldName(1, 0, 256)),
                Present(Problem.EmbedFieldValue(0, 1025, 1024)),
                Present(Problem.EmbedFooter(2049, 2048)),
                Present(Problem.EmbedAuthor(257, 256)),
                Present(Problem.EmbedColor(0x1000000))
            ))
        }

        "characters counts what Discord's 6000 total counts" in {
            val embed = Embed.init(
                title = Present("ab"),
                description = Present("cde"),
                fields = Chunk(Embed.Field("f", "gh")),
                footer = Present(Embed.Footer("ijkl")),
                author = Present(Embed.Author("m")),
                url = Present("https://example.com/not-counted")
            ).getOrThrow
            assert(embed.characters == 13)
        }

        "an embed round-trips with an attachment URL and a timestamp" in {
            val embed = Embed.init(
                title = Present("t"),
                image = Present(Embed.Media("attachment://a.png")),
                timestamp = Present(at(1462015105L, 0L))
            ).getOrThrow
            assert(Json.encode(embed) == """{"title":"t","timestamp":"2016-04-30T11:18:25Z","image":{"url":"attachment://a.png"}}""")
            assert(Json.decode[Embed](Json.encode(embed)) == Result.succeed(embed))
        }
    }

    "AllowedMentions" - {

        "writes Discord's parse kinds as lowercase strings" in {
            val mentions = AllowedMentions.init(parse = Chunk(AllowedMentions.Kind.Users, AllowedMentions.Kind.Everyone)).getOrThrow
            assert(Json.encode(mentions) == """{"parse":["users","everyone"],"users":[],"roles":[],"replied_user":false}""")
            assert(Json.decode[AllowedMentions](Json.encode(mentions)) == Result.succeed(mentions))
        }

        "init refuses more than 100 ids and a kind both parsed and listed" in {
            assert(Chunk(
                AllowedMentions.init(users = Chunk.from((1L to 101L).map(UserId(_)))),
                AllowedMentions.init(roles = Chunk.from((1L to 101L).map(RoleId(_)))),
                AllowedMentions.init(parse = Chunk(AllowedMentions.Kind.Users), users = Chunk(UserId(1L))),
                AllowedMentions.init(parse = Chunk(AllowedMentions.Kind.Roles), roles = Chunk(RoleId(1L)))
            ).map(_.failure.map(_.problem)) == Chunk(
                Present(Problem.MentionCount("users", 101, 100)),
                Present(Problem.MentionCount("roles", 101, 100)),
                Present(Problem.MentionConflict("users")),
                Present(Problem.MentionConflict("roles"))
            ))
        }
    }

    "File.init refuses a name a multipart part's head or a file system cannot carry, and a long description" in {
        assert(Chunk(
            File.init("", Span.empty[Byte]),
            File.init("n" * 1025, Span.empty[Byte]),
            File.init("a\"b.txt", Span.empty[Byte]),
            File.init("a\r\nb.txt", Span.empty[Byte]),
            File.init("dir/a.txt", Span.empty[Byte]),
            File.init("a.txt", Span.empty[Byte], description = Present("d" * 1025))
        ).map(_.failure.map(_.problem)) == Chunk(
            Present(Problem.FileName(0)),
            Present(Problem.FileName(1025)),
            Present(Problem.FileNameCharacter(1)),
            Present(Problem.FileNameCharacter(1)),
            Present(Problem.FileNameCharacter(3)),
            Present(Problem.FileDescription(1025, 1024))
        ))
    }

    "a file is never read from JSON: a body naming files is refused" in {
        assert(Json.decode[Message.Create](
            """{"content":"x","files":["a.txt"]}"""
        ).failure.exists(_.isInstanceOf[ConstructorRejectedException]))
        assert(Json.decode[Message.Create]("""{"content":"x"}""").map(_.files) == Result.succeed(Chunk.empty))
    }

end DiscordMessageTest
