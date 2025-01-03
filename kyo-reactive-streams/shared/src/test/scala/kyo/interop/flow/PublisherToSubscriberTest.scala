package kyo.interop.flow

import kyo.*
import kyo.Duration
import kyo.interop.flow.StreamSubscriber.EmitStrategy
import kyo.kernel.ArrowEffect

abstract private class PublisherToSubscriberTest extends Test:
    import PublisherToSubscriberTest.*

    protected def streamSubscriber: StreamSubscriber[Int] < IO

    "should have the same output as input" in runJVM {
        val stream = Stream.range(0, MaxStreamLength, 1, BufferSize)
        for
            publisher  <- stream.toPublisher
            subscriber <- streamSubscriber
            _ = publisher.subscribe(subscriber)
            (isSame, _) <- subscriber.stream
                .runFold(true -> 0) { case ((acc, expected), cur) =>
                    (acc && (expected == cur)) -> (expected + 1)
                }
        yield assert(isSame)
        end for
    }

    "should propagate errors downstream" in runJVM {
        val inputStream: Stream[Int, IO] = Stream
            .range(0, 10, 1, 1)
            .map { int =>
                if int < 5 then
                    IO(int)
                else
                    Abort.panic(TestError)
            }

        for
            publisher  <- inputStream.toPublisher
            subscriber <- streamSubscriber
            _ = publisher.subscribe(subscriber)
            result <- Abort.run[Throwable](subscriber.stream.runDiscard)
        yield result match
            case Result.Error(e: Throwable) => assert(e == TestError)
            case _                          => assert(false)
        end for
    }

    "should cancel upstream if downstream completes" in runJVM {
        def emit(ack: Ack, cur: Int, stopPromise: Fiber.Promise[Nothing, Unit]): Ack < (Emit[Chunk[Int]] & IO) =
            ack match
                case Ack.Stop        => stopPromise.completeDiscard(Result.success(())).andThen(Ack.Stop)
                case Ack.Continue(_) => Emit.andMap(Chunk(cur))(emit(_, cur + 1, stopPromise))
        end emit

        for
            stopPromise <- Fiber.Promise.init[Nothing, Unit]
            stream = Stream(Emit.andMap(Chunk.empty[Int])(emit(_, 0, stopPromise)))
            publisher  <- stream.toPublisher
            subscriber <- streamSubscriber
            _ = publisher.subscribe(subscriber)
            _ <- subscriber.stream.take(10).runDiscard
            _ <- stopPromise.get
        yield assert(true)
        end for
    }

    "single publisher & multiple subscribers" - {
        "contention" in runJVM {
            def emit(ack: Ack, counter: AtomicInt): Ack < (Emit[Chunk[Int]] & IO) =
                ack match
                    case Ack.Stop => IO(Ack.Stop)
                    case Ack.Continue(_) =>
                        counter.getAndIncrement.map: value =>
                            if value >= MaxStreamLength then
                                IO(Ack.Stop)
                            else
                                Emit.andMap(Chunk(value))(emit(_, counter))
                            end if
            end emit

            def checkStrictIncrease(chunk: Chunk[Int]): Boolean =
                val (isStrictIncrease, _) = chunk.foldLeft(true -> -1) { case ((accRes, last), cur) => (accRes && (last < cur)) -> cur }
                isStrictIncrease
            end checkStrictIncrease

            for
                counter <- AtomicInt.init(0)
                inputStream = Stream(Emit.andMap(Chunk.empty)(emit(_, counter)))
                publisher   <- inputStream.toPublisher
                subscriber1 <- streamSubscriber
                subscriber2 <- streamSubscriber
                subscriber3 <- streamSubscriber
                subscriber4 <- streamSubscriber
                _ = publisher.subscribe(subscriber1)
                _ = publisher.subscribe(subscriber2)
                _ = publisher.subscribe(subscriber3)
                _ = publisher.subscribe(subscriber4)
                values <- Fiber.parallelUnbounded[Nothing, Chunk[Int], Any](List(
                    subscriber1.stream.run,
                    subscriber2.stream.run,
                    subscriber3.stream.run,
                    subscriber4.stream.run
                )).map(_.get)
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
            def emit(ack: Ack, counter: AtomicInt): Ack < (Emit[Chunk[Int]] & IO) =
                ack match
                    case Ack.Stop => IO(Ack.Stop)
                    case Ack.Continue(_) =>
                        counter.getAndIncrement.map: value =>
                            if value >= MaxStreamLength then
                                IO(Ack.Stop)
                            else
                                Emit.andMap(Chunk(value))(emit(_, counter))
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
                inputStream = Stream(Emit.andMap(Chunk.empty)(emit(_, counter)))
                publisher   <- inputStream.toPublisher
                subscriber1 <- streamSubscriber
                subscriber2 <- streamSubscriber
                subscriber3 <- streamSubscriber
                subscriber4 <- streamSubscriber
                _ = publisher.subscribe(subscriber1)
                _ = publisher.subscribe(subscriber2)
                _ = publisher.subscribe(subscriber3)
                _ = publisher.subscribe(subscriber4)
                fiber1 <- Async.run(modify(subscriber1.stream, shouldFail = false))
                fiber2 <- Async.run(modify(subscriber2.stream, shouldFail = true))
                fiber3 <- Async.run(modify(subscriber3.stream, shouldFail = false))
                fiber4 <- Async.run(modify(subscriber4.stream, shouldFail = true))
                value1 <- fiber1.get
                value2 <- fiber2.getResult
                value3 <- fiber3.get
                value4 <- fiber4.getResult
            yield
                assert(value1.size + value3.size == MaxStreamLength)
                assert(checkStrictIncrease(value1))
                assert(value2 == Result.Panic(TestError))
                assert(checkStrictIncrease(value3))
                assert(value4 == Result.Panic(TestError))
                val actualSum   = value1.sum + value3.sum
                val expectedSum = (MaxStreamLength >> 1) * (MaxStreamLength - 1)
                assert(actualSum == expectedSum)
            end for
        }

        "publisher's interuption should end all subscribed parties" in runJVM {
            def emit(ack: Ack, counter: AtomicInt): Ack < (Emit[Chunk[Int]] & IO) =
                ack match
                    case Ack.Stop => IO(Ack.Stop)
                    case Ack.Continue(_) =>
                        counter.getAndIncrement.map: value =>
                            if value >= MaxStreamLength then
                                IO(Ack.Stop)
                            else
                                Emit.andMap(Chunk(value))(emit(_, counter))
                            end if
            end emit

            for
                counter     <- AtomicInt.init(0)
                publisher   <- Stream(Emit.andMap(Chunk.empty)(emit(_, counter))).toPublisher
                subscriber1 <- streamSubscriber
                subscriber2 <- streamSubscriber
                subscriber3 <- streamSubscriber
                subscriber4 <- streamSubscriber
                latch       <- Latch.init(5)
                fiber1      <- Async.run(latch.release.andThen(subscriber1.stream.run.unit))
                fiber2      <- Async.run(latch.release.andThen(subscriber2.stream.run.unit))
                fiber3      <- Async.run(latch.release.andThen(subscriber3.stream.run.unit))
                fiber4      <- Async.run(latch.release.andThen(subscriber4.stream.run.unit))
                publisherFiber <- Async.run(Resource.run(
                    Stream(Emit.andMap(Chunk.empty)(emit(_, counter)))
                        .toPublisher
                        .map { publisher =>
                            publisher.subscribe(subscriber1)
                            publisher.subscribe(subscriber2)
                            publisher.subscribe(subscriber3)
                            publisher.subscribe(subscriber4)
                        }
                        .andThen(latch.release.andThen(Async.sleep(1.seconds)))
                ))
                _ <- latch.await.andThen(publisherFiber.interrupt.unit)
                _ <- fiber1.getResult
                _ <- fiber2.getResult
                _ <- fiber3.getResult
                _ <- fiber4.getResult
            yield assert(true)
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
    override protected def streamSubscriber: StreamSubscriber[Int] < IO =
        StreamSubscriber[Int](PublisherToSubscriberTest.BufferSize, EmitStrategy.Eager)
end PublisherToEagerSubscriberTest

final class PublisherToBufferSubscriberTest extends PublisherToSubscriberTest:
    override protected def streamSubscriber: StreamSubscriber[Int] < IO =
        StreamSubscriber[Int](PublisherToSubscriberTest.BufferSize, EmitStrategy.Buffer)
end PublisherToBufferSubscriberTest
