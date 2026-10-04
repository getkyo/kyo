package kyo

import Telegram.Entity
import Telegram.Entity.Kind
import TelegramTest.Fixtures.*

class TelegramEntityTest extends kyo.test.Test[Any]:

    "every documented type without extra fields is its case, both ways" in {
        val json =
            """[{"type":"mention","offset":0,"length":9},{"type":"hashtag","offset":0,"length":8},{"type":"cashtag","offset":0,"length":4},""" +
                """{"type":"bot_command","offset":0,"length":15},{"type":"url","offset":0,"length":20},{"type":"email","offset":0,"length":25},""" +
                """{"type":"phone_number","offset":0,"length":15},{"type":"bold","offset":0,"length":4},{"type":"italic","offset":0,"length":4},""" +
                """{"type":"underline","offset":0,"length":4},{"type":"strikethrough","offset":0,"length":4},{"type":"spoiler","offset":0,"length":4},""" +
                """{"type":"blockquote","offset":0,"length":4},{"type":"expandable_blockquote","offset":0,"length":4},{"type":"code","offset":0,"length":4}]"""
        val entities = Chunk(
            Entity(Kind.Mention, 0, 9),
            Entity(Kind.Hashtag, 0, 8),
            Entity(Kind.Cashtag, 0, 4),
            Entity(Kind.BotCommand, 0, 15),
            Entity(Kind.Url, 0, 20),
            Entity(Kind.Email, 0, 25),
            Entity(Kind.PhoneNumber, 0, 15),
            Entity(Kind.Bold, 0, 4),
            Entity(Kind.Italic, 0, 4),
            Entity(Kind.Underline, 0, 4),
            Entity(Kind.Strikethrough, 0, 4),
            Entity(Kind.Spoiler, 0, 4),
            Entity(Kind.Blockquote, 0, 4),
            Entity(Kind.ExpandableBlockquote, 0, 4),
            Entity(Kind.Code, 0, 4)
        )
        assert(Json.decode[Chunk[Entity]](json) == Result.succeed(entities))
        assert(wire(entities) == wire(json))
    }

    "pre with and without a language" in {
        val json     = """[{"type":"pre","offset":0,"length":12,"language":"scala"},{"type":"pre","offset":13,"length":3}]"""
        val entities = Chunk(Entity(Kind.Pre(Present("scala")), 0, 12), Entity(Kind.Pre(Absent), 13, 3))
        assert(Json.decode[Chunk[Entity]](json) == Result.succeed(entities))
        assert(wire(entities) == wire(json))
    }

    "text_link carries its url" in {
        val json   = """{"type":"text_link","offset":4,"length":4,"url":"https://kyo.dev/docs"}"""
        val entity = Entity(Kind.TextLink(Telegram.Url.init("https://kyo.dev/docs").getOrThrow), 4, 4)
        assert(Json.decode[Entity](json) == Result.succeed(entity))
        assert(wire(entity) == wire(json))
    }

    "text_mention carries the whole user" in {
        val json   = """{"type":"text_mention","offset":0,"length":3,"user":{"id":333333333,"is_bot":false,"first_name":"Eve"}}"""
        val entity = Entity(Kind.TextMention(Telegram.User(Telegram.UserId(333333333L), isBot = false, firstName = "Eve")), 0, 3)
        assert(Json.decode[Entity](json) == Result.succeed(entity))
        assert(wire(entity) == wire(json))
    }

    "custom_emoji carries its sticker id" in {
        val json   = """{"type":"custom_emoji","offset":2,"length":2,"custom_emoji_id":"5368324170671202286"}"""
        val entity = Entity(Kind.CustomEmoji("5368324170671202286"), 2, 2)
        assert(Json.decode[Entity](json) == Result.succeed(entity))
        assert(wire(entity) == wire(json))
    }

    "date_time carries its Unix time and, when given, its format" in {
        val json = """[{"type":"date_time","offset":10,"length":8,"unix_time":1735689600,"date_time_format":"t"},""" +
            """{"type":"date_time","offset":0,"length":5,"unix_time":0}]"""
        val entities = Chunk(Entity(Kind.DateTime(at(1735689600), Present("t")), 10, 8), Entity(Kind.DateTime(at(0)), 0, 5))
        assert(Json.decode[Chunk[Entity]](json) == Result.succeed(entities))
        assert(wire(entities) == wire(json))
    }

    "a type after Bot API 10.3 is Other, holding its name and the whole entity, and is written back as it came" in {
        val json   = """{"type":"sparkle","offset":0,"length":5,"intensity":3}"""
        val entity = Entity(Kind.Other("sparkle", raw(json)), 0, 5)
        assert(Json.decode[Entity](json) == Result.succeed(entity))
        assert(wire(entity) == wire(json))
    }

    "a text_link whose url Telegram.Url refuses fails the decode" in {
        assert(Json.decode[Entity]("""{"type":"text_link","offset":0,"length":1,"url":"ftp://kyo.dev"}""").isFailure)
    }

    "offsets and lengths are UTF-16 code units" in {
        val json =
            s"""{"message_id":10,"date":1735689600,"chat":$privateChatJson,"text":"héllo 👍 @alice","entities":[{"type":"mention","offset":9,"length":6}]}"""
        assert(Json.decode[Telegram.Message](json) == Result.succeed(Telegram.Message(
            Telegram.MessageId(10),
            privateChat,
            at(1735689600),
            Telegram.Message.Content.Text("héllo 👍 @alice", Chunk(Entity(Kind.Mention, 9, 6)))
        )))
        assert(Entity(Kind.Mention, 9, 6).of("héllo 👍 @alice") == Present("@alice"))
    }

    "of reads the entity's span in UTF-16 code units, and nothing outside the text" in {
        val text = "👍 /start now"
        assert(Telegram.Entity(Telegram.Entity.Kind.BotCommand, 3, 6).of(text) == Present("/start"))
        assert(Telegram.Entity(Telegram.Entity.Kind.Bold, 0, 2).of(text) == Present("👍"))
        assert(Telegram.Entity(Telegram.Entity.Kind.Bold, 10, 5).of(text) == Absent)
        assert(Telegram.Entity(Telegram.Entity.Kind.Bold, -1, 1).of(text) == Absent)
        assert(Telegram.Entity(Telegram.Entity.Kind.Bold, 1, Int.MaxValue).of(text) == Absent)
    }

end TelegramEntityTest
