package kyo.grpc

import kyo.*
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
  * @param channel
  *   the underlying channel for value storage and transport
  * @param error
  *   atomic reference to track any error state
  * @param initFrame
  *   the initial frame where the channel was created, used for debugging and error reporting
  * @param emitTag
  *   implicit tag for emitting chunks of values
  */
private[kyo] class StreamChannel[A, E](channel: Channel[A], error: AtomicRef[Maybe[E]], initFrame: Frame)(using
    emitTag: Tag[Emit[Chunk[A]]]
):

    /** Puts an element into the channel, asynchronously blocking if necessary.
      *
      * @param value
      *   The element to put
      * @return
      *   a pending computation that completes with [[Unit]] or aborts with [[Closed]]
      */
    def put(value: A)(using Frame): Unit < (Abort[Closed] & Async) =
        channel.put(value)

    /** Signals an error and closes the producer side of the channel.
      *
      * Once an error is signaled, subsequent [[take]] operations will abort with the error after all remaining element have been consumed.
      *
      * @param e
      *   the error to signal
      * @return
      *   a pending computation that completes with [[Unit]] or aborts with [[Closed]]
      */
    def error(e: E)(using Frame): Unit < (Abort[Closed] & Async) =
        for
            open    <- channel.open
            _       <- Abort.when(!open)(Closed("StreamChannel", initFrame))
            success <- error.compareAndSet(Maybe.empty, Maybe(e))
            _       <- Abort.when(!success)(Closed("StreamChannel", initFrame))
            _       <- closeProducer
        yield ()

    /** Takes an element from the channel, asynchronously blocking if necessary.
      *
      * If the channel is empty and has been closed, this will abort with either:
      *   - The error that was previously signaled via [[error]]
      *   - [[Closed]] if no error was signaled
      *
      * @return
      *   The taken element or aborts with the error or `Closed`
      */
    def take(using Frame): A < (Abort[E | Closed] & Async) =
        Abort.recover(errorOrClosed)(channel.take)

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
        error.getAndSet(Maybe.empty)
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
        channel.closeAwaitEmptyFiber.unit

    /** Closes the channel immediately and discards any elements or error that have yet to be consumed.
      */
    def close(using Frame): Unit < Sync =
        channel.close.unit

    /** Checks if the channel is completely closed.
      *
      * A channel is considered completely closed when the underlying channel is closed, which happens when the producer was closed and the
      * consumer has consumed all elements.
      *
      * @return
      *   a pending computation that produces `true` if the channel is closed, `false` otherwise
      */
    def closed(using Frame): Boolean < Sync =
        channel.closed.map: isClosed =>
            if !isClosed then false else error.get.map(_.isEmpty)

    /** Creates a stream from this channel.
      *
      * This method can only be called once and is mutually exclusive with `take` operations. The stream will emit chunks of values as they
      * become available.
      *
      * @return
      *   a stream that emits values from the channel
      */
    def stream(using Frame): Stream[A, Abort[E] & Async] =
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
    private def emitChunks(maxChunkSize: Int = Int.MaxValue)(using Frame): Unit < (Emit[Chunk[A]] & Abort[E] & Async) =
        if maxChunkSize <= 0 then ()
        else
            Loop.foreach:
                Abort.recover[Closed](_ => Loop.done[Unit]):
                    for
                        // This will only abort when it is empty.
                        chunk <- Abort.recover(errorOrClosed)(channel.drainUpTo(maxChunkSize))
                        _ <-
                            if chunk.nonEmpty then
                                Emit.value(chunk)
                            else
                                for
                                    // This will only abort when it is empty.
                                    head <- Abort.recover(errorOrClosed)(channel.take)
                                    _    <- Emit.value(Chunk(head))
                                yield ()
                    yield Loop.continue(())
    end emitChunks

end StreamChannel

/** Companion object for [[StreamChannel]] providing factory methods.
  */
private[kyo] object StreamChannel:

    // TODO: Set the capacity to something else that matches how we backpressure.
    final private[kyo] val Capacity = 42

    // This is only thread-safe if the channel is used in a single producer, single consumer pattern.
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
    def init[A, E](using Frame, Tag[Emit[Chunk[A]]]): StreamChannel[A, E] < Sync =
        for
            channel <- Channel.init[A](capacity = Capacity, access = Access.SingleProducerSingleConsumer)
            error   <- AtomicRef.init(Maybe.empty[E])
        yield new StreamChannel[A, E](channel, error, summon)

end StreamChannel
