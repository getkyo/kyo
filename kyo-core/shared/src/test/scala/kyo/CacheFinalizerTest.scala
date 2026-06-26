package kyo

import java.util.concurrent.atomic.AtomicInteger
import kyo.AllowUnsafe.embrace.danger
import kyo.internal.Platform

class CacheFinalizerTest extends kyo.test.Test[Any]:

    override def timeout = if Platform.isNative then 60.seconds else 30.seconds

    "finalizer fires once per size-evicted value" in {
        Scope.run:
            // Use a small capacity (4) so inserting a 5th entry forces size-eviction.
            // A signal channel carries each finalized value so the test waits on the
            // drainer without a sleep.
            Channel.initUnscoped[Int](8).map: signal =>
                Cache.initWithFinalizer[Int, Int](maxSize = 4)(v => signal.put(v)).map: cache =>
                    for
                        _ <- cache.add(1, 100)
                        _ <- cache.add(2, 200)
                        _ <- cache.add(3, 300)
                        _ <- cache.add(4, 400)
                        // 5th insert forces a size-eviction; one of the four values is evicted.
                        _ <- cache.add(5, 500)
                        // The drainer enqueues the evicted value; take it from the signal.
                        evicted <- signal.take
                        // The evicted value must be one of the four original values.
                        _ = assert(
                            evicted == 100 || evicted == 200 || evicted == 300 || evicted == 400,
                            s"unexpected evicted value: $evicted"
                        )
                    yield ()
    }

    "finalizer fires once per expiry-reclaimed value" in {
        // The safe initWithFinalizer uses Clock.live internally and does not accept a
        // clock parameter. To exercise the expiry path with a controlled clock, the
        // unsafe store is used directly: onExpire fires synchronously inside the
        // reclaiming add call, so an AtomicInteger counter is the correct witness (no
        // sleep, no async wait needed).
        Clock.withTimeControl: tc =>
            for
                clock <- Clock.get
                expiredCount = new AtomicInteger(0)
                store = Cache.Unsafe.init[Int, Int](
                    maxSize = 8,
                    expireAfterAccess = 10.seconds,
                    clock = clock.unsafe,
                    onExpire = (_, _) => discard(expiredCount.incrementAndGet())
                )
                _ = store.add(42, 999)
                // Advance the controlled clock past expireAfterAccess.
                _ <- tc.advance(11.seconds, Duration.Zero)
                // A reclaiming add to the same key probes the expired slot and fires onExpire.
                _ = store.add(42, 888)
                _ = assert(expiredCount.get() == 1, s"expected exactly 1 expiry, got ${expiredCount.get()}")
            yield ()
    }

    "finalizer fires once on explicit remove" in {
        Scope.run:
            Channel.initUnscoped[Int](4).map: signal =>
                Cache.initWithFinalizer[Int, Int](maxSize = 8)(v => signal.put(v)).map: cache =>
                    for
                        _ <- cache.add(7, 777)
                        _ <- cache.remove(7)
                        // The drainer signals after running the finalizer on the removed value.
                        removed <- signal.take
                        _ = assert(removed == 777, s"expected 777, got $removed")
                    yield ()
    }

    "close finalizes every remaining entry then stops accepting" in {
        Scope.run:
            Channel.initUnscoped[Int](16).map: signal =>
                Cache.initWithFinalizer[Int, Int](maxSize = 8)(v => signal.put(v)).map: cache =>
                    for
                        _ <- cache.add(1, 10)
                        _ <- cache.add(2, 20)
                        _ <- cache.add(3, 30)
                        // Explicit close with default grace period finalizes all three live entries.
                        _ <- cache.close
                        // Collect the three finalized values (order is not defined).
                        v1 <- signal.take
                        v2 <- signal.take
                        v3 <- signal.take
                        collected = Chunk(v1, v2, v3).sorted
                        _         = assert(collected == Chunk(10, 20, 30), s"expected Chunk(10, 20, 30), got $collected")
                    yield ()
    }

    "a finalizer failure logs-and-continues" in {
        Scope.run:
            // One channel per value: failSignal for value A (which triggers a failure),
            // successSignal for value B (which must succeed despite A's failure).
            Channel.initUnscoped[Int](4).map: failSignal =>
                Channel.initUnscoped[Int](4).map: successSignal =>
                    Cache.initWithFinalizer[Int, Int](maxSize = 8): v =>
                        if v == 111 then
                            // Signal attempt then abort: the drainer must log and continue.
                            failSignal.put(v).andThen(Abort.fail(new RuntimeException("finalizer error")))
                        else
                            successSignal.put(v)
                    .map: cache =>
                        for
                            // Insert A (fails) and B (succeeds) and close to drain both.
                            _ <- cache.add(0, 111)
                            _ <- cache.add(1, 222)
                            _ <- cache.close
                            // Both finalizer attempts must have fired.
                            failed  <- failSignal.take
                            success <- successSignal.take
                            // close returned without surfacing A's failure; both callbacks ran.
                            _ = assert(failed == 111, s"expected failed value 111, got $failed")
                            _ = assert(success == 222, s"expected success value 222, got $success")
                        yield ()
    }

    "closeNow uses zero grace and Cache.init is unaffected" in {
        Scope.run:
            // A separate plain Cache.init cache that must remain fully functional
            // after the finalizer-bearing cache is closed via closeNow.
            Cache.init[Int, Int](maxSize = 8).map: plainCache =>
                Cache.initWithFinalizer[Int, Int](maxSize = 8)(_ => ()).map: finalizerCache =>
                    for
                        _ <- plainCache.add(100, 1000)
                        _ <- plainCache.add(200, 2000)
                        _ <- finalizerCache.add(10, 111)
                        _ <- finalizerCache.add(20, 222)
                        // closeNow must complete without hanging; zero grace means immediate
                        // drainer interruption after queue-close. "Finalized count == 1 each"
                        // is not asserted because the drainer may be interrupted before processing
                        // all entries: only deterministic post-conditions are checked here.
                        _ <- finalizerCache.closeNow
                        // The plain Cache.init is completely unaffected: it still serves entries.
                        v100 <- plainCache.get(100)
                        v200 <- plainCache.get(200)
                        _ = assert(v100 == Maybe(1000), s"expected Maybe(1000), got $v100")
                        _ = assert(v200 == Maybe(2000), s"expected Maybe(2000), got $v200")
                    yield ()
    }

    "every evicted value is finalized exactly once under backpressure (no drops)" in {
        Scope.run:
            val N = 64
            // releaseLatch starts at 1: all finalizers await until the test calls release.
            // Once released (count reaches 0), subsequent awaits return immediately, so all
            // 64 finalizers proceed after a single release call.
            // completionLatch starts at N: each finalizer calls release once it records its
            // value; await on it ensures all N finalizations are done before asserting.
            Latch.init(1).map: releaseLatch =>
                Latch.init(N).map: completionLatch =>
                    // counts(i) tracks how many times value i was finalized; all must be 1.
                    val counts = Array.fill(N)(new AtomicInteger(0))
                    Cache.initWithFinalizer[Int, Int](maxSize = 4): v =>
                        // Park until the test releases the latch, then record and signal.
                        releaseLatch.await.andThen:
                            Sync.Unsafe.defer:
                                discard(counts(v).incrementAndGet())
                            .andThen(completionLatch.release)
                    .map: cache =>
                        for
                            // Insert N distinct values with the finalizer parked on the latch.
                            // Evictions accumulate in the unbounded queue undrained: the drainer
                            // is blocked on the first finalizer's releaseLatch.await.
                            _ <- Kyo.foreachDiscard((0 until N).toList)(i => cache.add(i, i))
                            // Release the latch: all pending and future finalizers proceed.
                            _ <- releaseLatch.release
                            // Close the cache: snapshots the 4 live entries into the queue and
                            // waits for the drainer to finish (via drainer.get in close).
                            _ <- cache.close
                            // All N finalizations are now complete (drainer.get in close waited).
                            _ <- completionLatch.await
                            // Every value must appear exactly once: no drops, no double-fires.
                            _ = assert(
                                counts.forall(_.get() == 1),
                                s"finalization counts must all be 1; got: ${counts.zipWithIndex.filter(_._1.get() != 1).map { case (c, i) =>
                                        s"value=$i count=${c.get()}"
                                    }.mkString(", ")}"
                            )
                            _ = assert(
                                counts.map(_.get()).sum == N,
                                s"expected total finalizations $N, got ${counts.map(_.get()).sum}"
                            )
                        yield ()
    }

end CacheFinalizerTest
