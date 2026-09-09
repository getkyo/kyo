package kyo

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** `Sync.ensure`'s guarantee when an interrupt lands on the step that produces the body's outcome.
  *
  * jvm-native rather than shared for the reason `ScopeInterruptTest` gives: pinning that moment needs the worker held inside the body's
  * own step until the interrupt has been sent, and a held worker has no meaning on the single-threaded JS runtime.
  */
class SyncInterruptTest extends kyo.test.Test[Any]:

    def untilInterrupted(sent: AtomicBoolean): Unit =
        // Unsafe: this is the opaque side effect itself, a synchronous hold on the worker, so it cannot
        // suspend to read through the effectful tier.
        import AllowUnsafe.embrace.danger
        val deadline = java.lang.System.nanoTime() + 5.seconds.toNanos
        while !sent.unsafe.get() && java.lang.System.nanoTime() < deadline do Thread.onSpinWait()
    end untilInterrupted

    // Cleanup that always occurs, for a computation that never got a slice. Holds only while nothing
    // deferred sits above the region, since the abandonment walk stops at one.
    "Sync.ensure runs its finalizer for a fiber abandoned before its first slice" in {
        Async.foreachDiscard(1 to 20, 20) { _ =>
            for
                ran <- AtomicInt.init(0)
                p   <- Promise.init[Int, Any]
                fiber <- Fiber.initUnscoped {
                    import AllowUnsafe.embrace.danger
                    Sync.ensure(Sync.Unsafe.defer(discard(ran.unsafe.incrementAndGet())))(p.get)
                }
                _   <- fiber.interrupt
                _   <- fiber.getResult
                out <- Abort.run[Timeout](Async.timeout(5.seconds)(assertEventually(ran.get.map(_ == 1))))
                c   <- ran.get
            yield assert(out.isSuccess && c == 1, s"the finalizer ran $c times")
            end for
        }.andThen(assert(true))
    }

    "an interrupt landing as the body produces its outcome" - {

        "still runs the finalizer" in {
            val inBody = new CountDownLatch(1)
            for
                interruptSent <- AtomicBoolean.init(false)
                ran           <- AtomicInt.init(0)
                done          <- Latch.init(1)
                fiber <- Fiber.initUnscoped {
                    Sync.ensure(ran.incrementAndGet.unit.andThen(done.release)) {
                        Sync.defer {
                            inBody.countDown()
                            untilInterrupted(interruptSent)
                            42
                        }
                    }
                }
                _   <- Sync.defer(discard(inBody.await(5, TimeUnit.SECONDS)))
                _   <- fiber.interrupt
                _   <- interruptSent.set(true)
                res <- fiber.getResult
                out <- Abort.run[Timeout](Async.timeout(3.seconds)(done.await))
                r   <- ran.get
            yield
                assert(res.isPanic, s"$res")
                assert(out.isSuccess && r == 1, s"the finalizer ran $r times ($out)")
            end for
        }

        "runs the finalizer exactly once when the body aborts" in {
            val inBody = new CountDownLatch(1)
            for
                interruptSent <- AtomicBoolean.init(false)
                ran           <- AtomicInt.init(0)
                done          <- Latch.init(1)
                fiber <- Fiber.initUnscoped {
                    Abort.run[String] {
                        Sync.ensure(ran.incrementAndGet.unit.andThen(done.release)) {
                            Sync.defer {
                                inBody.countDown()
                                untilInterrupted(interruptSent)
                            }.andThen(Abort.fail("boom"))
                        }
                    }
                }
                _   <- Sync.defer(discard(inBody.await(5, TimeUnit.SECONDS)))
                _   <- fiber.interrupt
                _   <- interruptSent.set(true)
                _   <- fiber.getResult
                out <- Abort.run[Timeout](Async.timeout(3.seconds)(done.await))
                r   <- ran.get
            yield assert(out.isSuccess && r == 1, s"the finalizer ran $r times ($out)")
            end for
        }
    }

end SyncInterruptTest
