package kyo.internal.mysql

import kyo.Span
import kyo.Test

/** Tests for [[AccumulatedBuffer]] peek and consume behaviour. */
class AccumulatedBufferTest extends Test:

    "AccumulatedBuffer peek does not consume bytes" in {
        val buf = new AccumulatedBuffer
        buf.append(Span.from(Array[Byte](1, 2, 3, 4, 5)))
        // Peek 3 bytes, available count must remain 5 afterwards.
        val peeked = buf.peek(3)
        assert(peeked.size == 3)
        assert(peeked(0) == 1.toByte)
        assert(peeked(1) == 2.toByte)
        assert(peeked(2) == 3.toByte)
        assert(buf.available == 5)
        // A second peek of the same range must return the same bytes.
        val peeked2 = buf.peek(3)
        assert(peeked2(0) == 1.toByte)
        assert(peeked2(1) == 2.toByte)
        assert(peeked2(2) == 3.toByte)
    }

    "AccumulatedBuffer consume removes bytes and advances the read position" in {
        val buf = new AccumulatedBuffer
        buf.append(Span.from(Array[Byte](10, 20, 30, 40)))
        // Consume first 2 bytes.
        val first = buf.consume(2)
        assert(first.size == 2)
        assert(first(0) == 10.toByte)
        assert(first(1) == 20.toByte)
        assert(buf.available == 2)
        // Consume the remaining 2 bytes.
        val second = buf.consume(2)
        assert(second.size == 2)
        assert(second(0) == 30.toByte)
        assert(second(1) == 40.toByte)
        assert(buf.available == 0)
    }

end AccumulatedBufferTest
