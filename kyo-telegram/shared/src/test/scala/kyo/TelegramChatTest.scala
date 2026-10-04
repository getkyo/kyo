package kyo

import kyo.internal.telegram.WireFieldTest.wire

class TelegramChatTest extends kyo.test.Test[Any]:

    "a private chat is Telegram's Chat object, both ways" in {
        val json = """{"id":5,"type":"private","first_name":"Ann","last_name":"Lee","username":"ann"}"""
        val chat = Telegram.Chat(
            Telegram.ChatId(5L),
            Telegram.Chat.Type.Private,
            firstName = Present("Ann"),
            lastName = Present("Lee"),
            username = Present("ann")
        )
        assert(Json.decode[Telegram.Chat](json) == Result.succeed(chat))
        assert(wire(chat) == wire(json))
    }

    "a forum supergroup carries its title and is_forum" in {
        val json = """{"id":-1001234567890,"type":"supergroup","title":"Team","is_forum":true}"""
        val chat = Telegram.Chat(Telegram.ChatId(-1001234567890L), Telegram.Chat.Type.Supergroup, title = Present("Team"), isForum = true)
        assert(Json.decode[Telegram.Chat](json) == Result.succeed(chat))
        assert(wire(chat) == wire(json))
    }

    "is_forum absent is false, and false is not written back" in {
        val json = """{"id":-1001111111111,"type":"supergroup","title":"Plain"}"""
        val chat = Telegram.Chat(Telegram.ChatId(-1001111111111L), Telegram.Chat.Type.Supergroup, title = Present("Plain"))
        assert(Json.decode[Telegram.Chat](json) == Result.succeed(chat))
        assert(wire(chat) == wire(json))
    }

    "a channel and a basic group" in {
        import TelegramTest.Fixtures.{channel, channelJson, group, groupJson}
        assert(Chunk(channelJson, groupJson).map(Json.decode[Telegram.Chat](_)) == Chunk(channel, group).map(Result.succeed(_)))
        assert(Chunk(channel, group).map(wire(_)) == Chunk(channelJson, groupJson).map(wire(_)))
    }

    "a documented field the module does not model (is_direct_messages) is dropped" in {
        val chat = Telegram.Chat(Telegram.ChatId(-1002222222222L), Telegram.Chat.Type.Supergroup, title = Present("Paid DMs"))
        assert(Json.decode[Telegram.Chat]("""{"id":-1002222222222,"type":"supergroup","title":"Paid DMs","is_direct_messages":true}""") ==
            Result.succeed(chat))
        assert(wire(chat) == wire("""{"id":-1002222222222,"type":"supergroup","title":"Paid DMs"}"""))
    }

    "a chat whose type is outside the four documented is Other with its name" in {
        val json = """{"id":-1003333333333,"type":"community","title":"Future"}"""
        val chat = Telegram.Chat(Telegram.ChatId(-1003333333333L), Telegram.Chat.Type.Other("community"), title = Present("Future"))
        assert(Json.decode[Telegram.Chat](json) == Result.succeed(chat))
        assert(wire(chat) == wire(json))
    }

    "each chat type is its lowercase name, and a type Telegram adds later is Other with that name" in {
        import Telegram.Chat.Type.*
        val types = Chunk(Private, Group, Supergroup, Channel, Other("gigagroup"))
        assert(types.map(Json.encode(_)) == Chunk("\"private\"", "\"group\"", "\"supergroup\"", "\"channel\"", "\"gigagroup\""))
        assert(types.map(t => Json.decode[Telegram.Chat.Type](Json.encode(t))) == types.map(Result.succeed(_)))
    }

    "a target is the chat id as a number, or the username with its @" in {
        val id: Telegram.Chat.Target       = Telegram.Chat.Target.Id(Telegram.ChatId(-100L))
        val username: Telegram.Chat.Target = Telegram.Chat.Target.Username("standup_channel")
        assert(Json.encode(id) == "-100")
        assert(Json.encode(username) == "\"@standup_channel\"")
        assert(Json.decode[Telegram.Chat.Target]("-100") == Result.succeed(id))
        assert(Json.decode[Telegram.Chat.Target]("\"@standup_channel\"") == Result.succeed(username))
    }

    "text that is not a username with its @ is not a target" in {
        assert(Json.decode[Telegram.Chat.Target]("\"standup_channel\"").isFailure)
    }

end TelegramChatTest
