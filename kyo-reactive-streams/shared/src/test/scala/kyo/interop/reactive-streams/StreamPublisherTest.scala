package kyo.interop.reactivestreams

import kyo.*
import kyo.Emit.Ack
import kyo.interop.reactivestreams.StreamPublisher
import org.reactivestreams.tck.PublisherVerification
import org.reactivestreams.tck.TestEnvironment
import org.scalatestplus.testng.*

final class StreamPublisherTest extends PublisherVerification[Int](new TestEnvironment(1000L)), TestNGSuiteLike:
    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    private def createStream(n: Int = 1) =
        if n <= 0 then
            Stream.empty[Int]
        else
            val chunkSize = Math.sqrt(n).floor.intValue
            Stream.range(0, n, 1, chunkSize).map { int =>
                Random
                    .use(_.nextInt(50))
                    .map(millis => Async.sleep(Duration.fromUnits(millis, Duration.Units.Millis)))
                    .andThen(int)
            }

    override def createPublisher(n: Long): StreamPublisher[Int, Async] =
        if n > Int.MaxValue then
            null
        else
            StreamPublisher.Unsafe(
                createStream(n.toInt),
                subscribeCallback = fiber =>
                    discard(IO.Unsafe.run(Abort.run(Async.runAndBlock(Duration.Infinity)(fiber))).eval)
            )
        end if
    end createPublisher

    override def createFailedPublisher(): StreamPublisher[Int, Async] =
        StreamPublisher.Unsafe(
            createStream(),
            subscribeCallback = fiber =>
                val asynced = Async.runAndBlock(Duration.Infinity)(fiber)
                val aborted = Abort.run(asynced)
                val ioed    = IO.Unsafe.run(aborted).eval
                ioed match
                    case Result.Success(fiber) => discard(fiber.unsafe.interrupt())
                    case _                     => ()
        )
    end createFailedPublisher
end StreamPublisherTest
