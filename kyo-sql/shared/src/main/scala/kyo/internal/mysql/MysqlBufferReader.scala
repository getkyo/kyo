package kyo.internal.mysql

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.Span
import kyo.SqlException

/** Little-endian byte reader for the MySQL 8.x wire protocol.
  *
  * Backed by an immutable [[Span[Byte]]]. The reader maintains a mutable position cursor internally; all read methods advance the cursor.
  *
  * All multi-byte integers are read in little-endian order as required by the MySQL protocol.
  *
  * Read methods that consume bytes (`readByte`, `readUInt8`, `readUInt16LE`, `readUInt24LE`, `readUInt32LE`, `readUInt64LE`,
  * `readLenencInt`) perform a bounds check before any array access. On under-length input they return
  * `Abort.fail(SqlException.Decode(...))` rather than throwing an exception.
  *
  * Reference: MySQL Internals, Connection Phase Packets and Text Protocol
  *
  * @param span
  *   the immutable byte span to read from
  */
final class MysqlBufferReader(private val span: Span[Byte]):
    private var pos = 0

    /** Returns the number of bytes remaining to be read. */
    def remaining: Int = span.size - pos

    /** Returns the current read position. */
    def position: Int = pos

    /** Reads a single unsigned byte (0..255) and advances the cursor. */
    def readByte()(using Frame): Byte < Abort[SqlException.Decode] =
        if pos >= span.size then
            Abort.fail(SqlException.Decode(
                s"Short read: expected 1 byte at position $pos but only ${span.size - pos} remain",
                Maybe.Absent,
                summon[Frame]
            ))
        else
            val b = span(pos)
            pos += 1
            b
    end readByte

    /** Reads an unsigned 8-bit integer (0..255). */
    def readUInt8()(using Frame): Int < Abort[SqlException.Decode] =
        readByte().map(_ & 0xff)

    /** Reads a little-endian unsigned 16-bit integer (0..65535). */
    def readUInt16LE()(using Frame): Int < Abort[SqlException.Decode] =
        if pos + 2 > span.size then
            Abort.fail(SqlException.Decode(
                s"Short read: expected 2 bytes at position $pos but only ${span.size - pos} remain",
                Maybe.Absent,
                summon[Frame]
            ))
        else
            val lo = span(pos) & 0xff
            val hi = span(pos + 1) & 0xff
            pos += 2
            (hi << 8) | lo
    end readUInt16LE

    /** Reads a little-endian unsigned 24-bit integer (0..16777215). */
    def readUInt24LE()(using Frame): Int < Abort[SqlException.Decode] =
        if pos + 3 > span.size then
            Abort.fail(SqlException.Decode(
                s"Short read: expected 3 bytes at position $pos but only ${span.size - pos} remain",
                Maybe.Absent,
                summon[Frame]
            ))
        else
            val b0 = span(pos) & 0xff
            val b1 = span(pos + 1) & 0xff
            val b2 = span(pos + 2) & 0xff
            pos += 3
            b0 | (b1 << 8) | (b2 << 16)
    end readUInt24LE

    /** Reads a little-endian unsigned 32-bit integer (0..4294967295) as a Long. */
    def readUInt32LE()(using Frame): Long < Abort[SqlException.Decode] =
        if pos + 4 > span.size then
            Abort.fail(SqlException.Decode(
                s"Short read: expected 4 bytes at position $pos but only ${span.size - pos} remain",
                Maybe.Absent,
                summon[Frame]
            ))
        else
            val b0 = span(pos).toLong & 0xffL
            val b1 = span(pos + 1).toLong & 0xffL
            val b2 = span(pos + 2).toLong & 0xffL
            val b3 = span(pos + 3).toLong & 0xffL
            pos += 4
            b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)
    end readUInt32LE

    /** Reads a little-endian unsigned 64-bit integer as a Long (unsigned semantics via bit pattern). */
    def readUInt64LE()(using Frame): Long < Abort[SqlException.Decode] =
        if pos + 8 > span.size then
            Abort.fail(SqlException.Decode(
                s"Short read: expected 8 bytes at position $pos but only ${span.size - pos} remain",
                Maybe.Absent,
                summon[Frame]
            ))
        else
            val b0 = span(pos).toLong & 0xffL
            val b1 = span(pos + 1).toLong & 0xffL
            val b2 = span(pos + 2).toLong & 0xffL
            val b3 = span(pos + 3).toLong & 0xffL
            val b4 = span(pos + 4).toLong & 0xffL
            val b5 = span(pos + 5).toLong & 0xffL
            val b6 = span(pos + 6).toLong & 0xffL
            val b7 = span(pos + 7).toLong & 0xffL
            pos += 8
            b0 | (b1 << 8) | (b2 << 16) | (b3 << 24) | (b4 << 32) | (b5 << 40) | (b6 << 48) | (b7 << 56)
    end readUInt64LE

    /** Reads a length-encoded integer.
      *
      * Encoding rules (MySQL protocol §14.1.1.1):
      *   - First byte 0..250: value is that byte itself (1 byte total)
      *   - First byte 0xFC (252): value is LE uint16 in the next 2 bytes (3 bytes total)
      *   - First byte 0xFD (253): value is LE uint24 in the next 3 bytes (4 bytes total)
      *   - First byte 0xFE (254): value is LE uint64 in the next 8 bytes (9 bytes total)
      *   - First byte 0xFF (255): reserved for ERR packet sentinel, never appears as a valid lenenc int; represented as [[Maybe.Absent]]
      *     to allow callers to distinguish the NULL marker from a numeric zero.
      *
      * @return
      *   [[Maybe.Present]] with the decoded value for valid lenenc ints; [[Maybe.Absent]] for the 0xFF sentinel
      */
    def readLenencInt()(using Frame): Maybe[Long] < Abort[SqlException.Decode] =
        readByte().map { rawByte =>
            val first = rawByte & 0xff
            first match
                case v if v <= 250 => Maybe.Present(v.toLong)
                case 0xfc          => readUInt16LE().map(v => Maybe.Present(v.toLong))
                case 0xfd          => readUInt24LE().map(v => Maybe.Present(v.toLong))
                case 0xfe          => readUInt64LE().map(Maybe.Present(_))
                case _             => Maybe.Absent // 0xff, ERR-packet sentinel, not a valid lenenc int
            end match
        }
    end readLenencInt

    /** Reads a length-encoded string: a lenenc-int length followed by that many raw bytes decoded as UTF-8.
      *
      * Returns `Abort.fail(SqlException.Decode)` if the 0xFF sentinel byte is encountered (invalid in string position).
      */
    def readLenencString()(using Frame): String < Abort[SqlException.Decode] =
        readLenencInt().flatMap {
            case Maybe.Absent =>
                Abort.fail(SqlException.Decode(
                    s"Unexpected 0xFF sentinel in lenenc-string at position $pos",
                    Maybe.Absent,
                    summon[Frame]
                ))
            case Maybe.Present(len) =>
                readBytes(len.toInt).map { bytes =>
                    new String(bytes.toArray, StandardCharsets.UTF_8)
                }
        }
    end readLenencString

    /** Reads a NUL-terminated string (C-string): bytes up to (not including) the 0x00 byte. Cursor advances past the NUL. */
    def readNulTerminatedString(): String =
        val start = pos
        // Performance: while loop for NUL scan, encapsulated, CONTRIBUTING permits this.
        while pos < span.size && span(pos) != 0.toByte do pos += 1
        val s = new String(span.slice(start, pos).toArray, StandardCharsets.UTF_8)
        if pos < span.size then pos += 1 // consume NUL
        s
    end readNulTerminatedString

    /** Reads exactly `n` bytes as a UTF-8 string (no NUL terminator). */
    def readFixedString(n: Int): String =
        val bytes = span.slice(pos, pos + n)
        pos += n
        new String(bytes.toArray, StandardCharsets.UTF_8)
    end readFixedString

    /** Reads exactly `n` raw bytes and returns them as an immutable [[Span[Byte]]].
      *
      * If fewer than `n` bytes remain, returns `Abort.fail(SqlException.Decode(...))`.
      */
    def readBytes(n: Int)(using Frame): Span[Byte] < Abort[SqlException.Decode] =
        if n < 0 || pos + n > span.size then
            Abort.fail(SqlException.Decode(
                s"Short read: expected $n bytes at position $pos but only ${span.size - pos} remain",
                Maybe.Absent,
                summon[Frame]
            ))
        else
            val result = span.slice(pos, pos + n)
            pos += n
            result
    end readBytes

    /** Reads all remaining bytes and returns them as an immutable [[Span[Byte]]]. */
    def readRestOfPacket(): Span[Byte] =
        val result = span.slice(pos, span.size)
        pos = span.size
        result
    end readRestOfPacket

end MysqlBufferReader

object MysqlBufferReader:
    def apply(bytes: Array[Byte]): MysqlBufferReader =
        new MysqlBufferReader(Span.from(bytes))

    @scala.annotation.targetName("fromSpan")
    def apply(span: Span[Byte]): MysqlBufferReader =
        new MysqlBufferReader(span)
end MysqlBufferReader
