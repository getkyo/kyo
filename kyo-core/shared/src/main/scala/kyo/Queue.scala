package kyo

import java.util.concurrent.atomic.AtomicReference
import org.jctools.queues.*
import scala.annotation.tailrec

/** A high-performance, thread-safe queue with configurable concurrency patterns.
  *
  * Queue provides a comprehensive foundation for concurrent data transfer with customizable concurrency patterns (see [[kyo.Access]]).
  * Unlike Channel which focuses on fiber-aware communication, Queue directly exposes a lower-level interface optimized for raw throughput
  * and minimal synchronization overhead.
  *
  * Key features:
  *   - Non-blocking, lock-free implementation (using JCTools on the JVM, with platform-specific implementations elsewhere)
  *   - Different specialized implementations for various producer-consumer patterns
  *   - Bounded capacity (`Queue.init`) with clear backpressure semantics
  *   - Unbounded variants with different overflow strategies:
  *     - Regular unbounded (`Queue.Unbounded.init`): grows as needed without bounds
  *     - Dropping (`Queue.Unbounded.initDropping`): discards new elements when full
  *     - Sliding (`Queue.Unbounded.initSliding`): discards oldest elements when full
  *   - Safe concurrent access with proper failure handling
  *
  * The standard Queue has fixed capacity (bounded), providing natural backpressure. For scenarios requiring dynamic sizing, Queue.Unbounded
  * offers strategies that either grow indefinitely or handle overflow by dropping elements, ensuring operations like offer/poll remain
  * non-blocking even under high load.
  *
  * WARNING: Unbounded queues can lead to memory exhaustion if producers consistently outpace consumers. In production systems, bounded
  * queues or overflow strategies (dropping/sliding) are generally safer choices unless you can guarantee bounded growth.
  *
  * @see
  *   [[kyo.Channel]] For a higher-level, fiber-aware communication primitive
  * @see
  *   [[kyo.Access]] For available producer-consumer access patterns
  * @see
  *   [[kyo.Queue.Unbounded]] For dynamically-sized queue variant
  *
  * @tparam A
  *   the type of elements in the queue
  */
opaque type Queue[A] = Queue.Unsafe[A]

object Queue:

    extension [A](self: Queue[A])
        /** Returns the capacity of the queue.
          *
          * @return
          *   the capacity of the queue
          */
        def capacity: Int = self.capacity

        /** Returns the current size of the queue.
          *
          * @return
          *   the current size of the queue
          */
        def size(using Frame): Int < (Sync & Abort[Closed]) = Sync.Unsafe(Abort.get(self.size()))

        /** Checks if the queue is empty.
          *
          * @return
          *   true if the queue is empty, false otherwise
          */
        def empty(using Frame): Boolean < (Sync & Abort[Closed]) = Sync.Unsafe(Abort.get(self.empty()))

        /** Checks if the queue is full.
          *
          * @return
          *   true if the queue is full, false otherwise
          */
        def full(using Frame): Boolean < (Sync & Abort[Closed]) = Sync.Unsafe(Abort.get(self.full()))

        /** Offers an element to the queue.
          *
          * @param v
          *   the element to offer
          * @return
          *   true if the element was added, false if the queue is full or closed
          */
        def offer(v: A)(using Frame): Boolean < (Sync & Abort[Closed]) = Sync.Unsafe(Abort.get(self.offer(v)))

        /** Offers an element to the queue and discards the result
          *
          * @param v
          *   the element to offer
          */
        def offerDiscard(v: A)(using Frame): Unit < (Sync & Abort[Closed]) = Sync.Unsafe(Abort.get(self.offer(v).unit))

        /** Polls an element from the queue.
          *
          * @return
          *   Maybe containing the polled element, or empty if the queue is empty
          */
        def poll(using Frame): Maybe[A] < (Sync & Abort[Closed]) = Sync.Unsafe(Abort.get(self.poll()))

        /** Peeks at the first element in the queue without removing it.
          *
          * @return
          *   Maybe containing the first element, or empty if the queue is empty
          */
        def peek(using Frame): Maybe[A] < (Sync & Abort[Closed]) = Sync.Unsafe(Abort.get(self.peek()))

        /** Drains all elements from the queue.
          *
          * @return
          *   a sequence of all elements in the queue
          */
        def drain(using Frame): Chunk[A] < (Sync & Abort[Closed]) = Sync.Unsafe(Abort.get(self.drain()))

        /** Takes up to [[max]] elements from the queue.
          *
          * @return
          *   a sequence of up to [[max]] elements from the queue.
          */
        def drainUpTo(max: Int)(using Frame): Chunk[A] < (Sync & Abort[Closed]) = Sync.Unsafe(Abort.get(self.drainUpTo(max)))

        /** Closes the queue and returns any remaining elements.
          *
          * @return
          *   a sequence of remaining elements
          */
        def close(using Frame): Maybe[Seq[A]] < Sync = Sync.Unsafe(self.close())

        /** Closes the queue and asynchronously waits until it's empty.
          *
          * This method closes the queue to new elements and returns a computation that completes when all elements have been consumed.
          * Unlike the regular `close` method, this allows consumers to process all remaining elements before considering the queue fully
          * closed.
          *
          * @return
          *   true if the queue was successfully closed and emptied, false if it was already closed or another closeAwaitEmpty is already
          *   running.
          */
        def closeAwaitEmpty(using Frame): Boolean < Async = Sync.Unsafe(self.closeAwaitEmpty().safe.get)

        /** Checks if the queue is closed.
          *
          * @return
          *   true if the queue is closed, false otherwise
          */
        def closed(using Frame): Boolean < Sync = Sync.Unsafe(self.closed())

        /** Returns the unsafe version of the queue.
          *
          * @return
          *   the unsafe version of the queue
          */
        def unsafe: Unsafe[A] = self
    end extension

    /** Initializes a new queue with the specified capacity and access pattern. The actual capacity will be rounded up to the next power of
      * two.
      *
      * @param capacity
      *   the desired capacity of the queue. Note that this will be rounded up to the next power of two.
      * @param access
      *   the access pattern (default is MPMC)
      * @return
      *   a new Queue instance with a capacity that is the next power of two greater than or equal to the specified capacity
      *
      * @note
      *   The actual capacity will be rounded up to the next power of two.
      * @warning
      *   The actual capacity may be larger than the specified capacity due to rounding.
      */
    def init[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using Frame): Queue[A] < Sync =
        initWith[A](capacity, access)(identity)

    /** Uses a new Queue with the provided count.
      * @param capacity
      *   the desired capacity of the queue. Note that this will be rounded up to the next power of two.
      * @param access
      *   the access pattern (default is MPMC)
      * @param f
      *   The function to apply to the new Queue
      * @return
      *   The result of applying the function
      */
    inline def initWith[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)[B, S](inline f: Queue[A] => B < S)(
        using inline frame: Frame
    ): B < (Sync & S) =
        Sync.Unsafe(f(Unsafe.init(capacity, access)))

    /** An unbounded queue that can grow indefinitely.
      *
      * @tparam A
      *   the type of elements in the queue
      */
    opaque type Unbounded[A] <: Queue[A] = Queue[A]

    object Unbounded:
        extension [A](self: Unbounded[A])
            /** Adds an element to the unbounded queue.
              *
              * @param value
              *   the element to add
              */
            def add(value: A)(using Frame): Unit < Sync = Sync.Unsafe(Unsafe.add(self)(value))

            def unsafe: Unsafe[A] = self
        end extension

        /** Initializes a new unbounded queue with the specified access pattern and chunk size.
          *
          * @param access
          *   the access pattern (default is MPMC)
          * @param chunkSize
          *   the chunk size for internal array allocation (default is 8)
          * @return
          *   a new Unbounded Queue instance
          */
        def init[A](access: Access = Access.MultiProducerMultiConsumer, chunkSize: Int = 8)(using Frame): Unbounded[A] < Sync =
            initWith[A](access, chunkSize)(identity)

        /** Uses a new unbounded Queue with the provided count.
          * @param count
          *   The initial count for the latch
          * @param f
          *   The function to apply to the new Queue
          * @return
          *   The result of applying the function
          */
        inline def initWith[A](
            access: Access = Access.MultiProducerMultiConsumer,
            chunkSize: Int = 8
        )[B, S](inline f: Unbounded[A] => B < S)(
            using inline frame: Frame
        ): B < (Sync & S) =
            Sync.Unsafe(f(Unsafe.init(access, chunkSize)))

        /** Initializes a new dropping queue with the specified capacity and access pattern.
          *
          * @param capacity
          *   the capacity of the queue. Note that this will be rounded up to the next power of two.
          * @param access
          *   the access pattern (default is MPMC)
          * @return
          *   a new Unbounded Queue instance that drops elements when full
          *
          * @note
          *   The actual capacity will be rounded up to the next power of two.
          * @warning
          *   The actual capacity may be larger than the specified capacity due to rounding.
          */
        def initDropping[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using Frame): Unbounded[A] < Sync =
            Sync.Unsafe(Unsafe.initDropping(capacity, access))

        /** Initializes a new sliding queue with the specified capacity and access pattern.
          *
          * @param capacity
          *   the capacity of the queue. Note that this will be rounded up to the next power of two.
          * @param access
          *   the access pattern (default is MPMC)
          * @return
          *   a new Unbounded Queue instance that slides elements when full
          *
          * @note
          *   The actual capacity will be rounded up to the next power of two.
          * @warning
          *   The actual capacity may be larger than the specified capacity due to rounding.
          */
        def initSliding[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using Frame): Unbounded[A] < Sync =
            Sync.Unsafe(Unsafe.initSliding(capacity, access))

        /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
        opaque type Unsafe[A] <: Queue.Unsafe[A] = Queue[A]

        /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
        object Unsafe:
            extension [A](self: Unsafe[A])
                def add(value: A)(using AllowUnsafe, Frame): Unit = discard(self.offer(value))

            def init[A](access: Access = Access.MultiProducerMultiConsumer, chunkSize: Int = 8)(
                using
                Frame,
                AllowUnsafe
            ): Unsafe[A] =
                access match
                    case Access.MultiProducerMultiConsumer =>
                        Queue.Unsafe.fromJava(new MpmcUnboundedXaddArrayQueue[A](chunkSize))
                    case Access.MultiProducerSingleConsumer =>
                        Queue.Unsafe.fromJava(new MpscUnboundedArrayQueue[A](chunkSize))
                    case Access.SingleProducerMultiConsumer =>
                        Queue.Unsafe.fromJava(new MpmcUnboundedXaddArrayQueue[A](chunkSize))
                    case Access.SingleProducerSingleConsumer =>
                        Queue.Unsafe.fromJava(new SpscUnboundedArrayQueue[A](chunkSize))

            def initDropping[A](_capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unsafe[A] =
                new Unsafe[A]:
                    val underlying                                  = Queue.Unsafe.init[A](_capacity, access)
                    def capacity                                    = _capacity
                    def size()(using AllowUnsafe)                   = underlying.size()
                    def empty()(using AllowUnsafe)                  = underlying.empty()
                    def full()(using AllowUnsafe)                   = underlying.full().map(_ => false)
                    def offer(v: A)(using AllowUnsafe)              = underlying.offer(v).map(_ => true)
                    def poll()(using AllowUnsafe)                   = underlying.poll()
                    def drainUpTo(max: Int)(using AllowUnsafe)      = underlying.drainUpTo(max)
                    def peek()(using AllowUnsafe)                   = underlying.peek()
                    def drain()(using AllowUnsafe)                  = underlying.drain()
                    def close()(using Frame, AllowUnsafe)           = underlying.close()
                    def closeAwaitEmpty()(using Frame, AllowUnsafe) = underlying.closeAwaitEmpty()
                    def closed()(using AllowUnsafe): Boolean        = underlying.closed()
                end new
            end initDropping

            def initSliding[A](_capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unsafe[A] =
                new Unsafe[A]:
                    val underlying                 = Queue.Unsafe.init[A](_capacity, access)
                    def capacity                   = _capacity
                    def size()(using AllowUnsafe)  = underlying.size()
                    def empty()(using AllowUnsafe) = underlying.empty()
                    def full()(using AllowUnsafe)  = underlying.full().map(_ => false)
                    def offer(v: A)(using AllowUnsafe) =
                        @tailrec def loop(v: A): Result[Closed, Boolean] =
                            underlying.offer(v) match
                                case Result.Success(false) =>
                                    discard(underlying.poll())
                                    loop(v)
                                case result =>
                                    result
                        end loop
                        loop(v)
                    end offer
                    def poll()(using AllowUnsafe)                   = underlying.poll()
                    def drainUpTo(max: Int)(using AllowUnsafe)      = underlying.drainUpTo(max)
                    def peek()(using AllowUnsafe)                   = underlying.peek()
                    def drain()(using AllowUnsafe)                  = underlying.drain()
                    def close()(using Frame, AllowUnsafe)           = underlying.close()
                    def closeAwaitEmpty()(using Frame, AllowUnsafe) = underlying.closeAwaitEmpty()
                    def closed()(using AllowUnsafe): Boolean        = underlying.closed()
                end new
            end initSliding
        end Unsafe
    end Unbounded

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    sealed abstract class Unsafe[A] extends Serializable:
        def capacity: Int
        def size()(using AllowUnsafe): Result[Closed, Int]
        def empty()(using AllowUnsafe): Result[Closed, Boolean]
        def full()(using AllowUnsafe): Result[Closed, Boolean]
        def offer(v: A)(using AllowUnsafe): Result[Closed, Boolean]
        def poll()(using AllowUnsafe): Result[Closed, Maybe[A]]
        def drainUpTo(max: Int)(using AllowUnsafe): Result[Closed, Chunk[A]]
        def peek()(using AllowUnsafe): Result[Closed, Maybe[A]]
        def drain()(using AllowUnsafe): Result[Closed, Chunk[A]]
        def close()(using Frame, AllowUnsafe): Maybe[Seq[A]]
        def closeAwaitEmpty()(using Frame, AllowUnsafe): Fiber.Unsafe[Nothing, Boolean]
        def closed()(using AllowUnsafe): Boolean
        final def safe: Queue[A] = this
    end Unsafe

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:

        private enum State derives CanEqual:
            case Open
            case HalfOpen(p: Promise.Unsafe[Nothing, Boolean], r: Result.Error[Closed])
            case FullyClosed(r: Result.Error[Closed])
        end State

        sealed abstract private class Closeable[A](initFrame: Frame) extends Unsafe[A]:
            import AllowUnsafe.embrace.danger
            final private val state = AtomicRef.Unsafe.init[State](State.Open)

            final def close()(using frame: Frame, allow: AllowUnsafe) =
                val fail = Result.Failure(Closed("Queue", initFrame))
                Maybe.when(state.compareAndSet(State.Open, State.FullyClosed(fail)))(_drain())
            end close

            final def closeAwaitEmpty()(using frame: Frame, allow: AllowUnsafe): Fiber.Unsafe[Nothing, Boolean] =
                val fail = Result.Failure(Closed("Queue", initFrame))
                val p    = Promise.Unsafe.init[Nothing, Boolean]()
                if state.compareAndSet(State.Open, State.HalfOpen(p, fail)) then
                    handleHalfOpen()
                    p
                else
                    Fiber.Unsafe.init(Result.succeed(false))
                end if
            end closeAwaitEmpty

            final def closed()(using AllowUnsafe) =
                state.get().isInstanceOf[State.FullyClosed]

            final def drainUpTo(max: Int)(using AllowUnsafe): Result[Closed, Chunk[A]] = pollOp(_drain(Maybe.Present(max)))

            final def drain()(using AllowUnsafe): Result[Closed, Chunk[A]] = pollOp(_drain())

            protected def _drain(max: Maybe[Int] = Maybe.Absent): Chunk[A]
            protected def _isEmpty(): Boolean

            private def opClosed: Maybe[Result.Error[Closed]] =
                state.get() match
                    case State.FullyClosed(r) => Present(r)
                    case _                    => Absent

            protected inline def op[A](inline f: => A): Result[Closed, A] =
                opClosed.getOrElse(Result(f))

            protected inline def pollOp[A](inline f: => A): Result[Closed, A] =
                opClosed.getOrElse {
                    val r = Result(f)
                    handleHalfOpen()
                    r
                }

            private def offerClosed: Maybe[Result.Error[Closed]] =
                state.get() match
                    case State.Open           => Absent
                    case State.HalfOpen(_, r) => Present(r)
                    case State.FullyClosed(r) => Present(r)

            protected inline def offerOp(inline f: => Boolean, inline raceRepair: => Boolean): Result[Closed, Boolean] =
                offerClosed.getOrElse {
                    val result = f
                    if result && closed() then
                        Result(raceRepair)
                    else
                        Result(result)
                    end if
                }

            private def handleHalfOpen(): Unit =
                state.get() match
                    case s: State.HalfOpen if _isEmpty() && state.compareAndSet(s, State.FullyClosed(s.r)) =>
                        s.p.completeDiscard(Result.succeed(true))
                    case _ =>

        end Closeable

        def init[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using
            initFrame: Frame,
            allow: AllowUnsafe
        ): Unsafe[A] =
            capacity match
                case _ if capacity <= 0 =>
                    new Closeable[A](initFrame):
                        def capacity                               = 0
                        def size()(using AllowUnsafe)              = op(0)
                        def empty()(using AllowUnsafe)             = op(true)
                        def full()(using AllowUnsafe)              = op(true)
                        def offer(v: A)(using AllowUnsafe)         = op(false)
                        def poll()(using AllowUnsafe)              = pollOp(Maybe.empty)
                        def peek()(using AllowUnsafe)              = op(Maybe.empty)
                        def _drain(max: Maybe[Int] = Maybe.Absent) = Chunk.empty
                        def _isEmpty()                             = true
                case 1 =>
                    new Closeable[A](initFrame):
                        private val state              = AtomicRef.Unsafe.init(Maybe.empty[A])
                        def capacity                   = 1
                        def empty()(using AllowUnsafe) = op(state.get().isEmpty)
                        def size()(using AllowUnsafe)  = op(if state.get().isEmpty then 0 else 1)
                        def full()(using AllowUnsafe)  = op(state.get().isDefined)
                        def offer(v: A)(using AllowUnsafe) =
                            offerOp(state.compareAndSet(Maybe.empty, Maybe(v)), !state.compareAndSet(Maybe(v), Maybe.empty))
                        def poll()(using AllowUnsafe) = pollOp(state.getAndSet(Maybe.empty))
                        def peek()(using AllowUnsafe) = op(state.get())
                        def _drain(max: Maybe[Int] = Maybe.Absent) =
                            max.fold(
                                state.getAndSet(Maybe.empty).fold(Chunk.empty)(Chunk(_))
                            )(m => if m <= 0 then Chunk.empty else state.getAndSet(Maybe.empty).fold(Chunk.empty)(Chunk(_)))
                        def _isEmpty() = state.get().isEmpty
                case Int.MaxValue =>
                    Unbounded.Unsafe.init(access).safe
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

        def fromJava[A](q: java.util.Queue[A], _capacity: Int = Int.MaxValue)(using initFrame: Frame, allow: AllowUnsafe): Unsafe[A] =
            new Closeable[A](initFrame):
                def capacity                   = _capacity
                def size()(using AllowUnsafe)  = op(q.size())
                def empty()(using AllowUnsafe) = op(q.isEmpty())
                def full()(using AllowUnsafe)  = op(q.size() >= _capacity)
                def offer(v: A)(using AllowUnsafe) =
                    offerOp(
                        q.offer(v),
                        try !q.remove(v)
                        catch
                            case _: UnsupportedOperationException =>
                                // TODO the race repair should use '!q.remove(v)' but JCTools doesn't support the operation.
                                // In rare cases, items may be left in the queue permanently after closing due to this limitation.
                                // The item will only be removed when the queue object itself is garbage collected.
                                !q.contains(v)
                    )
                def poll()(using AllowUnsafe) = pollOp(Maybe(q.poll()))
                def peek()(using AllowUnsafe) = op(Maybe(q.peek()))
                def _drain(max: Maybe[Int] = Maybe.Absent) =
                    val b = Chunk.newBuilder[A]
                    @tailrec def loop(i: Int): Unit =
                        if max.forall(i < _) then
                            val value = q.poll()
                            if !isNull(value) then
                                b.addOne(value)
                                loop(i + 1)
                    end loop
                    loop(0)
                    b.result()
                end _drain
                def _isEmpty() = q.isEmpty()

    end Unsafe

end Queue
