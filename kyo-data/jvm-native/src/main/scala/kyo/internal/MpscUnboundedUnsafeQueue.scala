package kyo.internal

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.*
import scala.annotation.tailrec

/** Lock-free unbounded MPSC queue. Port of JCTools MpscUnboundedAtomicArrayQueue + BaseMpscLinkedAtomicArrayQueue.
  *
  * Like MPSC bounded but with dynamic resizing. Uses a parity bit trick: producerIndex is doubled (actual index = pIndex / 2). The low bit
  * flags resize-in-progress. When chunk fills, one producer wins the resize CAS, allocates a new chunk, and links it via JUMP sentinel.
  */
final private[kyo] class MpscUnboundedUnsafeQueue[A](chunkSize: Int) extends UnsafeQueue[A]:
    import MpscUnboundedUnsafeQueue.*

    private val chunkCapacity = UnsafeQueue.roundToPowerOfTwo(Math.max(2, chunkSize))
    private val initialBuffer = allocate(chunkCapacity)
    private val initialMask   = ((chunkCapacity - 1) << 1).toLong

    private val producerIndex = new AtomicLong(0L)
    private val consumerIndex = new AtomicLong(0L)
    private val producerLimit = new AtomicLong(initialMask.toLong)

    // unsafe: mutable fields shared between producers (volatile via producerBuffer/producerMask reads after CAS)
    @volatile private var producerBuffer = initialBuffer
    @volatile private var producerMask   = initialMask

    // unsafe: consumer-only mutable fields
    private var consumerBuffer = initialBuffer
    private var consumerMask   = initialMask

    def capacity: Int = Int.MaxValue

    def size()(using AllowUnsafe): Int =
        UnsafeQueue.currentSize(producerIndex, consumerIndex, Int.MaxValue, divisor = 2)

    def isEmpty()(using AllowUnsafe): Boolean =
        consumerIndex.get() >= producerIndex.get()

    def isFull()(using AllowUnsafe): Boolean = false

    def offer(a: A)(using AllowUnsafe): Boolean =
        UnsafeQueue.checkNotNull(a)
        @tailrec def loop(): Boolean =
            val pLimit = producerLimit.get()
            val pIdx   = producerIndex.get()
            if (pIdx & 1) == 1 then
                loop() // resize in progress, retry
            else
                val mask   = producerMask
                val buffer = producerBuffer
                if pLimit <= pIdx then
                    offerSlowPath(mask, pIdx, pLimit) match
                        case RETRY    => loop()
                        case CONTINUE => loop()
                        case QUEUE_RESIZE =>
                            if resize(mask, buffer, pIdx, a) then true
                            else loop()
                else if producerIndex.compareAndSet(pIdx, pIdx + 2) then
                    val offset = ((pIdx & mask) >> 1).toInt
                    buffer.lazySet(offset, a.asInstanceOf[AnyRef])
                    true
                else loop()
                end if
            end if
        end loop
        loop()
    end offer

    private def offerSlowPath(mask: Long, pIdx: Long, producerLimit: Long): Int =
        val cIdx = consumerIndex.get()
        // unsafe: use mask (not mask+2) so resize triggers one slot before wrap-around,
        // leaving room for JUMP sentinel without overwriting unconsumed data
        if cIdx + mask > pIdx then
            if !this.producerLimit.compareAndSet(producerLimit, cIdx + mask) then
                RETRY
            else
                CONTINUE
            end if
        else
            QUEUE_RESIZE
        end if
    end offerSlowPath

    private def resize(oldMask: Long, oldBuffer: AtomicReferenceArray[AnyRef], pIdx: Long, a: A): Boolean =
        // Try to set the resize bit
        if !producerIndex.compareAndSet(pIdx, pIdx + 1) then
            return false

        try
            val newCapacity = ((oldMask + 2) >> 1).toInt // same chunk size
            val newBuffer   = allocate(newCapacity)
            val newMask     = ((newCapacity - 1) << 1).toLong

            producerBuffer = newBuffer
            producerMask = newMask

            val offsetInNew  = ((pIdx & newMask) >> 1).toInt
            val nextArrayOff = ((oldMask + 2) >> 1).toInt // next link slot in old buffer
            val offsetInOld  = ((pIdx & oldMask) >> 1).toInt

            newBuffer.lazySet(offsetInNew, a.asInstanceOf[AnyRef])
            oldBuffer.lazySet(nextArrayOff, newBuffer)

            val newLimit = pIdx + Math.min(newMask, Long.MaxValue)
            producerLimit.lazySet(newLimit)

            // Release: advance producer index by 2 (clears resize bit: pIdx+1 → pIdx+2)
            producerIndex.lazySet(pIdx + 2)

            // Set JUMP sentinel in old buffer
            oldBuffer.lazySet(offsetInOld, JUMP)

            true
        catch
            case e: OutOfMemoryError =>
                // Clear the resize bit so other producers can proceed
                producerIndex.set(pIdx)
                throw e
        end try
    end resize

    def poll()(using AllowUnsafe): Maybe[A] =
        val buffer       = consumerBuffer
        val cIdx         = consumerIndex.get()
        val mask         = consumerMask
        val offset       = ((cIdx & mask) >> 1).toInt
        val nextArrayOff = ((mask + 2) >> 1).toInt
        val e0           = buffer.get(offset)

        if isNull(e0) then
            if cIdx != producerIndex.get() then
                // unsafe: producer won CAS but hasn't stored yet — spin
                @tailrec def spin(): AnyRef =
                    val v = buffer.get(offset)
                    if isNull(v) then spin() else v
                consumeElement(spin(), buffer, cIdx, offset, nextArrayOff)
            else
                Absent
        else
            consumeElement(e0, buffer, cIdx, offset, nextArrayOff)
        end if
    end poll

    private def consumeElement(
        e: AnyRef,
        buffer: AtomicReferenceArray[AnyRef],
        cIdx: Long,
        offset: Int,
        nextArrayOff: Int
    ): Maybe[A] =
        if e eq JUMP then
            val nextBuffer = buffer.get(nextArrayOff).asInstanceOf[AtomicReferenceArray[AnyRef]]
            buffer.lazySet(nextArrayOff, null) // GC nepotism
            consumerBuffer = nextBuffer
            consumerMask = ((nextBuffer.length() - 2) << 1).toLong
            newBufferPoll(nextBuffer, cIdx)
        else
            buffer.lazySet(offset, null)
            consumerIndex.lazySet(cIdx + 2)
            Maybe(e.asInstanceOf[A])
    end consumeElement

    private def newBufferPoll(nextBuffer: AtomicReferenceArray[AnyRef], cIdx: Long): Maybe[A] =
        val newMask = ((nextBuffer.length() - 1) << 1).toLong - 2
        consumerMask = newMask
        val offset = ((cIdx & newMask) >> 1).toInt
        @tailrec def spin(): AnyRef =
            val v = nextBuffer.get(offset)
            if isNull(v) then spin() else v
        val e = spin()
        nextBuffer.lazySet(offset, null)
        consumerIndex.lazySet(cIdx + 2)
        Maybe(e.asInstanceOf[A])
    end newBufferPoll

    def peek()(using AllowUnsafe): Maybe[A] =
        val buffer = consumerBuffer
        val cIdx   = consumerIndex.get()
        val mask   = consumerMask
        val offset = ((cIdx & mask) >> 1).toInt
        val e0     = buffer.get(offset)

        if isNull(e0) then
            if cIdx != producerIndex.get() then
                @tailrec def spin(): AnyRef =
                    val v = buffer.get(offset)
                    if isNull(v) then spin() else v
                peekElement(spin(), buffer, cIdx, mask)
            else
                Absent
        else
            peekElement(e0, buffer, cIdx, mask)
        end if
    end peek

    private def peekElement(e: AnyRef, buffer: AtomicReferenceArray[AnyRef], cIdx: Long, mask: Long): Maybe[A] =
        if e eq JUMP then
            val nextArrayOff = ((mask + 2) >> 1).toInt
            val raw          = buffer.get(nextArrayOff)
            if isNull(raw) then Absent
            else
                val nextBuffer = raw.asInstanceOf[AtomicReferenceArray[AnyRef]]
                val newMask    = ((nextBuffer.length() - 1) << 1).toLong - 2
                val newOffset  = ((cIdx & newMask) >> 1).toInt
                val e2         = nextBuffer.get(newOffset)
                if isNull(e2) then
                    if cIdx != producerIndex.get() then
                        @tailrec def spin(): AnyRef =
                            val v = nextBuffer.get(newOffset)
                            if isNull(v) then spin() else v
                        Maybe(spin().asInstanceOf[A])
                    else
                        Absent
                else
                    Maybe(e2.asInstanceOf[A])
                end if
            end if
        else
            Maybe(e.asInstanceOf[A])
    end peekElement

end MpscUnboundedUnsafeQueue

private[kyo] object MpscUnboundedUnsafeQueue:
    // unsafe: sentinel
    val JUMP: AnyRef = new Object

    // slow path return codes
    private[internal] inline val RETRY        = 0
    private[internal] inline val CONTINUE     = 1
    private[internal] inline val QUEUE_RESIZE = 3

    def allocate(chunkCapacity: Int): AtomicReferenceArray[AnyRef] =
        // chunkCapacity element slots + 1 next-link slot.
        // No init loop needed: AtomicReferenceArray initializes all slots to null with
        // full publication guarantees (final field semantics in the constructor). We use
        // null as the empty sentinel instead of Absent to avoid the ARM visibility bug
        // where lazySet (StoreStore only) in a freshly allocated chunk may not be visible
        // to a consumer reading via plain volatile load on a different core.
        new AtomicReferenceArray[AnyRef](chunkCapacity + 1)
    end allocate
end MpscUnboundedUnsafeQueue
