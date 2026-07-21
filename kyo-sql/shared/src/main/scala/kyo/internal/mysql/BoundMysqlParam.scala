package kyo.internal.mysql

import kyo.Maybe
import kyo.Span
import kyo.internal.mysql.types.MysqlEncoder

/** A parameter value paired with its encoder, ready to be sent in [[ComStmtExecute]].
  *
  * Each `BoundMysqlParam` corresponds to one positional `?` placeholder in the SQL text.
  *
  * @tparam A
  *   the Scala type of the parameter value
  * @param value
  *   the parameter value; [[Maybe.Absent]] represents SQL NULL
  * @param encoder
  *   the encoder to use when the value is non-NULL
  */
final class BoundMysqlParam[A](val value: Maybe[A], val encoder: MysqlEncoder[A]):

    /** MySQL column-type byte for this parameter (used in the type descriptor list of [[ComStmtExecute]]). */
    def mysqlType: Int = encoder.mysqlType

    /** 0 = signed, 1 = unsigned. */
    def unsigned: Int = encoder.unsigned

    /** Returns [[Maybe.Absent]] for NULL, or the binary-encoded bytes for a non-NULL value. */
    def encoded: Maybe[Span[Byte]] =
        value match
            case Maybe.Absent =>
                Maybe.Absent
            case Maybe.Present(v) =>
                val writer = new MysqlBufferWriter
                encoder.write(v, writer)
                Maybe.Present(writer.toSpan)

end BoundMysqlParam

object BoundMysqlParam:

    /** Convenience constructor for a non-NULL parameter. */
    def apply[A](value: A, encoder: MysqlEncoder[A]): BoundMysqlParam[A] =
        new BoundMysqlParam(Maybe.Present(value), encoder)

    /** Convenience constructor for a NULL parameter. */
    def nullParam[A](encoder: MysqlEncoder[A]): BoundMysqlParam[A] =
        new BoundMysqlParam(Maybe.Absent, encoder)

end BoundMysqlParam
