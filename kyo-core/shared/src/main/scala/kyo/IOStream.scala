package kyo

import java.util.concurrent.Flow
import java.util.concurrent.Flow.Subscriber
import java.util.concurrent.Flow.Subscription
import java.util.concurrent.SubmissionPublisher
import java.util.concurrent.atomic.AtomicInteger
import kyo.Emit.Ack
import kyo.kernel.Boundary
import kyo.kernel.Reducible

object IOStream:
    def toJavaPublisher[E, A: Flat, Ctx](stream: Stream[A, Abort[E] & Async & Ctx])(
        using
        frame: Frame,
        tag: Tag[Emit[Chunk[A]]],
        boundary: Boundary[Ctx, IO],
        reduce: Reducible[Abort[E]]
    ): Flow.Publisher[A] < (IO & Ctx) =
        IO.Unsafe {
            val requested = new java.util.concurrent.atomic.AtomicLong
            val onClose   = Promise.Unsafe.init[Nothing, Unit]()
            val publisher = new SubmissionPublisher[A]:
                override def close(): Unit =
                    onClose.completeDiscard(Result.unit)
                    super.close()
                override def subscribe(subscriber: Subscriber[? >: A]): Unit =
                    super.subscribe(
                        new Subscriber[A]:
                            def onSubscribe(subscription: Subscription): Unit =
                                subscriber.onSubscribe(
                                    new Subscription:
                                        def request(n: Long): Unit =
                                            requested.addAndGet(n)
                                            subscription.request(n)
                                        def cancel(): Unit = subscription.cancel()
                                )
                            def onNext(item: A): Unit               = subscriber.onNext(item)
                            def onError(throwable: Throwable): Unit = subscriber.onError(throwable)
                            def onComplete(): Unit                  = subscriber.onComplete()
                    )
                    ???
                end subscribe
            Async.run {
                IO.ensure(publisher.close()) {
                    Emit.runAck(stream.emit) { chunk =>
                        discard(chunk.foreach(publisher.submit))
                        if publisher.hasSubscribers() then Emit.Ack.Continue() else Emit.Ack.Stop
                    }
                }
            }.as(publisher)
        }
    end toJavaPublisher
end IOStream
