package kyo.compat

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kyo.compat.*
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success

// Local error sentinels for acquireReleaseWith/ensure failure-path tests.
case object AcquireErr extends Exception("acquire-err")
case object ReleaseErr extends Exception("release-err")
case object UseErr     extends Exception("use-err")
case object CleanupErr extends Exception("cleanup-err")

class BracketEnsureTest extends CompatTest:

    // ----- acquireReleaseWith -----

    "acquireReleaseWith runs release on successful use" in run {
        val released = new AtomicInteger(0)
        val c: CIO[Int] =
            CIO.acquireReleaseWith(CIO.unit)(_ =>
                CIO.defer { val _ = released.incrementAndGet() }
            )(_ => CIO.defer { 7 })
        c.map { v =>
            assert(v == 7 && released.get == 1)
        }
    }

    "acquireReleaseWith passes the acquired value to release" in run {
        // The release receives the same value acquire returned. Use a sentinel.
        val seen = new java.util.concurrent.atomic.AtomicReference[String]("unset")
        val c: CIO[Int] =
            CIO.acquireReleaseWith(CIO.defer("sentinel-42"))(a => CIO.defer { seen.set(a) })(_ => CIO.defer { 1 })
        c.map { v =>
            assert(v == 1 && seen.get == "sentinel-42")
        }
    }

    "acquireReleaseWith runs release when use fails" in run {
        val released = new AtomicInteger(0)
        val c: CIO[Int] =
            CIO.acquireReleaseWith(CIO.unit)(_ =>
                CIO.defer { val _ = released.incrementAndGet() }
            )(_ => CIO.fail(TestError("nope")))
        c.liftToTry.map {
            case Failure(e: TestError) if e.msg == "nope" => assert(released.get == 1)
            case other => fail(s"expected Failure(TestError(\"nope\")) with released=1, got: $other (released=${released.get})")
        }
    }

    "acquireReleaseWith does not run acquire until the CIO is materialized" in run {
        // Constructing a acquireReleaseWith CIO must not run acquire — acquire fires
        // only when the CIO materializes.
        val acquired = new AtomicInteger(0)
        val c: CIO[Int] =
            CIO.acquireReleaseWith(CIO.defer { acquired.incrementAndGet(); 0 })(_ => CIO.unit)(_ => CIO.defer { 9 })
        // Pre-materialization: acquire should not have run.
        val pre = acquired.get
        c.map { v =>
            assert(pre == 0 && v == 9 && acquired.get == 1)
        }
    }

    "acquireReleaseWith runs release when use throws synchronously" in run {
        // `use` throws inside its body via CIO.defer; release must still
        // run because acquireReleaseWith's release is unconditional on success/failure.
        val released = new AtomicInteger(0)
        val c: CIO[Any] =
            CIO.acquireReleaseWith(CIO.unit)(_ =>
                CIO.defer { val _ = released.incrementAndGet() }
            )(_ =>
                CIO.defer {
                    throw new RuntimeException("use-throws")
                }
            )
        c.liftToTry.map {
            case Failure(t: RuntimeException) =>
                assert(t.getMessage == "use-throws" && released.get == 1)
            case other => fail(s"expected Failure(RuntimeException) with released=1, got: $other (released=${released.get})")
        }
    }

    // ----- ensure -----

    "ensure runs the cleanup on success" in run {
        val ran = new AtomicInteger(0)
        val c: CIO[Int] =
            CIO.ensure(CIO.defer { val _ = ran.incrementAndGet() })(CIO.defer { 11 })
        c.map { v =>
            assert(v == 11 && ran.get == 1)
        }
    }

    "ensure runs the cleanup on failure" in run {
        val ran = new AtomicInteger(0)
        val c: CIO[Int] =
            CIO.ensure(CIO.defer { val _ = ran.incrementAndGet() })(CIO.fail(TestError("bad")))
        c.liftToTry.map {
            case Failure(e: TestError) if e.msg == "bad" => assert(ran.get == 1)
            case other => fail(s"expected Failure(TestError(\"bad\")) with ran=1, got: $other (ran=${ran.get})")
        }
    }

    "ensure returns the inner computation's value" in run {
        val c: CIO[Int] =
            CIO.ensure(CIO.unit)(CIO.defer { 99 })
        c.map { v =>
            assert(v == 99)
        }
    }

    "ensure propagates the inner computation's failure" in run {
        val c: CIO[Int] =
            CIO.ensure(CIO.unit)(CIO.fail(TestError("kept")))
        c.liftToTry.map {
            case Failure(e: TestError) if e.msg == "kept" => succeed
            case other                                    => fail(s"expected Failure(TestError(\"kept\")), got: $other")
        }
    }

    "ensure runs the cleanup exactly once" in run {
        val ran = new AtomicInteger(0)
        val c: CIO[Int] =
            CIO.ensure(CIO.defer { val _ = ran.incrementAndGet() })(CIO.defer { 5 })
        c.map { v =>
            assert(v == 5 && ran.get == 1)
        }
    }

    "nested acquireReleaseWiths thread values and run both releases" in run {
        // Compose two acquireReleaseWiths via `flatMap`. Both releases must run; both
        // values must thread.
        val releasedOuter = new AtomicInteger(0)
        val releasedInner = new AtomicInteger(0)
        val outer: CIO[Int] =
            CIO.acquireReleaseWith(CIO.defer("outer"))(_ =>
                CIO.defer { val _ = releasedOuter.incrementAndGet() }
            )(_ => CIO.defer { 1 })
        val composed: CIO[Int] =
            outer.flatMap { o =>
                val inner: CIO[Int] =
                    CIO.acquireReleaseWith(CIO.defer("inner"))(_ =>
                        CIO.defer { val _ = releasedInner.incrementAndGet() }
                    )(_ => CIO.defer { o + 2 })
                inner
            }
        composed.map { v =>
            assert(v == 3 && releasedOuter.get == 1 && releasedInner.get == 1)
        }
    }
    "acquireReleaseWith acquire fails → release does not run" in run {
        val counter = new AtomicInteger(0)
        val c: CIO[Int] =
            CIO.acquireReleaseWith(CIO.fail(AcquireErr))(_ =>
                CIO.defer { val _ = counter.incrementAndGet() }
            )(_ => CIO.value(0))
        c.liftToTry.map {
            case Failure(e) if e eq AcquireErr =>
                assert(counter.get == 0, s"release ran but should not have; counter=${counter.get}")
            case other => fail(s"expected Failure(AcquireErr) with counter==0, got: $other (counter=${counter.get})")
        }
    }

    "acquireReleaseWith both use and release fail → use's error wins" in run {
        val c: CIO[Int] =
            CIO.acquireReleaseWith(CIO.value("a"))(_ => CIO.fail(ReleaseErr))(_ => CIO.fail(UseErr))
        c.liftToTry.map {
            case Failure(e) if e eq UseErr => succeed
            case other                     => fail(s"expected Failure(UseErr) (use's error takes precedence), got: $other")
        }
    }

    "acquireReleaseWith async release sequences correctly" in run {
        val counter  = new AtomicInteger(0)
        val observed = new AtomicInteger(-1)
        // use observes the counter BEFORE release fires (counter still 0)
        // after acquireReleaseWith completes, counter == 1
        val c: CIO[Unit] =
            CIO.acquireReleaseWith(CIO.value("a"))(_ =>
                CIO.sleep(100.millis).flatMap(_ => CIO.defer { val _ = counter.incrementAndGet(); () })
            )(_ => CIO.defer { observed.set(counter.get); () })
        c.map { _ =>
            assert(counter.get == 1, s"release did not complete; counter=${counter.get}")
            assert(observed.get == 0, s"use observed counter post-release; observed=${observed.get}")
        }
    }

    "acquireReleaseWith release runs exactly once on synchronous use-throw" in run {
        val counter = new AtomicInteger(0)
        val c: CIO[Any] =
            CIO.acquireReleaseWith(CIO.value("a"))(_ =>
                CIO.defer { val _ = counter.incrementAndGet() }
            )(_ => CIO.defer { throw new RuntimeException("sync-throw") })
        c.liftToTry.map {
            case Failure(_: RuntimeException) =>
                assert(counter.get == 1, s"expected release exactly once, got counter=${counter.get}")
            case other => fail(s"expected Failure(RuntimeException), got: $other (counter=${counter.get})")
        }
    }

    "acquireReleaseWith use returns the use-computation's value" in run {
        val c: CIO[String] =
            CIO.acquireReleaseWith(CIO.value("a"))(_ => CIO.value(()))(s => CIO.value(s + "!"))
        c.map { v =>
            assert(v == "a!", s"expected \"a!\" but got: $v")
        }
    }

    "acquireReleaseWith runs release when use is cut short by a timeout" in run {
        // `use` parks on `proceed` so it cannot finish before the timeout, `release` fulfills
        // `releaseDone` which the test waits on, and `proceed.succeed` afterwards unblocks the
        // orphaned `use` on bindings without cancellation.
        val released = new AtomicBoolean(false)
        CPromise.init[Unit].flatMap { proceed =>
            CPromise.init[Unit].flatMap { releaseDone =>
                val bracket: CIO[Unit] =
                    CIO.acquireReleaseWith(CIO.unit)(_ =>
                        CIO.defer { val _ = released.set(true) }.flatMap(_ => releaseDone.succeed(()).unit)
                    )(_ => proceed.get)
                CIO.timeout(100.millis)(bracket).flatMap { result =>
                    proceed.succeed(()).flatMap { _ =>
                        releaseDone.get.map { _ =>
                            assert(result.isEmpty, s"expected timeout (None), got: $result")
                            assert(released.get, "release did not run after use was cut short by the timeout")
                        }
                    }
                }
            }
        }
    }

    "ensure runs the cleanup when the computation is cut short by a timeout" in run {
        val ran = new AtomicBoolean(false)
        CPromise.init[Unit].flatMap { proceed =>
            CPromise.init[Unit].flatMap { cleanupDone =>
                val guarded: CIO[Unit] =
                    CIO.ensure(
                        CIO.defer { val _ = ran.set(true) }.flatMap(_ => cleanupDone.succeed(()).unit)
                    )(proceed.get)
                CIO.timeout(100.millis)(guarded).flatMap { result =>
                    proceed.succeed(()).flatMap { _ =>
                        cleanupDone.get.map { _ =>
                            assert(result.isEmpty, s"expected timeout (None), got: $result")
                            assert(ran.get, "cleanup did not run after the computation was cut short by the timeout")
                        }
                    }
                }
            }
        }
    }

end BracketEnsureTest
