package kyo.internal

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.*
import scala.annotation.tailrec

/** Lock-free unbounded SPMC queue using non-circular linked chunks.
  *
  * Single producer fills fixed-size chunks linearly (no wrap-around), linking them at deterministic boundaries (every chunkCapacity
  * elements). Multiple consumers CAS on consumerIndex, then navigate the chunk chain to find the target chunk. Shared consumer chunk state
  * uses AtomicReference for consistent reads. Each chunk is used exactly once, so offset = index & mask always maps to the correct element.
  */
final private[kyo] class SpmcUnboundedUnsafeQueue[A](chunkSize: Int) extends UnsafeQueue[A]:
    import SpmcUnboundedUnsafeQueue.*

    private val chunkCapacity = UnsafeQueue.roundToPowerOfTwo(Math.max(8, chunkSize))
    private val chunkMask     = chunkCapacity - 1
    private val chunkShift    = Integer.numberOfTrailingZeros(chunkCapacity)

    private val producerIndex      = new AtomicLong(0L)
    private val consumerIndex      = new AtomicLong(0L)
    private val producerIndexCache = new AtomicLong(0L)

    private val initialChunk = allocate(chunkCapacity)

    // Producer-side (single writer, no synchronization needed)
    private var pChunk      = initialChunk
    private var pChunkIndex = 0L

    // Shared consumer-side: chunk + its chunk index, read atomically via AtomicReference
    private val consumerChunkRef = new AtomicReference[ChunkRef](
        new ChunkRef(initialChunk, 0L)
    )

    def capacity: Int = Int.MaxValue

    def size()(using AllowUnsafe): Int =
        UnsafeQueue.currentSize(producerIndex, consumerIndex, Int.MaxValue)

    def isEmpty()(using AllowUnsafe): Boolean =
        consumerIndex.get() >= producerIndex.get()

    def isFull()(using AllowUnsafe): Boolean = false

    def offer(a: A)(using AllowUnsafe): Boolean =
        UnsafeQueue.checkNotNull(a)
        val idx      = producerIndex.get()
        val chunkIdx = idx >> chunkShift
        val offset   = (idx & chunkMask).toInt

        if chunkIdx != pChunkIndex then
            // Crossed chunk boundary — allocate and link new chunk
            val newChunk = allocate(chunkCapacity)
            pChunk.set(chunkCapacity, newChunk) // volatile next-link
            pChunk = newChunk
            pChunkIndex = chunkIdx
        end if

        pChunk.lazySet(offset, a.asInstanceOf[AnyRef])
        producerIndex.lazySet(idx + 1)
        true
    end offer

    @tailrec
    def poll()(using AllowUnsafe): Maybe[A] =
        // Read consumerChunkRef BEFORE consumerIndex: JMM happens-before transitivity
        // guarantees ref.chunkIdx <= targetChunkIdx for any cIdx we CAS successfully.
        // Reading after CAS would race with other consumers advancing the ref past our chunk.
        val ref  = consumerChunkRef.get()
        val cIdx = consumerIndex.get()
        if cIdx >= producerIndexCache.get() then
            val pIdx = producerIndex.get()
            if cIdx >= pIdx then
                return Absent
            producerIndexCache.set(pIdx)
        end if

        if consumerIndex.compareAndSet(cIdx, cIdx + 1) then
            readElement(cIdx, ref)
        else
            poll()
        end if
    end poll

    private def readElement(cIdx: Long, ref: ChunkRef): Maybe[A] =
        val targetChunkIdx = cIdx >> chunkShift
        val offset         = (cIdx & chunkMask).toInt

        // Navigate forward from captured ref to target chunk
        @tailrec def navigateToChunk(chunk: AtomicReferenceArray[AnyRef], chunkIdx: Long): AtomicReferenceArray[AnyRef] =
            if chunkIdx >= targetChunkIdx then chunk
            else
                // unsafe: spin until producer links next chunk
                @tailrec def spinForLink(): AtomicReferenceArray[AnyRef] =
                    val raw = chunk.get(chunkCapacity)
                    if isNull(raw) then spinForLink()
                    else raw.asInstanceOf[AtomicReferenceArray[AnyRef]]
                end spinForLink
                navigateToChunk(spinForLink(), chunkIdx + 1)
        val chunk    = navigateToChunk(ref.chunk, ref.chunkIdx)
        val chunkIdx = ref.chunkIdx + (targetChunkIdx - ref.chunkIdx) // = targetChunkIdx

        // Best-effort advance of shared consumer chunk
        if chunkIdx > ref.chunkIdx then
            discard(consumerChunkRef.compareAndSet(ref, new ChunkRef(chunk, chunkIdx)))

        // unsafe: spin until element is visible (producer used lazySet)
        @tailrec def spinForElement(): AnyRef =
            val v = chunk.get(offset)
            if isNull(v) then spinForElement() else v
        val e = spinForElement()

        chunk.lazySet(offset, null) // consume + GC
        Maybe(e.asInstanceOf[A])
    end readElement

    @tailrec
    def peek()(using AllowUnsafe): Maybe[A] =
        // Read consumerChunkRef BEFORE consumerIndex (same ordering as poll)
        val ref  = consumerChunkRef.get()
        val cIdx = consumerIndex.get()
        if cIdx >= producerIndexCache.get() then
            val pIdx = producerIndex.get()
            if cIdx >= pIdx then
                return Absent
            producerIndexCache.set(pIdx)
        end if

        val targetChunkIdx = cIdx >> chunkShift
        val offset         = (cIdx & chunkMask).toInt

        @tailrec def navigateToChunk(
            chunk: AtomicReferenceArray[AnyRef],
            chunkIdx: Long
        ): (AtomicReferenceArray[AnyRef], Boolean) =
            if chunkIdx >= targetChunkIdx then (chunk, true)
            else
                val raw = chunk.get(chunkCapacity)
                if isNull(raw) then (chunk, false) // producer hasn't linked yet
                else navigateToChunk(raw.asInstanceOf[AtomicReferenceArray[AnyRef]], chunkIdx + 1)

        val (chunk, found) = navigateToChunk(ref.chunk, ref.chunkIdx)
        if !found then
            peek() // retry
        else
            val e = chunk.get(offset)
            if isNull(e) then
                peek() // lazySet not visible yet, retry
            else if consumerIndex.get() == cIdx then
                Maybe(e.asInstanceOf[A]) // still valid
            else
                peek() // consumed by another thread, retry
            end if
        end if
    end peek

end SpmcUnboundedUnsafeQueue

private[kyo] object SpmcUnboundedUnsafeQueue:

    final private[kyo] class ChunkRef(
        val chunk: AtomicReferenceArray[AnyRef],
        val chunkIdx: Long
    )

    def allocate(chunkCapacity: Int): AtomicReferenceArray[AnyRef] =
        // No init loop needed: AtomicReferenceArray slots default to null,
        // and we use null (via isNull) as the empty sentinel. This avoids
        // an ARM visibility bug where lazySet(Absent) in the init loop
        // (StoreStore only) might not be visible to consumers reading a
        // newly linked chunk, causing them to see Java null instead of
        // Absent and incorrectly treating the slot as having an element.
        new AtomicReferenceArray[AnyRef](chunkCapacity + 1) // +1 for next-link
    end allocate

end SpmcUnboundedUnsafeQueue
