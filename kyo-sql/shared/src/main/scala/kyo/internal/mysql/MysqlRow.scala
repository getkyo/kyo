package kyo.internal.mysql

import kyo.*
import kyo.SqlDecoder
import kyo.SqlException
import kyo.internal.postgres.types.Format

/** A single row from a MySQL result set.
  *
  * Stores the raw bytes for each column alongside the column metadata from [[ColumnDefinition41]] and the wire [[Format]] the values came
  * back in (Text for simple-query, Binary for extended / prepared-stmt). SQL NULL columns are represented as [[Maybe.Absent]].
  *
  * This is separate from the shared [[kyo.SqlRow]] to keep MySQL column metadata ([[ColumnDefinition41]]) decoupled from the
  * Postgres-oriented [[kyo.internal.postgres.FieldDescription]] type. A future generalisation could introduce a backend-agnostic
  * `RowMetadata` trait if both backends need to share a public `Row` API.
  *
  * WARNING: `MysqlRow.columns` is `Chunk[ColumnDefinition41]`, not the `Chunk[FieldDescription]` exposed by [[kyo.SqlRow.fields]]. Code
  * that bridges [[MysqlRow]] to [[kyo.SqlRow]] (e.g. inside [[kyo.SqlClient]]) must convert column definitions explicitly and forward the
  * `format` field, do not pass `columns` where `fields` is expected.
  *
  * @param values
  *   one entry per column; [[Maybe.Absent]] = SQL NULL
  * @param columns
  *   column definitions from the preceding ColumnDefinition41 packets, in the same order as [[values]]
  * @param format
  *   wire format the `values` bytes are encoded in; [[Format.Text]] for simple-query, [[Format.Binary]] for extended / prepared-stmt
  */
final class MysqlRow(
    val values: Chunk[Maybe[Span[Byte]]],
    val columns: Chunk[ColumnDefinition41],
    val format: Format
):

    /** Returns the raw bytes for the column at `idx`, or [[Maybe.Absent]] for SQL NULL or out-of-bounds. */
    def column(idx: Int): Maybe[Span[Byte]] =
        if idx < 0 || idx >= values.size then Maybe.Absent
        else values(idx)

    /** Returns the raw bytes for the column with the given name (matching [[ColumnDefinition41.name]]), or [[Maybe.Absent]] if not found or
      * NULL.
      */
    def column(name: String): Maybe[Span[Byte]] =
        val idx = columns.indexWhere(_.name == name)
        if idx < 0 then Maybe.Absent
        else values(idx)
    end column

    /** Decodes the column at `idx` using the provided [[Decoder]]. */
    def columnAs[A](idx: Int)(using d: SqlDecoder[A])(using Frame): A < Abort[SqlException.Decode] =
        column(idx) match
            case Maybe.Absent => Abort.fail(SqlException.Decode(s"Column $idx is NULL or out of bounds", Maybe.Absent, summon[Frame]))
            case Maybe.Present(bytes) => d.decode(bytes)

    /** Decodes the column with `name` using the provided [[Decoder]]. */
    def columnAs[A](name: String)(using d: SqlDecoder[A])(using Frame): A < Abort[SqlException.Decode] =
        column(name) match
            case Maybe.Absent => Abort.fail(SqlException.Decode(s"Column '$name' is NULL or not found", Maybe.Absent, summon[Frame]))
            case Maybe.Present(bytes) => d.decode(bytes)

    override def toString: String =
        val columnStrs = columns.zip(values).map { case (columnDef, v) =>
            v match
                case Maybe.Absent     => s"${columnDef.name}=NULL"
                case Maybe.Present(b) => s"${columnDef.name}=${new String(b.toArray, java.nio.charset.StandardCharsets.UTF_8)}"
        }
        s"MysqlRow(${columnStrs.mkString(", ")})"
    end toString

end MysqlRow
