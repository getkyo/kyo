package kyo.net.internal.util

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import kyo.discard
import scala.annotation.tailrec

/** An unbounded multi-producer single-consumer FIFO of primitive `long`, held unboxed in array chunks.
  *
  * Built for the [[kyo.net.internal.posix.PollerIoDriver]] interest-change FIFO: many fibers submit packed `long` commands ([[offer]],
  * multi-producer) and the single change-worker fiber drains them ([[poll]], single-consumer). A `ConcurrentLinkedQueue[java.lang.Long]`
  * would box each command on every enqueue (a `java.lang.Long` allocation per offer, which a JFR alloc profile of the poller pinpointed as
  * the dominant boxing source on the hot path); this queue stores the raw `long` in an array slot, so a command costs no allocation at all.
  *
  * Algorithm: JCTools' `MpscUnboundedAtomicArrayQueue`, specialized to `long`, and the long-element sibling of
  * [[kyo.internal.MpscUnboundedUnsafeQueue]]. Commands live in a fixed-size chunk used as a ring: producers reserve a slot by advancing
  * `producerIndex`, then store into it; the consumer reads at `consumerIndex` and clears the slot behind it. A chunk is allocated only when
  * one genuinely fills, which takes [[ChunkCapacity]] unconsumed commands. The driver drains the FIFO to empty once per poll cycle, so a
  * cycle that stays under that allocates nothing; a cycle that exceeds it links a chunk and leaves the old one behind, since a linked chunk
  * replaces its predecessor rather than being returned to it.
  *
  * Growth: a producer that finds the chunk full takes `producerIndex` odd, which is the exclusive claim to link the next chunk (every other
  * producer spins on the parity check until it is released), stores its command into the new chunk, and leaves a [[Jump]] marker in the slot
  * it would have used. The consumer follows that marker to the next chunk and drops the drained one. Growth is therefore one allocation per
  * [[ChunkCapacity]] commands, never one per command.
  *
  * Slot encoding: a slot holds `0` while vacant, the command ORed with [[ValueTag]] once stored, and [[Jump]] where the chunk is linked.
  * Vacant being the zero a fresh [[Chunk]] is born with means a new chunk needs no initialization pass and its slots carry the array's
  * construction-time publication rather than a later store's. Commands must therefore fit in `[0, 2^62)`, which the poller's packing
  * satisfies with room to spare (an opcode in bits 34-37 over a 32-bit fd).
  *
  * Memory ordering: a producer's slot store is a release (`lazySet`) that the consumer's `get` acquires, so a value stored before [[offer]]
  * is visible to the consumer that dequeues it (the same publication guarantee a `ConcurrentLinkedQueue.offer` provides the poller's
  * `pendingReadPromise` store).
  *
  * Single-consumer contract: [[poll]] must be called from one thread/fiber at a time (the driver's single change worker). [[offer]] is safe
  * from any number of producers, and [[peekNonEmpty]] from any thread.
  */
final private[kyo] class MpscLongQueue:
    import MpscLongQueue.*

    // Both indices are stored doubled, so the low bit of `producerIndex` is free to flag a link in progress. `Mask` is doubled to match, and
    // is a constant rather than a field because every chunk has the same capacity.
    private val producerIndex = new AtomicLong(0L)
    private val consumerIndex = new AtomicLong(0L)
    // How far producers may advance before they must consult `consumerIndex`. Caching it keeps the consumer's cache line off the fast path.
    private val producerLimit = new AtomicLong(Mask)

    private val initialChunk = new Chunk

    @volatile private var producerChunk: Chunk = initialChunk
    private var consumerChunk: Chunk           = initialChunk

    /** Append `value` to the tail. Safe from any number of producer threads/fibers. Allocates only when it fills a chunk.
      *
      * Rejects a value outside `[0, 2^62)`, the range the slot encoding can carry. Left unchecked, one with bit 62 set would come back with
      * that bit stripped and one with bit 63 set would come back as [[Empty]], both silently.
      */
    def offer(value: Long): Unit =
        checkInRange(value)
        @tailrec def loop(): Unit =
            val limit = producerLimit.get()
            val pIdx  = producerIndex.get()
            if (pIdx & 1) == 1 then
                // A link holds the index odd. It publishes within a handful of instructions, so retrying is cheaper than parking.
                loop()
            else
                // Read after `pIdx`, and that order is load-bearing rather than incidental. A link publishes the new chunk before it
                // releases the index, so an index read this late cannot predate the chunk that owns it: whatever `pIdx` this thread saw,
                // the chunk read after it is at least as new. Hoisting this above the index read would admit the reverse pairing.
                val chunk = producerChunk
                if pIdx < limit then
                    if producerIndex.compareAndSet(pIdx, pIdx + 2) then
                        // Winning the CAS reserves this slot: no other producer stores into it, and no link ran since `pIdx` was read (a
                        // link takes the index odd, which would have failed this CAS), so `chunk` is still the one that slot belongs to.
                        chunk.elements.lazySet(slot(pIdx), value | ValueTag)
                    else loop()
                else
                    // The chunk looks full. Either the consumer has freed room since the limit was last refreshed, or it has not and this
                    // producer links a fresh chunk.
                    val cIdx = consumerIndex.get()
                    if cIdx + Mask > pIdx then
                        discard(producerLimit.compareAndSet(limit, cIdx + Mask))
                        loop()
                    else if !link(chunk, pIdx, value) then loop()
                    end if
                end if
            end if
        end loop
        loop()
    end offer

    /** Remove and return the head value, or [[MpscLongQueue.Empty]] if the queue is observably empty. Single-consumer only.
      *
      * Returns [[Empty]] both when the queue is genuinely empty and for the instant between a producer reserving the head slot and storing
      * into it; the caller treats `Empty` as "nothing to do right now" and the next poll observes the value. Reporting empty rather than
      * spinning for the store is what keeps a descheduled producer from stalling the poll loop. Since the packed commands the poller
      * enqueues are always `>= 0`, [[Empty]] (`Long.MinValue`) can never collide with a real value.
      */
    def poll(): Long =
        val chunk  = consumerChunk
        val cIdx   = consumerIndex.get()
        val offset = slot(cIdx)
        val raw    = chunk.elements.get(offset)
        if raw == Vacant then Empty
        else if raw == Jump then
            val next = chunk.next.get()
            // Unlink so the drained chunk is collectable rather than held by the chain behind the consumer.
            chunk.next.set(null)
            consumerChunk = next
            take(next, cIdx, offset)
        else
            chunk.elements.lazySet(offset, Vacant)
            consumerIndex.lazySet(cIdx + 2)
            raw & ValueMask
        end if
    end poll

    /** True when a value has been offered that [[poll]] has not yet returned. Safe from any thread: it reads only the two indices, so the
      * driver's diagnostics dump and its stranded-op probe, its only callers, can read it off the poll carrier without touching
      * consumer-owned state.
      *
      * Reports pending for a command whose producer has reserved its slot but not yet stored into it. That is the safe direction for a
      * stranded-op probe: the command is already queued, so reporting it idle is the answer that would mislead.
      */
    def peekNonEmpty(): Boolean =
        consumerIndex.get() < producerIndex.get()

    /** Link a fresh chunk and store `value` as its first command, returning false when another producer took the claim first.
      *
      * Taking `producerIndex` odd is the claim, so exactly one producer links at a time and the rest spin on the parity check in [[offer]].
      */
    private def link(oldChunk: Chunk, pIdx: Long, value: Long): Boolean =
        if !producerIndex.compareAndSet(pIdx, pIdx + 1) then false
        else
            try
                val newChunk = new Chunk
                val offset   = slot(pIdx)
                producerChunk = newChunk
                newChunk.elements.lazySet(offset, value | ValueTag)
                oldChunk.next.set(newChunk)
                producerLimit.lazySet(pIdx + Mask)
                // Releases the claim: the index goes even again, one slot on.
                producerIndex.lazySet(pIdx + 2)
                // Published last, so the consumer follows the link only once the value behind it is in place.
                oldChunk.elements.lazySet(offset, Jump)
                true
            catch
                case error: OutOfMemoryError =>
                    // Release the claim, or every other producer spins on the parity check forever.
                    producerIndex.set(pIdx)
                    throw error
            end try
        end if
    end link

    /** Read the head value out of a freshly linked chunk. The linking producer stores the value before publishing the jump that led here, so
      * the slot is filled whenever the jump was observed; reporting empty instead of assuming it keeps the consumer off a spin, and the
      * retry reads this chunk directly because `consumerChunk` has already advanced.
      */
    private def take(chunk: Chunk, cIdx: Long, offset: Int): Long =
        val raw = chunk.elements.get(offset)
        if raw == Vacant then Empty
        else
            chunk.elements.lazySet(offset, Vacant)
            consumerIndex.lazySet(cIdx + 2)
            raw & ValueMask
        end if
    end take

end MpscLongQueue

private[kyo] object MpscLongQueue:

    /** Sentinel returned by [[MpscLongQueue.poll]] when the queue is observably empty. `Long.MinValue` is never a valid packed poller command
      * (those are always `>= 0`), so it can never collide with a real dequeued value.
      */
    final val Empty: Long = Long.MinValue

    // Slots per chunk. A power of two, so a doubled index addresses its slot with a mask rather than a division.
    final private val ChunkSlots: Int = 256

    /** Commands a chunk holds: every slot but the one reserved for the [[Jump]] that links the next chunk. Sized so the poller's realistic
      * burst (an interest change per fiber doing I/O in a cycle) fits in the chunk the driver is born with, which is what keeps a poll cycle
      * off the allocator entirely.
      */
    final val ChunkCapacity: Int = ChunkSlots - 1

    // Doubled capacity mask, matching the doubled indices. Producers may run `Mask` past the consumer, which is ChunkCapacity commands.
    final private val Mask: Long = ((ChunkSlots - 1) << 1).toLong

    // A slot holds this while it carries no command. It is the zero a fresh chunk's array is born with, so a chunk needs no fill pass.
    final private val Vacant: Long = 0L

    // ORed into a stored command so a slot holding command 0 is still distinguishable from a vacant one. Bit 62 keeps a tagged value
    // positive, so it can never be mistaken for the Jump marker either.
    final private val ValueTag: Long = 1L << 62

    // Strips the tag back off on the way out.
    final private val ValueMask: Long = ~ValueTag

    // Marks the slot where a chunk is linked to its successor. Bit 63 only, which no tagged command ever sets.
    final private val Jump: Long = Long.MinValue

    /** The array slot a doubled index addresses. */
    private def slot(index: Long): Int = ((index & Mask) >> 1).toInt

    /** Reject a value the slot encoding cannot carry, mirroring how [[kyo.internal.UnsafeQueue.checkNotNull]] guards the reference queues
      * against their own sentinel. Inline, so the check is two comparisons on a branch the poller's packed commands never take.
      */
    private inline def checkInRange(value: Long): Unit =
        if value < 0 || value >= ValueTag then
            throw new IllegalArgumentException(s"command out of range, must be in [0, ${ValueTag}): $value")

    /** One ring of command slots plus the link to its successor.
      *
      * The element array is left at its construction zero, which is the vacant encoding: no initialization pass, and a slot a producer has
      * not reached carries the array's own construction-time publication rather than a later store's.
      */
    final private class Chunk:
        val elements: AtomicLongArray    = new AtomicLongArray(ChunkSlots)
        val next: AtomicReference[Chunk] = new AtomicReference[Chunk](null)
    end Chunk

end MpscLongQueue
