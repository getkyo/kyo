package kyo

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.jctools.queues.*
import scala.annotation.tailrec

/** A thread-safe queue implementation based on JCTools.
  *
  * This queue provides various concurrency-safe operations and supports different access patterns (MPMC, MPSC, SPMC, SPSC) using JCTools'
  * efficient non-blocking data structures under the hood.
  *
  * @tparam A
  *   the type of elements in the queue
  */
class Queue[A] private[kyo] (initFrame: Frame, private[kyo] val unsafe: Queue.Unsafe[A]):

    /** Returns the capacity of the queue.
      *
      * @return
      *   the capacity of the queue
      */
    final def capacity(using Frame): Int = unsafe.capacity

    /** Returns the current size of the queue.
      *
      * @return
      *   the current size of the queue
      */
    final def size(using Frame): Int < IO = op(unsafe.size())

    /** Checks if the queue is empty.
      *
      * @return
      *   true if the queue is empty, false otherwise
      */
    final def empty(using Frame): Boolean < IO = op(unsafe.empty())

    /** Checks if the queue is full.
      *
      * @return
      *   true if the queue is full, false otherwise
      */
    final def full(using Frame): Boolean < IO = op(unsafe.full())

    /** Offers an element to the queue.
      *
      * @param v
      *   the element to offer
      * @return
      *   true if the element was added, false if the queue is full or closed
      */
    final def offer(v: A)(using Frame): Boolean < IO = IO(!unsafe.closed() && unsafe.offer(v))

    /** Polls an element from the queue.
      *
      * @return
      *   Maybe containing the polled element, or empty if the queue is empty
      */
    final def poll(using Frame): Maybe[A] < IO = op(Maybe(unsafe.poll()))

    /** Peeks at the first element in the queue without removing it.
      *
      * @return
      *   Maybe containing the first element, or empty if the queue is empty
      */
    final def peek(using Frame): Maybe[A] < IO = op(Maybe(unsafe.peek()))

    /** Drains all elements from the queue.
      *
      * @return
      *   a sequence of all elements in the queue
      */
    final def drain(using Frame): Seq[A] < IO = op(unsafe.drain())

    /** Checks if the queue is closed.
      *
      * @return
      *   true if the queue is closed, false otherwise
      */
    final def closed(using Frame): Boolean < IO = IO(unsafe.closed())

    /** Closes the queue and returns any remaining elements.
      *
      * @return
      *   Maybe containing a sequence of remaining elements, or empty if already closed
      */
    final def close(using Frame): Maybe[Seq[A]] < IO = IO(unsafe.close())

    protected inline def op[A, S](inline v: => A < (IO & S))(using frame: Frame): A < (IO & S) =
        IO {
            if unsafe.closed() then
                throw Closed("Queue", initFrame, frame)
            else
                v
        }
end Queue

/** Companion object for Queue, containing factory methods and nested classes.
  *
  * This object provides various initialization methods for different types of queues, all based on JCTools' concurrent queue
  * implementations.
  */
object Queue:

    abstract private[kyo] class Unsafe[A]
        extends AtomicBoolean(false):
        def capacity: Int
        def size(): Int
        def empty(): Boolean
        def full(): Boolean
        def offer(v: A): Boolean
        def poll(): A
        def peek(): A
        final def drain(): Seq[A] =
            val b = Seq.newBuilder[A]
            @tailrec def loop(): Unit =
                val v = poll()
                if !isNull(v) then
                    b += v
                    loop()
            end loop
            loop()
            b.result()
        end drain

        final def closed(): Boolean =
            super.get()

        final def close(): Maybe[Seq[A]] =
            super.compareAndSet(false, true) match
                case false =>
                    Maybe.empty
                case true =>
                    Maybe(drain())
    end Unsafe

    /** An unbounded queue that can grow indefinitely.
      *
      * @tparam A
      *   the type of elements in the queue
      */
    class Unbounded[A] private[kyo] (initFrame: Frame, unsafe: Queue.Unsafe[A]) extends Queue[A](initFrame, unsafe):
        /** Adds an element to the unbounded queue.
          *
          * @param v
          *   the element to add
          */
        final def add[S](v: A < S)(using Frame): Unit < (IO & S) =
            op(v.map(offer).unit)
    end Unbounded

    /** Initializes a new queue with the specified capacity and access pattern.
      *
      * @param capacity
      *   the capacity of the queue
      * @param access
      *   the access pattern (default is MPMC)
      * @return
      *   a new Queue instance
      */
    def init[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using frame: Frame): Queue[A] < IO =
        IO {
            capacity match
                case c if (c <= 0) =>
                    new Queue(
                        frame,
                        new Unsafe[A]:
                            def capacity    = 0
                            def size()      = 0
                            def empty()     = true
                            def full()      = true
                            def offer(v: A) = false
                            def poll()      = null.asInstanceOf[A]
                            def peek()      = null.asInstanceOf[A]
                    )
                case 1 =>
                    new Queue(
                        frame,
                        new Unsafe[A]:
                            val state       = new AtomicReference[A]
                            def capacity    = 1
                            def size()      = if isNull(state.get()) then 0 else 1
                            def empty()     = isNull(state.get())
                            def full()      = !isNull(state.get())
                            def offer(v: A) = state.compareAndSet(null.asInstanceOf[A], v)
                            def poll()      = state.getAndSet(null.asInstanceOf[A])
                            def peek()      = state.get()
                    )
                case Int.MaxValue =>
                    initUnbounded(access)
                case _ =>
                    access match
                        case Access.MultiProducerMultiConsumer =>
                            fromJava(new MpmcArrayQueue[A](capacity), capacity)
                        case Access.MultiProducerSingleConsumer =>
                            fromJava(new MpscArrayQueue[A](capacity), capacity)
                        case Access.SingleProducerMultiConsumer =>
                            fromJava(new SpmcArrayQueue[A](capacity), capacity)
                        case Access.SingleProducerSingleConsumer =>
                            if capacity >= 4 then
                                fromJava(new SpscArrayQueue[A](capacity), capacity)
                            else
                                // Spsc queue doesn't support capacity < 4
                                fromJava(new SpmcArrayQueue[A](capacity), capacity)
        }

    /** Initializes a new unbounded queue with the specified access pattern and chunk size.
      *
      * @param access
      *   the access pattern (default is MPMC)
      * @param chunkSize
      *   the chunk size for internal array allocation (default is 8)
      * @return
      *   a new Unbounded Queue instance
      */
    def initUnbounded[A](access: Access = Access.MultiProducerMultiConsumer, chunkSize: Int = 8)(using Frame): Unbounded[A] < IO =
        IO {
            access match
                case Access.MultiProducerMultiConsumer =>
                    fromJava(new MpmcUnboundedXaddArrayQueue[A](chunkSize))
                case Access.MultiProducerSingleConsumer =>
                    fromJava(new MpscUnboundedArrayQueue[A](chunkSize))
                case Access.SingleProducerMultiConsumer =>
                    fromJava(new MpmcUnboundedXaddArrayQueue[A](chunkSize))
                case Access.SingleProducerSingleConsumer =>
                    fromJava(new SpscUnboundedArrayQueue[A](chunkSize))
        }

    /** Initializes a new dropping queue with the specified capacity and access pattern.
      *
      * @param capacity
      *   the capacity of the queue
      * @param access
      *   the access pattern (default is MPMC)
      * @return
      *   a new Unbounded Queue instance that drops elements when full
      */
    def initDropping[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using frame: Frame): Unbounded[A] < IO =
        init[A](capacity, access).map { q =>
            val u = q.unsafe
            val c = capacity
            new Unbounded(
                frame,
                new Unsafe[A]:
                    def capacity = c
                    def size()   = u.size()
                    def empty()  = u.empty()
                    def full()   = false
                    def offer(v: A) =
                        u.offer(v)
                        true
                    def poll() = u.poll()
                    def peek() = u.peek()
            )
        }

    /** Initializes a new sliding queue with the specified capacity and access pattern.
      *
      * @param capacity
      *   the capacity of the queue
      * @param access
      *   the access pattern (default is MPMC)
      * @return
      *   a new Unbounded Queue instance that slides elements when full
      */
    def initSliding[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using frame: Frame): Unbounded[A] < IO =
        init[A](capacity, access).map { q =>
            val u = q.unsafe
            val c = capacity
            new Unbounded(
                frame,
                new Unsafe[A]:
                    def capacity = c
                    def size()   = u.size()
                    def empty()  = u.empty()
                    def full()   = false
                    def offer(v: A) =
                        @tailrec def loop(v: A): Unit =
                            val u = q.unsafe
                            if u.offer(v) then ()
                            else
                                u.poll()
                                loop(v)
                            end if
                        end loop
                        loop(v)
                        true
                    end offer
                    def poll() = u.poll()
                    def peek() = u.peek()
            )
        }

    private def fromJava[A](q: java.util.Queue[A])(using frame: Frame): Unbounded[A] =
        new Unbounded(
            frame,
            new Unsafe[A]:
                def capacity    = Int.MaxValue
                def size()      = q.size
                def empty()     = q.isEmpty()
                def full()      = false
                def offer(v: A) = q.offer(v)
                def poll()      = q.poll
                def peek()      = q.peek
        )

    private def fromJava[A](q: java.util.Queue[A], _capacity: Int)(using frame: Frame): Queue[A] =
        new Queue(
            frame,
            new Unsafe[A]:
                def capacity    = _capacity
                def size()      = q.size
                def empty()     = q.isEmpty()
                def full()      = q.size >= _capacity
                def offer(v: A) = q.offer(v)
                def poll()      = q.poll
                def peek()      = q.peek
        )
end Queue
