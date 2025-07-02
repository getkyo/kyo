package kyo.interop.reactivestreams

import kyo.*
import kyo.interop.flow.StreamPublisher
import org.reactivestreams.FlowAdapters
import org.reactivestreams.Publisher
import org.reactivestreams.tck.PublisherVerification
import org.reactivestreams.tck.TestEnvironment
import org.scalatestplus.testng.*

final class StreamPublisherTest extends PublisherVerification[Int](new TestEnvironment(50L)), TestNGSuiteLike:
    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    private def createStream(n: Int = 1) =
        if n <= 0 then
            Stream.empty[Int]
        else
            val chunkSize = Math.sqrt(n).floor.intValue
            Stream.range(0, n, 1, chunkSize)

    override def createPublisher(n: Long): Publisher[Int] =
        if n > Int.MaxValue then
            null
        else
            FlowAdapters.toPublisher(
                StreamPublisher.Unsafe(
                    createStream(n.toInt),
                    subscribeCallback = fiber =>
                        discard(Sync.Unsafe.evalOrThrow(Async.runAndBlock(Duration.Infinity)(fiber)))
                )
            )
        end if
    end createPublisher

    override def createFailedPublisher(): Publisher[Int] =
        FlowAdapters.toPublisher(
            StreamPublisher.Unsafe(
                createStream(),
                subscribeCallback = fiber =>
                    val asynced = Async.runAndBlock(Duration.Infinity)(fiber)
                    val result  = Sync.Unsafe.evalOrThrow(asynced)
                    discard(result.unsafe.interrupt())
            )
        )
    end createFailedPublisher
end StreamPublisherTest
