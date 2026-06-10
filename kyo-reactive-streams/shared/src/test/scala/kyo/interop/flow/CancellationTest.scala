package kyo.interop.flow

import java.util.concurrent.Flow.*
import kyo.*

final class CancellationTest extends kyo.test.Test[Any]:
    final class Sub[A](b: AtomicBoolean) extends Subscriber[A]:
        import AllowUnsafe.embrace.danger
        def onNext(t: A)                 = Sync.Unsafe.evalOrThrow(b.set(true))
        def onComplete()                 = Sync.Unsafe.evalOrThrow(b.set(true))
        def onError(e: Throwable)        = Sync.Unsafe.evalOrThrow(b.set(true))
        def onSubscribe(s: Subscription) = ()
    end Sub

    val stream: Stream[Int, Any] = Stream.range(0, 5, 1)

    val attempts = 100

    def testStreamSubscription(clue: String)(program: Subscription => Unit)(using Frame, kyo.test.AssertScope): Unit < (Sync & Scope) =
        Loop(attempts) { index =>
            if index <= 0 then
                Loop.done
            else
                for
                    flag         <- AtomicBoolean.init(false)
                    subscription <- StreamSubscription.subscribe(stream, new Sub(flag))
                    _            <- Sync.defer(program(subscription))
                    fired        <- flag.get
                    _ = assert(!fired, clue)
                yield Loop.continue(index - 1)
            end if
        }

    "after subscription is canceled request must be NOOPs" in {
        testStreamSubscription(clue = "onNext was called after the subscription was canceled") { sub =>
            sub.cancel()
            sub.request(1)
            sub.request(1)
            sub.request(1)
        }.andThen(succeed("no subscriber callback fired after cancel across all attempts"))
    }

    "after subscription is canceled additional cancellations must be NOOPs" in {
        testStreamSubscription(clue = "onComplete was called after the subscription was canceled") {
            sub =>
                sub.cancel()
                sub.cancel()
        }.andThen(succeed("no subscriber callback fired after repeated cancel across all attempts"))
    }
end CancellationTest
