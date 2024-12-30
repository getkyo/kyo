package kyo.interop.flow

import java.util.concurrent.Flow.*
import kyo.*

final class CancellationTest extends Test:
    final class Sub[A](b: AtomicBoolean) extends Subscriber[A]:
        import AllowUnsafe.embrace.danger
        def onNext(t: A)                 = IO.Unsafe.evalOrThrow(b.set(true))
        def onComplete()                 = IO.Unsafe.evalOrThrow(b.set(true))
        def onError(e: Throwable)        = IO.Unsafe.evalOrThrow(b.set(true))
        def onSubscribe(s: Subscription) = ()
    end Sub

    val stream: Stream[Int, Any] = Stream.range(0, 5, 1)

    val attempts = 100

    def testStreamSubscription(clue: String)(program: Subscription => Unit): Unit < (IO & Resource) =
        Loop(attempts) { index =>
            if index <= 0 then
                Loop.done
            else
                for
                    flag         <- AtomicBoolean.init(false)
                    subscription <- StreamSubscription.subscribe(stream, new Sub(flag))
                    _            <- IO(program(subscription))
                yield Loop.continue(index - 1)
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
