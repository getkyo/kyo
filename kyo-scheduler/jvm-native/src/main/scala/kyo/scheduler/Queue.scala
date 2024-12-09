package kyo.scheduler

import java.lang.invoke.VarHandle
import java.util.concurrent.atomic.AtomicBoolean
import kyo.*
import scala.collection.mutable.PriorityQueue

/** Specialized concurrent priority queue supporting atomic batch operations and work stealing.
  *
  * This queue implementation extends beyond standard concurrent queue operations to provide atomic batch transfers and priority-based
  * ordering. While designed primarily for task scheduling, the core operations are generally applicable to producer-consumer scenarios
  * requiring work stealing and load balancing.
  *
  * The implementation uses explicit locking via AtomicBoolean for thread safety while supporting specialized operations like atomic batch
  * transfers. Priority ordering is maintained using the implicit ordering, typically based on runtime or cost metrics in scheduling
  * contexts.
  *
  * The scheduler uses this queue as the foundation for task distribution between workers, leveraging the work stealing and draining
  * capabilities for load balancing and worker management.
  *
  * @tparam A
  *   The element type, ordered by the implicit Ordering
  * @param ord
  *   Implicit ordering for priority queue behavior
  */
final class Queue[A](implicit ord: Ordering[A]) extends AtomicBoolean {

    private val queue = PriorityQueue[A]()

    private var items = 0

    /** Tests if queue contains no elements. Provides a non-blocking snapshot of empty state.
      */
    def isEmpty() =
        size() == 0

    /** Returns number of elements in queue. Non-blocking operation with memory fence for cross-thread visibility.
      */
    def size(): Int = {
        VarHandle.acquireFence()
        items
    }

    /** Adds element to queue in priority order. Acquires lock for thread-safe insertion.
      */
    def add(value: A): Unit = {
        lock()
        try {
            items += 1
            queue += value
            ()
        } finally
            unlock()
    }

    /** Attempts non-blocking element addition.
      */
    def offer(value: A): Boolean =
        tryLock() && {
            try {
                items += 1
                queue += value
                true
            } finally
                unlock()
        }

    /** Retrieves and removes highest priority element.
      */
    def poll(): A =
        if (isEmpty())
            null.asInstanceOf[A]
        else {
            lock()
            try {
                if (isEmpty())
                    null.asInstanceOf[A]
                else {
                    items -= 1
                    queue.dequeue()
                }
            } finally
                unlock()
        }

    /** Atomically exchanges input element for highest priority element.
      *
      * Returns input if queue is empty, otherwise adds input and returns previous highest priority element as single atomic operation.
      */
    def addAndPoll(value: A): A =
        if (isEmpty())
            value
        else {
            lock()
            try {
                if (isEmpty()) value
                else {
                    val r = queue.dequeue()
                    queue += value
                    r
                }
            } finally
                unlock()
        }

    /** Atomically transfers elements between queues.
      *
      * Moves highest priority element plus approximately half of remaining elements to target queue. Operation only succeeds if target is
      * empty and both locks can be acquired without blocking. Maintains priority ordering in both queues.
      *
      * Used for work stealing in scheduling contexts.
      *
      * @param to
      *   recipient queue for stolen elements
      * @return
      *   highest priority element that was transferred, or null if transfer failed
      */
    def stealingBy(to: Queue[A]): A = {
        var t: A = null.asInstanceOf[A]
        val _ =
            !isEmpty() && tryLock() && {
                try {
                    !isEmpty() && to.isEmpty() && to.tryLock() && {
                        try {
                            t = queue.dequeue()
                            val s = size() - 1
                            var i = s - Math.ceil(s.toDouble / 2).intValue()
                            items -= i + 1
                            to.items += i
                            while (i > 0) {
                                to.queue += queue.dequeue()
                                i -= 1
                            }
                            true
                        } finally
                            to.unlock()
                    }
                } finally
                    unlock()
            }
        t
    }

    /** Atomically removes and processes all elements.
      *
      * Locks queue, removes all elements, then applies function to each removed element after lock release. Used for queue shutdown and
      * rebalancing.
      */
    def drain(f: A => Unit): Unit =
        if (!isEmpty()) {
            val tasks = {
                lock()
                try {
                    items = 0
                    queue.dequeueAll
                } finally
                    unlock()
            }
            tasks.foreach(f)
        }

    /** Atomically removes and returns all elements as a sequence.
      *
      * Locks queue, removes all elements, then returns all elements as a sequence after lock release. Used for initializing a Stream from a
      * Queue.
      */
    def drainToSeq(): Seq[A] =
        if (!isEmpty()) {
            lock()
            try {
                items = 0
                queue.dequeueAll
            } finally
                unlock()
        } else
            Seq.empty

    /** Acquires queue lock using busy-wait spin loop.
      *
      * Uses busy waiting rather than parking/blocking since:
      *   - Effective concurrency is bounded by available cores
      *   - Lock hold times are very short (optimized queue operations)
      *   - Most contended operations (stealing) fail fast if lock unavailable
      *
      * The scheduler's worker model ensures the number of threads competing for any given queue is limited, making spinning more efficient
      * than thread parking overhead.
      */
    private def lock(): Unit =
        while (!compareAndSet(false, true)) {}

    private def tryLock(): Boolean =
        compareAndSet(false, true)

    private def unlock(): Unit =
        set(false)
}
