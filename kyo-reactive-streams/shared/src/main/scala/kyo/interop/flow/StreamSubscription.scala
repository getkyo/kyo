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

    private[interop] def poll(using Tag[Poll[Chunk[V]]], Frame): StreamComplete < (Async & Poll[Chunk[V]] & Abort[StreamCanceled]) =
        inline def loopPoll(requesting: Long): (Chunk[V] | StreamComplete) < (IO & Poll[Chunk[V]]) =
            Loop[Long, Chunk[V] | StreamComplete, IO & Poll[Chunk[V]]](requesting): requesting =>
                Poll.andMap:
                    case Present(values) =>
                        if values.size <= requesting then
                            IO(values.foreach(subscriber.onNext(_)))
                                .andThen(Loop.continue(requesting - values.size))
                        else
                            IO(values.take(requesting.intValue).foreach(subscriber.onNext(_)))
                                .andThen(Loop.done(values.drop(requesting.intValue)))
                    case Absent =>
                        IO(Loop.done(StreamComplete))

        Loop[Chunk[V], StreamComplete, Async & Poll[Chunk[V]] & Abort[StreamCanceled]](Chunk.empty[V]): leftOver =>
            Abort.run[Closed](requestChannel.safe.take).map:
                case Result.Success(requesting) =>
                    if requesting <= leftOver.size then
                        IO(leftOver.take(requesting.intValue).foreach(subscriber.onNext(_)))
                            .andThen(Loop.continue(leftOver.drop(requesting.intValue)))
                    else
                        IO(leftOver.foreach(subscriber.onNext(_)))
                            .andThen(loopPoll(requesting - leftOver.size))
                            .map {
                                case nextLeftOver: Chunk[V] => Loop.continue(nextLeftOver)
                                case _: StreamComplete      => Loop.done(StreamComplete)
                            }
                case result => Abort.get(result.mapFailure(_ => StreamCanceled)).andThen(Loop.done(StreamComplete))
    end poll

    private[interop] def consume(
        using
        Tag[Emit[Chunk[V]]],
        Tag[Poll[Chunk[V]]],
        Frame,
        Boundary[Ctx, IO & Abort[StreamCanceled]]
    ): Fiber[StreamCanceled, StreamComplete] < (IO & Ctx) =
        Async
            ._run[StreamCanceled, StreamComplete, Ctx](Poll.run(stream.emit)(poll).map(_._2))
            .map { fiber =>
                fiber.onComplete {
                    case Result.Success(StreamComplete) => IO(subscriber.onComplete())
                    case Result.Panic(e)                => IO(subscriber.onError(e))
                    case Result.Failure(StreamCanceled) => IO.unit
                }.andThen(fiber)
            }
    end consume

end StreamSubscription

object StreamSubscription:

    type StreamComplete = StreamComplete.type
    case object StreamComplete
    type StreamCanceled = StreamCanceled.type
    case object StreamCanceled

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
            subscribeCallback: (Fiber[StreamCanceled, StreamComplete] < (IO & Ctx)) => Unit
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
            subscribeCallback: (Fiber[StreamCanceled, StreamComplete] < (IO & Ctx)) => Unit
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
