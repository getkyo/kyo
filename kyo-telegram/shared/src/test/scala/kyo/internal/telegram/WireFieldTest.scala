package kyo.internal.telegram

import kyo.*
import kyo.schema.*

class WireFieldTest extends kyo.test.Test[Any]:
    import WireFieldTest.*

    "Unix seconds are a duration, a date and an optional date, both ways" in {
        val json = """{"duration":90,"date":1700000000,"edit_date":1700000060}"""
        val full = Times(90.seconds, at(1700000000), Present(at(1700000060)))
        assert(Json.decode[Times](json) == Result.succeed(full))
        assert(wire(full) == wire(json))
        assert(Json.decode[Times]("""{"duration":0,"date":0}""") == Result.succeed(Times(Duration.Zero, at(0), Absent)))
        assert(wire(Times(Duration.Zero, at(0), Absent)) == wire("""{"duration":0,"date":0}"""))
    }

    "a file size is its bytes, and absent when Telegram omits it" in {
        val big = Sized(Present(ByteSize.fromBytes(2147483648L)))
        assert(Json.decode[Sized]("""{"file_size":2147483648}""") == Result.succeed(big))
        assert(wire(big) == wire("""{"file_size":2147483648}"""))
        assert(Json.decode[Sized]("""{}""") == Result.succeed(Sized(Absent)))
        assert(wire(Sized(Absent)) == wire("""{}"""))
    }

    "a negative file size fails the decode instead of claiming an empty file" in {
        val failure = Json.decode[Sized]("""{"file_size":-1}""").failure
        assert(failure.map(_.getClass.getSimpleName) == Present("ConstructorRejectedException"))
        assert(failure.exists(_.getMessage.contains("at file_size: a file size of -1 bytes")))
    }

    "a URL is its text, and text HttpUrl.parse refuses fails the decode at its field with the parser's reason and the decode's frame" in {
        val url = HttpUrl.parse("https://example.com/hooks/x").getOrThrow
        assert(Json.decode[Linked]("""{"url":"https://example.com/hooks/x"}""") == Result.succeed(Linked(url)))
        assert(wire(Linked(url)) == wire("""{"url":"https://example.com/hooks/x"}"""))
        assert(parseRefusal(refused).map(_._1) == Present("bad://x y"))
        val (decodeFrame, result) = decodedAt[Linked]("""{"url":"bad://x y"}""")
        result.failure match
            case Present(e: ConstructorRejectedException) =>
                assert(e.path == Seq("url"))
                assert(parseRefusal(e.rejection) == parseRefusal(refused), s"got: ${e.rejection}")
                assert(e.frame == decodeFrame)
            case other => fail(s"expected a ConstructorRejectedException at url, got $other")
        end match
    }

    "a webhook URL is absent as the empty text, and refused text fails the decode at its field with the parser's reason" in {
        val url = HttpUrl.parse("https://example.com/hooks/x").getOrThrow
        assert(Json.decode[Hooked]("""{"url":""}""") == Result.succeed(Hooked(Absent)))
        assert(wire(Hooked(Absent)) == wire("""{"url":""}"""))
        assert(Json.decode[Hooked]("""{"url":"https://example.com/hooks/x"}""") == Result.succeed(Hooked(Present(url))))
        assert(parseRefusal(refused).map(_._1) == Present("bad://x y"))
        Json.decode[Hooked]("""{"url":"bad://x y"}""").failure match
            case Present(e: ConstructorRejectedException) =>
                assert(e.path == Seq("url"))
                assert(parseRefusal(e.rejection) == parseRefusal(refused), s"got: ${e.rejection}")
            case other => fail(s"expected a ConstructorRejectedException at url, got $other")
        end match
    }

end WireFieldTest

object WireFieldTest:

    final case class Times(
        @transform(WireField.Seconds) duration: Duration,
        @transform(WireField.Date) date: Instant,
        @transform(WireField.MaybeDate) @omit editDate: Maybe[Instant] = Absent
    ) derives CanEqual
    object Times:
        given Schema[Times] = WireField.snakeCase(Schema.derived[Times])

    final case class Sized(@transform(WireField.FileSize) @omit fileSize: Maybe[ByteSize] = Absent) derives CanEqual
    object Sized:
        given Schema[Sized] = WireField.snakeCase(Schema.derived[Sized])

    final case class Linked(@transform(WireField.UrlText) url: HttpUrl) derives Schema, CanEqual

    final case class Hooked(@transform(WireField.WebhookUrl) url: Maybe[HttpUrl]) derives Schema, CanEqual

    def at(seconds: Long): Instant = Instant.of(seconds.seconds, Duration.Zero)

    /** What `HttpUrl.parse` refuses "bad://x y" with. */
    def refused(using Frame): Throwable = HttpUrl.parse("bad://x y").failure.getOrElse(new AssertionError("bad://x y parsed"))

    /** A parse refusal as its URL and reason, which identify it apart from the frame it was raised at. */
    def parseRefusal(rejection: String | Throwable): Maybe[(String, HttpUrlParseException.Reason)] =
        rejection match
            case e: HttpUrlParseException => Present((e.url, e.reason))
            case _                        => Absent

    /** `json` decoded as `A` under the caller's frame, paired with that frame. */
    def decodedAt[A: Schema](json: String)(using frame: Frame): (Frame, Result[DecodeException, A]) = (frame, Json.decode[A](json))

    /** `value` encoded through its schema, as JSON with each object's fields in name order. */
    def wire[A: Schema](value: A)(using Frame): Structure.Value = wire(Json.encode(value))

    /** `json` parsed, with each object's fields in name order, so two encodings compare regardless of field order. */
    def wire(json: String)(using Frame): Structure.Value = sorted(Json.decode[Structure.Value](json).getOrThrow)

    private def sorted(value: Structure.Value): Structure.Value =
        value match
            case Structure.Value.Record(fields) => Structure.Value.Record(fields.map((k, v) => (k, sorted(v))).sortBy(_._1))
            case Structure.Value.Sequence(vs)   => Structure.Value.Sequence(vs.map(sorted))
            case other                          => other
end WireFieldTest
