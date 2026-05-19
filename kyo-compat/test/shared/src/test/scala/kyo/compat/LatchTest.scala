package kyo.compat

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kyo.compat.*
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success
class LatchTest extends CompatTest:
    "init with n=0 releases immediately" in run {
        // A latch initialised at 0 is already released; await returns
        // immediately.
        val c =
            CLatch.init(0).flatMap { l =>
                l.await
            }
        c.map(r => assert(r == ((): Unit)))
    }
    "init normalizes a negative count to zero (already released)" in run {
        // A latch initialised with n < 0 is normalised to 0 (already-released);
        // await must return without throwing IllegalArgumentException and the
        // flag set after await must be true.
        val reached = new AtomicBoolean(false)
        val c =
            CLatch.init(-5).flatMap { l =>
                l.await.flatMap { _ =>
                    CIO.defer { reached.set(true) }
                }
            }
        c.map { _ =>
            assert(reached.get(), "await did not return (flag was never set)")
        }
    }
    "await blocks until the release count is reached" in run {
        // Latch(2): await must NOT complete after one release. Bound the
        // await with a 50ms timeout — expect None.
        val c =
            CLatch.init(2).flatMap { l =>
                l.release.flatMap { _ =>
                    CIO.timeout(50.millis)(l.await)
                }
            }
        c.map(r => assert(r == None))
    }
    "successive releases unblock await once the count drains" in run {
        // Latch(2): release twice, then await must complete.
        val c =
            CLatch.init(2).flatMap { l =>
                l.release.flatMap { _ =>
                    l.release.flatMap { _ =>
                        l.await
                    }
                }
            }
        c.map(r => assert(r == ((): Unit)))
    }
    "a concurrent release unblocks a pending await" in run {
        // Latch(1): a concurrent release + await must complete and the
        // observable value reflects both ran.
        val ctr = new AtomicInteger(0)
        val c =
            CLatch.init(1).flatMap { l =>
                val releaser =
                    CIO.sleep(20.millis).flatMap { _ =>
                        l.release.flatMap { _ =>
                            CIO.defer { val _ = ctr.incrementAndGet(); 0 }
                        }
                    }
                val awaiter =
                    l.await.flatMap { _ =>
                        CIO.defer { ctr.incrementAndGet() }
                    }
                CIO.zip(releaser, awaiter)
            }
        c.map { case (_, awaiterValue) =>
            // awaiter ran → ctr was incremented at least once after releaser
            // also incremented; final value depends on interleaving but must
            // be ≥ 1 from the awaiter's increment.
            assert(awaiterValue >= 1 && ctr.get >= 2)
        }
    }
    "multiple concurrent awaits all resolve on single release" in run {
        // latch.init(1). Spawn 5 CFibers each running latch.await.
        // After 50ms, call latch.release. All 5 fibers' get resolve within 300ms.
        val c =
            CLatch.init(1).flatMap { l =>
                val fibers = CIO.foreach(1 to 5)(_ => CFiber.init(l.await))
                fibers.flatMap { fiberList =>
                    CIO.sleep(50.millis).flatMap { _ =>
                        l.release.flatMap { _ =>
                            CIO.timeout(300.millis)(CIO.foreach(fiberList.lower)(_.get)).flatMap { result =>
                                CIO.defer(result)
                            }
                        }
                    }
                }
            }
        c.map { result =>
            assert(result.isDefined, s"not all 5 fibers resolved within 300ms: $result")
        }
    }

    "release called more times than count is a no-op; await resolves immediately" in run {
        // latch.init(1). Call latch.release × 3. No exception.
        // Then latch.await resolves immediately (within timeout).
        val c =
            CLatch.init(1).flatMap { l =>
                l.release.flatMap { _ =>
                    l.release.flatMap { _ =>
                        l.release.flatMap { _ =>
                            CIO.timeout(100.millis)(l.await)
                        }
                    }
                }
            }
        c.map { result =>
            assert(result == Some(()), s"await after extra releases should succeed immediately, got: $result")
        }
    }
    "CLatch lift/lower round-trip preserves observable behavior" in run {
        // lower the CLatch to its carrier and lift it back; the re-lifted
        // view must be behaviorally equivalent — release twice through it and
        // await must complete successfully.
        val c =
            CLatch.init(2).flatMap { original =>
                val relifted = CLatch.lift(original.lower)
                relifted.release.flatMap { _ =>
                    relifted.release.flatMap { _ =>
                        relifted.await.map(_ => succeed)
                    }
                }
            }
        c
    }

end LatchTest
