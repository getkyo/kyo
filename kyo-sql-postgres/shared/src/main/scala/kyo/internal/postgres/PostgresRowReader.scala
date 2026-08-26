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

    /** The name of the column the cursor is on, so a refusal can say which field of a row type disagreed. Absent past the end and for a
      * row carrying fewer column descriptions than values.
      */
    private def currentName(): Maybe[String] =
        if idx < row.columns.size then Maybe(row.columns(idx).name) else Maybe.empty

    /** Reads the current column: hands its bytes and OID to the per-type decoder and advances the cursor. */
    private inline def readPrimitive[A](inline fromColumn: (Span[Byte], Int) => A): A =
        val oid = currentOid()
        fromColumn(nextBytes(), oid)
    end readPrimitive

    /** Reads the current column at a numeric target, refusing a named column outside the numeric and text families.
      *
      * Separate from [[readDeclared]] because a numeric read legitimately takes more than one column type: every integral width satisfies
      * an `Int`, both float widths satisfy a `Double`, and the text family satisfies any of them. What it must not take is a named column
      * whose layout the decode would misread, which is what
      * [[kyo.internal.postgres.types.PostgresDecoder.requireNumericColumn]] settles.
      */
    private inline def readNumeric[A](scalaType: String, accepts: Set[Int], inline fromColumn: (Span[Byte], Int) => A): A =
        val oid = currentOid()
        PostgresDecoder.requireNumericColumn(scalaType, oid, currentName(), accepts)
        fromColumn(nextBytes(), oid)
    end readNumeric

    /** Reads the current column with a decoder that resolves its wire layout from the target type rather than from the column.
      *
      * Every such read is checked against what the decoder claims, because it cannot fail on its own: handed the wrong column it reads
      * whatever bytes are there at the layout it expects and answers a plausible value. See
      * [[kyo.internal.postgres.types.PostgresDecoder.requireAcceptedColumn]] for what it stays silent on.
      */
    private inline def readDeclared[A](scalaType: String, decoder: PostgresDecoder[A]): A =
        val oid = currentOid()
        PostgresDecoder.requireAcceptedColumn(scalaType, decoder.oids, oid, currentName())
        decoder.read(format, nextBytes(), oid)
    end readDeclared

    override def boolean(): Boolean =
        // A bool column is the target's own type and outside the numeric family, so it is named here; an int4 or
        // numeric column reaching a Boolean field is one of the widenings the numeric set already carries.
        readNumeric("Boolean", PostgresDecoder.numericOrBoolOids, (bytes, oid) => PostgresDecoder.bool.read(format, bytes, oid))

    override def short(): Short =
        readNumeric("Short", PostgresDecoder.numericFamilyOids, (bytes, oid) => PostgresDecoder.int2.read(format, bytes, oid))

    override def int(): Int =
        readNumeric("Int", PostgresDecoder.numericFamilyOids, (bytes, oid) => PostgresDecoder.int4.read(format, bytes, oid))

    override def long(): Long =
        readNumeric("Long", PostgresDecoder.numericFamilyOids, (bytes, oid) => PostgresDecoder.int8.read(format, bytes, oid))

    override def float(): Float =
        readNumeric("Float", PostgresDecoder.numericFamilyOids, (bytes, oid) => PostgresDecoder.float4.read(format, bytes, oid))

    override def double(): Double =
        readNumeric("Double", PostgresDecoder.numericFamilyOids, (bytes, oid) => PostgresDecoder.float8.read(format, bytes, oid))

    override def string(): String =
        // The column's OID goes to the decoder: a text read is the one read every wire type's bytes satisfy, so the
        // decoder refuses a column whose type is not text rather than answering the protocol buffer as UTF-8.
        readPrimitive((bytes, oid) => PostgresDecoder.textDecoder.read(format, bytes, oid))

    override def bytes(): Span[Byte] =
        readDeclared("Span[Byte]", PostgresDecoder.bytea)

    override def bigDecimal(): BigDecimal =
        // Use the row's wire format: Binary for extended-protocol results, Text for simple-query results.
        readNumeric("BigDecimal", PostgresDecoder.numericFamilyOids, (bytes, oid) => PostgresDecoder.numeric.read(format, bytes, oid))

    override def instant(): java.time.Instant =
        // timestamptz decoder returns kyo.Instant; convert at the boundary.
        readDeclared("Instant", PostgresDecoder.timestamptz).toJava

    override def byte(): Byte =
        // PG has no single-byte integer type, so a Byte field travels as int2 and comes back from a column at least
        // twice as wide as the field. `readByte` range-checks the narrowing instead of wrapping it.
        readNumeric("Byte", PostgresDecoder.numericFamilyOids, (bytes, oid) => PostgresDecoder.readByte(format, bytes, oid))

    override def char(): Char =
        // char is encoded as a 1-char text string; take the first character. A longer value is refused rather than
        // truncated to its first character: truncating to `s.charAt(0)` would drop the rest silently, a value change
        // rather than a decode. The column-type guard runs first so a non-text column is named against `Char`, the
        // type that actually asked, rather than against the `String` the text decoder reads on the way there.
        PostgresDecoder.requireTextColumn("Char", currentOid(), currentName())
        val s = PostgresDecoder.textDecoder.read(format, nextBytes())
        if s.isEmpty then throw SqlDecodeEmptyStringForCharException(Maybe(idx - 1))
        else if s.length > 1 then throw SqlDecodeMultiCharacterForCharException(Maybe(idx - 1), s.length)
        else s.charAt(0)
    end char

    override def bigInt(): BigInt =
        // Use the row's wire format: Binary for extended-protocol results, Text for simple-query results.
        // `readBigInt` rejects a fractional value rather than truncating it, which `.toBigInt` would do silently.
        readNumeric("BigInt", PostgresDecoder.numericFamilyOids, (bytes, oid) => PostgresDecoder.readBigInt(format, bytes, oid))

    override def duration(): java.time.Duration =
        readDeclared("Duration", PostgresDecoder.interval)

    // --- SQL type vocabulary ---
    //
    // Each method consumes the next column and decodes it with the PostgreSQL codec for the SQL type
    // the schema asked for, in the wire format the row carries. Unlike the primitive reads above these
    // never delegate to a sub-reader: none of these types appears as an element inside a PG binary array
    // or a JSON document.

    override def nextJson(): String =
        readDeclared("JsonText", PostgresDecoder.jsonDecoder)

    override def nextUuid(): java.util.UUID =
        readDeclared("UUID", PostgresDecoder.uuid)

    override def nextDate(): java.time.LocalDate =
        readDeclared("LocalDate", PostgresDecoder.date)

    override def nextTime(): java.time.LocalTime =
        readDeclared("LocalTime", PostgresDecoder.time)

    override def nextTimeWithOffset(): java.time.OffsetTime =
        readDeclared("OffsetTime", PostgresDecoder.timetz)

    override def nextDateTime(): java.time.LocalDateTime =
        readDeclared("LocalDateTime", PostgresDecoder.timestamp)

    override def nextCalendarInterval(): java.time.Period =
        readDeclared("Period", PostgresDecoder.intervalPeriod)

    override def nextArrayOfInt(): Chunk[Int] =
        readDeclared("Chunk[Int]", PostgresDecoder.int4Array)

    override def nextArrayOfString(): Chunk[String] =
        readDeclared("Chunk[String]", PostgresDecoder.textArray)

    override def nextArrayOfJson(): Chunk[String] =
        readDeclared("Chunk[JsonText]", PostgresDecoder.jsonbArray)

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
