package kyo.grpc

import kyo.*
import kyo.kernel.Loop.Outcome

/** A channel that can be closed without draining.
  *
  * This is only thread-safe if the channel is used in a single producer, single consumer pattern.
  *
  * @see
  *   https://github.com/getkyo/kyo/issues/721.
  */
class StreamChannel[A: Tag, E](private val channel: Channel[Result[E, A]], private val _completed: AtomicBoolean)(using initFrame: Frame):

    def put(value: A)(using Frame): Unit < (Abort[Closed] & Async) =
        println("put")
        putResult(Result.succeed(value))

    private def putResult(value: Result[E, A])(using Frame): Unit < (Abort[Closed] & Async) =
        for
            completed <- _completed.get
            _ <- Abort.when(completed)(Closed("StreamChannel", initFrame))
            _ <- channel.put(value)
        yield ()

    def fail(e: E)(using Frame): Unit < (Abort[Closed] & Async) =
        for
            _ <- error(e)
            _ <- complete
        yield ()

    def error(e: E)(using Frame): Unit < (Abort[Closed] & Async) =
        putResult(Result.fail(e))

    def take(using Frame): Result[E, A] < (Abort[Closed] & Async) =
        for
            value <- channel.take
            _     <- closeIfCompleteAndEmpty
        yield value

    def complete(using Frame): Unit < (Abort[Closed] & IO) =
        for
            _ <- _completed.set(true)
            _ <- closeIfEmpty
        yield ()

    private def closeIfEmpty(using Frame) =
        for
            shouldClose <- Abort.recover[Closed](_ => false)(channel.empty)
            _           <- if shouldClose then channel.close else Kyo.unit
        yield ()

    private def closeIfCompleteAndEmpty(using Frame) =
        for
            shouldClose <- _completed.get
            _           <- if shouldClose then closeIfEmpty else Kyo.unit
        yield ()

    def completed(using Frame): Boolean < IO =
        _completed.get

    def closed(using Frame): Boolean < IO =
        channel.closed

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
                        head <- channel.take
                        tail <- channel.drainUpTo(maxChunkSize - 1)
                        _ <- closeIfCompleteAndEmpty
                        // TODO: Can we avoid the extra Chunk allocation here?
                        results = Chunk(head).concat(tail)
                        // TODO: Should be easier to fold Result[E, A] to A < Abort[E]
                        successes = results.map[A < Abort[E]](_.foldOrThrow(identity, Abort.fail))
                        chunk <- Kyo.collectAll(successes)
                    yield Emit.valueWith(chunk)(Loop.continue(()))
    end emitChunks

end StreamChannel

object StreamChannel:

    // TODO: Set the capacity to something else that matches how we backpressure.
    final private[kyo] val Capacity = 42

    def init[A: Tag, E](using Frame): StreamChannel[A, E] < IO =
        for
            channel <- Channel.init[Result[E, A]](capacity = Capacity, access = Access.SingleProducerSingleConsumer)
            // TODO: Double check the access pattern here.
            complete <- AtomicBoolean.init(false)
        yield new StreamChannel[A, E](channel, complete)

end StreamChannel
