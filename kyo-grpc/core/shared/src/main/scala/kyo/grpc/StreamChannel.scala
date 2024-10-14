package kyo.grpc

import kyo.*
import kyo.Emit.Ack
import kyo.Emit.Ack.*
import kyo.Result.*

object StreamChannel:

    // TODO: Set the capacity to something else that matches how we backpressure.
    // TODO: Double check the access pattern here.
    def init[A, E](using Frame): Channel[Result[E, A]] < IO =
        Channel.init[Result[E, A]](capacity = 42, access = Access.SingleProducerSingleConsumer)

    def stream[A: Tag, E, S](channel: Channel[Result[E, A]] < S, complete: AtomicBoolean < S)(using
        Frame
    ): Stream[A, Abort[E] & Async & S] =
        Stream(
            for
                ch   <- channel
                comp <- complete
            yield emit(ch, comp)
        )

    def emit[A: Tag, E](channel: Channel[Result[E, A]], complete: AtomicBoolean)(using Frame): Ack < (Emit[Chunk[A]] & Abort[E] & Async) =
        Emit.andMap(Chunk.empty)(emitLoop(channel, complete))

    // TODO: Remember to convert Panic to StatusException using StreamNotifier.throwableToStatusException.
    // TODO: Use Loop here instead?
    private def emitLoop[A: Tag, E](channel: Channel[Result[E, A]], complete: AtomicBoolean)(ack: Ack)(using
        Frame
    ): Ack < (Emit[Chunk[A]] & Abort[E] & Async) =
        ack match
            case Stop => Stop
            // TODO: Can we take multiple? https://github.com/getkyo/kyo/issues/678
            case Continue(n) =>
                for
                    isDrained  <- channel.empty
                    isComplete <- complete.get
                    ack <-
                        if isDrained && isComplete then channel.close.map(_ => Kyo.pure[Ack](Stop))
                        else
                            // TODO: Unnest this.
                            for
                                result <- takeMaybe(channel)
                                maybeA <- Abort.get(result)
                            yield maybeA.fold(Kyo.pure[Ack](Stop))(a => Emit.andMap(Chunk(a))(emitLoop(channel, complete)))
                yield ack
                end for

    private def takeMaybe[A: Tag, E](channel: Channel[Result[E, A]])(using Frame): Result[E, Maybe[A]] < Async =
        // TODO: Is there a better way to do this?
        Abort.run[Closed](channel.take).map { closedResult =>
            closedResult.fold(_ => Success(Maybe.empty))(_.map(Maybe(_)))
        }

end StreamChannel
