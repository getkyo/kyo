package kyo.interop.reactivestreams

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.interop.flow.StreamSubscriber
import kyo.interop.flow.StreamSubscriber.*
import org.reactivestreams.*
import org.reactivestreams.tck.SubscriberBlackboxVerification
import org.reactivestreams.tck.SubscriberWhiteboxVerification
import org.reactivestreams.tck.SubscriberWhiteboxVerification.SubscriberPuppet
import org.reactivestreams.tck.SubscriberWhiteboxVerification.WhiteboxSubscriberProbe
import org.reactivestreams.tck.TestEnvironment
import org.scalatestplus.testng.*

class EagerStreamSubscriberTest extends SubscriberWhiteboxVerification[Int](new TestEnvironment(1000L)), TestNGSuiteLike:
    import AllowUnsafe.embrace.danger
    private val counter = new AtomicInteger()

    def createSubscriber(
        p: SubscriberWhiteboxVerification.WhiteboxSubscriberProbe[Int]
    ): Subscriber[Int] =
        IO.Unsafe.evalOrThrow(StreamSubscriber[Int](bufferSize = 1).map(s => new WhiteboxSubscriber(s, p)))

    def createElement(i: Int): Int = counter.getAndIncrement
end EagerStreamSubscriberTest

class BufferStreamSubscriberTest extends SubscriberWhiteboxVerification[Int](new TestEnvironment(1000L)), TestNGSuiteLike:
    import AllowUnsafe.embrace.danger
    private val counter = new AtomicInteger()

    def createSubscriber(
        p: SubscriberWhiteboxVerification.WhiteboxSubscriberProbe[Int]
    ): Subscriber[Int] =
        IO.Unsafe.evalOrThrow(StreamSubscriber[Int](bufferSize = 16, EmitStrategy.Buffer).map(s =>
            new WhiteboxSubscriber(s, p)
        ))

    def createElement(i: Int): Int = counter.getAndIncrement
end BufferStreamSubscriberTest

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
        val flowS = if isNull(s) then
            null.asInstanceOf[java.util.concurrent.Flow.Subscription]
        else
            new java.util.concurrent.Flow.Subscription:
                override def request(n: Long): Unit = s.request(n)
                override def cancel(): Unit         = s.cancel()
        sub.onSubscribe(flowS)
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
                    discard(IO.Unsafe.evalOrThrow(computation))
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

final class StreamSubscriberWrapper[V](val streamSubscriber: StreamSubscriber[V]) extends Subscriber[V]:
    override def onSubscribe(s: Subscription): Unit =
        val flowS = if isNull(s) then
            null.asInstanceOf[java.util.concurrent.Flow.Subscription]
        else
            new java.util.concurrent.Flow.Subscription:
                override def request(n: Long): Unit = s.request(n)
                override def cancel(): Unit         = s.cancel()
        streamSubscriber.onSubscribe(flowS)
    end onSubscribe
    override def onNext(item: V): Unit       = streamSubscriber.onNext(item)
    override def onError(t: Throwable): Unit = streamSubscriber.onError(t)
    override def onComplete(): Unit          = streamSubscriber.onComplete()
end StreamSubscriberWrapper

final class EagerSubscriberBlackboxSpec extends SubscriberBlackboxVerification[Int](new TestEnvironment(1000L)), TestNGSuiteLike:
    import AllowUnsafe.embrace.danger
    private val counter = new AtomicInteger()

    def createSubscriber(): StreamSubscriberWrapper[Int] =
        new StreamSubscriberWrapper(IO.Unsafe.evalOrThrow(StreamSubscriber[Int](bufferSize = 1)))

    override def triggerRequest(s: Subscriber[? >: Int]): Unit =
        val computation: Long < IO = s.asInstanceOf[StreamSubscriberWrapper[Int]].streamSubscriber.request
        discard(IO.Unsafe.evalOrThrow(computation))

    def createElement(i: Int): Int = counter.incrementAndGet()
end EagerSubscriberBlackboxSpec

final class BufferSubscriberBlackboxSpec extends SubscriberBlackboxVerification[Int](new TestEnvironment(1000L)), TestNGSuiteLike:
    import AllowUnsafe.embrace.danger
    private val counter = new AtomicInteger()

    override def createSubscriber(): Subscriber[Int] =
        new StreamSubscriberWrapper(IO.Unsafe.evalOrThrow(StreamSubscriber[Int](bufferSize = 16, EmitStrategy.Buffer)))

    override def triggerRequest(s: Subscriber[? >: Int]): Unit =
        val computation: Long < IO = s.asInstanceOf[StreamSubscriberWrapper[Int]].streamSubscriber.request
        discard(IO.Unsafe.evalOrThrow(computation))

    def createElement(i: Int): Int = counter.incrementAndGet()
end BufferSubscriberBlackboxSpec
