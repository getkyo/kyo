package kyo.interop.reactivestreams

import kyo.*
import kyo.interop.reactivestreams.StreamSubscription.StreamFinishState
import kyo.kernel.Boundary
import kyo.kernel.ContextEffect.Isolated
import kyo.kernel.Safepoint
import kyo.scheduler.IOTask
import org.reactivestreams.*

abstract class StreamPublisher[V, Ctx] private (
    stream: Stream[V, Ctx]
) extends Publisher[V]:

    protected def bind(subscriber: Subscriber[? >: V]): Unit

    override def subscribe(subscriber: Subscriber[? >: V]): Unit =
        if isNull(subscriber) then
            throw new NullPointerException("Subscriber must not be null.")
        else
            bind(subscriber)
    end subscribe

end StreamPublisher

object StreamPublisher:
    def apply[V, Ctx](
        stream: Stream[V, Ctx],
        capacity: Int = Int.MaxValue
    )(
        using
        boundary: Boundary[Ctx, IO],
        frame: Frame,
        tag: Tag[V]
    ): StreamPublisher[V, Ctx] < (Resource & IO & Ctx) =
        inline def interruptPanic = Result.Panic(Fiber.Interrupted(frame))

        def discardSubscriber(subscriber: Subscriber[? >: V]): Unit =
            subscriber.onSubscribe(new Subscription:
                override def request(n: Long): Unit = ()
                override def cancel(): Unit         = ()
            )
            subscriber.onComplete()
        end discardSubscriber

        def consumeChannel(
            channel: Channel[Subscriber[? >: V]],
            supervisorPromise: Fiber.Promise[Nothing, Unit]
        ): Unit < (Async & Ctx) =
            Loop(()) { _ =>
                channel.closed.map {
                    if _ then
                        Loop.done
                    else
                        val result = Abort.run[Closed] {
                            for
                                subscriber   <- channel.take
                                subscription <- IO.Unsafe(new StreamSubscription[V, Ctx](stream, subscriber))
                                fiber        <- subscription.subscribe.andThen(subscription.consume)
                                _            <- supervisorPromise.onComplete(_ => discard(fiber.interrupt(interruptPanic)))
                            yield ()
                        }
                        result.map {
                            case Result.Success(_) => Loop.continue(())
                            case _                 => Loop.done
                        }
                }
            }

        IO.Unsafe {
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
                supervisorPromise <- Fiber.Promise.init[Nothing, Unit]
                _ <- Resource.acquireRelease(boundary((trace, context) =>
                    Fiber.fromTask(IOTask(consumeChannel(channel, supervisorPromise), trace, context))
                ))(
                    _.interrupt.map(discard(_))
                )
            yield publisher
            end for
        }
    end apply

    object Unsafe:
        def apply[V, Ctx](
            stream: Stream[V, Ctx],
            subscribeCallback: (Fiber[Nothing, StreamFinishState] < (IO & Ctx)) => Unit
        )(
            using
            allowance: AllowUnsafe,
            boundary: Boundary[Ctx, IO],
            frame: Frame,
            tag: Tag[V]
        ): StreamPublisher[V, Ctx] =
            new StreamPublisher[V, Ctx](stream):
                override protected def bind(
                    subscriber: Subscriber[? >: V]
                ): Unit =
                    discard(StreamSubscription.Unsafe.subscribe(
                        stream,
                        subscriber
                    )(
                        subscribeCallback
                    )(
                        using
                        allowance,
                        boundary,
                        frame,
                        tag
                    ))
    end Unsafe
end StreamPublisher
