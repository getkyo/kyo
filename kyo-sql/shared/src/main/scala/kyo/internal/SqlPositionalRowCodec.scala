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

    /** Renders one column as text by decoding it at the type its own column reports, then rendering that value.
      *
      * Under [[kyo.SqlCodec.Format.Text]] every value is already its rendering, so the bytes are the answer and no decode happens. Under
      * [[kyo.SqlCodec.Format.Binary]] the value is a wire representation that has to be read before it can be rendered, and what reads it
      * is the [[kyo.SqlSchema.Column]] for the Scala type the column's [[kyo.SqlRow.ColumnKind]] names. Each kind maps to the widest Scala
      * type in its family, so one integral read covers `int2` through `int8` and one float read covers both binary float widths.
      *
      * A kind with no lossless Scala rendering falls back to the bytes as UTF-8: [[kyo.SqlRow.ColumnKind.Unknown]] is a type the backend
      * did not recognise, and an [[kyo.SqlRow.ColumnKind.Array]] or [[kyo.SqlRow.ColumnKind.Bytes]] column has no single scalar reading.
      * That fallback is a rendering of last resort and can answer mojibake for a binary value, which is exactly why it is not what
      * `decode[String]` does.
      *
      * [[kyo.SqlRow.ColumnKind.Interval]] falls there too, for the same reason read the other way round: `java.time.Duration` carries an
      * interval's time part and `java.time.Period` its calendar part, and an interval may carry both, so neither type spans the kind. A
      * backend that reports it renders it from its own wire fields by overriding this method, which is where the layout is known.
      */
    override def text(row: SqlRow, idx: Int)(using Frame): String < Abort[SqlDecodeException] =
        import SqlRow.ColumnKind
        def read[A](using SqlSchema[A]): A < Abort[SqlDecodeException] =
            val sliced = row.slice(idx, idx + 1)
            SqlRow.Codec.catchingColumn(Maybe(idx))(summon[SqlSchema[A]].read(newReader(sliced, Maybe.empty)))
        end read
        format match
            case Format.Text => super.text(row, idx)
            case Format.Binary =>
                columnKind(row.columns(idx).typeToken) match
                    case ColumnKind.Integer => read[Long].map(_.toString)
                    // toPlainString: a fixed-point column's rendering never takes exponent notation, and
                    // BigDecimal.toString does once the adjusted exponent is below -6, so a DECIMAL(10,7) holding
                    // 0.0000001 rendered `1E-7` under the binary protocol against the server's `0.0000001`.
                    case ColumnKind.Decimal        => read[BigDecimal].map(_.bigDecimal.toPlainString)
                    case ColumnKind.Float          => read[Double].map(_.toString)
                    case ColumnKind.Bool           => read[Boolean].map(_.toString)
                    case ColumnKind.Text           => read[String]
                    case ColumnKind.Json           => read[JsonText].map(_.text)
                    case ColumnKind.Uuid           => read[java.util.UUID].map(_.toString)
                    case ColumnKind.Date           => read[java.time.LocalDate].map(_.toString)
                    case ColumnKind.Time           => read[java.time.LocalTime].map(_.toString)
                    case ColumnKind.TimeWithOffset => read[java.time.OffsetTime].map(_.toString)
                    case ColumnKind.DateTime       => read[java.time.LocalDateTime].map(_.toString)
                    case ColumnKind.Timestamp      => read[java.time.Instant].map(_.toString)
                    case ColumnKind.Interval | ColumnKind.Array | ColumnKind.Bytes | ColumnKind.Unknown =>
                        super.text(row, idx)
        end match
    end text

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
