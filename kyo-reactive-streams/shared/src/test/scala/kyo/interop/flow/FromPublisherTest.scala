package kyo.interop.flow

import java.util.concurrent.Flow.*
import kyo.*
import kyo.interop.flow.StreamSubscriber.EmitStrategy

final class FromPublisherTest extends Test:
    import FromPublisherTest.*

    private def simplePublisher(size: Int): Publisher[Int] =
        new Publisher[Int]:
            override def subscribe(subscriber: Subscriber[? >: Int]): Unit =
                val counter  = java.util.concurrent.atomic.AtomicInteger(0)
                val canceled = java.util.concurrent.atomic.AtomicBoolean(false)
                subscriber.onSubscribe(new Subscription:
                    override def request(n: Long): Unit =
                        var i = 0L
                        while i < n && !canceled.get() do
                            val v = counter.getAndIncrement()
                            if v < size then
                                subscriber.onNext(v)
                            else
                                subscriber.onComplete()
                                return
                            end if
                            i += 1
                        end while
                    end request
                    override def cancel(): Unit = canceled.set(true))
            end subscribe

    private def failingPublisher(succeedCount: Int, error: Throwable): Publisher[Int] =
        new Publisher[Int]:
            override def subscribe(subscriber: Subscriber[? >: Int]): Unit =
                val counter = java.util.concurrent.atomic.AtomicInteger(0)
                subscriber.onSubscribe(new Subscription:
                    override def request(n: Long): Unit =
                        var i = 0L
                        while i < n do
                            val v = counter.getAndIncrement()
                            if v < succeedCount then
                                subscriber.onNext(v)
                            else
                                subscriber.onError(error)
                                return
                            end if
                            i += 1
                        end while
                    end request
                    override def cancel(): Unit = ())
            end subscribe

    "fromPublisher with Eager strategy" in run {
        val publisher = simplePublisher(StreamLength)
        for
            stream  <- fromPublisher(publisher, BufferSize, EmitStrategy.Eager)
            results <- stream.fold(0)(_ + _)
        yield assert(results == (StreamLength - 1) * StreamLength / 2)
        end for
    }

    "fromPublisher with Buffer strategy" in run {
        val publisher = simplePublisher(StreamLength)
        for
            stream  <- fromPublisher(publisher, BufferSize, EmitStrategy.Buffer)
            results <- stream.fold(0)(_ + _)
        yield assert(results == (StreamLength - 1) * StreamLength / 2)
        end for
    }

    "fromPublisher should propagate errors" in run {
        val publisher = failingPublisher(5, TestError)
        for
            stream <- fromPublisher(publisher, BufferSize)
            fiber  <- Fiber.initUnscoped(stream.run)
            result <- fiber.getResult
        yield assert(result.isPanic)
        end for
    }

    "fromPublisher should handle immediate completion" in run {
        val publisher = simplePublisher(0)
        for
            stream  <- fromPublisher(publisher, BufferSize)
            results <- stream.run
        yield assert(results.isEmpty)
        end for
    }

    "fromPublisher should handle immediate error" in run {
        val publisher = failingPublisher(0, TestError)
        for
            stream <- fromPublisher(publisher, BufferSize)
            fiber  <- Fiber.initUnscoped(stream.run)
            result <- fiber.getResult
        yield assert(result.isPanic)
        end for
    }

    "fromPublisher should preserve element order" in run {
        val publisher = simplePublisher(StreamLength)
        for
            stream <- fromPublisher(publisher, BufferSize)
            result <- stream.fold(true -> 0) { case ((acc, expected), cur) =>
                (acc && (expected == cur)) -> (expected + 1)
            }
        yield assert(result._1)
        end for
    }

end FromPublisherTest

object FromPublisherTest:
    private val BufferSize   = 16
    private val StreamLength = 1 << 10
    type TestError = TestError.type
    object TestError extends Exception("BOOM")
end FromPublisherTest
