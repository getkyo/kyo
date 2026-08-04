package kyo.internal.postgres

import java.nio.charset.StandardCharsets
import kyo.Chunk
import kyo.Frame
import kyo.Instant
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Span
import kyo.SqlCodec
import kyo.SqlCodec.Format
import kyo.SqlDecodeArrayAbsentElementException
import kyo.SqlDecodeColumnDecodeException
import kyo.SqlDecodeEmptyStringForCharException
import kyo.SqlDecodeMapAbsentValueException
import kyo.SqlDecodeMultiCharacterForCharException
import kyo.SqlRow
import kyo.SqlSchema
import kyo.SqlUnsupportedTypeOnBackendException
import kyo.db.Idiom
import kyo.internal.SqlPositionalRowReader
import kyo.internal.postgres.types.PostgresDecoder
import kyo.internal.postgres.types.PostgresEncoder

/** Maps `Codec.Reader` primitive calls to column values read positionally from a [[SqlRow]].
  *
  * Each primitive method reads the next column from [[row]] at the current cursor position, advances the cursor by one, and decodes the raw
  * wire bytes using the `format`-aware logic of the corresponding [[PostgresDecoder]] singleton. The format comes from the result set the row
  * belongs to, so that both extended-protocol (binary) and simple-query (text) results decode correctly.
  *
  * Row reads are driven by [[kyo.SqlSchema]]'s row codec: columns are consumed in incoming order and routed to their field slots
  * through the field matcher (see [[kyo.internal.SqlPositionalRowReader.fieldIndex]]); arrays and JSON documents are whole-column
  * reads through the vocabulary (`nextArrayOfInt`, `nextJson`, ...).
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
final class PostgresRowReader(row: SqlRow, format: Format, matchesFieldAt: Maybe[(Int, String) => Boolean] = Absent)(using Frame)
    extends SqlPositionalRowReader(row, matchesFieldAt, summon[Frame]):

    override def isNil(): Boolean =
        // The contract every optional read relies on: a NULL column answers true AND is consumed; a non-null
        // column leaves the cursor for the value read that follows.
        if row.column(idx).isEmpty then
            idx += 1
            true
        else false
    end isNil

    override private[kyo] def currentColumnToken(): Int = currentOid()

    /** The OID of the column the cursor is on, 0 for a row assembled without server metadata. */
    private def currentOid(): Int =
        if idx < row.size then row.columns(idx).typeToken else 0

    /** Reads the current column: hands its bytes and OID to the per-type decoder and advances the cursor. */
    private inline def readPrimitive[A](inline fromColumn: (Span[Byte], Int) => A): A =
        val oid = currentOid()
        fromColumn(nextBytes(), oid)
    end readPrimitive

    override def boolean(): Boolean =
        readPrimitive((bytes, oid) => PostgresDecoder.bool.read(format, bytes, oid))

    override def short(): Short =
        readPrimitive((bytes, oid) => PostgresDecoder.int2.read(format, bytes, oid))

    override def int(): Int =
        readPrimitive((bytes, oid) => PostgresDecoder.int4.read(format, bytes, oid))

    override def long(): Long =
        readPrimitive((bytes, oid) => PostgresDecoder.int8.read(format, bytes, oid))

    override def float(): Float =
        readPrimitive((bytes, oid) => PostgresDecoder.float4.read(format, bytes, oid))

    override def double(): Double =
        readPrimitive((bytes, oid) => PostgresDecoder.float8.read(format, bytes, oid))

    override def string(): String =
        // textDecoder accepts both binary and text format identically (UTF-8 bytes either way).
        PostgresDecoder.textDecoder.read(format, nextBytes())

    override def bytes(): Span[Byte] =
        PostgresDecoder.bytea.read(format, nextBytes())

    override def bigDecimal(): BigDecimal =
        // Use the row's wire format: Binary for extended-protocol results, Text for simple-query results.
        readPrimitive((bytes, oid) => PostgresDecoder.numeric.read(format, bytes, oid))

    override def instant(): java.time.Instant =
        // timestamptz decoder returns kyo.Instant; convert at the boundary.
        PostgresDecoder.timestamptz.read(format, nextBytes()).toJava

    override def byte(): Byte =
        // PG has no single-byte integer type, so a Byte field travels as int2 and comes back from a column at least
        // twice as wide as the field. `readByte` range-checks the narrowing instead of wrapping it.
        readPrimitive((bytes, oid) => PostgresDecoder.readByte(format, bytes, oid))

    override def char(): Char =
        // char is encoded as a 1-char text string; take the first character. A longer value is refused rather than
        // truncated to its first character: truncating to `s.charAt(0)` would drop the rest silently, a value change
        // rather than a decode.
        val s = PostgresDecoder.textDecoder.read(format, nextBytes())
        if s.isEmpty then throw SqlDecodeEmptyStringForCharException(Maybe(idx - 1))
        else if s.length > 1 then throw SqlDecodeMultiCharacterForCharException(Maybe(idx - 1), s.length)
        else s.charAt(0)
    end char

    override def bigInt(): BigInt =
        // Use the row's wire format: Binary for extended-protocol results, Text for simple-query results.
        // `readBigInt` rejects a fractional value rather than truncating it, which `.toBigInt` would do silently.
        readPrimitive((bytes, oid) => PostgresDecoder.readBigInt(format, bytes, oid))

    override def duration(): java.time.Duration =
        PostgresDecoder.interval.read(format, nextBytes())

    // --- SQL type vocabulary ---
    //
    // Each method consumes the next column and decodes it with the PostgreSQL codec for the SQL type
    // the schema asked for, in the wire format the row carries. Unlike the primitive reads above these
    // never delegate to a sub-reader: none of these types appears as an element inside a PG binary array
    // or a JSON document.

    override def nextJson(): String =
        PostgresDecoder.jsonDecoder.read(format, nextBytes())

    override def nextUuid(): java.util.UUID =
        PostgresDecoder.uuid.read(format, nextBytes())

    override def nextDate(): java.time.LocalDate =
        PostgresDecoder.date.read(format, nextBytes())

    override def nextTime(): java.time.LocalTime =
        PostgresDecoder.time.read(format, nextBytes())

    override def nextTimeWithOffset(): java.time.OffsetTime =
        PostgresDecoder.timetz.read(format, nextBytes())

    override def nextDateTime(): java.time.LocalDateTime =
        PostgresDecoder.timestamp.read(format, nextBytes())

    override def nextCalendarInterval(): java.time.Period =
        PostgresDecoder.intervalPeriod.read(format, nextBytes())

    override def nextArrayOfInt(): Chunk[Int] =
        PostgresDecoder.int4Array.read(format, nextBytes())

    override def nextArrayOfString(): Chunk[String] =
        PostgresDecoder.textArray.read(format, nextBytes())

    override def nextArrayOfJson(): Chunk[String] =
        PostgresDecoder.jsonbArray.read(format, nextBytes())

    /** Returns the next column's bytes for a type PostgreSQL owns, in the PostgreSQL wire form, together with which form that is.
      *
      * The format is the one this row was assembled under: PostgreSQL negotiates it per column, so an extension column of a simple-query
      * result is text while the same column of an extended-protocol result is binary, and the read closure branches on the answer.
      *
      * @throws kyo.SqlUnsupportedTypeOnBackendException
      *   when `dialect` is not PostgreSQL.
      */
    override def nextExtension(dialect: Idiom.Id, typeName: String): SqlCodec.Reader.Extension =
        if dialect != PostgresEncoder.dialectId then
            throw SqlUnsupportedTypeOnBackendException(dialect, typeName, PostgresEncoder.dialectId)(using frame)
        end if
        SqlCodec.Reader.Extension(format, nextBytes())
    end nextExtension

    // The format is the caller's, not this row's: the bytes are a slice the caller cut out of a composite payload,
    // and the payload's layout fixes what form its elements are in, which is independent of the form the enclosing
    // column arrived in.
    override def decodeElement[A](column: SqlSchema.Column[A], bytes: Span[Byte], format: SqlCodec.Format): A =
        PostgresRowReader.readValue(column, bytes, format)(using frame)

    // --- Skip ---

    override def skip(): Unit =
        idx += 1

    // The cursor and the field-slot resolution are the positional core shared with the MySQL reader; see
    // [[kyo.internal.SqlPositionalRowReader]].

end PostgresRowReader

object PostgresRowReader:

    /** Decodes a single value from the bytes of one PostgreSQL column.
      *
      * The entry point for reading a value that arrived nested inside another value's payload, a range end being the case that needs it.
      * `schema` must occupy exactly one column; a decode failure is thrown, as everywhere in the reader contract.
      */
    private[kyo] def readValue[A](column: SqlSchema.Column[A], bytes: Span[Byte], format: Format)(using Frame): A =
        SqlPositionalRowReader.readSingleValue(
            column,
            bytes,
            PostgresEncoder.OID_UNSPECIFIED,
            PostgresRowCodec(format),
            new PostgresRowReader(_, format)
        )
    end readValue

end PostgresRowReader
