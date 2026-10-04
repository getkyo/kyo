package kyo

class DiscordThreadTest extends kyo.test.Test[Any]:

    import Discord.*
    import DiscordInvalidThreadException.Problem

    "a thread writes Discord's start-thread JSON and round-trips" in {
        val thread =
            Thread.Start.init("Planning", Present(1440), Present(Channel.Type.PrivateThread), Present(false), Present(30)).getOrThrow
        assert(Json.encode(thread) ==
            """{"name":"Planning","auto_archive_duration":1440,"type":12,"invitable":false,"rate_limit_per_user":30}""")
        assert(Json.decode[Thread.Start](Json.encode(thread)) == Result.succeed(thread))
        assert(Json.encode(Thread.Start.init("Planning").getOrThrow) == """{"name":"Planning"}""")
    }

    "init refuses what Discord would" in {
        assert(Chunk(
            Thread.Start.init(""),
            Thread.Start.init("n" * 101),
            Thread.Start.init("t", autoArchiveDuration = Present(30)),
            Thread.Start.init("t", rateLimitPerUser = Present(21601)),
            Thread.Start.init("t", `type` = Present(Channel.Type.GuildText))
        ).map(_.failure.map(_.problem)) == Chunk(
            Present(Problem.NameLength(0)),
            Present(Problem.NameLength(101)),
            Present(Problem.AutoArchiveDuration(30)),
            Present(Problem.RateLimitPerUser(21601)),
            Present(Problem.ThreadType(0))
        ))
        assert(Chunk(60, 1440, 4320, 10080).forall(d => Thread.Start.init("t", autoArchiveDuration = Present(d)).isSuccess))
    }

end DiscordThreadTest
