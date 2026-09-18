package kyo.internal.sqlite

import kyo.*
import kyo.internal.SqlPositionalRowCodec

/** Decodes a SQLite result set, dispatching on each column's DECLARED type.
  *
  * A value's STORAGE CLASS is per row and has five members, so a date and a decimal are both `text`: it cannot say that a column is a date.
  * The DECLARED type is per column, is whatever string the DDL wrote, and is the vocabulary the type mapping chose, so `columnKind` reads
  * it and `typeToken` is the column's index into the declarations this result set reported. An index rather than a packed tag, because a
  * declared type is a STRING with parameters: `DECIMAL TEXT(38,10)` carries a scale no Int holds.
  *
  * A column traceable to no declared one answers [[Absent]] and is [[SqlRow.ColumnKind.Unknown]]: every expression, every `CAST`, and every
  * column declared with no type.
  *
  * @param declarations
  *   the declared type of each column, in order
  */
final private[sqlite] class SqliteRowCodec(declarations: Chunk[Maybe[String]]) extends SqlPositionalRowCodec:

    /** One format: every type SQLite has no native storage for is held as the text the renderer wrote. */
    def format: SqlCodec.Format = SqlCodec.Format.Text

    def newReader(sliced: SqlRow, matchesFieldAt: Maybe[(Int, String) => Boolean])(using Frame): SqlCodec.Reader =
        // The slice's columns travel with the reader so a read can be checked against what the column was DECLARED as. Their token indexes
        // the whole result set's declarations rather than the slice, so a decode of the second row type in a join still resolves.
        new SqliteRowReader(sliced.values, sliced.columns, declaredAt, matchesFieldAt, summon[Frame])

    override def columnKind(typeToken: Int): SqlRow.ColumnKind =
        declaredAt(typeToken) match
            case Present(decl) => SqliteRowCodec.kindOf(decl)
            case Absent        => SqlRow.ColumnKind.Unknown

    override def typeName(typeToken: Int): Maybe[String] = declaredAt(typeToken)

    /** A `FLOAT` column is four bytes wide even though SQLite stores it as a double.
      *
      * The width is the one the DDL declared, and it keeps 0.1 from being rendered at double width after a round trip through a column that
      * was asked to hold a Float.
      */
    override def isSingleWidthFloat(typeToken: Int): Boolean =
        declaredAt(typeToken).exists(decl => SqliteRowCodec.leadingWord(decl) == "FLOAT")

    /** The kinds the shared decode leaves to the backend, plus the one case SQLite has that no declared type covers.
      *
      * The shared implementation refuses the kinds a network backend needs the wire itself for; here they need only the text, which
      * [[SqliteText]] already parses. `Unknown` means an EXPRESSION rather than an unrenderable type, which is an ordinary thing to select,
      * so it answers [[SqlValue.ServerRendering]] rather than refusing.
      */
    override def columnValue(row: SqlRow, idx: Int)(using Frame): SqlValue < Abort[SqlDecodeException] =
        import SqlRow.ColumnKind
        val typeToken = row.columns(idx).typeToken
        columnKind(typeToken) match
            case ColumnKind.Date | ColumnKind.Time | ColumnKind.TimeWithOffset | ColumnKind.DateTime | ColumnKind.Interval =>
                textOf(row, idx).map { text =>
                    SqlRow.Codec.catchingColumn(Present(idx))(SqliteRowCodec.temporal(columnKind(typeToken), text))
                }
            case ColumnKind.Array =>
                // Arrays are JSON, as on MySQL. The elements come back as their own text rather than re-parsed, which
                // is what Elements carries.
                textOf(row, idx).map { text =>
                    SqlRow.Codec.catchingColumn(Present(idx)) {
                        SqlValue.Elements(
                            kyo.internal.SqlJsonArray.elements(text)(m => throw new IllegalArgumentException(m))
                                .map(e => Present(SqlValue.ServerRendering(e)))
                        )
                    }
                }
            case ColumnKind.Unknown => textOf(row, idx).map(SqlValue.ServerRendering(_))
            case ColumnKind.Decimal =>
                // SQLite applied no scale, so the DECLARED one is the only place the intended scale survives: a column
                // declared (38,10) holding "2.5" means 2.5000000000, and nothing in the stored text says so.
                super.columnValue(row, idx).map {
                    case SqlValue.Decimal(value) =>
                        declaredAt(typeToken).flatMap(SqliteRowCodec.declaredScale) match
                            case Present(scale) if value.scale < scale => SqlValue.Decimal(value.setScale(scale))
                            case _                                     => SqlValue.Decimal(value)
                    case other => other
                }
            case _ => super.columnValue(row, idx)
        end match
    end columnValue

    private def textOf(row: SqlRow, idx: Int)(using Frame): String < Abort[SqlDecodeException] =
        row.column(idx) match
            case Present(bytes) => SqliteText.utf8(bytes)
            case Absent         => Abort.fail(SqlDecodeColumnAbsentException(idx))

    private[sqlite] def declaredAt(idx: Int): Maybe[String] =
        if idx < 0 || idx >= declarations.size then Absent else declarations(idx)

end SqliteRowCodec

private[sqlite] object SqliteRowCodec:

    /** The leading word of a declared type, upper-cased.
      *
      * The type mapping's names are multi-word (`DECIMAL TEXT(38,10)`, `UUID TEXT`) because a trailing `TEXT` is what forces TEXT affinity.
      * The kind lives in the first word; the rest is affinity and parameters.
      */
    def leadingWord(decl: String): String =
        val trimmed = decl.trim
        val end     = trimmed.indexWhere(c => c == ' ' || c == '(')
        (if end < 0 then trimmed else trimmed.substring(0, end)).toUpperCase
    end leadingWord

    def kindOf(decl: String): SqlRow.ColumnKind =
        leadingWord(decl) match
            case "INTEGER" | "INT" | "BIGINT" | "SMALLINT" | "TINYINT" => SqlRow.ColumnKind.Integer
            case "DECIMAL" | "NUMERIC"                                 => SqlRow.ColumnKind.Decimal
            case "BOOLEAN" | "BOOL"                                    => SqlRow.ColumnKind.Bool
            case "REAL" | "FLOAT" | "DOUBLE"                           => SqlRow.ColumnKind.Float
            case "BLOB"                                                => SqlRow.ColumnKind.Bytes
            case "UUID"                                                => SqlRow.ColumnKind.Uuid
            case "JSON"                                                => SqlRow.ColumnKind.Json
            case "DATE"                                                => SqlRow.ColumnKind.Date
            case "TIME"                                                => SqlRow.ColumnKind.Time
            case "TIMETZ"                                              => SqlRow.ColumnKind.TimeWithOffset
            case "DATETIME"                                            => SqlRow.ColumnKind.DateTime
            case "TIMESTAMP"                                           => SqlRow.ColumnKind.Timestamp
            case "INTERVAL" | "DURATION"                               => SqlRow.ColumnKind.Interval
            case "ARRAY"                                               => SqlRow.ColumnKind.Array
            case "TEXT" | "VARCHAR" | "CHAR" | "CHARACTER" | "CLOB"    => SqlRow.ColumnKind.Text
            case _                                                     => SqlRow.ColumnKind.Unknown

    /** The scale a `DECIMAL(p, s)` or `DECIMAL TEXT(p, s)` declares, which SQLite itself does not apply. */
    def declaredScale(decl: String): Maybe[Int] =
        val open = decl.indexOf('(')
        if open < 0 then Absent
        else
            val close = decl.indexOf(')', open)
            if close < 0 then Absent
            else
                decl.substring(open + 1, close).split(',') match
                    case Array(_, s) => s.trim.toIntOption.fold(Absent)(Present(_))
                    case _           => Absent
            end if
        end if
    end declaredScale

    /** The temporal kinds, built from the wire FIELDS because the neutral values hold what `java.time` cannot: a date counts its year
      * within an era rather than as a negative number, and a time is a signed span reaching past a day in both directions.
      */
    def temporal(kind: SqlRow.ColumnKind, text: String): SqlValue =
        kind match
            case SqlRow.ColumnKind.Date =>
                val (y, m, d, bc) = SqliteText.dateFields(text)
                SqlValue.Date(y, m, d, bc)
            case SqlRow.ColumnKind.Time =>
                val negative           = text.startsWith("-")
                val body               = if negative then text.substring(1) else text
                val (h, mi, s, micros) = SqliteText.timeFields(body)
                SqlValue.Time(negative, h, mi, s, micros)
            case SqlRow.ColumnKind.TimeWithOffset =>
                val (body, offsetSeconds) = SqliteText.splitOffset(text)
                val (h, mi, s, micros)    = SqliteText.timeFields(body)
                SqlValue.TimeWithOffset(h.toInt, mi, s, micros, offsetSeconds)
            case SqlRow.ColumnKind.DateTime =>
                val (y, mo, d, bc, h, mi, s, micros) = SqliteText.dateTimeFields(text)
                SqlValue.DateTime(y, mo, d, bc, h, mi, s, micros)
            case SqlRow.ColumnKind.Interval =>
                val (months, days, micros) = SqliteText.intervalFields(text)
                SqlValue.Interval(months, days, micros)
            case other => throw new IllegalArgumentException(s"not a temporal kind: $other")
        end match
    end temporal

end SqliteRowCodec
