package kyo.internal.teams

import kyo.*

class ConnectorTest extends kyo.test.Test[Any]:

    private val unavailable = HttpStatus(503)

    /** Runs `retry` on its own fiber and answers its result with the virtual time it returned at, advancing time by `steps` once the
      * wait is registered.
      */
    private def waited(schedule: Schedule, maxDelay: Duration, retryAfter: Maybe[Duration], steps: Duration*)(using
        Frame
    ): (Boolean, Instant) < (Async & Abort[Any]) =
        Clock.withTimeControl { control =>
            Fiber.initUnscoped(Connector.retry(Present(schedule), maxDelay, unavailable, retryAfter).map(next =>
                Clock.now.map(now => (next.nonEmpty, now))
            )).map { fiber =>
                control.awaitPendingSleepers(1)
                    .andThen(Kyo.foreachDiscard(steps)(control.advance))
                    .andThen(fiber.get)
            }
        }

    "a retried status waits the schedule's delay, then answers the schedule's next step" in {
        waited(Schedule.fixed(3.seconds).take(2), 60.seconds, Absent, 1.second, 2.seconds).map { result =>
            assert(result == (true, Instant.Epoch + 3.seconds))
        }
    }

    "a Retry-After longer than the schedule's delay is waited instead" in {
        waited(Schedule.fixed(1.second), 60.seconds, Present(5.seconds), 1.second, 4.seconds).map { result =>
            assert(result == (true, Instant.Epoch + 5.seconds))
        }
    }

    "a Retry-After shorter than the schedule's delay waits the delay" in {
        waited(Schedule.fixed(4.seconds), 60.seconds, Present(1.second), 1.second, 3.seconds).map { result =>
            assert(result == (true, Instant.Epoch + 4.seconds))
        }
    }

    "a schedule delay above retryMaxDelay waits retryMaxDelay" in {
        waited(Schedule.fixed(90.seconds), 60.seconds, Absent, 59.seconds, 1.second).map { result =>
            assert(result == (true, Instant.Epoch + 60.seconds))
        }
    }

    "a Retry-After equal to retryMaxDelay is waited" in {
        waited(Schedule.fixed(1.second), 60.seconds, Present(60.seconds), 59.seconds, 1.second).map { result =>
            assert(result == (true, Instant.Epoch + 60.seconds))
        }
    }

    "no retry, and no wait, for a Retry-After above retryMaxDelay, a status Microsoft does not say to retry, an exhausted schedule, or no schedule" in {
        Clock.withTimeControl { _ =>
            val schedule = Present(Schedule.fixed(1.second))
            Kyo.foreach(Chunk(
                Connector.retry(schedule, 60.seconds, unavailable, Present(61.seconds)),
                Connector.retry(schedule, 60.seconds, HttpStatus(500), Absent),
                Connector.retry(schedule, 60.seconds, HttpStatus(400), Absent),
                Connector.retry(Present(Schedule.fixed(1.second).take(0)), 60.seconds, unavailable, Absent),
                Connector.retry(Absent, 60.seconds, unavailable, Absent)
            ))(identity).map { results =>
                Clock.now.map { now =>
                    assert(results.map(_.nonEmpty) == Chunk.fill(5)(false))
                    assert(now == Instant.Epoch)
                }
            }
        }
    }

    "every status Microsoft says to retry is retried" in {
        Clock.withTimeControl { _ =>
            Kyo.foreach(Chunk(HttpStatus(412), HttpStatus(429), HttpStatus(502), HttpStatus(503), HttpStatus(504)))(status =>
                Connector.retry(Present(Schedule.fixed(Duration.Zero)), 60.seconds, status, Absent).map(_.nonEmpty)
            ).map(retried => assert(retried == Chunk.fill(5)(true)))
        }
    }

    "Retry-After accepts one to nine ASCII digits with optional white space and nothing else" in {
        assert(
            Chunk(" 12\t", "0", "999999999", "1000000000", "", "1.5", "-1", "١", "Wed, 21 Oct 2015 07:28:00 GMT")
                .map(Connector.parseRetryAfter) ==
                Chunk(
                    Present(12.seconds),
                    Present(Duration.Zero),
                    Present(999999999.seconds),
                    Absent,
                    Absent,
                    Absent,
                    Absent,
                    Absent,
                    Absent
                )
        )
    }

    "redact replaces each secret raw and percent-encoded, and leaves the rest" in {
        val secret = Chunk("a b", "c/d").mkString(":")
        assert(Connector.redact(Chunk(secret, ""), s"x $secret y ${ServiceUrls.encode(secret)} z") == "x <redacted> y <redacted> z")
    }

    "a response bound is held as given and narrowed only at the request config: zero to 1 byte, past Int.MaxValue to Int.MaxValue" in {
        val appId  = Teams.AppId.init("00001111-aaaa-2222-bbbb-3333cccc4444").getOrThrow
        val secret = TeamsConfig.Credential.Secret(Teams.ClientSecret.init(Chunk("conTEST", "~secret.", "Part_9").mkString).getOrThrow)
        val sizes  = Chunk(0.bytes, 1.bytes, 4.mib, Int.MaxValue.bytes, (Int.MaxValue.toLong + 1).bytes)
        val held   = sizes.map(size => TeamsConfig.init(appId, secret, maxResponseLength = size, keysMaxResponseLength = size).getOrThrow)
        assert(held.map(c => (c.maxResponseLength, c.keysMaxResponseLength)) == sizes.map(size => (size, size)))
        assert(held.map(c => Connector.requestConfig(c, c.requestTimeout).maxResponseLength) ==
            Chunk(1, 1, 4 * 1024 * 1024, Int.MaxValue, Int.MaxValue))
    }

end ConnectorTest
