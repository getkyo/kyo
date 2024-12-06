package kyo.interop.reactivestreams

import kyo.*
import kyo.Duration
import kyo.interop.reactivestreams.*

final class PublisherToSubscriberTest extends Test:
    import PublisherToSubscriberTest.*

    private def randomStream: Stream[Int, Async] < IO =
        IO(Stream
            .range(0, 1 << 10, 1, BufferSize)
            .map { int =>
                Random
                    .use(_.nextInt(10))
                    .map(millis => Async.sleep(Duration.fromUnits(millis, Duration.Units.Millis)))
                    .andThen(int)
            })

    "should have the same output as input" in runJVM {
        for
            stream     <- randomStream
            publisher  <- StreamPublisher[Int, Async](stream)
            subscriber <- StreamSubscriber[Int](BufferSize)
            _ = publisher.subscribe(subscriber)
            (isSame, _) <- subscriber.stream
                .runFold(true -> 0) { case ((acc, expected), cur) =>
                    Random
                        .use(_.nextInt(10))
                        .map(millis => Async.sleep(Duration.fromUnits(millis, Duration.Units.Millis)))
                        .andThen((acc && (expected == cur)) -> (expected + 1))
                }
        yield assert(isSame)
    }

    "should propagate errors downstream" in runJVM {
        for
            inputStream <- IO {
                Stream.range(0, 10, 1, 1).map { int =>
                    if int < 5 then
                        Async.sleep(Duration.fromUnits(10, Duration.Units.Millis)).andThen(int)
                    else
                        Abort.panic(TestError)
                }
            }
            publisher  <- StreamPublisher[Int, Async](inputStream)
            subscriber <- StreamSubscriber[Int](BufferSize)
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
            stream      <- IO(Stream(Emit.andMap(Chunk.empty[Int])(emit(_, 0, stopPromise))))
            publisher   <- StreamPublisher[Int, IO](stream)
            subscriber  <- StreamSubscriber[Int](BufferSize)
            _ = publisher.subscribe(subscriber)
            _ <- subscriber.stream.take(10).runDiscard
            _ <- stopPromise.get
        yield assert(true)
        end for
    }
end PublisherToSubscriberTest

object PublisherToSubscriberTest:
    type TestError = TestError.type
    object TestError extends Exception("BOOM")
    private val BufferSize = 1 << 4
end PublisherToSubscriberTest
