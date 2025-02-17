package kyo.grpc

import kyo.*
import kyo.kernel.Loop.Outcome

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
            yield emitChunks(ch, comp)
        )

    // TODO: This was copied from Channel because we don't have a way of closing the Channel without draining.
    // See https://github.com/getkyo/kyo/issues/721.
    private def emitChunks[A: Tag, E](channel: Channel[Result[E, A]], complete: AtomicBoolean, maxChunkSize: Int = Int.MaxValue)(
        using
        Tag[Emit[Chunk[A]]],
        Frame
    ): Unit < (Emit[Chunk[A]] & Abort[E] & Async) =
        if maxChunkSize <= 0 then ()
        else
            Loop(()): _ =>
                Abort.recover[Closed](_ => Loop.done[Unit]):
                    for
                        head <- channel.take
                        tail <- channel.drainUpTo(maxChunkSize - 1)
                        // TODO: There ought to be a better way to do this.
                        // See https://github.com/getkyo/kyo/issues/721.
                        empty        <- channel.empty
                        closeIfEmpty <- complete.get
                        _            <- if empty && closeIfEmpty then channel.close else Kyo.pure(Maybe.empty)
                        // TODO: Can we avoid the extra Chunk allocation here?
                        results = Chunk(head).concat(tail)
                        // TODO: Should be easier to fold Result[E, A] to A < Abort[E]
                        chunk <- Kyo.collect(results.map(_.foldFailureOrThrow(Abort.fail)(identity)))
                    yield Emit.valueWith(chunk)(Loop.continue(()))

end StreamChannel
