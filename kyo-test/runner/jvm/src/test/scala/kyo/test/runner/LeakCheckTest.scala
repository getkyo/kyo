package kyo.test.runner

import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import kyo.*
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

    test("fdLeaks reports only new, non-benign, non-whitelisted descriptors") {
        val baseline = Set("socket:[1]", "/app/lib/foo.jar", "pipe:[2]")
        val current = Set(
            "socket:[1]",             // in baseline -> not a leak (e.g. the sbt.ForkMain socket)
            "/app/lib/foo.jar",       // baseline jar
            "/app/lib/new.jar",       // new but benign (classloader jar)
            "anon_inode:[eventpoll]", // new but benign (JVM epoll)
            "/dev/random",            // new but benign
            "socket:[99]",            // NEW socket -> leak
            "/tmp/leaked.txt",        // NEW file -> leak
            "/tmp/excused.txt"        // NEW file but whitelisted
        )
        val leaks = LeakCheck.fdLeaks(baseline, current, Chunk("excused"))
        assert(leaks.toSet == Set("socket:[99]", "/tmp/leaked.txt"), s"got $leaks")
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

    test("non-daemon thread probe detects a leaked thread, respects the whitelist, and clears after join") {
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
                "a thread whose name matches a whitelist pattern must be excused"
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

end LeakCheckTest
