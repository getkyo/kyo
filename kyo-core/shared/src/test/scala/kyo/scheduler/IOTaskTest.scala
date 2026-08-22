package kyo.scheduler

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kyo.*
import kyo.kernel.internal.*

class IOTaskTest extends kyo.test.Test[Any]:

    "cleanup barriers" - {
        "linearizes registration before close and drains in LIFO order" in {
            val barriers                      = IOTask.CleanupBarriers.init()
            val seen                          = scala.collection.mutable.ArrayBuffer.empty[Int]
            val first: IOTask.CleanupBarrier  = _ => Sync.defer(seen += 1).unit
            val second: IOTask.CleanupBarrier = _ => Sync.defer(seen += 2).unit

            assert(barriers.add(first))
            assert(barriers.add(second))
            assert(barriers.size == 2)
            val drained = barriers.close(Absent).toIndexed
            assert(drained.size == 2)
            assert(drained(0) eq second)
            assert(drained(1) eq first)
            assert(barriers.size == 0)
            assert(!barriers.add(first))
            assert(barriers.close(Absent).isEmpty)
        }

        "removes a normally completed barrier without retention" in {
            val barriers                       = IOTask.CleanupBarriers.init()
            val barrier: IOTask.CleanupBarrier = _ => ()
            assert(barriers.add(barrier))
            barriers.remove(barrier)
            assert(barriers.size == 0)
            assert(barriers.close(Absent).isEmpty)
        }

        "register and close race has one linearized owner" in {
            Kyo.foreachDiscard(0 until 1000) { _ =>
                for
                    start      <- Promise.init[Unit, Any]
                    registered <- Promise.init[Boolean, Any]
                    drained    <- Promise.init[Chunk[IOTask.CleanupBarrier], Any]
                    barriers                       = IOTask.CleanupBarriers.init()
                    barrier: IOTask.CleanupBarrier = _ => ()
                    registerFiber <-
                        Fiber.initUnscoped(start.get.andThen(Sync.defer(registered.completeDiscard(Result.succeed(barriers.add(barrier))))))
                    closeFiber <-
                        Fiber.initUnscoped(start.get.andThen(Sync.defer(drained.completeDiscard(Result.succeed(barriers.close(Absent))))))
                    _           <- start.completeUnit
                    didRegister <- registered.get
                    closed      <- drained.get
                    _           <- registerFiber.getResult
                    _           <- closeFiber.getResult
                yield
                    assert(didRegister == closed.contains(barrier))
                    assert(barriers.size == 0)
                end for
            }
        }
    }

    "interrupted completion" - {
        "keeps terminal visibility pending until nested Scope cleanup completes" in {
            for
                entered        <- Promise.init[Unit, Any]
                cleanupStarted <- Promise.init[Unit, Any]
                releaseCleanup <- Promise.init[Unit, Any]
                interruptSeen  <- Promise.init[Unit, Any]
                completionSeen <- Promise.init[Unit, Any]
                lateCompletion <- Promise.init[Unit, Any]
                syncFinalized  <- kyo.AtomicBoolean.init(false)
                fiber <- Fiber.initUnscoped {
                    Sync.ensure(syncFinalized.set(true)) {
                        Scope.run {
                            Scope.ensure(cleanupStarted.completeUnitDiscard.andThen(releaseCleanup.get))
                                .andThen(entered.completeUnitDiscard)
                                .andThen(Async.never[Unit])
                        }
                    }
                }
                _              <- fiber.onInterrupt(_ => interruptSeen.completeUnitDiscard)
                _              <- fiber.onComplete(_ => completionSeen.completeUnitDiscard)
                _              <- entered.get
                interrupted    <- fiber.interrupt
                _              <- interruptSeen.get
                _              <- cleanupStarted.get
                pollBefore     <- fiber.poll
                doneBefore     <- fiber.done
                getWaiter      <- Fiber.initUnscoped(fiber.get)
                getBefore      <- getWaiter.poll
                callbackBefore <- completionSeen.poll
                syncBefore     <- syncFinalized.get
                _              <- fiber.onComplete(_ => lateCompletion.completeUnitDiscard)
                lateBefore     <- lateCompletion.poll
                _              <- releaseCleanup.completeUnit
                _              <- completionSeen.get
                _              <- lateCompletion.get
                _              <- assertEventually(getWaiter.done)
                result         <- fiber.poll
                getAfter       <- getWaiter.poll
                waiters        <- fiber.waiters
            yield
                assert(interrupted)
                assert(pollBefore.isEmpty)
                assert(!doneBefore)
                assert(getBefore.isEmpty)
                assert(callbackBefore.isEmpty)
                assert(syncBefore)
                assert(lateBefore.isEmpty)
                assert(result.exists(_.isPanic))
                assert(getAfter.exists(_.isPanic))
                assert(waiters == 0)
            end for
        }

        "repeated and concurrent interrupts publish one terminal callback" in {
            for
                entered        <- Promise.init[Unit, Any]
                cleanupStarted <- Promise.init[Unit, Any]
                releaseCleanup <- Promise.init[Unit, Any]
                fiber <- Fiber.initUnscoped(Scope.run {
                    Scope.ensure(cleanupStarted.completeUnitDiscard.andThen(releaseCleanup.get))
                        .andThen(entered.completeUnitDiscard)
                        .andThen(Async.never)
                })
                completions     <- AtomicInt.init(0)
                interrupts      <- AtomicInt.init(0)
                _               <- fiber.onComplete(_ => completions.incrementAndGet.unit)
                _               <- fiber.onInterrupt(_ => interrupts.incrementAndGet.unit)
                _               <- entered.get
                attempts        <- Async.gather((0 until 32).map(_ => fiber.interrupt))
                _               <- cleanupStarted.get
                _               <- releaseCleanup.completeUnit
                _               <- fiber.getResult
                completionCount <- completions.get
                interruptCount  <- interrupts.get
            yield
                assert(attempts.count(identity) == 1)
                assert(completionCount == 1)
                assert(interruptCount == 1)
            end for
        }
    }

    "fiberTrace" - {

        "fiberTrace renders the live user frames of a blocked effectful fiber" in {
            val blocker                      = new IOPromise[Nothing, Unit]()
            def userStep(x: Int): Int < Sync = Sync.defer(x + 1)
            def work: Unit < Async =
                Sync.defer(1).map(userStep).map(_ => Async.use(blocker)(_ => ())).map(_ => ())
            val iotask = IOTask(work, Trace.saved(), Context.empty)
            for
                // Deterministic readiness witness: poll the actual property (the live trace surfacing a
                // user frame), not a sleep. The trace is published at the suspend boundary's writeback, so
                // a populated trace also proves the fiber is blocked on `blocker` and its trace is stable.
                _ <- assertEventually(Sync.defer(iotask.fiberTrace().contains("IOTaskTest.scala:")))
                rendered = iotask.fiberTrace()
                _ <- Sync.defer(blocker.completeDiscard(Result.succeed(())))
                _ <- Async.use(iotask.asInstanceOf[IOPromise[Nothing, Unit]])(_ => ())
            yield
                assert(rendered.nonEmpty)
                assert(rendered.startsWith("at "))
                // A real user file:line from this test's effect chain, proving the live (not fork-time)
                // frames are readable cross-thread off a still-blocked fiber.
                assert(rendered.contains("IOTaskTest.scala:"))
                assert(!rendered.contains("<internal>"))
            end for
        }

        "fiberTrace excludes internal frames" in {
            val blocker                      = new IOPromise[Nothing, Unit]()
            def userStep(x: Int): Int < Sync = Sync.defer(x + 1)
            def work: Unit < Async =
                Sync.defer(1).map(userStep).map(_ => Async.use(blocker)(_ => ())).map(_ => ())
            val iotask = IOTask(work, Trace.saved(), Context.empty)
            for
                _ <- assertEventually(Sync.defer(iotask.fiberTrace().contains("IOTaskTest.scala:")))
                rendered = iotask.fiberTrace()
                _ <- Sync.defer(blocker.completeDiscard(Result.succeed(())))
                _ <- Async.use(iotask.asInstanceOf[IOPromise[Nothing, Unit]])(_ => ())
            yield
                // The trace carries real user frames (non-empty) yet never the shared internal placeholder:
                // pushFrame drops Frame.internal by reference, so no <internal> line can enter the ring.
                assert(rendered.nonEmpty)
                assert(!rendered.contains("<internal>"))
            end for
        }

        "fiberTrace is empty for a pure Sync.defer spin loop" in {
            val stop                      = new AtomicBoolean(false)
            val iterations                = new AtomicLong(0L)
            def loop(i: Int): Unit < Sync = Sync.defer { discard(iterations.incrementAndGet()); if stop.get() then () else loop(i + 1) }
            for
                fiber <- Fiber.initUnscoped(loop(0))
                // Deterministic witness that the loop is genuinely spinning (many bare defers executed)
                // before reading its trace, so the empty-trace assertion is about an active fiber.
                _ <- assertEventually(Sync.defer(iterations.get() > 100L))
                // fiberTrace lives on IOTask; reach the concrete task for the diagnostic read.
                rendered = fiber.asInstanceOf[IOTask[?, ?, ?]].fiberTrace()
                _ <- Sync.defer(stop.set(true))
                _ <- fiber.interrupt
                _ <- fiber.getResult
            yield
                // A pure Sync.defer chain pushes no frames (defers carry no user frame; the IOTask's own
                // call-site frame is Frame.internal and is skipped), so the live trace stays empty.
                assert(!rendered.contains("IOTaskTest.scala:"))
                assert(rendered.isEmpty)
            end for
        }

        "fiberTrace never throws under concurrent trace mutation" in {
            val blocker                      = new IOPromise[Nothing, Unit]()
            def userStep(x: Int): Int < Sync = Sync.defer(x + 1)
            def work: Unit < Async =
                Sync.defer(1).map(userStep).map(_ => Async.use(blocker)(_ => ())).map(_ => ())
            val iotask = IOTask(work, Trace.saved(), Context.empty)
            for
                // A forked reader hammers fiberTrace() while the worker mutates the trace: the fiber is
                // blocked (populated trace), then resumes (writeback), then completes (run() nulls trace).
                // Every cross-thread read must stay safe; the diagnostic read never escapes a throw.
                reader <- Fiber.initUnscoped(Sync.defer((0 until 2000).map(_ => iotask.fiberTrace()).toVector))
                _      <- Sync.defer(blocker.completeDiscard(Result.succeed(())))
                reads  <- reader.get
                _      <- Async.use(iotask.asInstanceOf[IOPromise[Nothing, Unit]])(_ => ())
                // After the task is definitely complete its trace is nulled, so every later read is "".
                afterComplete = (0 until 1000).map(_ => iotask.fiberTrace()).toVector
            yield
                assert(reads.size == 2000)
                assert(reads.forall(s => (s ne null) && (s == "" || s.startsWith("at "))))
                assert(afterComplete.forall(_ == ""))
            end for
        }

        "fiberTrace has no effect row and is a plain String" in {
            val iotask = IOTask(Sync.defer(()), Trace.init, Context.empty)
            // Compile-shaped assertion: fiberTrace() is a bare String, with no pending effect row and no
            // AllowUnsafe capability. If it returned `String < Sync` or required AllowUnsafe this would not
            // typecheck.
            val s: String = iotask.fiberTrace()
            for _ <- Async.use(iotask.asInstanceOf[IOPromise[Nothing, Unit]])(_ => ())
            yield assert(s == "" || s.startsWith("at "))
        }

    }

    "fatal error in a guarded body" - {
        // A computation on a scheduler fiber throws a fatal error. IOTask completes the fiber's own promise with a
        // Panic, but must still run the computation's finalizers; here a finalizer completes `probe`, standing in
        // for a promise awaited elsewhere. If finalizers are skipped on the fatal path, `probe` is never completed
        // and the timed await below expires. LinkageError is used because scala.util.control.NonFatal (which the
        // scheduler gates on) classifies it as fatal. JVM-only: it relies on worker-thread semantics (one worker
        // taking the fatal while the timeout fires on another), which the single-worker Native and single-threaded
        // JS runtimes do not provide.
        "runs the ensure finalizer even though the fatal aborts the fiber".onlyJvm in {
            for
                probe <- Promise.init[Unit, Any]
                _ <- Fiber.initUnscoped {
                    Sync.ensure { probe.completeDiscard(Result.succeed(())) } {
                        Sync.defer[Unit, Any](throw new LinkageError("fatal error"))
                    }
                }
                // Await only `probe`, never the fatal fiber's own result: awaiting a fatally-aborted fiber re-raises
                // the fatal here. Bounded so a missing completion fails fast rather than blocking indefinitely.
                finished <- Abort.run[Any](Async.timeout(5.seconds)(probe.get))
            yield assert(
                finished.isSuccess,
                "the Sync.ensure finalizer did not run when the guarded body threw a fatal error; the awaited promise was never completed"
            )
        }
    }

end IOTaskTest
