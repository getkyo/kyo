package kyo.interop.reactivestreams

import kyo.*
import kyo.interop.reactivestreams.StreamSubscription.StreamFinishState
import kyo.kernel.Boundary
import kyo.kernel.ContextEffect.Isolated
import kyo.kernel.Safepoint
import kyo.scheduler.IOTask
import org.reactivestreams.*
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

end StreamPublisher

object StreamPublisher:

    def apply[V, Ctx](
        stream: Stream[V, Ctx],
        capacity: Int = Int.MaxValue
    )(
        using
        Boundary[Ctx, IO & Abort[Nothing]],
        Frame,
        Tag[Emit[Chunk[V]]],
        Tag[Poll[Chunk[V]]]
    ): StreamPublisher[V, Ctx] < (Resource & IO & Ctx) =
        inline def interruptPanic = Result.Panic(Fiber.Interrupted(scala.compiletime.summonInline[Frame]))

        def discardSubscriber(subscriber: Subscriber[? >: V]): Unit =
            subscriber.onSubscribe(new Subscription:
                override def request(n: Long): Unit = ()
                override def cancel(): Unit         = ()
            )
            subscriber.onComplete()
        end discardSubscriber

        def consumeChannel(
            channel: Channel[Subscriber[? >: V]],
            supervisor: Fiber.Promise[Nothing, Unit]
        ): Unit < (Async & Ctx) =
            Loop(()) { _ =>
                channel.closed.map {
                    case true => Loop.done
                    case false =>
                        Abort.run[Closed] {
                            for
                                subscriber   <- channel.take
                                subscription <- IO.Unsafe(new StreamSubscription[V, Ctx](stream, subscriber))
                                fiber        <- subscription.subscribe.andThen(subscription.consume)
                                _            <- supervisor.onComplete(_ => discard(fiber.interrupt(interruptPanic)))
                            yield ()
                        }.map {
                            case Result.Success(_) => Loop.continue(())
                            case _                 => Loop.done
                        }
                }
            }

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
            supervisor <- Resource.acquireRelease(Fiber.Promise.init[Nothing, Unit])(_.interrupt.map(discard(_)))
            _          <- Resource.acquireRelease(Async._run(consumeChannel(channel, supervisor)))(_.interrupt.map(discard(_)))
        yield publisher
        end for
    end apply

    object Unsafe:
        @nowarn("msg=anonymous")
        inline def apply[V, Ctx](
            stream: Stream[V, Ctx],
            subscribeCallback: (Fiber[Nothing, StreamFinishState] < (IO & Ctx)) => Unit
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
