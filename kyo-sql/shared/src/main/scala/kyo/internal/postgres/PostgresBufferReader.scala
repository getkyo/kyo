package kyo.internal.postgres

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.Span
import kyo.SqlDecodeException
import kyo.SqlDecodeInsufficientBytesException

/** Big-endian byte reader for the PostgreSQL v3 wire protocol.
  *
  * Backed by an immutable [[Span[Byte]]]. The reader maintains a mutable position cursor internally; all read methods advance the cursor.
  *
  * All multi-byte integers are read in network byte order (big-endian) as required by the PostgreSQL protocol (§55.7).
  *
  * Read methods that consume bytes (`readByte`, `readInt16`, `readInt32`, `readBytes`) perform a bounds check before any array access. On
  * under-length input they return `Abort.fail(SqlDecodeInsufficientBytesException(...))` rather than throwing an exception.
  *
  * @param span
  *   the immutable byte span to read from
  */
final class PostgresBufferReader(private val span: Span[Byte]):
    // Not thread-safe by design: one PostgresBufferReader per message parse, never shared.
    private var pos = 0

    /** Returns the number of bytes remaining to be read. */
    def remaining: Int = span.size - pos

    /** Returns the current read position. */
    def position: Int = pos

    /** Reads a single byte and advances the cursor. */
    def readByte()(using Frame): Byte < Abort[SqlDecodeException] =
        if pos >= span.size then
            Abort.fail(SqlDecodeInsufficientBytesException("bytes", 1, span.size - pos, pos))
        else
            val b = span(pos)
            pos += 1
            b
    end readByte

    /** Reads a big-endian Int16 (2 bytes) and advances the cursor. */
    def readInt16()(using Frame): Short < Abort[SqlDecodeException] =
        if pos + 2 > span.size then
            Abort.fail(SqlDecodeInsufficientBytesException("bytes", 2, span.size - pos, pos))
        else
            val hi = span(pos) & 0xff
            val lo = span(pos + 1) & 0xff
            pos += 2
            ((hi << 8) | lo).toShort
    end readInt16

    /** Reads a big-endian Int32 (4 bytes) and advances the cursor. */
    def readInt32()(using Frame): Int < Abort[SqlDecodeException] =
        if pos + 4 > span.size then
            Abort.fail(SqlDecodeInsufficientBytesException("bytes", 4, span.size - pos, pos))
        else
            val b0 = span(pos) & 0xff
            val b1 = span(pos + 1) & 0xff
            val b2 = span(pos + 2) & 0xff
            val b3 = span(pos + 3) & 0xff
            pos += 4
            (b0 << 24) | (b1 << 16) | (b2 << 8) | b3
    end readInt32

    /** Reads a NUL-terminated UTF-8 string and advances the cursor past the NUL byte. */
    def readString(): String =
        val start = pos
        while pos < span.size && span(pos) != 0.toByte do pos += 1
        val s = new String(span.slice(start, pos).toArray, StandardCharsets.UTF_8)
        pos += 1 // consume NUL
        s
    end readString

    /** Reads exactly `n` bytes and returns them as an immutable [[Span[Byte]]]. Advances the cursor by `n`.
      *
      * If fewer than `n` bytes remain, returns `Abort.fail(SqlDecodeInsufficientBytesException(...))`.
      */
    def readBytes(n: Int)(using Frame): Span[Byte] < Abort[SqlDecodeException] =
        if n < 0 || pos + n > span.size then
            Abort.fail(SqlDecodeInsufficientBytesException("bytes", n, span.size - pos, pos))
        else
            val result = span.slice(pos, pos + n)
            pos += n
            result
    end readBytes

    /** Reads all remaining bytes and returns them as an immutable [[Span[Byte]]]. Advances the cursor to the end. */
    def readAll(): Span[Byte] =
        val result = span.slice(pos, span.size)
        pos = span.size
        result
    end readAll

    /** Returns a sub-reader over the next `n` bytes without consuming from this reader's perspective (creates a new reader over the slice).
      *
      * The cursor of this reader is advanced by `n`.
      */
    def slice(n: Int): PostgresBufferReader =
        val sub = new PostgresBufferReader(span.slice(pos, pos + n))
        pos += n
        sub
    end slice

end PostgresBufferReader

object PostgresBufferReader:
    def apply(bytes: Array[Byte]): PostgresBufferReader =
        new PostgresBufferReader(Span.from(bytes))
end PostgresBufferReader
