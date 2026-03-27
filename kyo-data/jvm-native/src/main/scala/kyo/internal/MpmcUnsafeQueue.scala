package kyo.internal

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray
import kyo.*
import scala.annotation.tailrec

/** Lock-free bounded MPMC queue. Port of JCTools MpmcAtomicArrayQueue (D. Vyukov algorithm).
  *
  * Uses two parallel arrays: elements and sequence numbers. Sequence numbers coordinate which slots are available for writing vs reading
  * without requiring producers/consumers to observe each other's index writes directly.
  *
  * Minimum capacity is 2.
  */
final private[kyo] class MpmcUnsafeQueue[A](requestedCapacity: Int) extends UnsafeQueue[A]:

    private val actualCapacity = UnsafeQueue.roundToPowerOfTwo(Math.max(2, requestedCapacity))
    private val mask           = actualCapacity - 1
    private val lookAheadStep  = Math.max(2, Math.min(actualCapacity / 4, 4096))
    // No init needed: AtomicReferenceArray defaults to null, which is the empty sentinel.
    private val buffer = new AtomicReferenceArray[AnyRef](actualCapacity)
    private val sequenceBuffer =
        val b = new AtomicLongArray(actualCapacity)
        @tailrec def init(i: Int): Unit =
            if i < actualCapacity then
                b.lazySet(i, i.toLong)
                init(i + 1)
        init(0)
        b
    end sequenceBuffer
    private val producerIndex = new AtomicLong(0L)
    private val consumerIndex = new AtomicLong(0L)

    def capacity: Int = actualCapacity

    def size()(using AllowUnsafe): Int =
        UnsafeQueue.currentSize(producerIndex, consumerIndex, actualCapacity)

    def isEmpty()(using AllowUnsafe): Boolean =
        consumerIndex.get() >= producerIndex.get()

    def isFull()(using AllowUnsafe): Boolean =
        producerIndex.get() - consumerIndex.get() >= actualCapacity

    def offer(a: A)(using AllowUnsafe): Boolean =
        UnsafeQueue.checkNotNull(a)
        @tailrec def loop(cIndex: Long): Boolean =
            val pIdx      = producerIndex.get()
            val seqOffset = (pIdx & mask).toInt
            val seq       = sequenceBuffer.get(seqOffset)
            if seq < pIdx then
                // consumer hasn't freed this slot yet
                if pIdx - actualCapacity >= cIndex then
                    val newCIndex = consumerIndex.get()
                    if pIdx - actualCapacity >= newCIndex then
                        false // full
                    else
                        loop(newCIndex)
                    end if
                else
                    loop(cIndex)
            else if seq <= pIdx && producerIndex.compareAndSet(pIdx, pIdx + 1) then
                buffer.lazySet(seqOffset, a.asInstanceOf[AnyRef])
                sequenceBuffer.lazySet(seqOffset, pIdx + 1)
                true
            else
                loop(cIndex)
            end if
        end loop
        loop(Long.MinValue)
    end offer

    def poll()(using AllowUnsafe): Maybe[A] =
        @tailrec def loop(pIndex: Long): Maybe[A] =
            val cIdx        = consumerIndex.get()
            val seqOffset   = (cIdx & mask).toInt
            val seq         = sequenceBuffer.get(seqOffset)
            val expectedSeq = cIdx + 1
            if seq < expectedSeq then
                // producer hasn't filled this slot yet
                if cIdx >= pIndex then
                    val newPIndex = producerIndex.get()
                    if cIdx == newPIndex then Absent
                    else loop(newPIndex)
                else
                    loop(pIndex)
            else if seq <= expectedSeq && consumerIndex.compareAndSet(cIdx, cIdx + 1) then
                val e = buffer.get(seqOffset)
                buffer.lazySet(seqOffset, null)
                sequenceBuffer.lazySet(seqOffset, cIdx + mask + 1)
                Maybe(e.asInstanceOf[A])
            else
                loop(pIndex)
            end if
        end loop
        loop(-1L)
    end poll

    def peek()(using AllowUnsafe): Maybe[A] =
        @tailrec def loop(): Maybe[A] =
            val cIdx        = consumerIndex.get()
            val seqOffset   = (cIdx & mask).toInt
            val seq         = sequenceBuffer.get(seqOffset)
            val expectedSeq = cIdx + 1
            if seq < expectedSeq then
                if cIdx == producerIndex.get() then Absent
                else loop()
            else if seq == expectedSeq then
                val e = buffer.get(seqOffset)
                // verify cIdx hasn't advanced
                if consumerIndex.get() == cIdx && !isNull(e) then Maybe(e.asInstanceOf[A])
                else loop()
            else
                loop()
            end if
        end loop
        loop()
    end peek

    override def drain(f: A => Unit, limit: Int)(using AllowUnsafe): Int =
        @tailrec def loop(consumed: Int): Int =
            if consumed >= limit then consumed
            else
                val remaining = limit - consumed
                val lookAhead = Math.min(remaining, lookAheadStep)
                val cIdx      = consumerIndex.get()

                val lookAheadIdx       = cIdx + lookAhead - 1
                val lookAheadSeqOffset = (lookAheadIdx & mask).toInt
                val lookAheadSeq       = sequenceBuffer.get(lookAheadSeqOffset)

                if lookAheadSeq == lookAheadIdx + 1 && consumerIndex.compareAndSet(cIdx, cIdx + lookAhead) then
                    // fast path: batch claim
                    @tailrec def processBatch(i: Int): Unit =
                        if i < lookAhead then
                            val idx    = cIdx + i
                            val seqOff = (idx & mask).toInt
                            // unsafe: spin until producer finishes storing
                            @tailrec def spinForSeq(): Unit =
                                if sequenceBuffer.get(seqOff) != idx + 1 then spinForSeq()
                            spinForSeq()
                            val e = buffer.get(seqOff)
                            buffer.lazySet(seqOff, null)
                            sequenceBuffer.lazySet(seqOff, idx + mask + 1)
                            f(e.asInstanceOf[A])
                            processBatch(i + 1)
                    processBatch(0)
                    loop(consumed + lookAhead)
                else
                    // slow path: one-by-one
                    @tailrec def pollOne(i: Int): Int =
                        if i < remaining then
                            poll() match
                                case Maybe.Present(v) =>
                                    f(v)
                                    pollOne(i + 1)
                                case _ =>
                                    consumed + i
                        else
                            consumed + remaining
                    pollOne(0)
                end if
        loop(0)
    end drain

end MpmcUnsafeQueue
