package kyo.internal.postgres

import java.nio.charset.StandardCharsets
import kyo.internal.SqlBufferWriter

/** Big-endian byte writer for the PostgreSQL v3 wire protocol.
  *
  * Backed by a growable buffer. Call [[toSpan]] to obtain an immutable snapshot of the written bytes; further writes after that call do not
  * affect the returned [[kyo.Span]].
  *
  * All multi-byte integers are written in network byte order (big-endian) as required by the PostgreSQL protocol (§55.7).
  */
final class PostgresBufferWriter extends SqlBufferWriter:

    /** Writes a big-endian Int16 (2 bytes). */
    def writeInt16(v: Short): Unit =
        buf.writeByte(((v >> 8) & 0xff).toByte)
        buf.writeByte((v & 0xff).toByte)

    /** Writes a big-endian Int32 (4 bytes). */
    def writeInt32(v: Int): Unit =
        buf.writeByte(((v >> 24) & 0xff).toByte)
        buf.writeByte(((v >> 16) & 0xff).toByte)
        buf.writeByte(((v >> 8) & 0xff).toByte)
        buf.writeByte((v & 0xff).toByte)
    end writeInt32

    /** Writes a NUL-terminated UTF-8 string (C string). */
    def writeString(s: String): Unit =
        val bytes = s.getBytes(StandardCharsets.UTF_8)
        buf.writeBytes(bytes, 0, bytes.length)
        buf.writeByte(0.toByte) // NUL terminator
    end writeString

    /** Patches an Int32 value at a previously-written offset (used for length-prefix back-patching). */
    def patchInt32(offset: Int, v: Int): Unit =
        val arr = buf.array
        arr(offset) = ((v >> 24) & 0xff).toByte
        arr(offset + 1) = ((v >> 16) & 0xff).toByte
        arr(offset + 2) = ((v >> 8) & 0xff).toByte
        arr(offset + 3) = (v & 0xff).toByte
    end patchInt32

    /** Writes a length-framed message: `tag`, then a placeholder Int32 length, then `body`, then back-patches the length (including the
      * 4-byte length field itself) once `body` has finished writing the payload.
      *
      * Every Frontend message but the fixed-size handful (`CancelRequest`, `Flush`, `Sync`, `Terminate`, whose length is a known constant)
      * shares this shape: a byte tag, an Int32 length nobody can compute before the body is written, and the body itself. Capturing the
      * offset and patching it after the fact is the only way to write the length as part of one linear pass over the buffer.
      */
    def framed(tag: Byte)(body: => Unit): Unit =
        writeByte(tag)
        val lengthOffset = size
        writeInt32(0) // placeholder
        body
        patchInt32(lengthOffset, size - lengthOffset)
    end framed

    /** [[framed]] without a leading tag byte, for `StartupMessage`, the one Frontend message with no type byte. */
    def framedNoTag(body: => Unit): Unit =
        val lengthOffset = size
        writeInt32(0) // placeholder
        body
        patchInt32(lengthOffset, size - lengthOffset)
    end framedNoTag

end PostgresBufferWriter
