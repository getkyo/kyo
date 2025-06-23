package kyo.interop.flow

import kyo.*
import kyo.Result.Failure
import kyo.Result.Panic
import kyo.Result.Success
import kyo.interop.flow.StreamSubscriber.EmitStrategy
import kyo.interop.flow.StreamSubscription.StreamCanceled
import kyo.interop.flow.StreamSubscription.StreamComplete
import kyo.kernel.ArrowEffect

abstract private class PublisherToSubscriberTest extends Test:
    import PublisherToSubscriberTest.*

    protected def streamSubscriber: StreamSubscriber[Int] < Sync

    "should have the same output as input" in runJVM {
        val stream = Stream.range(0, MaxStreamLength, 1, BufferSize)
        for
            publisher  <- stream.toPublisher
            subscriber <- streamSubscriber
            _ = publisher.subscribe(subscriber)
            subscriberStream <- subscriber.stream
            (isSame, _) <- subscriberStream
                .fold(true -> 0) { case ((acc, expected), cur) =>
                    (acc && (expected == cur)) -> (expected + 1)
                }
        yield assert(isSame)
        end for
    }

    "should propagate errors downstream" in runJVM {
        pending
        val inputStream: Stream[Int, Sync] = Stream
            .range(0, 10, 1, 1)
            .map { int =>
                if int < 5 then
                    Sync(int)
                else
                    Abort.panic(TestError)
            }

        for
            publisher  <- inputStream.toPublisher
            subscriber <- streamSubscriber
            _ = publisher.subscribe(subscriber)
            subscriberStream <- subscriber.stream
            result           <- Abort.run[Throwable](subscriberStream.discard)
        yield assert(result == Panic(TestError))
        end for
    }

    "single publisher & multiple subscribers" - {
        "contention" in runJVM {
            def emit(counter: AtomicInt): Unit < (Emit[Chunk[Int]] & Sync) =
                counter.getAndIncrement.map: value =>
                    if value >= MaxStreamLength then ()
                    else
                        Emit.valueWith(Chunk(value))(emit(counter))
                    end if
            end emit

            def checkStrictIncrease(chunk: Chunk[Int]): Boolean =
                val (isStrictIncrease, _) = chunk.foldLeft(true -> -1) { case ((accRes, last), cur) => (accRes && (last < cur)) -> cur }
                isStrictIncrease
            end checkStrictIncrease

            for
                counter <- AtomicInt.init(0)
                inputStream = Stream(Emit.valueWith(Chunk.empty)(emit(counter)))
                publisher   <- inputStream.toPublisher
                subscriber1 <- streamSubscriber
                subStream1  <- subscriber1.stream
                subscriber2 <- streamSubscriber
                subStream2  <- subscriber2.stream
                subscriber3 <- streamSubscriber
                subStream3  <- subscriber3.stream
                subscriber4 <- streamSubscriber
                subStream4  <- subscriber4.stream
                _ = publisher.subscribe(subscriber1)
                _ = publisher.subscribe(subscriber2)
                _ = publisher.subscribe(subscriber3)
                _ = publisher.subscribe(subscriber4)
                values <- Async.collectAll[Nothing, Chunk[Int], Any](List(
                    subStream1.run,
                    subStream2.run,
                    subStream3.run,
                    subStream4.run
                ))
            yield
                assert(values.size == 4)
                assert(values(0).size + values(1).size + values(2).size + values(3).size == MaxStreamLength)
                assert(checkStrictIncrease(values(0)))
                assert(checkStrictIncrease(values(1)))
                assert(checkStrictIncrease(values(2)))
                assert(checkStrictIncrease(values(3)))
                val actualSum   = values(0).sum + values(1).sum + values(2).sum + values(3).sum
                val expectedSum = (MaxStreamLength >> 1) * (MaxStreamLength - 1)
                assert(actualSum == expectedSum)
            end for
        }

        "one subscriber's failure does not affect others." in runJVM {
            def emit(counter: AtomicInt): Unit < (Emit[Chunk[Int]] & Sync) =
                counter.getAndIncrement.map: value =>
                    if value >= MaxStreamLength then
                        Sync(())
                    else
                        Emit.valueWith(Chunk(value))(emit(counter))
                    end if
            end emit

            def checkStrictIncrease(chunk: Chunk[Int]): Boolean =
                val (isStrictIncrease, _) = chunk.foldLeft(true -> -1) { case ((accRes, last), cur) => (accRes && (last < cur)) -> cur }
                isStrictIncrease
            end checkStrictIncrease

            inline def modify(stream: Stream[Int, Async], inline shouldFail: Boolean) =
                inline shouldFail match
                    case true  => Stream(Abort.panic(TestError).andThen(stream.emit)).run
                    case false => stream.run
            end modify

            for
                counter <- AtomicInt.init(0)
                inputStream = Stream(Emit.valueWith(Chunk.empty)(emit(counter)))
                publisher   <- inputStream.toPublisher
                subscriber1 <- streamSubscriber
                subStream1  <- subscriber1.stream
                subscriber2 <- streamSubscriber
                subStream2  <- subscriber2.stream
                subscriber3 <- streamSubscriber
                subStream3  <- subscriber3.stream
                subscriber4 <- streamSubscriber
                subStream4  <- subscriber4.stream
                _ = publisher.subscribe(subscriber1)
                _ = publisher.subscribe(subscriber2)
                _ = publisher.subscribe(subscriber3)
                _ = publisher.subscribe(subscriber4)
                fiber1 <- Async.run(modify(subStream1, shouldFail = false))
                fiber2 <- Async.run(modify(subStream2, shouldFail = true))
                fiber3 <- Async.run(modify(subStream3, shouldFail = false))
                fiber4 <- Async.run(modify(subStream4, shouldFail = true))
                value1 <- fiber1.get
                value2 <- fiber2.getResult
                value3 <- fiber3.get
                value4 <- fiber4.getResult
            yield
                assert(checkStrictIncrease(value1))
                assert(value2 == Result.Panic(TestError))
                assert(checkStrictIncrease(value3))
                assert(value4 == Result.Panic(TestError))
                assert(value1.size + value3.size == MaxStreamLength)
                val actualSum   = value1.sum + value3.sum
                val expectedSum = (MaxStreamLength >> 1) * (MaxStreamLength - 1)
                assert(actualSum == expectedSum)
            end for
        }

        "publisher's interuption should end all subscribed parties" in runJVM {
            def emit(counter: AtomicInt): Unit < (Emit[Chunk[Int]] & Sync) =
                counter.getAndIncrement.map: value =>
                    if value >= MaxStreamLength then
                        Sync(())
                    else
                        Emit.valueWith(Chunk(value))(emit(counter))
                    end if
            end emit

            for
                counter     <- AtomicInt.init(0)
                publisher   <- Stream(Emit.valueWith(Chunk.empty)(emit(counter))).toPublisher
                subscriber1 <- streamSubscriber
                subStream1  <- subscriber1.stream
                subscriber2 <- streamSubscriber
                subStream2  <- subscriber2.stream
                subscriber3 <- streamSubscriber
                subStream3  <- subscriber3.stream
                subscriber4 <- streamSubscriber
                subStream4  <- subscriber4.stream
                latch       <- Latch.init(4)
                fiber1      <- Async.run(latch.release.andThen(subStream1.run.unit))
                fiber2      <- Async.run(latch.release.andThen(subStream2.run.unit))
                fiber3      <- Async.run(latch.release.andThen(subStream3.run.unit))
                fiber4      <- Async.run(latch.release.andThen(subStream4.run.unit))
                latchPub    <- Latch.init(1)
                publisherFiber <- Async.run(latch.await.andThen(Resource.run(
                    Stream(Emit.valueWith(Chunk.empty)(emit(counter)))
                        .toPublisher
                        .map { publisher =>
                            publisher.subscribe(subscriber1)
                            publisher.subscribe(subscriber2)
                            publisher.subscribe(subscriber3)
                            publisher.subscribe(subscriber4)
                        }
                        .andThen(latchPub.release).andThen(Async.never)
                )))
                _ <- latchPub.await
                _ <- publisherFiber.interrupt.unit
                _ <- fiber1.getResult
                _ <- fiber2.getResult
                _ <- fiber3.getResult
                _ <- fiber4.getResult
            yield assert(true)
            end for
        }

        "when complete, associated subscription should be canceled" in runJVM {
            val stream: Stream[Int, Any] =
                Stream(
                    Loop(0)(cur => Emit.valueWith(Chunk(cur))(Loop.continue(cur + 1)))
                )
            for
                promise    <- Fiber.Promise.init[Throwable, Unit]
                subscriber <- streamSubscriber
                subscription <- Sync.Unsafe {
                    StreamSubscription.Unsafe.subscribe(
                        stream,
                        subscriber
                    ): fiber =>
                        discard(Sync.Unsafe.evalOrThrow(fiber.map(_.onComplete { result =>
                            result match
                                case Failure(StreamCanceled) => promise.completeDiscard(Success(()))
                                case _                       => promise.completeDiscard(Failure(TestError))
                        })))
                }
                _      <- Resource.run(subscriber.stream.map(_.take(10).discard))
                result <- promise.getResult
            yield assert(result == Success(()))
            end for
        }
    }
end PublisherToSubscriberTest

object PublisherToSubscriberTest:
    type TestError = TestError.type
    object TestError extends Exception("BOOM")
    private[flow] val BufferSize      = 1 << 4
    private[flow] val MaxStreamLength = 1 << 10
end PublisherToSubscriberTest

final class PublisherToEagerSubscriberTest extends PublisherToSubscriberTest:
    override protected def streamSubscriber: StreamSubscriber[Int] < Sync =
        StreamSubscriber[Int](PublisherToSubscriberTest.BufferSize, EmitStrategy.Eager)
end PublisherToEagerSubscriberTest

final class PublisherToBufferSubscriberTest extends PublisherToSubscriberTest:
    override protected def streamSubscriber: StreamSubscriber[Int] < Sync =
        StreamSubscriber[Int](PublisherToSubscriberTest.BufferSize, EmitStrategy.Buffer)
end PublisherToBufferSubscriberTest
