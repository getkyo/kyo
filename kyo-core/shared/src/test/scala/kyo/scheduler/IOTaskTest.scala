package kyo.scheduler

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kyo.*
import kyo.kernel.internal.*

class IOTaskTest extends kyo.test.Test[Any]:

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

end IOTaskTest
