package kyo.interop.reactivestreams

import StreamSubscription.*
import kyo.*
import kyo.interop.reactivestreams.*
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

    private val requestChannel = Channel.Unsafe.init[Long](Int.MaxValue, Access.SingleProducerSingleConsumer)

    override def request(n: Long): Unit =
        if n <= 0 then subscriber.onError(new IllegalArgumentException("non-positive subscription request"))
        discard(requestChannel.offer(n))
    end request

    override def cancel(): Unit =
        given Frame = Frame.internal
        discard(requestChannel.close())
    end cancel

    private[reactivestreams] inline def subscribe: Unit < IO = IO(subscriber.onSubscribe(this))

    private[reactivestreams] def poll: StreamFinishState < (Async & Poll[Chunk[V]]) =
        def loopPoll(requesting: Long): (Chunk[V] | StreamFinishState) < (IO & Poll[Chunk[V]]) =
            Loop(requesting) { requesting =>
                Poll.one[Chunk[V]](Ack.Continue()).map {
                    case Present(values) =>
                        if values.size <= requesting then
                            IO(values.foreach(subscriber.onNext(_)))
                                .andThen(Loop.continue(requesting - values.size))
                        else
                            IO(values.take(requesting.intValue).foreach(subscriber.onNext(_)))
                                .andThen(Loop.done[Long, Chunk[V] | StreamFinishState](
                                    values.drop(requesting.intValue)
                                ))
                    case Absent =>
                        IO(Loop.done[Long, Chunk[V] | StreamFinishState](StreamFinishState.StreamComplete))
                }
            }

        Loop[Chunk[V] | StreamFinishState, StreamFinishState, Async & Poll[Chunk[V]]](Chunk.empty[V]) {
            case leftOver: Chunk[V] =>
                for
                    requestingResult <- (requestChannel.poll(): @unchecked) match
                        case Result.Success(Present(requesting)) => IO(Result.Success(requesting))
                        case Result.Success(Absent)              => requestChannel.takeFiber().safe.getResult
                        case error: Result.Error[Closed]         => IO(error)
                    outcome <- requestingResult match
                        case Result.Success(requesting) =>
                            if requesting < leftOver.size then
                                IO(leftOver.take(requesting.intValue).foreach(subscriber.onNext(_)))
                                    .andThen(Loop.continue[StreamFinishState | Chunk[V], StreamFinishState, Async & Poll[Chunk[V]]](
                                        leftOver.drop(requesting.intValue)
                                    ))
                            else
                                IO(leftOver.foreach(subscriber.onNext(_)))
                                    .andThen(loopPoll(requesting - leftOver.size))
                                    .map(Loop.continue[StreamFinishState | Chunk[V], StreamFinishState, Async & Poll[Chunk[V]]](_))
                        case Result.Fail(_) =>
                            IO(Loop.continue[StreamFinishState | Chunk[V], StreamFinishState, Async & Poll[Chunk[V]]](
                                StreamFinishState.StreamCanceled
                            ))
                        case Result.Panic(exception) => IO(throw exception).andThen(Loop.continue[
                                StreamFinishState | Chunk[V],
                                StreamFinishState,
                                Async & Poll[Chunk[V]]
                            ](StreamFinishState.StreamCanceled))
                yield outcome
            case state: StreamFinishState => Loop.done(state)
        }
    end poll

    private[reactivestreams] def consume(
        using
        emitTag: Tag[Emit[Chunk[V]]],
        pollTag: Tag[Poll[Chunk[V]]],
        frame: Frame,
        safepoint: Safepoint
    ): Fiber[Nothing, StreamFinishState] < (IO & Ctx) =
        boundary { (trace, context) =>
            val fiber = Fiber.fromTask(IOTask(Poll.run(stream.emit)(poll).map(_._2), safepoint.copyTrace(trace), context))
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
