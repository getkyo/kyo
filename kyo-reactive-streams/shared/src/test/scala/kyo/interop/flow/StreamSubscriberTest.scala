package kyo.interop.flow

import StreamSubscriber.EmitStrategy
import java.util.concurrent.Flow.*
import kyo.*

final class StreamSubscriberTest extends Test:
    import StreamSubscriberTest.*

    def getPublisher(
        batchSize: Int
    ) =
        new Publisher[Int]:
            private val counter = java.util.concurrent.atomic.AtomicInteger(1)
            override def subscribe(subscriber: Subscriber[? >: Int]): Unit =
                import AllowUnsafe.embrace.danger
                val isCanceled = java.util.concurrent.atomic.AtomicBoolean(false)
                val subscription = new Subscription:
                    override def request(n: Long): Unit =
                        val requestCount = java.util.concurrent.atomic.AtomicInteger(0)
                        discard(Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped[Nothing, Unit, Any, Any](
                                Async.fill[Nothing, Unit, Any](batchSize) {
                                    Loop.foreach {
                                        Sync.defer {
                                            if !isCanceled.get() && (requestCount.getAndIncrement() < n) then
                                                subscriber.onNext(counter.getAndIncrement())
                                                Loop.continue
                                            else
                                                Loop.done
                                            end if
                                        }
                                    }
                                }.unit
                            )
                        ))
                    end request
                    override def cancel(): Unit = isCanceled.set(true)
                subscriber.onSubscribe(subscription)
            end subscribe

    "Concurrent publisher & eager subscriber" in run {
        val publisher = getPublisher(BatchSize)
        for
            subscriber <- StreamSubscriber[Int](BufferSize, EmitStrategy.Eager)
            subStream  <- subscriber.stream
            _ = publisher.subscribe(subscriber)
            results <- subStream.take(StreamLength).fold(0)(_ + _)
        yield assert(results == (StreamLength >> 1) * (StreamLength + 1))
        end for
    }

    "Concurrent publisher & buffer subscriber" in run {
        val publisher = getPublisher(BatchSize)
        for
            subscriber <- StreamSubscriber[Int](BufferSize, EmitStrategy.Buffer)
            subStream  <- subscriber.stream
            _ = publisher.subscribe(subscriber)
            results <- subStream.take(StreamLength).fold(0)(_ + _)
        yield assert(results == (StreamLength >> 1) * (StreamLength + 1))
        end for
    }

    "Concurrent publisher & multiple subscribers" in run {
        val publisher = getPublisher(BatchSize)
        for
            subscriber1 <- StreamSubscriber[Int](BufferSize, EmitStrategy.Eager)
            subStream1  <- subscriber1.stream
            subscriber2 <- StreamSubscriber[Int](BufferSize, EmitStrategy.Buffer)
            subStream2  <- subscriber2.stream
            _ = publisher.subscribe(subscriber1)
            _ = publisher.subscribe(subscriber2)
            results <- Async.collectAll(List(
                subStream1.take(StreamLength >> 1).fold(0)(_ + _),
                subStream2.take(StreamLength >> 1).fold(0)(_ + _)
            ))
        yield
            assert(results.size == 2)
            assert(results(0) + results(1) == (StreamLength >> 1) * (StreamLength + 1))
        end for
    }
end StreamSubscriberTest

object StreamSubscriberTest:
    private val BatchSize    = 4
    private val BufferSize   = 1 << 5
    private val StreamLength = 1 << 15
end StreamSubscriberTest
