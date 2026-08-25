package kyo.internal.mysql
import kyo.Chunk
import kyo.Frame
import kyo.Instant
import kyo.Span
import kyo.SqlCodec
import kyo.SqlRequestDurationOverflowException
import kyo.SqlRequestPeriodOverflowException
import kyo.SqlSchema
import kyo.SqlUnsupportedCustomTypeException
import kyo.SqlUnsupportedTypeOnBackendException
import kyo.internal.mysql.types.MysqlEncoder

/** Maps `Codec.Writer` primitive calls to MySQL [[BoundMysqlParam]] instances.
  *
  * Each primitive method appends one [[BoundMysqlParam]] to an internal buffer. The MySQL type byte and wire-format selection mirrors the
  * existing [[MysqlEncoder]] singletons exactly: the byte output is byte-for-byte identical to calling those encoders directly.
  *
  * After all writes, retrieve the accumulated params via [[params]].
  */
final class MysqlParamWriter()(using frame: Frame) extends SqlCodec.Writer(frame):

    private val _params = Chunk.newBuilder[BoundMysqlParam[?]]

    /** Returns the accumulated bound parameters in the order they were written. Drains the accumulator: a second read answers empty, so
      * the caller collects once per statement.
      */
    def params: Chunk[BoundMysqlParam[?]] = _params.result()

    // --- Inline encoders for types with no named MysqlEncoder singleton ---

    // Byte → TYPE_TINY (1 byte). No MysqlEncoder.byteEncoder singleton exists.
    private val byteEncoder: MysqlEncoder[Byte] = new MysqlEncoder[Byte]:
        def mysqlType: Int = MysqlEncoder.TYPE_TINY
        def write(value: Byte, buf: MysqlBufferWriter): Unit =
            buf.writeUInt8(value.toInt)

    // --- Primitive value methods ---

    override def boolean(value: Boolean): Unit =
        _params += BoundMysqlParam(value, MysqlEncoder.boolEncoder)

    override def short(value: Short): Unit =
        _params += BoundMysqlParam(value, MysqlEncoder.shortEncoder)

    override def int(value: Int): Unit =
        _params += BoundMysqlParam(value, MysqlEncoder.intEncoder)

    override def long(value: Long): Unit =
        _params += BoundMysqlParam(value, MysqlEncoder.longEncoder)

    override def float(value: Float): Unit =
        _params += BoundMysqlParam(value, MysqlEncoder.floatEncoder)

    override def double(value: Double): Unit =
        _params += BoundMysqlParam(value, MysqlEncoder.doubleEncoder)

    /** Encodes a `BigDecimal` using MySQL's `DECIMAL` / `NEWDECIMAL` wire type (`TYPE_NEWDECIMAL`, `0xf6`).
      *
      * MySQL transmits `DECIMAL` values as a length-prefixed decimal string in the binary protocol, not as a fixed-point binary
      * representation. `bigDecimalEncoder` handles the string serialisation. This is the same wire format used for `NUMERIC` columns and is
      * distinct from the PostgreSQL approach (which uses OID-tagged text or binary `NUMERIC`). The encoding is exact and round-trips
      * without loss for all `BigDecimal` values that fit within MySQL's `DECIMAL(65, 30)` precision limits.
      */
    override def bigDecimal(value: BigDecimal): Unit =
        _params += BoundMysqlParam(value, MysqlEncoder.bigDecimalEncoder)

    override def string(value: String): Unit =
        _params += BoundMysqlParam(value, MysqlEncoder.stringEncoder)

    override def bytes(value: Span[Byte]): Unit =
        _params += BoundMysqlParam(value, MysqlEncoder.bytesEncoder)

    override def instant(value: java.time.Instant): Unit =
        // instantEncoder takes kyo.Instant; convert at the boundary.
        _params += BoundMysqlParam(Instant.fromJava(value), MysqlEncoder.instantEncoder)

    /** Encodes a `Byte` using MySQL's native `TINYINT` wire type (`TYPE_TINY`, `0x01`).
      *
      * Unlike PostgreSQL (which widens `Byte` to `INT2` because it has no single-byte OID), MySQL provides `TYPE_TINY` as a first-class
      * wire type. The value is transmitted as a single unsigned or signed byte depending on the column definition. No widening is applied;
      * the value range `[-128, 127]` maps directly. If the target column is `TINYINT UNSIGNED`, values `[128, 255]` must be supplied as a
      * wider Scala type and handled via a custom codec.
      */
    override def byte(value: Byte): Unit =
        _params += BoundMysqlParam(value, byteEncoder)

    /** Encodes a `Char` as a single-character MySQL `VAR_STRING` (`TYPE_VAR_STRING`, `0xfd`) via `stringEncoder`.
      *
      * MySQL has no dedicated single-character wire type. The `Char` is promoted to a one-element `String` and transmitted as a
      * length-prefixed UTF-8 string, which is accepted by `CHAR(1)`, `VARCHAR(n >= 1)`, and `TEXT` columns alike. An empty (null) `Char`
      * (Unicode U+0000) encodes as a one-byte string containing the NUL character, not as an SQL NULL; use `nil()` for SQL NULL. A Scala
      * `Char` is always exactly one UTF-16 code unit, so the resulting string is never longer than two UTF-8 bytes.
      */
    override def char(value: Char): Unit =
        _params += BoundMysqlParam(value.toString, MysqlEncoder.stringEncoder)

    /** Encodes a `BigInt` as MySQL `DECIMAL` (`TYPE_NEWDECIMAL`) by promoting it to `BigDecimal` first.
      *
      * MySQL has no dedicated arbitrary-precision integer wire type. The `BigInt` is promoted to `BigDecimal` (an exact, lossless
      * conversion) and then serialised as a length-prefixed decimal string via `bigDecimalEncoder`, identical to calling
      * `bigDecimal(BigDecimal(value))`. The target column is expected to be `DECIMAL` or `NUMERIC`; storing in a `BIGINT` column may
      * trigger implicit truncation if the value exceeds `INT64` range.
      */
    override def bigInt(value: BigInt): Unit =
        _params += BoundMysqlParam(BigDecimal(value), MysqlEncoder.bigDecimalEncoder)

    override def duration(value: java.time.Duration): Unit =
        // Guard against day-count overflow before the encoder writes bytes.
        // Duration.toDays() returns getSeconds()/86400; check eagerly so the caller
        // receives a typed leaf rather than an unchecked ArithmeticException.
        val abs       = if value.isNegative then value.negated() else value
        val totalDays = abs.toDays
        if totalDays > Int.MaxValue.toLong then
            throw SqlRequestDurationOverflowException(totalDays, "the MySQL TIME day-count range")
        end if
        _params += BoundMysqlParam(value, MysqlEncoder.durationEncoder)
    end duration

    /** Writes a NULL parameter slot to the MySQL `COM_STMT_EXECUTE` message.
      *
      * `nil()` carries no type information. In MySQL's binary protocol, NULL parameters are tracked via a null bitmap embedded in the
      * `COM_STMT_EXECUTE` packet; the type byte for a NULL slot is never read by the server. `boolEncoder` is used solely as a sentinel to
      * satisfy the `MysqlEncoder[?]` type parameter: its type byte (`TYPE_TINY`, `0x01`) has no semantic effect for NULL slots. Unlike
      * PostgreSQL, MySQL does not use the type hint for planner optimisation, so the sentinel choice does not affect query plans.
      */
    override def nil(): Unit =
        _params += BoundMysqlParam.nullParam(MysqlEncoder.boolEncoder)

    // --- SQL type vocabulary ---
    //
    // Each method names the SQL type the value IS and picks the MySQL wire form for it. Types MySQL has
    // no native column for (UUID, calendar interval, arrays) go out as strings, which is what the
    // corresponding reader methods parse back.

    override def json(text: String): Unit =
        _params += BoundMysqlParam(text, MysqlEncoder.jsonEncoder)

    override def uuid(value: java.util.UUID): Unit =
        string(value.toString)

    override def date(value: java.time.LocalDate): Unit =
        _params += BoundMysqlParam(value, MysqlEncoder.localDateEncoder)

    override def time(value: java.time.LocalTime): Unit =
        _params += BoundMysqlParam(value, MysqlEncoder.localTimeEncoder)

    override def timeWithOffset(value: java.time.OffsetTime): Unit =
        // MySQL has no offset-carrying time column; ISO-8601 text keeps the offset.
        string(value.toString)

    override def dateTime(value: java.time.LocalDateTime): Unit =
        _params += BoundMysqlParam(value, MysqlEncoder.localDateTimeEncoder)

    override def calendarInterval(value: java.time.Period): Unit =
        // MySQL has no calendar-interval column; ISO-8601 text keeps months and days.
        //
        // The normalisation is what keeps the column's values comparable, and it is PostgreSQL's own. There the
        // wire form carries a single month count, so `Period.of(1, 2, 0)` and `Period.ofMonths(14)` are literally
        // the same stored value; without normalisation they would store as the distinct strings "P1Y2M" and "P14M",
        // so a predicate matching one would miss the other on MySQL while matching both on PostgreSQL.
        // `normalized` carries years and months into the canonical pair and leaves days untouched, which is the
        // same collapse PostgreSQL's month count performs, and it pairs with the `normalized` on the read side.
        //
        // Guard against the year-carry overflow before normalized() computes it, the same reason the duration
        // guard above exists: normalized() narrows years*12L+months back into an Int, and a directly constructed
        // Period.of(y, m, d), reachable through the public bind API, carries no bound the way Period.between over
        // LocalDate values does (LocalDate's own year range keeps that quotient well inside Int), so the quotient
        // can land outside Int range and throw an unchecked ArithmeticException.
        val totalMonths = value.getYears.toLong * 12L + value.getMonths.toLong
        val splitYears  = totalMonths / 12L
        if splitYears.toInt != splitYears then
            throw SqlRequestPeriodOverflowException(totalMonths, "the Int year range Period.normalized() narrows into")
        end if
        string(value.normalized().toString)
    end calendarInterval

    override def arrayOfInt(values: Chunk[Int]): Unit =
        string(MysqlJsonArray.encodeInts(values))

    override def arrayOfString(values: Chunk[String]): Unit =
        string(MysqlJsonArray.encodeStrings(values))

    override def arrayOfJson(values: Chunk[String]): Unit =
        // Each element is already a JSON document, so the array is their concatenation with separators.
        string(values.mkString("[", ",", "]"))

    /** This backend implements no extension types, so every payload names a type it cannot write.
      *
      * MySQL does have types of its own that would go through this channel, `GEOMETRY`, `SET` and the spatial family among them; none is
      * implemented here, which is a property of the backend rather than of the engine.
      *
      * @throws kyo.SqlUnsupportedTypeOnBackendException
      *   when the payload belongs to another dialect.
      * @throws SqlUnsupportedCustomTypeException
      *   when the payload claims MySQL, for which no extension type is registered.
      */
    override def extension(payload: SqlCodec.Writer.Payload): Unit =
        given Frame = frame
        if payload.dialect != MysqlEncoder.dialectId then
            throw SqlUnsupportedTypeOnBackendException(payload.dialect, payload.typeName, MysqlEncoder.dialectId)
        end if
        throw SqlUnsupportedCustomTypeException(payload.typeName)
    end extension

    /** Encodes one column of `schema` for a composite payload that inlines its elements.
      *
      * MySQL's bound parameters are binary by construction: [[BoundMysqlParam.encoded]] has no format to choose. So a demand for the text
      * form is refused rather than answered with binary bytes the composite's reader would parse as text.
      */
    override def encodeElement[A](column: SqlSchema.Column[A], value: A, typeName: String, format: SqlCodec.Format): Span[Byte] =
        given Frame = frame
        encodeSingleElement(typeName, MysqlParamWriter.write(column, value), format, MysqlEncoder.dialectId)(
            _.encoded,
            _ => format == SqlCodec.Format.Binary
        )
    end encodeElement

end MysqlParamWriter

object MysqlParamWriter:

    /** Encodes `value` as the MySQL bind parameters `column` describes.
      *
      * One [[BoundMysqlParam]] per scalar field: structural framing methods are no-ops on [[MysqlParamWriter]], so a case class flattens into
      * a positional parameter list. This is the single write entry point for the MySQL backend, used by the client backend and by the
      * wire-encoding suites alike.
      */
    private[kyo] def write[A](column: SqlSchema.Column[A], value: A)(using Frame): Chunk[BoundMysqlParam[?]] =
        val writer = new MysqlParamWriter()
        column.write(value, writer)
        writer.params
    end write

end MysqlParamWriter
