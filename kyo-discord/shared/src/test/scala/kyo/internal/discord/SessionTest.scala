package kyo.internal.discord

import kyo.*

class SessionTest extends kyo.test.Test[Any]:

    "the first reconnection after a healthy connection goes at once, and each further one waits the backoff's next step" in {
        val first  = Session.Backoff.start(Schedule.fixed(3.seconds).take(2)).next(Instant.Epoch)
        val second = first.flatMap(_._2.next(Instant.Epoch))
        val third  = second.flatMap(_._2.next(Instant.Epoch))
        val fourth = third.flatMap(_._2.next(Instant.Epoch))
        assert(Chunk(first, second, third, fourth).map(_.map(_._1)) ==
            Chunk(Present(Duration.Zero), Present(3.seconds), Present(3.seconds), Absent))
        // A connection that reached Ready or Resumed restarts it: the next reconnection goes at once again.
        assert(third.map(_._2.reset.next(Instant.Epoch).map(_._1)) == Present(Present(Duration.Zero)))
    }

    "identifies on one client are spaced by 5 seconds over max_concurrency" in {
        val now = Instant.Epoch + 10.seconds
        assert(Chunk(
            Session.identifyWait(Absent, now, 1),
            Session.identifyWait(Present(now - 2.seconds), now, 1),
            Session.identifyWait(Present(now - 2.seconds), now, 2),
            Session.identifyWait(Present(now - 9.seconds), now, 1)
        ) == Chunk(Duration.Zero, 3.seconds, 500.millis, Duration.Zero))
    }

    "a Gateway URL is admitted only as wss with a host, and gets the version and encoding the module speaks" in {
        assert(Session.gatewayUrl("wss://gateway.discord.gg").map(_.full) == Present("wss://gateway.discord.gg/?v=10&encoding=json"))
        assert(Session.gatewayUrl("WSS://gateway-us-east1-b.discord.gg/?v=9").map(u => (u.host, u.rawQuery)) ==
            Present(("gateway-us-east1-b.discord.gg", Present("v=10&encoding=json"))))
        assert(Chunk("ws://gateway.discord.gg", "https://gateway.discord.gg", "wss://", "not a url", "").map(Session.gatewayUrl) ==
            Chunk.fill(5)(Absent))
    }

    "each close code is its outcome: a leaf, a fresh identify, or a resume" in {
        import Gateway.Outcome
        val intents                   = Discord.Intents.Guilds
        val shard                     = Present(Discord.Shard.init(1, 2).getOrThrow)
        def outcome(code: Maybe[Int]) = Gateway.classify(code, "why", intents, shard, Chunk.empty)
        assert(Chunk(4004, 4010, 4011, 4012, 4013, 4014).map(c => outcome(Present(c))) == Chunk(
            Outcome.Fail(DiscordAuthenticationFailedException()),
            Outcome.Fail(DiscordInvalidShardException(shard.get)),
            Outcome.Fail(DiscordShardingRequiredException()),
            Outcome.Fail(DiscordGatewayClosedException(4012, "why")),
            Outcome.Fail(DiscordInvalidIntentsException(intents)),
            Outcome.Fail(DiscordDisallowedIntentsException(intents))
        ))
        assert(Chunk(4007, 4009).map(c => outcome(Present(c))) == Chunk.fill(2)(Outcome.Identify))
        assert(Chunk(
            Present(4000),
            Present(4001),
            Present(4002),
            Present(4003),
            Present(4005),
            Present(4008),
            Present(1000),
            Present(1001),
            Absent
        )
            .map(outcome) == Chunk.fill(9)(Outcome.Resume))
    }

    "a close reason is kept with the bot token redacted" in {
        assert(Gateway.classify(Present(4012), "bad token abc.def", Discord.Intents.Guilds, Absent, Chunk("abc.def")) ==
            Gateway.Outcome.Fail(DiscordGatewayClosedException(4012, "bad token <redacted>")))
    }

end SessionTest
