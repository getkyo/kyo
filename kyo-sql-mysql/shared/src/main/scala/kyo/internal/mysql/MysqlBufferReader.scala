package kyo.internal.mysql

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.SqlDecodeException
import kyo.SqlDecodeInsufficientBytesException
import kyo.SqlDecodeProtocolFormatException
import kyo.internal.SqlBufferReader

/** Little-endian byte reader for the MySQL 8.x wire protocol.
  *
  * Backed by an immutable [[Span[Byte]]]. The reader maintains a mutable position cursor internally; all read methods advance the cursor.
  *
  * All multi-byte integers are read in little-endian order as required by the MySQL protocol.
  *
  * Read methods that consume bytes (`readByte`, `readUInt8`, `readUInt16LE`, `readUInt24LE`, `readUInt32LE`, `readUInt64LE`,
  * `readLenencInt`) perform a bounds check before any array access. On under-length input they return
  * `Abort.fail(SqlDecodeInsufficientBytesException(...))` rather than throwing an exception.
  *
  * `readLenencInt` and `readLenencBytes` additionally reject the two first bytes the protocol reserves, `0xFB` and `0xFF`, with
  * `Abort.fail(SqlDecodeProtocolFormatException(byte, offset))`. `readLenencBytes` reads `0xFB` as SQL NULL first, since that is what the byte
  * means in a text-protocol column.
  *
  * Reference: MySQL Internals, Connection Phase Packets and Text Protocol
  *
  * @param spanBytes
  *   the immutable byte span to read from
  */
final class MysqlBufferReader(spanBytes: Span[Byte]) extends SqlBufferReader(spanBytes):

    /** Returns the current read position. */
    def position: Int = pos

    /** Reads an unsigned 8-bit integer (0..255). */
    def readUInt8()(using Frame): Int < Abort[SqlDecodeException] =
        readByte().map(_ & 0xff)

    /** Reads a little-endian unsigned 16-bit integer (0..65535). */
    def readUInt16LE()(using Frame): Int < Abort[SqlDecodeException] =
        if pos + 2 > span.size then
            Abort.fail(SqlDecodeInsufficientBytesException("bytes", 2, span.size - pos, pos))
        else
            val lo = span(pos) & 0xff
            val hi = span(pos + 1) & 0xff
            pos += 2
            (hi << 8) | lo
    end readUInt16LE

    /** Reads a little-endian unsigned 24-bit integer (0..16777215). */
    def readUInt24LE()(using Frame): Int < Abort[SqlDecodeException] =
        if pos + 3 > span.size then
            Abort.fail(SqlDecodeInsufficientBytesException("bytes", 3, span.size - pos, pos))
        else
            val b0 = span(pos) & 0xff
            val b1 = span(pos + 1) & 0xff
            val b2 = span(pos + 2) & 0xff
            pos += 3
            b0 | (b1 << 8) | (b2 << 16)
    end readUInt24LE

    /** Reads a little-endian unsigned 32-bit integer (0..4294967295) as a Long. */
    def readUInt32LE()(using Frame): Long < Abort[SqlDecodeException] =
        if pos + 4 > span.size then
            Abort.fail(SqlDecodeInsufficientBytesException("bytes", 4, span.size - pos, pos))
        else
            val b0 = span(pos).toLong & 0xffL
            val b1 = span(pos + 1).toLong & 0xffL
            val b2 = span(pos + 2).toLong & 0xffL
            val b3 = span(pos + 3).toLong & 0xffL
            pos += 4
            b0 | (b1 << 8) | (b2 << 16) | (b3 << 24)
    end readUInt32LE

    /** Reads a little-endian unsigned 64-bit integer as a Long (unsigned semantics via bit pattern). */
    def readUInt64LE()(using Frame): Long < Abort[SqlDecodeException] =
        if pos + 8 > span.size then
            Abort.fail(SqlDecodeInsufficientBytesException("bytes", 8, span.size - pos, pos))
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
      *   - First byte 0xFB (251) or 0xFF (255): reserved markers, never a valid lenenc int, so both raise
      *     `SqlDecodeProtocolFormatException` naming the byte that was there and its offset
      *
      * A lenenc int that decodes has a value, so there is no absence to model on this method. [[readLenencBytes]] is the form for a
      * text-protocol column, where `0xFB` does mean something and is the one genuine absence on this wire.
      */
    def readLenencInt()(using Frame): Long < Abort[SqlDecodeException] =
        readUInt8().flatMap(lenencIntFrom)

    /** Reads one text-protocol result-set column: the `0xFB` NULL marker, or a lenenc-int length followed by that many raw bytes.
      *
      * `Absent` is SQL NULL and nothing else. `0xFF` here is a reserved marker inside a row, which means the stream has desynchronised, and it
      * raises `SqlDecodeProtocolFormatException` naming the byte and its offset.
      */
    def readLenencBytes()(using Frame): Maybe[Span[Byte]] < Abort[SqlDecodeException] =
        readUInt8().flatMap {
            case 0xfb  => Maybe.Absent
            case first => lenencIntFrom(first).flatMap(len => readBytes(len.toInt).map(Maybe.Present(_)))
        }

    /** Reads a length-encoded string: a lenenc-int length followed by that many raw bytes decoded as UTF-8.
      *
      * A reserved first byte raises `SqlDecodeProtocolFormatException` from [[readLenencInt]], which names the byte that was actually there.
      */
    def readLenencString()(using Frame): String < Abort[SqlDecodeException] =
        readLenencInt().flatMap { len =>
            readBytes(len.toInt).map(bytes => new String(bytes.toArray, StandardCharsets.UTF_8))
        }
    end readLenencString

    /** Reads a NUL-terminated string (C-string): bytes up to (not including) the 0x00 byte. Cursor advances past the NUL. */
    def readNulTerminatedString(): String =
        val start = pos
        // Performance: while loop for the NUL scan; the only state it moves is this reader's own cursor.
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

    /** Reads all remaining bytes and returns them as an immutable [[Span[Byte]]]. */
    def readRestOfPacket(): Span[Byte] =
        val result = span.slice(pos, span.size)
        pos = span.size
        result
    end readRestOfPacket

    /** Decodes the rest of a length-encoded integer whose first byte has already been read.
      *
      * `0xFB` and `0xFF` are the two first bytes the protocol reserves, `0xFB` for the text-protocol NULL marker and `0xFF` for the ERR-packet
      * marker, and neither is a valid lenenc int in any position. Both abort here rather than at a caller, because this is the last place that
      * still holds the offending byte, so the failure names the byte that was actually there instead of a guess. `pos - 1` is the offending
      * byte's own offset rather than the cursor past it.
      */
    private def lenencIntFrom(first: Int)(using Frame): Long < Abort[SqlDecodeException] =
        first match
            case v if v <= 250 => v.toLong
            case 0xfc          => readUInt16LE().map(_.toLong)
            case 0xfd          => readUInt24LE().map(_.toLong)
            case 0xfe          => readUInt64LE()
            case reserved      => Abort.fail(SqlDecodeProtocolFormatException(reserved.toByte, pos - 1))

end MysqlBufferReader

object MysqlBufferReader:
    def apply(bytes: Array[Byte]): MysqlBufferReader =
        new MysqlBufferReader(Span.from(bytes))

    @scala.annotation.targetName("fromSpan")
    def apply(span: Span[Byte]): MysqlBufferReader =
        new MysqlBufferReader(span)
end MysqlBufferReader
