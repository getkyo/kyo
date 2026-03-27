package kyo.internal

import java.util.concurrent.atomic.AtomicLong
import kyo.*
import scala.annotation.tailrec

/** Lock-free queue base class. All implementations are ports of JCTools `atomic` package algorithms. */
abstract private[kyo] class UnsafeQueue[A]:
    def capacity: Int
    def size()(using AllowUnsafe): Int
    def isEmpty()(using AllowUnsafe): Boolean
    def isFull()(using AllowUnsafe): Boolean
    def offer(a: A)(using AllowUnsafe): Boolean
    def poll()(using AllowUnsafe): Maybe[A]
    def peek()(using AllowUnsafe): Maybe[A]

    def drain(f: A => Unit)(using AllowUnsafe): Int =
        @tailrec def loop(count: Int): Int =
            poll() match
                case Maybe.Present(v) =>
                    f(v)
                    loop(count + 1)
                case _ =>
                    count
        loop(0)
    end drain

    def drain(f: A => Unit, limit: Int)(using AllowUnsafe): Int =
        @tailrec def loop(i: Int): Int =
            if i < limit then
                poll() match
                    case Maybe.Present(v) =>
                        f(v)
                        loop(i + 1)
                    case _ =>
                        i
            else i
        loop(0)
    end drain
end UnsafeQueue

private[kyo] object UnsafeQueue:

    inline def checkNotNull[A](a: A): Unit =
        if isNull(a) then
            throw new NullPointerException("Null elements are not allowed")

    inline def roundToPowerOfTwo(value: Int): Int =
        1 << (32 - Integer.numberOfLeadingZeros(value - 1))

    /** Sandwiched size calculation from JCTools IndexedQueueSizeUtil. Reads cIndex, pIndex, cIndex to get accurate size under contention.
      */
    def currentSize(producerIndex: AtomicLong, consumerIndex: AtomicLong, capacity: Int, divisor: Int = 1): Int =
        @tailrec def loop(after: Long): Int =
            val before   = after
            val pIndex   = producerIndex.get()
            val newAfter = consumerIndex.get()
            if before == newAfter then
                val size = (pIndex - newAfter) / divisor
                Math.max(0L, Math.min(size, capacity.toLong)).toInt
            else
                loop(newAfter)
            end if
        end loop
        loop(consumerIndex.get())
    end currentSize

end UnsafeQueue
