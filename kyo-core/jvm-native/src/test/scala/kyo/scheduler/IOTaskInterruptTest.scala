package kyo.scheduler

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kyo.*

class IOTaskInterruptTest extends kyo.test.Test[Any]:

    private def flaggedBlocked(thread: Thread): Boolean =
        (thread ne null) && Scheduler.get.status().workers.exists(w => (w ne null) && w.isBlocked && w.mount == thread.getName)

    "needsInterrupt" - {
        "is false for a fiber that completed on its own" in {
            for
                fiber <- Fiber.initUnscoped(42)
                v     <- fiber.get
            yield
                assert(v == 42)
                assert(!fiber.unsafe.asInstanceOf[Task].needsInterrupt())
        }
    }

    "the blocking monitor" - {
        "interrupts the blocking call of an interrupted fiber" in {
            val entered     = new CountDownLatch(1)
            val interrupted = new AtomicBoolean(false)
            for
                fiber <- Fiber.initUnscoped {
                    Sync.defer {
                        entered.countDown()
                        try new CountDownLatch(1).await()
                        catch case _: InterruptedException => interrupted.set(true)
                    }
                }
                _ <- assertEventually(Sync.defer(entered.getCount() == 0))
                _ <- fiber.interrupt
                _ <- assertEventually(Sync.defer(interrupted.get()))
                r <- fiber.getResult
            yield assert(r.isPanic)
            end for
        }
        "leaves no interrupt behind for the finalizers of the fiber it interrupted" in {
            val entered         = new CountDownLatch(1)
            val finalizerFailed = new AtomicBoolean(false)
            val finalized       = new CountDownLatch(1)
            for
                fiber <- Fiber.initUnscoped {
                    Sync.ensure {
                        Sync.defer {
                            // A latch at zero waits for nothing, so this throws only for a flag already set.
                            try new CountDownLatch(0).await()
                            catch case _: InterruptedException => finalizerFailed.set(true)
                            finalized.countDown()
                        }
                    } {
                        Sync.defer {
                            entered.countDown()
                            // LockSupport.park returns on Thread.interrupt and keeps the flag set, as NIO channels do.
                            while !Thread.currentThread().isInterrupted() do java.util.concurrent.locks.LockSupport.park()
                        }
                    }
                }
                _ <- assertEventually(Sync.defer(entered.getCount() == 0))
                _ <- fiber.interrupt
                _ <- assertEventually(Sync.defer(finalized.getCount() == 0))
                r <- fiber.getResult
            yield
                assert(r.isPanic)
                assert(!finalizerFailed.get(), "the finalizer's blocking call failed on the interrupt left set for the body")
            end for
        }
        "does not interrupt a completion callback of a fiber nobody interrupted" in {
            val entered     = new CountDownLatch(1)
            val release     = new CountDownLatch(1)
            val interrupted = new AtomicBoolean(false)
            val carrier     = new AtomicReference[Thread](null)
            for
                gate  <- Latch.init(1)
                fiber <- Fiber.initUnscoped(gate.await.andThen(42))
                _     <- fiber.onComplete { _ =>
                    Sync.defer {
                        carrier.set(Thread.currentThread())
                        entered.countDown()
                        try release.await()
                        catch case _: InterruptedException => interrupted.set(true)
                    }
                }
                _ <- gate.release
                _ <- assertEventually(Sync.defer(entered.getCount() == 0))
                // The callback runs on the fiber's own worker, inside the slice that completed it.
                _ <- assertEventually(Sync.defer(interrupted.get() || flaggedBlocked(carrier.get())))
                // A scan dispatches in the same pass that flags the worker blocked; two more scans leave no doubt.
                scans = Scheduler.get.blockingMonitor.cycles
                _ <- assertEventually(Sync.defer(interrupted.get() || Scheduler.get.blockingMonitor.cycles >= scans + 2))
                _ <- Sync.defer(release.countDown())
                v <- fiber.get
            yield
                assert(v == 42)
                assert(!interrupted.get(), "the callback's blocking call was interrupted")
            end for
        }
    }

end IOTaskInterruptTest
