package kyo.grpc

import kyo.*
import kyo.kernel.Loop.Outcome

/** A channel that can be closed without draining.
  *
  * It will only abort with errors after all the values have been taken.
  *
  * @see
  *   https://github.com/getkyo/kyo/issues/721.
  */
class StreamChannel[A: Tag, E](channel: Channel[A], error: AtomicRef[Maybe[E]])(using emitTag: Tag[Emit[Chunk[A]]]):

    def put(value: A)(using Frame): Unit < (Abort[Closed] & Async) =
        channel.put(value)

    def error(e: E)(using Frame): Unit < (Abort[Closed] & Async) =
        for
            _ <- error.set(Maybe(e))
            _ <- closeProducer
        yield ()

    def take(using Frame): A < (Abort[Closed | E] & Async) =
        Abort.recover(errorOrClosed)(channel.take)

    private def errorOrClosed(closed: Closed)(using Frame): Nothing < (Abort[Closed | E] & IO) =
        error.getAndSet(Maybe.empty)
            .map(e => Abort.fail(e.getOrElse(closed)))

    def closeProducer(using Frame): Unit < Async =
        channel.closeAwaitEmpty.unit

    def closed(using Frame): Boolean < IO =
        for
            channelClosed <- channel.closed
            // TODO: It'd be better if this was a lazy operation.
            noErrors <- error.get.map(_.isEmpty)
        yield channelClosed && noErrors

    // This can only be called once and mutually exclusive with take.
    def stream(using Frame): Stream[A, Abort[E] & Async] =
        Stream(emitChunks())

    // TODO: This was copied from Channel because we don't have a way of closing the Channel without draining.
    //  See https://github.com/getkyo/kyo/issues/721.
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

object StreamChannel:

    // TODO: Set the capacity to something else that matches how we backpressure.
    final private[kyo] val Capacity = 42

    /**
     * This is only thread - safe if the channel is used in a single producer, single consumer pattern.
     */
    def init[A: Tag, E](using Frame, Tag[Emit[Chunk[A]]]): StreamChannel[A, E] < IO =
        for
            // TODO: Double check the access pattern here.
            channel        <- Channel.init[A](capacity = Capacity, access = Access.SingleProducerSingleConsumer)
            error          <- AtomicRef.init(Maybe.empty[E])
        yield new StreamChannel[A, E](channel, error)

end StreamChannel
