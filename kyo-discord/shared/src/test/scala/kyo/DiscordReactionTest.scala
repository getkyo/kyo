package kyo

class DiscordReactionTest extends kyo.test.Test[Any]:

    import Discord.*
    import DiscordInvalidReactionException.Problem

    "a unicode emoji's path segment is its name, percent-encoded as UTF-8" in {
        assert(Reaction.unicode("🔥").map(_.segment) == Result.succeed("%F0%9F%94%A5"))
        assert(Reaction.unicode("a/b?c").map(_.segment) == Result.succeed("a%2Fb%3Fc"))
    }

    "a custom emoji's path segment is name:id, percent-encoded" in {
        assert(Reaction.custom(EmojiId(1234567890123L), "party_blob").map(_.segment) == Result.succeed("party_blob%3A1234567890123"))
    }

    "init takes an emoji with a name, as the conveniences build it" in {
        assert(Reaction.init(Emoji.unicode("👍")) == Reaction.unicode("👍"))
        assert(Reaction.init(Emoji.custom(EmojiId(7L), "kyo", animated = true)) == Reaction.custom(EmojiId(7L), "kyo", animated = true))
        assert(Reaction.init(Emoji.custom(EmojiId(7L), "kyo")).map(_.emoji) == Result.succeed(Emoji.custom(EmojiId(7L), "kyo")))
    }

    "an emoji with no name or an empty one is refused, since its path segment would be empty" in {
        val refused = Chunk(
            Reaction.init(Emoji(id = Present(EmojiId(7L)))),
            Reaction.init(Emoji()),
            Reaction.init(Emoji(name = Present(""))),
            Reaction.unicode(""),
            Reaction.custom(EmojiId(7L), "")
        ).map(_.failure.map(_.problem))
        assert(refused == Chunk.fill(5)(Present(Problem.NoName)))
        assert(Reaction.unicode("").failure.exists(_.getMessage.contains("Discord reaction is not usable: the emoji has no name")))
    }

end DiscordReactionTest
