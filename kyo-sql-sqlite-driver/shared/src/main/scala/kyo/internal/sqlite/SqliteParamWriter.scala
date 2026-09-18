package kyo.internal.sqlite

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.db.Idiom
import kyo.internal.SqlJsonArray
import kyo.internal.SqlValueRender

/** Collects the bind parameters for one statement, in the storage class SQLite will hold each value in.
  *
  * The choice per value is not free: a declared column's AFFINITY rewrites what it is given, so a value bound in the wrong class is
  * converted rather than refused. A value the driver renders as text is BOUND as text, and the declared type in the DDL is what makes the
  * column hold it verbatim. Only the four kinds SQLite genuinely stores land in a non-text class: integers, floats, blobs, and booleans,
  * which it holds as integers. Everything else is the rendering [[kyo.internal.SqlValueRender]] produces, which is what [[SqliteText]]
  * reads back.
  */
final private[sqlite] class SqliteParamWriter(frame0: Frame) extends SqlCodec.Writer(frame0):

    private val collected = Chunk.newBuilder[SqliteParamWriter.Param]

    def params: Chunk[SqliteParamWriter.Param] = collected.result()

    private def add(p: SqliteParamWriter.Param): Unit = discard(collected.addOne(p))

    private def text(value: String): Unit = add(SqliteParamWriter.Param.Text(value))

    // --- Scalar primitives ---

    def string(value: String): Unit = text(value)
    def int(value: Int): Unit       = add(SqliteParamWriter.Param.Integer(value.toLong))
    def long(value: Long): Unit     = add(SqliteParamWriter.Param.Integer(value))
    def short(value: Short): Unit   = add(SqliteParamWriter.Param.Integer(value.toLong))
    def byte(value: Byte): Unit     = add(SqliteParamWriter.Param.Integer(value.toLong))
    def char(value: Char): Unit     = text(value.toString)

    /** A float is widened to the double SQLite stores, which is lossless: the narrowing on the way back recovers the bit pattern. */
    def float(value: Float): Unit = double(value.toDouble)
    def double(value: Double): Unit =
        // NaN is REFUSED rather than bound: sqlite3_bind_double stores it as NULL and reports success, so binding it
        // would turn a value into an absent one silently.
        if java.lang.Double.isNaN(value) then
            throw SqliteNaNNotStorableException()(using frame)
        add(SqliteParamWriter.Param.Real(value))
    end double

    /** SQLite has no boolean type, and a `BOOLEAN` declaration takes NUMERIC affinity, so the value is bound as the integer it will be
      * stored as rather than as the word the renderer writes.
      */
    def boolean(value: Boolean): Unit = add(SqliteParamWriter.Param.Integer(if value then 1L else 0L))

    /** Text, not a double. A decimal bound as a double loses its scale and every digit past a double before the column sees it.
      *
      * Always carrying a decimal point, because text is coerced by what it LOOKS like: `4` parses as an integer, so a stored 10 over a
      * bound 4 divides integrally and answers 2, where `4.0` parses as a real and answers 2.5. The point costs nothing elsewhere, since the
      * value is read back through the column's declared scale.
      */
    def bigDecimal(value: BigDecimal): Unit =
        val rendered = SqlValueRender.decimal(value)
        text(if rendered.contains('.') then rendered else rendered + ".0")
    end bigDecimal

    /** Text likewise: a BigInt reaches past what SQLite's 64-bit integer holds, and an out-of-range bind would silently become a float. */
    def bigInt(value: BigInt): Unit =
        if value.isValidLong then add(SqliteParamWriter.Param.Integer(value.toLong))
        else text(SqlValueRender.integer(value))

    def bytes(value: Span[Byte]): Unit = add(SqliteParamWriter.Param.Blob(value))

    def nil(): Unit = add(SqliteParamWriter.Param.Null)

    // --- SQL type vocabulary ---

    def instant(value: java.time.Instant): Unit =
        text(SqlValueRender.timestamp(value.getEpochSecond, value.getNano / 1000))

    /** A duration is written as an interval, which is the form the reader parses back. */
    def duration(value: java.time.Duration): Unit =
        text(SqlValueRender.interval(0L, 0L, value.toNanos / 1000L))

    def json(value: String): Unit = text(value)

    def uuid(value: java.util.UUID): Unit = text(value.toString)

    def date(value: java.time.LocalDate): Unit =
        // A proleptic year counts an era-BC year as a non-positive number; the rendering counts it WITHIN its era, so
        // 44 BC is written 0044 with an era rather than -43.
        val bc   = value.getYear <= 0
        val year = if bc then 1 - value.getYear else value.getYear
        text(SqlValueRender.date(year, value.getMonthValue, value.getDayOfMonth, bc))
    end date

    def time(value: java.time.LocalTime): Unit =
        text(SqlValueRender.time(false, value.getHour.toLong, value.getMinute, value.getSecond, value.getNano / 1000))

    def timeWithOffset(value: java.time.OffsetTime): Unit =
        val t = value.toLocalTime
        text(SqlValueRender.timeWithOffset(t.getHour, t.getMinute, t.getSecond, t.getNano / 1000, value.getOffset.getTotalSeconds))

    def dateTime(value: java.time.LocalDateTime): Unit =
        val bc   = value.getYear <= 0
        val year = if bc then 1 - value.getYear else value.getYear
        text(SqlValueRender.dateTime(
            year,
            value.getMonthValue,
            value.getDayOfMonth,
            bc,
            value.getHour,
            value.getMinute,
            value.getSecond,
            value.getNano / 1000
        ))
    end dateTime

    def calendarInterval(value: java.time.Period): Unit =
        text(SqlValueRender.interval(value.getYears.toLong * 12 + value.getMonths.toLong, value.getDays.toLong, 0L))

    // Arrays are JSON, as on MySQL: neither engine has an array type.
    def arrayOfInt(values: Chunk[Int]): Unit       = text(SqlJsonArray.encodeInts(values))
    def arrayOfString(values: Chunk[String]): Unit = text(SqlJsonArray.encodeStrings(values))
    def arrayOfJson(values: Chunk[String]): Unit   = text(values.mkString("[", ",", "]"))

    def extension(payload: SqlCodec.Writer.Payload): Unit =
        if payload.dialect != SqliteParamWriter.DialectId then
            throw SqlUnsupportedTypeOnBackendException(payload.dialect, payload.typeName, SqliteParamWriter.DialectId)(using frame)
        add(SqliteParamWriter.Param.Blob(payload.bytes))
    end extension

    def encodeElement[A](column: SqlSchema.Column[A], value: A, typeName: String, format: SqlCodec.Format): Span[Byte] =
        given Frame = frame
        val nested  = new SqliteParamWriter(frame)
        column.write(value, nested)
        encodeSingleElement(typeName, nested.params, format, SqliteParamWriter.DialectId)(
            // One wire format, so a param's bytes are its text unless it is a blob, and every format request is met.
            p => SqliteParamWriter.bytesOf(p),
            _ => true
        )
    end encodeElement

end SqliteParamWriter

private[sqlite] object SqliteParamWriter:

    val DialectId: Idiom.Id = Idiom.Id("sqlite")

    /** One bound value, in the storage class SQLite will hold it in. A class rather than bytes plus a tag, because the bind call differs
      * per class: an integer and a blob of the same bytes are different values.
      */
    enum Param derives CanEqual:
        case Null
        case Integer(value: Long)
        case Real(value: Double)
        case Text(value: String)
        case Blob(value: Span[Byte])
    end Param

    def bytesOf(p: Param): Maybe[Span[Byte]] =
        p match
            case Param.Null           => Absent
            case Param.Integer(value) => Present(Span.from(value.toString.getBytes(StandardCharsets.UTF_8)))
            case Param.Real(value)    => Present(Span.from(SqlValueRender.float8(value).getBytes(StandardCharsets.UTF_8)))
            case Param.Text(value)    => Present(Span.from(value.getBytes(StandardCharsets.UTF_8)))
            case Param.Blob(value)    => Present(value)

end SqliteParamWriter
