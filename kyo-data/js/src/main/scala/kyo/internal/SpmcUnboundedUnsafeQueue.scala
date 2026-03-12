package kyo.internal

import kyo.*
import scala.collection.mutable.ArrayDeque

/** Unbounded SPMC queue — JS single-threaded simplification of the JVM/Native lock-free linked-chunk SPMC queue.
  *
  * Uses ArrayDeque instead of linked chunks with AtomicReference navigation. No atomics needed on JS.
  */
final private[kyo] class SpmcUnboundedUnsafeQueue[A](chunkSize: Int) extends UnsafeQueue[A]:

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

end SpmcUnboundedUnsafeQueue

private[kyo] object SpmcUnboundedUnsafeQueue
