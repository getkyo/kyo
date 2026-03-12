package kyo.internal

import kyo.*
import scala.collection.mutable.ArrayDeque

/** Unbounded MPMC queue — JS single-threaded simplification of JCTools MpmcUnboundedXaddArrayQueue.
  *
  * Uses ArrayDeque instead of XADD-allocated linked chunks with pooling. No atomics or chunk pooling needed on JS.
  * The maxPooledChunks parameter is accepted for API compatibility but ignored.
  */
final private[kyo] class MpmcUnboundedUnsafeQueue[A](chunkSize: Int, maxPooledChunks: Int = 2) extends UnsafeQueue[A]:

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

end MpmcUnboundedUnsafeQueue

private[kyo] object MpmcUnboundedUnsafeQueue
