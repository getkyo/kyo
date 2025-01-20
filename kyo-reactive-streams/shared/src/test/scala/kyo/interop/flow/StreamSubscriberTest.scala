package kyo.interop.flow

import StreamSubscriber.EmitStrategy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow.*
import kyo.*

final class JavaSubscription(subscriber: Subscriber[? >: Int], batchSize: Int, counter: java.util.concurrent.atomic.AtomicInteger)
    extends Subscription:

    private val isCanceled   = java.util.concurrent.atomic.AtomicBoolean(false)
    private val requestCount = java.util.concurrent.atomic.AtomicInteger(0)

    override def request(n: Long): Unit =
        def loop(): Runnable =
            new Runnable:
                override def run(): Unit =
                    if !isCanceled.get() && (requestCount.getAndIncrement() < n) then
                        subscriber.onNext(counter.getAndIncrement())
                        run()
        requestCount.set(0)
        discard(CompletableFuture.allOf(
            List.fill(batchSize)(CompletableFuture.runAsync(loop()))*
        ).get())
    end request

    override def cancel(): Unit = isCanceled.set(true)
end JavaSubscription

final class StreamSubscriberTest extends Test:
    import StreamSubscriberTest.*

    def getPublisher(
        batchSize: Int
    ) =
        new Publisher[Int]:
            private val counter = java.util.concurrent.atomic.AtomicInteger(1)
            override def subscribe(subscriber: Subscriber[? >: Int]): Unit =
                val subscription = new JavaSubscription(subscriber, batchSize, counter)
                subscriber.onSubscribe(subscription)
            end subscribe

    "Concurrent publisher & eager subscriber" in runJVM {
        val publisher = getPublisher(BatchSize)
        for
            subscriber <- StreamSubscriber[Int](BufferSize, EmitStrategy.Eager)
            _ = publisher.subscribe(subscriber)
            results <- subscriber.stream.take(StreamLength).runFold(0)(_ + _)
        yield assert(results == (StreamLength >> 1) * (StreamLength + 1))
        end for
    }

    "Concurrent publisher & buffer subscriber" in runJVM {
        val publisher = getPublisher(BatchSize)
        for
            subscriber <- StreamSubscriber[Int](BufferSize, EmitStrategy.Buffer)
            _ = publisher.subscribe(subscriber)
            results <- subscriber.stream.take(StreamLength).runFold(0)(_ + _)
        yield assert(results == (StreamLength >> 1) * (StreamLength + 1))
        end for
    }

    "Concurrent publisher & multiple subscribers" in runJVM {
        val publisher = getPublisher(BatchSize)
        for
            subscriber1 <- StreamSubscriber[Int](BufferSize, EmitStrategy.Eager)
            subscriber2 <- StreamSubscriber[Int](BufferSize, EmitStrategy.Buffer)
            _ = publisher.subscribe(subscriber1)
            _ = publisher.subscribe(subscriber2)
            results <- Async.parallelUnbounded(List(
                subscriber1.stream.take(StreamLength >> 1).runFold(0)(_ + _),
                subscriber2.stream.take(StreamLength >> 1).runFold(0)(_ + _)
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
