package kyo

import org.jctools.queues.MpmcUnboundedXaddArrayQueue
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
  *   - `offer` attempts to add an element, returning true if successful or false if the channel is full
  *   - `poll` attempts to retrieve an element, returning Maybe.empty if the channel is empty
  *
  * Asynchronous operations (put/take) will suspend the current fiber until the operation can complete:
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
  * WARNING: On the JVM, the actual capacity of a Channel is rounded up to the next power of two for performance reasons. For example, if
  * you specify a capacity of 10, the actual capacity will be 16.
  *
  * @see
  *   [[kyo.Queue]] A similar structure without the fiber-aware asynchronous operations
  * @see
  *   [[kyo.Hub]] A multi-producer, multi-consumer broadcast primitive for one-to-many communication
  * @see
  *   [[kyo.Access]] For available producer-consumer access patterns
  *
  * @tparam A
  *   The type of elements that can be sent through the channel
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
        def size(using Frame): Int < (Abort[Closed] & Sync) = Sync.Unsafe(Abort.get(self.size()))

        /** Returns the number of fibers currently waiting to put values into the channel.
          *
          * This method provides visibility into the backpressure state of the channel by counting how many producer fibers are currently
          * suspended waiting for space to become available. A non-zero value indicates that producers are being throttled due to the
          * channel being full.
          *
          * @return
          *   The number of fibers waiting to put values into the channel
          */
        def pendingPuts(using Frame): Int < (Abort[Closed] & Sync) = Sync.Unsafe(Abort.get(self.pendingPuts()))

        /** Returns the number of fibers currently waiting to take values from the channel.
          *
          * This method provides visibility into the consumer demand state of the channel by counting how many consumer fibers are currently
          * suspended waiting for values to become available. A non-zero value indicates that consumers are waiting for producers to add
          * values.
          *
          * @return
          *   The number of fibers waiting to take values from the channel
          */
        def pendingTakes(using Frame): Int < (Abort[Closed] & Sync) = Sync.Unsafe(Abort.get(self.pendingTakes()))

        /** Attempts to offer an element to the channel without blocking.
          *
          * @param value
          *   The element to offer
          * @return
          *   true if the element was added to the channel, false otherwise
          */
        def offer(value: A)(using Frame): Boolean < (Abort[Closed] & Sync) = Sync.Unsafe(Abort.get(self.offer(value)))

        /** Offers an element to the channel without returning a result.
          *
          * @param v
          *   The element to offer
          */
        def offerDiscard(value: A)(using Frame): Unit < (Abort[Closed] & Sync) = Sync.Unsafe(Abort.get(self.offer(value).unit))

        /** Attempts to poll an element from the channel without blocking.
          *
          * @return
          *   Maybe containing the polled element, or empty if the channel is empty
          */
        def poll(using Frame): Maybe[A] < (Abort[Closed] & Sync) = Sync.Unsafe(Abort.get(self.poll()))

        /** Puts an element into the channel, asynchronously blocking if necessary.
          *
          * @param value
          *   The element to put
          */
        def put(value: A)(using Frame): Unit < (Abort[Closed] & Async) =
            Sync.Unsafe {
                self.offer(value).foldError(
                    {
                        case true  => ()
                        case false => self.putFiber(value).safe.get
                    },
                    Abort.error
                )
            }

        /** Puts elements into the channel as a batch, asynchronously blocking if necessary. Breaks batch up if it exceeds channel capacity.
          *
          * @param values
          *   Chunk of elements to put
          */
        def putBatch(values: Seq[A])(using Frame): Unit < (Abort[Closed] & Async) =
            if values.isEmpty then ()
            else if self.capacity == 0 then
                Sync.Unsafe(self.putBatchFiber(values).safe.get)
            else
                Sync.Unsafe {
                    self.offerAll(values) match
                        case Result.Success(remaining) =>
                            if remaining.isEmpty then ()
                            else
                                self.putBatchFiber(remaining).safe.get
                        case err @ Result.Error(_) => Abort.get(err.unit)
                }
            end if
        end putBatch

        /** Takes an element from the channel, asynchronously blocking if necessary.
          *
          * @return
          *   The taken element
          */
        def take(using Frame): A < (Abort[Closed] & Async) =
            Sync.Unsafe {
                self.poll().foldError(
                    {
                        case Present(value) => value
                        case Absent         => self.takeFiber().safe.get
                    },
                    Abort.error
                )
            }
        end take

        /** Takes [[n]] elements from the channel, semantically blocking until enough elements are present. Note that if enough elements are
          * not added to the channel it can block indefinitely.
          *
          * @return
          *   Chunk of [[n]] elements
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
        def drain(using Frame): Chunk[A] < (Abort[Closed] & Sync) = Sync.Unsafe(Abort.get(self.drain()))

        /** Takes up to [[max]] elements from the channel.
          *
          * @return
          *   a sequence of up to [[max]] elements that were in the channel.
          */
        def drainUpTo(max: Int)(using Frame): Chunk[A] < (Sync & Abort[Closed]) = Sync.Unsafe(Abort.get(self.drainUpTo(max)))

        /** Closes the channel.
          *
          * @return
          *   A sequence of remaining elements
          */
        def close(using Frame): Maybe[Seq[A]] < Sync = Sync.Unsafe(self.close())

        /** Closes the channel and asynchronously waits until it's empty.
          *
          * This method closes the channel to new elements and returns a computation that completes when all elements have been consumed.
          * Unlike the regular [[close]] method, this allows consumers to process all remaining elements before considering the channel
          * fully closed.
          *
          * @return
          *   `true` if the channel was successfully closed and emptied, `false` if it was already closed
          */
        def closeAwaitEmpty(using Frame): Boolean < Async = Sync.Unsafe(self.closeAwaitEmpty().safe.get)

        /** Closes the channel and returns the [[Fiber]] waits until it's empty.
          *
          * This method closes the channel to new elements and returns a `Fiber` that completes when all elements have been consumed. Unlike
          * the regular [[close]] method, this allows consumers to process all remaining elements before considering the channel fully
          * closed.
          *
          * This differs from [[closeAwaitEmpty]] in that once the `Fiber` has been obtained it guarantees to have begun closing the channel
          * and future offers to the channel will abort with [[Closed]] even if the channel is not yet completely closed. On the other hand,
          * when handling the `Async` effect from `closeAwaitEmpty` the `Fiber` it returns may not have started closing the channel yet.
          *
          * @return
          *   a `Fiber` that completes with `true` if the channel was successfully closed and emptied, `false` if it was already closed
          */
        def closeAwaitEmptyFiber(using Frame): Fiber[Nothing, Boolean] < Sync = Sync.Unsafe(self.closeAwaitEmpty().safe)

        /** Checks if the channel is closed.
          *
          * A channel is considered closed if it has fully closed, i.e. it is not open and it is empty.
          *
          * This will always be `true` after [[close]]. In the case of [[closeAwaitEmpty]] and [[closeAwaitEmptyFiber]], it will only be
          * `true` once the channel has been emptied.
          *
          * @return
          *   `true` if the channel is closed, `false` otherwise
          */
        def closed(using Frame): Boolean < Sync = Sync.Unsafe(self.closed())

        /** Checks if the channel is open.
          *
          * A channel is considered open if it has not begun closing, and it may still accept new elements (although it might be full).
          *
          * @return
          *   `true` if the channel is open, `false` otherwise
          */
        def open(using Frame): Boolean < Sync = Sync.Unsafe(self.open())

        /** Checks if the channel is empty.
          *
          * @return
          *   true if the channel is empty, false otherwise
          */
        def empty(using Frame): Boolean < (Abort[Closed] & Sync) = Sync.Unsafe(Abort.get(self.empty()))

        /** Checks if the channel is full.
          *
          * @return
          *   true if the channel is full, false otherwise
          */
        def full(using Frame): Boolean < (Abort[Closed] & Sync) = Sync.Unsafe(Abort.get(self.full()))

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
                        case _ =>
                            for
                                a  <- Channel.take(self)
                                ch <- Channel.drainUpTo(self)(maxChunkSize - 1)
                            yield Emit.value(Chunk(a).concat(ch))

        /** Stream elements from channel, optionally specifying a maximum chunk size. In the absence of [[maxChunkSize]], chunk sizes will
          * be limited only by channel capacity or the number of elements in the channel at a given time. (Chunks can still be larger than
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
    def init[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using Frame): Channel[A] < Sync =
        initWith[A](capacity, access)(identity)

    /** Uses a new Channel with the provided configuration.
      * @param f
      *   The function to apply to the new Channel
      * @return
      *   The result of applying the function
      */
    inline def initWith[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)[B, S](
        inline f: Channel[A] => B < S
    )(using inline frame: Frame): B < (S & Sync) =
        Sync.Unsafe(f(Unsafe.init(capacity, access)))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    sealed abstract class Unsafe[A] extends Serializable:
        def capacity: Int
        def size()(using AllowUnsafe, Frame): Result[Closed, Int]
        def pendingPuts()(using AllowUnsafe, Frame): Result[Closed, Int]
        def pendingTakes()(using AllowUnsafe, Frame): Result[Closed, Int]

        def offer(value: A)(using AllowUnsafe, Frame): Result[Closed, Boolean]
        def offerAll(values: Seq[A])(using AllowUnsafe, Frame): Result[Closed, Chunk[A]]
        def poll()(using AllowUnsafe, Frame): Result[Closed, Maybe[A]]

        def putFiber(value: A)(using AllowUnsafe, Frame): Fiber.Unsafe[Closed, Unit]
        def putBatchFiber(values: Seq[A])(using AllowUnsafe, Frame): Fiber.Unsafe[Closed, Unit]
        def takeFiber()(using AllowUnsafe, Frame): Fiber.Unsafe[Closed, A]

        def drain()(using AllowUnsafe, Frame): Result[Closed, Chunk[A]]
        def drainUpTo(max: Int)(using AllowUnsafe, Frame): Result[Closed, Chunk[A]]
        def close()(using Frame, AllowUnsafe): Maybe[Seq[A]]
        def closeAwaitEmpty()(using Frame, AllowUnsafe): Fiber.Unsafe[Nothing, Boolean]

        def empty()(using AllowUnsafe, Frame): Result[Closed, Boolean]
        def full()(using AllowUnsafe, Frame): Result[Closed, Boolean]
        def closed()(using AllowUnsafe): Boolean
        def open()(using AllowUnsafe): Boolean

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
            val promise: Promise.Unsafe[Closed, Unit]
            case Batch(batch: Chunk[A], override val promise: Promise.Unsafe[Closed, Unit])
            case Value(value: A, override val promise: Promise.Unsafe[Closed, Unit])
        end Put

        sealed abstract class BaseUnsafe[A] extends Unsafe[A]:
            val takes = new MpmcUnboundedXaddArrayQueue[Promise.Unsafe[Closed, A]](8)
            val puts  = new MpmcUnboundedXaddArrayQueue[Put[A]](8)

            protected def flush()(using Frame): Unit

            final def putFiber(value: A)(using AllowUnsafe, Frame): Fiber.Unsafe[Closed, Unit] =
                val promise = Promise.Unsafe.init[Closed, Unit]()
                val put     = Put.Value(value, promise)
                puts.add(put)
                flush()
                promise
            end putFiber

            final def putBatchFiber(values: Seq[A])(using AllowUnsafe, Frame): Fiber.Unsafe[Closed, Unit] =
                val promise = Promise.Unsafe.init[Closed, Unit]()
                val put     = Put.Batch(Chunk.from(values), promise)
                puts.add(put)
                flush()
                promise
            end putBatchFiber

            final def takeFiber()(using AllowUnsafe, Frame): Fiber.Unsafe[Closed, A] =
                val promise = Promise.Unsafe.init[Closed, A]()
                takes.add(promise)
                flush()
                promise
            end takeFiber
        end BaseUnsafe

        final class ZeroCapacityUnsafe[A](val initFrame: Frame)(using allow: AllowUnsafe) extends BaseUnsafe[A]:
            val isClosed = AtomicBoolean.Unsafe.init(false)

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

            def size()(using AllowUnsafe, Frame) = succeedIfOpen(0)

            def pendingPuts()(using AllowUnsafe, Frame)  = succeedIfOpen(puts.size())
            def pendingTakes()(using AllowUnsafe, Frame) = succeedIfOpen(takes.size())

            def offer(value: A)(using AllowUnsafe, Frame) =
                Maybe(takes.poll()) match
                    case Absent =>
                        succeedIfOpen(false)
                    case Present(takePromise) =>
                        if takePromise.complete(Result.succeed(value)) then succeedIfOpen(true)
                        else offer(value)

            def offerAll(values: Seq[A])(using AllowUnsafe, Frame): Result[Closed, Chunk[A]] =
                @tailrec def loop(currentChunk: Chunk[A]): Result[Closed, Chunk[A]] =
                    currentChunk.headMaybe match
                        case Absent =>
                            succeedIfOpen(Chunk.empty)
                        case Present(value) =>
                            Maybe(takes.poll()) match
                                case Absent =>
                                    succeedIfOpen {
                                        currentChunk
                                    }
                                case Present(takePromise) =>
                                    if takePromise.complete(Result.succeed(value)) then loop(currentChunk.dropLeft(1))
                                    else loop(currentChunk)

                loop(Chunk.from(values))
            end offerAll

            def poll()(using AllowUnsafe, Frame) =
                succeedIfOpen {
                    Maybe(puts.poll()) match
                        case Absent =>
                            Absent
                        case Present(Put.Value(value, promise)) =>
                            discard(promise.complete(Result.unit))
                            flush()
                            Present(value)
                        case Present(Put.Batch(batch, promise)) =>
                            val result = batch.headMaybe match
                                case Absent =>
                                    discard(promise.complete(Result.unit))
                                    Absent
                                case Present(value) =>
                                    if batch.tail.nonEmpty then discard(puts.offer(Put.Batch(batch.tail, promise)))
                                    else discard(promise.complete(Result.unit))
                                    Present(value)
                            flush()
                            result
                }
            end poll

            def drainUpTo(max: Int)(using AllowUnsafe, Frame) =
                @tailrec
                def loop(current: Chunk[A], i: Int): Result[Closed, Chunk[A]] =
                    if i <= 0 then Result.Success(current)
                    else
                        Maybe(puts.poll()) match
                            case Absent =>
                                flush()
                                succeedIfNonEmptyOrOpen(current)
                            case Present(Put.Value(value, promise)) =>
                                discard(promise.complete(Result.unit))
                                loop(current.appended(value), i - 1)
                            case Present(Put.Batch(batch, promise)) =>
                                val taken     = batch.take(i)
                                val remaining = batch.drop(i)
                                if remaining.nonEmpty then
                                    discard(puts.offer(Put.Batch(remaining, promise)))
                                    flush()
                                    succeedIfNonEmptyOrOpen(current.concat(taken))
                                else
                                    discard(promise.complete(Result.unit))
                                    loop(current.concat(taken), i - taken.length)
                                end if
                        end match
                    end if
                end loop

                loop(Chunk.empty, max)
            end drainUpTo

            def drain()(using AllowUnsafe, Frame) =
                @tailrec
                def loop(current: Chunk[A]): Result[Closed, Chunk[A]] =
                    Maybe(puts.poll()) match
                        case Absent =>
                            succeedIfNonEmptyOrOpen(current)
                        case Present(Put.Value(value, promise)) =>
                            discard(promise.complete(Result.unit))
                            loop(current.appended(value))
                        case Present(Put.Batch(batch, promise)) =>
                            discard(promise.complete(Result.unit))
                            loop(current.concat(batch))
                    end match
                end loop

                loop(Chunk.empty)
            end drain

            def close()(using frame: Frame, allow: AllowUnsafe) =
                if isClosed.getAndSet(true) then Absent
                else
                    flush()
                    Present(Chunk.empty)
            end close

            def closeAwaitEmpty()(using Frame, AllowUnsafe) =
                Fiber.Unsafe.init(Result.succeed(close().isDefined))

            def empty()(using AllowUnsafe, Frame) = succeedIfOpen(true)
            def full()(using AllowUnsafe, Frame)  = succeedIfOpen(true)
            def closed()(using AllowUnsafe)       = isClosed.get()
            def open()(using AllowUnsafe)         = !isClosed.get()

            @tailrec protected def flush()(using Frame): Unit =
                // This method ensures that all values are processed
                // and handles interrupted fibers by discarding them.

                val putsEmpty  = puts.isEmpty()
                val takesEmpty = takes.isEmpty()

                if isClosed.get() && (!takesEmpty || !putsEmpty) then
                    takes.drain(_.completeDiscard(closedResult))
                    puts.drain(_.promise.completeDiscard(closedResult))
                    flush()
                else if !putsEmpty && !takesEmpty then
                    Maybe(puts.poll()).foreach { put =>
                        put match
                            case Put.Value(value, promise) =>
                                Maybe(takes.poll()) match
                                    case Present(takePromise) if takePromise.complete(Result.succeed(value)) =>
                                        // Value transfered, complete put
                                        promise.completeDiscard(Result.unit)

                                    case _ =>
                                        // Take promise was interrupted, return put to the queue
                                        discard(puts.add(put))
                                end match

                            case Put.Batch(chunk, promise) =>
                                // NB: this is only efficient if chunk is effectively indexed
                                // (i.e. Chunk.Indexed or Chunk.Drop with Chunk.Indexed underlying)
                                @tailrec
                                def loop(i: Int): Unit =
                                    if i >= chunk.length then
                                        // All items transfered, complete put
                                        promise.completeDiscard(Result.unit)
                                    else
                                        Maybe(takes.poll()) match
                                            case Present(takePromise) =>
                                                if takePromise.complete(Result.succeed(chunk(i))) then
                                                    // Item transfered, move to the next one
                                                    loop(i + 1)
                                                else
                                                    // Take was interrupted, retry current item
                                                    loop(i)
                                                end if
                                            case _ =>
                                                // No more pending takes, enqueue put again for the remaining items
                                                discard(puts.add(Put.Batch(chunk.dropLeft(i), promise)))
                                    end if
                                end loop

                                loop(0)
                        end match
                    }
                    flush()
                end if
            end flush
        end ZeroCapacityUnsafe

        final class NonZeroCapacityUnsafe[A](
            override val capacity: Int,
            access: Access = Access.MultiProducerMultiConsumer
        )(using initFrame: Frame, allow: AllowUnsafe) extends BaseUnsafe[A]:
            val queue = Queue.Unsafe.init[A](capacity, access)

            def size()(using AllowUnsafe, Frame) = queue.size()

            def pendingPuts()(using AllowUnsafe, Frame)  = queue.size().map(_ => puts.size())
            def pendingTakes()(using AllowUnsafe, Frame) = queue.size().map(_ => (takes.size()))

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
                val result = queue.poll()
                if result.exists(_.nonEmpty) then flush()
                result
            end poll

            def drainUpTo(max: Int)(using AllowUnsafe, Frame) =
                @tailrec
                def loop(current: Chunk[A], i: Int): Result[Closed, Chunk[A]] =
                    if i == 0 then Result.Success(current)
                    else
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

                loop(Chunk.empty, max)
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

                loop(Chunk.empty)
            end drain

            def close()(using Frame, AllowUnsafe) =
                queue.close().map { backlog =>
                    flush()
                    backlog
                }

            def closeAwaitEmpty()(using Frame, AllowUnsafe) =
                val r = queue.closeAwaitEmpty()
                r.onComplete(_ => flush())
                r
            end closeAwaitEmpty

            def empty()(using AllowUnsafe, Frame) = queue.empty()
            def full()(using AllowUnsafe, Frame)  = queue.full()
            def closed()(using AllowUnsafe)       = queue.closed()
            def open()(using AllowUnsafe)         = queue.open()

            @tailrec protected def flush()(using Frame): Unit =
                // This method ensures that all values are processed
                // and handles interrupted fibers by discarding them.
                val queueClosed = queue.closed()
                val queueSize   = queue.size().getOrElse(0)
                val takesEmpty  = takes.isEmpty()
                val putsEmpty   = puts.isEmpty()

                if queueClosed && (!takesEmpty || !putsEmpty) then
                    // Queue is closed, drain all takes and puts
                    val fail = queue.size() // Obtain the failed Result
                    takes.drain(_.completeDiscard(fail.asInstanceOf[Result[Closed, Nothing]]))
                    puts.drain(_.promise.completeDiscard(fail.unit))
                    flush()
                else if queueSize > 0 && !takesEmpty then
                    // Attempt to transfer a value from the queue to
                    // a waiting take operation.
                    Maybe(takes.poll()).foreach { promise =>
                        queue.poll() match
                            case Result.Success(Present(value)) =>
                                if !promise.complete(Result.succeed(value)) && !queue.offer(value).contains(true) then
                                    // If completing the take fails and the queue
                                    // cannot accept the value back, enqueue a
                                    // placeholder put operation
                                    val placeholder = Promise.Unsafe.init[Closed, Unit]()
                                    discard(puts.add(Put.Value(value, placeholder)))
                            case _ =>
                                // Queue became empty, enqueue the take again
                                discard(takes.add(promise))
                    }
                    flush()
                else if queueSize < capacity && !putsEmpty then
                    // Attempt to transfer a value from a waiting
                    // put operation to the queue.
                    Maybe(puts.poll()).foreach {
                        case Put.Batch(chunk, promise) =>
                            // NB: this is only efficient if chunk is effectively indexed
                            // (i.e. Chunk.Indexed or Chunk.Drop with Chunk.Indexed underlying)
                            @tailrec
                            def loop(i: Int): Unit =
                                if i >= chunk.length then
                                    // All items offered, complete put
                                    promise.completeDiscard(Result.unit)
                                else if !queue.offer(chunk(i)).contains(true) then
                                    // Queue became full, add pending put for the rest of the batch
                                    discard(puts.add(Put.Batch(chunk.dropLeft(i), promise)))
                                else loop(i + 1)

                            loop(0)

                        case put @ Put.Value(value, promise) =>
                            if queue.offer(value).contains(true) then
                                // Queue accepted the value, complete the put
                                promise.completeDiscard(Result.unit)
                            else
                                // Queue became full, enqueue the put again
                                discard(puts.add(put))
                            end if
                    }
                    flush()
                else if queueSize == 0 && !putsEmpty && !takesEmpty then
                    // Directly transfer a value from a producer to a
                    // consumer when the queue is empty.
                    Maybe(puts.poll()).foreach { put =>
                        put match
                            case Put.Value(value, promise) =>
                                Maybe(takes.poll()) match
                                    case Present(takePromise) if takePromise.complete(Result.succeed(value)) =>
                                        // Value transfered, complete put
                                        promise.completeDiscard(Result.unit)

                                    case _ =>
                                        // Take promise was interrupted, return put to the queue
                                        discard(puts.add(put))

                            case Put.Batch(chunk, promise) =>
                                // NB: this is only efficient if chunk is effectively indexed
                                // (i.e. Chunk.Indexed or Chunk.Drop with Chunk.Indexed underlying)
                                @tailrec
                                def loop(i: Int): Unit =
                                    if i >= chunk.length then
                                        // All items transfered, complete put
                                        promise.completeDiscard(Result.unit)
                                    else
                                        Maybe(takes.poll()) match
                                            case Present(takePromise) =>
                                                if takePromise.complete(Result.succeed(chunk(i))) then
                                                    // Item transfered, move to the next one
                                                    loop(i + 1)
                                                else
                                                    // Take was interrupted, retry current item
                                                    loop(i)
                                            case _ =>
                                                // No more pending takes, enqueue put again for the remaining items
                                                discard(puts.add(Put.Batch(chunk.dropLeft(i), promise)))
                                    end if
                                end loop

                                loop(0)
                        end match
                    }
                    flush()
                end if
            end flush
        end NonZeroCapacityUnsafe

    end Unsafe
end Channel
