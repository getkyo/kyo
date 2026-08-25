package kyo.scheduler.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kyo.scheduler.BlockingMonitor
import org.scalatest.NonImplicitAssertions
import org.scalatest.concurrent.Eventually.*
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

/** Covers what Sleep owes a caller: it suspends, and it comes back.
  *
  * The nanosleep-vs-Thread.sleep choice is covered by SleepDescriptorTest (pipe descriptors in /proc/self/fd), and
  * magnitude by ConcurrencyTest; magnitude is out of scope here since this suite reads no clock.
  */
class SleepTest extends AnyFreeSpec with NonImplicitAssertions {

    "suspends the calling thread" in {
        // The detector reports a thread blocked once its user CPU time stops advancing: true for a thread parked in
        // Sleep, false for one spinning or returned. The property is thread state, not elapsed time.
        val entered  = new CountDownLatch(1)
        val returned = new AtomicBoolean(false)
        val threadId = new AtomicLong(0L)
        val thread = new Thread((() => {
            threadId.set(ThreadUserTime.currentThreadId())
            entered.countDown()
            // The test's final interrupt releases this thread where Sleep is interruptible; catching it keeps it quiet.
            try {
                Sleep(30000)
                returned.set(true)
            } catch {
                case _: InterruptedException => ()
            }
        }): Runnable)
        thread.setDaemon(true)
        thread.start()
        try {
            assert(entered.await(30, TimeUnit.SECONDS), "the sleeping thread should have started")
            val detector = new BlockingMonitor(1)
            val ids      = Array(threadId.get())
            // The 200ms interval outruns the coarsest CPU-time counter (Windows' 15.6ms per-thread
            // tick): a spinning Sleep must not read as flat between two samples that landed in one tick.
            eventually(timeout(scaled(Span(30, Seconds))), interval(Span(200, Millis))) {
                detector.sample(ids, 1)
                assert(detector.isBlocked(0), "a thread inside Sleep should read as blocked")
            }
            assert(!returned.get(), "Sleep returned instead of suspending its caller")
        } finally {
            thread.interrupt()
            ()
        }
    }

    "returns" - {
        "for a zero duration" in assertReturns(0)

        "for a positive duration" in assertReturns(50)
    }

    /** Runs `Sleep(ms)` on its own thread and asserts it hands control back. Completion is the whole observable; the
      * await bound only catches a Sleep that never returns.
      */
    private def assertReturns(ms: Int): Unit = {
        val returned = new CountDownLatch(1)
        val thread = new Thread((() => {
            Sleep(ms)
            returned.countDown()
        }): Runnable)
        thread.setDaemon(true)
        thread.start()
        val _ = assert(returned.await(30, TimeUnit.SECONDS), s"Sleep($ms) did not return")
    }
}
