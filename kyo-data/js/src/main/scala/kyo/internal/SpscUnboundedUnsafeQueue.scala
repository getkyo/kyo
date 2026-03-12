package kyo.internal

import kyo.*
import scala.collection.mutable.ArrayDeque

/** Unbounded SPSC queue — JS single-threaded simplification of JCTools SpscUnboundedAtomicArrayQueue.
  *
  * Uses ArrayDeque instead of linked AtomicReferenceArray chunks. No atomics or chunk linking needed on JS.
  */
final private[kyo] class SpscUnboundedUnsafeQueue[A](chunkSize: Int) extends UnsafeQueue[A]:

    private val buf = new ArrayDeque[AnyRef](Math.max(8, chunkSize))

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

end SpscUnboundedUnsafeQueue

private[kyo] object SpscUnboundedUnsafeQueue
