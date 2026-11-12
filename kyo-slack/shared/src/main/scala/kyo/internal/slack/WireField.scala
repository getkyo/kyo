package kyo.internal.slack

import kyo.*
import kyo.schema.Transformer

/** The field transforms the model declares with `@transform`, for Slack's encodings that no kyo-schema default reads. */
private[kyo] object WireField:

    /** Slack documents a file's `size` as a count of bytes; `ByteSize` would clamp a negative one to an empty file. */
    object MaybeSize extends Transformer.Of[Maybe[ByteSize]](
            summon[Schema[Maybe[Long]]].transformVia((bytes: Maybe[Long]) =>
                bytes match
                    case Present(n) if n < 0 => Result.fail(s"a file size of $n bytes")
                    case _                   => Result.succeed(bytes.map(ByteSize.fromBytes))
            )((size: Maybe[ByteSize]) => size.map(_.toBytes))
        )

    /** A url Slack supplies, such as a file's `url_private`; text `HttpUrl.parse` refuses fails the decode. */
    object MaybeUrl extends Transformer.Of[Maybe[HttpUrl]](
            Schema.maybeSchema(using
                Schema.stringSchema.transformVia((text: String) => HttpUrl.parse(text).mapFailure(_.getMessage))(_.full)
            )
        )

end WireField
