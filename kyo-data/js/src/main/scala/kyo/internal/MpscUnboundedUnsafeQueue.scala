package kyo.internal

import kyo.*
import scala.collection.mutable.ArrayDeque

/** Unbounded MPSC queue — JS single-threaded simplification of JCTools MpscUnboundedAtomicArrayQueue.
  *
  * Uses ArrayDeque instead of linked chunks with parity-bit resize protocol. No atomics or resize CAS needed on JS.
  */
final private[kyo] class MpscUnboundedUnsafeQueue[A](chunkSize: Int) extends UnsafeQueue[A]:

    private val buf = new ArrayDeque[AnyRef](Math.max(2, chunkSize))

    def capacity: Int = Int.MaxValue

    def size()(using AllowUnsafe): Int = buf.size

    def isEmpty()(using AllowUnsafe): Boolean = buf.isEmpty

    def isFull()(using AllowUnsafe): Boolean = false

    def offer(a: A)(using AllowUnsafe): Boolean =
        UnsafeQueue.checkNotNull(a)
        buf.append(a.asInstanceOf[AnyRef])
        true
    end offer

    def poll()(using AllowUnsafe): Maybe[A] =
        if buf.isEmpty then Absent
        else Maybe(buf.removeHead().asInstanceOf[A])

    def peek()(using AllowUnsafe): Maybe[A] =
        if buf.isEmpty then Absent
        else Maybe(buf.head.asInstanceOf[A])

end MpscUnboundedUnsafeQueue

private[kyo] object MpscUnboundedUnsafeQueue
