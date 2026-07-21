package kyo.internal.postgres

import kyo.Span
import kyo.Test

/** Tests for [[PostgresBufferWriter]].
  *
  * Byte values are per the PostgreSQL v3 wire protocol big-endian encoding §55.7.
  */
class PostgresBufferWriterTest extends Test:

    // BufferWriter writeInt32 big-endian, write 256, produced bytes are [0,0,1,0]
    "BufferWriter writeInt32 big-endian" in {
        val writer = new PostgresBufferWriter
        writer.writeInt32(256)
        val span = writer.toSpan
        assert(span.size == 4)
        assert(span(0) == 0.toByte)
        assert(span(1) == 0.toByte)
        assert(span(2) == 1.toByte)
        assert(span(3) == 0.toByte)
    }

    // BufferWriter toSpan immutable, writes after toSpan don't affect returned Span
    "BufferWriter toSpan immutable" in {
        val writer = new PostgresBufferWriter
        writer.writeInt32(1)
        val snap = writer.toSpan
        writer.writeInt32(2)
        // snap should still be 4 bytes (just the first int)
        assert(snap.size == 4)
        assert(writer.toSpan.size == 8)
    }

    // BufferWriter growable beyond initial capacity (default 512 bytes)
    "BufferWriter growable beyond initial capacity" in {
        val writer = new PostgresBufferWriter
        // Write 4096 bytes (well beyond default 512 capacity)
        val n = 4096
        (0 until n).foreach(i => writer.writeByte((i & 0xff).toByte))
        val span = writer.toSpan
        assert(span.size == n)
        // Verify bytes are correct
        assert((0 until n).forall(i => span(i) == (i & 0xff).toByte))
    }

end PostgresBufferWriterTest
