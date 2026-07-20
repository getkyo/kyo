package kyo.test.runner

import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import kyo.*
import kyo.scheduler.Scheduler
import kyo.test.runner.internal.LeakCheck
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** Validates the three end-of-run leak probes: the descriptor diff logic ([[LeakCheck.fdLeaks]] / [[LeakCheck.benignFd]], pure so it runs on
  * every platform) plus a Linux-only `/proc/self/fd` enumeration check, a deliberately leaked spinning fiber, and a deliberately leaked
  * non-daemon thread, confirming each signal clears after cleanup. Plain ScalaTest (not self-hosted via kyo-test) so the leaks can be driven
  * and torn down from outside any fiber.
  */
class LeakCheckTest extends AnyFunSuite with NonImplicitAssertions:

    private def minLoad(samples: Int): Double =
        var min = Double.MaxValue
        var i   = 0
        while i < samples do
            Thread.sleep(20)
            val l = LeakCheck.loadAvg()
            if l < min then min = l
            i += 1
        end while
        min
    end minLoad

    /** Polls `cond` every 10ms up to `timeoutMs`, returning whether it became true. Used to wait for a fiber to reach an observable scheduler
      * state instead of guessing with a fixed sleep, so the scheduler probe test does not race fiber startup.
      */
    private def awaitTrue(timeoutMs: Long)(cond: => Boolean): Boolean =
        val deadline = java.lang.System.nanoTime() + timeoutMs * 1_000_000L
        var ok       = cond
        while !ok && java.lang.System.nanoTime() < deadline do
            Thread.sleep(10)
            ok = cond
        ok
    end awaitTrue

    /** Runs the fiber probe of [[LeakCheck.detect]] (the `IdleResult.Busy` branch) with all other categories off, returning the
      * leak report. Short idle budget so it returns a `Busy` verdict promptly while a deliberately busy worker is running; the dump it
      * assembles iterates `Scheduler.get.busyFiberTraces()`.
      */
    private def detectFiberReport(allowlist: Chunk[String]): Maybe[String] =
        LeakCheck.detect(
            LeakCheck.baseline(),
            allowlist,
            checkFibers = true,
            checkThreads = false,
            checkFileDescriptors = false,
            checkSockets = false,
            idleBudgetNanos = 200_000_000L,
            settleNanos = 20_000_000L,
            pollNanos = 5_000_000L,
            fdDrainBudgetNanos = 200_000_000L
        )
    end detectFiberReport

    test("benignFd excludes classpath/library/JVM-internal targets, keeps sockets/pipes/files") {
        assert(LeakCheck.benignFd("/home/u/.ivy2/cache/io.getkyo/kyo-core.jar"))
        assert(LeakCheck.benignFd("/usr/lib/x86_64-linux-gnu/libc.so.6"))
        assert(LeakCheck.benignFd("/dev/urandom"))
        assert(LeakCheck.benignFd("/proc/self/fd"))
        assert(LeakCheck.benignFd("anon_inode:[eventpoll]"))
        assert(!LeakCheck.benignFd("socket:[5]"))
        assert(!LeakCheck.benignFd("pipe:[7]"))
        assert(!LeakCheck.benignFd("/tmp/data.txt"))
    }

    test("fdLeaks reports only new, non-benign, non-allowlisted descriptors") {
        val baseline = Set("socket:[1]", "/app/lib/foo.jar", "pipe:[2]")
        val current = Set(
            "socket:[1]",             // in baseline -> not a leak (e.g. the sbt.ForkMain socket)
            "/app/lib/foo.jar",       // baseline jar
            "/app/lib/new.jar",       // new but benign (classloader jar)
            "anon_inode:[eventpoll]", // new but benign (JVM epoll)
            "/dev/random",            // new but benign
            "socket:[99]",            // NEW socket -> leak
            "/tmp/leaked.txt",        // NEW file -> leak
            "/tmp/excused.txt"        // NEW file but allowlisted
        )
        val leaks = LeakCheck.fdLeaks(baseline, current, Chunk("excused"))
        assert(leaks.toSet == Set("socket:[99]", "/tmp/leaked.txt"), s"got $leaks")
    }

    test("fdLeaksForCategories keeps only the enabled descriptor categories") {
        val leaks = Chunk("socket:[99]", "/tmp/leaked.txt", "pipe:[7]")
        // both categories on: every leak kept
        assert(LeakCheck.fdLeaksForCategories(leaks, checkSockets = true, checkFileDescriptors = true).toSet == leaks.toSet)
        // sockets off (e.g. BaseHttpTest): socket dropped, non-socket descriptors still reported
        assert(
            LeakCheck.fdLeaksForCategories(leaks, checkSockets = false, checkFileDescriptors = true).toSet ==
                Set("/tmp/leaked.txt", "pipe:[7]")
        )
        // file descriptors off: only the socket remains
        assert(LeakCheck.fdLeaksForCategories(leaks, checkSockets = true, checkFileDescriptors = false).toSet == Set("socket:[99]"))
        // both off: nothing reported
        assert(LeakCheck.fdLeaksForCategories(leaks, checkSockets = false, checkFileDescriptors = false).isEmpty)
    }

    test("awaitFdDrain drops a descriptor that closes within the budget") {
        // leaksNow reports the socket on the first two samples, then empty: an async deferred close that completes mid-window.
        var n = 0
        val out = LeakCheck.awaitFdDrain(
            () =>
                val r = if n < 2 then Chunk("socket:[42]") else Chunk.empty
                n += 1
                r
            ,
            budgetNanos = 1_000_000_000L,
            settleNanos = 1_000_000L
        )
        assert(out.isEmpty, s"a descriptor that closed within the drain budget must not be reported, got $out")
    }

    test("awaitFdDrain reports a descriptor that never closes within the budget") {
        // leaksNow always reports the socket: a genuine leak survives every sample through the budget and is returned.
        val out = LeakCheck.awaitFdDrain(() => Chunk("socket:[99]"), budgetNanos = 50_000_000L, settleNanos = 1_000_000L)
        assert(out.toSet == Set("socket:[99]"), s"a never-closing descriptor must be reported, got $out")
    }

    test("awaitFdDrain returns immediately and does not park on an empty first sample") {
        // A clean run: the first sample is empty, so the loop never runs (zero cost).
        var calls = 0
        val out = LeakCheck.awaitFdDrain(
            () =>
                calls += 1
                Chunk.empty
            ,
            budgetNanos = 10_000_000_000L,
            settleNanos = 1_000_000_000L
        )
        assert(out.isEmpty && calls == 1, s"a clean run must sample once and not park, got calls=$calls out=$out")
    }

    test("openFdTargets enumerates real descriptors and the diff clears on close (Linux only)") {
        LeakCheck.openFdTargets() match
            case Maybe.Absent =>
                cancel("/proc/self/fd unavailable on this OS; descriptor probe is a no-op here")
            case Maybe.Present(before) =>
                val tmp = java.io.File.createTempFile("kyo-leak-fd", ".tmp")
                tmp.deleteOnExit()
                val name = tmp.getName
                val ch   = FileChannel.open(tmp.toPath, StandardOpenOption.READ)
                try
                    val after = LeakCheck.openFdTargets().getOrElse(fail("openFdTargets became Absent mid-test"))
                    val leaks = LeakCheck.fdLeaks(before, after, Chunk.empty)
                    assert(leaks.exists(_.contains(name)), s"the open temp-file descriptor should be reported as a leak; got $leaks")
                finally ch.close()
                end try
                val restored = LeakCheck.openFdTargets().getOrElse(fail("openFdTargets became Absent mid-test"))
                assert(
                    !LeakCheck.fdLeaks(before, restored, Chunk.empty).exists(_.contains(name)),
                    "after close the temp-file descriptor must no longer be reported"
                )
        end match
    }

    test("scheduler probe reports Busy with a frame while a fiber spins, and drains after cleanup") {
        import kyo.AllowUnsafe.embrace.danger
        val stop = new AtomicBoolean(false)
        // A single CPU-bound thunk that pegs one worker thread until the flag flips: it allocates nothing (unlike a tight
        // kyo Loop, which mints a Loop.Outcome per iteration and exhausts the fork's heap), modelling the spinning-producer
        // leak's effect on the scheduler (a worker stuck at 100% with currentTask set, so load stays >= 1).
        val spinner: Unit < Sync =
            Sync.defer {
                var x = 0L
                while !stop.get() do x += 1
                if x < 0 then throw new AssertionError() // keep the loop live (x is read)
            }
        val ambient = minLoad(8)
        val fiber   = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(spinner))
        // Wait until the spinner is actually mounted and observed as busy load, instead of guessing with a fixed sleep
        // (which races startup). At a real done() a leaked spinner has been running since before the check.
        val observed = awaitTrue(2000)(LeakCheck.busyWorkerFrame().isDefined)
        val verdict = LeakCheck.awaitSchedulerIdle(
            budgetNanos = 300_000_000L,
            settleNanos = 150_000_000L,
            pollNanos = 10_000_000L
        )
        // Tear the spinner down BEFORE asserting so a failed assertion never leaves it pegging a worker.
        stop.set(true)
        val _ = Sync.Unsafe.evalOrThrow(fiber.interrupt)
        Thread.sleep(200)
        val drained = minLoad(10)

        assert(observed, s"the spinner should be observed as busy load within 2s (ambient=$ambient)")
        verdict match
            case LeakCheck.IdleResult.Busy(la, frame) =>
                assert(la > 0.0, s"a spinning fiber should keep load above zero (ambient=$ambient)")
                assert(frame.isDefined, "a busy worker should surface a stack frame for the spinning fiber")
            case LeakCheck.IdleResult.Idle =>
                fail(s"expected Busy while a fiber was spinning, got Idle (ambient=$ambient)")
        end match
        assert(drained <= ambient + 0.5, s"after cleanup the spinner's load should be gone (ambient=$ambient drained=$drained)")
    }

    test("non-daemon thread probe detects a leaked thread, respects the allowlist, and clears after join") {
        val baseline = LeakCheck.liveNonDaemonThreads()
        assert(
            !LeakCheck.leakedNonDaemonThreads(baseline, Chunk.empty).exists(_.contains("leak-probe-thread")),
            "baseline must not already contain the probe thread"
        )
        val leaked = new Thread(
            () =>
                try Thread.sleep(60000)
                catch case _: InterruptedException => (),
            "leak-probe-thread"
        )
        leaked.setDaemon(false)
        leaked.start()
        try
            assert(
                awaitTrue(2000)(LeakCheck.leakedNonDaemonThreads(baseline, Chunk.empty).exists(_.contains("leak-probe-thread"))),
                "a live non-daemon thread started after the baseline should be flagged"
            )
            assert(
                !LeakCheck.leakedNonDaemonThreads(baseline, Chunk("leak-probe-thread")).exists(_.contains("leak-probe-thread")),
                "a thread whose name matches a allowlist pattern must be excused"
            )
        finally
            leaked.interrupt()
            leaked.join(2000)
        end try
        assert(!leaked.isAlive, "probe thread should have stopped after interrupt+join")
        assert(
            !LeakCheck.leakedNonDaemonThreads(baseline, Chunk.empty).exists(_.contains("leak-probe-thread")),
            "after the thread joined it must no longer be reported as leaked"
        )
    }

    test("leak report contains a kyo Trace frame for a busy IOTask fiber") {
        import kyo.AllowUnsafe.embrace.danger
        val stop = new AtomicBoolean(false)
        // An effectful busy loop: each iteration runs an allocation-free CPU burst (pegging one worker) then a `.map`
        // whose `(using Frame)` stamps this test file's file:line into the fiber's live Trace, so the fiber stays
        // runnable (busy) AND its fiberTrace is non-empty. The burst bounds the allocation rate (one continuation set
        // per burst) so a fast .map recursion never exhausts the fork heap; the recursion threads `x` so it stays live.
        // Not a pure Sync.defer loop, which by kernel design pushes no user frames and renders an empty trace.
        def kyoBusyLoop(n: Long): Unit < Sync =
            Sync.defer {
                var x = n
                var i = 0
                while i < 1_000_000 && !stop.get() do
                    x += 1
                    i += 1
                x
            }.map(m => if stop.get() then () else kyoBusyLoop(m))
        val fiber    = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(kyoBusyLoop(0L)))
        val observed = awaitTrue(2000)(Scheduler.get.busyFiberTraces().exists(_.fiberTrace.nonEmpty))
        val report   = detectFiberReport(Chunk.empty)
        // Tear the loop down BEFORE asserting so a failed assertion never leaves the fiber pegging a worker.
        stop.set(true)
        val _       = Sync.Unsafe.evalOrThrow(fiber.interrupt)
        val drained = awaitTrue(2000)(Scheduler.get.busyFiberTraces().isEmpty)

        assert(observed, "an effectful busy IOTask fiber should surface a non-empty fiberTrace within 2s")
        val text = report.getOrElse("")
        assert(text.contains("kyo trace:"), s"the per-busy-worker dump should carry a 'kyo trace:' label; got:\n$text")
        assert(text.contains("LeakCheckTest.scala:"), s"the dump should name this test's fiber-body file:line; got:\n$text")
        assert(drained, "after teardown the scheduler should report no busy workers (no leaked fiber)")
    }

    test("busy non-IOTask worker produces a dump with no kyo trace subsection") {
        val stop = new AtomicBoolean(false)
        // A plain spinning Runnable scheduled through asExecutor becomes a non-IOTask Task whose fiberTrace() is "",
        // so the dump shows only the JVM thread stack for that worker (the graceful empty-trace fallback). The
        // `if x < 0 then throw` keeps the otherwise-dead accumulator live so the spin is not eliminated.
        val spinner: Runnable =
            () =>
                var x = 0L
                while !stop.get() do x += 1
                if x < 0 then throw new AssertionError()
        Scheduler.get.asExecutor.execute(spinner)
        val observed = awaitTrue(2000)(Scheduler.get.busyFiberTraces().exists(_.fiberTrace.isEmpty))
        val report   = detectFiberReport(Chunk.empty)
        stop.set(true)
        val drained = awaitTrue(2000)(Scheduler.get.busyFiberTraces().isEmpty)

        assert(observed, "a busy non-IOTask worker should surface a BusyWorker with an empty fiberTrace within 2s")
        val text = report.getOrElse("")
        assert(text.contains("thread stack:"), s"every busy worker must show a 'thread stack:' subsection; got:\n$text")
        assert(!text.contains("kyo trace:"), s"a non-IOTask worker (empty fiberTrace) must NOT show a 'kyo trace:' subsection; got:\n$text")
        assert(drained, "after stop the scheduler should report no busy workers")
    }

    test("allowlist match covers a kyo-trace-only frame") {
        import kyo.AllowUnsafe.embrace.danger
        val stop = new AtomicBoolean(false)
        def kyoBusyLoop(n: Long): Unit < Sync =
            Sync.defer {
                var x = n
                var i = 0
                while i < 1_000_000 && !stop.get() do
                    x += 1
                    i += 1
                x
            }.map(m => if stop.get() then () else kyoBusyLoop(m))
        val fiber = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(kyoBusyLoop(0L)))
        // Wait for the PERSISTENT recursion frame (the `.map` call site, `LeakCheckTest.kyoBusyLoop(...)`), not merely any
        // non-empty trace: a transient startup frame is the newest line early on but does not survive once the 16-slot
        // ring fills with the steady-state recursion frame, so a token taken from it would not match a later detect render.
        val observed = awaitTrue(2000)(Scheduler.get.busyFiberTraces().exists(_.fiberTrace.contains("LeakCheckTest.kyoBusyLoop(")))
        // Reproduce the Busy branch's allowlist match text against the REAL busy fiber: per worker, its rendered kyo
        // trace joined with its JVM stack. The token is the ` @ <class>.<caller>(File:line)` fragment of the recursion
        // frame: a JVM StackTraceElement carries no ` @ ` separator, so the token appears ONLY in the kyo trace, never in
        // the JVM stack. The padded snippet (before ` @ `) and the trailing ` (xN)` repeat-count suffix are dropped (the
        // count grows as the ring fills, so a count-bearing token would not match a later render).
        val busy       = Scheduler.get.busyFiberTraces()
        val jvmStacks  = busy.map(w => LeakCheck.stackOfThread(w.mount).getOrElse("")).mkString("\n")
        val matchText  = busy.map(w => w.fiberTrace + "\n" + LeakCheck.stackOfThread(w.mount).getOrElse("")).mkString("\n")
        val steadyLine = busy.iterator.flatMap(_.fiberTrace.linesIterator).find(_.contains("LeakCheckTest.kyoBusyLoop(")).getOrElse("")
        val atIdx      = steadyLine.indexOf(" @ ")
        val afterAt    = if atIdx >= 0 then steadyLine.substring(atIdx) else steadyLine
        val xIdx       = afterAt.indexOf(" (x")
        val kyoOnly    = if xIdx >= 0 then afterAt.substring(0, xIdx) else afterAt
        // End-to-end: ONE detect with the kyo-trace token suppresses the finding (a single Busy probe, as reliable as
        // the kyo-trace-frame arm above; a second back-to-back probe races the first probe's System.gc() and is avoided).
        val suppressed = detectFiberReport(Chunk(kyoOnly))
        // Tear the loop down BEFORE asserting so a failed assertion never leaves the fiber pegging a worker.
        stop.set(true)
        val _       = Sync.Unsafe.evalOrThrow(fiber.interrupt)
        val drained = awaitTrue(2000)(Scheduler.get.busyFiberTraces().isEmpty)

        assert(observed, "an effectful busy IOTask fiber should surface a non-empty fiberTrace within 2s")
        assert(kyoOnly.nonEmpty && kyoOnly.contains(" @ "), s"the token should be a kyo-trace frame fragment; got '$kyoOnly'")
        assert(!jvmStacks.contains(kyoOnly), s"the chosen token must be kyo-trace-only (absent from the JVM stacks); token '$kyoOnly'")
        // Deterministic widening proof against the exact Busy-branch match expression on the real fiber's match text:
        // absent the token no default pattern matches (so the finding would fire), and adding the kyo-trace token makes
        // the match succeed (so the finding is suppressed) via the kyo trace, not the JVM stack.
        assert(
            !LeakCheck.defaultAllowlist.exists(matchText.contains),
            "absent the token, no default pattern matches the busy fiber, so the finding would fire"
        )
        assert(
            (LeakCheck.defaultAllowlist ++ Chunk(kyoOnly)).exists(matchText.contains),
            s"the kyo-trace-only token must match via the Busy-branch match expression; token '$kyoOnly'"
        )
        assert(
            !suppressed.getOrElse("").contains("fiber leak:"),
            s"the kyo-trace-only allowlist token must suppress the detect fiber-leak finding; token=[$kyoOnly]"
        )
        assert(drained, "after teardown the scheduler should report no busy workers")
    }

    test("allowlist match excuses a default JVM-stack pattern") {
        // The match text per busy worker is `fiberTrace + "\n" + jvmStack`. A default allowlist pattern that
        // appears only in the JVM-stack portion suppresses the finding; a pattern in the kyo-trace portion
        // suppresses it too. Driven via the pure match logic because constructing a real NioIoDriver-backed
        // busy worker in a unit test is heavyweight.
        // The frame a process-lifetime driver actually emits: the marker rides the CYCLE method name, and it is lower-camel, so a frame
        // naming the ProcessSharedTransport CLASS does not contain the token. This used to pass on the "NioIoDriver" pattern the allowlist
        // no longer carries, which is exactly the over-broad excuse that narrowing removed.
        val jvmStackPortion =
            "    at kyo.net.internal.posix.PollerIoDriver.processSharedTransportCycle(PollerIoDriver.scala:480)\n" +
                "    at kyo.net.internal.posix.PollerIoDriver$$anon$1.run(PollerIoDriver.scala:478)"
        val matchTextJvmOnly = "" + "\n" + jvmStackPortion
        assert(
            LeakCheck.defaultAllowlist.exists(matchTextJvmOnly.contains),
            s"a default pattern in the JVM-stack portion must suppress; match text was:\n$matchTextJvmOnly"
        )
        val matchTextKyoOnly = "at processSharedTransport @ kyo.Foo.bar(Foo.scala:1)" + "\n" + ""
        assert(
            LeakCheck.defaultAllowlist.exists(matchTextKyoOnly.contains),
            s"a default pattern in the kyo-trace portion must suppress; match text was:\n$matchTextKyoOnly"
        )
    }

    test("the default allowlist excuses only a process-lifetime driver cycle, and still reports an owned one") {
        // Both directions of the narrowed allowlist. It used to carry "NioIoDriver", which excused EVERY carrier of that driver, including a
        // genuinely leaked one from a driver a test built and never closed. It now carries only the process-lifetime marker, so the two frames
        // must be treated differently, and a regression that re-broadened it would be invisible without the negative direction below.
        //
        // Driven through the pure match logic for the same reason the sibling leaf above is: standing up a real driver-backed busy worker in a
        // unit test is heavyweight, and the predicate is what the runner actually applies.
        val processLifetimeFrame =
            "    at kyo.net.internal.posix.PollerIoDriver.processSharedTransportCycle(PollerIoDriver.scala:480)"
        assert(
            LeakCheck.defaultAllowlist.exists(processLifetimeFrame.contains),
            s"a process-lifetime driver cycle is parked by design and must be excused; frame was:\n$processLifetimeFrame"
        )

        // The negative direction: an owned driver's cycle carries the plain frame, and a leaked one MUST be reported.
        val ownedDriverFrame =
            "    at kyo.net.internal.posix.PollerIoDriver.runCycle(PollerIoDriver.scala:538)\n" +
                "    at kyo.net.internal.posix.PollerIoDriver$$anon$1.run(PollerIoDriver.scala:478)"
        assert(
            !LeakCheck.defaultAllowlist.exists(ownedDriverFrame.contains),
            s"an owned driver's cycle must NOT be excused, or a driver a test never closed leaks silently; frame was:\n$ownedDriverFrame"
        )
        val ownedNioFrame = "    at kyo.net.internal.NioIoDriver.runCycle(NioIoDriver.scala:900)"
        assert(
            !LeakCheck.defaultAllowlist.exists(ownedNioFrame.contains),
            s"the allowlist must not excuse a driver by NAME, only by the process-lifetime marker; frame was:\n$ownedNioFrame"
        )
    }

end LeakCheckTest
