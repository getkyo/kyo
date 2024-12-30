package kyo

import java.io.CharArrayReader
import org.jctools.queues.MpmcUnboundedXaddArrayQueue
import scala.annotation.tailrec
import scala.util.NotGiven
import scala.util.boundary

/** A channel for communicating between fibers.
  *
  * @tparam A
  *   The type of elements in the channel
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
        def size(using Frame): Int < (Abort[Closed] & IO) = IO.Unsafe(Abort.get(self.size()))

        /** Attempts to offer an element to the channel without blocking.
          *
          * @param value
          *   The element to offer
          * @return
          *   true if the element was added to the channel, false otherwise
          */
        def offer(value: A)(using Frame): Boolean < (Abort[Closed] & IO) = IO.Unsafe(Abort.get(self.offer(value)))

        /** Offers an element to the channel without returning a result.
          *
          * @param v
          *   The element to offer
          */
        def offerDiscard(value: A)(using Frame): Unit < (Abort[Closed] & IO) = IO.Unsafe(Abort.get(self.offer(value).unit))

        /** Attempts to poll an element from the channel without blocking.
          *
          * @return
          *   Maybe containing the polled element, or empty if the channel is empty
          */
        def poll(using Frame): Maybe[A] < (Abort[Closed] & IO) = IO.Unsafe(Abort.get(self.poll()))

        /** Puts an element into the channel, asynchronously blocking if necessary.
          *
          * @param value
          *   The element to put
          */
        def put(value: A)(using Frame): Unit < (Abort[Closed] & Async) =
            IO.Unsafe {
                self.offer(value).fold(Abort.error) {
                    case true  => ()
                    case false => self.putFiber(value).safe.get
                }
            }

        /** Puts elements into the channel as a batch, asynchronously blocking if necessary. Breaks batch up if it exceeds channel capacity.
          *
          * @param values
          *   Chunk of elements to put
          */
        def putBatch(values: Chunk[A])(using Frame): Unit < (Abort[Closed] & Async) =
            if values.isEmpty then ()
            else
                IO.Unsafe {
                    var i = 0
                    val attempt = boundary[Maybe[Closed]]:
                        values.foreach: value =>
                            val attempt = self.offer(value)
                            if attempt.contains(false) then boundary.break(Maybe.Absent)
                            else if attempt.isFail then boundary.break(attempt.failure)
                            i += 1
                        Maybe.Absent
                    attempt match
                        case Maybe.Present(closed) =>
                            Abort.fail(closed)
                        case _ =>
                            if i >= values.length then
                                ()
                            else if i == values.length - 1 then
                                self.putFiber(values.last).safe.get
                            else
                                self.putBatchFiber(values.dropLeft(i)).safe.get
                            end if
                    end match
                }

        /** Takes an element from the channel, asynchronously blocking if necessary.
          *
          * @return
          *   The taken element
          */
        def take(using Frame): A < (Abort[Closed] & Async) =
            IO.Unsafe {
                self.poll().fold(Abort.error) {
                    case Present(value) => value
                    case Absent         => self.takeFiber().safe.get
                }
            }

        /** Takes [[n]] elements from the channel, semantically blocking until enough elements are present. Note that if enough elements are
          * not added to the channel it can block indefinitely.
          *
          * @return
          *   Chunk of [[n]] elements
          */
        def takeExactly(n: Int)(using Frame): Chunk[A] < (Abort[Closed] & Async) =
            if n <= 0 then Chunk.empty
            else
                Loop(Chunk.empty[A]): lastChunk =>
                    val nextN = n - lastChunk.size
                    Channel.drainUpTo(self)(nextN).map: chunk =>
                        // IO.Unsafe(Abort.get(self.drainUpTo(nextN))).map: chunk =>
                        val chunk1 = lastChunk.concat(chunk)
                        if chunk1.size == n then Loop.done(chunk1)
                        else
                            self.take.map: a =>
                                val chunk2 = chunk1.append(a)
                                if chunk2.size == n then Loop.done(chunk2)
                                else Loop.continue(chunk2)
                        end if

        /** Creates a fiber that puts an element into the channel.
          *
          * @param value
          *   The element to put
          * @return
          *   A fiber that completes when the element is put into the channel
          */
        def putFiber(value: A)(using Frame): Fiber[Closed, Unit] < IO = IO.Unsafe(self.putFiber(value).safe)

        /** Creates a fiber that takes an element from the channel.
          *
          * @return
          *   A fiber that completes with the taken element
          */
        def takeFiber(using Frame): Fiber[Closed, A] < IO = IO.Unsafe(self.takeFiber().safe)

        /** Drains all elements from the channel.
          *
          * @return
          *   A sequence containing all elements that were in the channel
          */
        def drain(using Frame): Chunk[A] < (Abort[Closed] & IO) = IO.Unsafe(Abort.get(self.drain()))

        /** Takes up to [[max]] elements from the channel.
          *
          * @return
          *   a sequence of up to [[max]] elements that were in the channel.
          */
        def drainUpTo(max: Int)(using Frame): Chunk[A] < (IO & Abort[Closed]) = IO.Unsafe(Abort.get(self.drainUpTo(max)))

        /** Closes the channel.
          *
          * @return
          *   A sequence of remaining elements
          */
        def close(using Frame): Maybe[Seq[A]] < IO = IO.Unsafe(self.close())

        /** Checks if the channel is closed.
          *
          * @return
          *   true if the channel is closed, false otherwise
          */
        def closed(using Frame): Boolean < IO = IO.Unsafe(self.closed())

        /** Checks if the channel is empty.
          *
          * @return
          *   true if the channel is empty, false otherwise
          */
        def empty(using Frame): Boolean < (Abort[Closed] & IO) = IO.Unsafe(Abort.get(self.empty()))

        /** Checks if the channel is full.
          *
          * @return
          *   true if the channel is full, false otherwise
          */
        def full(using Frame): Boolean < (Abort[Closed] & IO) = IO.Unsafe(Abort.get(self.full()))

        private def emitChunks(maxChunkSize: Int = Int.MaxValue)(
            using
            Tag[Emit[Chunk[A]]],
            Frame
        ): Ack < (Emit[Chunk[A]] & Abort[Closed] & Async) =
            if maxChunkSize <= 0 then Ack.Stop
            else if maxChunkSize == 1 then
                Loop(()): _ =>
                    Channel.take(self).map: v =>
                        Emit.andMap(Chunk(v)):
                            case Ack.Stop => Loop.done(Ack.Stop)
                            case _        => Loop.continue(())
            else
                val drainEffect =
                    if maxChunkSize == Int.MaxValue then Channel.drain(self)
                    else Channel.drainUpTo(self)(maxChunkSize - 1)
                Loop[Unit, Ack, Abort[Closed] & Async & Emit[Chunk[A]]](()): _ =>
                    Channel.take(self).map: a =>
                        drainEffect.map: chunk =>
                            Emit.andMap(Chunk(a).concat(chunk)):
                                case Ack.Stop => Loop.done(Ack.Stop)
                                case _        => Loop.continue(())

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
                    case Result.Fail(_)    => Ack.Stop
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
    def init[A](capacity: Int, access: Access = Access.MultiProducerMultiConsumer)(using PutType[A], Frame): Channel[A] < IO =
        IO.Unsafe(Unsafe.init(capacity, access))

    sealed abstract class PutType[A]:
        type Put
        def apply(value: A): Put
        def chunk(values: Chunk[A]): Put
        def unapplyChunk(value: Put): Option[Chunk[A]]
        def unapplyValue(value: Put): Option[A]

        object IsChunk:
            def unapply(value: Put): Option[Chunk[A]] = unapplyChunk(value)
        object IsValue:
            def unapply(value: Put): Option[A] = unapplyValue(value)
    end PutType

    sealed trait LowPriorityPutTypes:
        sealed case class Wr[A](inner: A)
        given nested[A]: PutType[A] with
            opaque type Put = Wr[A] | Chunk[A]
            def apply(value: A): Put         = Wr(value)
            def chunk(values: Chunk[A]): Put = values
            def unapplyChunk(value: Chunk[A] | Wr[A]): Option[Chunk[A]] = value match
                case Wr(_)           => None
                case chunk: Chunk[A] => Some(chunk)
            def unapplyValue(value: Put): Option[A] = value match
                case Wr(inner) => Some(inner)
                case _         => None
        end nested
    end LowPriorityPutTypes

    object PutType extends LowPriorityPutTypes:
        given notNested[A](using NotGiven[A <:< Seq[Any]], NotGiven[Seq[Any] <:< A]): PutType[A] with
            opaque type Put = A | Chunk[A]
            def apply(value: A): Put         = value
            def chunk(values: Chunk[A]): Put = values
            def unapplyChunk(value: Put): Option[Chunk[A]] = value match
                case result: Chunk[A] @unchecked => Some(result)
                case _                           => None
            def unapplyValue(value: Put): Option[A] = value match
                case result: Chunk[A] @unchecked => None
                case a: A @unchecked             => Some(a)
        end notNested
    end PutType

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    abstract class Unsafe[A]:
        def capacity: Int
        def size()(using AllowUnsafe): Result[Closed, Int]

        def offer(value: A)(using AllowUnsafe): Result[Closed, Boolean]
        def poll()(using AllowUnsafe): Result[Closed, Maybe[A]]

        def putFiber(value: A)(using AllowUnsafe): Fiber.Unsafe[Closed, Unit]
        def putBatchFiber(values: Chunk[A])(using AllowUnsafe): Fiber.Unsafe[Closed, Unit]
        def takeFiber()(using AllowUnsafe): Fiber.Unsafe[Closed, A]

        def drain()(using AllowUnsafe): Result[Closed, Chunk[A]]
        def drainUpTo(max: Int)(using AllowUnsafe): Result[Closed, Chunk[A]]
        def close()(using Frame, AllowUnsafe): Maybe[Seq[A]]

        def empty()(using AllowUnsafe): Result[Closed, Boolean]
        def full()(using AllowUnsafe): Result[Closed, Boolean]
        def closed()(using AllowUnsafe): Boolean

        def safe: Channel[A] = this
    end Unsafe

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        def init[A](
            _capacity: Int,
            access: Access = Access.MultiProducerMultiConsumer
        )(using putType: PutType[A], initFrame: Frame, allow: AllowUnsafe): Unsafe[A] =
            import putType.Put

            new Unsafe[A]:
                val queue = Queue.Unsafe.init[A](_capacity, access)
                val takes = new MpmcUnboundedXaddArrayQueue[Promise.Unsafe[Closed, A]](8)
                val puts  = new MpmcUnboundedXaddArrayQueue[(Put, Promise.Unsafe[Closed, Unit])](8)

                def capacity = _capacity

                def size()(using AllowUnsafe) = queue.size()

                def offer(value: A)(using AllowUnsafe) =
                    val result = queue.offer(value)
                    if result.contains(true) then flush()
                    result
                end offer

                def poll()(using AllowUnsafe) =
                    val result = queue.poll()
                    if result.exists(_.nonEmpty) then flush()
                    result
                end poll

                def drainUpTo(max: Int)(using AllowUnsafe) =
                    val result = queue.drainUpTo(max)
                    if result.exists(_.nonEmpty) then flush()
                    result
                end drainUpTo

                def putFiber(value: A)(using AllowUnsafe): Fiber.Unsafe[Closed, Unit] =
                    val promise = Promise.Unsafe.init[Closed, Unit]()
                    val tuple   = (putType(value), promise)
                    puts.add(tuple)
                    flush()
                    promise
                end putFiber

                def putBatchFiber(values: Chunk[A])(using AllowUnsafe): Fiber.Unsafe[Closed, Unit] =
                    val promise = Promise.Unsafe.init[Closed, Unit]()
                    val tuple   = (putType.chunk(values), promise)
                    puts.add(tuple)
                    flush()
                    promise
                end putBatchFiber

                def takeFiber()(using AllowUnsafe): Fiber.Unsafe[Closed, A] =
                    val promise = Promise.Unsafe.init[Closed, A]()
                    takes.add(promise)
                    flush()
                    promise
                end takeFiber

                def drain()(using AllowUnsafe) =
                    val result = queue.drain()
                    if result.exists(_.nonEmpty) then flush()
                    result
                end drain

                def close()(using frame: Frame, allow: AllowUnsafe) =
                    queue.close().map { backlog =>
                        flush()
                        backlog
                    }

                def empty()(using AllowUnsafe)  = queue.empty()
                def full()(using AllowUnsafe)   = queue.full()
                def closed()(using AllowUnsafe) = queue.closed()

                @tailrec private def flush(): Unit =
                    // This method ensures that all values are processed
                    // and handles interrupted fibers by discarding them.
                    val queueClosed = queue.closed()
                    val queueSize   = queue.size().getOrElse(0)
                    val takesEmpty  = takes.isEmpty()
                    val putsEmpty   = puts.isEmpty()

                    if queueClosed && (!takesEmpty || !putsEmpty) then
                        // Queue is closed, drain all takes and puts
                        val fail = queue.size() // Obtain the failed Result
                        takes.drain(_.completeDiscard(fail))
                        puts.drain(_._2.completeDiscard(fail.unit))
                        puts.drain(_._2.completeDiscard(fail.unit))
                        flush()
                    else if queueSize > 0 && !takesEmpty then
                        // Attempt to transfer a value from the queue to
                        // a waiting take operation.
                        Maybe(takes.poll()).foreach { promise =>
                            queue.poll() match
                                case Result.Success(Present(value)) =>
                                    if !promise.complete(Result.success(value)) && !queue.offer(value).contains(true) then
                                        // If completing the take fails and the queue
                                        // cannot accept the value back, enqueue a
                                        // placeholder put operation
                                        val placeholder = Promise.Unsafe.init[Nothing, Unit]()
                                        discard(puts.add((putType(value), placeholder)))
                                case _ =>
                                    // Queue became empty, enqueue the take again
                                    discard(takes.add(promise))
                        }
                        flush()
                    else if queueSize < capacity && !putsEmpty then
                        // Attempt to transfer a value from a waiting
                        // put operation to the queue.
                        Maybe(puts.poll()).foreach {
                            case (putType.IsChunk(chunk), promise) =>
                                var i = 0
                                boundary[Unit]:
                                    chunk.foreach: value =>
                                        if queue.offer(value).contains(false) then boundary.break(())
                                        else i += 1
                                if i >= chunk.length then
                                    discard(promise.complete(Result.unit))
                                else if i == (chunk.length - 1) then
                                    discard(puts.add(putType(chunk.last), promise))
                                else
                                    discard(puts.add(putType.chunk(chunk.dropLeft(i)), promise))
                                end if
                            case tuple @ (putType.IsValue(value), promise) =>
                                if queue.offer(value).contains(true) then
                                    // Queue accepted the value, complete the put
                                    discard(promise.complete(Result.unit))
                                else
                                    // Queue became full, enqueue the put again
                                    discard(puts.add(tuple))
                                end if
                        }
                        flush()
                    else if queueSize == 0 && !putsEmpty && !takesEmpty then
                        // Directly transfer a value from a producer to a
                        // consumer when the queue is empty.
                        Maybe(puts.poll()).foreach { putTuple =>
                            val (value, putPromise) = putTuple
                            Maybe(takes.poll()) match
                                case Present(takePromise) if takePromise.complete(Result.success(value)) =>
                                    // Value transfered to the pending take, complete put
                                    putPromise.completeDiscard(Result.unit)
                                case _ =>
                                    // No pending take available or the pending take is already
                                    // completed due to interruption. Enqueue the put again.
                                    discard(puts.add(putTuple))
                            end match
                        }
                        flush()
                    end if
                end flush
            end new
        end init
    end Unsafe
end Channel
