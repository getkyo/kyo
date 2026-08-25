package kyo.internal.mysql

import java.nio.charset.StandardCharsets
import kyo.internal.SqlBufferWriter

/** Little-endian byte writer for the MySQL 8.x wire protocol.
  *
  * Backed by a growable buffer. Call [[toSpan]] to obtain an immutable snapshot of the written bytes; further writes after that call do not
  * affect the returned [[kyo.Span]].
  *
  * All multi-byte integers are written in little-endian order as required by the MySQL protocol.
  *
  * Reference: MySQL Internals, Connection Phase Packets and Text Protocol
  */
final class MysqlBufferWriter extends SqlBufferWriter:

    /** Writes an unsigned 8-bit value (only the lowest 8 bits). */
    def writeUInt8(v: Int): Unit = buf.writeByte((v & 0xff).toByte)

    /** Writes a little-endian unsigned 16-bit integer (2 bytes). */
    def writeUInt16LE(v: Int): Unit =
        buf.writeByte((v & 0xff).toByte)
        buf.writeByte(((v >> 8) & 0xff).toByte)
    end writeUInt16LE

    /** Writes a little-endian unsigned 24-bit integer (3 bytes). */
    def writeUInt24LE(v: Int): Unit =
        buf.writeByte((v & 0xff).toByte)
        buf.writeByte(((v >> 8) & 0xff).toByte)
        buf.writeByte(((v >> 16) & 0xff).toByte)
    end writeUInt24LE

    /** Writes a little-endian unsigned 32-bit integer (4 bytes). */
    def writeUInt32LE(v: Long): Unit =
        buf.writeByte((v & 0xff).toByte)
        buf.writeByte(((v >> 8) & 0xff).toByte)
        buf.writeByte(((v >> 16) & 0xff).toByte)
        buf.writeByte(((v >> 24) & 0xff).toByte)
    end writeUInt32LE

    /** Writes a little-endian 64-bit integer (8 bytes). */
    def writeUInt64LE(v: Long): Unit =
        buf.writeByte((v & 0xff).toByte)
        buf.writeByte(((v >> 8) & 0xff).toByte)
        buf.writeByte(((v >> 16) & 0xff).toByte)
        buf.writeByte(((v >> 24) & 0xff).toByte)
        buf.writeByte(((v >> 32) & 0xff).toByte)
        buf.writeByte(((v >> 40) & 0xff).toByte)
        buf.writeByte(((v >> 48) & 0xff).toByte)
        buf.writeByte(((v >> 56) & 0xff).toByte)
    end writeUInt64LE

    /** Writes a length-encoded integer.
      *
      * Encoding rules (MySQL protocol §14.1.1.1):
      *   - 0..250: 1 byte (the value itself)
      *   - 251..65535: 0xFC + 2 bytes LE
      *   - 65536..16777215: 0xFD + 3 bytes LE
      *   - 16777216..2^64-1: 0xFE + 8 bytes LE
      */
    def writeLenencInt(v: Long): Unit =
        if v <= 250L then
            buf.writeByte(v.toByte)
        else if v <= 0xffffL then
            buf.writeByte(0xfc.toByte)
            writeUInt16LE(v.toInt)
        else if v <= 0xffffffL then
            buf.writeByte(0xfd.toByte)
            writeUInt24LE(v.toInt)
        else
            buf.writeByte(0xfe.toByte)
            writeUInt64LE(v)
    end writeLenencInt

    /** Writes a length-encoded string: a lenenc-int length followed by raw UTF-8 bytes (no NUL terminator). */
    def writeLenencString(s: String): Unit =
        val bytes = s.getBytes(StandardCharsets.UTF_8)
        writeLenencInt(bytes.length.toLong)
        buf.writeBytes(bytes, 0, bytes.length)
    end writeLenencString

    /** Writes a NUL-terminated UTF-8 string (C-string). */
    def writeNulTerminatedString(s: String): Unit =
        val bytes = s.getBytes(StandardCharsets.UTF_8)
        buf.writeBytes(bytes, 0, bytes.length)
        buf.writeByte(0.toByte) // NUL terminator
    end writeNulTerminatedString

    /** Writes a fixed-length UTF-8 string without NUL terminator (caller must ensure `n == s.utf8bytes.length`). */
    def writeFixedString(s: String): Unit =
        val bytes = s.getBytes(StandardCharsets.UTF_8)
        buf.writeBytes(bytes, 0, bytes.length)
    end writeFixedString

end MysqlBufferWriter
