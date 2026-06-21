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

    "a mid-drain cancel with large outstanding demand stops emission and never completes" in {
        // Regression for StreamSubscription.loopPoll: with a large outstanding demand the drain loop must observe
        // cancel() and stop, raising StreamCanceled, instead of pulling the rest of the stream (calling onNext past
        // cancellation) and then delivering a terminal onComplete while leaving the consumer fiber running. The
        // consumer fiber is awaited directly so the outcome is deterministic, not timing-dependent.
        import AllowUnsafe.embrace.danger
        val chunkSize = 16
        val total     = 100000
        val bigStream = Stream.range(0, total, 1, chunkSize)

        val onNextCount   = new java.util.concurrent.atomic.AtomicInteger(0)
        val completedFlag = new java.util.concurrent.atomic.AtomicBoolean(false)
        val capturedSub   = new java.util.concurrent.atomic.AtomicReference[Subscription]()
        val subscriber = new Subscriber[Int]:
            def onSubscribe(s: Subscription): Unit = capturedSub.set(s)
            def onNext(t: Int): Unit =
                discard(onNextCount.incrementAndGet())
                // The first delivered element cancels mid-drain, while demand is still outstanding.
                val s = capturedSub.get()
                if s != null then s.cancel()
            end onNext
            def onComplete(): Unit          = completedFlag.set(true)
            def onError(e: Throwable): Unit = ()

        for
            sub    <- Sync.Unsafe.defer(new StreamSubscription[Int, Any](bigStream, subscriber))
            _      <- sub.subscribe
            fiber  <- sub.consume
            _      <- Sync.defer(sub.request(Long.MaxValue))
            result <- fiber.getResult
        yield
            assert(result.isFailure, s"consumer must end in StreamCanceled (a Failure), got: $result")
            assert(!completedFlag.get(), "onComplete must not be delivered after a mid-drain cancel")
            assert(
                onNextCount.get() < total,
                s"emission must stop after cancel, not drain all $total elements (emitted ${onNextCount.get()})"
            )
        end for
    }
end CancellationTest
