package kyo.internal

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.*
import scala.annotation.tailrec

/** Lock-free bounded MPSC queue. Port of JCTools MpscAtomicArrayQueue.
  *
  * Multiple producers CAS on producerIndex. Single consumer needs no CAS. Uses producer limit caching to avoid reading consumerIndex on
  * every offer.
  */
final private[kyo] class MpscUnsafeQueue[A](requestedCapacity: Int) extends UnsafeQueue[A]:

    private val actualCapacity = UnsafeQueue.roundToPowerOfTwo(Math.max(2, requestedCapacity))
    private val mask           = actualCapacity - 1
    // No init needed: AtomicReferenceArray defaults to null, which is the empty sentinel.
    private val buffer        = new AtomicReferenceArray[AnyRef](actualCapacity)
    private val producerIndex = new AtomicLong(0L)
    private val consumerIndex = new AtomicLong(0L)
    private val producerLimit = new AtomicLong(actualCapacity.toLong)

    def capacity: Int = actualCapacity

    def size()(using AllowUnsafe): Int =
        UnsafeQueue.currentSize(producerIndex, consumerIndex, actualCapacity)

    def isEmpty()(using AllowUnsafe): Boolean =
        consumerIndex.get() >= producerIndex.get()

    def isFull()(using AllowUnsafe): Boolean =
        producerIndex.get() - consumerIndex.get() >= actualCapacity

    def offer(a: A)(using AllowUnsafe): Boolean =
        UnsafeQueue.checkNotNull(a)
        @tailrec def loop(): Boolean =
            val pIdx = producerIndex.get()
            if pIdx >= producerLimit.get() then
                val cIdx     = consumerIndex.get()
                val newLimit = cIdx + mask + 1
                if pIdx >= newLimit then
                    return false
                producerLimit.lazySet(newLimit)
            end if
            if producerIndex.compareAndSet(pIdx, pIdx + 1) then
                buffer.lazySet((pIdx & mask).toInt, a.asInstanceOf[AnyRef])
                true
            else
                loop()
            end if
        end loop
        loop()
    end offer

    def poll()(using AllowUnsafe): Maybe[A] =
        val cIdx   = consumerIndex.get()
        val offset = (cIdx & mask).toInt
        val e      = buffer.get(offset)
        if isNull(e) then
            if cIdx != producerIndex.get() then
                // unsafe: producer won CAS but hasn't stored yet — spin
                @tailrec def spin(): AnyRef =
                    val v = buffer.get(offset)
                    if isNull(v) then spin() else v
                val v = spin()
                buffer.lazySet(offset, null)
                consumerIndex.lazySet(cIdx + 1)
                Maybe(v.asInstanceOf[A])
            else
                Absent
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
        if isNull(e) then
            if cIdx != producerIndex.get() then
                // unsafe: spin for element
                @tailrec def spin(): AnyRef =
                    val v = buffer.get(offset)
                    if isNull(v) then spin() else v
                Maybe(spin().asInstanceOf[A])
            else
                Absent
        else
            Maybe(e.asInstanceOf[A])
        end if
    end peek

end MpscUnsafeQueue
