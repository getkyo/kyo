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

final private[kyo] class StreamSubscription[V, Ctx](
    private val stream: Stream[V, Ctx],
    subscriber: Subscriber[? >: V]
)(
    using
    AllowUnsafe,
    Frame
) extends Subscription:

    private val requestChannel = Channel.Unsafe.init[Long](Int.MaxValue)

    override def request(n: Long): Unit =
        if n <= 0 then subscriber.onError(new IllegalArgumentException("non-positive subscription request"))
        discard(requestChannel.offer(n))
    end request

    override def cancel(): Unit =
        given Frame = Frame.internal
        discard(requestChannel.close())
    end cancel

    private[interop] inline def subscribe(using Frame): Unit < IO = IO(subscriber.onSubscribe(this))

    private[interop] def poll(using Tag[Poll[Chunk[V]]], Frame): StreamFinishState < (Async & Poll[Chunk[V]]) =
        inline def loopPoll(requesting: Long): (Chunk[V] | StreamFinishState) < (IO & Poll[Chunk[V]]) =
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

    private[interop] def consume(
        using
        Tag[Emit[Chunk[V]]],
        Tag[Poll[Chunk[V]]],
        Frame,
        Boundary[Ctx, IO & Abort[Nothing]]
    ): Fiber[Nothing, StreamFinishState] < (IO & Ctx) =
        Async
            ._run[Nothing, StreamFinishState, Ctx](Poll.run(stream.emit)(poll).map(_._2))
            .map { fiber =>
                fiber.onComplete {
                    case Result.Success(StreamFinishState.StreamComplete) => IO(subscriber.onComplete())
                    case Result.Panic(e)                                  => IO(subscriber.onError(e))
                    case _                                                => IO.unit
                }.andThen(fiber)
            }
    end consume

end StreamSubscription

object StreamSubscription:

    private[interop] enum StreamFinishState derives CanEqual:
        case StreamComplete, StreamCanceled
    end StreamFinishState

    inline def subscribe[V, Ctx](
        stream: Stream[V, Ctx],
        subscriber: Subscriber[? >: V]
    )(
        using
        Frame,
        Tag[Emit[Chunk[V]]],
        Tag[Poll[Chunk[V]]]
    ): StreamSubscription[V, Ctx] < (IO & Ctx & Resource) =
        _subscribe(stream, subscriber)

    private[kyo] inline def _subscribe[V, Ctx](
        stream: Stream[V, Ctx],
        subscriber: Subscriber[? >: V]
    )(
        using
        Frame,
        Boundary[Ctx, IO & Abort[Nothing]],
        Tag[Emit[Chunk[V]]],
        Tag[Poll[Chunk[V]]]
    ): StreamSubscription[V, Ctx] < (IO & Ctx & Resource) =
        for
            subscription <- IO.Unsafe(new StreamSubscription[V, Ctx](stream, subscriber))
            _            <- subscription.subscribe
            _            <- Resource.acquireRelease(subscription.consume)(_.interrupt.unit)
        yield subscription

    object Unsafe:
        inline def subscribe[V, Ctx](
            stream: Stream[V, Ctx],
            subscriber: Subscriber[? >: V]
        )(
            subscribeCallback: (Fiber[Nothing, StreamFinishState] < (IO & Ctx)) => Unit
        )(
            using
            AllowUnsafe,
            Frame,
            Tag[Emit[Chunk[V]]],
            Tag[Poll[Chunk[V]]]
        ): StreamSubscription[V, Ctx] =
            _subscribe(stream, subscriber)(subscribeCallback)

        private[kyo] inline def _subscribe[V, Ctx](
            stream: Stream[V, Ctx],
            subscriber: Subscriber[? >: V]
        )(
            subscribeCallback: (Fiber[Nothing, StreamFinishState] < (IO & Ctx)) => Unit
        )(
            using
            AllowUnsafe,
            Boundary[Ctx, IO & Abort[Nothing]],
            Frame,
            Tag[Emit[Chunk[V]]],
            Tag[Poll[Chunk[V]]]
        ): StreamSubscription[V, Ctx] =
            val subscription = new StreamSubscription[V, Ctx](stream, subscriber)
            subscribeCallback(subscription.subscribe.andThen(subscription.consume))
            subscription
        end _subscribe
    end Unsafe

end StreamSubscription
