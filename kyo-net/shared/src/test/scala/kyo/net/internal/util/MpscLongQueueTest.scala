package kyo.net.internal.util

import kyo.*
import kyo.net.Test

/** Single-consumer correctness for the unboxed MPSC long FIFO that backs the poller's interest-change queue.
  *
  * Covers FIFO order, the empty sentinel, walking one chunk as a ring, and crossing a chunk link. These need no concurrency and run on every
  * platform the posix transport reaches, which is all four. The multi-producer contract needs real threads and lives in
  * [[MpscLongQueueConcurrencyTest]].
  */
class MpscLongQueueTest extends Test:

    "empty queue polls the Empty sentinel" in {
        val q = new MpscLongQueue()
        assert(q.poll() == MpscLongQueue.Empty)
        assert(!q.peekNonEmpty())
        succeed
    }

    "single offer then poll returns the value, then Empty" in {
        val q = new MpscLongQueue()
        q.offer(42L)
        assert(q.peekNonEmpty())
        assert(q.poll() == 42L)
        assert(q.poll() == MpscLongQueue.Empty)
        assert(!q.peekNonEmpty())
        succeed
    }

    "a command of zero round-trips, so a stored slot is told from a vacant one" in {
        // Zero is what a chunk's slots hold before anything is stored, and it is also a real command: the poller packs
        // OpRegisterRead on fd 0 as exactly 0L. A queue that read vacancy straight off the slot would drop it.
        val q = new MpscLongQueue()
        q.offer(0L)
        assert(q.peekNonEmpty(), "a stored zero must not read as an empty queue")
        assert(q.poll() == 0L)
        assert(q.poll() == MpscLongQueue.Empty)
        succeed
    }

    "a value the slot encoding cannot carry is rejected rather than silently mangled" in {
        // The top two bits carry the encoding: bit 62 marks a stored slot, bit 63 marks a chunk link. A value using either would come back
        // altered, so offer rejects it instead. The poller's packed commands never reach bit 38, so this guards a caller that does not exist
        // yet rather than one that does.
        val q = new MpscLongQueue()
        assert(intercept[IllegalArgumentException](q.offer(1L << 62)).getMessage.contains("out of range"))
        discard(intercept[IllegalArgumentException](q.offer(Long.MinValue)))
        discard(intercept[IllegalArgumentException](q.offer(-1L)))
        // The rejected offers left nothing behind.
        assert(q.poll() == MpscLongQueue.Empty)
        assert(!q.peekNonEmpty())
        // The largest value the encoding does carry still round-trips.
        q.offer((1L << 62) - 1)
        assert(q.poll() == (1L << 62) - 1)
        succeed
    }

    "preserves FIFO order across many offers" in {
        val q = new MpscLongQueue()
        val n = 1000
        var i = 0
        while i < n do
            q.offer(i.toLong)
            i += 1
        var k  = 0
        var ok = true
        while k < n do
            if q.poll() != k.toLong then ok = false
            k += 1
        assert(ok, "values must come out in offer order")
        assert(q.poll() == MpscLongQueue.Empty)
        succeed
    }

    "a burst that outruns the consumer links chunks and stays in FIFO order across the boundary" in {
        // Nothing is polled until every offer has returned, so the burst cannot fit one chunk: the queue links a fresh chunk each time
        // ChunkCapacity commands go unconsumed, and this burst spans four. The boundary is where a link can go wrong, by dropping the
        // command that triggered it, by leaving the consumer on the drained chunk, or by resuming at the wrong slot in the new one.
        val q     = new MpscLongQueue()
        val total = MpscLongQueue.ChunkCapacity * 3 + 7
        var i     = 0
        while i < total do
            q.offer(i.toLong)
            i += 1
        var k  = 0
        var ok = true
        while k < total do
            if q.poll() != k.toLong then ok = false
            k += 1
        assert(ok, s"all $total values must come out in offer order across the chunks they span")
        assert(q.poll() == MpscLongQueue.Empty, "queue must be empty once the whole burst is drained")
        succeed
    }

    "interleaved offer/poll preserves FIFO and drains fully" in {
        val q   = new MpscLongQueue()
        val out = scala.collection.mutable.ArrayBuffer.empty[Long]
        // Offer 0,1 then poll one; offer 2,3 then poll one; etc. The consumer always trails the producer.
        var i = 0L
        while i < 10L do
            q.offer(i)
            q.offer(i + 1)
            out += q.poll()
            i += 2
        end while
        var p = q.poll()
        while p != MpscLongQueue.Empty do
            out += p
            p = q.poll()
        assert(out.toList == (0L until 10L).toList, s"expected 0..9 in order, got ${out.toList}")
        succeed
    }

    "a long offer/poll churn wraps the chunk without losing or reordering values" in {
        // The consumer keeps pace, so the producer never fills a chunk and instead walks the same one as a ring, wrapping many times over
        // these rounds. A slot the consumer failed to clear, or an index that wrapped to the wrong slot, surfaces as a wrong value or a
        // missing entry.
        val q       = new MpscLongQueue()
        val rounds  = 5000
        var r       = 0
        var ok      = true
        var expectV = 0L
        while r < rounds do
            q.offer(expectV)
            q.offer(expectV + 1)
            if q.poll() != expectV then ok = false
            if q.poll() != expectV + 1 then ok = false
            expectV += 2
            r += 1
        end while
        assert(ok, "churn must preserve every value in FIFO order")
        assert(q.poll() == MpscLongQueue.Empty, "queue must be empty after balanced churn")
        succeed
    }

end MpscLongQueueTest
