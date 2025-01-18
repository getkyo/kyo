package kyo.interop.flow

import StreamSubscription.*
import java.util.concurrent.Flow.*
import kyo.*
import kyo.kernel.ArrowEffect
import kyo.kernel.Boundary
import kyo.scheduler.IOPromise
import kyo.scheduler.IOTask

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
            Loop[Long, Chunk[V] | StreamFinishState, IO & Poll[Chunk[V]]](requesting): requesting =>
                Poll.andMap:
                    case Present(values) =>
                        if values.size <= requesting then
                            IO(values.foreach(subscriber.onNext(_)))
                                .andThen(Loop.continue(requesting - values.size))
                        else
                            IO(values.take(requesting.intValue).foreach(subscriber.onNext(_)))
                                .andThen(Loop.done(values.drop(requesting.intValue)))
                    case Absent =>
                        IO(Loop.done(StreamFinishState.StreamComplete))

        Loop[Chunk[V], StreamFinishState, Async & Poll[Chunk[V]]](Chunk.empty[V]): leftOver =>
            Abort.run[Closed](requestChannel.safe.take).map:
                case Result.Success(requesting) =>
                    if requesting <= leftOver.size then
                        IO(leftOver.take(requesting.intValue).foreach(subscriber.onNext(_)))
                            .andThen(Loop.continue(leftOver.drop(requesting.intValue)))
                    else
                        IO(leftOver.foreach(subscriber.onNext(_)))
                            .andThen(loopPoll(requesting - leftOver.size))
                            .map {
                                case nextLeftOver: Chunk[V]   => Loop.continue(nextLeftOver)
                                case state: StreamFinishState => Loop.done(state)
                            }
                case Result.Failure(_)       => IO(Loop.done(StreamFinishState.StreamCanceled))
                case Result.Panic(exception) => Abort.panic(exception).andThen(Loop.done(StreamFinishState.StreamCanceled))
    end poll

    private[interop] def consume(
        using
        Tag[Emit[Chunk[V]]],
        Tag[Poll[Chunk[V]]],
        Frame,
        Boundary[Ctx, IO]
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
        Boundary[Ctx, IO],
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
            Boundary[Ctx, IO],
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
