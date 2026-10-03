package kyo.internal

import kyo.*
import kyo.schema.*

class TimeTextTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    private def utc(text: String): java.time.Instant = java.time.Instant.parse(text)

    "instant" - {

        "reads Z and numeric offsets" in {
            assert(TimeText.instant("2016-04-30T11:18:25Z") == Result.succeed(utc("2016-04-30T11:18:25Z")))
            assert(TimeText.instant("2016-04-30T11:18:25+00:00") == Result.succeed(utc("2016-04-30T11:18:25Z")))
            assert(TimeText.instant("2016-04-30T11:18:25+05:30") == Result.succeed(utc("2016-04-30T05:48:25Z")))
            assert(TimeText.instant("2016-04-30T11:18:25-08:00") == Result.succeed(utc("2016-04-30T19:18:25Z")))
        }

        "reads a fraction of 1 to 9 digits" in {
            assert(TimeText.instant("2016-04-30T11:18:25.796000+00:00") == Result.succeed(utc("2016-04-30T11:18:25.796Z")))
            assert(TimeText.instant("2016-04-30T11:18:25.1Z") == Result.succeed(utc("2016-04-30T11:18:25.100Z")))
            assert(TimeText.instant("2016-04-30T11:18:25.123456789Z") == Result.succeed(utc("2016-04-30T11:18:25.123456789Z")))
        }

        "an offset crosses the date" in {
            assert(TimeText.instant("2016-12-31T23:30:00-01:00") == Result.succeed(utc("2017-01-01T00:30:00Z")))
            assert(TimeText.instant("2016-03-01T00:30:00+01:00") == Result.succeed(utc("2016-02-29T23:30:00Z")))
        }

        "reads lower-case t and z" in {
            assert(TimeText.instant("2016-04-30t11:18:25z") == Result.succeed(utc("2016-04-30T11:18:25Z")))
        }

        "a leap second reads as the second before it" in {
            assert(TimeText.instant("2016-12-31T23:59:60Z") == Result.succeed(utc("2016-12-31T23:59:59Z")))
        }

        "reads back what Instant.toString writes outside years 0000 to 9999" in {
            val far    = java.time.Instant.ofEpochSecond(253402300800L)
            val before = java.time.Instant.ofEpochSecond(-62198755200L)
            assert(TimeText.instant(far.toString) == Result.succeed(far))
            assert(TimeText.instant(before.toString) == Result.succeed(before))
        }

        "the epoch and a date before it" in {
            assert(TimeText.instant("1970-01-01T00:00:00Z") == Result.succeed(java.time.Instant.EPOCH))
            assert(TimeText.instant("1969-12-31T23:59:59.5Z") == Result.succeed(java.time.Instant.ofEpochSecond(-1, 500000000)))
        }

        "rejects what is not an RFC 3339 date-time" in {
            Chunk(
                "yesterday",
                "",
                "2016-04-30T11:18:25",
                "2016-04-30 11:18:25Z",
                "2016-04-30T11:18Z",
                "2016-04-30T11:18:25+0530",
                "2016-04-30T11:18:25+05",
                "2016-04-30T11:18:25.Z",
                "2016-04-30T11:18:25.1234567890Z",
                "2016-02-30T00:00:00Z",
                "2015-02-29T00:00:00Z",
                "2016-13-01T00:00:00Z",
                "2016-04-30T24:00:00Z",
                "2016-04-30T11:60:00Z",
                "2016-04-30T11:18:60Z",
                "2016-04-30T11:18:25+24:00",
                "16-04-30T11:18:25Z",
                "+16-04-30T11:18:25Z",
                "2016-04-30T11:18:25Zjunk"
            ).foreach { text =>
                assert(TimeText.instant(text).isFailure, text)
            }
            succeed
        }
    }

    "duration" - {

        "reads an ISO 8601 duration" in {
            assert(TimeText.duration("PT1.5S") == Result.succeed(java.time.Duration.ofMillis(1500)))
            assert(TimeText.duration("P2DT3H") == Result.succeed(java.time.Duration.ofHours(51)))
        }

        "rejects other text" in {
            assert(TimeText.duration("soon").isFailure)
        }
    }

    "every text codec reads an offset instant" in {
        val expected = utc("2016-04-30T05:48:25.796Z")
        assert(Json.decode[java.time.Instant]("\"2016-04-30T11:18:25.796+05:30\"") == Result.succeed(expected))
        assert(Schema[java.time.Instant].decodeString[Yaml]("\"2016-04-30T11:18:25.796+05:30\"\n") == Result.succeed(expected))
        assert(Schema[java.time.Instant].decodeString[Ion]("\"2016-04-30T11:18:25.796+05:30\"") == Result.succeed(expected))
        assert(Json.decode[TTTRecord]("""{"at":"2016-04-30T11:18:25.796000+00:00"}""") ==
            Result.succeed(TTTRecord(utc("2016-04-30T11:18:25.796Z"))))
    }

    "a malformed instant decoded directly is a ParseException" in {
        Json.decode[TTTRecord]("""{"at":"yesterday"}""") match
            case Result.Failure(_: ParseException) => succeed
            case other                             => fail(s"expected ParseException, got $other")
    }

    "through a catch-all sum's reader" - {

        "an offset instant parses" in {
            assert(Json.decode[TTTEvent]("""{"type":"TTTStarted","at":"2016-04-30T11:18:25-08:00"}""") ==
                Result.succeed(TTTStarted(utc("2016-04-30T19:18:25Z"))))
        }

        "a malformed instant is a decode failure naming the field" in {
            Json.decode[TTTEvent]("""{"type":"TTTStarted","at":"yesterday"}""") match
                case Result.Failure(e: TypeMismatchException) =>
                    assert(e.path == Seq("at"), e.getMessage)
                    assert(e.getMessage.contains("yesterday"), e.getMessage)
                case other => fail(s"expected TypeMismatchException at 'at', got $other")
        }

        "a malformed duration is a decode failure naming the field" in {
            Json.decode[TTTEvent]("""{"type":"TTTWaited","length":"soon"}""") match
                case Result.Failure(e: TypeMismatchException) =>
                    assert(e.path == Seq("length"), e.getMessage)
                    assert(e.getMessage.contains("soon"), e.getMessage)
                case other => fail(s"expected TypeMismatchException at 'length', got $other")
        }

        "a duration parses" in {
            assert(Json.decode[TTTEvent]("""{"type":"TTTWaited","length":"PT1.5S"}""") ==
                Result.succeed(TTTWaited(java.time.Duration.ofMillis(1500))))
        }
    }

end TimeTextTest

case class TTTRecord(at: java.time.Instant) derives CanEqual, Schema

@discriminator("type") sealed trait TTTEvent derives CanEqual, Schema
case class TTTStarted(at: java.time.Instant)                       extends TTTEvent derives CanEqual
case class TTTWaited(length: java.time.Duration)                   extends TTTEvent derives CanEqual
@catchAll() case class TTTOther(tag: String, raw: Structure.Value) extends TTTEvent derives CanEqual
