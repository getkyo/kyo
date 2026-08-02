package kyo.internal.postgres

import kyo.<
import kyo.Abort
import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Span
import kyo.SqlCodec
import kyo.SqlCodec.Format
import kyo.SqlDecodeColumnAbsentException
import kyo.SqlDecodeColumnNotFoundException
import kyo.SqlDecodeColumnOutOfBoundsException
import kyo.SqlDecodeException
import kyo.SqlRow
import kyo.SqlSchema
import kyo.bug
import kyo.internal.SqlPositionalRowCodec
import kyo.internal.postgres.types.PostgresDecoder

/** The PostgreSQL backend's [[SqlRow.Codec]]: decodes a row's columns through [[PostgresRowReader]].
  *
  * Carries the wire format the server used for the result set that produced the row, which is the one piece of decode context a PostgreSQL row
  * needs beyond its own bytes. Being a case class, two codecs for the same format are equal, so [[SqlRow]] equality stays format-sensitive.
  *
  * @param format
  *   the wire format of every column in the rows this codec decodes
  */
final private[kyo] case class PostgresRowCodec(format: Format) extends SqlPositionalRowCodec:

    def newReader(sliced: SqlRow, matchesFieldAt: Maybe[(Int, String) => Boolean])(using Frame): SqlCodec.Reader =
        new PostgresRowReader(sliced, format, matchesFieldAt)

end PostgresRowCodec

private[kyo] object PostgresRowCodec:

    /** Builds a [[SqlRow]] from what a PostgreSQL result-set message carries: the column bytes, the `RowDescription` fields, and the wire
      * format the Bind message asked for.
      */
    private[kyo] def row(
        values: Chunk[Maybe[Span[Byte]]],
        fields: Chunk[FieldDescription],
        format: Format = Format.Text
    ): SqlRow =
        new SqlRow(values, fields.map(f => SqlRow.Column(f.name, f.dataType)), PostgresRowCodec(format))

    /** The wire format the row's own codec carries.
      *
      * A row a PostgreSQL exchange produced always carries a [[PostgresRowCodec]]; anything else reaching a PostgreSQL decode path is a wiring
      * error, not a runtime condition.
      */
    private[kyo] def formatOf(row: SqlRow): Format =
        row.codec match
            case codec: PostgresRowCodec => codec.format
            case other                   => bug(s"a PostgreSQL decoder cannot read a row decoded by $other")

    /** Decodes one column with an explicit [[PostgresDecoder]], resolved by the caller rather than by the schema layer.
      *
      * The escape hatch for a column whose type has no `Schema`, or whose wire form the caller wants to decode itself. Aborts when the
      * index is out of bounds, the column is NULL, or the decoder fails. A decoder failure `NonFatal` excludes, a `VirtualMachineError` or an
      * interrupt among them, arrives as a panic on this same channel rather than as a `SqlDecodeException` a caller could recover from by
      * type.
      */
    private[kyo] def columnDecoded[A](row: SqlRow, idx: Int)(using Frame, PostgresDecoder[A]): A < Abort[SqlDecodeException] =
        if idx < 0 || idx >= row.size then
            Abort.fail(SqlDecodeColumnOutOfBoundsException(idx, row.size))
        else
            row.column(idx) match
                case Maybe.Absent         => Abort.fail(SqlDecodeColumnAbsentException(idx))
                case Maybe.Present(bytes) =>
                    // The column's own OID goes to the decoder: a numeric decoder resolves the wire width from it, and
                    // an explicitly-summoned decoder is exactly where a caller's Scala type and the column's type are
                    // most likely to differ.
                    //
                    // Classification is the shared helper's, so this entry point and the schema-driven `read` above
                    // report one decoder failure the same way. What this one adds is the column: it has an index where
                    // a whole-row decode has none, and naming it is why this entry point exists separately at all.
                    SqlRow.Codec.catchingColumn(Maybe.Present(idx)) {
                        summon[PostgresDecoder[A]].read(formatOf(row), bytes, row.columns(idx).typeToken)
                    }

    /** Decodes the column named `name` with an explicit [[PostgresDecoder]]. */
    private[kyo] def columnDecoded[A](row: SqlRow, name: String)(using Frame, PostgresDecoder[A]): A < Abort[SqlDecodeException] =
        val idx = row.columnNames.indexWhere(_ == name)
        if idx < 0 then Abort.fail(SqlDecodeColumnNotFoundException(name))
        else columnDecoded[A](row, idx)
    end columnDecoded

end PostgresRowCodec
