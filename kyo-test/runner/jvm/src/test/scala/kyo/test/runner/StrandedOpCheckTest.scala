package kyo.test.runner

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kyo.Chunk
import kyo.internal.Diagnostics
import kyo.test.runner.internal.StrandedOpCheck
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** Validates [[StrandedOpCheck]]'s classifier across its budget: a component whose pending flag stays true while its cycle counter stays
  * frozen in every sample until the budget passes is reported STRANDED (the deterministic negative-test shape: a deliberately-stranded
  * probe must trip the gate); one whose cycle counter advances (continuous progress), even after a frozen window, or whose pending flag
  * clears is not; a closed component or one matched by the allowlist is never reported regardless of its counters. Plain ScalaTest (like
  * `LeakCheckTest`) so registrations can be driven and torn down directly; each test uses a uniquely-named registration so it cannot be
  * confused with another suite's concurrently registered component in the same JVM.
  */
class StrandedOpCheckTest extends AnyFunSuite with NonImplicitAssertions:

    private def detect(allowlist: Chunk[String], settleNanos: Long, budgetNanos: Long) =
        val time = new AtomicLong(0L)
        StrandedOpCheck.detect(
            allowlist,
            settleNanos,
            budgetNanos,
            () => time.get(),
            nanos =>
                time.addAndGet(nanos); ()
        )
    end detect

    private def freshName(tag: String): String = s"StrandedOpCheckTest-$tag-${scala.util.Random.nextLong()}"

    test("reports a component whose pending work survives with frozen cycles as stranded") {
        val name = freshName("stranded")
        val reg  = Diagnostics.register(name)(
            () => "unused",
            () => Diagnostics.Probe(closed = false, cycles = 7L, pending = true)
        )
        try
            val report = detect(Chunk.empty, settleNanos = 5_000_000L, budgetNanos = 20_000_000L)
            assert(report.exists(r => r.contains(name) && r.contains("lost wakeup")))
        finally reg.close()
        end try
    }

    test("does not report a component whose cycles stay frozen for a window and then advance") {
        val name  = freshName("paused")
        val calls = new AtomicLong(0L)
        val reg   = Diagnostics.register(name)(
            () => "unused",
            () =>
                val call = calls.incrementAndGet()
                Diagnostics.Probe(closed = false, cycles = if call <= 3 then 7L else call, pending = true)
        )
        try
            // Samples advance virtual time; the fourth sample resumes the component before the deadline.
            val report = detect(Chunk.empty, settleNanos = 5_000_000L, budgetNanos = 30_000_000_000L)
            assert(!report.exists(_.contains(name)))
            assert(calls.get() == 4L)
        finally reg.close()
        end try
    }

    test("reports a component frozen across the whole budget") {
        val name = freshName("frozen")
        val time = new AtomicLong(0L)
        val reg  = Diagnostics.register(name)(
            () => "unused",
            () => Diagnostics.Probe(closed = false, cycles = 7L, pending = true)
        )
        try
            val report = StrandedOpCheck.detect(
                Chunk.empty,
                settleNanos = 5_000_000L,
                budgetNanos = 50_000_000L,
                () => time.get(),
                nanos =>
                    time.addAndGet(nanos); ()
            )
            assert(report.exists(r => r.contains(name) && r.contains("frozen at 7") && r.contains("50 ms budget")))
            assert(time.get() == 50_000_000L)
        finally reg.close()
        end try
    }

    Seq(false, true).foreach { closes =>
        test(s"drops a paused component when it ${if closes then "closes" else "drains"}") {
            val name  = freshName("resolved")
            val calls = new AtomicLong(0L)
            val reg   = Diagnostics.register(name)(
                () => "unused",
                () =>
                    val resolved = calls.incrementAndGet() >= 4
                    Diagnostics.Probe(closed = closes && resolved, cycles = 7L, pending = closes || !resolved)
            )
            try
                assert(detect(Chunk.empty, settleNanos = 5_000_000L, budgetNanos = 50_000_000L).isEmpty)
                assert(calls.get() == 4L)
            finally reg.close()
            end try
        }
    }

    test("drops a paused component when it unregisters") {
        val name = freshName("unregistered")
        val time = new AtomicLong(0L)
        val reg  = Diagnostics.register(name)(
            () => "unused",
            () => Diagnostics.Probe(closed = false, cycles = 7L, pending = true)
        )
        try
            val report = StrandedOpCheck.detect(
                Chunk.empty,
                settleNanos = 5_000_000L,
                budgetNanos = 50_000_000L,
                () => time.get(),
                nanos => if time.addAndGet(nanos) == 15_000_000L then reg.close()
            )
            assert(report.isEmpty)
            assert(time.get() == 15_000_000L)
        finally reg.close()
        end try
    }

    test("does not reconsider a component after it makes progress") {
        val frozenName  = freshName("frozen")
        val resumedName = freshName("resumed")
        val calls       = new AtomicLong(0L)
        val frozen      = Diagnostics.register(frozenName)(
            () => "unused",
            () => Diagnostics.Probe(closed = false, cycles = 7L, pending = true)
        )
        val resumed = Diagnostics.register(resumedName)(
            () => "unused",
            () => Diagnostics.Probe(closed = false, cycles = if calls.incrementAndGet() == 4 then 8L else 7L, pending = true)
        )
        try
            val report   = detect(Chunk.empty, settleNanos = 5_000_000L, budgetNanos = 50_000_000L)
            val findings = report.getOrElse(fail("Expected the frozen component to be reported")).split("full diagnostics dump:")(0)
            assert(findings.contains(frozenName))
            assert(!findings.contains(resumedName))
            assert(calls.get() == 11L)
        finally
            frozen.close()
            resumed.close()
        end try
    }

    test("reports a live loop parked with work queued that no wakeup reaches") {
        val name    = freshName("lost-wakeup")
        val queue   = new ConcurrentLinkedQueue[String]()
        val cycles  = new AtomicLong(0L)
        val wakeup  = new AtomicBoolean(false)
        val running = new AtomicBoolean(true)
        val parked  = new CountDownLatch(1)
        // deviation: this check observes raw driver threads after the effect runtime settles; a latch fences the queued work.
        // A loop in the shape of kyo-net's drivers: each turn drains the queue, then the loop parks until a producer sets the wakeup.
        val loop = new Thread(() =>
            while running.get() do
                queue.clear()
                cycles.incrementAndGet()
                parked.countDown()
                while !wakeup.getAndSet(false) do LockSupport.park()
            end while
        )
        loop.setDaemon(true)
        loop.start()
        val reg = Diagnostics.register(name)(
            () => "unused",
            () => Diagnostics.Probe(closed = false, cycles = cycles.get(), pending = !queue.isEmpty)
        )
        try
            parked.await()
            // A producer that loses the wakeup: it queues the op and never sets the flag nor unparks the loop.
            queue.add("op")
            val report = detect(Chunk.empty, settleNanos = 5_000_000L, budgetNanos = 50_000_000L)
            assert(report.exists(r => r.contains(name) && r.contains("frozen at 1") && r.contains("lost wakeup")))
        finally
            reg.close()
            running.set(false)
            wakeup.set(true)
            LockSupport.unpark(loop)
            loop.join()
        end try
    }

    test("does not report a component whose cycle counter advances between samples") {
        val name    = freshName("advancing")
        val counter = new AtomicLong(0L)
        val reg     = Diagnostics.register(name)(
            () => "unused",
            () => Diagnostics.Probe(closed = false, cycles = counter.incrementAndGet(), pending = true)
        )
        try
            val report = detect(Chunk.empty, settleNanos = 5_000_000L, budgetNanos = 20_000_000L)
            assert(!report.exists(_.contains(name)))
        finally reg.close()
        end try
    }

    test("does not report a closed component even with frozen cycles and pending work") {
        val name = freshName("closed")
        val reg  = Diagnostics.register(name)(
            () => "unused",
            () => Diagnostics.Probe(closed = true, cycles = 3L, pending = true)
        )
        try
            val report = detect(Chunk.empty, settleNanos = 5_000_000L, budgetNanos = 20_000_000L)
            assert(!report.exists(_.contains(name)))
        finally reg.close()
        end try
    }

    test("does not report a component whose pending flag clears between samples") {
        val name  = freshName("drained")
        val first = new AtomicBoolean(true)
        val reg   = Diagnostics.register(name)(
            () => "unused",
            () => Diagnostics.Probe(closed = false, cycles = 1L, pending = first.getAndSet(false))
        )
        try
            val report = detect(Chunk.empty, settleNanos = 5_000_000L, budgetNanos = 20_000_000L)
            assert(!report.exists(_.contains(name)))
        finally reg.close()
        end try
    }

    test("does not report a component matched by the fork's aggregated allowlist") {
        val name = freshName("stranded")
        val reg  = Diagnostics.register(name)(
            () => "unused",
            () => Diagnostics.Probe(closed = false, cycles = 1L, pending = true)
        )
        try
            val report = detect(Chunk(name), settleNanos = 5_000_000L, budgetNanos = 20_000_000L)
            assert(!report.exists(_.contains(name)))
        finally reg.close()
        end try
    }

    test("does not report a component matched by LeakCheck's default processSharedTransport marker") {
        val name = freshName("marked") + " processSharedTransport"
        val reg  = Diagnostics.register(name)(
            () => "unused",
            () => Diagnostics.Probe(closed = false, cycles = 1L, pending = true)
        )
        try
            val report = detect(Chunk.empty, settleNanos = 5_000_000L, budgetNanos = 20_000_000L)
            assert(!report.exists(_.contains(name)))
        finally reg.close()
        end try
    }
end StrandedOpCheckTest
