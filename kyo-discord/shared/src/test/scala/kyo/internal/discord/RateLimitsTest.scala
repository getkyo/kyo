package kyo.internal.discord

import kyo.*

class RateLimitsTest extends kyo.test.Test[Any]:

    private val messages = RateLimits.Key("POST", "/channels/{channel.id}/messages", "channels/111")
    private val edits    = RateLimits.Key("PATCH", "/channels/{channel.id}/messages/{message.id}", "channels/111")
    private val other    = RateLimits.Key("POST", "/channels/{channel.id}/messages", "channels/222")
    private val method   = "POST /channels/{channel.id}/messages"

    private def answer(bucket: String, remaining: Int, resetAfter: Duration): RateLimits.Answer =
        RateLimits.Answer(Present(bucket), Present(remaining), Present(resetAfter))

    /** Runs `v` against limits of `globalRate` under virtual time, answering its result and the virtual time it returned at. */
    private def timed[A](globalRate: Int)(v: RateLimits => A < (Async & Abort[DiscordRouteRateLimitException]))(using
        Frame
    ): (Result[DiscordRouteRateLimitException, A], Instant) < (Async & Abort[Any] & Scope) =
        Clock.withTimeControl { _ =>
            RateLimits.init(globalRate).map(limits => Abort.run[DiscordRouteRateLimitException](v(limits)).map(r => Clock.now.map(r -> _)))
        }

    "a route Discord never answered is sent at once" in {
        timed(50)(_.acquire(messages, global = true, 10.seconds, method)).map(result =>
            assert(result == (Result.unit, Instant.Epoch))
        )
    }

    "a bucket's remaining calls go at once, and the next waits for its reset" in {
        Clock.withTimeControl { control =>
            RateLimits.init(50).map { limits =>
                limits.record(messages, answer("b", 2, 3.seconds))
                    .andThen(limits.acquire(messages, global = true, 10.seconds, method))
                    .andThen(limits.acquire(messages, global = true, 10.seconds, method))
                    .andThen(Fiber.initUnscoped(limits.acquire(messages, global = true, 10.seconds, method).andThen(Clock.now)))
                    .map { third =>
                        // Two sleepers: the global limiter's refill timer and the bucket's wait.
                        control.awaitPendingSleepers(2).andThen(control.advance(3.seconds)).andThen(third.get).map(at =>
                            assert(at == Instant.Epoch + 3.seconds)
                        )
                    }
            }
        }
    }

    "a wait past maxWait fails at once without taking a call, naming the wait and the bucket" in {
        timed(50) { limits =>
            limits.record(messages, answer("b", 0, 3.seconds)).andThen(limits.acquire(messages, global = true, 2.seconds, method))
        }.map(result =>
            assert(result == (Result.fail(DiscordRouteRateLimitException(method, Present(3.seconds), Absent, Present("b"))), Instant.Epoch))
        )
    }

    "routes Discord puts in one bucket share its remaining per resource, and another resource has its own" in {
        timed(50) { limits =>
            limits.record(messages, answer("b", 1, 3.seconds))
                .andThen(limits.record(edits, answer("b", 1, 3.seconds)))
                .andThen(limits.record(other, answer("b", 1, 3.seconds)))
                .andThen(limits.acquire(messages, global = true, 10.seconds, method))
                .andThen(limits.acquire(other, global = true, 10.seconds, method))
                .andThen(Abort.run[DiscordRouteRateLimitException](limits.acquire(edits, global = true, 1.second, method)))
        }.map { (result, at) =>
            assert(at == Instant.Epoch)
            val refused: Maybe[String] = result match
                case Result.Success(edit) => edit.failure.flatMap(_.bucket)
                case _                    => Absent
            assert(refused == Present("b"))
        }
    }

    "a reset that passed restores the bucket" in {
        Clock.withTimeControl { control =>
            RateLimits.init(50).map { limits =>
                limits.record(messages, answer("b", 0, 3.seconds))
                    .andThen(control.advance(3.seconds))
                    .andThen(Abort.run[DiscordRouteRateLimitException](limits.acquire(messages, global = true, 1.second, method)))
                    .map(result => assert(result == Result.unit))
            }
        }
    }

    "the global limiter admits globalRateLimit calls a second, and a call on an interaction route bypasses it" in {
        Clock.withTimeControl { control =>
            RateLimits.init(2).map { limits =>
                def call(global: Boolean) = limits.acquire(messages, global, 10.seconds, method)
                call(true).andThen(call(true)).andThen(call(false)).andThen(Clock.now).map { bypassed =>
                    Fiber.initUnscoped(call(true).andThen(Clock.now)).map { third =>
                        // The third call is queued on the limiter before the clock moves, so its time is the refill's.
                        val queued = Loop.foreach(limits.globalWaiters.map(n => if n == 1 then Loop.done(()) else Loop.continue))
                        queued.andThen(control.advance(1.second)).andThen(third.get).map { at =>
                            assert((bypassed, at) == (Instant.Epoch, Instant.Epoch + 1.second))
                        }
                    }
                }
            }
        }
    }

    "an answer's rate-limit headers are its bucket, remaining calls and reset, and a header that does not parse is absent" in {
        val headers = HttpHeaders.empty.add("X-RateLimit-Bucket", "abcd").add("X-RateLimit-Remaining", "4")
            .add("X-RateLimit-Reset-After", "1.25")
        assert(RateLimits.Answer.of(headers) == answer("abcd", 4, 1250.millis))
        assert(RateLimits.Answer.of(HttpHeaders.empty.add("X-RateLimit-Remaining", "x").add("X-RateLimit-Reset-After", "-1")) ==
            RateLimits.Answer(Absent, Absent, Absent))
    }

    "the top-level resource is the channel, guild, webhook with its token, or interaction with its token the path starts with" in {
        assert(Chunk(
            "/channels/111/messages/3",
            "/guilds/5/members/42",
            "/webhooks/900/tok/messages/@original",
            "/interactions/901/tok/callback",
            "/users/@me/channels",
            "/gateway/bot"
        ).map(RateLimits.resourceOf) == Chunk("channels/111", "guilds/5", "webhooks/900/tok", "interactions/901/tok", "", ""))
    }

end RateLimitsTest
