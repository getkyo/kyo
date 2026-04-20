package kyo.internal

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.*
import scala.annotation.tailrec

/** Lock-free bounded SPMC queue. Port of JCTools SpmcAtomicArrayQueue.
  *
  * Single producer doesn't need CAS. Multiple consumers CAS on consumerIndex. Uses producerIndexCache to reduce cross-core reads.
  */
final private[kyo] class SpmcUnsafeQueue[A](requestedCapacity: Int) extends UnsafeQueue[A]:

    private val actualCapacity = UnsafeQueue.roundToPowerOfTwo(Math.max(2, requestedCapacity))
    private val mask           = actualCapacity - 1
    // No init needed: AtomicReferenceArray defaults to null, which is the empty sentinel.
    private val buffer             = new AtomicReferenceArray[AnyRef](actualCapacity)
    private val producerIndex      = new AtomicLong(0L)
    private val consumerIndex      = new AtomicLong(0L)
    private val producerIndexCache = new AtomicLong(0L)

    def capacity: Int = actualCapacity

    def size()(using AllowUnsafe): Int =
        UnsafeQueue.currentSize(producerIndex, consumerIndex, actualCapacity)

    def isEmpty()(using AllowUnsafe): Boolean =
        consumerIndex.get() >= producerIndex.get()

    def isFull()(using AllowUnsafe): Boolean =
        producerIndex.get() - consumerIndex.get() >= actualCapacity

    def offer(a: A)(using AllowUnsafe): Boolean =
        UnsafeQueue.checkNotNull(a)
        val pIdx   = producerIndex.get()
        val offset = (pIdx & mask).toInt
        if !isNull(buffer.get(offset)) then
            val size = pIdx - consumerIndex.get()
            if size > mask then
                // unsafe: full
                return false
            // unsafe: bubble condition — consumer CAS'd but hasn't cleared slot yet — spin
            @tailrec def spinForSlot(): Unit =
                if !isNull(buffer.get(offset)) then spinForSlot()
            spinForSlot()
        end if
        buffer.lazySet(offset, a.asInstanceOf[AnyRef])
        producerIndex.lazySet(pIdx + 1)
        true
    end offer

    @tailrec
    def poll()(using AllowUnsafe): Maybe[A] =
        val cIdx = consumerIndex.get()
        if cIdx >= producerIndexCache.get() then
            val pIdx = producerIndex.get()
            if cIdx >= pIdx then
                return Absent
            producerIndexCache.set(pIdx)
        end if
        if consumerIndex.compareAndSet(cIdx, cIdx + 1) then
            val offset = (cIdx & mask).toInt
            val e      = buffer.get(offset)
            buffer.lazySet(offset, null)
            Maybe(e.asInstanceOf[A])
        else
            poll()
        end if
    end poll

    @tailrec
    def peek()(using AllowUnsafe): Maybe[A] =
        val cIdx = consumerIndex.get()
        if cIdx >= producerIndexCache.get() then
            val pIdx = producerIndex.get()
            if cIdx >= pIdx then
                return Absent
            producerIndexCache.set(pIdx)
        end if
        val offset = (cIdx & mask).toInt
        val e      = buffer.get(offset)
        if isNull(e) then
            // unsafe: retry — may have been consumed between our read and CAS
            peek()
        else if consumerIndex.get() == cIdx then
            Maybe(e.asInstanceOf[A])
        else
            // unsafe: stale read
            peek()
        end if
    end peek

end SpmcUnsafeQueue
