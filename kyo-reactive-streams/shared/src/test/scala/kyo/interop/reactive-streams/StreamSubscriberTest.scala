package kyo.interop.reactivestreams

import java.lang.Thread
import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.Result.*
import kyo.interop.reactivestreams.StreamSubscriber
import kyo.interop.reactivestreams.StreamSubscriber.*
import org.reactivestreams.*
import org.reactivestreams.tck.SubscriberBlackboxVerification
import org.reactivestreams.tck.SubscriberWhiteboxVerification
import org.reactivestreams.tck.SubscriberWhiteboxVerification.SubscriberPuppet
import org.reactivestreams.tck.SubscriberWhiteboxVerification.WhiteboxSubscriberProbe
import org.reactivestreams.tck.TestEnvironment
import org.scalatestplus.testng.*

class StreamSubscriberTest extends SubscriberWhiteboxVerification[Int](new TestEnvironment(1000L)), TestNGSuiteLike:
    import AllowUnsafe.embrace.danger
    private val counter = new AtomicInteger()

    def createSubscriber(
        p: SubscriberWhiteboxVerification.WhiteboxSubscriberProbe[Int]
    ): Subscriber[Int] =
        IO.Unsafe.run {
            StreamSubscriber[Int](bufferSize = 1).map(s => new WhiteboxSubscriber(s, p))
        }.eval

    def createElement(i: Int): Int = counter.getAndIncrement
end StreamSubscriberTest

final class WhiteboxSubscriber[V](
    sub: StreamSubscriber[V],
    probe: WhiteboxSubscriberProbe[V]
)(
    using Tag[V]
) extends Subscriber[V]:
    import AllowUnsafe.embrace.danger

    def onError(t: Throwable): Unit =
        sub.onError(t)
        probe.registerOnError(t)
    end onError

    def onSubscribe(s: Subscription): Unit =
        sub.onSubscribe(s)
        probe.registerOnSubscribe(
            new SubscriberPuppet:
                override def triggerRequest(elements: Long): Unit =
                    val computation: Unit < IO = Loop(elements) { remaining =>
                        if remaining <= 0 then
                            Loop.done
                        else
                            sub.request.map { accepted =>
                                Loop.continue(remaining - accepted)
                            }
                    }
                    IO.Unsafe.run(computation).eval
                end triggerRequest

                override def signalCancel(): Unit =
                    s.cancel()
                end signalCancel
        )
    end onSubscribe

    def onComplete(): Unit =
        sub.onComplete()
        probe.registerOnComplete()
    end onComplete

    def onNext(a: V): Unit =
        sub.onNext(a)
        probe.registerOnNext(a)
    end onNext
end WhiteboxSubscriber

final class SubscriberBlackboxSpec extends SubscriberBlackboxVerification[Int](new TestEnvironment(1000L)), TestNGSuiteLike:
    import AllowUnsafe.embrace.danger
    private val counter = new AtomicInteger()

    def createSubscriber(): StreamSubscriber[Int] =
        IO.Unsafe.run {
            StreamSubscriber[Int](bufferSize = 1)
        }.eval

    override def triggerRequest(s: Subscriber[? >: Int]): Unit =
        val computation: Long < IO = s.asInstanceOf[StreamSubscriber[Int]].request
        discard(IO.Unsafe.run(computation).eval)

    def createElement(i: Int): Int = counter.incrementAndGet()
end SubscriberBlackboxSpec
