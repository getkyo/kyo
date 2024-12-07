package kyo.interop.reactivestreams

import kyo.*
import kyo.interop.reactivestreams.StreamSubscription
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription

final class CancellationTest extends Test:
    final class Sub[A](b: AtomicBoolean.Unsafe) extends Subscriber[A]:
        import AllowUnsafe.embrace.danger
        def onNext(t: A)                 = b.set(true)
        def onComplete()                 = b.set(true)
        def onError(e: Throwable)        = b.set(true)
        def onSubscribe(s: Subscription) = ()
    end Sub

    val stream: Stream[Int, Any] = Stream.range(0, 5, 1)

    val attempts = 1000

    def testStreamSubscription(clue: String)(program: Subscription => Unit): Unit < IO =
        Loop(attempts) { index =>
            if index <= 0 then
                Loop.done
            else
                IO.Unsafe {
                    val flag = AtomicBoolean.Unsafe.init(false)
                    StreamSubscription.Unsafe.subscribe(stream, new Sub(flag)) { fiber =>
                        discard(IO.Unsafe.run(fiber).eval)
                    }
                }.map { subscription =>
                    program(subscription)
                }.andThen(Loop.continue(index - 1))
            end if
        }

    "after subscription is canceled request must be NOOPs" in runJVM {
        testStreamSubscription(clue = "onNext was called after the subscription was canceled") { sub =>
            sub.cancel()
            sub.request(1)
            sub.request(1)
            sub.request(1)
        }.map(_ => assert(true))
    }

    "after subscription is canceled additional cancellations must be NOOPs" in runJVM {
        testStreamSubscription(clue = "onComplete was called after the subscription was canceled") {
            sub =>
                sub.cancel()
                sub.cancel()
        }.map(_ => assert(true))
    }
end CancellationTest
