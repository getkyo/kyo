package kyo.internal.whatsapp

import kyo.*
import kyo.schema.Transformer

/** The field codecs of Meta's values whose JSON is not their Scala type's own. Each reads through a `transformVia` schema, so a value
  * that does not fit is refused with kyo-schema's `ConstructorRejectedException` at the field's path, and never stands in for one.
  */
private[kyo] object Transformers:

    /** Epoch seconds as a string of ASCII digits, which is how Meta writes every webhook timestamp. */
    object EpochSeconds extends Transformer.Of[Instant](
            Schema[String].transformVia(epochSeconds)(secondsText)
        )

    /** An optional epoch-seconds string, such as a conversation's `expiration_timestamp`. */
    object OptionalEpochSeconds extends Transformer.Of[Maybe[Instant]](
            Schema[Maybe[String]].transformVia((text: Maybe[String]) =>
                val read: Result[String, Maybe[Instant]] =
                    text match
                        case Present(t) => epochSeconds(t).map(Present(_))
                        case Absent     => Result.succeed(Absent)
                read
            )(_.map(secondsText))
        )

    /** A url Meta sends or is sent: written as its text, and read only as an absolute http or https url over TCP. `HttpUrl.parse` also
      * takes a bare path as a path-only url, which no Cloud API field holds.
      */
    object Url extends Transformer.Full[HttpUrl]:
        def write(value: HttpUrl, writer: Codec.Writer): Unit = Schema[String].serializeWrite(value.full, writer)
        def read(reader: Codec.Reader): HttpUrl               = schema(using reader.frame).serializeRead(reader)

        private def schema(using Frame): Schema[HttpUrl] =
            Schema[String].transformVia((text: String) =>
                HttpUrl.parse(text) match
                    case Result.Success(url)
                        if url.scheme.exists(s => s == "http" || s == "https") && url.host.nonEmpty && url.unixSocket.isEmpty =>
                        Result.succeed(url)
                    case _ => Result.fail("an absolute http or https url")
            )(_.full)
    end Url

    /** A media file's size, which Meta sends as a JSON number or as a string of digits. */
    object FileSize extends Transformer.Of[ByteSize](
            Structure.Value.valueSchema.transformVia(fileSize)(size => Structure.Value.Integer(size.toBytes))
        )

    /** A latitude or longitude, which Meta writes as a JSON number or as a numeric string; written as a number. */
    object Coordinate extends Transformer.Of[Double](
            Structure.Value.valueSchema.transformVia(coordinate)(Structure.Value.Decimal(_))
        )

    private def coordinate(value: Structure.Value): Result[String, Double] =
        value match
            case Structure.Value.Decimal(d)                 => Result.succeed(d)
            case Structure.Value.Integer(n)                 => Result.succeed(n.toDouble)
            case Structure.Value.Str(text) if text.nonEmpty =>
                Maybe.fromOption(text.toDoubleOption).filter(d => !d.isNaN && !d.isInfinite)
                    .fold(Result.fail("a coordinate string that is not a number"))(Result.succeed(_))
            case _ => Result.fail("a coordinate is a number or a numeric string")

    // The text is checked character by character because `toLongOption` also takes a sign and any Unicode digit. The instant is the
    // epoch plus a kyo `Duration`, whose nanosecond `Long` reaches the year 2262; a later time saturates to `Duration.Infinity` and is
    // refused rather than clamped.
    private def epochSeconds(text: String): Result[String, Instant] =
        if text.isEmpty || !text.forall(c => c >= '0' && c <= '9') then Result.fail("epoch seconds are a string of ASCII digits")
        else
            Maybe.fromOption(text.toLongOption).map(_.seconds).filter(_.isFinite)
                .fold(Result.fail("epoch seconds past the latest time a Duration holds"))(sinceEpoch =>
                    Result.succeed(Instant.Epoch + sinceEpoch)
                )

    private def secondsText(instant: Instant): String = instant.toDuration.toSeconds.toString

    private def fileSize(value: Structure.Value): Result[String, ByteSize] =
        value match
            case Structure.Value.Integer(n) if n >= 0                                                 => Result.succeed(n.bytes)
            case Structure.Value.Str(text) if text.nonEmpty && text.forall(c => c >= '0' && c <= '9') =>
                Maybe.fromOption(text.toLongOption).fold(Result.fail("a file size string past a Long"))(n => Result.succeed(n.bytes))
            case _ => Result.fail("a file size is a non-negative whole number or a string of ASCII digits")

end Transformers
