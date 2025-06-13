package kyo.interop.flow

import StreamSubscription.*
import java.util.concurrent.Flow.*
import kyo.*
import kyo.kernel.ArrowEffect

final private[kyo] class StreamSubscription[V, S](
    private val stream: Stream[V, S & IO],
    subscriber: Subscriber[? >: V]
)(
    using
    Isolate.Contextual[S, IO],
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

    private[interop] def subscribe(using Frame): Unit < IO = IO(subscriber.onSubscribe(this))

    private[interop] def poll(using Tag[Poll[Chunk[V]]], Frame): StreamComplete < (Async & Poll[Chunk[V]] & Abort[StreamCanceled]) =
        def loopPoll(requesting: Long): (Chunk[V] | StreamComplete) < (IO & Poll[Chunk[V]]) =
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
        Frame
    ): Fiber[StreamCanceled, StreamComplete] < (IO & S) =
        Async.run[StreamCanceled, StreamComplete, S](Poll.runEmit(stream.emit)(poll).map(_._2))
            .map { fiber =>
                fiber.onComplete {
                    case Result.Success(StreamComplete) => IO(subscriber.onComplete())
                    case Result.Panic(e)                => IO(subscriber.onError(e))
                    case Result.Failure(StreamCanceled) => Kyo.unit
                }.andThen(fiber)
            }
    end consume

end StreamSubscription

object StreamSubscription:

    type StreamComplete = StreamComplete.type
    case object StreamComplete
    type StreamCanceled = StreamCanceled.type
    case object StreamCanceled

    def subscribe[V, S](
        using Isolate.Contextual[S, IO]
    )(
        stream: Stream[V, S & IO],
        subscriber: Subscriber[? >: V]
    )(
        using
        Frame,
        Tag[Emit[Chunk[V]]],
        Tag[Poll[Chunk[V]]]
    ): StreamSubscription[V, S] < (IO & S & Resource) =
        for
            subscription <- IO.Unsafe(new StreamSubscription[V, S](stream, subscriber))
            _            <- subscription.subscribe
            _            <- Resource.acquireRelease(subscription.consume)(_.interrupt.unit)
        yield subscription

    object Unsafe:
        def subscribe[V, S](
            using Isolate.Contextual[S, IO]
        )(
            stream: Stream[V, S & IO],
            subscriber: Subscriber[? >: V]
        )(
            subscribeCallback: (Fiber[StreamCanceled, StreamComplete] < (IO & S)) => Unit
        )(
            using
            AllowUnsafe,
            Frame,
            Tag[Emit[Chunk[V]]],
            Tag[Poll[Chunk[V]]]
        ): StreamSubscription[V, S] =
            val subscription = new StreamSubscription[V, S](stream, subscriber)
            subscribeCallback(subscription.subscribe.andThen(subscription.consume))
            subscription
        end subscribe
    end Unsafe

end StreamSubscription
