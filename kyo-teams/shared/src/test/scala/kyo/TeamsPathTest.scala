package kyo

class TeamsPathTest extends kyo.test.Test[Any]:

    import TeamsInvalidPathException.Problem

    "a path keeps its segments as given, whatever characters they hold" in {
        val path = Teams.Path.init("v3", "conversations", "19:abc@thread.skype", "pagedmembers").getOrThrow
        assert(path.segments == Chunk("v3", "conversations", "19:abc@thread.skype", "pagedmembers"))
        assert(Teams.Path.init("a/b?c#d").map(_.segments) == Result.succeed(Chunk("a/b?c#d")))
    }

    "init refuses no segment, an empty segment and a dot segment, naming the first" in {
        assert(Chunk(
            Teams.Path.init().failure.map(_.problem),
            Teams.Path.init("v3", "", "x").failure.map(_.problem),
            Teams.Path.init("v3", "..").failure.map(_.problem),
            Teams.Path.init(".", "x").failure.map(_.problem),
            Teams.Path.init("v3", "", "..").failure.map(_.problem)
        ) == Chunk(
            Present(Problem.Empty),
            Present(Problem.EmptySegment(1)),
            Present(Problem.DotSegment(1)),
            Present(Problem.DotSegment(0)),
            Present(Problem.EmptySegment(1))
        ))
        assert(Teams.Path.init("x", "..").failure.map(_.getMessage).exists(_.contains("segment 1 is . or ..")))
    }

    "a segment of three dots is a name, not a dot segment" in {
        assert(Teams.Path.init("...").map(_.segments) == Result.succeed(Chunk("...")))
    }

end TeamsPathTest
