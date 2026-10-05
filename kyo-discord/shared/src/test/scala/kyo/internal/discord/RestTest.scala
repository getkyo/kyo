package kyo.internal.discord

import kyo.*

class RestTest extends kyo.test.Test[Any]:

    import DiscordInvalidFormBodyException.FieldError

    private val tooMany = HttpStatus(429)
    private val gateway = HttpStatus(502)

    /** Runs `retryWait` on its own fiber and answers whether it retries and the virtual time it returned at, advancing time by `steps`
      * once the wait is registered.
      */
    private def waited(schedule: Schedule, maxDelay: Duration, status: HttpStatus, retryAfter: Maybe[Duration], steps: Duration*)(using
        Frame
    ): (Boolean, Instant) < (Async & Abort[Any]) =
        Clock.withTimeControl { control =>
            Fiber.initUnscoped(Rest.retryWait(Present(schedule), maxDelay, status, retryAfter).map(next =>
                Clock.now.map(now => (next.nonEmpty, now))
            )).map { fiber =>
                control.awaitPendingSleepers(1)
                    .andThen(Kyo.foreachDiscard(steps)(control.advance))
                    .andThen(fiber.get)
            }
        }

    "a 429 waits the wait Discord named when it is longer than the schedule's delay" in {
        waited(Schedule.fixed(1.second), 60.seconds, tooMany, Present(2500.millis), 1.second, 1500.millis).map { result =>
            assert(result == (true, Instant.Epoch + 2500.millis))
        }
    }

    "a 429 waits the schedule's delay when it is longer than the wait Discord named" in {
        waited(Schedule.fixed(4.seconds), 60.seconds, tooMany, Present(1.second), 1.second, 3.seconds).map { result =>
            assert(result == (true, Instant.Epoch + 4.seconds))
        }
    }

    "a 502 waits the schedule's delay" in {
        waited(Schedule.fixed(3.seconds), 60.seconds, gateway, Absent, 1.second, 2.seconds).map { result =>
            assert(result == (true, Instant.Epoch + 3.seconds))
        }
    }

    "a schedule delay above retryMaxDelay waits retryMaxDelay, and a named wait equal to it is waited" in {
        for
            capped <- waited(Schedule.fixed(90.seconds), 60.seconds, gateway, Absent, 59.seconds, 1.second)
            equal  <- waited(Schedule.fixed(1.second), 60.seconds, tooMany, Present(60.seconds), 59.seconds, 1.second)
        yield assert((capped, equal) == ((true, Instant.Epoch + 60.seconds), (true, Instant.Epoch + 60.seconds)))
    }

    "no retry and no wait for a 429 naming no wait or a wait above retryMaxDelay, another status, a done schedule, or no schedule" in {
        Clock.withTimeControl { _ =>
            val schedule = Present(Schedule.fixed(1.second))
            Kyo.collectAll(Chunk(
                Rest.retryWait(schedule, 60.seconds, tooMany, Absent),
                Rest.retryWait(schedule, 60.seconds, tooMany, Present(61.seconds)),
                Rest.retryWait(schedule, 60.seconds, HttpStatus(500), Absent),
                Rest.retryWait(schedule, 60.seconds, HttpStatus(503), Present(1.second)),
                Rest.retryWait(schedule, 60.seconds, HttpStatus(400), Absent),
                Rest.retryWait(Present(Schedule.fixed(1.second).take(0)), 60.seconds, gateway, Absent),
                Rest.retryWait(Absent, 60.seconds, gateway, Absent)
            )).map(results => Clock.now.map(now => assert((results.map(_.isEmpty), now) == (Chunk.fill(7)(true), Instant.Epoch))))
        }
    }

    "a 429's retry_after is float seconds, kept to the microsecond; a negative one names no wait" in {
        assert(Chunk(1.234, 0.0, 2.000001, -1.0).map(Rest.retryAfterSeconds) ==
            Chunk(Present(1234.millis), Present(Duration.Zero), Present(2000001.micros), Absent))
    }

    "Retry-After is one to nine ASCII digits with optional white space, and nothing else" in {
        assert(Chunk("5", " 7 ", "123456789", "1234567890", "1.5", "", "Wed, 21 Oct 2015 07:28:00 GMT", "-1").map(Rest.parseRetryAfter) ==
            Chunk(Present(5.seconds), Present(7.seconds), Present(123456789.seconds), Absent, Absent, Absent, Absent, Absent))
    }

    "the errors tree flattens to one field error per refusal, with its path from the body's root" in {
        val errors = Json.decode[Structure.Value](
            """{"activities":{"0":{"platform":{"_errors":[{"code":"BASE_TYPE_CHOICES","message":"Value must be one of ('desktop', 'android', 'ios')."}]},""" +
                """"type":{"_errors":[{"code":"BASE_TYPE_CHOICES","message":"Value must be one of (0, 1, 2, 3, 4, 5)."}]}}},""" +
                """"_errors":[{"code":"TOP","message":"top"}]}"""
        ).getOrThrow
        assert(Rest.fieldErrors(errors, identity) == Chunk(
            FieldError(Chunk("activities", "0", "platform"), "BASE_TYPE_CHOICES", "Value must be one of ('desktop', 'android', 'ios')."),
            FieldError(Chunk("activities", "0", "type"), "BASE_TYPE_CHOICES", "Value must be one of (0, 1, 2, 3, 4, 5)."),
            FieldError(Chunk.empty, "TOP", "top")
        ))
    }

    "an errors tree of another shape yields no field error, and a refusal's text is redacted" in {
        val shapes = Chunk("""[1,2]""", """{"a":{"_errors":"x"}}""", """{"a":{"_errors":[{"code":1}]}}""").map(s =>
            Rest.fieldErrors(Json.decode[Structure.Value](s).getOrThrow, identity)
        )
        assert(shapes == Chunk.fill(3)(Chunk.empty[FieldError]))
        val leaked = Json.decode[Structure.Value]("""{"content":{"_errors":[{"code":"X","message":"got SECRET"}]}}""").getOrThrow
        assert(Rest.fieldErrors(leaked, Rest.redact(Chunk("SECRET"), _)) == Chunk(FieldError(Chunk("content"), "X", "got <redacted>")))
    }

    "redact replaces each secret, and a secret of nothing replaces nothing" in {
        assert(Rest.redact(Chunk("abc", "xyz", ""), "abc then xyz then abc") == "<redacted> then <redacted> then <redacted>")
    }

    "a message with files names each under attachments, inside data for an interaction's answer; without files it is plain JSON" in {
        val file   = Discord.File.init("a.txt", Span.from("A".getBytes("UTF-8")), description = Present("alt")).getOrThrow
        val bare   = Discord.File.init("b.txt", Span.from("B".getBytes("UTF-8"))).getOrThrow
        val create = Discord.Message.Create.init(content = Present("hi"), files = Chunk(file, bare)).getOrThrow
        val plain  = Discord.Message.Create.init(content = Present("hi")).getOrThrow
        val names  = """"attachments":[{"id":0,"filename":"a.txt","description":"alt"},{"id":1,"filename":"b.txt"}]"""
        assert(Rest.messageBody(plain, plain.files) == Rest.Body.Json("""{"content":"hi","tts":false}"""))
        assert(Rest.messageBody(create, create.files) == Rest.Body.Form(s"""{"content":"hi","tts":false,$names}""", Chunk(file, bare)))
        val answer: Discord.InteractionResponse = Discord.InteractionResponse.Message(create)
        assert(Rest.messageBody(answer, create.files, inData = true) ==
            Rest.Body.Form(s"""{"type":4,"data":{"content":"hi","tts":false,$names}}""", Chunk(file, bare)))
    }

    "a route's path joins its segments, and an empty segment, which would name another route, is a module bug" in {
        assert(Rest.path("channels", "1", "messages") == "/channels/1/messages")
        assert(Result.catching[Throwable](Rest.path(
            "channels",
            "",
            "messages"
        )).failure.exists(_.getMessage.contains("empty path segment")))
    }

    "a path under the base URL joins to its path, with or without one" in {
        val root = HttpUrl(Present("https"), "discord.com", 443, "/", Absent)
        val api  = HttpUrl(Present("https"), "discord.com", 443, "/api/v10", Absent)
        assert((Rest.under(root, "/channels/1").path, Rest.under(api, "/channels/1").path) == ("/channels/1", "/api/v10/channels/1"))
    }

end RestTest
