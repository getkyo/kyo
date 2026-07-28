package kyo.test.runner

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kyo.Chunk
import kyo.internal.Diagnostics
import kyo.test.runner.internal.StrandedOpCheck
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** Validates [[StrandedOpCheck]]'s two-sample classifier: a component whose pending flag stays true while its cycle counter stays frozen
  * across the settle window is reported STRANDED (the deterministic negative-test shape: a deliberately-stranded probe must trip the
  * gate); one whose cycle counter advances (continuous progress) or whose pending flag clears is not; a closed component or one matched
  * by the allowlist is never reported regardless of its counters. Plain ScalaTest (like `LeakCheckTest`) so registrations can be driven
  * and torn down directly; each test uses a uniquely-named registration so it cannot be confused with another suite's concurrently
  * registered component in the same JVM.
  */
class StrandedOpCheckTest extends AnyFunSuite with NonImplicitAssertions:

    private def freshName(tag: String): String = s"StrandedOpCheckTest-$tag-${scala.util.Random.nextLong()}"

    test("reports a component whose pending work survives with frozen cycles as stranded") {
        val name = freshName("stranded")
        val reg = Diagnostics.register(name)(
            () => "unused",
            () => Diagnostics.Probe(closed = false, cycles = 7L, pending = true)
        )
        try
            val report = StrandedOpCheck.detect(Chunk.empty, settleNanos = 5_000_000L)
            assert(report.exists(r => r.contains(name) && r.contains("lost wakeup")))
        finally reg.close()
        end try
    }

    test("does not report a component whose cycle counter advances between samples") {
        val name    = freshName("advancing")
        val counter = new AtomicLong(0L)
        val reg = Diagnostics.register(name)(
            () => "unused",
            () => Diagnostics.Probe(closed = false, cycles = counter.incrementAndGet(), pending = true)
        )
        try
            val report = StrandedOpCheck.detect(Chunk.empty, settleNanos = 5_000_000L)
            assert(!report.exists(_.contains(name)))
        finally reg.close()
        end try
    }

    test("does not report a closed component even with frozen cycles and pending work") {
        val name = freshName("closed")
        val reg = Diagnostics.register(name)(
            () => "unused",
            () => Diagnostics.Probe(closed = true, cycles = 3L, pending = true)
        )
        try
            val report = StrandedOpCheck.detect(Chunk.empty, settleNanos = 5_000_000L)
            assert(!report.exists(_.contains(name)))
        finally reg.close()
        end try
    }

    test("does not report a component whose pending flag clears between samples") {
        val name  = freshName("drained")
        val first = new AtomicBoolean(true)
        val reg = Diagnostics.register(name)(
            () => "unused",
            () => Diagnostics.Probe(closed = false, cycles = 1L, pending = first.getAndSet(false))
        )
        try
            val report = StrandedOpCheck.detect(Chunk.empty, settleNanos = 5_000_000L)
            assert(!report.exists(_.contains(name)))
        finally reg.close()
        end try
    }

    test("does not report a component matched by the fork's aggregated allowlist") {
        val name = freshName("stranded")
        val reg = Diagnostics.register(name)(
            () => "unused",
            () => Diagnostics.Probe(closed = false, cycles = 1L, pending = true)
        )
        try
            val report = StrandedOpCheck.detect(Chunk(name), settleNanos = 5_000_000L)
            assert(!report.exists(_.contains(name)))
        finally reg.close()
        end try
    }

    test("does not report a component matched by LeakCheck's default processSharedTransport marker") {
        val name = freshName("marked") + " processSharedTransport"
        val reg = Diagnostics.register(name)(
            () => "unused",
            () => Diagnostics.Probe(closed = false, cycles = 1L, pending = true)
        )
        try
            val report = StrandedOpCheck.detect(Chunk.empty, settleNanos = 5_000_000L)
            assert(!report.exists(_.contains(name)))
        finally reg.close()
        end try
    }
end StrandedOpCheckTest
