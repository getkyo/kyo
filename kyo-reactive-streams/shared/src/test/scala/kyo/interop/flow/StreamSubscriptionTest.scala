package kyo.interop.flow

import java.util.concurrent.Flow.Subscriber
import java.util.concurrent.Flow.Subscription
import kyo.*

final class StreamSubscriptionTest extends kyo.test.Test[Any]:
    import StreamSubscriptionTest.*

    "stopped before it is consumed, it still tells its subscriber" in {
        // The stage where nothing else could possibly signal: onSubscribe has been delivered, so the subscriber is waiting for a terminal
        // event, and no drain fiber exists to send one. Ending the request channel is not enough here, because nobody is polling it.
        for
            terminated <- Promise.init[String, Any]
            subscriber = new Recorder(terminated)
            subscription <- newSubscription(subscriber)
            _            <- subscription.subscribe
            _            <- Sync.defer(subscription.stop())
            signal       <- terminated.get
        yield assert(signal == "onError")
        end for
    }

    "stopped before onSubscribe, it still gives the subscriber a legal ending" in {
        // A subscriber may not be handed a terminal event it cannot attribute to a subscription, so a stop at this stage owes it both
        // signals, in this order.
        for
            terminated <- Promise.init[String, Any]
            subscriber = new Recorder(terminated)
            subscription <- newSubscription(subscriber)
            _            <- Sync.defer(subscription.stop())
            _            <- terminated.get
            recorded     <- Sync.defer(subscriber.recorded)
        yield assert(recorded == Seq("onSubscribe", "onComplete"))
        end for
    }

    "stopped while consuming, it signals exactly once however many times it is stopped" in {
        for
            terminated <- Promise.init[String, Any]
            subscriber = new Recorder(terminated)
            subscription <- newSubscription(subscriber)
            _            <- subscription.subscribe
            fiber        <- subscription.consume
            _            <- Sync.defer(subscription.stop())
            _            <- Sync.defer(subscription.stop())
            _            <- terminated.get
            _            <- fiber.getResult
            recorded     <- Sync.defer(subscriber.recorded)
        yield assert(recorded.count(isTerminal) == 1)
        end for
    }

    "a subscriber that cancelled is left in silence" in {
        // The specification lets a publisher stop signalling once the SUBSCRIBER cancels. That silence must survive the machinery that
        // guarantees a signal to everyone else.
        for
            terminated <- Promise.init[String, Any]
            subscriber = new Recorder(terminated)
            subscription <- newSubscription(subscriber)
            _            <- subscription.subscribe
            fiber        <- subscription.consume
            _            <- Sync.defer(subscription.cancel())
            result       <- fiber.getResult
            recorded     <- Sync.defer(subscriber.recorded)
        yield
            val endedAsCancelled = result match
                case Result.Failure(StreamSubscription.StreamCanceled) => true
                case _                                                 => false
            assert(endedAsCancelled && recorded == Seq("onSubscribe"))
        end for
    }
end StreamSubscriptionTest

object StreamSubscriptionTest:

    private def isTerminal(signal: String): Boolean = signal == "onComplete" || signal == "onError"

    private def newSubscription(subscriber: Subscriber[Int])(using Frame): StreamSubscription[Int, Any] < Sync =
        Sync.Unsafe.defer(new StreamSubscription[Int, Any](Stream.range(0, 16, 1), subscriber))

    /** Records every signal in arrival order and completes `terminated` with the first terminal one. */
    final private class Recorder(terminated: Promise[String, Any]) extends Subscriber[Int]:
        import AllowUnsafe.embrace.danger

        private val signals = new java.util.concurrent.atomic.AtomicReference(Seq.empty[String])

        def recorded: Seq[String] = signals.get()

        private def record(signal: String): Unit =
            discard(signals.updateAndGet(_ :+ signal))
            if isTerminal(signal) then
                discard(Sync.Unsafe.evalOrThrow(terminated.unsafe.completeDiscard(Result.succeed(signal))))
        end record

        def onSubscribe(subscription: Subscription): Unit = record("onSubscribe")
        def onNext(value: Int): Unit                      = record("onNext")
        def onComplete(): Unit                            = record("onComplete")
        def onError(error: Throwable): Unit               = record("onError")
    end Recorder
end StreamSubscriptionTest
