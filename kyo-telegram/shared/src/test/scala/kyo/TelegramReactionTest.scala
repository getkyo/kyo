package kyo

import kyo.internal.telegram.WireFieldTest.wire

class TelegramReactionTest extends kyo.test.Test[Any]:

    "each ReactionType is tagged by type, with its own field" in {
        val reactions = Chunk[Telegram.Reaction](
            Telegram.Reaction.Emoji("👍"),
            Telegram.Reaction.CustomEmoji("5368324170671202286"),
            Telegram.Reaction.Paid
        )
        val json = Chunk(
            """{"type":"emoji","emoji":"👍"}""",
            """{"type":"custom_emoji","custom_emoji_id":"5368324170671202286"}""",
            """{"type":"paid"}"""
        )
        assert(reactions.map(wire(_)) == json.map(wire))
        assert(json.map(Json.decode[Telegram.Reaction](_)) == reactions.map(Result.succeed(_)))
    }

    "a reaction type Telegram adds later is Other with its name and the reaction as sent, and encodes back to it" in {
        val json    = """{"type":"star","count":3}"""
        val decoded = Json.decode[Telegram.Reaction](json)
        decoded match
            case Result.Success(other @ Telegram.Reaction.Other(name, _)) =>
                assert(name == "star")
                assert(wire(other: Telegram.Reaction) == wire(json))
            case unexpected => fail(s"expected Other, got $unexpected")
        end match
    }

    "a sendable reaction encodes as the reaction it is, and neither a paid reaction nor Other reads as one" in {
        val sendable: Telegram.Reaction.Sendable = Telegram.Reaction.CustomEmoji("e1")
        assert(wire(sendable)(using Telegram.Reaction.sendableSchema, summon[Frame]) ==
            wire("""{"type":"custom_emoji","custom_emoji_id":"e1"}"""))
        assert(Chunk("""{"type":"star","count":3}""", """{"type":"paid"}""").map(json =>
            Json.decode[Telegram.Reaction.Sendable](json)(using summon[Json], Telegram.Reaction.sendableSchema).isFailure
        ) == Chunk(true, true))
    }

end TelegramReactionTest
