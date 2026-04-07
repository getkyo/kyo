package kyo.internal

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.*
import scala.annotation.tailrec

/** Wait-free bounded SPSC queue. Port of JCTools SpscAtomicArrayQueue (Fast Flow + BQueue algorithm).
  *
  * No CAS needed — single producer and single consumer are isolated. Uses look-ahead optimization to reduce volatile reads on the hot path.
  * Minimum capacity is 4 (required by lookAheadStep calculation).
  */
final private[kyo] class SpscUnsafeQueue[A](requestedCapacity: Int) extends UnsafeQueue[A]:

    private val actualCapacity = Math.max(4, UnsafeQueue.roundToPowerOfTwo(requestedCapacity))
    private val mask           = actualCapacity - 1
    // No init needed: AtomicReferenceArray defaults to null, which is the empty sentinel.
    private val buffer        = new AtomicReferenceArray[AnyRef](actualCapacity)
    private val producerIndex = new AtomicLong(0L)
    private val consumerIndex = new AtomicLong(0L)
    private val lookAheadStep = actualCapacity / 4
    // unsafe: mutable field, only accessed by producer thread
    private var producerLimit = 0L

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
        if pIdx >= producerLimit then
            // unsafe: slow path — check look-ahead
            if isNull(buffer.get(((pIdx + lookAheadStep) & mask).toInt)) then
                producerLimit = pIdx + lookAheadStep
            else if !isNull(buffer.get(offset)) then
                // unsafe: truly full
                return false
        end if
        buffer.lazySet(offset, a.asInstanceOf[AnyRef])
        producerIndex.lazySet(pIdx + 1)
        true
    end offer

    def poll()(using AllowUnsafe): Maybe[A] =
        val cIdx   = consumerIndex.get()
        val offset = (cIdx & mask).toInt
        val e      = buffer.get(offset)
        if isNull(e) then Absent
        else
            buffer.lazySet(offset, null)
            consumerIndex.lazySet(cIdx + 1)
            Maybe(e.asInstanceOf[A])
        end if
    end poll

    def peek()(using AllowUnsafe): Maybe[A] =
        val cIdx   = consumerIndex.get()
        val offset = (cIdx & mask).toInt
        val e      = buffer.get(offset)
        if isNull(e) then Absent
        else Maybe(e.asInstanceOf[A])
    end peek

end SpscUnsafeQueue
