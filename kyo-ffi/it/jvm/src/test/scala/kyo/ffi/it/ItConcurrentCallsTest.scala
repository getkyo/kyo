package kyo.ffi.it

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kyo.discard
import kyo.ffi.Ffi

/** G4: Concurrent FFI calls, JVM-only (JS is single-threaded, Native has its own threading model).
  *
  * Launches 8 threads, each calling `abs` or `strlen` 1000 times concurrently. Validates that:
  *   - Every call returns the correct result (no Scratch corruption between threads).
  *   - No errno bleed: repeated signed-integer abs calls on distinct inputs never corrupt results.
  *
  * Lives under `jvm/src/test/` because `java.util.concurrent` threading is JVM-specific and the JS runtime is single-threaded by design.
  */
class ItConcurrentCallsTest extends ItTestBase:

    private val threads        = 8
    private val callsPerThread = 1000

    "abs, concurrent calls from 8 threads each run 1000 times" in {
        val libc     = Ffi.load[LibCBindings]
        val failures = new AtomicInteger(0)
        val latch    = new CountDownLatch(threads)

        val workers = (0 until threads).map { threadIdx =>
            new Thread(() =>
                var i = 0
                while i < callsPerThread do
                    // Each thread uses its own input so results are deterministic.
                    val input    = (threadIdx * callsPerThread + i + 1) % Int.MaxValue
                    val expected = if input < 0 then -input else input
                    val result   = libc.abs(input)
                    if result != expected then
                        discard(failures.incrementAndGet())
                    end if
                    i += 1
                end while
                latch.countDown()
            )
        }

        workers.foreach(_.start())
        latch.await()

        assert(failures.get() == 0)
    }

    "strlen, concurrent calls from 8 threads each run 1000 times" in {
        val libc     = Ffi.load[LibCBindings]
        val failures = new AtomicInteger(0)
        val latch    = new CountDownLatch(threads)

        // Pre-build distinct strings per thread so no allocation happens in the hot loop.
        val inputs: Array[String] = (0 until threads).map { threadIdx =>
            "x" * (threadIdx + 1) * 10
        }.toArray

        val workers = (0 until threads).map { threadIdx =>
            new Thread(() =>
                val s        = inputs(threadIdx)
                val expected = s.getBytes("UTF-8").length.toLong
                var i        = 0
                while i < callsPerThread do
                    val result = libc.strlen(s)
                    if result != expected then
                        discard(failures.incrementAndGet())
                    end if
                    i += 1
                end while
                latch.countDown()
            )
        }

        workers.foreach(_.start())
        latch.await()

        assert(failures.get() == 0)
    }

    "labs, concurrent calls from 8 threads each run 1000 times" in {
        val libc     = Ffi.load[LibCBindings]
        val failures = new AtomicInteger(0)
        val latch    = new CountDownLatch(threads)

        val workers = (0 until threads).map { threadIdx =>
            new Thread(() =>
                var i = 0
                while i < callsPerThread do
                    val input    = -(threadIdx.toLong * callsPerThread + i + 1L)
                    val expected = -input
                    val result   = libc.labs(input)
                    if result != expected then
                        discard(failures.incrementAndGet())
                    end if
                    i += 1
                end while
                latch.countDown()
            )
        }

        workers.foreach(_.start())
        latch.await()

        assert(failures.get() == 0)
    }

end ItConcurrentCallsTest
