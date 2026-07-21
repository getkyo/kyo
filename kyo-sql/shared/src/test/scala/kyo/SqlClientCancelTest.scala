package kyo

import kyo.*
import kyo.Test

/** Unit tests for G-Leak-4: `cancelMysql` sidecar socket closed on every exit edge.
  *
  * Strategy: the `cancelMysql` fix wraps the sidecar lifecycle in `Scope.run { Scope.acquireRelease(connect)(_.closeNow) }`. These tests
  * verify that the Kyo structural property holds, `closeNow` is called on every exit edge (cancelTimeout fires, fiber interrupt), without
  * requiring a live MySQL server.
  *
  * A fake "connection" is represented by an `AtomicBoolean` (`closed` flag). The acquire step sets `closed = false`; the release step
  * (`closeNow` analogue) sets `closed = true`. After the timeout or interrupt the test asserts `closed == true`.
  *
  * Test count: 2 shared/unit leaves.
  */
class MysqlCancelLeakTest extends Test:

    // ── cancelTimeout during kill closes sidecar conn ────────────────────────

    /** Verifies that `Scope.run { Scope.acquireRelease(connect)(closeNow) }` calls `closeNow` when a timeout interrupts the computation
      * after the sidecar is acquired.
      *
      * BEFORE the fix: `close` lived in the `flatMap` chain; a timeout between `connect` and `close` left the sidecar open. AFTER the fix:
      * `Scope.acquireRelease` guarantees `closeNow` runs on every scope exit edge, including timeout-triggered interruption.
      */
    "cancelTimeout during kill closes sidecar conn" in {
        AtomicBoolean.initWith(false) { closed =>
            // Simulate: acquire a sidecar resource that hangs during `kill`.
            // The computation inside Scope.run suspends indefinitely (Async.sleep(Infinity))
            // so the timeout fires while the scope is still live. Scope.ensure must then invoke
            // the release (closeNow analogue), setting `closed = true`.
            val acquire: Unit < Async =
                closed.set(false)

            val closeNow: Unit < Sync =
                closed.set(true)

            val killHangs: Unit < (Async & Abort[SqlException]) =
                Async.sleep(Duration.Infinity).andThen(())

            val doCancel: Unit < (Async & Abort[SqlException] & Scope) =
                Scope.acquireRelease(acquire.andThen(()))(_ => closeNow).flatMap { _ =>
                    killHangs
                }

            val timed: Unit < (Async & Abort[SqlException] & Scope) =
                Async.timeoutWithError(
                    50.millis,
                    Result.Failure(SqlException.Connection("cancel timed out (test)", summon[Frame]))
                )(doCancel)

            Abort.run[SqlException](Scope.run(timed)).flatMap { _ =>
                // Give the Scope cleanup a moment to run, it fires asynchronously after the
                // timeout interrupt propagates through Scope's ensure callbacks.
                Async.sleep(100.millis).andThen {
                    closed.get.map { wasClosed =>
                        assert(wasClosed, "G-Leak-4 regression: closeNow was NOT called after cancelTimeout fired")
                    }
                }
            }
        }
    }

    // ── fiber interrupt during cancel closes sidecar conn ─────────────────────

    /** Verifies that `Scope.run { Scope.acquireRelease(connect)(closeNow) }` calls `closeNow` when the running fiber is interrupted from
      * the outside while the sidecar is held.
      *
      * BEFORE the fix: `close` lived in the `flatMap` chain; an external interrupt bypassed it. AFTER the fix: `Scope.run` finalizers run
      * on fiber cancellation, triggering `closeNow`.
      */
    "fiber interrupt during cancel closes sidecar conn" in {
        AtomicBoolean.initWith(false) { closed =>
            // Latch: signals that the sidecar has been "acquired" and the computation is now
            // suspended in `killHangs`. The external interrupter waits for this before
            // interrupting so the interrupt always lands while the scope is live.
            Latch.initWith(1) { sidecarAcquired =>
                val acquire: Unit < Async =
                    closed.set(false)

                val closeNow: Unit < Sync =
                    closed.set(true)

                val killHangs: Unit < (Async & Abort[SqlException]) =
                    sidecarAcquired.release.andThen(Async.sleep(Duration.Infinity).andThen(()))

                val doCancel: Unit < (Async & Abort[SqlException] & Scope) =
                    Scope.acquireRelease(acquire.andThen(()))(_ => closeNow).flatMap { _ =>
                        killHangs
                    }

                // Run the cancel computation in a background fiber so we can interrupt it.
                Fiber.initUnscoped(
                    Abort.run[SqlException](Scope.run(doCancel))
                ).flatMap { cancelFiber =>
                    // Wait until the sidecar is acquired and kill is in flight.
                    sidecarAcquired.await.andThen {
                        // Interrupt the cancel fiber from outside (simulates caller cancellation).
                        cancelFiber.interrupt.flatMap { interrupted =>
                            assert(interrupted, "Expected cancel fiber to be interrupted")
                            // Give the Scope cleanup a moment to propagate.
                            Async.sleep(100.millis).andThen {
                                closed.get.map { wasClosed =>
                                    assert(wasClosed, "G-Leak-4 regression: closeNow was NOT called after fiber interrupt")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end MysqlCancelLeakTest
