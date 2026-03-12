package kyo.internal

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.*
import scala.annotation.tailrec

/** Lock-free unbounded MPMC queue. Port of JCTools MpmcUnboundedXaddArrayQueue + MpUnboundedXaddArrayQueue.
  *
  * Uses getAndIncrement (XADD) instead of CAS loops for producer slot allocation. Linked chunks with optional chunk pooling (reuse consumed
  * chunks). Pooled chunks use sequence numbers per slot for coordination — consumers check sequence rather than buffer to detect when a
  * producer has written to a recycled slot, avoiding stale reads.
  */
final private[kyo] class MpmcUnboundedUnsafeQueue[A](chunkSize: Int, maxPooledChunks: Int = 2) extends UnsafeQueue[A]:
    import MpmcUnboundedUnsafeQueue.*

    private val chunkCapacity = UnsafeQueue.roundToPowerOfTwo(Math.max(8, chunkSize))
    private val chunkMask     = chunkCapacity - 1
    private val chunkShift    = Integer.numberOfTrailingZeros(chunkCapacity)

    private val isPooled       = maxPooledChunks > 0
    private val freeChunksPool = if isPooled then new MpmcUnsafeQueue[Chunk](maxPooledChunks + 4) else null
    private val initialChunk   = newOrPooledChunk(0L)

    private val producerIndex      = new AtomicLong(0L)
    private val consumerIndex      = new AtomicLong(0L)
    private val producerChunk      = new AtomicReference[Chunk](initialChunk)
    private val producerChunkIndex = new AtomicLong(0L)
    private val consumerChunk      = new AtomicReference[Chunk](initialChunk)

    def capacity: Int = Int.MaxValue

    def size()(using AllowUnsafe): Int =
        UnsafeQueue.currentSize(producerIndex, consumerIndex, Int.MaxValue)

    def isEmpty()(using AllowUnsafe): Boolean =
        consumerIndex.get() >= producerIndex.get()

    def isFull()(using AllowUnsafe): Boolean = false

    def offer(a: A)(using AllowUnsafe): Boolean =
        UnsafeQueue.checkNotNull(a)
        val pIdx          = producerIndex.getAndIncrement()
        val piChunkOffset = (pIdx & chunkMask).toInt
        val piChunkIndex  = pIdx >> chunkShift

        var pChunk = producerChunk.get()
        if pChunk.index != piChunkIndex then
            pChunk = producerChunkForIndex(pChunk, piChunkIndex)

        if isPooled then
            // Wait for previous consumer to clear the recycled slot (JCTools: spinForElement).
            // Without this, the producer's write can race with a late-arriving consumer
            // clear (lazySet reordering), losing the element.
            @tailrec def spinForSlot(): Unit =
                if !isNull(pChunk.buffer.get(piChunkOffset)) then spinForSlot()
            spinForSlot()
        end if
        pChunk.buffer.lazySet(piChunkOffset, a.asInstanceOf[AnyRef])
        if isPooled then
            pChunk.sequence.lazySet(piChunkOffset, piChunkIndex)
        end if
        true
    end offer

    private def producerChunkForIndex(startChunk: Chunk, requiredChunkIndex: Long): Chunk =
        @tailrec def loop(currentChunk: Chunk): Chunk =
            val cc                = if currentChunk == null then producerChunk.get() else currentChunk
            val currentChunkIndex = cc.index
            val jumpBackward      = currentChunkIndex - requiredChunkIndex
            if jumpBackward >= 0 then
                // chunk exists, walk backward
                @tailrec def walkBack(chunk: Chunk, i: Long): Chunk =
                    if i >= jumpBackward then chunk
                    else
                        val prev = chunk.prev.get()
                        if prev == null then null // consumer cleared prev link — restart
                        else walkBack(prev, i + 1)
                val found = walkBack(cc, 0L)
                if found != null then found
                else loop(null) // retry from producerChunk
            else
                // need to append chunks
                if producerChunkIndex.get() == currentChunkIndex then
                    val appended = appendNextChunks(cc, currentChunkIndex, (-jumpBackward).toInt)
                    if appended != null then appended
                    else loop(null)
                else
                    loop(null) // retry from producerChunk
            end if
        end loop
        loop(startChunk)
    end producerChunkForIndex

    private val ROTATION = Long.MinValue

    private def appendNextChunks(currentChunk: Chunk, currentChunkIndex: Long, chunksToAppend: Int): Chunk =
        if !producerChunkIndex.compareAndSet(currentChunkIndex, ROTATION) then
            // unsafe: another thread is appending
            return null

        @tailrec def appendLoop(chunk: Chunk, i: Int): Chunk =
            if i >= chunksToAppend then chunk
            else
                val newChunkIndex = currentChunkIndex + i + 1
                val newChunk      = newOrPooledChunk(newChunkIndex)
                newChunk.prev.lazySet(chunk)
                chunk.next.lazySet(newChunk)
                // Volatile write: other producers read this via get() to navigate chunks.
                // lazySet would delay visibility on ARM, causing producers to spin in
                // producerChunkForIndex unable to find the new chunk.
                producerChunk.set(newChunk)
                appendLoop(newChunk, i + 1)
        val result = appendLoop(currentChunk, 0)

        // Volatile write: releases the ROTATION lock. Other producers CAS on this
        // to acquire the append lock, so they must see the release promptly.
        producerChunkIndex.set(currentChunkIndex + chunksToAppend)
        result
    end appendNextChunks

    private def newOrPooledChunk(chunkIndex: Long): Chunk =
        if isPooled then
            import AllowUnsafe.embrace.danger
            freeChunksPool.poll() match
                case Maybe.Present(chunk) =>
                    chunk.index = chunkIndex
                    chunk
                case _ =>
                    new Chunk(chunkIndex, chunkCapacity, isPooled)
            end match
        else
            new Chunk(chunkIndex, chunkCapacity, false)
    end newOrPooledChunk

    def poll()(using AllowUnsafe): Maybe[A] =
        @tailrec def loop(pIndex: Long): Maybe[A] =
            val cIdx          = consumerIndex.get()
            val ciChunkOffset = (cIdx & chunkMask).toInt
            val ciChunkIndex  = cIdx >> chunkShift
            val cChunk        = consumerChunk.get()
            val ccChunkIndex  = cChunk.index

            if ciChunkOffset == 0 && cIdx != 0 then
                // First element of new chunk. We must verify the element exists
                // BEFORE CAS'ing consumerIndex. Otherwise the consumer commits to
                // a chunk transition, enters spinForElement, and blocks — while
                // other consumers advance past the chunk and clear prev links that
                // producers need for backward walks, causing deadlock.
                if ciChunkIndex - ccChunkIndex != 1 then
                    loop(pIndex) // stale view, retry
                else
                    val next = cChunk.next.get()
                    if next == null then
                        if cIdx >= pIndex then
                            val newPIndex = producerIndex.get()
                            if cIdx == newPIndex then Absent
                            else loop(newPIndex)
                        else loop(pIndex)
                    else if isPooled then
                        val seq = next.sequence.get(ciChunkOffset)
                        if seq == ciChunkIndex then
                            val e = next.buffer.get(ciChunkOffset)
                            if !isNull(e) && consumerIndex.compareAndSet(cIdx, cIdx + 1) then
                                moveToNextConsumerChunk(cChunk, next, ciChunkOffset, e)
                            else loop(pIndex)
                        else if seq > ciChunkIndex then
                            loop(pIndex)
                        else
                            if cIdx >= pIndex then
                                val newPIndex = producerIndex.get()
                                if cIdx == newPIndex then Absent
                                else loop(newPIndex)
                            else loop(pIndex)
                        end if
                    else
                        val e = next.buffer.get(ciChunkOffset)
                        if !isNull(e) then
                            if consumerIndex.compareAndSet(cIdx, cIdx + 1) then
                                moveToNextConsumerChunk(cChunk, next, ciChunkOffset, e)
                            else loop(pIndex)
                        else
                            if cIdx >= pIndex then
                                val newPIndex = producerIndex.get()
                                if cIdx == newPIndex then Absent
                                else loop(newPIndex)
                            else loop(pIndex)
                        end if
                    end if
                end if
            else if ccChunkIndex > ciChunkIndex then
                loop(pIndex) // stale view, retry
            else if ccChunkIndex == ciChunkIndex then
                if isPooled then
                    val seq = cChunk.sequence.get(ciChunkOffset)
                    if seq == ciChunkIndex then
                        if consumerIndex.compareAndSet(cIdx, cIdx + 1) then
                            val e = cChunk.buffer.get(ciChunkOffset)
                            // Volatile write: producers spin on buffer.get() in spinForSlot()
                            // when the chunk is recycled. lazySet from a different consumer thread
                            // may not be visible to the producer, causing deadlock on ARM.
                            cChunk.buffer.set(ciChunkOffset, null)
                            Maybe(e.asInstanceOf[A])
                        else loop(pIndex)
                    else if seq > ciChunkIndex then
                        loop(pIndex) // stale, retry
                    else
                        // not yet stored — check if empty
                        if cIdx >= pIndex then
                            val newPIndex = producerIndex.get()
                            if cIdx == newPIndex then Absent
                            else loop(newPIndex)
                        else loop(pIndex)
                    end if
                else
                    val e = cChunk.buffer.get(ciChunkOffset)
                    if !isNull(e) then
                        if consumerIndex.compareAndSet(cIdx, cIdx + 1) then
                            cChunk.buffer.lazySet(ciChunkOffset, null)
                            Maybe(e.asInstanceOf[A])
                        else loop(pIndex)
                    else
                        // not yet stored — check if empty
                        if cIdx >= pIndex then
                            val newPIndex = producerIndex.get()
                            if cIdx == newPIndex then Absent
                            else loop(newPIndex)
                        else loop(pIndex)
                    end if
                end if
            else
                loop(pIndex)
            end if
        end loop
        loop(-1L)
    end poll

    private def moveToNextConsumerChunk(
        cChunk: Chunk,
        next: Chunk,
        ciChunkOffset: Int,
        e: AnyRef
    ): Maybe[A] =
        // Unlink for GC. Safe to clear prev now — the producer already wrote
        // (we verified the element before CAS'ing consumerIndex).
        cChunk.next.lazySet(null)
        next.prev.lazySet(null)

        // Pool the old chunk if applicable
        if isPooled then
            import AllowUnsafe.embrace.danger
            discard(freeChunksPool.offer(cChunk))

        consumerChunk.lazySet(next)

        if isPooled then
            next.buffer.set(ciChunkOffset, null)
        else
            next.buffer.lazySet(ciChunkOffset, null)
        end if
        Maybe(e.asInstanceOf[A])
    end moveToNextConsumerChunk

    def peek()(using AllowUnsafe): Maybe[A] =
        @tailrec def loop(pIndex: Long): Maybe[A] =
            val cIdx          = consumerIndex.get()
            val ciChunkOffset = (cIdx & chunkMask).toInt
            val ciChunkIndex  = cIdx >> chunkShift
            val cChunk        = consumerChunk.get()
            val ccChunkIndex  = cChunk.index

            if ccChunkIndex == ciChunkIndex then
                if isPooled then
                    val seq = cChunk.sequence.get(ciChunkOffset)
                    if seq == ciChunkIndex then
                        val e = cChunk.buffer.get(ciChunkOffset)
                        if consumerIndex.get() == cIdx && !isNull(e) then
                            Maybe(e.asInstanceOf[A])
                        else loop(pIndex)
                    else
                        // not yet stored — check if empty
                        if cIdx >= pIndex then
                            val newPIndex = producerIndex.get()
                            if cIdx >= newPIndex then Absent
                            else loop(newPIndex)
                        else loop(pIndex)
                    end if
                else
                    val e = cChunk.buffer.get(ciChunkOffset)
                    if !isNull(e) && consumerIndex.get() == cIdx then
                        Maybe(e.asInstanceOf[A])
                    else
                        // not yet stored — check if empty
                        if cIdx >= pIndex then
                            val newPIndex = producerIndex.get()
                            if cIdx >= newPIndex then Absent
                            else loop(newPIndex)
                        else loop(pIndex)
                    end if
            else if ccChunkIndex < ciChunkIndex then
                // consumer chunk is stale (updated via lazySet by another consumer)
                // walk forward through chunk links to find the right chunk
                @tailrec def walkForward(chunk: Chunk, ci: Long): Maybe[A] =
                    if ci >= ciChunkIndex then
                        if ci == ciChunkIndex && consumerIndex.get() == cIdx then
                            if isPooled then
                                val seq = chunk.sequence.get(ciChunkOffset)
                                if seq == ciChunkIndex then
                                    val e = chunk.buffer.get(ciChunkOffset)
                                    if consumerIndex.get() == cIdx && !isNull(e) then
                                        Maybe(e.asInstanceOf[A])
                                    else loop(pIndex)
                                else loop(pIndex)
                                end if
                            else
                                val e = chunk.buffer.get(ciChunkOffset)
                                if !isNull(e) then
                                    Maybe(e.asInstanceOf[A])
                                else loop(pIndex)
                        else loop(pIndex)
                    else
                        val nextChunk = chunk.next.get()
                        if nextChunk == null then loop(pIndex) // can't follow, retry
                        else walkForward(nextChunk, ci + 1)
                walkForward(cChunk, ccChunkIndex)
            else
                // check if empty
                if cIdx >= pIndex then
                    val newPIndex = producerIndex.get()
                    if cIdx >= newPIndex then Absent
                    else loop(newPIndex)
                else if consumerIndex.get() != cIdx then
                    loop(pIndex) // cIdx was stale, retry
                else
                    loop(pIndex)
            end if
        end loop
        loop(-1L)
    end peek

end MpmcUnboundedUnsafeQueue

private[kyo] object MpmcUnboundedUnsafeQueue:
    final class Chunk(
        @volatile var index: Long,
        chunkCapacity: Int,
        pooled: Boolean
    ):
        // No init needed: AtomicReferenceArray defaults to null, which is the empty sentinel.
        // Using null (not Absent) avoids a visibility race on ARM: chunks are published via
        // lazySet (StoreStore only), so Absent writes might not be visible when a consumer
        // first reads the new chunk. Java's null default is safe because it requires no writes.
        val buffer = new AtomicReferenceArray[AnyRef](chunkCapacity)
        val sequence: AtomicLongArray = if pooled then
            val b = new AtomicLongArray(chunkCapacity)
            @tailrec def init(i: Int): Unit =
                if i < chunkCapacity then
                    b.lazySet(i, -1L) // sentinel: "not yet written in this round"
                    init(i + 1)
            init(0)
            b
        else null
        val prev: AtomicReference[Chunk] = new AtomicReference[Chunk](null)
        val next: AtomicReference[Chunk] = new AtomicReference[Chunk](null)
    end Chunk
end MpmcUnboundedUnsafeQueue
