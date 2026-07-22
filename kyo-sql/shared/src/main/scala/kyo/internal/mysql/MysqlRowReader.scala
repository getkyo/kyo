package kyo.internal.mysql

import java.nio.charset.StandardCharsets
import kyo.Codec
import kyo.Frame
import kyo.Maybe
import kyo.Span
import kyo.SqlDecodeColumnNullException
import kyo.SqlDecodeDurationException
import kyo.SqlDecodeEmptyStringForCharException
import kyo.SqlDecodeException
import kyo.SqlDecodeInsufficientBytesException
import kyo.SqlDecodeNumericException
import kyo.SqlDecodeTemporalException
import kyo.SqlRow
import kyo.internal.BufferedSqlReader
import kyo.internal.JsonReader
import kyo.internal.SqlReader
import kyo.internal.postgres.types.Format

/** Maps `Codec.Reader` primitive calls to column values read positionally from a [[kyo.SqlRow]].
  *
  * Each primitive method reads the next column from [[row]] at the current cursor position, advances the cursor by one, and parses the raw
  * wire bytes inline using little-endian byte reads identical to the MySQL binary protocol (§14.7.4). NULL columns are represented as
  * [[Maybe.Absent]] in [[SqlRow.values]] (the null-bitmap was resolved during row assembly by [[BinaryResultsetRowUnmarshaller]]).
  *
  * For Schema-derived case class reads, the object-iteration protocol (`objectStart`, `hasNextField`, `fieldParse`, `matchField`,
  * `objectEnd`) is implemented positionally: fields are consumed in the order they appear in the row, matched by column index. The
  * `nameBytes` argument to `matchField` is ignored, the n-th Schema field maps to the n-th result column regardless of column name.
  *
  * Arrays and maps are decoded by reading the column's JSON bytes and delegating to a [[kyo.internal.JsonReader]] sub-reader. MySQL has no
  * native array type; arrays and maps are stored as TYPE_JSON columns containing `[…]` and `{…}` JSON values respectively.
  *
  * @param row
  *   the SQL result row to read from
  * @param frame
  *   call-site frame attached to any decode errors
  */
final class MysqlRowReader(row: SqlRow)(using Frame) extends SqlReader(summon[Frame]):

    private var idx = 0

    // Sub-reader for in-column JSON structural reads (array or map). When non-null, primitive
    // reads are delegated to this reader instead of advancing to the next SQL column.
    // Cleared by arrayEnd() and mapEnd().
    private var jsonSubReader: Codec.Reader | Null = null

    /** Returns the raw bytes for the current column and advances the cursor, or throws if the column is NULL. */
    private def nextBytes(): Span[Byte] =
        val column = row.column(idx)
        idx += 1
        column.getOrElse(throw SqlDecodeColumnNullException(idx - 1)(using frame))
    end nextBytes

    // --- Inline little-endian readers, direct span access, mirrors MysqlBufferReader primitives ---

    private def readInt4LE(bytes: Span[Byte]): Int =
        if bytes.size < 4 then throw SqlDecodeInsufficientBytesException("LONG", 4, bytes.size, 0)(using frame)
        val b0 = bytes(0).toLong & 0xffL
        val b1 = bytes(1).toLong & 0xffL
        val b2 = bytes(2).toLong & 0xffL
        val b3 = bytes(3).toLong & 0xffL
        (b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)).toInt
    end readInt4LE

    private def readInt8LE(bytes: Span[Byte]): Long =
        if bytes.size < 8 then throw SqlDecodeInsufficientBytesException("LONGLONG", 8, bytes.size, 0)(using frame)
        val b0 = bytes(0).toLong & 0xffL
        val b1 = bytes(1).toLong & 0xffL
        val b2 = bytes(2).toLong & 0xffL
        val b3 = bytes(3).toLong & 0xffL
        val b4 = bytes(4).toLong & 0xffL
        val b5 = bytes(5).toLong & 0xffL
        val b6 = bytes(6).toLong & 0xffL
        val b7 = bytes(7).toLong & 0xffL
        b0 | (b1 << 8) | (b2 << 16) | (b3 << 24) | (b4 << 32) | (b5 << 40) | (b6 << 48) | (b7 << 56)
    end readInt8LE

    private def readInt2LE(bytes: Span[Byte]): Short =
        if bytes.size < 2 then throw SqlDecodeInsufficientBytesException("SHORT", 2, bytes.size, 0)(using frame)
        val lo = bytes(0) & 0xff
        val hi = bytes(1) & 0xff
        ((hi << 8) | lo).toShort
    end readInt2LE

    private def readInt1(bytes: Span[Byte]): Int =
        if bytes.size < 1 then throw SqlDecodeInsufficientBytesException("TINY", 1, bytes.size, 0)(using frame)
        bytes(0) & 0xff
    end readInt1

    private def readFloat4LE(bytes: Span[Byte]): Float =
        if bytes.size < 4 then throw SqlDecodeInsufficientBytesException("FLOAT", 4, bytes.size, 0)(using frame)
        java.lang.Float.intBitsToFloat(readInt4LE(bytes))
    end readFloat4LE

    private def readFloat8LE(bytes: Span[Byte]): Double =
        if bytes.size < 8 then throw SqlDecodeInsufficientBytesException("DOUBLE", 8, bytes.size, 0)(using frame)
        java.lang.Double.longBitsToDouble(readInt8LE(bytes))
    end readFloat8LE

    private def readUtf8String(bytes: Span[Byte]): String =
        new String(bytes.toArray, StandardCharsets.UTF_8)
    end readUtf8String

    /** Decodes a MySQL binary TIMESTAMP/DATETIME struct body (length prefix already stripped).
      *
      * Layout (MySQL protocol §14.7.4):
      *   - 0 bytes → zero datetime (represented as 0001-01-01T00:00:00)
      *   - 4 bytes → date only: year(2 LE) | month(1) | day(1), time defaults to midnight
      *   - 7 bytes → date + time: year(2 LE) | month(1) | day(1) | hour(1) | min(1) | sec(1)
      *   - 11 bytes → date + time + micros: above + micros(4 LE)
      *
      * The length prefix is consumed by [[BinaryResultsetRowUnmarshaller.readColumnValue]] via the lenenc path; the bytes stored in
      * [[MysqlRow.values]] are the struct body only.
      */
    private def decodeDatetimeBody(bytes: Span[Byte]): java.time.LocalDateTime =
        bytes.size match
            case 0 =>
                java.time.LocalDateTime.of(1, 1, 1, 0, 0, 0)
            case 4 =>
                val year     = (bytes(0) & 0xff) | ((bytes(1) & 0xff) << 8)
                val month    = bytes(2) & 0xff
                val day      = bytes(3) & 0xff
                val safeYear = if year == 0 then 1 else year
                val safeMon  = if month == 0 then 1 else month
                val safeDay  = if day == 0 then 1 else day
                try java.time.LocalDateTime.of(safeYear, safeMon, safeDay, 0, 0, 0)
                catch
                    case e: java.time.DateTimeException =>
                        throw SqlDecodeTemporalException(year, month, day, 0, 0, 0, 4)(using frame)
                end try
            case 7 =>
                val year     = (bytes(0) & 0xff) | ((bytes(1) & 0xff) << 8)
                val month    = bytes(2) & 0xff
                val day      = bytes(3) & 0xff
                val hour     = bytes(4) & 0xff
                val minute   = bytes(5) & 0xff
                val second   = bytes(6) & 0xff
                val safeYear = if year == 0 then 1 else year
                val safeMon  = if month == 0 then 1 else month
                val safeDay  = if day == 0 then 1 else day
                try java.time.LocalDateTime.of(safeYear, safeMon, safeDay, hour, minute, second)
                catch
                    case e: java.time.DateTimeException =>
                        throw SqlDecodeTemporalException(year, month, day, hour, minute, second, 7)(using frame)
                end try
            case 11 =>
                val year     = (bytes(0) & 0xff) | ((bytes(1) & 0xff) << 8)
                val month    = bytes(2) & 0xff
                val day      = bytes(3) & 0xff
                val hour     = bytes(4) & 0xff
                val minute   = bytes(5) & 0xff
                val second   = bytes(6) & 0xff
                val micros   = (bytes(7) & 0xff) | ((bytes(8) & 0xff) << 8) | ((bytes(9) & 0xff) << 16) | ((bytes(10) & 0xff) << 24)
                val safeYear = if year == 0 then 1 else year
                val safeMon  = if month == 0 then 1 else month
                val safeDay  = if day == 0 then 1 else day
                try java.time.LocalDateTime.of(safeYear, safeMon, safeDay, hour, minute, second, micros * 1000)
                catch
                    case e: java.time.DateTimeException =>
                        throw SqlDecodeTemporalException(year, month, day, hour, minute, second, 11)(using frame)
                end try
            case n =>
                throw SqlDecodeTemporalException(0, 0, 0, 0, 0, 0, n)(using frame)
        end match
    end decodeDatetimeBody

    // --- Nil check, peek without advancing ---

    override def isNil(): Boolean =
        jsonSubReader match
            case null => row.column(idx).isEmpty
            case sub  => sub.isNil()

    // --- Primitive value reads ---

    override def boolean(): Boolean =
        jsonSubReader match
            case null => readInt1(nextBytes()) != 0
            case sub  => sub.boolean()

    override def short(): Short =
        jsonSubReader match
            case null => readInt2LE(nextBytes())
            case sub  => sub.short()

    override def int(): Int =
        jsonSubReader match
            case null => readInt4LE(nextBytes())
            case sub  => sub.int()

    override def long(): Long =
        jsonSubReader match
            case null => readInt8LE(nextBytes())
            case sub  => sub.long()

    override def float(): Float =
        jsonSubReader match
            case null => readFloat4LE(nextBytes())
            case sub  => sub.float()

    override def double(): Double =
        jsonSubReader match
            case null => readFloat8LE(nextBytes())
            case sub  => sub.double()

    override def string(): String =
        jsonSubReader match
            case null => readUtf8String(nextBytes())
            case sub  => sub.string()

    override def bytes(): Span[Byte] =
        jsonSubReader match
            case null => nextBytes()
            case sub  => sub.bytes()

    override def bigDecimal(): BigDecimal =
        jsonSubReader match
            case null =>
                val s = readUtf8String(nextBytes())
                try BigDecimal(s)
                catch
                    case _: NumberFormatException =>
                        throw SqlDecodeNumericException(s, SqlDecodeNumericException.Subtype.Parse)(using frame)
                end try
            case sub => sub.bigDecimal()

    override def instant(): java.time.Instant =
        jsonSubReader match
            case null =>
                // MySQL TIMESTAMP/DATETIME wire bytes → LocalDateTime → UTC Instant.
                // The struct body is stored without the length prefix byte (consumed by BinaryResultsetRowUnmarshaller).
                decodeDatetimeBody(nextBytes()).toInstant(java.time.ZoneOffset.UTC)
            case sub => sub.instant()
    end instant

    override def byte(): Byte =
        // TINY wire: 1 unsigned byte, interpreted as signed Byte at this boundary.
        jsonSubReader match
            case null => readInt1(nextBytes()).toByte
            case sub  => sub.byte()

    override def char(): Char =
        jsonSubReader match
            case null =>
                val s = readUtf8String(nextBytes())
                if s.isEmpty then throw SqlDecodeEmptyStringForCharException(idx - 1)(using frame)
                else s.charAt(0)
            case sub => sub.char()
    end char

    override def bigInt(): BigInt =
        jsonSubReader match
            case null => bigDecimal().toBigInt
            case sub  => sub.bigInt()

    override def duration(): java.time.Duration =
        jsonSubReader match
            case null => decodeDurationBody(nextBytes())
            case sub  => sub.duration()

    /** Decodes a MySQL binary TIME struct body (length prefix already stripped) as a signed [[java.time.Duration]].
      *
      * Layout (MySQL protocol §14.7.4):
      *   - 0 bytes → zero duration
      *   - 8 bytes → is_negative(1) | days(4 LE) | hours(1) | minutes(1) | seconds(1)
      *   - 12 bytes → same as 8 + micros(4 LE)
      */
    private def decodeDurationBody(bytes: Span[Byte]): java.time.Duration =
        bytes.size match
            case 0 => java.time.Duration.ZERO
            case 8 =>
                val isNeg     = bytes(0) & 0xff
                val days      = (bytes(1) & 0xffL) | ((bytes(2) & 0xffL) << 8) | ((bytes(3) & 0xffL) << 16) | ((bytes(4) & 0xffL) << 24)
                val hours     = bytes(5) & 0xff
                val minutes   = bytes(6) & 0xff
                val seconds   = bytes(7) & 0xff
                val totalSecs = days * 86400L + hours * 3600L + minutes * 60L + seconds
                val d         = java.time.Duration.ofSeconds(totalSecs)
                if isNeg != 0 then d.negated() else d
            case 12 =>
                val isNeg     = bytes(0) & 0xff
                val days      = (bytes(1) & 0xffL) | ((bytes(2) & 0xffL) << 8) | ((bytes(3) & 0xffL) << 16) | ((bytes(4) & 0xffL) << 24)
                val hours     = bytes(5) & 0xff
                val minutes   = bytes(6) & 0xff
                val seconds   = bytes(7) & 0xff
                val micros    = (bytes(8) & 0xffL) | ((bytes(9) & 0xffL) << 8) | ((bytes(10) & 0xffL) << 16) | ((bytes(11) & 0xffL) << 24)
                val totalSecs = days * 86400L + hours * 3600L + minutes * 60L + seconds
                val nanos     = micros * 1000L
                val d         = java.time.Duration.ofSeconds(totalSecs, nanos)
                if isNeg != 0 then d.negated() else d
            case n =>
                throw SqlDecodeDurationException("<struct>", new Exception(s"unexpected struct length $n"))(using frame)
        end match
    end decodeDurationBody

    // --- Custom escape for opaque backend-specific types ---

    override def custom(typeName: String): Span[Byte] =
        nextBytes()

    // --- Skip ---

    override def skip(): Unit =
        jsonSubReader match
            case null => idx += 1
            case sub  => sub.skip()

    // --- Case-class field-iteration protocol ---
    //
    // Schema-derived read bodies for case classes/tuples call objectStart/hasNextField/
    // fieldParse / matchField / objectEnd to iterate over fields. The generated body emits an
    // if/else-if dispatch on matchField, so matchField MUST return true only for the arm that
    // matches the current column's name; a blanket-true would pin every column to arm0 and
    // leave later fields' `_seen` bits unset, producing spurious `Missing required field` errors.
    // Compare the probe against the current column's UTF-8 name and, for tuple projections
    // whose SQL columns carry expression labels rather than `_1` / `_2`, accept the `_<idx+1>`
    // positional fallback. Primitive reads advance the cursor; matchField does not.

    /** Opens an object (case class) scope. The return value is not used by the generated decoder. */
    override def objectStart(): Int = 0

    /** Closes the object scope. No cleanup needed for positional SQL reads. */
    override def objectEnd(): Unit = ()

    /** Returns true while there are unread columns at the current cursor position. */
    override def hasNextField(): Boolean = idx < row.values.size

    /** No-op: SQL field names come from SqlRow.fields, not from the wire stream. */
    override def fieldParse(): Unit = ()

    /** Accepts the field at the current cursor position when its name matches the probe. See the block-comment above for why a blanket
      * positional accept corrupts the Schema decoder's per-field dispatch. Does NOT advance the cursor; the cursor advances only when a
      * primitive value is read.
      */
    override def matchField(nameBytes: Array[Byte]): Boolean =
        if idx >= row.fields.size then false
        else
            val currentName  = row.fields(idx).name
            val currentBytes = currentName.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            if java.util.Arrays.equals(currentBytes, nameBytes) then true
            else
                val probe = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)
                probe.length > 1 && probe.charAt(0) == '_' && probe.drop(1) == (idx + 1).toString
            end if

    /** Returns the name of the column at the current cursor position for error reporting. */
    override def lastFieldName(): String =
        if idx < row.fields.size then row.fields(idx).name else s"<column $idx>"

    // --- Structural reads, array, map, and captureValue ---

    /** Reads the current column as a JSON array and returns its element count.
      *
      * MySQL has no native array type; arrays must be stored as TYPE_JSON columns containing `[…]` values. The column bytes are parsed as
      * UTF-8 JSON, element count is determined by scanning the array, and a [[JsonReader]] sub-reader is installed. Subsequent primitive
      * reads are delegated to that sub-reader until [[arrayEnd]] is called.
      */
    override def arrayStart(): Int =
        val bytes           = nextBytes()
        val (reader, count) = MysqlJsonReader.openArray(bytes, frame)
        jsonSubReader = reader
        count
    end arrayStart

    /** Clears the active JSON array sub-reader. */
    override def arrayEnd(): Unit =
        jsonSubReader match
            case null => ()
            case sub  => sub.arrayEnd()
        jsonSubReader = null
    end arrayEnd

    /** Returns true while the active JSON array sub-reader has unread elements. */
    override def hasNextElement(): Boolean =
        jsonSubReader match
            case null => false
            case sub  => sub.hasNextElement()
    end hasNextElement

    /** Reads the current column as a JSON object and returns its entry count.
      *
      * MySQL has no native map type; maps must be stored as TYPE_JSON columns containing `{…}` values. A [[JsonReader]] sub-reader is
      * installed for subsequent key/value reads until [[mapEnd]] is called.
      */
    override def mapStart(): Int =
        val bytes           = nextBytes()
        val (reader, count) = MysqlJsonReader.openMap(bytes, frame)
        jsonSubReader = reader
        count
    end mapStart

    /** Clears the active JSON map sub-reader. */
    override def mapEnd(): Unit =
        jsonSubReader match
            case null => ()
            case sub  => sub.mapEnd()
        jsonSubReader = null
    end mapEnd

    /** Returns true while the active JSON object sub-reader has unread entries. */
    override def hasNextEntry(): Boolean =
        jsonSubReader match
            case null => false
            case sub  => sub.hasNextEntry()
    end hasNextEntry

    /** Returns the current column's name, or the JSON key when inside a map.
      *
      * Inside a JSON map (after [[mapStart]]), delegates to the JSON sub-reader to return the current map key. Outside a map, returns the
      * column name from [[SqlRow.fields]] at the current cursor position.
      */
    override def field(): String =
        jsonSubReader match
            case null => if idx < row.fields.size then row.fields(idx).name else s"<column $idx>"
            case sub  => sub.field()
    end field

    /** Buffers the current column's bytes and returns a [[BufferedSqlReader]] scoped to that column.
      *
      * Used by sum codecs (sealed traits, `Result`, `Either`) for field-order-independent decoding. The returned reader's primitive methods
      * decode against the buffered bytes using MySQL little-endian binary decoders. Structural reads (arrayStart, mapStart) on the buffered
      * reader raise [[SqlDecodeException]].
      */
    override def captureValue(): Codec.Reader =
        val bytes = nextBytes()
        new BufferedSqlReader(bytes, row.format, BufferedSqlReader.Backend.Mysql, frame)

end MysqlRowReader

object MysqlRowReader:

    /** Decodes a MySQL binary DATE struct body (length prefix already stripped by BinaryResultsetRowUnmarshaller).
      *
      * Expected sizes: 0 = zero date, 4 = year(2 LE) | month(1) | day(1).
      */
    def decodeDateBytes(bytes: Span[Byte])(using frame: Frame): java.time.LocalDate =
        bytes.size match
            case 0 => java.time.LocalDate.of(1, 1, 1)
            case 4 =>
                val year     = (bytes(0) & 0xff) | ((bytes(1) & 0xff) << 8)
                val month    = bytes(2) & 0xff
                val day      = bytes(3) & 0xff
                val safeYear = if year == 0 then 1 else year
                val safeMon  = if month == 0 then 1 else month
                val safeDay  = if day == 0 then 1 else day
                try java.time.LocalDate.of(safeYear, safeMon, safeDay)
                catch
                    case e: java.time.DateTimeException =>
                        throw SqlDecodeTemporalException(year, month, day, 0, 0, 0, 4)(using frame)
                end try
            case n => throw SqlDecodeTemporalException(0, 0, 0, 0, 0, 0, n)(using frame)
        end match
    end decodeDateBytes

    /** Decodes a MySQL binary DATETIME/TIMESTAMP struct body (length prefix already stripped).
      *
      * Expected sizes: 0, 4, 7, or 11 bytes.
      */
    def decodeDatetimeBytes(bytes: Span[Byte])(using frame: Frame): java.time.LocalDateTime =
        bytes.size match
            case 0 => java.time.LocalDateTime.of(1, 1, 1, 0, 0, 0)
            case 4 =>
                val year     = (bytes(0) & 0xff) | ((bytes(1) & 0xff) << 8)
                val month    = bytes(2) & 0xff
                val day      = bytes(3) & 0xff
                val safeYear = if year == 0 then 1 else year
                val safeMon  = if month == 0 then 1 else month
                val safeDay  = if day == 0 then 1 else day
                try java.time.LocalDateTime.of(safeYear, safeMon, safeDay, 0, 0, 0)
                catch
                    case e: java.time.DateTimeException =>
                        throw SqlDecodeTemporalException(year, month, day, 0, 0, 0, 4)(using frame)
                end try
            case 7 =>
                val year     = (bytes(0) & 0xff) | ((bytes(1) & 0xff) << 8)
                val month    = bytes(2) & 0xff
                val day      = bytes(3) & 0xff
                val hour     = bytes(4) & 0xff
                val minute   = bytes(5) & 0xff
                val second   = bytes(6) & 0xff
                val safeYear = if year == 0 then 1 else year
                val safeMon  = if month == 0 then 1 else month
                val safeDay  = if day == 0 then 1 else day
                try java.time.LocalDateTime.of(safeYear, safeMon, safeDay, hour, minute, second)
                catch
                    case e: java.time.DateTimeException =>
                        throw SqlDecodeTemporalException(year, month, day, hour, minute, second, 7)(using frame)
                end try
            case 11 =>
                val year     = (bytes(0) & 0xff) | ((bytes(1) & 0xff) << 8)
                val month    = bytes(2) & 0xff
                val day      = bytes(3) & 0xff
                val hour     = bytes(4) & 0xff
                val minute   = bytes(5) & 0xff
                val second   = bytes(6) & 0xff
                val micros   = (bytes(7) & 0xff) | ((bytes(8) & 0xff) << 8) | ((bytes(9) & 0xff) << 16) | ((bytes(10) & 0xff) << 24)
                val safeYear = if year == 0 then 1 else year
                val safeMon  = if month == 0 then 1 else month
                val safeDay  = if day == 0 then 1 else day
                try java.time.LocalDateTime.of(safeYear, safeMon, safeDay, hour, minute, second, micros * 1000)
                catch
                    case e: java.time.DateTimeException =>
                        throw SqlDecodeTemporalException(year, month, day, hour, minute, second, 11)(using frame)
                end try
            case n => throw SqlDecodeTemporalException(0, 0, 0, 0, 0, 0, n)(using frame)
        end match
    end decodeDatetimeBytes

end MysqlRowReader
