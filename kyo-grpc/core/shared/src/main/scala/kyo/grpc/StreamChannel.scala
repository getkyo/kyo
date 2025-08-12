package kyo.grpc

import kyo.*
import kyo.Clock.Deadline
import kyo.kernel.Loop.Outcome

/** A channel that is consumed as a stream.
  *
  * The producer side of the channel can put elements into the channel and then close or signal an error. If an error is signaled, the
  * producer side of the channel is closed. Once the consumer side has consumed all elements, it will then abort with the error if one was
  * signaled, or with [[Closed]] if no error was signaled.
  *
  * @tparam A
  *   the type of values that can be put into and taken from the channel
  * @tparam E
  *   the type of errors that can be signaled through the channel
  */
private[kyo] opaque type StreamChannel[A, E] = StreamChannel.Unsafe[A, E]

/** Companion object for [[StreamChannel]] providing factory methods and extensions.
  */
private[kyo] object StreamChannel:

    extension [A, E](self: StreamChannel[A, E])

        /** Puts an element into the channel, asynchronously blocking if necessary.
          *
          * @param value
          *   The element to put
          * @return
          *   a pending computation that completes with [[Unit]] or aborts with [[Closed]]
          */
        def put(value: A)(using Frame): Unit < (Abort[Closed] & Async) =
            self.channel.safe.put(value)

        /** Signals an error and closes the producer side of the channel.
          *
          * Once an error is signaled, subsequent [[take]] operations will abort with the error after all remaining element have been
          * consumed.
          *
          * @param e
          *   the error to signal
          * @return
          *   a pending computation that completes with [[Unit]] or aborts with [[Closed]]
          */
        def fail(e: E)(using Frame): Unit < (Abort[Closed] & Async) =
            Sync.Unsafe:
                Abort.get(self.fail(e)).map(_.safe.get.unit)

        /** Takes an element from the channel, asynchronously blocking if necessary.
          *
          * If the channel is empty and has been closed, this will abort with either:
          *   - The error that was previously signaled via [[fail]]
          *   - [[Closed]] if no error was signaled
          *
          * @return
          *   The taken element or aborts with the error or `Closed`
          */
        def take(using Frame): A < (Abort[E | Closed] & Async) =
            Abort.recover(errorOrClosed)(self.channel.safe.take)

        /** Handles the case when the channel is closed during a [[take]] operation.
          *
          * Checks if an error was previously signaled and aborts with that error, otherwise aborts with the given [[Closed]] signal.
          *
          * @param closed
          *   the closed signal from the underlying channel
          * @return
          *   a pending computation that always aborts with either the error or `Closed`
          */
        private def errorOrClosed(closed: Closed)(using Frame): Nothing < (Abort[E | Closed] & Sync) =
            self.failure.safe.getAndSet(Maybe.empty)
                .map(e => Abort.fail(e.getOrElse(closed)))

        /** Closes the producer side of the channel.
          *
          * This method closes the channel without draining the remaining elements or waiting for it to be emptied.
          *
          * After calling this method, no more values can be put into the channel.
          *
          * @return
          *   a pending computation that completes with [[Unit]] when the channel is closed
          */
        def closeProducer(using Frame): Unit < Sync =
            Sync.Unsafe:
                discard(self.closeProducerFiber())

        /** Closes the channel immediately and discards any elements or error that have yet to be consumed.
          */
        def close(using Frame): Unit < Sync =
            Sync.Unsafe:
                self.close()

        /** Checks if the channel is completely closed.
          *
          * A channel is considered completely closed when the underlying channel is closed, which happens when the producer was closed and
          * the consumer has consumed all elements.
          *
          * @return
          *   a pending computation that produces `true` if the channel is closed, `false` otherwise
          */
        def closed(using Frame): Boolean < Sync =
            Sync.Unsafe:
                self.closed()

        /** Creates a stream from this channel.
          *
          * This method can only be called once and is mutually exclusive with `take` operations. The stream will emit chunks of values as
          * they become available.
          *
          * @return
          *   a stream that emits values from the channel
          */
        def stream(using Frame, Tag[Emit[Chunk[A]]]): Stream[A, Abort[E] & Async] =
            Stream(emitChunks())

        /** Emits chunks of values from the channel.
          *
          * This is an internal method used by [[stream]] to emit chunks of values. It handles draining the channel and properly propagating
          * errors.
          *
          * @param maxChunkSize
          *   the maximum size of chunks to emit (default: `Int.MaxValue`)
          * @return
          *   a pending computation that emits chunks of values
          */
        private def emitChunks(maxChunkSize: Int = Int.MaxValue)(using
            Frame,
            Tag[Emit[Chunk[A]]]
        ): Unit < (Emit[Chunk[A]] & Abort[E] & Async) =
            if maxChunkSize <= 0 then ()
            else
                Loop.foreach:
                    Abort.recover[Closed](_ => Loop.done[Unit]):
                        for
                            // This will only abort when it is empty.
                            chunk <- Abort.recover(errorOrClosed)(self.channel.safe.drainUpTo(maxChunkSize))
                            _ <-
                                if chunk.nonEmpty then
                                    Emit.value(chunk)
                                else
                                    for
                                        // This will only abort when it is empty.
                                        head <- Abort.recover(errorOrClosed)(self.channel.safe.take)
                                        _    <- Emit.value(Chunk(head))
                                    yield ()
                        yield Loop.continue(())
        end emitChunks

        /** Converts the `StreamChannel` to its unsafe representation.
          *
          * This is a low-level operation that should only be used when necessary, such as in integrations or performance-sensitive code.
          *
          * @return
          *   the unsafe representation of the channel
          */
        def unsafe: Unsafe[A, E] = self
    end extension

    // TODO: Set the capacity to something else that matches how we backpressure.
    final private val Capacity = 42

    /** Creates a new `StreamChannel` instance.
      *
      * This factory method initializes a new channel with the specified capacity and creates the necessary error tracking. The channel is
      * configured for single producer, single consumer access pattern.
      *
      * @tparam A
      *   the type of values that will flow through the channel
      * @tparam E
      *   the type of errors that can be signaled
      * @return
      *   a pending computation that produces a new `StreamChannel` instance
      */
    def init[A, E](using Frame): StreamChannel[A, E] < (Sync & Scope) =
        for
            channel <- Channel.init[A](capacity = Capacity, access = Access.SingleProducerSingleConsumer)
            error   <- AtomicRef.init(Maybe.empty[E])
        yield Unsafe(channel.unsafe, error.unsafe).safe

    /** Creates a new `StreamChannel` instance without guaranteeing eventual cleanup.
      *
      * This factory method initializes a new channel with the specified capacity and creates the necessary error tracking. The channel is
      * configured for single producer, single consumer access pattern.
      *
      * @param capacity
      *   The capacity of the channel. Note that this will be rounded up to the next power of two.
      * @param access
      *   The access mode for the channel (default is [[Access.SingleProducerSingleConsumer]]).
      * @tparam A
      *   the type of values that will flow through the channel
      * @tparam E
      *   the type of errors that can be signaled
      * @return
      *   a pending computation that produces a new `StreamChannel` instance
      */
    def initUnscoped[A, E](capacity: Int, access: Access = Access.SingleProducerSingleConsumer)(using Frame): StreamChannel[A, E] < Sync =
        for
            channel <- Channel.initUnscoped[A](capacity, access)
            error   <- AtomicRef.init(Maybe.empty[E])
        yield Unsafe(channel.unsafe, error.unsafe).safe

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    final class Unsafe[A, E](val channel: Channel.Unsafe[A], val failure: AtomicRef.Unsafe[Maybe[E]])(using initFrame: Frame):

        // TODO: Fix all the docs in here.

        def putFiber(value: A)(using Frame, AllowUnsafe): Fiber.Unsafe[Unit, Abort[Closed]] =
            channel.offer(value).foldOrThrow(
                {
                    case true  => Fiber.unit.unsafe
                    case false => channel.putFiber(value)
                },
                Fiber.fail(_).unsafe
            )

        /** Signals an error and closes the producer side of the channel.
          *
          * Once an error is signaled, subsequent [[take]] operations will abort with the error after all remaining element have been
          * consumed.
          *
          * @param e
          *   the error to signal
          * @return
          *   a pending computation that completes with [[Unit]] or aborts with [[Closed]]
          */
        def fail(e: E)(using Frame, AllowUnsafe): Result[Closed, Fiber.Unsafe[Boolean, Any]] =
            if !channel.open() then
                Result.fail(Closed("StreamChannel", initFrame))
            else if !failure.compareAndSet(Maybe.empty, Maybe(e)) then
                Result.fail(Closed("StreamChannel", initFrame))
            else
                Result.succeed(closeProducerFiber())
            end if
        end fail

        /** Closes the producer side of the channel.
          *
          * This method closes the channel without draining the remaining elements or waiting for it to be emptied.
          *
          * After calling this method, no more values can be put into the channel.
          *
          * @return
          *   a pending computation that completes with [[Unit]] when the channel is closed
          */
        def closeProducerFiber()(using Frame, AllowUnsafe): Fiber.Unsafe[Boolean, Any] =
            channel.closeAwaitEmpty()

        /** Closes the channel immediately and discards any elements or error that have yet to be consumed.
          */
        def close()(using Frame, AllowUnsafe): Unit =
            discard(channel.close())

        /** Checks if the channel is completely closed.
          *
          * A channel is considered completely closed when the underlying channel is closed, which happens when the producer was closed and
          * the consumer has consumed all elements.
          *
          * @return
          *   a pending computation that produces `true` if the channel is closed, `false` otherwise
          */
        def closed()(using Frame, AllowUnsafe): Boolean =
            channel.closed() && failure.get().isEmpty

        def safe: StreamChannel[A, E] = this
    end Unsafe

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        def init[A, E](
            capacity: Int,
            access: Access = Access.SingleProducerSingleConsumer
        )(using Frame, AllowUnsafe): Unsafe[A, E] =
            val channel = Channel.Unsafe.init[A](capacity, access)
            val error   = AtomicRef.Unsafe.init(Maybe.empty[E])
            Unsafe(channel, error)
        end init
    end Unsafe

end StreamChannel
