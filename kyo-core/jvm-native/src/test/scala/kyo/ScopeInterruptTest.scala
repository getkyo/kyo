package kyo

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean as JAtomicBoolean

/** Scope's behaviour when an interrupt lands inside an acquire, and when a scope exits over a child still holding a resource.
  *
  * jvm-native rather than shared: pinning the moment an interrupt lands inside the acquire's own step needs the acquiring worker held
  * there until the interrupt has been sent, and a held worker has no meaning on the single-threaded JS runtime. The hold is a plain JDK
  * handshake on purpose, bounded, and it stands for what the acquire really is: the user's opaque side effect, a socket or a file being
  * opened, running while an interrupt arrives from outside. The scheduler is adaptive, so a worker parked this way is compensated.
  *
  * A release that runs through a fiber's abandonment is asynchronous, so each test waits on a latch the release opens, bounded by a
  * live-clock timeout. That bound is a failure detector only: a `Timeout` is the evidence that the release never ran, and no assertion
  * passes because of elapsed time.
  */
class ScopeInterruptTest extends kyo.test.Test[Any]:

    /** A closeable whose close is observable, standing in for a file or a socket. */
    final class Handle extends java.lang.AutoCloseable:
        val closed            = new JAtomicBoolean(false)
        def close(): Unit     = closed.set(true)
        def isClosed: Boolean = closed.get()
    end Handle

    /** Holds this worker until the parent says it has sent the interrupt, so the interrupt is pending when the step completes. */
    def untilInterrupted(sent: JAtomicBoolean): Unit =
        val deadline = java.lang.System.nanoTime() + 5.seconds.toNanos
        while !sent.get() && java.lang.System.nanoTime() < deadline do Thread.onSpinWait()

    "an interrupt landing while the acquire's last step runs" - {

        // The control: Sync's bracket builds its region as the acquire is applied, so an abandonment
        // that finds the acquired value has something to release it with.
        "Sync.acquireReleaseWith still releases what the acquire produced" in {
            val inAcquire     = new CountDownLatch(1)
            val interruptSent = new JAtomicBoolean(false)
            for
                acquired <- AtomicInt.init(0)
                released <- AtomicInt.init(0)
                relDone  <- Latch.init(1)
                fiber <- Fiber.initUnscoped {
                    Sync.acquireReleaseWith {
                        acquired.incrementAndGet.andThen(Sync.defer {
                            inAcquire.countDown()
                            untilInterrupted(interruptSent)
                            "token"
                        })
                    }(_ => released.incrementAndGet.unit.andThen(relDone.release))(_ => Sync.defer(()))
                }
                _   <- Sync.defer(discard(inAcquire.await(5, TimeUnit.SECONDS)))
                _   <- fiber.interrupt
                _   <- Sync.defer(interruptSent.set(true))
                res <- fiber.getResult
                out <- Abort.run[Timeout](Async.timeout(3.seconds)(relDone.await))
                a   <- acquired.get
                r   <- released.get
            yield
                assert(res.isPanic, s"$res")
                assert(a == 1)
                assert(out.isSuccess && r == 1, s"acquired $a, released $r ($out)")
            end for
        }

        // Scope used to register its finalizer in a suspension that follows the acquire, so an
        // interrupt pending when the acquire completed parked the eval before that registration was
        // dispatched and the acquired value was never released. The finalizer now goes in first and
        // a bracket records what the acquire produced, which is atomic with the acquire's own exit.
        "Scope.acquireRelease releases what the acquire produced" in {
            val inAcquire     = new CountDownLatch(1)
            val interruptSent = new JAtomicBoolean(false)
            for
                acquired <- AtomicInt.init(0)
                released <- AtomicInt.init(0)
                relDone  <- Latch.init(1)
                fiber <- Fiber.initUnscoped {
                    Scope.run {
                        Scope.acquireRelease {
                            acquired.incrementAndGet.andThen(Sync.defer {
                                inAcquire.countDown()
                                untilInterrupted(interruptSent)
                                "token"
                            })
                        }(_ => released.incrementAndGet.unit.andThen(relDone.release)).andThen(Sync.defer(()))
                    }
                }
                _   <- Sync.defer(discard(inAcquire.await(5, TimeUnit.SECONDS)))
                _   <- fiber.interrupt
                _   <- Sync.defer(interruptSent.set(true))
                res <- fiber.getResult
                out <- Abort.run[Timeout](Async.timeout(3.seconds)(relDone.await))
                a   <- acquired.get
                r   <- released.get
            yield
                assert(res.isPanic, s"$res")
                assert(a == 1)
                assert(out.isSuccess && r == 1, s"acquired $a, released $r: the value the acquire produced was never released ($out)")
            end for
        }

        "Scope.acquire closes the handle it opened" in {
            val inAcquire     = new CountDownLatch(1)
            val interruptSent = new JAtomicBoolean(false)
            val handle        = new Handle
            for
                fiber <- Fiber.initUnscoped {
                    Scope.run {
                        Scope.acquire {
                            Sync.defer {
                                inAcquire.countDown()
                                untilInterrupted(interruptSent)
                                handle
                            }
                        }.andThen(Sync.defer(()))
                    }
                }
                _   <- Sync.defer(discard(inAcquire.await(5, TimeUnit.SECONDS)))
                _   <- fiber.interrupt
                _   <- Sync.defer(interruptSent.set(true))
                res <- fiber.getResult
                out <- Abort.run[Timeout](Async.timeout(3.seconds)(Loop.foreach {
                    Sync.defer(handle.isClosed).map(c => if c then Loop.done else Async.sleep(10.millis).andThen(Loop.continue))
                }))
            yield
                assert(res.isPanic, s"$res")
                assert(out.isSuccess && handle.isClosed, s"the opened handle was never closed ($out)")
            end for
        }
    }

    // Fiber.init registers `_.interrupt`, which signals the child and returns, so the scope's exit
    // does not wait for what the child still holds. The child is held past the parent's own record
    // of the exit, so the order below is the one the scope actually produces rather than a race.
    "Scope.run waits for a scoped fiber to release the bracket it is inside".pendingUntilFixed(
        "Fiber.init registers an interrupt rather than an awaited release, so the scope exits while the child still holds its resource"
    ) in {
        val flag = new JAtomicBoolean(false)
        for
            log      <- AtomicRef.init(Chunk.empty[String])
            started  <- Latch.init(1)
            released <- Latch.init(1)
            _ <- Scope.run {
                Fiber.init {
                    Sync.ensure(log.updateAndGet(_.append("released")).andThen(released.release)) {
                        started.release.andThen(Sync.defer(untilInterrupted(flag))).andThen(Sync.defer(()))
                    }
                }.andThen(started.await)
            }
            _   <- log.updateAndGet(_.append("scope exited"))
            _   <- Sync.defer(flag.set(true))
            _   <- released.await
            seq <- log.get
        yield assert(seq == Chunk("released", "scope exited"), s"order was $seq")
        end for
    }

end ScopeInterruptTest
