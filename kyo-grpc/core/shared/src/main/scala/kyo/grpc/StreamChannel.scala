package kyo.grpc

import kyo.*
import kyo.kernel.Loop.Outcome

/** A channel that can be closed without draining.
  *
  * It will only abort with errors after all the values have been taken.
  *
  * This is only thread-safe if the channel is used in a single producer, single consumer pattern.
  *
  * @see
  *   https://github.com/getkyo/kyo/issues/721.
  */
class StreamChannel[A: Tag, E](channel: Channel[A], error: AtomicRef[Maybe[E]], _producerClosed: AtomicBoolean)(using initFrame: Frame):

    def put(value: A)(using Frame): Unit < (Abort[Closed] & Async) =
        for
            _ <- checkProduce
            _ <- channel.put(value)
        yield ()

    def error(e: E)(using Frame): Unit < (Abort[Closed] & IO) =
        for
            _ <- checkProduce
            _ <- error.set(Maybe(e))
            _ <- closeProducer
        yield ()

    private def checkProduce: Unit < (Abort[Closed] & IO) =
        for
            closed <- _producerClosed.get
            _      <- Abort.when(closed)(closedError)
        yield ()

    private def closedError =
        Closed(this.getClass.getSimpleName, initFrame)

    def take(using Frame): A < (Abort[Closed | E] & Async) =
        for
            _     <- checkConsume
            value <- channel.take
            _     <- closeIfDone
        yield value

    private def checkConsume(using Frame): Unit < (Abort[Closed | E] & IO) =
        // Only abort with the error after all the values have been taken.
        for
            done <- channel.closed
            _ <-
                if done then
                    for
                        e <- error.getAndSet(Maybe.empty)
                        _ <- Abort.fail(e.getOrElse(closedError))
                    yield ()
                else Kyo.unit[Any]
        yield ()

    def closeProducer(using Frame): Unit < (Abort[Closed] & IO) =
        for
            _ <- _producerClosed.set(true)
            _ <- closeIfEmpty
        yield ()

    private def closeIfEmpty(using Frame) =
        for
            shouldClose <- channel.empty
            _           <- if shouldClose then channel.close else Kyo.unit
        yield ()

    private def closeIfDone(using Frame) =
        for
            shouldClose <- _producerClosed.get
            _           <- if shouldClose then closeIfEmpty else Kyo.unit
        yield ()

    def producerClosed(using Frame): Boolean < IO =
        _producerClosed.get

    def consumerClosed(using Frame): Boolean < IO =
        for
            channelClosed <- channel.closed
            // TODO: It'd be better if this was a lazy operation.
            noErrors <- error.get.map(_.isEmpty)
        yield channelClosed && noErrors

    def stream(using Frame): Stream[A, Abort[E] & Async] =
        Stream(emitChunks())

    // TODO: This was copied from Channel because we don't have a way of closing the Channel without draining.
    //  See https://github.com/getkyo/kyo/issues/721.
    private def emitChunks(maxChunkSize: Int = Int.MaxValue)(using Frame): Unit < (Emit[Chunk[A]] & Abort[E] & Async) =
        if maxChunkSize <= 0 then ()
        else
            Loop(()): _ =>
                Abort.recover[Closed](_ => Loop.done[Unit]):
                    for
                        _    <- checkConsume
                        head <- channel.take
                        tail <- channel.drainUpTo(maxChunkSize - 1)
                        _    <- closeIfDone
                        _    <- Emit.value(Chunk(head))
                        _    <- Emit.value(tail)
                    yield Loop.continue(())
    end emitChunks

end StreamChannel

object StreamChannel:

    // TODO: Set the capacity to something else that matches how we backpressure.
    final private[kyo] val Capacity = 42

    def init[A: Tag, E](using Frame): StreamChannel[A, E] < IO =
        for
            // TODO: Double check the access pattern here.
            channel        <- Channel.init[A](capacity = Capacity, access = Access.SingleProducerSingleConsumer)
            error          <- AtomicRef.init(Maybe.empty[E])
            producerClosed <- AtomicBoolean.init(false)
        yield new StreamChannel[A, E](channel, error, producerClosed)

end StreamChannel
