package kyo

class DiscordPathTest extends kyo.test.Test[Any]:

    import DiscordInvalidPathException.Problem

    private def rejected(text: String): Maybe[DiscordInvalidPathException] = Discord.Path.init(text).failure

    "relative segments of A-Z a-z 0-9 . _ - @ joined by single slashes are accepted" in {
        val paths = Chunk("guilds/123/roles", "users/@me", "webhooks/1/abc-DEF_9.x/messages/@original", "gateway", "a.b..c")
        assert(paths.map(p => Discord.Path.init(p).map(_.value)) == paths.map(Result.succeed(_)))
    }

    "init refuses what could move the request, at its position" - {

        "an empty path" in {
            assert(rejected("") == Present(DiscordInvalidPathException(Problem.Empty)))
            assert(rejected("").exists(_.getMessage.contains("Discord.Path is not usable: it is empty.")))
        }

        "a leading, doubled or trailing slash" in {
            assert(Chunk("/guilds", "guilds//roles", "guilds/").map(rejected) == Chunk(
                Present(DiscordInvalidPathException(Problem.Character(0))),
                Present(DiscordInvalidPathException(Problem.Character(7))),
                Present(DiscordInvalidPathException(Problem.Character(6)))
            ))
        }

        "a query, a fragment, an escape, a space, a colon or a non-ASCII character" in {
            val cases = Chunk("guilds?x=1" -> 6, "guilds#x" -> 6, "guilds/%2E%2E" -> 7, "gui lds" -> 3, "http://x" -> 4, "guildé" -> 5)
            assert(cases.map((text, _) => rejected(text)) ==
                cases.map((_, at) => Present(DiscordInvalidPathException(Problem.Character(at)))))
            assert(rejected("guilds?x=1").exists(_.getMessage.contains(
                "Discord.Path is not usable: the character at position 6 is not one of A-Z a-z 0-9 . _ - @ or a single inner /."
            )))
        }

        "a dot segment, at the segment's position" in {
            assert(Chunk("..", ".", "guilds/../users", "guilds/.", "a/b/..").map(rejected) == Chunk(
                Present(DiscordInvalidPathException(Problem.DotSegment(0))),
                Present(DiscordInvalidPathException(Problem.DotSegment(0))),
                Present(DiscordInvalidPathException(Problem.DotSegment(7))),
                Present(DiscordInvalidPathException(Problem.DotSegment(7))),
                Present(DiscordInvalidPathException(Problem.DotSegment(4)))
            ))
            assert(rejected("..").exists(_.getMessage.contains("Discord.Path is not usable: the segment at position 0 is . or ...")))
        }
    }

end DiscordPathTest
