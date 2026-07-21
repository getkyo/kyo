package kyo.internal.postgres

import java.nio.charset.StandardCharsets
import kyo.Span
import kyo.net.internal.util.GrowableByteBuffer

/** Big-endian byte writer for the PostgreSQL v3 wire protocol.
  *
  * Backed by a [[GrowableByteBuffer]] that grows automatically. Call [[toSpan]] to obtain an immutable snapshot of the written bytes;
  * further writes after that call do not affect the returned [[Span]].
  *
  * All multi-byte integers are written in network byte order (big-endian) as required by the PostgreSQL protocol (§55.7).
  */
final class PostgresBufferWriter:
    private val buf = new GrowableByteBuffer

    /** Returns an immutable snapshot of the bytes written so far.
      *
      * Further writes after this call do not affect the returned [[Span]].
      */
    def toSpan: Span[Byte] = Span.from(buf.toByteArray)

    /** Current number of bytes written. */
    def size: Int = buf.size

    /** Writes a single byte. */
    def writeByte(b: Byte): Unit = buf.writeByte(b)

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

    /** Writes raw bytes from a [[Span]]. */
    def writeBytes(span: Span[Byte]): Unit =
        val arr = span.toArray
        buf.writeBytes(arr, 0, arr.length)

    /** Writes raw bytes from a raw Array[Byte]. Callers should prefer [[writeBytes(Span[Byte])]] when possible. */
    @scala.annotation.targetName("writeBytesArray")
    def writeBytes(arr: Array[Byte]): Unit =
        buf.writeBytes(arr, 0, arr.length)

    /** Patches an Int32 value at a previously-written offset (used for length-prefix back-patching). */
    def patchInt32(offset: Int, v: Int): Unit =
        val arr = buf.array
        arr(offset) = ((v >> 24) & 0xff).toByte
        arr(offset + 1) = ((v >> 16) & 0xff).toByte
        arr(offset + 2) = ((v >> 8) & 0xff).toByte
        arr(offset + 3) = (v & 0xff).toByte
    end patchInt32

end PostgresBufferWriter
