package kyo.internal.sqlite

import kyo.*
import kyo.db.Idiom
import kyo.internal.SqlJsonArray

/** Consumes a SQLite row's columns positionally, decoding each from the text SQLite returned.
  *
  * Every type the driver does not store natively is stored as the text the renderer wrote, so there is no binary arm here. [[SqliteText]]
  * holds the parsers because the codec needs the same ones.
  *
  * The null contract is the SPI's: [[isNil]] consumes a NULL column and leaves a non-null one for the read that follows.
  *
  * @param values
  *   the row's columns, [[Absent]] for a NULL
  * @param matchesFieldAt
  *   the field matcher for a by-name whole-row decode, [[Absent]] for a positional one
  */
final private[sqlite] class SqliteRowReader(
    values: Chunk[Maybe[Span[Byte]]],
    columns: Chunk[SqlRow.Column],
    declaredAt: Int => Maybe[String],
    matchesFieldAt: Maybe[(Int, String) => Boolean],
    frame0: Frame
) extends SqlCodec.Reader(frame0):

    private var cursor = 0

    // --- Cursor ---

    private def nextBytes(): Span[Byte] =
        if cursor >= values.size then decodeFail(s"the row has ${values.size} columns and column $cursor was asked for")
        val value = values(cursor)
        cursor += 1
        value match
            case Present(bytes) => bytes
            // Reached only when a caller read a value without asking isNil first, which the SPI allows.
            case Absent => throw SqlDecodeColumnAbsentException(cursor - 1)(using frame)
        end match
    end nextBytes

    private def nextText(): String = SqliteText.utf8(nextBytes())

    private def decodeFail(message: String): Nothing =
        throw SqlDecodeColumnDecodeException(Present(cursor), new Exception(message))(using frame)

    /** Runs a parse, turning whatever it throws into a decode failure naming the column it was reading. */
    private def parsing[A](text: String)(parse: String => A): A =
        try parse(text)
        catch
            case e: SqlException => throw e
            case e: Throwable if scala.util.control.NonFatal(e) =>
                throw SqlDecodeColumnDecodeException(Present(cursor - 1), e)(using frame)

    override def isNil(): Boolean =
        if cursor >= values.size then decodeFail(s"the row has ${values.size} columns and column $cursor was asked for")
        if values(cursor).isEmpty then
            cursor += 1
            true
        else false
        end if
    end isNil

    override def skip(): Unit = cursor += 1

    // --- Scalar primitives ---

    /** Refuses a column the DDL declared as something other than text.
      *
      * SQLite would answer this happily, because every value can be rendered as text and a declared type does not constrain what a row
      * holds: reading an INTEGER column as `String` returns `"42"` here and is a caller's mistake on every other engine.
      */
    override def string(): String =
        requireDeclared("String", SqliteRowReader.readableAsString)
        nextText()
    end string

    /** The kind the column at the cursor was declared as, or [[Absent]] where it has no declared type. */
    private def declaredKind(): Maybe[SqlRow.ColumnKind] =
        if cursor >= columns.size then Maybe.empty
        else declaredAt(columns(cursor).typeToken).map(SqliteRowCodec.kindOf)

    /** Refuses the column at the cursor when its DECLARED kind is one `accepts` rejects.
      *
      * A column traceable to no declared type is never refused: an expression or a CAST has no declared type on any engine, so refusing one
      * would reject code that is correct.
      */
    private def requireDeclared(scalaType: String, accepts: SqlRow.ColumnKind => Boolean): Unit =
        declaredKind() match
            case Present(kind) if !accepts(kind) =>
                val decl = declaredAt(columns(cursor).typeToken).getOrElse("")
                throw SqlDecodeColumnTypeMismatchException(
                    scalaType,
                    SqliteRowReader.DialectId,
                    decl,
                    decl,
                    Present(columns(cursor).name)
                )(using frame)
            case _ => ()

    // The bounded integrals range-check rather than letting `toInt` and friends throw, because those throw the SAME
    // NumberFormatException for malformed text and for a number that does not fit, which are different failures to a
    // caller. SQLite reaches the second on its own: its integers are 64-bit and it widens an overflowing sum instead
    // of refusing it, so an Int-declared column can hand back a well-formed value larger than Int.
    override def int(): Int     = bounded(Int.MinValue, Int.MaxValue, "Int").toInt
    override def short(): Short = bounded(Short.MinValue, Short.MaxValue, "Short").toShort
    override def byte(): Byte   = bounded(Byte.MinValue, Byte.MaxValue, "Byte").toByte

    override def long(): Long     = numeric(nextText())(_.trim.toLong)
    override def float(): Float   = numeric(nextText())(SqliteRowReader.toFloat)
    override def double(): Double = numeric(nextText())(SqliteRowReader.toDouble)

    /** Runs a numeric parse, reporting a failure as a NUMERIC decode error naming the text that would not parse.
      *
      * Distinct from [[parsing]] because text that is not a number is a different failure from a column whose declared type was never
      * numeric.
      */
    private def numeric[A](text: String)(parse: String => A): A =
        try parse(text)
        catch
            case e: SqlException => throw e
            case e: Throwable if scala.util.control.NonFatal(e) =>
                throw SqlDecodeNumericException(text, SqlDecodeNumericException.Subtype.Parse)(using frame)

    /** Reads an integral column and checks it against `[min, max]`, naming which of the two failures happened.
      *
      * Parsed as BigInt rather than Long so a value past Long's own range is still reported as out of range rather than as malformed text.
      */
    private def bounded(min: Long, max: Long, scalaType: String): Long =
        val text   = nextText()
        val parsed = numeric(text)(t => BigInt(t.trim))
        if parsed < BigInt(min) || parsed > BigInt(max) then
            throw SqlDecodeValueRangeException(scalaType, parsed.toString, "SQLite INTEGER")(using frame)
        parsed.toLong
    end bounded

    override def boolean(): Boolean = parsing(nextText())(SqliteText.boolean)

    override def char(): Char =
        val text = nextText()
        if text.length == 1 then text.charAt(0)
        else decodeFail(s"expected a single character, got ${text.length}")
    end char

    override def bigDecimal(): BigDecimal = numeric(nextText())(t => BigDecimal(t.trim))
    override def bigInt(): BigInt         = numeric(nextText())(t => BigInt(t.trim))

    /** The column's bytes as they stand, which for a BLOB is the value itself and for text is its UTF-8. */
    override def bytes(): Span[Byte] = nextBytes()

    override def instant(): java.time.Instant =
        requireDeclared("Instant", SqliteRowReader.readableAsTemporal)
        val text = nextText()
        parsing(text) { t =>
            val (epochSecond, micros) = SqliteText.timestampFields(t)
            java.time.Instant.ofEpochSecond(epochSecond, micros.toLong * 1000L)
        }
    end instant

    /** A duration is written as an interval, so it is read as one and folded back to a length of time.
      *
      * Months are refused rather than approximated, since a month is not a fixed number of seconds.
      */
    override def duration(): java.time.Duration =
        val text = nextText()
        parsing(text) { t =>
            val (months, days, micros) = SqliteText.intervalFields(t)
            if months != 0 then decodeFail(s"a duration cannot carry months, and '$t' does")
            java.time.Duration.ofDays(days).plusNanos(micros * 1000L)
        }
    end duration

    // --- SQL type vocabulary ---

    override def nextJson(): String = nextText()

    override def nextUuid(): java.util.UUID =
        val text = nextText()
        parsing(text)(java.util.UUID.fromString)

    override def nextDate(): java.time.LocalDate =
        requireDeclared("LocalDate", SqliteRowReader.readableAsTemporal)
        val text = nextText()
        parsing(text) { t =>
            val (year, month, day, bc) = SqliteText.dateFields(t)
            // A proleptic year counts an era-BC year as 1 - year, which is what LocalDate holds and not what the
            // rendering carries: 44 BC is written 0044 with an era rather than -43.
            java.time.LocalDate.of(if bc then 1 - year else year, month, day)
        }
    end nextDate

    override def nextTime(): java.time.LocalTime =
        requireDeclared("LocalTime", SqliteRowReader.readableAsTemporal)
        val text = nextText()
        parsing(text) { t =>
            val (hours, minutes, seconds, micros) = SqliteText.timeFields(t)
            if hours > 23 || t.startsWith("-") then
                // The column is a signed SPAN reaching past a day in both directions; a time of day does not. The
                // endpoints are real values on the other engines, so this refuses rather than wrapping them.
                decodeFail(s"'$t' is a span rather than a time of day, and does not fit a LocalTime")
            end if
            java.time.LocalTime.of(hours.toInt, minutes, seconds, micros * 1000)
        }
    end nextTime

    override def nextTimeWithOffset(): java.time.OffsetTime =
        requireDeclared("OffsetTime", SqliteRowReader.readableAsTemporal)
        val text = nextText()
        parsing(text) { t =>
            val (body, offsetSeconds)             = SqliteText.splitOffset(t)
            val (hours, minutes, seconds, micros) = SqliteText.timeFields(body)
            java.time.OffsetTime.of(
                java.time.LocalTime.of(hours.toInt, minutes, seconds, micros * 1000),
                java.time.ZoneOffset.ofTotalSeconds(offsetSeconds)
            )
        }
    end nextTimeWithOffset

    override def nextDateTime(): java.time.LocalDateTime =
        requireDeclared("LocalDateTime", SqliteRowReader.readableAsTemporal)
        val text = nextText()
        parsing(text) { t =>
            val (year, month, day, bc, hours, minutes, seconds, micros) = SqliteText.dateTimeFields(t)
            java.time.LocalDateTime.of(if bc then 1 - year else year, month, day, hours, minutes, seconds, micros * 1000)
        }
    end nextDateTime

    /** A calendar span, which is the months and days of an interval and none of its time part.
      *
      * A `Period` has no hours, so an interval carrying them is refused rather than truncated.
      */
    override def nextCalendarInterval(): java.time.Period =
        val text = nextText()
        parsing(text) { t =>
            val (months, days, micros) = SqliteText.intervalFields(t)
            if micros != 0 then decodeFail(s"a calendar interval cannot carry a time part, and '$t' does")
            java.time.Period.of((months / 12).toInt, (months % 12).toInt, days.toInt)
        }
    end nextCalendarInterval

    // Arrays are JSON, as on MySQL: neither engine has an array type.
    override def nextArrayOfInt(): Chunk[Int]       = SqlJsonArray.decodeInts(nextText())(jsonFail)
    override def nextArrayOfString(): Chunk[String] = SqlJsonArray.decodeStrings(nextText())(jsonFail)
    override def nextArrayOfJson(): Chunk[String]   = SqlJsonArray.elements(nextText())(jsonFail)

    private def jsonFail(message: String): Nothing =
        // The cause carries the same capped text: an uncapped one rides along in getMessage and defeats the cap.
        throw SqlDecodeJsonException(message.take(100), new Exception(message.take(100)))(using frame)

    override def nextExtension(dialect: Idiom.Id, typeName: String): SqlCodec.Reader.Extension =
        if dialect != SqliteRowReader.DialectId then
            throw SqlUnsupportedTypeOnBackendException(dialect, typeName, SqliteRowReader.DialectId)(using frame)
        SqlCodec.Reader.Extension(SqlCodec.Format.Text, nextBytes())
    end nextExtension

    override def decodeElement[A](column: SqlSchema.Column[A], bytes: Span[Byte], format: SqlCodec.Format): A =
        // The token is -1, which no declaration index reaches, so the nested column reads as having no declared type.
        kyo.internal.SqlPositionalRowReader.readSingleValue(
            column,
            bytes,
            -1,
            new SqliteRowCodec(Chunk.empty),
            row => new SqliteRowReader(row.values, row.columns, _ => Maybe.empty, Absent, frame)
        )(using frame)
    end decodeElement

    override def fieldIndex(index: Int, names: Chunk[String]): Int =
        matchesFieldAt match
            case Absent => index
            case Present(matches) =>
                val found = names.indexWhere(name => matches(index, name))
                if found >= 0 then found else index

end SqliteRowReader

private[sqlite] object SqliteRowReader:

    /** Whether a column declared as `kind` may be read as a temporal value.
      *
      * The temporal kinds plus text, which is what this engine stores every one of them in, so a column declared `DATE TEXT` and a column
      * declared `TEXT` are both legitimate sources. It rules out the numeric and boolean kinds, where the stored value would parse into
      * something well-formed and wrong rather than failing.
      */
    def readableAsTemporal(kind: SqlRow.ColumnKind): Boolean =
        kind match
            case SqlRow.ColumnKind.Date | SqlRow.ColumnKind.Time | SqlRow.ColumnKind.TimeWithOffset |
                SqlRow.ColumnKind.DateTime | SqlRow.ColumnKind.Timestamp | SqlRow.ColumnKind.Interval |
                SqlRow.ColumnKind.Text | SqlRow.ColumnKind.Unknown => true
            case _ => false

    /** Whether a column declared as `kind` may be read as a `String`.
      *
      * The textual kinds, and only those. `Unknown` never reaches here, since a column with no declared type is not checked at all.
      */
    def readableAsString(kind: SqlRow.ColumnKind): Boolean =
        kind match
            case SqlRow.ColumnKind.Text | SqlRow.ColumnKind.Json | SqlRow.ColumnKind.Uuid | SqlRow.ColumnKind.Unknown => true
            case _                                                                                                    => false

    val DialectId: Idiom.Id = Idiom.Id("sqlite")

    /** SQLite spells the infinities `Inf` and `-Inf`, which `toDouble` does not read. NaN never arrives: it is refused at bind, since
      * storing it would silently become NULL.
      */
    def toDouble(text: String): Double =
        text.trim match
            case "Inf"  => Double.PositiveInfinity
            case "-Inf" => Double.NegativeInfinity
            case other  => other.toDouble

    def toFloat(text: String): Float =
        text.trim match
            case "Inf"  => Float.PositiveInfinity
            case "-Inf" => Float.NegativeInfinity
            case other  => other.toFloat

end SqliteRowReader
