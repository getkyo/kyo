package kyo.internal

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.*
import scala.annotation.tailrec

/** Wait-free unbounded SPSC queue. Port of JCTools SpscUnboundedAtomicArrayQueue + BaseSpscLinkedAtomicArrayQueue.
  *
  * Linked list of AtomicReferenceArray chunks. When current chunk fills up, producer allocates a new chunk and links it via a JUMP sentinel
  * in the last slot. Consumer follows JUMP links to traverse chunks.
  */
final private[kyo] class SpscUnboundedUnsafeQueue[A](chunkSize: Int) extends UnsafeQueue[A]:

    private val chunkCapacity = UnsafeQueue.roundToPowerOfTwo(Math.max(8, chunkSize))
    private val producerIndex = new AtomicLong(0L)
    private val consumerIndex = new AtomicLong(0L)

    // Chunk layout: chunkCapacity elements + 1 next-link slot at the end
    private val initialBuffer = SpscUnboundedUnsafeQueue.allocate(chunkCapacity)

    // unsafe: mutable producer-side fields
    private var producerBuffer      = initialBuffer
    private var producerMask        = (chunkCapacity - 1).toLong
    private var producerBufferLimit = chunkCapacity.toLong - (chunkCapacity.toLong / 4)

    // cache-line padding between producer and consumer fields
    private val p0, p1, p2, p3, p4, p5, p6, p7 = 0L

    // unsafe: mutable consumer-side fields
    private var consumerBuffer = initialBuffer
    private var consumerMask   = (chunkCapacity - 1).toLong

    def capacity: Int = Int.MaxValue

    def size()(using AllowUnsafe): Int =
        UnsafeQueue.currentSize(producerIndex, consumerIndex, Int.MaxValue)

    def isEmpty()(using AllowUnsafe): Boolean =
        consumerIndex.get() >= producerIndex.get()

    def isFull()(using AllowUnsafe): Boolean = false

    def offer(a: A)(using AllowUnsafe): Boolean =
        UnsafeQueue.checkNotNull(a)
        val buffer = producerBuffer
        val index  = producerIndex.get()
        val mask   = producerMask
        val offset = (index & mask).toInt

        if index < producerBufferLimit then
            // hot path: room in current chunk
            buffer.lazySet(offset, a.asInstanceOf[AnyRef])
            producerIndex.lazySet(index + 1)
        else
            offerColdPath(buffer, mask, index, offset, a)
        end if
        true
    end offer

    private def offerColdPath(buffer: AtomicReferenceArray[AnyRef], mask: Long, index: Long, offset: Int, a: A): Unit =
        val lookAheadStep = (mask + 1) / 4
        val nextArrayOff  = (mask + 1).toInt // last slot = next link

        if isNull(buffer.get(((index + lookAheadStep) & mask).toInt)) then
            // still room in chunk — extend limit
            producerBufferLimit = index + lookAheadStep
            buffer.lazySet(offset, a.asInstanceOf[AnyRef])
            producerIndex.lazySet(index + 1)
        else if !isNull(buffer.get(((index + 1) & mask).toInt)) then
            // next slot occupied — need a new chunk
            val newBuffer = SpscUnboundedUnsafeQueue.allocate(mask.toInt + 1)
            producerBuffer = newBuffer
            producerMask = newBuffer.length() - 2
            producerBufferLimit = index + producerMask - (producerMask / 4)
            val newOffset = (index & producerMask).toInt
            newBuffer.lazySet(newOffset, a.asInstanceOf[AnyRef])
            buffer.lazySet(nextArrayOff, newBuffer)               // link old → new
            buffer.lazySet(offset, SpscUnboundedUnsafeQueue.JUMP) // sentinel
            producerIndex.lazySet(index + 1)
        else
            // slot available but past look-ahead
            buffer.lazySet(offset, a.asInstanceOf[AnyRef])
            producerIndex.lazySet(index + 1)
        end if
    end offerColdPath

    def poll()(using AllowUnsafe): Maybe[A] =
        val buffer       = consumerBuffer
        val index        = consumerIndex.get()
        val mask         = consumerMask
        val offset       = (index & mask).toInt
        val e            = buffer.get(offset)
        val nextArrayOff = (mask + 1).toInt

        if e eq SpscUnboundedUnsafeQueue.JUMP then
            // follow link to next chunk
            // unsafe: spin until producer finishes linking
            @tailrec def spinForLink(): AtomicReferenceArray[AnyRef] =
                val raw = buffer.get(nextArrayOff)
                if isNull(raw) then spinForLink()
                else raw.asInstanceOf[AtomicReferenceArray[AnyRef]]
            end spinForLink
            val nextBuffer = spinForLink()
            buffer.lazySet(nextArrayOff, null) // GC nepotism prevention
            consumerBuffer = nextBuffer
            consumerMask = (nextBuffer.length() - 2).toLong
            val newOffset = (index & consumerMask).toInt
            // unsafe: spin until producer stores the element
            @tailrec def spinForElement(): AnyRef =
                val v = nextBuffer.get(newOffset)
                if isNull(v) then spinForElement() else v
            val e2 = spinForElement()
            nextBuffer.lazySet(newOffset, null)
            consumerIndex.lazySet(index + 1)
            Maybe(e2.asInstanceOf[A])
        else if !isNull(e) then
            buffer.lazySet(offset, null)
            consumerIndex.lazySet(index + 1)
            Maybe(e.asInstanceOf[A])
        else
            Absent
        end if
    end poll

    def peek()(using AllowUnsafe): Maybe[A] =
        val buffer = consumerBuffer
        val index  = consumerIndex.get()
        val mask   = consumerMask
        val offset = (index & mask).toInt
        val e      = buffer.get(offset)

        if e eq SpscUnboundedUnsafeQueue.JUMP then
            val nextArrayOff = (mask + 1).toInt
            val raw          = buffer.get(nextArrayOff)
            if isNull(raw) then Absent
            else
                val nextBuffer = raw.asInstanceOf[AtomicReferenceArray[AnyRef]]
                val newOffset  = (index & (nextBuffer.length() - 2).toLong).toInt
                val e2         = nextBuffer.get(newOffset)
                if isNull(e2) then Absent
                else Maybe(e2.asInstanceOf[A])
            end if
        else if !isNull(e) then
            Maybe(e.asInstanceOf[A])
        else
            Absent
        end if
    end peek

end SpscUnboundedUnsafeQueue

private[kyo] object SpscUnboundedUnsafeQueue:
    // unsafe: sentinel object to indicate chunk transition
    val JUMP: AnyRef = new Object

    def allocate(chunkCapacity: Int): AtomicReferenceArray[AnyRef] =
        // No slot initialization needed: AtomicReferenceArray defaults to null,
        // which is the empty sentinel. Avoiding lazySet(Absent) here prevents an
        // ARM visibility bug — new chunks are published to the consumer via lazySet
        // (StoreStore only), so init writes with lazySet could be reordered past the
        // publication, causing the consumer to see null instead of Absent.
        new AtomicReferenceArray[AnyRef](chunkCapacity + 1)
    end allocate
end SpscUnboundedUnsafeQueue
