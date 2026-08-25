package kyo.net.internal

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.net.NetAddress
import kyo.net.Test

class ConnectionPoolTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    val key1 = NetAddress.Tcp("host1", 80)
    val key2 = NetAddress.Tcp("host2", 80)

    def mkPool(max: Int = 2): ConnectionPool[NetAddress, String] =
        // Infinity timeout: no reaper, and idle-age is never compared, so the ambient clock is immaterial here.
        Sync.Unsafe.evalOrThrow(ConnectionPool.init[NetAddress, String](max, kyo.Duration.Infinity, _ => true, _ => ()))

    "poll" - {
        "returns empty when no idle connections" in {
            val pool   = mkPool()
            val result = pool.poll(key1)
            assert(result == Maybe.empty)
        }

        "returns released connection" in {
            val pool = mkPool()
            pool.release(key1, "conn1")
            val result = pool.poll(key1)
            assert(result == Present("conn1"))
        }
    }

    "release" - {
        "discards when full" in {
            val discardCount = new AtomicInteger(0)
            val pool = Sync.Unsafe.evalOrThrow(ConnectionPool.init[NetAddress, String](
                2,
                kyo.Duration.Infinity,
                _ => true,
                _ => discard(discardCount.incrementAndGet())
            ))
            pool.release(key1, "a")
            pool.release(key1, "b")
            pool.release(key1, "c")
            assert(discardCount.get() == 1)
        }
    }

    "tryReserve" - {
        "returns true when under limit" in {
            val pool     = mkPool()
            val reserved = pool.tryReserve(key1)
            assert(reserved)
        }

        "returns false when at limit" in {
            val pool = mkPool(2)
            val r1   = pool.tryReserve(key1)
            assert(r1)
            val r2 = pool.tryReserve(key1)
            assert(r2)
            val r3 = pool.tryReserve(key1)
            assert(!r3)
        }
    }

    "unreserve" - {
        // Releasing an in-flight slot frees capacity so a subsequent tryReserve succeeds again.
        "frees a reserved slot so tryReserve succeeds again" in {
            val pool = mkPool(2)
            assert(pool.tryReserve(key1))
            assert(pool.tryReserve(key1))
            // At the limit now: the next reserve must fail.
            assert(!pool.tryReserve(key1))
            // Release one in-flight slot.
            pool.unreserve(key1)
            // Capacity freed: a reserve must now succeed.
            assert(pool.tryReserve(key1))
            // And we are at the limit again.
            assert(!pool.tryReserve(key1))
        }
    }

    "close" - {
        "returns idle connections" in {
            val pool = mkPool()
            pool.release(key1, "a")
            pool.release(key1, "b")
            val conns = pool.close()
            assert(conns.size == 2)
        }

        "returns empty when no idle connections" in {
            val pool  = mkPool()
            val conns = pool.close()
            assert(conns.size == 0)
        }
    }

    "isAlive check during poll" in {
        val discardCount = new AtomicInteger(0)
        val pool = Sync.Unsafe.evalOrThrow(ConnectionPool.init[NetAddress, String](
            2,
            kyo.Duration.Infinity,
            conn => conn != "dead",
            _ => discard(discardCount.incrementAndGet())
        ))
        pool.release(key1, "dead")
        pool.release(key1, "alive")
        val result = pool.poll(key1)
        assert(result == Present("alive"))
        assert(discardCount.get() == 1)
    }

    "idle-timeout eviction during poll" in {
        // Idle-age reads the withTimeControl-rebound clock, so advancing past the timeout evicts on the next poll, no sleep. The finite
        // timeout also spawns a reaper, but poll or reaper each discards a conn exactly once, so the counts hold regardless of interleaving.
        Clock.withTimeControl { tc =>
            val discardCount = new AtomicInteger(0)
            for
                pool <- ConnectionPool.init[NetAddress, String](
                    2,
                    100.millis,
                    _ => true,
                    _ => discard(discardCount.incrementAndGet())
                )
                _ = pool.release(key1, "stale1") // idle-start stamped at virtual time 0
                _ = pool.release(key1, "stale2")
                _ <- tc.advance(150.millis) // past the 100ms timeout
                result = pool.poll(key1) // both conns expired: poll evicts+discards each, finds none live
                _ <- Sync.defer(discard(pool.close())) // interrupt the reaper so it does not outlive the test
            yield
                assert(result == Maybe.empty)
                assert(discardCount.get() == 2)
            end for
        }
    }

    "reaper expires an idle connection with no further poll" in {
        // Without a reaper, a connection released and never polled again is never closed (an fd leak); a finite timeout spawns one that
        // closes it with no poll. On the withTimeControl clock, advancing past the timeout wakes it; a discard-callback latch fences.
        Clock.withTimeControl { tc =>
            val discardCount = AtomicInt.Unsafe.init(0)
            val reaped       = Latch.Unsafe.init(1)
            for
                pool <- ConnectionPool.init[NetAddress, String](
                    2,
                    100.millis,
                    _ => true,
                    _ =>
                        discard(discardCount.incrementAndGet())
                        reaped.release()
                )
                _ = pool.release(key1, "c") // idle-start stamped at virtual time 0
                _ <- tc.awaitPendingSleepers(1)        // the reaper has armed its first sleep
                _ <- tc.advance(150.millis)            // past the 100ms timeout: the reaper wakes and evicts
                _ <- reaped.safe.await                 // fence on the discard
                _ <- Sync.defer(discard(pool.close())) // interrupt the reaper so it does not outlive the test
            yield assert(
                discardCount.get() == 1,
                s"reaper must close the idle connection exactly once with no poll; discardCount=${discardCount.get()}"
            )
            end for
        }
    }

    "reaper expires idle connections across multiple host pools" in {
        // The sweep iterates every host pool, so an idle connection is closed regardless of its host. Release one to each
        // of two hosts, never poll, and assert the reaper closes both. On virtual time; a latch counting both discards fences.
        Clock.withTimeControl { tc =>
            val discardCount = AtomicInt.Unsafe.init(0)
            val reaped       = Latch.Unsafe.init(2)
            for
                pool <- ConnectionPool.init[NetAddress, String](
                    2,
                    100.millis,
                    _ => true,
                    _ =>
                        discard(discardCount.incrementAndGet())
                        reaped.release()
                )
                _ = pool.release(key1, "a") // idle-start stamped at virtual time 0
                _ = pool.release(key2, "b")
                _ <- tc.awaitPendingSleepers(1) // the reaper has armed its first sleep
                _ <- tc.advance(150.millis)     // past the 100ms timeout: one sweep evicts both hosts
                _ <- reaped.safe.await          // fence on both discards
                _ <- Sync.defer(discard(pool.close()))
            yield assert(
                discardCount.get() == 2,
                s"reaper must close both hosts' idle connections; discardCount=${discardCount.get()}"
            )
            end for
        }
    }

    "release that observes close mid-publish disposes the connection, never orphans it (fd-leak race regression, CI #1837)" in {
        // The shared-transport fd leak: release(key, conn) passes its `closed` check, then close() runs (drains every host
        // pool, sets closed, clears the map). Release then re-creates a host pool via computeIfAbsent and publishes into a
        // ring nothing else will ever drain, so the connection's socket is never closed (the CI dump: pendingCloses=0, recv
        // still armed). The raceProbe seam fires close() in exactly that window, so the interleaving is deterministic on
        // every platform. The connection must still be disposed exactly once.
        val discardCount = AtomicInt.Unsafe.init(0)
        val pool =
            Sync.Unsafe.evalOrThrow(
                ConnectionPool.init[NetAddress, String](2, kyo.Duration.Infinity, _ => true, _ => discard(discardCount.incrementAndGet()))
            )
        pool.raceProbe = () => discard(pool.close())
        pool.release(key1, "c")
        assert(
            discardCount.get() == 1,
            s"the connection must be disposed exactly once (1); got ${discardCount.get()} (0 = orphaned/leaked)"
        )
    }

    "close drains concurrently with releases without orphaning or double-disposing (concurrency smoke)" in {
        // The linearizable-drain path under real preemption: `n` concurrent releases race one close on a ring sized to hold
        // them, so close catches slots mid-publish and its drain must spin over them rather than stop. Every released
        // connection ends up extracted by close or discarded, exactly once. This also guards the drain's spin against
        // deadlock under contention. JS has no in-method preemption, so it passes by construction.
        val n         = 32
        val scenarios = 1000
        val expected  = (0 until n).map("c" + _).toSet
        Loop(0) { s =>
            if s >= scenarios then Loop.done(assert(true))
            else
                val discarded = AtomicRef.Unsafe.init(Chunk.empty[String])
                val pool =
                    Sync.Unsafe.evalOrThrow(ConnectionPool.init[NetAddress, String](
                        n,
                        kyo.Duration.Infinity,
                        _ => true,
                        c => discard(discarded.updateAndGet(_ :+ c))
                    ))
                for
                    latch     <- Latch.init(1)
                    releasers <- Fiber.init(Async.foreach(0 until n, n)(j => latch.await.map(_ => Sync.defer(pool.release(key1, "c" + j)))))
                    closer    <- Fiber.init(latch.await.map(_ => Sync.defer(pool.close())))
                    _         <- latch.release
                    _         <- releasers.get
                    extracted <- closer.get
                yield
                    // Exactly-once per connection: every released id lands in exactly one of the two sets, none orphaned, none doubled.
                    val ext = extracted.toArray.toSet
                    val dis = discarded.get().toArray.toSet
                    if (ext ++ dis) == expected && ext.intersect(dis).isEmpty then Loop.continue(s + 1)
                    else
                        Loop.done(assert(
                            (ext ++ dis) == expected && ext.intersect(dis).isEmpty,
                            s"scenario $s: exactly-once violated. orphaned=${expected -- ext -- dis}, double-disposed=${ext.intersect(dis)}"
                        ))
                    end if
                end for
        }
    }

end ConnectionPoolTest
