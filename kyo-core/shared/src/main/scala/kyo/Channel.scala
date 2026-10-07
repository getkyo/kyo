package kyo

import kyo.internal.*
import scala.annotation.tailrec

/** A channel for communicating between fibers.
  *
  * Channel provides a thread-safe communication primitive designed for passing messages between fibers. It functions as a bounded buffer
  * where producers can send values and consumers can receive them, creating a structured way to coordinate work and share data across
  * concurrent computations.
  *
  * The core functionality of Channel can be understood through two main operation types:
  *
  * Synchronous operations (offer/poll) immediately succeed or fail without parking fibers. These are useful when you want to attempt
  * communication without blocking execution:
  *
  *   - `offer` attempts to add an element, returning true if successful or false if the channel is full
  *   - `poll` attempts to retrieve an element, returning Maybe.empty if the channel is empty
  *
  * Asynchronous operations (put/take) will suspend the current fiber until the operation can complete:
  *
  *   - `put` adds an element, suspending if the channel is full until space becomes available
  *   - `take` retrieves an element, suspending if the channel is empty until an element arrives
  *
  * Channels have a fixed capacity specified at creation time, which serves as a natural backpressure mechanism. When the channel fills up,
  * producers using `put` will be suspended until consumers make space by taking elements. This helps regulate the flow of work between
  * faster producers and slower consumers, preventing unbounded resource consumption.
  *
  * Beyond individual operations, Channel also supports batching through operations like `putBatch` and `takeExactly`, allowing for more
  * efficient bulk processing. For continuous consumption, the `stream` method transforms the channel's contents into a Stream that can be
  * processed with standard stream operations.
  *
  * When a channel is no longer needed, it should be closed with the `close` method, which will release resources and notify any suspended
  * fibers. Attempting operations on a closed channel will result in a Closed error.
  *
  * The access pattern (MPMC, MPSC, SPMC, or SPSC) can be specified at creation time to optimize performance based on your concurrency
  * requirements. Use MPMC (the default) when multiple fibers will both produce and consume, or more specialized patterns when your usage
  * follows a specific structure.
  *
  * IMPORTANT: While a Channel comes with a predefined capacity, there is no upper limit on the number of fibers that can be suspended by
  * it. In scenarios where your application spawns an unrestricted number of fibers—such as an HTTP service where each incoming request
  * initiates a new fiber—this can lead to significant memory consumption. The channel's internal queue for suspended fibers could grow
  * indefinitely, making it a potential source of unbounded queuing and memory issues. Exercise caution in such use-cases to prevent
  * resource exhaustion.
  *
  * WARNING: The actual capacity of a Channel is rounded up to the next power of two for performance reasons. For example, if
  * you specify a capacity of 10, the actual capacity will be 16.
  *
  * @tparam A
  *   The type of elements that can be sent through the channel
  * @see
  *   [[kyo.Queue]] A similar structure without the fiber-aware asynchronous operations
  * @see
  *   [[kyo.Hub]] A multi-producer, multi-consumer broadcast primitive for one-to-many communication
  * @see
  *   [[kyo.Access]] For available producer-consumer access patterns
  */
opaque type Channel[A] = Channel.Unsafe[A]

object Channel:

    extension [A](self: Channel[A])

        /** Returns the capacity of the channel.
          *
          * @return
          *   The capacity of the channel
          */
        def capacity: Int = self.capacity

        /** Returns the current size of the channel.
          *
          * @return
          *   The number of elements currently in the channel
          */
        def size(using Frame): Int < (Abort[Closed] & Sync) = Sync.Unsafe.defer(Abort.get(self.size()))

        /** Returns the number of fibers currently waiting to put values into the channel.
          *
          * This method provides visibility into the backpressure state of the channel by counting how many producer fibers are currently
          * suspended waiting for space to become available. A non-zero value indicates that producers are being throttled due to the
          * channel being full.
          *
          * @return
          *   The number of fibers waiting to put values into the channel
          */
        def pendingPuts(using Frame): Int < (Abort[Closed] & Sync) = Sync.Unsafe.defer(Abort.get(self.pendingPuts()))

        /** Returns the number of fibers currently waiting to take values from the channel.
          *
          * This method provides visibility into the consumer demand state of the channel by counting how many consumer fibers are currently
          * suspended waiting for values to become available. A non-zero value indicates that consumers are waiting for producers to add
          * values.
          *
          * @return
          *   The number of fibers waiting to take values from the channel
          */
        def pendingTakes(using Frame): Int < (Abort[Closed] & Sync) = Sync.Unsafe.defer(Abort.get(self.pendingTakes()))

        /** Attempts to offer an element to the channel without blocking.
          *
          * @param value
          *   The element to offer
          * @return
          *   true if the element was added to the channel, false otherwise
          */
        def offer(value: A)(using Frame): Boolean < (Abort[Closed] & Sync) = Sync.Unsafe.defer(Abort.get(self.offer(value)))

        /** Offers an element to the channel without returning a result.
          *
          * @param v
          *   The element to offer
          */
        def offerDiscard(value: A)(using Frame): Unit < (Abort[Closed] & Sync) = Sync.Unsafe.defer(Abort.get(self.offer(value).unit))

        /** Attempts to poll an element from the channel without blocking.
          *
          * @return
          *   Maybe containing the polled element, or empty if the channel is empty
          */
        def poll(using Frame): Maybe[A] < (Abort[Closed] & Sync) = Sync.Unsafe.defer(Abort.get(self.poll()))

        /** Puts an element into the channel, asynchronously blocking if necessary.
          *
          * @param value
          *   The element to put
          */
        def put(value: A)(using Frame): Unit < (Abort[Closed] & Async) =
            Sync.Unsafe.defer {
                self.offer(value).foldError(
                    {
                        case true  => ()
                        case false => self.putFiber(value).safe.get
                    },
                    Abort.error
                )
            }

        /** Puts elements into the channel as a batch, asynchronously blocking if necessary.
          *
          * Batch items are kept contiguous in the channel — items from one putBatch call will not be interleaved with items from another
          * concurrent putBatch call.
          *
          * @param values
          *   Chunk of elements to put
          */
        def putBatch(values: Seq[A])(using Frame): Unit < (Abort[Closed] & Async) =
            if values.isEmpty then ()
            else Sync.Unsafe.defer(self.putBatchFiber(values).safe.get)
        end putBatch

        /** Takes an element from the channel, asynchronously blocking if necessary.
          *
          * @return
          *   The taken element
          */
        def take(using Frame): A < (Abort[Closed] & Async) =
            takeWith(identity)

        /** Takes an element from the channel and applies an inline function, avoiding a `.map` closure allocation.
          *
          * @return
          *   The result of applying the function to the taken element
          */
        inline def takeWith[B, S](inline f: A => B < S)(using Frame): B < (S & Abort[Closed] & Async) =
            Sync.Unsafe.defer {
                self.poll().foldError(
                    {
                        case Present(value) => f(value)
                        case Absent         =>
                            // A taker abandoned before it resumes never runs the continuation below, so the release decides
                            // whether the value it was handed was claimed or goes back to the channel.
                            val handover = self.takeHandover()
                            Sync.Unsafe.ensure(self.settleHandover(handover)) {
                                Promise.Unsafe.fromIOPromise(handover).safe.use { value =>
                                    self.claimHandover(handover)
                                    f(value)
                                }
                            }
                    },
                    Abort.error
                )
            }
        end takeWith

        /** Takes `n` elements from the channel, semantically blocking until enough elements are present. Note that if enough elements are
          * not added to the channel it can block indefinitely.
          *
          * @return
          *   Chunk of `n` elements
          */
        def takeExactly(n: Int)(using Frame): Chunk[A] < (Abort[Closed] & Async) =
            if n <= 0 then Chunk.empty
            else
                Loop(Chunk.empty[A], 0): (lastChunk, lastSize) =>
                    val nextN = n - lastSize
                    Channel.drainUpTo(self)(nextN).map: chunk =>
                        val chunk1 = lastChunk.concat(chunk)
                        if chunk1.size >= n then Loop.done(chunk1)
                        else
                            self.take.map: a =>
                                val chunk2 = chunk1.append(a)
                                val size2  = chunk2.size
                                if size2 >= n then Loop.done(chunk2)
                                else Loop.continue(chunk2, size2)
                        end if

        /** Drains all elements from the channel.
          *
          * @return
          *   A sequence containing all elements that were in the channel
          */
        def drain(using Frame): Chunk[A] < (Abort[Closed] & Sync) = Sync.Unsafe.defer(Abort.get(self.drain()))

        /** Takes up to `max` elements from the channel.
          *
          * @return
          *   a sequence of up to `max` elements that were in the channel.
          */
        def drainUpTo(max: Int)(using Frame): Chunk[A] < (Sync & Abort[Closed]) = Sync.Unsafe.defer(Abort.get(self.drainUpTo(max)))

        /** Closes the channel.
          *
          * The returned elements are the buffered ones, complete: a `put` that was accepted is among them, and one that was refused never
          * reached the buffer. Delivering that guarantee costs a suspension, because a put that began before this close can still be
          * committing when it runs. Use `closeDiscard` to close without the elements and stay in `Sync`.
          *
          * Interrupting a caller parked here discards those elements. The channel still closes, but they have no receiver, so an
          * interrupted close behaves as `closeDiscard`. Mask the interrupt where the elements own a resource that must be released.
          *
          * @return
          *   A sequence of remaining elements, or absent when another close owns the closure
          */
        def close(using Frame): Maybe[Seq[A]] < Async = Sync.Unsafe.defer(self.close().safe.get)

        /** Closes the channel, discarding any buffered elements.
          *
          * The `Sync`-only counterpart to `close`, for callers that do not read the remaining elements.
          */
        def closeDiscard(using Frame): Unit < Sync = Sync.Unsafe.defer(discard(self.close()))

        /** Closes the channel and asynchronously waits until it's empty.
          *
          * This method closes the channel to new elements and returns a computation that completes when all elements have been consumed.
          * Unlike the regular `close` method, this allows consumers to process all remaining elements before considering the channel fully
          * closed.
          *
          * @return
          *   true if the channel was successfully closed and emptied, false if it was already closed or a hard `close()` aborted the drain
          */
        def closeAwaitEmpty(using Frame): Boolean < Async = Sync.Unsafe.defer(self.closeAwaitEmpty().safe.get)

        /** Checks if the channel is closed.
          *
          * @return
          *   true if the channel is closed, false otherwise
          */
        def closed(using Frame): Boolean < Sync = Sync.Unsafe.defer(self.closed())

        /** Checks if the channel is empty.
          *
          * @return
          *   true if the channel is empty, false otherwise
          */
        def empty(using Frame): Boolean < (Abort[Closed] & Sync) = Sync.Unsafe.defer(Abort.get(self.empty()))

        /** Checks if the channel is full.
          *
          * @return
          *   true if the channel is full, false otherwise
          */
        def full(using Frame): Boolean < (Abort[Closed] & Sync) = Sync.Unsafe.defer(Abort.get(self.full()))

        private def emitChunks(maxChunkSize: Int = Int.MaxValue)(
            using
            Tag[Emit[Chunk[A]]],
            Frame
        ): Unit < (Emit[Chunk[A]] & Abort[Closed] & Async) =
            if maxChunkSize <= 0 then ()
            else if maxChunkSize == 1 then
                Loop.forever:
                    Channel.take(self).map: v =>
                        Emit.value(Chunk(v))
            else
                val drainEffect =
                    if maxChunkSize == Int.MaxValue then Channel.drain(self)
                    else Channel.drainUpTo(self)(maxChunkSize)
                Loop.forever:
                    drainEffect.map:
                        case chunk if chunk.nonEmpty => Emit.value(chunk)
                        case _                       =>
                            Channel.take(self).map { a =>
                                Channel.drainUpTo(self)(maxChunkSize - 1)
                                    .map(ch => Emit.value(Chunk(a).concat(ch)))
                                    .handle(
                                        Abort.recover[Closed](e => Emit.value(Chunk(a)).andThen(Abort.fail(e)))
                                    )
                            }

        /** Stream elements from channel, optionally specifying a maximum chunk size. In the absence of `maxChunkSize`, chunk sizes will be
          * limited only by channel capacity or the number of elements in the channel at a given time. (Chunks can still be larger than
          * channel capacity.) Consumes elements from channel. Fails on channel closure.
          *
          * @param maxChunkSize
          *   Maximum number of elements to take for each chunk
          *
          * @return
          *   Asynchronous stream of elements in this channel
          */
        def stream(maxChunkSize: Int = Int.MaxValue)(using Tag[Emit[Chunk[A]]], Frame): Stream[A, Abort[Closed] & Async] =
            Stream(emitChunks(maxChunkSize))

        /** Like [[stream]] but stops streaming when the channel closes instead of failing
          *
          * @param maxChunkSize
          *   Maximum number of elements to take for each chunk
          *
          * @return
          *   Asynchronous stream of elements in this channel
          */
        def streamUntilClosed(maxChunkSize: Int = Int.MaxValue)(using Tag[Emit[Chunk[A]]], Frame): Stream[A, Async] =
            Stream:
                Abort.run[Closed](emitChunks(maxChunkSize)).map:
                    case Result.Success(v) => v
                    case Result.Failure(_) => ()
                    case Result.Panic(e)   => Abort.panic(e)

        def unsafe: Unsafe[A] = self
    end extension

    /** Initializes a new Channel.
      *
      * @param capacity
      *   The capacity of the channel. Note that this will be rounded up to the next power of two.
      * @param access
      *   The access mode for the channel (default is MPMC)
      * @tparam A
      *   The type of elements in the channel
      * @return
      *   A new Channel instance
      *
      * @note
      *   The actual capacity will be rounded up to the next power of two.
      * @warning
      *   The actual capacity may be larger than the specified capacity due to rounding.
      */
    def init[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using Frame): Channel[A] < (Sync & Scope) =
        initWith[A](capacity, access)(identity)

    /** Uses a new Channel with the provided configuration.
      * @param f
      *   The function to apply to the new Channel
      * @return
      *   The result of applying the function
      */
    inline def initWith[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)[B, S](
        inline f: Channel[A] => B < S
    )(using inline frame: Frame): B < (S & Sync & Scope) =
        Sync.Unsafe.defer:
            val channel = Unsafe.init[A](capacity, access)
            Scope.ensure(Channel.close(channel)).andThen:
                f(channel)

    /** Uses a new Channel with the provided configuration, closing the channel after usage.
      * @param f
      *   The function to apply to the new Channel
      * @return
      *   The result of applying the function
      */
    inline def use[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)[B, S](
        inline f: Channel[A] => B < S
    )(using inline frame: Frame): B < (S & Sync) =
        Sync.Unsafe.defer:
            val channel = Unsafe.init[A](capacity, access)
            Sync.ensure(Channel.closeDiscard(channel)):
                f(channel)

    /** Initializes a new Channel without guaranteeing eventual cleanup.
      *
      * @param capacity
      *   The capacity of the channel. Note that this will be rounded up to the next power of two.
      * @param access
      *   The access mode for the channel (default is MPMC)
      * @tparam A
      *   The type of elements in the channel
      * @return
      *   A new Channel instance
      *
      * @note
      *   The actual capacity will be rounded up to the next power of two.
      * @note
      *   The channel should be manually cleaned up when no longer needed using [[close]]
      * @warning
      *   The actual capacity may be larger than the specified capacity due to rounding.
      */
    def initUnscoped[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using Frame): Channel[A] < Sync =
        initUnscopedWith[A](capacity, access)(identity)

    /** Uses a new Channel with the provided configuration without guaranteeing eventual cleanup.
      *
      * @param f
      *   The function to apply to the new Channel
      * @note
      *   The channel should be manually cleaned up when no longer needed using [[close]]
      * @return
      *   The result of applying the function
      */
    inline def initUnscopedWith[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)[B, S](
        inline f: Channel[A] => B < S
    )(using inline frame: Frame): B < (S & Sync) =
        Sync.Unsafe.defer(f(Unsafe.init[A](capacity, access)))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    sealed abstract class Unsafe[A] extends Serializable:
        def capacity: Int
        def size()(using AllowUnsafe, Frame): Result[Closed, Int]
        def pendingPuts()(using AllowUnsafe, Frame): Result[Closed, Int]
        def pendingTakes()(using AllowUnsafe, Frame): Result[Closed, Int]

        def offer(value: A)(using AllowUnsafe, Frame): Result[Closed, Boolean]
        def offerAll(values: Seq[A])(using AllowUnsafe, Frame): Result[Closed, Chunk[A]]
        def poll()(using AllowUnsafe, Frame): Result[Closed, Maybe[A]]

        def putFiber(value: A)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]]
        def putBatchFiber(values: Seq[A])(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]]
        def takeFiber()(using AllowUnsafe, Frame): Fiber.Unsafe[A, Abort[Closed]]
        private[kyo] def reuseTake(promise: Promise.Unsafe[A, Abort[Closed]])(using AllowUnsafe, Frame): Unit
        private[kyo] def takeHandover()(using AllowUnsafe, Frame): Unsafe.Handover[A]
        private[kyo] def claimHandover(handover: Unsafe.Handover[A])(using AllowUnsafe, Frame): Unit
        private[kyo] def settleHandover(handover: Unsafe.Handover[A])(using AllowUnsafe, Frame): Unit

        def drain()(using AllowUnsafe, Frame): Result[Closed, Chunk[A]]
        def drainUpTo(max: Int)(using AllowUnsafe, Frame): Result[Closed, Chunk[A]]
        def close()(using Frame, AllowUnsafe): Fiber.Unsafe[Maybe[Seq[A]], Any]
        def closeAwaitEmpty()(using Frame, AllowUnsafe): Fiber.Unsafe[Boolean, Any]

        def empty()(using AllowUnsafe, Frame): Result[Closed, Boolean]
        def full()(using AllowUnsafe, Frame): Result[Closed, Boolean]
        def closed()(using AllowUnsafe): Boolean

        /** Best-effort human-readable snapshot of this channel's coordination state (backing buffer/queue status plus the parked
          * take/put/priority-put counts and whether the next waiter of each is already completed) for the [[kyo.internal.Diagnostics]]
          * hang dumpers. Overridden by [[Unsafe.BaseUnsafe]]; the default covers any other implementation.
          */
        private[kyo] def dumpState(): String = "(no diagnostic state)"

        def safe: Channel[A] = this
    end Unsafe

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        def init[A](
            capacity: Int,
            access: Access = Access.MultiProducerMultiConsumer
        )(using initFrame: Frame, allow: AllowUnsafe): Unsafe[A] =
            if capacity <= 0 then ZeroCapacityUnsafe[A](initFrame)
            else NonZeroCapacityUnsafe(capacity, access)

        private[Unsafe] enum Put[A]:
            val promise: Promise.Unsafe[Unit, Abort[Closed]]
            case Batch(batch: Chunk[A], override val promise: Promise.Unsafe[Unit, Abort[Closed]])
            case Value(value: A, override val promise: Promise.Unsafe[Unit, Abort[Closed]])
        end Put

        /** A waiter's promise the channel created, counted in `live` while pending. The completion hook, not a registered callback,
          * so counting allocates nothing and drops once whatever completed it, an interrupt included.
          */
        private[kyo] class Waiter[A](live: java.util.concurrent.atomic.AtomicInteger)
            extends kyo.scheduler.IOPromise[Any, A < Abort[Closed]]:
            discard(live.incrementAndGet())
            override protected def onComplete(): Unit =
                super.onComplete()
                discard(live.decrementAndGet())
        end Waiter

        /** The take promise of a safe taker. A value delivered into it belongs to the channel until the taker claims it or its
          * abandonment returns it, and `settled` makes that decision once.
          */
        final private[kyo] class Handover[A](live: java.util.concurrent.atomic.AtomicInteger) extends Waiter[A](live):
            val settled = new java.util.concurrent.atomic.AtomicBoolean(false)

        sealed abstract class BaseUnsafe[A](using AllowUnsafe) extends Unsafe[A]:
            val takes           = new MpmcUnboundedUnsafeQueue[Promise.Unsafe[A, Abort[Closed]]](8)
            val puts            = new MpmcUnboundedUnsafeQueue[Put[A]](8)
            val priorityPuts    = new MpmcUnboundedUnsafeQueue[Put[A]](8)
            val batchInProgress = AtomicBoolean.Unsafe.init(false)

            /** Values a taker was handed and never received. They were accepted by the channel and their producers were told so, so
              * every read takes them before the ring or a parked put, also once the channel stopped accepting puts.
              */
            val returned = new MpmcUnboundedUnsafeQueue[A](8)

            /** Values delivered into a [[Handover]] whose taker has neither claimed nor returned them. A close cannot know its backlog
              * and a `closeAwaitEmpty` cannot know the channel is empty while this is nonzero. Incremented before the delivery is
              * visible, so a reader that sees the ring empty and this zero has seen every value.
              */
            val handovers = AtomicInt.Unsafe.init(0)

            /** Settled once the ring is drained and no handed-over value can still come back. */
            val awaitingEmpty = AtomicRef.Unsafe.init(Maybe.empty[Promise.Unsafe[Boolean, Any]])

            /** Run once no handover is outstanding: a close's backlog is final only then. */
            val awaitingHandovers = new MpmcUnboundedUnsafeQueue[() => Unit](8)

            /** Waiters whose promise is still pending. An interrupted waiter's entry stays in its queue until something polls past
              * it, so the queue sizes over-count; a [[Waiter]] drops out of these when its promise completes.
              */
            val liveTakes = new java.util.concurrent.atomic.AtomicInteger(0)
            val livePuts  = new java.util.concurrent.atomic.AtomicInteger(0)

            /** `reuseTake` entries still in `takes`. Their promises belong to the caller, which resets and reuses them and so can carry
              * no completion hook of the channel's, and they count until the channel takes them out of the queue.
              */
            val reusedTakes = new java.util.concurrent.atomic.AtomicInteger(0)

            private inline def isReused(take: Promise.Unsafe[A, Abort[Closed]]): Boolean = !(take: Any).isInstanceOf[Waiter[?]]

            final protected def pollTake()(using AllowUnsafe): Maybe[Promise.Unsafe[A, Abort[Closed]]] =
                val take = takes.poll()
                take.foreach(t => if isReused(t) then discard(reusedTakes.decrementAndGet()))
                take
            end pollTake

            final protected def requeueTake(take: Promise.Unsafe[A, Abort[Closed]])(using AllowUnsafe): Unit =
                if isReused(take) then discard(reusedTakes.incrementAndGet())
                discard(takes.offer(take))

            final protected def failTakes(failure: Result[Closed, Nothing])(using AllowUnsafe): Unit =
                discard(takes.drain { take =>
                    if isReused(take) then discard(reusedTakes.decrementAndGet())
                    take.completeDiscard(failure)
                })

            final private[kyo] def takeHandover()(using AllowUnsafe, Frame): Handover[A] =
                val handover = new Handover[A](liveTakes)
                discard(takes.offer(Promise.Unsafe.fromIOPromise(handover)))
                flush()
                handover
            end takeHandover

            final private[kyo] def claimHandover(handover: Handover[A])(using AllowUnsafe, Frame): Unit =
                if handover.settled.compareAndSet(false, true) then
                    // The last outstanding handover is what a closed channel's parked takers wait on before they are failed.
                    if handovers.decrementAndGet() == 0 then flush()
                    settle()

            final private[kyo] def settleHandover(handover: Handover[A])(using AllowUnsafe, Frame): Unit =
                if handover.settled.compareAndSet(false, true) then
                    // The completion is the arbiter against a delivery still on its way: winning it means nothing was handed over.
                    if !handover.complete(Result.fail(Closed("Channel", summon[Frame], "the taker left"))) then
                        handover.poll() match
                            case Present(Result.Success(value)) =>
                                discard(returned.offer(value.asInstanceOf[A]))
                                discard(handovers.decrementAndGet())
                                flush()
                                settle()
                            case _ =>
                        end match
                    end if
            end settleHandover

            /** Completes the take with the value, counting it as a handover when the taker is a [[Handover]]. */
            final protected def deliver(take: Promise.Unsafe[A, Abort[Closed]], value: A)(using AllowUnsafe): Boolean =
                (take: Any) match
                    case _: Handover[?] =>
                        discard(handovers.incrementAndGet())
                        take.complete(Result.succeed(value)) || {
                            discard(handovers.decrementAndGet())
                            settle()
                            false
                        }
                    case _ =>
                        take.complete(Result.succeed(value))

            /** Hands returned values to parked takers, oldest first. */
            @tailrec final protected def feedReturned()(using AllowUnsafe): Unit =
                if !takes.isEmpty() then
                    returned.poll() match
                        case Present(value) =>
                            @tailrec def handTo(): Unit =
                                pollTake() match
                                    case Present(take) => if !deliver(take, value) then handTo()
                                    case Absent        => discard(returned.offer(value))
                            handTo()
                            feedReturned()
                        case Absent =>
                    end match

            final protected def pollReturned()(using AllowUnsafe, Frame): Maybe[A] =
                val value = returned.poll()
                if value.nonEmpty then readReturned()
                value
            end pollReturned

            final protected def drainReturned(max: Int)(using AllowUnsafe, Frame): Chunk[A] =
                @tailrec def loop(values: Chunk[A], left: Int): Chunk[A] =
                    if left <= 0 then values
                    else
                        returned.poll() match
                            case Present(value) => loop(values.appended(value), left - 1)
                            case Absent         => values
                val values = loop(Chunk.empty, max)
                if values.nonEmpty then readReturned()
                values
            end drainReturned

            // The last returned value is, like the last handover, what a closed channel's parked takers wait on before they are failed.
            private def readReturned()(using AllowUnsafe, Frame): Unit =
                if returned.isEmpty() then flush()
                settle()

            /** Runs `f` once no handover is outstanding. */
            final protected def whenHandoversSettled(f: () => Unit)(using AllowUnsafe): Unit =
                discard(awaitingHandovers.offer(f))
                settle()

            /** Releases what waits on the handovers: the closes, then a `closeAwaitEmpty` once nothing is left to read. */
            final protected def settle()(using AllowUnsafe): Unit =
                if handovers.get() == 0 then
                    discard(awaitingHandovers.drain(_()))
                    if returned.isEmpty() then
                        awaitingEmpty.getAndSet(Absent).foreach { p =>
                            if handovers.get() == 0 && returned.isEmpty() then p.completeDiscard(Result.succeed(true))
                            else
                                awaitingEmpty.set(Present(p))
                                settle()
                        }
                    end if
                end if
            end settle

            /** Backend-specific queue-state fragment for [[dumpState]]: the underlying bounded ring for a capacity channel, a
              * closed-flag for the zero-capacity rendezvous.
              */
            protected def queueDiagnostic(): String

            override private[kyo] def dumpState(): String =
                // Unsafe: reads run under this channel's own construction-time AllowUnsafe. peek() is non-destructive, so the snapshot
                // never perturbs channel state; the reported next-waiter done() flag distinguishes a live parked waiter from a stale entry.
                s"queue[${queueDiagnostic()}] " +
                    s"takes=${takes.size()}(nextDone=${takes.peek().map(_.done())}) " +
                    s"puts=${puts.size()}(nextDone=${puts.peek().map(_.promise.done())}) " +
                    s"priorityPuts=${priorityPuts.size()}(nextDone=${priorityPuts.peek().map(_.promise.done())}) " +
                    s"batchInProgress=${batchInProgress.get()} " +
                    s"returned=${returned.size()} handovers=${handovers.get()}"
            end dumpState

            protected def flush()(using Frame): Unit

            final def putFiber(value: A)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]] =
                val promise = Promise.Unsafe.fromIOPromise(new Waiter[Unit](livePuts))
                val put     = Put.Value(value, promise)
                discard(puts.offer(put))
                flush()
                promise
            end putFiber

            final def putBatchFiber(values: Seq[A])(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]] =
                val promise = Promise.Unsafe.fromIOPromise(new Waiter[Unit](livePuts))
                val put     = Put.Batch(Chunk.from(values), promise)
                discard(puts.offer(put))
                flush()
                promise
            end putBatchFiber

            final def takeFiber()(using AllowUnsafe, Frame): Fiber.Unsafe[A, Abort[Closed]] =
                val promise = Promise.Unsafe.fromIOPromise(new Waiter[A](liveTakes))
                discard(takes.offer(promise))
                flush()
                promise
            end takeFiber

            /** Registers an existing promise as a taker without allocation. The promise must have been reset via becomeAvailable(). This is
              * the zero-alloc alternative to takeFiber().
              */
            final private[kyo] def reuseTake(promise: Promise.Unsafe[A, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
                discard(reusedTakes.incrementAndGet())
                require(takes.offer(promise), "reuseTake: unbounded queue offer must not fail")
                flush()
            end reuseTake

            /** Skip-if-cancelled poll: when the outer fiber that called Channel.put is interrupted while suspended on the put promise, the
              * promise transitions to an Error state but the Put.Value stays in the puts queue. Naive consumers would deliver the cancelled
              * producer's value to a future take — silently violating the interrupt-during-put-must-not-deliver contract honored by other
              * concurrent Queue primitives (cats-effect, ZIO, Loom). Consume sites use this helper to drop cancelled Put.Value entries and
              * recurse to the next live producer. Put.Batch entries are returned as-is; their per-element completion path handles partial
              * cancellation differently.
              */
            @tailrec
            final protected def pollNextLive()(using AllowUnsafe, Frame): Maybe[Put[A]] =
                (priorityPuts.poll().orElse(puts.poll()): @unchecked) match
                    case Absent                                           => Absent
                    case Present(Put.Value(_, promise)) if promise.done() => pollNextLive()
                    case Present(p)                                       => Present(p)
        end BaseUnsafe

        final class ZeroCapacityUnsafe[A](val initFrame: Frame)(using allow: AllowUnsafe) extends BaseUnsafe[A]:
            val isClosed                                            = AtomicBoolean.Unsafe.init(false)
            @volatile private var pendingBatch: Maybe[Put.Batch[A]] = Absent

            protected def queueDiagnostic(): String = s"zero-capacity(closed=${isClosed.get()}, pendingBatch=${pendingBatch.isDefined})"

            private def closedResult(using Frame) = Result.fail(Closed("Channel", initFrame, "zero-capacity"))

            /** Succeeds with the value if the channel is still open, otherwise fails with a [[Closed]] error.
              *
              * @param value
              *   The value to succeed with
              * @return
              *   The successful value or a [[Closed]] error
              */
            private def succeedIfOpen[B](value: B)(using Frame): Result[Closed, B] =
                if isClosed.get() then closedResult else Result.succeed(value)

            /** Succeeds with the value if it is non-empty or the channel is still open, otherwise fails with a [[Closed]] error.
              *
              * This is used in cases where the channel may be closed, but we still have a value to return. This typically occurs when the
              * producer calls [[closeAwaitEmpty]] and the consumer calls [[drain]] or [[drainUpTo]] where the drain will close the channel
              * once it has drained the last item, but we want the effect of the close occurring after the drain. This may also occur in a
              * race condition where the producer checks if the channel is empty and closes it while the consumer is draining.
              *
              * @param value
              *   The value to succeed with
              * @return
              *   The successful value or a [[Closed]] error
              */
            private def succeedIfNonEmptyOrOpen[B](value: Chunk[B])(using Frame): Result[Closed, Chunk[B]] =
                if value.nonEmpty then Result.succeed(value) else succeedIfOpen(value)

            def capacity = 0

            def size()(using AllowUnsafe, Frame) =
                if returned.isEmpty() then succeedIfOpen(0) else Result.succeed(returned.size())

            def pendingPuts()(using AllowUnsafe, Frame)  = succeedIfOpen(livePuts.get())
            def pendingTakes()(using AllowUnsafe, Frame) = succeedIfOpen(liveTakes.get() + reusedTakes.get())

            def offer(value: A)(using AllowUnsafe, Frame) =
                if !returned.isEmpty() then flush()
                pollTake() match
                    case Absent =>
                        succeedIfOpen(false)
                    case Present(takePromise) =>
                        if deliver(takePromise, value) then succeedIfOpen(true)
                        else offer(value)
                end match
            end offer

            def offerAll(values: Seq[A])(using AllowUnsafe, Frame): Result[Closed, Chunk[A]] =
                @tailrec def loop(currentChunk: Chunk[A]): Result[Closed, Chunk[A]] =
                    currentChunk.headMaybe match
                        case Absent =>
                            succeedIfOpen(Chunk.empty)
                        case Present(value) =>
                            pollTake() match
                                case Absent =>
                                    succeedIfOpen {
                                        currentChunk
                                    }
                                case Present(takePromise) =>
                                    if deliver(takePromise, value) then loop(currentChunk.dropLeft(1))
                                    else loop(currentChunk)

                if !returned.isEmpty() then flush()
                loop(Chunk.from(values))
            end offerAll

            def poll()(using AllowUnsafe, Frame) =
                pollReturned() match
                    case Present(value) => Result.succeed(Present(value))
                    case Absent         =>
                        if isClosed.get() then closedResult
                        else Result.succeed(readPuts(Chunk.empty, 1).headMaybe)
            end poll

            def drainUpTo(max: Int)(using AllowUnsafe, Frame) =
                val first = drainReturned(max)
                succeedIfNonEmptyOrOpen(readPuts(first, max - first.length))
            end drainUpTo

            def drain()(using AllowUnsafe, Frame) =
                succeedIfNonEmptyOrOpen(readPuts(drainReturned(Int.MaxValue), Int.MaxValue))

            /** Reads up to `max` values from parked producers, oldest first, appended to `read`.
              *
              * A batch is read element by element under the transfer claim, which `flush` holds while it moves a batch, so a read and
              * a transfer never split one batch out of order. What a read leaves of a batch stays at the head as `pendingBatch`, and
              * its producer completes only with its last element.
              *
              * A read that finds the claim held reads nothing rather than waiting: the holder may be this thread, completing a take
              * whose callback reads the channel, and the transfer holding it is already moving the parked values to takers.
              */
            private def readPuts(read: Chunk[A], max: Int)(using AllowUnsafe, Frame): Chunk[A] =
                @tailrec def loop(current: Chunk[A], left: Int): Chunk[A] =
                    if left <= 0 then current
                    else
                        pendingBatch match
                            case Present(Put.Batch(chunk, promise)) =>
                                val count = Math.min(left, chunk.length)
                                if count == chunk.length then
                                    pendingBatch = Absent
                                    promise.completeUnitDiscard()
                                else pendingBatch = Present(Put.Batch(chunk.dropLeft(count), promise))
                                end if
                                loop(current.concat(chunk.take(count)), left - count)
                            case _ =>
                                pollNextLive() match
                                    case Absent =>
                                        current
                                    case Present(Put.Value(value, promise)) =>
                                        promise.completeUnitDiscard()
                                        loop(current.appended(value), left - 1)
                                    case Present(batch: Put.Batch[A] @unchecked) =>
                                        pendingBatch = Present(batch)
                                        loop(current, left)
                        end match
                if !batchInProgress.compareAndSet(false, true) then read
                else
                    val result = loop(read, max)
                    releaseTransfer()
                    result
                end if
            end readPuts

            // A zero-capacity channel has no ring, so its backlog is the returned values once no handover can still add one.
            def close()(using frame: Frame, allow: AllowUnsafe) =
                val result = Promise.Unsafe.init[Maybe[Seq[A]], Any]()
                val first  = !isClosed.getAndSet(true)
                // A close after a closeAwaitEmpty that is still waiting on returned values ends that wait and owns the backlog.
                val owns = first || awaitingEmpty.getAndSet(Absent).exists { p =>
                    p.completeDiscard(Result.succeed(false))
                    true
                }
                if !owns then result.completeDiscard(Result.succeed(Absent))
                else
                    flush()
                    whenHandoversSettled { () =>
                        result.completeDiscard(Result.succeed(Present(drainReturned(Int.MaxValue))))
                        flush()
                    }
                end if
                result
            end close

            def closeAwaitEmpty()(using Frame, AllowUnsafe) =
                if isClosed.getAndSet(true) then Fiber.Unsafe.fromResult(Result.succeed(false))
                else
                    val result = Promise.Unsafe.init[Boolean, Any]()
                    awaitingEmpty.set(Present(result))
                    flush()
                    settle()
                    result
            end closeAwaitEmpty

            def empty()(using AllowUnsafe, Frame) = if returned.isEmpty() then succeedIfOpen(true) else Result.succeed(false)
            def full()(using AllowUnsafe, Frame)  = succeedIfOpen(true)
            def closed()(using AllowUnsafe)       = isClosed.get()

            @tailrec protected def flush()(using Frame): Unit =
                // This method ensures that all values are processed
                // and handles interrupted fibers by discarding them.

                val putsEmpty  = pendingBatch.isEmpty && priorityPuts.isEmpty() && puts.isEmpty()
                val takesEmpty = takes.isEmpty()
                // A parked taker may still be handed a returned value, so a close fails it only once none can come back.
                val owed = handovers.get() != 0 || !returned.isEmpty()

                if !returned.isEmpty() && !takesEmpty then
                    feedReturned()
                    flush()
                else if !putsEmpty && !takesEmpty then
                    // Ahead of the closing drain: a producer and a taker that are both parked are paired even on a closed channel,
                    // so the drain fails only what has no counterpart. A close that finds the claim held leaves the pairing to the
                    // holder, which flushes again on release.
                    if batchInProgress.compareAndSet(false, true) then
                        val put = pendingBatch match
                            case Present(batch) =>
                                pendingBatch = Absent
                                Present(batch: Put[A])
                            case _ =>
                                pollNextLive()
                        put.foreach {
                            case put @ Put.Value(value, promise) =>
                                pollTake() match
                                    case Present(takePromise) if deliver(takePromise, value) =>
                                        promise.completeUnitDiscard()

                                    case _ =>
                                        discard(puts.offer(put))
                                end match

                            case Put.Batch(chunk, promise) =>
                                val size = chunk.length
                                @tailrec
                                def loop(i: Int): Unit =
                                    if i >= size then
                                        promise.completeUnitDiscard()
                                    else
                                        pollTake() match
                                            case Present(takePromise) =>
                                                if deliver(takePromise, chunk(i)) then
                                                    loop(i + 1)
                                                else
                                                    loop(i)
                                                end if
                                            case _ =>
                                                pendingBatch = Present(Put.Batch(chunk.dropLeft(i), promise))
                                    end if
                                end loop

                                loop(0)
                        }
                        batchInProgress.set(false)
                        flush()
                    end if
                else if isClosed.get() && (!putsEmpty || (!takesEmpty && !owed)) then
                    pendingBatch.foreach(_.promise.completeDiscard(closedResult))
                    pendingBatch = Absent
                    if !owed then failTakes(closedResult)
                    discard(priorityPuts.drain(_.promise.completeDiscard(closedResult)))
                    discard(puts.drain(_.promise.completeDiscard(closedResult)))
                    flush()
                end if
            end flush

            /** Releases the transfer claim the way every holder does: the flush that follows runs whatever waited on the claim,
              * a close's pairing among it.
              */
            private[kyo] def releaseTransfer()(using Frame): Unit =
                batchInProgress.set(false)
                flush()
        end ZeroCapacityUnsafe

        final class NonZeroCapacityUnsafe[A](
            override val capacity: Int,
            access: Access = Access.MultiProducerMultiConsumer
        )(using initFrame: Frame, allow: AllowUnsafe) extends BaseUnsafe[A]:
            val queue = Queue.Unsafe.init[A](capacity, access)

            protected def queueDiagnostic(): String = queue.diagnosticState()

            def size()(using AllowUnsafe, Frame) =
                if returned.isEmpty() then queue.size()
                else Result.succeed(queue.size().getOrElse(0) + returned.size())

            def pendingPuts()(using AllowUnsafe, Frame)  = queue.size().map(_ => livePuts.get())
            def pendingTakes()(using AllowUnsafe, Frame) = queue.size().map(_ => liveTakes.get() + reusedTakes.get())

            def offer(value: A)(using AllowUnsafe, Frame) =
                val result = queue.offer(value)
                if result.contains(true) then flush()
                result
            end offer

            def offerAll(values: Seq[A])(using AllowUnsafe, Frame): Result[Closed, Chunk[A]] =
                @tailrec
                def loop(current: Chunk[A], offered: Boolean = false): Result[Closed, Chunk[A]] =
                    if current.isEmpty then
                        if offered then flush()
                        Result.Success(Chunk.empty)
                    else
                        queue.offer(current.head) match
                            case Result.Success(true) =>
                                loop(current.tail, true)
                            case Result.Success(false) =>
                                if offered then flush()
                                Result.succeed(current)
                            case result =>
                                if offered then flush()
                                result.map(_ => current)
                    end if
                end loop
                loop(Chunk.from(values))
            end offerAll

            def poll()(using AllowUnsafe, Frame) =
                pollReturned() match
                    case Present(value) => Result.succeed(Present(value))
                    case Absent         =>
                        while batchInProgress.get() do ()
                        val result = queue.poll()
                        if result.exists(_.nonEmpty) then flush()
                        result
            end poll

            def drainUpTo(max: Int)(using AllowUnsafe, Frame) =
                @tailrec
                def loop(current: Chunk[A], i: Int): Result[Closed, Chunk[A]] =
                    if i == 0 then Result.Success(current)
                    else
                        while batchInProgress.get() do ()
                        val next = queue.drainUpTo(i)
                        next match
                            case Result.Success(c) =>
                                if c.isEmpty then Result.Success(current)
                                else
                                    flush()
                                    loop(current.concat(c), i - c.length)
                            case _ if current.nonEmpty => Result.Success(current)
                            case other                 => other
                        end match
                    end if
                end loop

                val first = drainReturned(max)
                loop(first, max - first.length)
            end drainUpTo

            def drain()(using AllowUnsafe, Frame) =
                @tailrec
                def loop(current: Chunk[A]): Result[Closed, Chunk[A]] =
                    val next = queue.drain()
                    next match
                        case Result.Success(c) =>
                            if c.isEmpty then Result.Success(current)
                            else
                                flush()
                                loop(current.concat(c))
                        case _ if current.nonEmpty => Result.Success(current)
                        case other                 => other
                    end match
                end loop

                loop(drainReturned(Int.MaxValue))
            end drain

            def close()(using Frame, AllowUnsafe) =
                val r      = queue.close()
                val result = Promise.Unsafe.init[Maybe[Seq[A]], Any]()
                // The ring is drained by whoever wins the queue's handover, which may be an offer still in flight, so the flush that
                // fails parked puts and wakes parked takes runs on completion rather than here. Same shape as closeAwaitEmpty below.
                // The backlog is final only once no handover can still return a value, and returned values come first.
                r.onComplete { closing =>
                    flush()
                    closing match
                        case Result.Success(value) =>
                            value.asInstanceOf[Maybe[Seq[A]]] match
                                case Present(backlog) =>
                                    whenHandoversSettled { () =>
                                        result.completeDiscard(
                                            Result.succeed(Present(drainReturned(Int.MaxValue).concat(Chunk.from(backlog))))
                                        )
                                        flush()
                                    }
                                case Absent =>
                                    // The ring already closed through closeAwaitEmpty, which may still be waiting on returned values: this
                                    // close ends that wait and owns them.
                                    awaitingEmpty.getAndSet(Absent) match
                                        case Present(waiting) =>
                                            waiting.completeDiscard(Result.succeed(false))
                                            whenHandoversSettled { () =>
                                                result.completeDiscard(Result.succeed(Present(drainReturned(Int.MaxValue))))
                                                flush()
                                            }
                                        case Absent =>
                                            result.completeDiscard(Result.succeed(Absent))
                            end match
                        case failure =>
                            result.completeDiscard(failure.asInstanceOf[Result[Nothing, Maybe[Seq[A]] < Any]])
                    end match
                }
                result
            end close

            def closeAwaitEmpty()(using Frame, AllowUnsafe) =
                val r      = queue.closeAwaitEmpty()
                val result = Promise.Unsafe.init[Boolean, Any]()
                // The queue is now HalfOpen: it rejects new offers, so a producer parked because the ring was full
                // can never be transferred in. Fail those parked puts now with the closing error rather than
                // deferring to `flush`, which fails parked puts only on its FullyClosed drain, and the queue reaches
                // FullyClosed only once a consumer has drained the ring empty, a consumer that may never come. The
                // buffered ring values are untouched and still drain to consumers, which is what completes `r`. This
                // is the same drain `flush`'s FullyClosed branch does, applied at HalfOpen time so it does not depend
                // on a consumer.
                val closed = Result.fail(Closed("Channel", initFrame, "closeAwaitEmpty"))
                discard(priorityPuts.drain(_.promise.completeDiscard(closed)))
                discard(puts.drain(_.promise.completeDiscard(closed)))
                // A drained ring is not an empty channel while a handed-over value can still come back.
                r.onComplete { drained =>
                    flush()
                    drained match
                        case Result.Success(value) if value.asInstanceOf[Boolean] =>
                            awaitingEmpty.set(Present(result))
                            settle()
                        case other =>
                            result.completeDiscard(other.asInstanceOf[Result[Nothing, Boolean < Any]])
                    end match
                }
                result
            end closeAwaitEmpty

            def empty()(using AllowUnsafe, Frame) = queue.empty().map(_ && returned.isEmpty())
            def full()(using AllowUnsafe, Frame)  = queue.full()
            def closed()(using AllowUnsafe)       = queue.closed()

            @tailrec protected def flush()(using Frame): Unit =
                // This method ensures that all values are processed
                // and handles interrupted fibers by discarding them.
                val queueClosed = queue.closed()
                val queueSize   = queue.size().getOrElse(0)
                val takesEmpty  = takes.isEmpty()
                val putsEmpty   = priorityPuts.isEmpty() && puts.isEmpty()
                // A parked taker may still be handed a returned value, so a close fails it only once none can come back.
                val owed = handovers.get() != 0 || !returned.isEmpty()

                if !returned.isEmpty() && !takesEmpty then
                    feedReturned()
                    flush()
                else if queueClosed && (!putsEmpty || (!takesEmpty && !owed)) then
                    // Queue is closed, drain all takes and puts
                    val fail = queue.size() // Obtain the failed Result
                    if !owed then failTakes(fail.asInstanceOf[Result[Closed, Nothing]])
                    discard(priorityPuts.drain(_.promise.completeDiscard(fail.map(_ => ()))))
                    discard(puts.drain(_.promise.completeDiscard(fail.map(_ => ()))))
                    flush()
                else if !putsEmpty && queue.offersRejected() then
                    // The queue is soft-closed (HalfOpen: it rejects every new offer while draining its ring to consumers) but not yet
                    // FullyClosed, so the branch above has not fired. A parked put can never be transferred in from here, and with no
                    // consumer the ring may never drain to escalate FullyClosed, so nothing else would ever settle it. Fail it now with
                    // the closing error. This catches a put that registered after closeAwaitEmpty's one-shot drain. Takes are left intact:
                    // buffered ring values still drain to them via the transfer branch below.
                    val closing = Result.fail(Closed("Channel", initFrame, "closeAwaitEmpty"))
                    discard(priorityPuts.drain(_.promise.completeDiscard(closing)))
                    discard(puts.drain(_.promise.completeDiscard(closing)))
                    flush()
                else if queueSize > 0 && !takesEmpty then
                    // Attempt to transfer a value from the queue to
                    // a waiting take operation.
                    pollTake().foreach { promise =>
                        // Counted before the ring gives the value up, so a closeAwaitEmpty that sees the ring drained also sees the
                        // value on its way to this taker.
                        val tracked = (promise: Any).isInstanceOf[Handover[?]]
                        if tracked then discard(handovers.incrementAndGet())
                        val delivered =
                            queue.poll() match
                                case Result.Success(Present(value)) =>
                                    promise.complete(Result.succeed(value)) || {
                                        // The take was interrupted before receiving the value, which is still the channel's: the
                                        // next read takes it ahead of the ring, also once the ring stopped accepting writes.
                                        discard(returned.offer(value))
                                        false
                                    }
                                case _ =>
                                    // Queue became empty, enqueue the take again
                                    requeueTake(promise)
                                    false
                        if tracked && !delivered then
                            discard(handovers.decrementAndGet())
                            settle()
                    }
                    flush()
                else if queueSize < capacity && !putsEmpty then
                    // Attempt to transfer a value from a waiting put operation to the queue.
                    // Only one thread processes puts at a time to prevent batch interleaving.
                    if batchInProgress.compareAndSet(false, true) then
                        pollNextLive().foreach {
                            case Put.Batch(chunk, promise) =>
                                // NB: this is only efficient if chunk is effectively indexed
                                // (i.e. Chunk.Indexed or Chunk.Drop with Chunk.Indexed underlying)
                                val size = chunk.length
                                @tailrec
                                def loop(i: Int): Unit =
                                    if i >= size then
                                        // All items offered, complete put
                                        promise.completeUnitDiscard()
                                    else
                                        queue.offer(chunk(i)) match
                                            case Result.Success(true)  => loop(i + 1)
                                            case Result.Success(false) =>
                                                // Queue became full, add pending put for the rest of the batch
                                                discard(priorityPuts.offer(Put.Batch(chunk.dropLeft(i), promise)))
                                            case error =>
                                                // Closing or closed: the offer can never succeed again; re-enqueueing would livelock this flush. Fail like the closed drain.
                                                promise.completeDiscard(error.map(_ => ()))
                                        end match

                                loop(0)

                            case put @ Put.Value(value, promise) =>
                                queue.offer(value) match
                                    case Result.Success(true) =>
                                        promise.completeUnitDiscard()
                                    case Result.Success(false) =>
                                        discard(puts.offer(put))
                                    case error =>
                                        // closing/closed: fail rather than re-enqueue (see the batch arm above)
                                        promise.completeDiscard(error.map(_ => ()))
                                end match
                        }
                        batchInProgress.set(false)
                        flush()
                    end if
                else if queueSize == 0 && !putsEmpty && !takesEmpty then
                    // Directly transfer a value from a producer to a consumer when the queue is empty.
                    // Only one thread processes puts at a time to prevent batch interleaving.
                    if batchInProgress.compareAndSet(false, true) then
                        pollNextLive().foreach {
                            case put @ Put.Value(value, promise) =>
                                pollTake() match
                                    case Present(takePromise) if deliver(takePromise, value) =>
                                        // Value transfered, complete put
                                        promise.completeUnitDiscard()

                                    case _ =>
                                        // Take promise was interrupted, return put to the queue
                                        discard(puts.offer(put))

                            case Put.Batch(chunk, promise) =>
                                // NB: this is only efficient if chunk is effectively indexed
                                // (i.e. Chunk.Indexed or Chunk.Drop with Chunk.Indexed underlying)
                                val size = chunk.length
                                @tailrec
                                def loop(i: Int): Unit =
                                    if i >= size then
                                        // All items transfered, complete put
                                        promise.completeUnitDiscard()
                                    else
                                        pollTake() match
                                            case Present(takePromise) =>
                                                if deliver(takePromise, chunk(i)) then
                                                    // Item transfered, move to the next one
                                                    loop(i + 1)
                                                else
                                                    // Take was interrupted, retry current item
                                                    loop(i)
                                            case _ =>
                                                // No more pending takes, enqueue put for the remaining items
                                                discard(priorityPuts.offer(Put.Batch(chunk.dropLeft(i), promise)))
                                    end if
                                end loop

                                loop(0)
                        }
                        batchInProgress.set(false)
                        flush()
                    end if
                end if
            end flush
        end NonZeroCapacityUnsafe

    end Unsafe
end Channel
