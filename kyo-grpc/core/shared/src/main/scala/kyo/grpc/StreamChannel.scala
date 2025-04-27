package kyo.grpc

import kyo.*
import kyo.kernel.Loop.Outcome
import kyo.kernel.internal.Kyo

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
            _ <- Log.debug(s"Putting value: $value")
            _ <- channel.put(value)
        yield ()

    def error(e: E)(using Frame): Unit < (Abort[Closed] & IO) =
        for
            _ <- checkProduce
            _ <- Log.debug(s"Setting error: $e")
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
            _     <- Log.debug("Taking value.")
            value <- Abort.recover(_ => errorOrClosedError)(channel.take)
            _     <- closeIfDone
        yield value

    private def errorOrClosedError(using Frame): Nothing < (Abort[Closed | E] & IO) =
        val pendingError =
            for
                e <- error.getAndSet(Maybe.empty)
                eOrClosed = e.getOrElse(closedError)
                _ <- Log.debug(s"Aborting with error: $eOrClosed")
            yield eOrClosed
        pendingError.map(Abort.fail)
    end errorOrClosedError

    def closeProducer(using Frame): Unit < IO =
        for
            _ <- Log.debug("Closing producer.")
            _ <- _producerClosed.set(true)
            _ <- closeIfEmpty
        yield ()

    // Be careful calling this. It is only thread safe if there will be no more puts/errors,
    // i.e. the producer calls it or the producer is closed.
    private def closeIfEmpty(using Frame): Unit < IO =
        for
            // Be careful here too. This might be called concurrently and so channel.empty might abort with Closed.
            shouldClose <- Abort.recover(_ => false)(channel.empty)
            _ <-
                if shouldClose then Log.debug("Closing channel.").andThen(channel.close)
                else Kyo.unit
        yield ()

    private def closeIfDone(using Frame): Unit < IO =
        for
            producerClosed <- _producerClosed.get
            _              <- if producerClosed then closeIfEmpty else Kyo.unit
        yield ()

    def producerClosed(using Frame): Boolean < IO =
        _producerClosed.get

    def consumerClosed(using Frame): Boolean < IO =
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
            Loop(()): _ =>
                Abort.recover[Closed](_ => Log.debug("Stream complete.").andThen(Loop.done[Unit])):
                    for
                        _    <- Log.debug("Taking value for stream.")
                        head <- Abort.recover(_ => errorOrClosedError)(channel.take)
                        // Be careful here. The producer might close the channel in between taking the last element and
                        // attempting to drain what is left. We have to emit the head before draining so that it isn't
                        // dropped.
                        _    <- Log.debug(s"Emitting value: $head")
                        _    <- Emit.value(Chunk(head))
                        tail <- Abort.recover(_ => errorOrClosedError)(channel.drainUpTo(maxChunkSize - 1))
                        _    <- closeIfDone
                        _ <-
                            if tail.nonEmpty then Log.debug(s"Emitting chunk: $tail").andThen(Emit.value(tail))
                            else Kyo.unit
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
