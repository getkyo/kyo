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
            // Taking a subscriber out of the channel and setting it up is one indivisible step. In between, the subscriber
            // holds a live subscription that nothing is yet committed to ending, so an interrupt landing there strands it.
            // Masking the pair leaves an interrupt only two places to land: before the subscriber leaves the channel, where
            // the channel's own close discards it, or after the registrations below, which stop it. Taking INSIDE the mask
            // is what stops a subscriber from being out of the channel and not yet in a subscription at the same time.
            def setUpOne: Unit < (Abort[Closed] & Async & S) =
                for
                    subscriber   <- channel.take
                    subscription <- publisher.getSubscription(subscriber)
                    _            <- subscription.subscribe
                    _            <- supervisor.onInterrupt(_ => Sync.defer(subscription.stop()))
                    _            <- subscription.consume
                    // Registering on an ALREADY-settled promise is silently dropped, so a subscription set up while the
                    // publisher was being torn down would keep no registration at all. Re-reading the supervisor covers
                    // that. `stop` is idempotent and total, so the ordinary path costs one completed-promise read.
                    _ <- supervisor.done.map: settled =>
                        if settled then Sync.defer(subscription.stop())
                        else Kyo.unit
                yield ()

            Abort.recover[Closed](_ => supervisor.interrupt.unit)(
                Loop.foreach(
                    Fiber.initUnscoped[Closed, Unit, S, Any](setUpOne)
                        .map(_.mask)
                        .map(_.get)
                        .andThen(Loop.continue)
                )
            )
        end consumeChannel

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
