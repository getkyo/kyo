package kyo.internal

import kyo.<
import kyo.Abort
import kyo.Frame
import kyo.JsonText
import kyo.Maybe
import kyo.SqlCodec
import kyo.SqlCodec.Format
import kyo.SqlDecodeColumnNotFoundException
import kyo.SqlDecodeException
import kyo.SqlNaming
import kyo.SqlRow
import kyo.SqlSchema
import kyo.SqlValue

/** A backend [[SqlRow.Codec]] that decodes a row's columns positionally through a backend reader.
  *
  * The decode itself, slicing the row to the codec's column span and running the codec's read against a fresh reader inside the row-level
  * abort, is the same for every backend. What differs is the reader the backend builds and the wire format it carries, which the two
  * abstract members below supply.
  */
abstract class SqlPositionalRowCodec extends SqlRow.Codec:

    /** The wire format of every column in the rows this codec decodes. */
    def format: Format

    /** Builds the backend's positional reader over `sliced`, handed the field matcher the codec and the sliced row resolved together.
      *
      * The one decode hook a backend implements, which is what makes this class the row-decode half of the backend SPI: an out-of-tree
      * engine supplies its [[kyo.SqlCodec.Reader]] here and inherits the slicing, field matching, and abort handling above unchanged.
      */
    def newReader(sliced: SqlRow, matchesFieldAt: Maybe[(Int, String) => Boolean])(using Frame): SqlCodec.Reader

    /** Whether the column `typeToken` names holds a four-byte float rather than an eight-byte one.
      *
      * One [[kyo.SqlRow.ColumnKind.Float]] covers both widths, and they do not render alike: reading a four-byte column at the wider type
      * widens 0.1 to 0.10000000149011612 before it is rendered. A backend with a single width leaves this alone.
      */
    private[kyo] def isSingleWidthFloat(typeToken: Int): Boolean = false

    /** Renders one column as the string [[kyo.SqlRow.text]] promises: one per stored value, whatever carried the row.
      *
      * The kinds here are the ones a neutral read spans, decoded and handed to [[SqlValueRender]] under BOTH wire formats; see its header
      * for why the text format is parsed and re-rendered rather than passed through.
      *
      * The rest need the wire itself and belong to the backend, which overrides this and calls the same renderer: a date carries an era, a
      * time is a signed span wider than a day, an interval carries a calendar and a time part at once, and an array carries its elements'
      * own kind. Text and Json are their own rendering under both formats and are returned as they stand.
      */
    override def columnValue(row: SqlRow, idx: Int)(using Frame): SqlValue < Abort[SqlDecodeException] =
        import SqlRow.ColumnKind
        def read[A](using SqlSchema[A]): A < Abort[SqlDecodeException] =
            val sliced = row.slice(idx, idx + 1)
            SqlRow.Codec.catchingColumn(Maybe(idx))(summon[SqlSchema[A]].read(newReader(sliced, Maybe.empty)))
        end read
        val typeToken = row.columns(idx).typeToken
        columnKind(typeToken) match
            // BigInt, not Long: an unsigned 64-bit column reaches past what a signed Long holds.
            case ColumnKind.Integer => read[BigInt].map(SqlValue.Integer(_))
            case ColumnKind.Decimal => read[BigDecimal].map(SqlValue.Decimal(_))
            case ColumnKind.Float =>
                if isSingleWidthFloat(typeToken) then read[Float].map(SqlValue.Float4(_))
                else read[Double].map(SqlValue.Float8(_))
            case ColumnKind.Bool => read[Boolean].map(SqlValue.Bool(_))
            case ColumnKind.Text => read[String].map(SqlValue.Text(_))
            case ColumnKind.Json => read[JsonText].map(j => SqlValue.Json(j.text))
            case ColumnKind.Uuid => read[java.util.UUID].map(SqlValue.Uuid(_))
            case ColumnKind.Timestamp =>
                read[java.time.Instant].map(i => SqlValue.Timestamp(i.getEpochSecond, i.getNano / 1000))
            case ColumnKind.Bytes => read[kyo.Span[Byte]].map(SqlValue.Bytes(_))
            case ColumnKind.Date | ColumnKind.Time | ColumnKind.TimeWithOffset | ColumnKind.DateTime |
                ColumnKind.Interval | ColumnKind.Array | ColumnKind.Unknown =>
                unrenderedColumn(row, idx)
        end match
    end columnValue

    /** The answer for a column nothing renders: the same refusal under BOTH wire formats.
      *
      * Handing back the text protocol's bytes would break the contract: `money` has no neutral decode to render from, since `lc_monetary`
      * supplies both its fraction digits and its symbol, so it would read `$12.34` through a simple query and raise through a prepared one.
      *
      * The cost is that such a column is not readable as text at all, which it never was under the binary format.
      */
    private def unrenderedColumn(row: SqlRow, idx: Int)(using Frame): SqlValue < Abort[SqlDecodeException] =
        val column = row.columns(idx)
        Abort.fail(kyo.SqlDecodeColumnNotRenderableException(column.name, typeName(column.typeToken)))

    /** Decodes `schema` from the row's columns starting at `offset`.
      *
      * The reader is handed a field matcher built from the codec's field names and the sliced row together, because which field a column
      * belongs to depends on both: see [[kyo.internal.SqlFieldMatcher]] for the three modes and why the statement decides between them.
      * `naming` is the run-scope [[SqlNaming]] casing threaded from the query site (Absent for the raw [[SqlRow.decode]] API), so a cased
      * by-name read matches its columns. In the strict [[kyo.SqlRow.FieldMatch.ByName]] mode a field that resolves to no column fails
      * here, before any read, naming the missing column.
      */
    final def read[A](schema: SqlSchema[A], row: SqlRow, offset: Int, naming: Maybe[SqlNaming], fieldMatch: SqlRow.FieldMatch)(using
        Frame
    ): A < Abort[SqlDecodeException] =
        val sliced = row.slice(offset, offset + schema.width)
        val missing =
            fieldMatch match
                case SqlRow.FieldMatch.ByName => SqlFieldMatcher.missingByName(schema.fieldNames, sliced.columnNames, naming)
                case _                        => Maybe.empty[String]
        missing match
            case Maybe.Present(name) => Abort.fail(SqlDecodeColumnNotFoundException(name, sliced.columnNames))
            case Maybe.Absent =>
                SqlRow.Codec.catching(
                    schema.read(newReader(sliced, Maybe(SqlFieldMatcher.of(schema.fieldNames, sliced.columnNames, naming, fieldMatch))))
                )
        end match
    end read

end SqlPositionalRowCodec
