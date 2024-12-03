package kyo.interop.reactivestreams

import StreamSubscription.*
import kyo.*
import kyo.Emit.Ack
import kyo.interop.reactivestreams.StreamSubscription.StreamFinishState
import kyo.kernel.ArrowEffect
import kyo.kernel.Boundary
import kyo.kernel.Safepoint
import kyo.scheduler.IOPromise
import kyo.scheduler.IOTask
import org.reactivestreams.*

final class StreamSubscription[V, Ctx] private[reactivestreams] (
    private val stream: Stream[V, Ctx],
    subscriber: Subscriber[? >: V]
)(
    using
    allowance: AllowUnsafe,
    boundary: Boundary[Ctx, IO],
    tag: Tag[V],
    frame: Frame
) extends Subscription:

    private enum DownstreamState derives CanEqual:
        case Uninitialized extends DownstreamState
        case Requesting(
            requested: Long,
            maybePromise: Maybe[(Long, Fiber.Promise.Unsafe[Unit, Long])]
        )             extends DownstreamState
        case Finished extends DownstreamState
    end DownstreamState

    private val state = AtomicRef.Unsafe.init(DownstreamState.Uninitialized)

    private def offer(n: Long)(using Frame): Result[Unit, Long] < Async =
        var sideEffect: () => Result[Unit, Long] < Async = () => null.asInstanceOf
        state.update {
            case DownstreamState.Requesting(0L, Absent) =>
                // No one requested, accumulate offerring and wait
                val promise = Fiber.Promise.Unsafe.init[Unit, Long]()
                sideEffect = () => promise.safe.getResult
                DownstreamState.Requesting(0L, Present(n -> promise))
            case DownstreamState.Requesting(requested, Absent) =>
                // Someone requested, we offer right away
                val accepted      = Math.min(requested, n)
                val nextRequested = requested - accepted
                sideEffect = () => IO(Result.success(accepted))
                DownstreamState.Requesting(nextRequested, Absent)
            case DownstreamState.Finished =>
                // Downstream cancelled
                sideEffect = () => IO(Result.fail(()))
                DownstreamState.Finished
            case other =>
                sideEffect = () => IO(Result.success(0L))
                other
        }
        sideEffect()
    end offer

    override def request(n: Long): Unit =
        if n <= 0 then subscriber.onError(new IllegalArgumentException("non-positive subscription request"))
        var sideEffect: () => Unit = () => ()
        state.update {
            case DownstreamState.Requesting(0L, Present(offered -> promise)) =>
                val accepted      = Math.min(offered, n)
                val nextRequested = n - accepted
                val nextOfferred  = offered - accepted
                sideEffect = () => promise.completeDiscard(Result.success(accepted))
                DownstreamState.Requesting(nextRequested, Absent)
            case DownstreamState.Requesting(requested, Absent) =>
                val nextRequested = Math.min(Long.MaxValue - requested, n) + requested
                sideEffect = () => ()
                DownstreamState.Requesting(nextRequested, Absent)
            case other =>
                sideEffect = () => ()
                other
        }
        sideEffect()
    end request

    override def cancel(): Unit =
        given Frame                = Frame.internal
        var sideEffect: () => Unit = () => ()
        state.update {
            case DownstreamState.Requesting(_, Present(_ -> promise)) =>
                sideEffect = () => promise.completeDiscard(Result.fail(()))
                DownstreamState.Finished
            case other =>
                sideEffect = () => ()
                DownstreamState.Finished
        }
        sideEffect()
    end cancel

    def subscribe: Unit < (IO & Ctx) =
        var sideEffect: () => Unit < (IO & Ctx) = () => IO.unit
        state.update {
            case DownstreamState.Uninitialized =>
                sideEffect = () =>
                    IO {
                        subscriber.onSubscribe(this)
                    }
                DownstreamState.Requesting(0L, Absent)
            case other =>
                sideEffect = () => IO.unit
                other
        }
        sideEffect()
    end subscribe

    private[reactivestreams] def consume(
        using
        tag: Tag[Emit[Chunk[V]]],
        frame: Frame,
        safepoint: Safepoint
    ): Fiber[Nothing, StreamFinishState] < (IO & Ctx) =
        def consumeStream: StreamFinishState < (Abort[Nothing] & Async & Ctx) =
            ArrowEffect.handleState(tag, 0: (Long | StreamFinishState), stream.emit.unit)(
                handle =
                    [C] =>
                        (input, state, cont) =>
                            // Handle the input chunk
                            if input.nonEmpty then
                                // Input chunk contains values that we need to feed the subscriber
                                Loop[Chunk[V], Long | StreamFinishState, (Ack, Long | StreamFinishState), Abort[Nothing] & Async & Ctx](
                                    input,
                                    state
                                ) {
                                    (curChunk, curState) =>
                                        curState match
                                            case leftOver: Long =>
                                                if curChunk.isEmpty then
                                                    // We finish the current chunk, go next
                                                    Loop.done[Chunk[V], Long | StreamFinishState, (Ack, Long | StreamFinishState)](
                                                        Ack.Continue() -> leftOver
                                                    )
                                                else
                                                    if leftOver > 0 then
                                                        // Some requests left from last loop, feed them
                                                        val taken        = Math.min(curChunk.size, leftOver)
                                                        val nextLeftOver = leftOver - taken
                                                        curChunk.take(taken.toInt).foreach { value =>
                                                            subscriber.onNext(value)
                                                        }
                                                        // Loop the rest
                                                        Loop.continue(curChunk.drop(taken.toInt), nextLeftOver)
                                                    else
                                                        for
                                                            // We signal that we can `offer` "curChunk.size" elements
                                                            // then we wait until subscriber picks up that offer
                                                            acceptedResult <- offer(curChunk.size)
                                                            outcome = acceptedResult match
                                                                // Subscriber requests "accepted" elements
                                                                case Result.Success(accepted) =>
                                                                    val taken        = Math.min(curChunk.size, accepted)
                                                                    val nextLeftOver = accepted - taken
                                                                    curChunk.take(taken.toInt).foreach { value =>
                                                                        subscriber.onNext(value)
                                                                    }
                                                                    // Loop the rest
                                                                    IO(Loop.continue(curChunk.drop(taken.toInt), nextLeftOver))
                                                                case Result.Error(e) =>
                                                                    e match
                                                                        case t: Throwable =>
                                                                            Abort.panic(t)
                                                                                .andThen(Loop.done[
                                                                                    Chunk[V],
                                                                                    Long | StreamFinishState,
                                                                                    (Ack, Long | StreamFinishState)
                                                                                ](
                                                                                    Ack.Stop -> StreamFinishState.StreamCanceled
                                                                                ))
                                                                        case _: Unit =>
                                                                            IO(Loop.done[
                                                                                Chunk[V],
                                                                                Long | StreamFinishState,
                                                                                (Ack, Long | StreamFinishState)
                                                                            ](
                                                                                Ack.Stop -> StreamFinishState.StreamCanceled
                                                                            ))
                                                        yield outcome
                                                        end for
                                                end if
                                            case finishState: StreamFinishState =>
                                                Loop.done[Chunk[V], Long | StreamFinishState, (Ack, Long | StreamFinishState)](
                                                    Ack.Stop -> finishState
                                                )
                                }.map { case (ack, state) =>
                                    state -> cont(ack)
                                }
                            else
                                // The input chunk is empty, we go next
                                state -> cont(Ack.Continue())
                ,
                done = (state, _) =>
                    state match
                        case _: Long                        => StreamFinishState.StreamComplete
                        case finishState: StreamFinishState => finishState
            )

        boundary { (trace, context) =>
            val fiber = Fiber.fromTask(IOTask(consumeStream, safepoint.copyTrace(trace), context))
            fiber.unsafe.onComplete {
                case Result.Success(StreamFinishState.StreamComplete) => subscriber.onComplete()
                case Result.Panic(e)                                  => subscriber.onError(e)
                case _                                                => ()
            }
            fiber
        }
    end consume

end StreamSubscription

object StreamSubscription:

    private[reactivestreams] enum StreamFinishState derives CanEqual:
        case StreamComplete, StreamCanceled
    end StreamFinishState

    def subscribe[V, Ctx](
        stream: Stream[V, Ctx],
        subscriber: Subscriber[? >: V]
    )(
        using
        boundary: Boundary[Ctx, IO],
        frame: Frame,
        tag: Tag[V]
    ): StreamSubscription[V, Ctx] < (IO & Ctx & Resource) =
        for
            subscription <- IO.Unsafe(new StreamSubscription[V, Ctx](stream, subscriber))
            _            <- subscription.subscribe
            _            <- Resource.acquireRelease(subscription.consume)(_.interrupt.unit)
        yield subscription

    object Unsafe:
        def subscribe[V, Ctx](
            stream: Stream[V, Ctx],
            subscriber: Subscriber[? >: V]
        )(
            subscribeCallback: (Fiber[Nothing, StreamFinishState] < (IO & Ctx)) => Unit
        )(
            using
            allowance: AllowUnsafe,
            boundary: Boundary[Ctx, IO],
            frame: Frame,
            tag: Tag[V]
        ): StreamSubscription[V, Ctx] =
            val subscription = new StreamSubscription[V, Ctx](stream, subscriber)
            subscribeCallback(subscription.subscribe.andThen(subscription.consume))
            subscription
        end subscribe
    end Unsafe

end StreamSubscription
