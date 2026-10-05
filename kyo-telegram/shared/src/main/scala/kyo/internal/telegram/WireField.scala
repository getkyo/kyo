package kyo.internal.telegram

import kyo.*
import kyo.schema.Transformer
import kyo.schema.rename

/** The field transforms the model declares with `@transform`, for the Bot API's encodings that differ
  * from kyo-schema's defaults: Telegram writes times and durations as whole Unix seconds, not ISO text.
  */
private[kyo] object WireField:

    /** `schema` with every field named in snake_case, the Bot API's convention. */
    def snakeCase[A](schema: Schema[A]): Schema[A] = schema.renameAllFields(Schema.NameCase.SnakeCase)

    object Seconds extends Transformer.Full[Duration]:
        def write(value: Duration, writer: Codec.Writer): Unit = writer.long(value.toSeconds)
        def read(reader: Codec.Reader): Duration               = reader.long().seconds

    object MaybeSeconds extends Transformer.Full[Maybe[Duration]]:
        def write(value: Maybe[Duration], writer: Codec.Writer): Unit =
            value.fold(writer.nil())(Seconds.write(_, writer))
        def read(reader: Codec.Reader): Maybe[Duration] =
            if reader.isNil() then Absent else Present(Seconds.read(reader))
    end MaybeSeconds

    object Date extends Transformer.Full[Instant]:
        def write(value: Instant, writer: Codec.Writer): Unit = writer.long(value.toDuration.toSeconds)
        def read(reader: Codec.Reader): Instant               = Instant.of(reader.long().seconds, Duration.Zero)

    object MaybeDate extends Transformer.Full[Maybe[Instant]]:
        def write(value: Maybe[Instant], writer: Codec.Writer): Unit =
            value.fold(writer.nil())(Date.write(_, writer))
        def read(reader: Codec.Reader): Maybe[Instant] =
            if reader.isNil() then Absent else Present(Date.read(reader))
    end MaybeDate

    /** Telegram documents file sizes as non-negative; `ByteSize` would clamp a negative count to an empty file. */
    object FileSize extends Transformer.Of[Maybe[ByteSize]](
            summon[Schema[Maybe[Long]]].transformVia((bytes: Maybe[Long]) =>
                bytes match
                    case Present(n) if n < 0 => Result.fail(s"a file size of $n bytes")
                    case _                   => Result.succeed(bytes.map(ByteSize.fromBytes))
            )((size: Maybe[ByteSize]) => size.map(_.toBytes))
        )

    /** A reply's target as `reply_parameters`, an object of which the module sends only `message_id`. */
    object ReplyTo extends Transformer.Of[Maybe[Telegram.MessageId]](
            summon[Schema[Maybe[ReplyParameters]]].transform[Maybe[Telegram.MessageId]](_.map(_.message))(_.map(ReplyParameters(_)))
        )

    final case class ReplyParameters(@rename("message_id") message: Telegram.MessageId) derives Schema

    /** A URL as its text; text `HttpUrl.parse` refuses fails the decode. */
    object UrlText extends Transformer.Of[HttpUrl](
            Schema.stringSchema.transformVia((text: String) => HttpUrl.parse(text))(_.full)
        )

    /** `getWebhookInfo`'s `url`: the empty string when no webhook is set, otherwise the URL `setWebhook` registered, which
      * fails the decode when `HttpUrl.parse` refuses it.
      */
    object WebhookUrl extends Transformer.Of[Maybe[HttpUrl]](
            Schema.stringSchema.transformVia((text: String) =>
                if text.isEmpty then Result.succeed(Maybe.empty[HttpUrl]) else HttpUrl.parse(text).map(Maybe(_))
            )((url: Maybe[HttpUrl]) => url.fold("")(_.full))
        )

end WireField
