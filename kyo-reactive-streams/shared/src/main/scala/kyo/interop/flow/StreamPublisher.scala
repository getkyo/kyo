package kyo.interop.flow

import java.util.concurrent.Flow.*
import kyo.*
import kyo.interop.flow.StreamSubscription.StreamCanceled
import kyo.interop.flow.StreamSubscription.StreamComplete
import scala.annotation.nowarn

abstract private[kyo] class StreamPublisher[V, S](
    stream: Stream[V, S & IO]
)(using Isolate.Contextual[S, IO]) extends Publisher[V]:

    protected def bind(subscriber: Subscriber[? >: V]): Unit

    override def subscribe(subscriber: Subscriber[? >: V]): Unit =
        if isNull(subscriber) then
            throw new NullPointerException("Subscriber must not be null.")
        else
            bind(subscriber)
    end subscribe

    private[StreamPublisher] def getSubscription(subscriber: Subscriber[? >: V])(using Frame): StreamSubscription[V, S] < IO =
        IO.Unsafe(new StreamSubscription[V, S](stream, subscriber))
    end getSubscription

end StreamPublisher

object StreamPublisher:

    def apply[V, S](
        using Isolate.Contextual[S, IO]
    )(
        stream: Stream[V, S & IO],
        capacity: Int = Int.MaxValue
    )(
        using
        Frame,
        Tag[Emit[Chunk[V]]],
        Tag[Poll[Chunk[V]]]
    ): StreamPublisher[V, S] < (Resource & IO & S) =
        def discardSubscriber(subscriber: Subscriber[? >: V]): Unit =
            subscriber.onSubscribe(new Subscription:
                override def request(n: Long): Unit = ()
                override def cancel(): Unit         = ())
            subscriber.onComplete()
        end discardSubscriber

        def consumeChannel(
            publisher: StreamPublisher[V, S],
            channel: Channel[Subscriber[? >: V]],
            supervisor: Fiber.Promise[Nothing, Unit]
        ): Unit < (Async & S) =
            Abort.recover[Closed](_ => supervisor.interrupt.unit)(
                channel.stream().foreach: subscriber =>
                    for
                        subscription <- publisher.getSubscription(subscriber)
                        fiber        <- subscription.subscribe.andThen(subscription.consume)
                        _            <- supervisor.onInterrupt(_ => fiber.interrupt(Result.Panic(Interrupt())))
                    yield ()
            )

        for
            channel <-
                Resource.acquireRelease(Channel.init[Subscriber[? >: V]](capacity))(
                    _.close.map(_.foreach(_.foreach(discardSubscriber(_))))
                )
            publisher <- IO.Unsafe {
                new StreamPublisher[V, S](stream):
                    override protected def bind(
                        subscriber: Subscriber[? >: V]
                    ): Unit =
                        channel.unsafe.offer(subscriber) match
                            case Result.Success(true) => ()
                            case _                    => discardSubscriber(subscriber)
            }
            supervisor <- Resource.acquireRelease(Fiber.Promise.init[Nothing, Unit])(_.interrupt)
            _          <- Resource.acquireRelease(Async.run(consumeChannel(publisher, channel, supervisor)))(_.interrupt)
        yield publisher
        end for
    end apply

    object Unsafe:
        @nowarn("msg=anonymous")
        def apply[V, S](
            using Isolate.Contextual[S, IO]
        )(
            stream: Stream[V, S & IO],
            subscribeCallback: (Fiber[StreamCanceled, StreamComplete] < (IO & S)) => Unit
        )(
            using
            AllowUnsafe,
            Frame,
            Tag[Emit[Chunk[V]]],
            Tag[Poll[Chunk[V]]]
        ): StreamPublisher[V, S] =
            new StreamPublisher[V, S](stream):
                override protected def bind(
                    subscriber: Subscriber[? >: V]
                ): Unit =
                    discard(StreamSubscription.Unsafe.subscribe(
                        stream,
                        subscriber
                    )(
                        subscribeCallback
                    ))
    end Unsafe
end StreamPublisher
