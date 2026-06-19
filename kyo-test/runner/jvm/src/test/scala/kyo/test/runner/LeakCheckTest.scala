package kyo.test.runner

import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import kyo.*
import kyo.test.runner.internal.LeakCheck
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** Validates the three end-of-run leak probes against a deliberately leaked file descriptor, a deliberately leaked spinning fiber, and a
  * deliberately leaked non-daemon thread, then confirms each signal clears after cleanup. Plain ScalaTest (not self-hosted via kyo-test) so
  * the leaks can be driven and torn down from outside any fiber.
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

    test("openFdCount tracks opened and closed file descriptors") {
        LeakCheck.openFdCount() match
            case Maybe.Absent =>
                cancel("UnixOperatingSystemMXBean unavailable on this JVM/OS; FD probe is a no-op here")
            case Maybe.Present(before) =>
                val tmp = java.io.File.createTempFile("kyo-leak-fd", ".tmp")
                tmp.deleteOnExit()
                val n     = 16
                val chans = (1 to n).map(_ => FileChannel.open(tmp.toPath, StandardOpenOption.READ))
                try
                    val after = LeakCheck.openFdCount().getOrElse(fail("FD count became Absent mid-test"))
                    assert(after - before >= n, s"opening $n channels should raise the FD count by >= $n (before=$before after=$after)")
                finally chans.foreach(_.close())
                end try
                val restored = LeakCheck.openFdCount().getOrElse(fail("FD count became Absent mid-test"))
                assert(restored - before <= 2, s"closing the channels should return near baseline (before=$before restored=$restored)")
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
        Thread.sleep(150) // let the spinner peg a worker
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

        verdict match
            case LeakCheck.IdleResult.Busy(la, frame) =>
                assert(la > 0.0, s"a spinning fiber should keep load above zero (ambient=$ambient)")
                assert(frame.isDefined, "a busy worker should surface a stack frame for the spinning fiber")
            case LeakCheck.IdleResult.Idle =>
                fail(s"expected Busy while a fiber was spinning, got Idle (ambient=$ambient)")
        end match
        assert(drained <= ambient + 0.5, s"after cleanup the spinner's load should be gone (ambient=$ambient drained=$drained)")
    }

    test("non-daemon thread probe detects a leaked thread and clears it after join") {
        val baseline = LeakCheck.liveNonDaemonThreads()
        assert(
            !LeakCheck.leakedNonDaemonThreads(baseline).exists(_.contains("leak-probe-thread")),
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
            Thread.sleep(50) // let it reach the running state
            val leaks = LeakCheck.leakedNonDaemonThreads(baseline)
            assert(
                leaks.exists(_.contains("leak-probe-thread")),
                s"a live non-daemon thread started after the baseline should be flagged; got $leaks"
            )
        finally
            leaked.interrupt()
            leaked.join(2000)
        end try
        assert(!leaked.isAlive, "probe thread should have stopped after interrupt+join")
        assert(
            !LeakCheck.leakedNonDaemonThreads(baseline).exists(_.contains("leak-probe-thread")),
            "after the thread joined it must no longer be reported as leaked"
        )
    }

end LeakCheckTest
