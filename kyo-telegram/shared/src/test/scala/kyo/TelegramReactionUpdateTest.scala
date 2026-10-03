package kyo

import TelegramTest.Fixtures.*

class TelegramReactionUpdateTest extends kyo.test.Test[Any]:

    "a user adds a thumbs up" in {
        val json = s"""{"chat":$forumJson,"message_id":61,"user":$aliceUserJson,"date":1735689900,"old_reaction":[],""" +
            """"new_reaction":[{"type":"emoji","emoji":"👍"}]}"""
        val update = Telegram.ReactionUpdate(
            forum,
            Telegram.MessageId(61),
            at(1735689900),
            Chunk.empty,
            Chunk(Telegram.Reaction.Emoji("👍")),
            user = Present(aliceUser)
        )
        assert(Json.decode[Telegram.ReactionUpdate](json) == Result.succeed(update))
        assert(wire(update) == wire(json))
    }

    "an anonymous admin changes a reaction: actor_chat and no user" in {
        val json = s"""{"chat":$forumJson,"message_id":61,"actor_chat":$forumJson,"date":1735689901,""" +
            """"old_reaction":[{"type":"emoji","emoji":"👍"}],"new_reaction":[{"type":"custom_emoji","custom_emoji_id":"5368324170671202286"}]}"""
        val update = Telegram.ReactionUpdate(
            forum,
            Telegram.MessageId(61),
            at(1735689901),
            Chunk(Telegram.Reaction.Emoji("👍")),
            Chunk(Telegram.Reaction.CustomEmoji("5368324170671202286")),
            actorChat = Present(forum)
        )
        assert(Json.decode[Telegram.ReactionUpdate](json) == Result.succeed(update))
        assert(wire(update) == wire(json))
    }

    "a MessageReactionUpdated is Telegram's object, both ways" in {
        val json =
            """{"chat":{"id":-100,"type":"supergroup","title":"Team"},"message_id":42,"date":1700000000,""" +
                """"user":{"id":5,"is_bot":false,"first_name":"Ann"},""" +
                """"old_reaction":[{"type":"emoji","emoji":"👍"}],""" +
                """"new_reaction":[{"type":"custom_emoji","custom_emoji_id":"5368324170671202286"},{"type":"paid"}]}"""
        val update = Telegram.ReactionUpdate(
            Telegram.Chat(Telegram.ChatId(-100L), Telegram.Chat.Type.Supergroup, title = Present("Team")),
            Telegram.MessageId(42),
            Instant.of(1700000000L.seconds, Duration.Zero),
            Chunk(Telegram.Reaction.Emoji("👍")),
            Chunk(Telegram.Reaction.CustomEmoji("5368324170671202286"), Telegram.Reaction.Paid),
            user = Present(Telegram.User(Telegram.UserId(5L), false, "Ann"))
        )
        assert(Json.decode[Telegram.ReactionUpdate](json) == Result.succeed(update))
        assert(wire(update) == wire(json))
    }

    "an anonymous reaction names the chat that reacted instead of a user" in {
        val json =
            """{"chat":{"id":-100,"type":"supergroup","title":"Team"},"message_id":42,"date":1700000000,""" +
                """"actor_chat":{"id":-200,"type":"channel","title":"News"},"old_reaction":[],"new_reaction":[]}"""
        val decoded = Json.decode[Telegram.ReactionUpdate](json)
        assert(decoded.map(u => (u.user, u.actorChat.map(_.id))) == Result.succeed((Absent, Present(Telegram.ChatId(-200L)))))
    }

end TelegramReactionUpdateTest
