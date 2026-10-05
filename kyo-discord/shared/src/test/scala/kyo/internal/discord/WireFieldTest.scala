package kyo.internal.discord

import kyo.*

class WireFieldTest extends kyo.test.Test[Any]:

    "UnixSeconds reads and writes an integer count of seconds" in {
        val value = WFSeconds(Instant.of(1462015105L.seconds, Duration.Zero))
        assert(Json.encode(value) == """{"at":1462015105}""")
        assert(Json.decode[WFSeconds]("""{"at":1462015105}""") == Result.succeed(value))
    }

    "Millis reads and writes an integer count of milliseconds" in {
        assert(Json.encode(WFMillis(4.hours)) == """{"after":14400000}""")
        assert(Json.decode[WFMillis]("""{"after":14400000}""") == Result.succeed(WFMillis(4.hours)))
    }

    "MaybeSnowflake reads a decimal string as unsigned, absent and null as Absent" in {
        assert(Json.decode[WFTarget]("""{"id":"18446744073709551615"}""") == Result.succeed(WFTarget(Present(-1L))))
        assert(Json.decode[WFTarget]("""{"id":null}""") == Result.succeed(WFTarget(Absent)))
        assert(Json.decode[WFTarget]("{}") == Result.succeed(WFTarget(Absent)))
        assert(Json.encode(WFTarget(Present(-1L))) == """{"id":"18446744073709551615"}""")
        assert(Json.decode[WFTarget]("""{"id":"12a"}""").isFailure)
    }

    "Url reads a URL and refuses text that is not one" in {
        assert(Json.decode[WFLink]("""{"url":"https://cdn.discordapp.com/a.png"}""").map(_.url.full) ==
            Result.succeed("https://cdn.discordapp.com/a.png"))
        assert(Json.decode[WFLink]("""{"url":"://x"}""").isFailure)
    }

end WireFieldTest

case class WFSeconds(@kyo.schema.transform(WireField.UnixSeconds) at: Instant) derives CanEqual, Schema
case class WFMillis(@kyo.schema.transform(WireField.Millis) after: Duration) derives CanEqual, Schema
case class WFTarget(@kyo.schema.transform(WireField.MaybeSnowflake) id: Maybe[Long] = Absent) derives CanEqual, Schema
case class WFLink(@kyo.schema.transform(WireField.Url) url: HttpUrl) derives Schema
