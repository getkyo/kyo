package kyo.internal.postgres

import kyo.Maybe
import kyo.Span
import kyo.internal.postgres.PostgresBufferWriter
import kyo.internal.postgres.types.Format
import kyo.internal.postgres.types.PostgresEncoder

/** A parameter value paired with its encoder, ready to be serialised into a Bind message.
  *
  * `BoundParam` is the unit of work passed to [[exchange.ExtendedQueryExchange]]: each element corresponds to one positional parameter
  * (`$1`, `$2`, ...) in the SQL text.
  *
  * @tparam A
  *   the Scala type of the parameter value
  * @param value
  *   the parameter value; [[Maybe.Absent]] represents SQL NULL
  * @param encoder
  *   the encoder to use when the value is non-NULL
  */
final class BoundParam[A](val value: Maybe[A], val encoder: PostgresEncoder[A]):
    /** Returns the OID for this parameter (0 means "let server infer"). */
    def oid: Int = encoder.oid

    /** Returns the format code for this parameter. */
    def format: Format = encoder.format

    /** Serialises the value into `buf` for inclusion in a Bind message.
      *
      * @return
      *   [[Maybe.Present]] containing the encoded bytes (length-prefixed by Bind marshaller), or [[Maybe.Absent]] for NULL.
      */
    def encoded: Maybe[Span[Byte]] =
        value match
            case Maybe.Absent => Maybe.Absent
            case Maybe.Present(v) =>
                val buf = new PostgresBufferWriter
                encoder.write(v, buf)
                Maybe.Present(buf.toSpan)
end BoundParam

object BoundParam:
    /** Convenience constructor for a non-NULL parameter. */
    def apply[A](value: A, encoder: PostgresEncoder[A]): BoundParam[A] =
        new BoundParam(Maybe.Present(value), encoder)

    /** Convenience constructor for a NULL parameter. */
    def nullParam[A](encoder: PostgresEncoder[A]): BoundParam[A] =
        new BoundParam(Maybe.Absent, encoder)
end BoundParam
