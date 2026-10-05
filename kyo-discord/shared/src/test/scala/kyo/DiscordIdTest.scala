package kyo

class DiscordIdTest extends kyo.test.Test[Any]:

    import Discord.*
    import DiscordInvalidIdException.Problem

    "parse reads a snowflake's decimal text, and value is the number" in {
        assert(ChannelId.parse("175928847299117063").map(_.value) == Result.succeed(175928847299117063L))
    }

    "a snowflake with the top bit set reads and writes unsigned" in {
        val text = "18446744073709551615"
        val id   = MessageId.parse(text)
        assert(id.map(_.value) == Result.succeed(-1L))
        assert(id.map(m => Json.encode(m)) == Result.succeed(s""""$text""""))
    }

    "createdAt is bits 63 to 22 as milliseconds after Discord's epoch, the first millisecond of 2015" in {
        val millis = 41234567890L
        assert(UserId(millis << 22).createdAt == Instant.Epoch + (1420070400000L + millis).millis)
        assert(GuildId((millis << 22) | 0x3fffffL).createdAt == Instant.Epoch + (1420070400000L + millis).millis)
        assert(GuildId(0L).createdAt == Instant.Epoch + 1420070400000L.millis)
    }

    "createdAt reads the timestamp unsigned once it reaches bit 63" in {
        val millis = (1L << 41) + 5L
        assert(ChannelId(millis << 22).createdAt == Instant.Epoch + (1420070400000L + millis).millis)
    }

    "parse refuses text that is not a snowflake" - {
        "empty text" in {
            val ex = WebhookId.parse("").failure
            assert(ex == Present(DiscordInvalidIdException(Problem.Empty)))
            assert(ex.exists(_.getMessage.contains("Discord id is not usable: it is empty.")))
        }

        "a character that is not a decimal digit, at its position" in {
            assert(Chunk("12a4", "-123", "12 4", "1.0").map(t => RoleId.parse(t).failure) == Chunk(
                Present(DiscordInvalidIdException(Problem.Character(2))),
                Present(DiscordInvalidIdException(Problem.Character(0))),
                Present(DiscordInvalidIdException(Problem.Character(2))),
                Present(DiscordInvalidIdException(Problem.Character(1)))
            ))
            assert(RoleId.parse("12a4").failure.exists(_.getMessage.contains("the character at position 2 is not a decimal digit")))
        }

        "a number past 64 unsigned bits" in {
            assert(Chunk("18446744073709551616", "18446744073709551620", "99999999999999999999", "100000000000000000000")
                .map(t => EmojiId.parse(t).failure) == Chunk.fill(4)(Present(DiscordInvalidIdException(Problem.Overflow))))
        }
    }

    "the Schema is the decimal string, and refuses anything else as a decode failure" in {
        val id = AttachmentId(1234567890123456789L)
        assert(Json.encode(id) == "\"1234567890123456789\"")
        assert(Json.decode[AttachmentId]("\"1234567890123456789\"") == Result.succeed(id))
        assert(Json.decode[AttachmentId]("\"12x\"").failure.exists(_.isInstanceOf[ConstructorRejectedException]))
        assert(Json.decode[AttachmentId]("1234").failure.exists(_.isInstanceOf[TypeMismatchException]))
    }

    "every id kind round-trips through its Schema" in {
        val n = 987654321098765432L
        assert(Chunk(
            Json.decode[GuildId](Json.encode(GuildId(n))).map(_.value),
            Json.decode[ChannelId](Json.encode(ChannelId(n))).map(_.value),
            Json.decode[UserId](Json.encode(UserId(n))).map(_.value),
            Json.decode[MessageId](Json.encode(MessageId(n))).map(_.value),
            Json.decode[RoleId](Json.encode(RoleId(n))).map(_.value),
            Json.decode[EmojiId](Json.encode(EmojiId(n))).map(_.value),
            Json.decode[ApplicationId](Json.encode(ApplicationId(n))).map(_.value),
            Json.decode[InteractionId](Json.encode(InteractionId(n))).map(_.value),
            Json.decode[CommandId](Json.encode(CommandId(n))).map(_.value),
            Json.decode[AttachmentId](Json.encode(AttachmentId(n))).map(_.value),
            Json.decode[WebhookId](Json.encode(WebhookId(n))).map(_.value)
        ) == Chunk.fill(11)(Result.succeed(n)))
    }

    "a Code is Discord's integer error code" in {
        assert(Json.encode(Code(50013)) == "50013")
        assert(Json.decode[Code]("10003").map(_.value) == Result.succeed(10003))
    }

    "intents combine with union, contains tests every bit, and the Schema is the integer Identify sends" in {
        val intents = Intents.Guilds.union(Intents.GuildMessages).union(Intents.MessageContent)
        assert(intents.value == (1L | (1L << 9) | (1L << 15)))
        assert(intents.contains(Intents.GuildMessages.union(Intents.Guilds)))
        assert(!intents.contains(Intents.GuildMembers))
        assert(intents.contains(Intents.Empty))
        assert(Json.encode(intents) == "33281")
        assert(Json.decode[Intents]("33281") == Result.succeed(intents))
    }

    "a shard has 0 <= id < count" in {
        import DiscordInvalidConfigException.Problem
        assert(Shard.init(0, 1).map(s => (s.id, s.count)) == Result.succeed((0, 1)))
        assert(Shard.init(3, 4).map(s => (s.id, s.count)) == Result.succeed((3, 4)))
        assert(Chunk((1, 1), (-1, 2), (0, 0), (5, 4)).map((id, count) => Shard.init(id, count).failure) == Chunk(
            Present(DiscordInvalidConfigException(Problem.Shard(1, 1))),
            Present(DiscordInvalidConfigException(Problem.Shard(-1, 2))),
            Present(DiscordInvalidConfigException(Problem.Shard(0, 0))),
            Present(DiscordInvalidConfigException(Problem.Shard(5, 4)))
        ))
        assert(Shard.init(5, 4).failure.exists(
            _.getMessage.contains("DiscordConfig.shard must have 0 <= id < count; got id 5 of count 4.")
        ))
    }

end DiscordIdTest
