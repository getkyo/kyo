package kyo.interop.flow

import java.util.concurrent.Flow.*
import kyo.*
import kyo.interop.flow.StreamSubscription.StreamCanceled
import kyo.interop.flow.StreamSubscription.StreamComplete
import scala.annotation.nowarn

abstract private[kyo] class StreamPublisher[V, S](
    stream: Stream[V, S & Sync]
)(using Isolate[S, Sync, Any]) extends Publisher[V]:

    protected def bind(subscriber: Subscriber[? >: V]): Unit

    override def subscribe(subscriber: Subscriber[? >: V]): Unit =
        if isNull(subscriber) then
            throw new NullPointerException("Subscriber must not be null.")
        else
            bind(subscriber)
    end subscribe

    private[StreamPublisher] def getSubscription(subscriber: Subscriber[? >: V])(using Frame): StreamSubscription[V, S] < Sync =
        Sync.Unsafe.defer(new StreamSubscription[V, S](stream, subscriber))
    end getSubscription

end StreamPublisher

object StreamPublisher:

    def apply[V, S](
        using Isolate[S, Sync, Any]
    )(
        stream: Stream[V, S & Sync],
        capacity: Int = Int.MaxValue
    )(
        using
        Frame,
        Tag[Emit[Chunk[V]]],
        Tag[Poll[Chunk[V]]]
    ): StreamPublisher[V, S] < (Scope & Sync & S) =
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
                        _            <- subscription.subscribe
                        // Registered BEFORE the consuming fiber exists. Cancelling a subscription closes its request
                        // channel, which is what ends that fiber, so a teardown arriving between here and the fiber's
                        // creation still stops the subscription rather than stranding it with nothing to interrupt it.
                        _     <- supervisor.onInterrupt(_ => Sync.defer(subscription.stopForPublisherTeardown()))
                        fiber <- subscription.consume
                        _     <- supervisor.onInterrupt(_ => fiber.interrupt(Result.Panic(Interrupted(summon[Frame]))))
                        // Registering on an ALREADY-completed promise is silently dropped, so for a subscriber accepted
                        // while the publisher was being torn down neither registration above ever runs. Re-read the
                        // supervisor and stop this subscription directly when that is what happened. Both calls are
                        // idempotent, so the ordinary path costs a single completed-promise read.
                        _ <- supervisor.done.map: settled =>
                            if !settled then Kyo.unit
                            else
                                Sync.defer(subscription.stopForPublisherTeardown())
                                    .andThen(fiber.interrupt(Result.Panic(Interrupted(summon[Frame]))).unit)
                    yield ()
            )

        for
            channel <-
                Scope.acquireRelease(Channel.init[Subscriber[? >: V]](capacity))(
                    _.close.map(_.foreach(_.foreach(discardSubscriber(_))))
                )
            publisher <- Sync.Unsafe.defer {
                new StreamPublisher[V, S](stream):
                    override protected def bind(
                        subscriber: Subscriber[? >: V]
                    ): Unit =
                        channel.unsafe.offer(subscriber) match
                            case Result.Success(true) => ()
                            case _                    => discardSubscriber(subscriber)
            }
            supervisor <- Scope.acquireRelease(Fiber.Promise.init[Nothing, Unit])(_.interrupt)
            _          <- Scope.acquireRelease(Fiber.initUnscoped(consumeChannel(publisher, channel, supervisor)))(_.interrupt)
        yield publisher
        end for
    end apply

    object Unsafe:
        @nowarn("msg=anonymous")
        def apply[V, S](
            using Isolate[S, Sync, Any]
        )(
            stream: Stream[V, S & Sync],
            subscribeCallback: (Fiber[StreamComplete, Abort[StreamCanceled]] < (Sync & S)) => Unit
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
