package kyo.internal

import kyo.*

/** Bounded MPMC queue — JS single-threaded simplification of JCTools MpmcAtomicArrayQueue (Vyukov algorithm).
  *
  * Uses a plain ring buffer with head/tail indices. No sequence numbers or CAS needed on JS. Minimum capacity is 2 (matches JVM/Native).
  */
final private[kyo] class MpmcUnsafeQueue[A](requestedCapacity: Int) extends UnsafeQueue[A]:

    private val actualCapacity = UnsafeQueue.roundToPowerOfTwo(Math.max(2, requestedCapacity))
    private val mask           = actualCapacity - 1
    private val buf            = new Array[AnyRef](actualCapacity)
    private var head           = 0
    private var tail           = 0

    def capacity: Int = actualCapacity

    def size()(using AllowUnsafe): Int = tail - head

    def isEmpty()(using AllowUnsafe): Boolean = head == tail

    def isFull()(using AllowUnsafe): Boolean = tail - head >= actualCapacity

    def offer(a: A)(using AllowUnsafe): Boolean =
        UnsafeQueue.checkNotNull(a)
        if tail - head >= actualCapacity then false
        else
            buf(tail & mask) = a.asInstanceOf[AnyRef]
            tail += 1
            true
        end if
    end offer

    def poll()(using AllowUnsafe): Maybe[A] =
        if head == tail then Absent
        else
            val idx = head & mask
            val e   = buf(idx)
            buf(idx) = null
            head += 1
            Maybe(e.asInstanceOf[A])

    def peek()(using AllowUnsafe): Maybe[A] =
        if head == tail then Absent
        else Maybe(buf(head & mask).asInstanceOf[A])

end MpmcUnsafeQueue
