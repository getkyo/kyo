package kyo.internal

import kyo.*
import scala.collection.mutable.ArrayDeque

/** JS single-threaded base for all unbounded queue variants.
  *
  * On JS there's no concurrency, so all MPMC/MPSC/SPMC/SPSC unbounded queues reduce to a simple ArrayDeque wrapper.
  */
private[internal] class UnboundedUnsafeQueueBase[A](initialCapacity: Int) extends UnsafeQueue[A]:

    private val buf = new ArrayDeque[AnyRef](initialCapacity)

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

end UnboundedUnsafeQueueBase
