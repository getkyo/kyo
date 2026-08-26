package kyo.internal.mysql

import java.nio.charset.StandardCharsets
import kyo.<
import kyo.Abort
import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Result
import kyo.Span
import kyo.SqlCodec
import kyo.SqlCodec.Format
import kyo.SqlDecodeColumnTypeMismatchException
import kyo.SqlDecodeEmptyStringForCharException
import kyo.SqlDecodeException
import kyo.SqlDecodeIntervalException
import kyo.SqlDecodeJsonException
import kyo.SqlDecodeMultiCharacterForCharException
import kyo.SqlDecodeTemporalException
import kyo.SqlRow
import kyo.SqlSchema
import kyo.SqlUnsupportedTypeOnBackendException
import kyo.db.Idiom
import kyo.internal.SqlPositionalRowReader
import kyo.internal.mysql.types.MysqlEncoder
import kyo.internal.mysql.types.MysqlTemporalDecoder

/** Maps `Codec.Reader` primitive calls to column values read positionally from a [[kyo.SqlRow]].
  *
  * Each primitive method reads the next column from [[row]] at the current cursor position, advances the cursor by one, and parses the raw
  * wire bytes according to [[format]]. The two formats share no representation for a numeric value: the binary protocol (§14.7.4) writes a
  * fixed-width little-endian integer or IEEE-754 field per column type, and the text protocol writes the value's ASCII rendering. Parsing the
  * ASCII digits of `1234` as a little-endian `LONG` yields 875770417, and the ASCII `0` of a false boolean is byte 0x30, so a format-blind
  * read returns plausible wrong values rather than errors. NULL columns are represented as [[Maybe.Absent]] in [[SqlRow.values]] (the
  * null-bitmap was resolved during row assembly by [[BinaryResultsetRowUnmarshaller]]).
  *
  * Width and signedness come from the column, never from the Scala type. [[MysqlColumnToken]] on each [[SqlRow.Column]] carries the server's
  * type byte and UNSIGNED flag, so an `Int` field over a `BIGINT` widens through `Long` and aborts when the value does not fit, rather than
  * taking the low four bytes, and an `INT UNSIGNED` above 2^31 reads as its magnitude rather than as a negative number.
  *
  * For Schema-derived case class reads, the object-iteration protocol (`objectStart`, `hasNextField`, `fieldParse`, `matchField`,
  * `objectEnd`) walks the row in column order, and [[matchField]] decides which schema field the column at the cursor belongs to by name.
  *
  * MySQL has no native array type, so an array travels as a `TYPE_JSON` column holding a `[…]` document. Each array read consumes that one
  * whole column and parses its text through [[MysqlJsonArray]].
  *
  * @param row
  *   the SQL result row to read from
  * @param format
  *   the wire format the server used for this row's columns
  * @param matchesFieldAt
  *   decides which schema field the column at a given index belongs to, built by the codec from the schema and the row's column names. See
  *   [[kyo.internal.SqlFieldMatcher]]. `Absent` when the caller has no schema in hand (a single-column read, which never drives the
  *   object-iteration protocol), leaving the probe compared against the column name verbatim.
  * @param frame
  *   call-site frame attached to any decode errors
  */
final class MysqlRowReader(row: SqlRow, format: Format, matchesFieldAt: Maybe[(Int, String) => Boolean] = Maybe.Absent)(using Frame)
    extends SqlPositionalRowReader(row, matchesFieldAt, summon[Frame]):

    /** The [[MysqlColumnToken]] for the column the cursor is on, read before [[nextBytes]] advances past it.
      *
      * [[MysqlColumnToken.Unspecified]] for a row assembled without server column metadata, which leaves the numeric reads to resolve the
      * representation from the value's byte width and to treat the column as signed.
      */
    private def currentToken(): Int =
        if idx < row.size then row.columns(idx).typeToken
        else MysqlColumnToken.Unspecified

    override private[kyo] def currentColumnToken(): Int = currentToken()

    private def readUtf8String(bytes: Span[Byte]): String =
        new String(bytes.toArray, StandardCharsets.UTF_8)
    end readUtf8String

    /** Refuses a text read of a column whose type is not text, naming the Scala type that asked.
      *
      * The guard the text reads owe their caller: every other read resolves the wire representation from the column's type byte, and this
      * is the one whose target type any byte sequence satisfies, so without the guard an `INT` column read as `String` answers the four
      * little-endian bytes of the value as UTF-8 rather than failing.
      */
    private def requireTextColumn(scalaType: String): Unit =
        val token = currentToken()
        MysqlColumnToken.nonTextColumnType(token).foreach { columnType =>
            throw SqlDecodeColumnTypeMismatchException(
                scalaType,
                MysqlDialect.id,
                columnType,
                MysqlColumnToken.columnType(token).toString,
                // Named where the reader knows it, so a row type declaring the wrong type for one field of several
                // says which field rather than only which types disagreed.
                if idx < row.columns.size then Maybe(row.columns(idx).name) else Maybe.empty
            )(using frame)
        }
    end requireTextColumn

    // --- Nil check, consumes the column when it answers true ---

    /** True when the column the cursor is on is SQL NULL, consuming that column; false leaves the cursor where it was.
      *
      * The asymmetry is the `Codec.Reader` contract, matching `JsonReader.isNil` and `PostgresRowReader.isNil`, where the reason it is
      * load-bearing for the generated product decoder is spelled out.
      */
    override def isNil(): Boolean =
        if row.column(idx).isEmpty then
            idx += 1
            true
        else false
    end isNil

    // --- Primitive value reads ---

    /** The shared body of the nine numeric-family reads: read the row column through `decode`, given the column's token. */
    private inline def readNumeric[A](inline decode: (Span[Byte], Int) => A): A =
        val token = currentToken()
        decode(nextBytes(), token)
    end readNumeric

    /** MySQL's own truthiness: any nonzero numeric rendering is true. Under the text protocol the false value is the ASCII byte 0x30, which is
      * nonzero as a byte and zero as a number, so the truth of a column comes from its numeric value rather than its first byte.
      */
    override def boolean(): Boolean =
        readNumeric((bytes, token) => MysqlNumericDecoder.boolean(bytes, format, token)(using frame))

    override def short(): Short =
        readNumeric((bytes, token) => MysqlNumericDecoder.short(bytes, format, token)(using frame))

    override def int(): Int =
        readNumeric((bytes, token) => MysqlNumericDecoder.int(bytes, format, token)(using frame))

    override def long(): Long =
        readNumeric((bytes, token) => MysqlNumericDecoder.long(bytes, format, token)(using frame))

    override def float(): Float =
        readNumeric((bytes, token) => MysqlNumericDecoder.float(bytes, format, token)(using frame))

    override def double(): Double =
        readNumeric((bytes, token) => MysqlNumericDecoder.double(bytes, format, token)(using frame))

    override def string(): String =
        requireTextColumn("String")
        readUtf8String(nextBytes())
    end string

    override def bytes(): Span[Byte] =
        nextBytes()

    override def bigDecimal(): BigDecimal =
        readNumeric((bytes, token) => MysqlNumericDecoder.bigDecimal(bytes, format, token)(using frame))

    override def instant(): java.time.Instant =
        // MySQL TIMESTAMP/DATETIME to LocalDateTime to UTC Instant. Under the binary protocol the value is the
        // struct body without its length prefix (consumed by BinaryResultsetRowUnmarshaller); under the text
        // protocol it is `YYYY-MM-DD HH:MM:SS[.ffffff]`, whose length matches no binary struct size, so it is
        // parsed by the text path rather than the struct decoder.
        val bytes = nextBytes()
        val local = MysqlRowReader.decodeDatetime(bytes, format)(using frame)
        local.toInstant(java.time.ZoneOffset.UTC)
    end instant

    override def byte(): Byte =
        readNumeric((bytes, token) => MysqlNumericDecoder.byte(bytes, format, token)(using frame))

    override def char(): Char =
        requireTextColumn("Char")
        val s = readUtf8String(nextBytes())
        // A longer value is refused rather than truncated to its first character: taking `charAt(0)`
        // would drop the rest with nothing raised, a silent value change and not a decode.
        if s.isEmpty then throw SqlDecodeEmptyStringForCharException(Maybe(idx - 1))(using frame)
        else if s.length > 1 then throw SqlDecodeMultiCharacterForCharException(Maybe(idx - 1), s.length)(using frame)
        else s.charAt(0)
    end char

    override def bigInt(): BigInt =
        readNumeric((bytes, token) => MysqlNumericDecoder.bigInt(bytes, format, token)(using frame))

    override def duration(): java.time.Duration =
        MysqlRowReader.decodeDuration(nextBytes(), format)(using frame)

    // --- SQL type vocabulary ---
    //
    // Each method consumes the next column and parses it from the MySQL wire form for the SQL type the
    // schema asked for. Types MySQL has no native column for (UUID, calendar interval, arrays) arrive as
    // strings, matching what MysqlParamWriter emits.

    override def nextJson(): String =
        readUtf8String(nextBytes())

    override def nextUuid(): java.util.UUID =
        java.util.UUID.fromString(readUtf8String(nextBytes()))

    override def nextDate(): java.time.LocalDate =
        MysqlRowReader.decodeDate(nextBytes(), format)(using frame)

    override def nextTime(): java.time.LocalTime =
        MysqlRowReader.decodeLocalTime(nextBytes(), format)(using frame)

    override def nextTimeWithOffset(): java.time.OffsetTime =
        java.time.OffsetTime.parse(readUtf8String(nextBytes()))

    override def nextDateTime(): java.time.LocalDateTime =
        MysqlRowReader.decodeDatetime(nextBytes(), format)(using frame)

    override def nextCalendarInterval(): java.time.Period =
        // `normalized` for the same reason `PostgresDecoder.intervalPeriod` applies it: `Period.equals` compares
        // years, months and days field by field, so a decode that reports a different field split reports a
        // different value. PostgreSQL's wire form carries a single month count and re-expands it normalised, so
        // `Period.ofMonths(14)` comes back as `P1Y2M` from there. Parsing the text back verbatim would return `P14M`,
        // so one value would round-trip to two answers depending on the backend; normalising here keeps them equal.
        //
        // Both `parse` and `normalized` can fail on wire text this reader does not control: `parse` on text that
        // is not a valid ISO-8601 period, `normalized` on a years/months split whose year-carry overflows Int the
        // same way the write-side guard in `MysqlParamWriter.calendarInterval` describes. Left uncaught, either
        // reaches the caller as an unchecked JDK exception (`DateTimeParseException`, `ArithmeticException`)
        // instead of the typed `SqlDecodeIntervalException` the PostgreSQL sibling decode (`PostgresDecoder.intervalPeriod`)
        // already raises for the same two failure modes.
        val text = readUtf8String(nextBytes())
        try java.time.Period.parse(text).normalized()
        catch
            case _: java.time.format.DateTimeParseException =>
                throw SqlDecodeIntervalException("text", text)(using frame)
            case _: ArithmeticException =>
                throw SqlDecodeIntervalException("text", text)(using frame)
        end try
    end nextCalendarInterval

    override def nextArrayOfInt(): Chunk[Int] =
        MysqlJsonArray.decodeInts(readUtf8String(nextBytes()))(jsonFail)

    override def nextArrayOfString(): Chunk[String] =
        MysqlJsonArray.decodeStrings(readUtf8String(nextBytes()))(jsonFail)

    override def nextArrayOfJson(): Chunk[String] =
        MysqlJsonArray.elements(readUtf8String(nextBytes()))(jsonFail)

    private def jsonFail(msg: String): Nothing =
        // The cause carries the same capped text: an uncapped cause would ride along in getMessage and defeat
        // the preview's own truncation.
        throw SqlDecodeJsonException(msg.take(100), new Exception(msg.take(100)))(using frame)

    /** Returns the next column's bytes for a type MySQL owns, together with the format they are in.
      *
      * This backend implements no extension types, so every call names a type it cannot read. MySQL does have types of its own that would
      * come through this channel, `GEOMETRY`, `SET` and the spatial family among them; none is implemented here, which is a property of the
      * backend rather than of the engine. An implementation would answer `Extension(format, nextBytes())` with the row's own format.
      *
      * @throws kyo.SqlUnsupportedTypeOnBackendException
      *   always.
      */
    override def nextExtension(dialect: Idiom.Id, typeName: String): SqlCodec.Reader.Extension =
        throw SqlUnsupportedTypeOnBackendException(dialect, typeName, MysqlEncoder.dialectId)(using frame)

    // The format is the caller's, not this row's: the bytes are a slice cut out of a composite payload, whose layout
    // fixes what form its elements are in, independent of the form the enclosing column arrived in.
    override def decodeElement[A](column: SqlSchema.Column[A], bytes: Span[Byte], format: SqlCodec.Format): A =
        MysqlRowReader.readValue(column, bytes, format)(using frame)

    // --- Skip ---

    override def skip(): Unit =
        idx += 1

    // The cursor and the field-slot resolution are the positional core shared with the PostgreSQL reader; see
    // [[kyo.internal.SqlPositionalRowReader]].

end MysqlRowReader

object MysqlRowReader:

    // --- Temporal decodes, one entry point per SQL type, in either wire format ---
    //
    // These are the whole module's MySQL temporal decode. The struct decoders live once in
    // [[kyo.internal.mysql.types.MysqlTemporalDecoder]] and the text parsers live once below, and every caller,
    // this reader's own primitive and vocabulary reads, goes
    // through the pair here. A single decode per struct is what keeps every path answering the same
    // instant for the same wire bytes, including the zero date.
    //
    // The Abort-to-throw conversion is the reader contract: [[kyo.SqlCodec.Reader]] returns plain values and
    // signals failures by throwing, and the backend [[kyo.SqlRow.Codec]] catches at the boundary.

    /** Decodes a MySQL `DATETIME` or `TIMESTAMP` column as a wall-clock date and time. */
    private[kyo] def decodeDatetime(bytes: Span[Byte], format: Format)(using frame: Frame): java.time.LocalDateTime =
        format match
            case Format.Binary => unwrapDecode(MysqlTemporalDecoder.decodeDatetimeBytes(bytes))
            case Format.Text   => parseTextDatetime(new String(bytes.toArray, StandardCharsets.UTF_8))

    /** Decodes a MySQL `DATE` column. */
    private[kyo] def decodeDate(bytes: Span[Byte], format: Format)(using frame: Frame): java.time.LocalDate =
        format match
            case Format.Binary => unwrapDecode(MysqlTemporalDecoder.decodeDateBytes(bytes))
            case Format.Text   => parseTextDatetime(new String(bytes.toArray, StandardCharsets.UTF_8)).toLocalDate

    /** Decodes a MySQL `TIME` column as a time of day, refusing the negative and out-of-day values only [[decodeDuration]] can carry. */
    private[kyo] def decodeLocalTime(bytes: Span[Byte], format: Format)(using frame: Frame): java.time.LocalTime =
        format match
            case Format.Binary => unwrapDecode(MysqlTemporalDecoder.decodeTimeBytes(bytes))
            case Format.Text   => parseTextLocalTime(new String(bytes.toArray, StandardCharsets.UTF_8))

    /** Decodes a MySQL `TIME` column as the signed span it is, which is the mapping that covers its whole range. */
    private[kyo] def decodeDuration(bytes: Span[Byte], format: Format)(using frame: Frame): java.time.Duration =
        format match
            case Format.Binary => unwrapDecode(MysqlTemporalDecoder.decodeDurationBytes(bytes))
            case Format.Text   => parseTextDuration(new String(bytes.toArray, StandardCharsets.UTF_8))

    /** Runs a decoder whose failure channel is `Abort` inside the reader's throwing contract. */
    private def unwrapDecode[A](computed: A < Abort[SqlDecodeException])(using Frame): A =
        Abort.run(computed).eval match
            case Result.Success(v) => v
            case Result.Failure(e) => throw e
            case Result.Panic(t)   => throw t

    /** Parses the text protocol's `DATE`, `DATETIME`, and `TIMESTAMP` rendering: `YYYY-MM-DD` optionally followed by ` HH:MM:SS` and a
      * fractional part of up to six digits.
      *
      * `java.time.LocalDateTime.parse` cannot take it: MySQL separates date and time with a space where ISO-8601 requires `T`, and a bare
      * `DATE` column carries no time part at all. A zero year, month, or day is refused through the same
      * [[kyo.internal.mysql.types.MysqlTemporalDecoder.isZeroDate]] predicate the binary struct decoder consults, which is what keeps the two
      * formats answering alike for a value MySQL permits and `java.time` cannot hold.
      */
    private[kyo] def parseTextDatetime(text: String)(using frame: Frame): java.time.LocalDateTime =
        val trimmed    = text.trim
        val spaceAt    = trimmed.indexOf(' ')
        val datePart   = if spaceAt < 0 then trimmed else trimmed.substring(0, spaceAt)
        val timePart   = if spaceAt < 0 then "" else trimmed.substring(spaceAt + 1)
        val dateFields = datePart.split('-')
        if dateFields.length != 3 then
            throw SqlDecodeTemporalException(0, 0, 0, 0, 0, 0, trimmed.length)
        end if
        try
            val year  = dateFields(0).toInt
            val month = dateFields(1).toInt
            val day   = dateFields(2).toInt
            if MysqlTemporalDecoder.isZeroDate(year, month, day) then
                throw SqlDecodeTemporalException(year, month, day, 0, 0, 0, trimmed.length)
            else if timePart.isEmpty then java.time.LocalDateTime.of(year, month, day, 0, 0, 0)
            else
                val time = parseTextLocalTime(timePart)
                java.time.LocalDateTime.of(java.time.LocalDate.of(year, month, day), time)
            end if
        catch
            case _: NumberFormatException =>
                throw SqlDecodeTemporalException(0, 0, 0, 0, 0, 0, trimmed.length)
            case _: java.time.DateTimeException =>
                throw SqlDecodeTemporalException(0, 0, 0, 0, 0, 0, trimmed.length)
        end try
    end parseTextDatetime

    /** Parses the text protocol's `TIME` rendering as a time of day: `HH:MM:SS` with an optional fractional part.
      *
      * MySQL's `TIME` ranges over -838:59:59 to 838:59:59, and neither a negative value nor an hour above 23 is a time of day, so both are
      * rejected rather than folded into one. [[parseTextDuration]] is the mapping that carries them.
      */
    private[kyo] def parseTextLocalTime(text: String)(using frame: Frame): java.time.LocalTime =
        val (negative, hours, minutes, seconds, nanos) = splitTextTime(text)
        if negative || hours > 23 then
            throw SqlDecodeTemporalException(0, 0, 0, hours, minutes, seconds, text.trim.length)
        end if
        try java.time.LocalTime.of(hours, minutes, seconds, nanos)
        catch
            case _: java.time.DateTimeException =>
                throw SqlDecodeTemporalException(0, 0, 0, hours, minutes, seconds, text.trim.length)
        end try
    end parseTextLocalTime

    /** Parses the text protocol's `TIME` rendering as a signed [[java.time.Duration]], which is the mapping that can carry MySQL's full
      * -838:59:59 to 838:59:59 range including the sign.
      */
    private[kyo] def parseTextDuration(text: String)(using frame: Frame): java.time.Duration =
        val (negative, hours, minutes, seconds, nanos) = splitTextTime(text)
        val positive = java.time.Duration.ofSeconds(hours.toLong * 3600L + minutes.toLong * 60L + seconds.toLong, nanos.toLong)
        if negative then positive.negated() else positive
    end parseTextDuration

    /** Splits `[-]H+:MM:SS[.ffffff]` into its fields. The hour field is not bounded here: MySQL renders up to 838 hours. */
    private def splitTextTime(text: String)(using frame: Frame): (Boolean, Int, Int, Int, Int) =
        val trimmed  = text.trim
        val negative = trimmed.startsWith("-")
        val body     = if negative then trimmed.substring(1) else trimmed
        val dotAt    = body.indexOf('.')
        val hms      = if dotAt < 0 then body else body.substring(0, dotAt)
        val fraction = if dotAt < 0 then "" else body.substring(dotAt + 1)
        val fields   = hms.split(':')
        if fields.length != 3 then
            throw SqlDecodeTemporalException(0, 0, 0, 0, 0, 0, trimmed.length)
        end if
        try
            // MySQL renders at most six fractional digits; pad to nanosecond precision, and truncate anything longer.
            val nanos = if fraction.isEmpty then 0 else (fraction + "000000000").take(9).toInt
            (negative, fields(0).toInt, fields(1).toInt, fields(2).toInt, nanos)
        catch
            case _: NumberFormatException =>
                throw SqlDecodeTemporalException(0, 0, 0, 0, 0, 0, trimmed.length)
        end try
    end splitTextTime

    /** Decodes a single value from the bytes of one MySQL column.
      *
      * The entry point for reading a value that arrived nested inside another value's payload. `schema` must occupy exactly one column; a
      * decode failure is thrown, as everywhere in the reader contract.
      */
    private[kyo] def readValue[A](column: SqlSchema.Column[A], bytes: Span[Byte], format: Format)(using Frame): A =
        SqlPositionalRowReader.readSingleValue(
            column,
            bytes,
            MysqlColumnToken.Unspecified,
            MysqlRowCodec(format),
            new MysqlRowReader(_, format)
        )
    end readValue

end MysqlRowReader
