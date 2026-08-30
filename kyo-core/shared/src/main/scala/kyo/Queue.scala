package kyo

import java.util.concurrent.atomic.AtomicReference
import kyo.internal.*
import scala.annotation.tailrec

/** A high-performance, thread-safe queue with configurable concurrency patterns.
  *
  * Queue provides a comprehensive foundation for concurrent data transfer with customizable concurrency patterns (see [[kyo.Access]]).
  * Unlike Channel which focuses on fiber-aware communication, Queue directly exposes a lower-level interface optimized for raw throughput
  * and minimal synchronization overhead.
  *
  * Key features:
  *
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
  * @tparam A
  *   the type of elements in the queue
  * @see
  *   [[kyo.Channel]] For a higher-level, fiber-aware communication primitive
  * @see
  *   [[kyo.Access]] For available producer-consumer access patterns
  * @see
  *   [[kyo.Queue.Unbounded]] For dynamically-sized queue variant
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
        def size(using Frame): Int < (Sync & Abort[Closed]) = Sync.Unsafe.defer(Abort.get(self.size()))

        /** Checks if the queue is empty.
          *
          * @return
          *   true if the queue is empty, false otherwise
          */
        def empty(using Frame): Boolean < (Sync & Abort[Closed]) = Sync.Unsafe.defer(Abort.get(self.empty()))

        /** Checks if the queue is full.
          *
          * @return
          *   true if the queue is full, false otherwise
          */
        def full(using Frame): Boolean < (Sync & Abort[Closed]) = Sync.Unsafe.defer(Abort.get(self.full()))

        /** Offers an element to the queue.
          *
          * @param v
          *   the element to offer
          * @return
          *   true if the element was added, false if the queue is full or closed
          */
        def offer(v: A)(using Frame): Boolean < (Sync & Abort[Closed]) = Sync.Unsafe.defer(Abort.get(self.offer(v)))

        /** Offers an element to the queue and discards the result
          *
          * @param v
          *   the element to offer
          */
        def offerDiscard(v: A)(using Frame): Unit < (Sync & Abort[Closed]) = Sync.Unsafe.defer(Abort.get(self.offer(v).unit))

        /** Polls an element from the queue.
          *
          * @return
          *   Maybe containing the polled element, or empty if the queue is empty
          */
        def poll(using Frame): Maybe[A] < (Sync & Abort[Closed]) = Sync.Unsafe.defer(Abort.get(self.poll()))

        /** Peeks at the first element in the queue without removing it.
          *
          * @return
          *   Maybe containing the first element, or empty if the queue is empty
          */
        def peek(using Frame): Maybe[A] < (Sync & Abort[Closed]) = Sync.Unsafe.defer(Abort.get(self.peek()))

        /** Drains all elements from the queue.
          *
          * @return
          *   a sequence of all elements in the queue
          */
        def drain(using Frame): Chunk[A] < (Sync & Abort[Closed]) = Sync.Unsafe.defer(Abort.get(self.drain()))

        /** Takes up to `max` elements from the queue.
          *
          * @return
          *   a sequence of up to `max` elements from the queue.
          */
        def drainUpTo(max: Int)(using Frame): Chunk[A] < (Sync & Abort[Closed]) = Sync.Unsafe.defer(Abort.get(self.drainUpTo(max)))

        /** Closes the queue and returns any remaining elements. On a queue with a pending `closeAwaitEmpty`, this aborts the drain: the awaiter
          * completes `false` and this returns the undrained backlog.
          *
          * The backlog is complete: an offer that was accepted is in it, and one that was refused never reached the queue. Delivering that
          * guarantee costs a suspension, because an offer that began before this close can still be committing when it runs, and the
          * elements it has not written yet cannot be reported synchronously. When nothing is in flight, which is always the case on the
          * single-threaded platforms, the result is already available and this does not suspend. Use `closeDiscard` to close without the
          * backlog and stay in `Sync`.
          *
          * Consumers are locked out from the moment this is called: a `poll` or `drain` racing it aborts as closed rather than competing
          * with the drain. A consumer already inside `poll` is the caller's own business, as it is on an open queue.
          *
          * Interrupting a caller parked here discards the backlog. The queue still closes and still drains, but the elements have no
          * receiver and are dropped, so an interrupted close behaves as `closeDiscard`. Mask the interrupt where the elements own a
          * resource that must be released.
          *
          * @return
          *   a sequence of remaining elements, or absent when another close owns the closure. Absent means this call did not close the
          *   queue, not necessarily that the queue is already closed: the close that did own it may still be draining.
          */
        def close(using Frame): Maybe[Seq[A]] < Async = Sync.Unsafe.defer(self.close().safe.get)

        /** Closes the queue, discarding any remaining elements.
          *
          * The `Sync`-only counterpart to `close`, for callers that do not read the backlog. Nothing is awaited: the drain still happens,
          * on whichever offer is last to leave, and its result is dropped.
          */
        def closeDiscard(using Frame): Unit < Sync = Sync.Unsafe.defer(discard(self.close()))

        /** Closes the queue and asynchronously waits until it's empty.
          *
          * This method closes the queue to new elements and returns a computation that completes when all elements have been consumed.
          * Unlike the regular `close` method, this allows consumers to process all remaining elements before considering the queue fully
          * closed.
          *
          * @return
          *   true if the queue was successfully closed and emptied, false if it was already closed, another closeAwaitEmpty is already
          *   running, or a hard `close()` aborted the drain.
          */
        def closeAwaitEmpty(using Frame): Boolean < Async = Sync.Unsafe.defer(self.closeAwaitEmpty().safe.get)

        /** Checks if the queue is closed.
          *
          * @return
          *   true if the queue is closed, false otherwise
          */
        def closed(using Frame): Boolean < Sync = Sync.Unsafe.defer(self.closed())

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
    def init[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using Frame): Queue[A] < (Sync & Scope) =
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
    ): B < (Sync & S & Scope) =
        initUnscopedWith[A](capacity, access): queue =>
            Scope.ensure(Queue.close(queue)).andThen(f(queue))

    /** Uses a new Queue with the provided count, closing the queue after usage.
      *
      * @param capacity
      *   the desired capacity of the queue. Note that this will be rounded up to the next power of two.
      * @param access
      *   the access pattern (default is MPMC)
      * @param f
      *   The function to apply to the new Queue
      * @return
      *   The result of applying the function
      */
    def use[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)[B, S](f: Queue[A] => B < S)(
        using frame: Frame
    ): B < (Sync & S) =
        initUnscopedWith[A](capacity, access): queue =>
            Sync.ensure(Queue.closeDiscard(queue))(f(queue))

    /** Initializes a new queue with the specified capacity and access pattern without guaranteeing cleanup. The actual capacity will be
      * rounded up to the next power of two.
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
    def initUnscoped[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using Frame): Queue[A] < Sync =
        initUnscopedWith[A](capacity, access)(identity)

    /** Uses a new Queue with the provided count without guaranteeing cleanup.
      * @param capacity
      *   the desired capacity of the queue. Note that this will be rounded up to the next power of two.
      * @param access
      *   the access pattern (default is MPMC)
      * @param f
      *   The function to apply to the new Queue
      * @return
      *   The result of applying the function
      */
    inline def initUnscopedWith[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)[B, S](inline f: Queue[A] => B < S)(
        using inline frame: Frame
    ): B < (Sync & S) =
        Sync.Unsafe.defer(f(Unsafe.init(capacity, access)))

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
            def add(value: A)(using Frame): Unit < Sync = Sync.Unsafe.defer(Unsafe.add(self)(value))

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
        def init[A](access: Access = Access.MultiProducerMultiConsumer, chunkSize: Int = 8)(using Frame): Unbounded[A] < (Sync & Scope) =
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
        ): B < (Sync & S & Scope) =
            initUnscopedWith[A](access, chunkSize): queue =>
                Scope.ensure(Queue.close(queue)).andThen(f(queue))

        /** Uses a new unbounded Queue with the provided count, closing the queue after usage.
          * @param count
          *   The initial count for the latch
          * @param f
          *   The function to apply to the new Queue
          * @return
          *   The result of applying the function
          */
        def use[A](
            access: Access = Access.MultiProducerMultiConsumer,
            chunkSize: Int = 8
        )[B, S](f: Unbounded[A] => B < S)(
            using frame: Frame
        ): B < (Sync & S) =
            initUnscopedWith[A](access, chunkSize): queue =>
                Sync.ensure(Queue.closeDiscard(queue))(f(queue))

        /** Initializes a new unbounded queue with the specified access pattern and chunk size without guaranteeing cleanup.
          *
          * @param access
          *   the access pattern (default is MPMC)
          * @param chunkSize
          *   the chunk size for internal array allocation (default is 8)
          * @return
          *   a new Unbounded Queue instance
          */
        def initUnscoped[A](access: Access = Access.MultiProducerMultiConsumer, chunkSize: Int = 8)(using Frame): Unbounded[A] < Sync =
            initUnscopedWith[A](access, chunkSize)(identity)

        /** Uses a new unbounded Queue with the provided count without guaranteeing cleanup.
          * @param count
          *   The initial count for the latch
          * @param f
          *   The function to apply to the new Queue
          * @return
          *   The result of applying the function
          */
        inline def initUnscopedWith[A](
            access: Access = Access.MultiProducerMultiConsumer,
            chunkSize: Int = 8
        )[B, S](inline f: Unbounded[A] => B < S)(
            using inline frame: Frame
        ): B < (Sync & S) =
            Sync.Unsafe.defer(f(Unsafe.init(access, chunkSize)))

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
        def initDropping[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using
            Frame
        ): Unbounded[A] < (Scope & Sync) =
            initDroppingUnscoped[A](capacity, access).map: queue =>
                Scope.ensure(Queue.close(queue)).andThen(queue)

        /** Uses a new dropping queue with the specified capacity and access pattern, closing queue after usage.
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
        def useDropping[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)[B, S](f: Unbounded[A] => B < S)(
            using Frame
        ): B < (Sync & S) =
            initDroppingUnscoped[A](capacity, access).map: queue =>
                Sync.ensure(Queue.closeDiscard(queue))(f(queue))

        /** Initializes a new dropping queue with the specified capacity and access pattern without guaranteeing cleanup.
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
        def initDroppingUnscoped[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(
            using Frame
        ): Unbounded[A] < Sync =
            Sync.Unsafe.defer(Unsafe.initDropping(capacity, access))

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
        def initSliding[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using
            Frame
        ): Unbounded[A] < (Scope & Sync) =
            Scope.acquireRelease(initSlidingUnscoped[A](capacity, access))(Queue.close(_))

        /** Uses a new sliding queue with the specified capacity and access pattern, closing queue after usage.
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
        def useSliding[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)[B, S](f: Unbounded[A] => B < S)(
            using Frame
        ): B < (Sync & S) =
            initSlidingUnscoped[A](capacity, access).map: queue =>
                Sync.ensure(Queue.closeDiscard(queue))(f(queue))

        /** Initializes a new sliding queue with the specified capacity and access pattern without guaranteeing cleanup.
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
        def initSlidingUnscoped[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(
            using Frame
        ): Unbounded[A] < Sync =
            Sync.Unsafe.defer(Unsafe.initSliding(capacity, access))

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
                        Queue.Unsafe.fromInternal(new MpmcUnboundedUnsafeQueue[A](chunkSize))
                    case Access.MultiProducerSingleConsumer =>
                        Queue.Unsafe.fromInternal(new MpscUnboundedUnsafeQueue[A](chunkSize))
                    case Access.SingleProducerMultiConsumer =>
                        Queue.Unsafe.fromInternal(new SpmcUnboundedUnsafeQueue[A](chunkSize))
                    case Access.SingleProducerSingleConsumer =>
                        Queue.Unsafe.fromInternal(new SpscUnboundedUnsafeQueue[A](chunkSize))

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

            // Sliding removes the oldest element to make room, and that removal runs on whichever thread offered. A queue that admits
            // only one consumer cannot serve that: the offering thread would be a second consumer beside the real one, which is exactly
            // what the single-consumer algorithms are not written for, so they spin on a slot the other consumer already emptied. The
            // consumer side is therefore widened to one that permits it, the same substitution `Queue.Unsafe.init` already makes when
            // the requested implementation cannot meet the requirement. The caller's own contract is untouched: as many producers as
            // asked for, and one consumer. The cost is a multi-consumer structure on every sliding path, which a backing ring that
            // overwrites its oldest slot by design, letting a producer advance the consumer index without becoming a second consumer,
            // would remove by making the cheaper single-consumer implementations usable here again.
            private def slidingAccess(access: Access): Access =
                access match
                    case Access.MultiProducerSingleConsumer  => Access.MultiProducerMultiConsumer
                    case Access.SingleProducerSingleConsumer => Access.SingleProducerMultiConsumer
                    case multiConsumer                       => multiConsumer

            def initSliding[A](_capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unsafe[A] =
                new Unsafe[A]:
                    val underlying                 = Queue.Unsafe.init[A](_capacity, slidingAccess(access))
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
        def close()(using Frame, AllowUnsafe): Fiber.Unsafe[Maybe[Seq[A]], Any]
        def closeAwaitEmpty()(using Frame, AllowUnsafe): Fiber.Unsafe[Boolean, Any]
        def closed()(using AllowUnsafe): Boolean

        /** Best-effort human-readable snapshot of this queue's coordination state (open/half-open/closed status, ring emptiness and
          * size, in-flight offer count) for the [[kyo.internal.Diagnostics]] hang dumpers. Renders even when the queue is FullyClosed,
          * unlike [[size]]. Overridden by the closeable backends; the default covers any other implementation.
          */
        private[kyo] def diagnosticState(): String = "(non-closeable queue)"

        /** True once this queue rejects new offers: soft-closed (HalfOpen, still draining its ring to consumers) or fully closed. A
          * producer parked because the ring was full can never be transferred in once this holds, so a channel must fail such a producer
          * rather than wait for a delivery that can never happen. Default false; overridden by the closeable backend.
          */
        private[kyo] def offersRejected(): Boolean = false

        final def safe: Queue[A] = this
    end Unsafe

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:

        // `Draining` is the window between a close deciding to close and the backlog being handed over. Offers and consumer operations are
        // already refused in it, so the only party that may still touch the ring is the one that wins the single-shot CAS out of it.
        private enum State derives CanEqual:
            case Open
            case HalfOpen(p: Promise.Unsafe[Boolean, Any], r: Result.Error[Closed])
            case Draining(r: Result.Error[Closed])
            case FullyClosed(r: Result.Error[Closed])
        end State

        sealed abstract private class Closeable[A](initFrame: Frame) extends Unsafe[A]:
            import AllowUnsafe.embrace.danger
            // Both atomics are read against each other in opposite orders (a closer writes the state then reads the count, an offer writes
            // the count then reads the state), so the handover below is only correct while these carry sequentially consistent semantics.
            final private val state        = AtomicRef.Unsafe.init[State](State.Open)
            final private val activeOffers = AtomicInt.Unsafe.init(0)
            // Where the backlog is delivered. Held beside the state rather than inside it because a promise is invariant in its value and
            // the state is shared by every element type. Claiming it is what elects the one close that owns the drain; the claim is made
            // before the move into Draining, so any thread that observes Draining also observes the promise.
            final private val backlog = AtomicRef.Unsafe.init(Maybe.empty[Promise.Unsafe[Maybe[Seq[A]], Any]])

            final def close()(using frame: Frame, allow: AllowUnsafe): Fiber.Unsafe[Maybe[Seq[A]], Any] =
                val fail = Result.Failure(Closed("Queue", initFrame))
                val p    = Promise.Unsafe.init[Maybe[Seq[A]], Any]()
                // Claiming the backlog slot elects the single close that owns the drain, and it happens before the move into Draining so
                // that observing Draining implies observing the promise. A close that loses this claim closed nothing and answers Absent,
                // which also covers a queue that reached FullyClosed on its own through a completed closeAwaitEmpty.
                if !backlog.compareAndSet(Absent, Present(p)) then
                    p.completeDiscard(Result.succeed(Absent))
                else
                    // A hard close on a HalfOpen queue aborts the await-empty: complete its promise false (the queue did not drain before
                    // this close) so a parked closeAwaitEmpty caller does not hang.
                    @tailrec
                    def escalate(): Boolean =
                        state.get() match
                            case State.Open =>
                                if state.compareAndSet(State.Open, State.Draining(fail)) then true
                                else escalate()
                            case s @ State.HalfOpen(await, _) =>
                                if state.compareAndSet(s, State.Draining(fail)) then
                                    await.completeDiscard(Result.succeed(false))
                                    true
                                else escalate()
                            case _ => false
                        end match
                    end escalate
                    if !escalate() then p.completeDiscard(Result.succeed(Absent))
                    else
                        // An offer counts itself in activeOffers BEFORE it reads the state, and this CAS landed BEFORE the count is read
                        // below, so the two cannot miss each other: either the count is seen nonzero here, or that offer reads Draining
                        // and gives up. A zero therefore proves no offer can still reach the ring, which makes this thread the only
                        // possible consumer and lets it drain and answer without waiting. Offers cannot overlap a close at all on the
                        // single-threaded platforms, so this is the only path taken there.
                        // A nonzero count hands the drain to whichever offer decrements it to zero; see helpComplete.
                        if activeOffers.get() == 0 then helpComplete()
                    end if
                end if
                p
            end close

            final def closeAwaitEmpty()(using frame: Frame, allow: AllowUnsafe): Fiber.Unsafe[Boolean, Any] =
                val fail = Result.Failure(Closed("Queue", initFrame))
                val p    = Promise.Unsafe.init[Boolean, Any]()
                if state.compareAndSet(State.Open, State.HalfOpen(p, fail)) then
                    handleHalfOpen()
                    p
                else
                    state.get() match
                        case State.HalfOpen(other, _) =>
                            p.becomeDiscard(other.safe)
                        case _ => // Closed
                            p.completeDiscard(Result.succeed(false))
                    end match
                    p.map(_ => false) // avoid returning `true` from other promise
                end if
            end closeAwaitEmpty

            final def closed()(using AllowUnsafe) =
                state.get() match
                    case _: State.Draining    => true
                    case _: State.FullyClosed => true
                    case _                    => false

            /** Completes a pending close, or a pending await-empty, if this thread can prove it is alone with the ring.
              *
              * Called by `close` when it observes no offers in flight, and by the last offer to leave when one was. Exactly one caller wins
              * the CAS out of `Draining`, and only that winner drains, so the ring keeps a single consumer throughout. The drain runs after
              * the CAS, by which point every offer has either committed (its element is in the ring) or read a closed state and stopped.
              *
              * The count is re-read HERE, after the state, and that order is what makes the pair mean anything. The zero an offer observes
              * on its way out may have been observed while the state was still Open, which says nothing about offers that began afterwards:
              * trusting it would let a close that correctly declined to drain be overridden by a straggler, stranding an element whose offer
              * had already returned true. Reading the count only once Draining is visible closes that: an offer that could still commit
              * either incremented before this read, and is seen, or reads Draining and never touches the ring.
              */
            final private def helpComplete(): Unit =
                state.get() match
                    case s @ State.Draining(r) =>
                        if activeOffers.get() == 0 && state.compareAndSet(s, State.FullyClosed(r)) then
                            // Reaching Draining means the claim in close already published the promise, so this is never absent.
                            backlog.get().foreach(_.completeDiscard(Result.succeed(Present(_drain()))))
                    case _: State.HalfOpen => handleHalfOpen()
                    case _                 =>
            end helpComplete

            override private[kyo] def diagnosticState(): String =
                val st = state.get() match
                    case State.Open           => "Open"
                    case State.HalfOpen(p, _) => s"HalfOpen(awaitEmptyDone=${p.done()})"
                    case _: State.Draining    => s"Draining(backlogDone=${backlog.get().exists(_.done())})"
                    case _: State.FullyClosed => "FullyClosed"
                val ringSize = this.size() match
                    case Result.Success(n) => n.toString
                    case _                 => "closed"
                s"state=$st ringEmpty=${_isEmpty()} ringSize=$ringSize activeOffers=${activeOffers.get()}"
            end diagnosticState

            override private[kyo] def offersRejected(): Boolean = offerClosed.isDefined

            final def drainUpTo(max: Int)(using AllowUnsafe): Result[Closed, Chunk[A]] = pollOp(_drain(Maybe.Present(max)))

            final def drain()(using AllowUnsafe): Result[Closed, Chunk[A]] = pollOp(_drain())

            protected def _drain(max: Maybe[Int] = Maybe.Absent): Chunk[A]
            protected def _isEmpty(): Boolean

            // Consumer operations are refused from Draining on, not just FullyClosed: the ring in that window belongs to whoever wins the
            // handover CAS, and a user-side poll running alongside that drain would be a second consumer.
            private def opClosed: Maybe[Result.Error[Closed]] =
                state.get() match
                    case State.Draining(r)    => Present(r)
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
                    case State.Draining(r)    => Present(r)
                    case State.FullyClosed(r) => Present(r)

            protected inline def offerOp(inline f: => Boolean): Result[Closed, Boolean] =
                // Increment BEFORE reading state, so a close that CASes after this point still sees this offer as in flight and leaves the
                // ring alone. An offer that gets past `offerClosed` is therefore free to commit: a close either waits for it or has not
                // happened yet, and either way its element ends up in the backlog rather than stranded.
                // try/finally ensures the decrement runs even if `f` throws; a leaked count would strand a pending close forever.
                discard(activeOffers.incrementAndGet())
                try offerClosed.getOrElse(Result(f))
                finally
                    // Last one out gives whatever the close (or the await-empty) left pending a chance to complete. This zero is only a
                    // reason to look: it may have been read while the queue was still open, so it proves nothing on its own and
                    // helpComplete re-reads the count against the state it actually finds.
                    if activeOffers.decrementAndGet() == 0 then helpComplete()
                end try
            end offerOp

            private def handleHalfOpen(): Unit =
                // The count is read BEFORE the emptiness check, mirroring close: an offer in flight may still commit, so an empty ring
                // proves nothing while one is outstanding. Completing on it would tell the awaiter the queue drained and then strand the
                // element that lands next. When the count is nonzero this does nothing and the last offer out re-runs it via helpComplete,
                // so the awaiter cannot be left waiting on a queue that did empty.
                state.get() match
                    case s: State.HalfOpen
                        if activeOffers.get() == 0 && _isEmpty() && state.compareAndSet(s, State.FullyClosed(s.r)) =>
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
                            offerOp(state.compareAndSet(Maybe.empty, Maybe(v)))
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
                            fromInternal(new MpmcUnsafeQueue[A](capacity))
                        case Access.MultiProducerSingleConsumer =>
                            fromInternal(new MpscUnsafeQueue[A](capacity))
                        case Access.SingleProducerMultiConsumer =>
                            fromInternal(new SpmcUnsafeQueue[A](capacity))
                        case Access.SingleProducerSingleConsumer =>
                            if capacity >= 4 then
                                fromInternal(new SpscUnsafeQueue[A](capacity))
                            else
                                // Spsc queue doesn't support capacity < 4
                                fromInternal(new SpmcUnsafeQueue[A](capacity))

        private[Queue] def fromInternal[A](q: UnsafeQueue[A])(using initFrame: Frame, allow: AllowUnsafe): Unsafe[A] =
            new Closeable[A](initFrame):
                def capacity                       = q.capacity
                def size()(using AllowUnsafe)      = op(q.size())
                def empty()(using AllowUnsafe)     = op(q.isEmpty())
                def full()(using AllowUnsafe)      = op(q.isFull())
                def offer(v: A)(using AllowUnsafe) = offerOp(q.offer(v))
                def poll()(using AllowUnsafe)      = pollOp(q.poll())
                def peek()(using AllowUnsafe)      = op(q.peek())
                def _drain(max: Maybe[Int] = Maybe.Absent) =
                    val b = Chunk.newBuilder[A]
                    max match
                        case Maybe.Present(limit) => discard(q.drain(b.addOne(_), limit))
                        case _                    => discard(q.drain(b.addOne(_)))
                    b.result()
                end _drain
                def _isEmpty() = q.isEmpty()

    end Unsafe

end Queue
