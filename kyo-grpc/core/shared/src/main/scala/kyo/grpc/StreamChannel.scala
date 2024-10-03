package kyo.grpc

import kyo.*
import kyo.Emit.Ack
import kyo.Emit.Ack.*

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
            case Stop        => Stop
            case Continue(0) => Emit.andMap(Chunk.empty)(emitLoop(channel, complete))
            // TODO: Can we take multiple? https://github.com/getkyo/kyo/issues/678
            case Continue(_) =>
                for
                    isDrained  <- channel.empty
                    isComplete <- complete.get
                    ack <-
                        if isDrained && isComplete then channel.close.map(_ => (Stop: Ack < Any))
                        else
                            for
                                result <- channel.take
                                a      <- Abort.get(result)
                            yield Emit.andMap(Chunk(a))(emitLoop(channel, complete))
                yield ack

end StreamChannel
