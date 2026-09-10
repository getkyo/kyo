package kyo.scheduler

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kyo.*
import kyo.kernel.internal.*

class IOTaskTest extends kyo.test.Test[Any]:

    "fiberTrace" - {

        "renders the live user frame of a blocked effectful fiber" in {
            val blocker                      = new IOPromise[Nothing, Unit]()
            def userStep(x: Int): Int < Sync = Sync.defer(x + 1)
            def work: Unit < Async =
                Sync.defer(1).map(userStep).map(_ => Async.use(blocker)(_ => ())).map(_ => ())
            val iotask = IOTask.detached(work)
            for
                // Deterministic readiness witness: poll the actual property (the live trace surfacing a
                // user frame), not a sleep. The remainder is written back at the suspend boundary, so a
                // populated trace also proves the fiber is parked on `blocker`.
                _ <- assertEventually(Sync.defer(iotask.fiberTrace().contains("IOTaskTest.scala:")))
                rendered = iotask.fiberTrace()
                _ <- Sync.defer(blocker.completeDiscard(Result.succeed(())))
                _ <- Async.use(iotask.asInstanceOf[IOPromise[Nothing, Unit]])(_ => ())
            yield
                assert(rendered.startsWith("at "), s"the trace does not render as a frame: $rendered")
                // A real user file:line from this test's effect chain, proving the live (not fork-time)
                // frame is readable cross-thread off a still-parked fiber.
                assert(rendered.contains("IOTaskTest.scala:"), s"the trace names no user frame: $rendered")
            end for
        }

        "renders no kernel frame" in {
            val blocker                      = new IOPromise[Nothing, Unit]()
            def userStep(x: Int): Int < Sync = Sync.defer(x + 1)
            def work: Unit < Async =
                Sync.defer(1).map(userStep).map(_ => Async.use(blocker)(_ => ())).map(_ => ())
            val iotask = IOTask.detached(work)
            for
                _ <- assertEventually(Sync.defer(iotask.fiberTrace().nonEmpty))
                rendered = iotask.fiberTrace()
                _ <- Sync.defer(blocker.completeDiscard(Result.succeed(())))
                _ <- Async.use(iotask.asInstanceOf[IOPromise[Nothing, Unit]])(_ => ())
            yield
                // `currentFrame` drops `Frame.internal` by reference, so what renders is a call site the
                // user wrote, never a file the kernel owns.
                assert(!rendered.contains("<internal>"), s"the internal placeholder surfaced: $rendered")
                List("Eval.scala", "Arrow.scala", "IOTask.scala", "Fiber.scala").foreach { internal =>
                    assert(!rendered.contains(internal), s"a kernel frame surfaced: $rendered")
                }
            end for
        }

        "renders the deferral a Sync.defer spin loop stands in" in {
            val stop                      = new AtomicBoolean(false)
            val iterations                = new AtomicLong(0L)
            def loop(i: Int): Unit < Sync = Sync.defer { discard(iterations.incrementAndGet()); if stop.get() then () else loop(i + 1) }
            for
                fiber <- Fiber.initUnscoped(loop(0))
                // Deterministic witness that the loop is genuinely spinning (many bare defers executed)
                // before reading its trace, so what is read is an active fiber's frame.
                _ <- assertEventually(Sync.defer(iterations.get() > 100L))
                // fiberTrace lives on IOTask; reach the concrete task for the diagnostic read.
                rendered = fiber.asInstanceOf[IOTask[?, ?, ?]].fiberTrace()
                _ <- Sync.defer(stop.set(true))
                _ <- fiber.interrupt
                _ <- fiber.getResult
            yield
                // A spinning fiber stands at no suspension, so the frame comes from the deferral in front
                // of it: the arrow that applies a `Sync.defer` body names where the user wrote it.
                assert(rendered.contains("IOTaskTest.scala:"), s"the spin loop named no user frame: $rendered")
                assert(rendered.contains("IOTaskTest.loop"), s"the frame is not the spinning deferral: $rendered")
            end for
        }

        "never throws while the fiber it reads is running" in {
            val blocker                      = new IOPromise[Nothing, Unit]()
            def userStep(x: Int): Int < Sync = Sync.defer(x + 1)
            def work: Unit < Async =
                Sync.defer(1).map(userStep).map(_ => Async.use(blocker)(_ => ())).map(_ => ())
            val iotask = IOTask.detached(work)
            for
                // A forked reader hammers fiberTrace() while the worker rewrites `curr`: the fiber parks
                // (a frame to render), resumes (the remainder is replaced), then completes (`curr` is
                // cleared). Every one of those cross-thread reads has to stay safe.
                reader <- Fiber.initUnscoped(Sync.defer((0 until 2000).map(_ => iotask.fiberTrace()).toVector))
                _      <- Sync.defer(blocker.completeDiscard(Result.succeed(())))
                reads  <- reader.get
                _      <- Async.use(iotask.asInstanceOf[IOPromise[Nothing, Unit]])(_ => ())
                // After the task is definitely complete `curr` is cleared, so every later read is "".
                afterComplete = (0 until 1000).map(_ => iotask.fiberTrace()).toVector
            yield
                assert(reads.size == 2000)
                assert(reads.forall(s => (s ne null) && (s == "" || s.startsWith("at "))), "a read rendered a malformed frame")
                assert(afterComplete.forall(_ == ""), "a completed task still rendered a frame")
            end for
        }

        "has no effect row and is a plain String" in {
            val iotask = IOTask.detached(Sync.defer(()))
            // Compile-shaped assertion: fiberTrace() is a bare String, with no pending effect row and no
            // AllowUnsafe capability. If it returned `String < Sync` or required AllowUnsafe this would not
            // typecheck.
            val s: String = iotask.fiberTrace()
            for _ <- Async.use(iotask.asInstanceOf[IOPromise[Nothing, Unit]])(_ => ())
            yield assert(s == "" || s.startsWith("at "))
        }

    }

    "fatal error in a guarded body" - {
        // A fatal error must still run the computation's finalizers: here one completes `probe`, standing in
        // for a promise awaited elsewhere, so a skipped finalizer expires the await below.
        //
        // InternalError because the scheduler gates on `IsFatal`, which counts only `VirtualMachineError` and
        // `ControlThrowable`. JVM-only: it relies on one worker taking the fatal while the timeout fires on
        // another, which the single-worker Native and single-threaded JS runtimes do not provide.
        "runs the ensure finalizer even though the fatal aborts the fiber".onlyJvm in {
            for
                probe <- Promise.init[Unit, Any]
                _ <- Fiber.initUnscoped {
                    Sync.ensure { probe.completeDiscard(Result.succeed(())) } {
                        Sync.defer[Unit, Any](throw new InternalError("fatal error"))
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
