package kyo

import kyo.*
import kyo.internal.postgres.FieldDescription
import kyo.internal.postgres.types.EncodingRegistry
import kyo.internal.postgres.types.Format
import kyo.internal.postgres.types.PostgresDecoder

/** A single row from a query result.
  *
  * Holds the raw bytes for each column together with the field metadata from [[FieldDescription]]. Column values are text-format bytes for
  * simple-query results; binary-format bytes for extended-protocol results. The `format` field records which wire encoding the server used
  * for all columns in this row (per-column format is not tracked in v1; all columns share the same format from the Bind message).
  *
  * @param values
  *   one entry per column; [[Maybe.Absent]] represents SQL NULL
  * @param fields
  *   field descriptors from [[RowDescription]], in the same order as [[values]]
  * @param format
  *   wire format used by the server for this row's column values (Text for simple-query, Binary for extended-query)
  */
final class SqlRow(
    val values: Chunk[Maybe[Span[Byte]]],
    val fields: Chunk[FieldDescription],
    val format: Format = Format.Text
):

    /** Returns the raw bytes for the column at `idx`, or [[Absent]] for SQL NULL. */
    def column(idx: Int): Maybe[Span[Byte]] =
        if idx < 0 || idx >= values.size then Absent
        else values(idx)

    /** Returns the raw bytes for the column with the given name, or [[Absent]] if not found or NULL. */
    def column(name: String): Maybe[Span[Byte]] =
        val idx = fields.indexWhere(_.name == name)
        if idx < 0 then Absent
        else values(idx)
    end column

    /** Decodes the column at `idx` using the provided [[SqlDecoder]].
      *
      * @throws SqlException.Decode
      *   if the column is out of bounds, is NULL, or the decoder fails
      */
    def columnAs[A](idx: Int)(using Frame, SqlDecoder[A]): A < Abort[SqlException.Decode] =
        column(idx) match
            case Absent         => Abort.fail(SqlException.Decode(s"Column $idx is NULL or out of bounds", Maybe.Absent, summon[Frame]))
            case Present(bytes) => summon[SqlDecoder[A]].decode(bytes)

    /** Decodes the column with `name` using the provided [[SqlDecoder]].
      *
      * @throws SqlException.Decode
      *   if the column is not found, is NULL, or the decoder fails
      */
    def columnAs[A](name: String)(using Frame, SqlDecoder[A]): A < Abort[SqlException.Decode] =
        column(name) match
            case Absent         => Abort.fail(SqlException.Decode(s"Column '$name' is NULL or not found", Maybe.Absent, summon[Frame]))
            case Present(bytes) => summon[SqlDecoder[A]].decode(bytes)

    /** Decodes the column at `idx` using a [[PostgresDecoder]], looked up via the builtin [[EncodingRegistry]].
      *
      * The decoder is resolved by the column's OID (from [[FieldDescription.dataType]]) and the row's [[format]]. If no decoder is
      * registered for the OID/format combination, [[SqlException.Decode]] is raised.
      *
      * @throws SqlException.Decode
      *   if the column is out of bounds, is NULL, the decoder is not found, or decoding fails
      */
    def columnDecoded[A](idx: Int)(using Frame, PostgresDecoder[A]): A < Abort[SqlException.Decode] =
        if idx < 0 || idx >= values.size then
            Abort.fail(SqlException.Decode(s"Column $idx is out of bounds (${values.size} columns)", Maybe.Absent, summon[Frame]))
        else
            column(idx) match
                case Absent => Abort.fail(SqlException.Decode(s"Column $idx is NULL", Maybe.Absent, summon[Frame]))
                case Present(bytes) =>
                    try summon[PostgresDecoder[A]].read(format, bytes)
                    catch
                        case e: Exception =>
                            Abort.fail(SqlException.Decode(s"Failed to decode column $idx: ${e.getMessage}", Maybe.Absent, summon[Frame]))

    /** Decodes the column with `name` using a [[PostgresDecoder]], looked up via the builtin [[EncodingRegistry]].
      *
      * @throws SqlException.Decode
      *   if the column is not found, is NULL, no decoder is registered, or decoding fails
      */
    def columnDecoded[A](name: String)(using Frame, PostgresDecoder[A]): A < Abort[SqlException.Decode] =
        val idx = fields.indexWhere(_.name == name)
        if idx < 0 then Abort.fail(SqlException.Decode(s"Column '$name' not found", Maybe.Absent, summon[Frame]))
        else columnDecoded[A](idx)
    end columnDecoded

    /** Reads a typed value from column index [[idx]] using the given [[SqlSchema]].
      *
      * Handles both binary (PostgreSQL extended protocol) and text (PostgreSQL simple query or MySQL) protocol formats transparently. The
      * backend is inferred from the row's [[format]] and field OIDs: [[Format.Binary]] rows route to the PostgreSQL binary decoder;
      * [[Format.Text]] rows with at least one non-zero field OID route to the PostgreSQL text decoder; [[Format.Text]] rows with all-zero
      * OIDs (MySQL) route to the MySQL decoder.
      *
      * For multi-column types (e.g. derived case classes or tuples), [[idx]] is the start index of the first column; the schema's
      * [[SqlSchema.fieldCount]] determines how many columns are consumed.
      *
      * For raw bytes use [[column]]; for the backend-specific decoder extension point see [[columnDecoded]] (requires internal types).
      *
      * @throws SqlException.Decode
      *   if the column is out of bounds or decoding fails
      */
    def decode[A](idx: Int)(using frame: Frame, schema: SqlSchema[A]): A < Abort[SqlException.Decode] =
        if idx < 0 || idx >= values.size then
            Abort.fail(SqlException.Decode(s"Column $idx is out of bounds (${values.size} columns)", Maybe.Absent, frame))
        else
            val count  = schema.fieldCount
            val sliced = slice(idx, idx + count)
            // PG rows always carry a non-zero data-type OID per column; MySQL rows always have
            // dataType == 0 (its `FieldDescription` conversion in `mysqlRowToRow` leaves it zero).
            // Route by the OID presence first so a MySQL-produced binary row (extended protocol)
            // does NOT get sent to `readPostgres` (which decodes big-endian PG binary and would
            // mis-read MySQL's little-endian layout).
            if fields.exists(_.dataType != 0) then schema.readPostgres(sliced)
            else schema.readMysql(sliced)
            end if
    end decode

    /** Reads a typed value from named column [[name]] using the given [[SqlSchema]].
      *
      * Equivalent to finding the column index by name and calling [[decode[A](idx: Int)]]. The backend is inferred from [[format]] and
      * field OIDs exactly as in the index-based overload.
      *
      * @throws SqlException.Decode
      *   if the column is not found or decoding fails
      */
    def decode[A](name: String)(using frame: Frame, schema: SqlSchema[A]): A < Abort[SqlException.Decode] =
        val idx = fields.indexWhere(_.name == name)
        if idx < 0 then Abort.fail(SqlException.Decode(s"Column '$name' not found", Maybe.Absent, frame))
        else decode[A](idx)
    end decode

    /** Returns a view of this row restricted to columns `[from, until)`.
      *
      * Used by tuple `SqlSchema` instances to feed each component reader the correct positional slice. The `format` is propagated
      * unchanged. If `from >= until` or the range is out of bounds, the slice is empty.
      */
    def slice(from: Int, until: Int): SqlRow =
        new SqlRow(values.slice(from, until), fields.slice(from, until), format)

    override def equals(that: Any): Boolean = that match
        case other: SqlRow =>
            format == other.format &&
            fields == other.fields &&
            values.size == other.values.size &&
            values.indices.forall { i =>
                (values(i), other.values(i)) match
                    case (Maybe.Absent, Maybe.Absent)         => true
                    case (Maybe.Present(a), Maybe.Present(b)) => a.is(b)
                    case _                                    => false
            }
        case _ => false

    override def hashCode: Int =
        var h = format.hashCode
        h = h * 31 + fields.hashCode
        values.foreach {
            case Maybe.Absent     => h = h * 31
            case Maybe.Present(s) => h = h * 31 + java.util.Arrays.hashCode(s.toArray)
        }
        h
    end hashCode

    override def toString: String =
        val columns = fields.zip(values).map { case (f, v) =>
            v match
                case Absent     => s"${f.name}=NULL"
                case Present(b) => s"${f.name}=${new String(b.toArray, java.nio.charset.StandardCharsets.UTF_8)}"
        }
        s"SqlRow(${columns.mkString(", ")})"
    end toString

end SqlRow

object SqlRow:
    given CanEqual[SqlRow, SqlRow] = CanEqual.derived

/** Typeclass for decoding a raw [[Span[Byte]]] into a Scala value.
  *
  * The `SqlDecoder` trait is the simple text-oriented typeclass used by [[SqlRow.columnAs]]. For OID-aware, format-aware decoding using the
  * binary codec layer, see [[SqlRow.columnDecoded]] and [[PostgresDecoder]].
  *
  * @tparam A
  *   the Scala type to decode into
  */
trait SqlDecoder[A]:
    def decode(bytes: Span[Byte])(using Frame): A < Abort[SqlException.Decode]

object SqlDecoder:
    /** Decodes text-format bytes as a UTF-8 [[String]]. */
    given SqlDecoder[String] with
        def decode(bytes: Span[Byte])(using Frame): String < Abort[SqlException.Decode] =
            new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
end SqlDecoder
