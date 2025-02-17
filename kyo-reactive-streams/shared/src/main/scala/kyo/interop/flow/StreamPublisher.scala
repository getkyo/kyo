package kyo.interop.flow

import java.util.concurrent.Flow.*
import kyo.*
import kyo.interop.flow.StreamSubscription.StreamCanceled
import kyo.interop.flow.StreamSubscription.StreamComplete
import kyo.kernel.Boundary
import scala.annotation.nowarn

abstract private[kyo] class StreamPublisher[V, Ctx](
    stream: Stream[V, Ctx]
) extends Publisher[V]:

    protected def bind(subscriber: Subscriber[? >: V]): Unit

    override def subscribe(subscriber: Subscriber[? >: V]): Unit =
        if isNull(subscriber) then
            throw new NullPointerException("Subscriber must not be null.")
        else
            bind(subscriber)
    end subscribe

    private[StreamPublisher] def getSubscription(subscriber: Subscriber[? >: V])(using Frame): StreamSubscription[V, Ctx] < IO =
        IO.Unsafe(new StreamSubscription[V, Ctx](stream, subscriber))
    end getSubscription

end StreamPublisher

object StreamPublisher:

    def apply[V, Ctx](
        stream: Stream[V, Ctx],
        capacity: Int = Int.MaxValue
    )(
        using
        Boundary[Ctx, IO & Abort[StreamCanceled]],
        Frame,
        Tag[Emit[Chunk[V]]],
        Tag[Poll[Chunk[V]]]
    ): StreamPublisher[V, Ctx] < (Resource & IO & Ctx) =
        def discardSubscriber(subscriber: Subscriber[? >: V]): Unit =
            subscriber.onSubscribe(new Subscription:
                override def request(n: Long): Unit = ()
                override def cancel(): Unit         = ())
            subscriber.onComplete()
        end discardSubscriber

        def consumeChannel(
            publisher: StreamPublisher[V, Ctx],
            channel: Channel[Subscriber[? >: V]],
            supervisor: Fiber.Promise[Nothing, Unit]
        ): Unit < (Async & Ctx) =
            Abort.recover[Closed](_ => supervisor.interrupt.unit)(
                channel.stream().foreach: subscriber =>
                    for
                        subscription <- publisher.getSubscription(subscriber)
                        fiber        <- subscription.subscribe.andThen(subscription.consume)
                        _            <- supervisor.onInterrupt(_ => fiber.interrupt(Result.Panic(Interrupt())).unit)
                    yield ()
            )

        for
            channel <-
                Resource.acquireRelease(Channel.init[Subscriber[? >: V]](capacity))(
                    _.close.map(_.foreach(_.foreach(discardSubscriber(_))))
                )
            publisher <- IO.Unsafe {
                new StreamPublisher[V, Ctx](stream):
                    override protected def bind(
                        subscriber: Subscriber[? >: V]
                    ): Unit =
                        channel.unsafe.offer(subscriber) match
                            case Result.Success(true) => ()
                            case _                    => discardSubscriber(subscriber)
            }
            supervisor <- Resource.acquireRelease(Fiber.Promise.init[Nothing, Unit])(_.interrupt.unit)
            _          <- Resource.acquireRelease(Async._run(consumeChannel(publisher, channel, supervisor)))(_.interrupt.unit)
        yield publisher
        end for
    end apply

    object Unsafe:
        @nowarn("msg=anonymous")
        inline def apply[V, Ctx](
            stream: Stream[V, Ctx],
            subscribeCallback: (Fiber[StreamCanceled, StreamComplete] < (IO & Ctx)) => Unit
        )(
            using
            AllowUnsafe,
            Frame,
            Tag[Emit[Chunk[V]]],
            Tag[Poll[Chunk[V]]]
        ): StreamPublisher[V, Ctx] =
            new StreamPublisher[V, Ctx](stream):
                override protected def bind(
                    subscriber: Subscriber[? >: V]
                ): Unit =
                    discard(StreamSubscription.Unsafe._subscribe(
                        stream,
                        subscriber
                    )(
                        subscribeCallback
                    ))
    end Unsafe
end StreamPublisher
