package kyo.scheduler.util

import java.util.concurrent.atomic.AtomicBoolean
import kyo.scheduler.regulator.Concurrency
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class SleepTest extends AnyFreeSpec with NonImplicitAssertions {

    "sleeps for at least the requested duration" in {
        val ms    = 50
        val start = System.nanoTime()
        Sleep(ms)
        val elapsed = (System.nanoTime() - start) / 1000000
        assert(elapsed >= ms, s"Sleep($ms) returned after only ${elapsed}ms")
    }

    "sleeps for a reasonable upper bound" in {
        val ms    = 50
        val start = System.nanoTime()
        Sleep(ms)
        val elapsed = (System.nanoTime() - start) / 1000000
        assert(elapsed < ms * 10, s"Sleep($ms) took ${elapsed}ms, expected < ${ms * 10}ms")
    }

    "handles zero" in {
        val start = System.nanoTime()
        Sleep(0)
        val elapsed = (System.nanoTime() - start) / 1000000
        assert(elapsed < 100, s"Sleep(0) took ${elapsed}ms")
    }

    "probe jitter stays below regulator threshold under blocking load" in {
        // Reproduces the real-world scenario: many threads doing blocking
        // sleeps via Thread.sleep while the probe measures jitter with Sleep.
        // On Scala Native, Thread.sleep uses pipe+poll+close (4 syscalls).
        // If Sleep also uses Thread.sleep, it competes for the same fd table
        // and OS scheduler resources, amplifying jitter. If Sleep uses
        // nanosleep (single syscall, no fds), it stays stable.
        val samples  = 100
        val measures = new Array[Long](samples)
        val running  = new AtomicBoolean(true)

        // Spawn many blocking threads — each does Thread.sleep(1) in a loop.
        // On Native, each call does pipe+poll+close×2, creating fd table and
        // OS scheduler contention that amplifies probe jitter.
        val nThreads = 200
        val threads = (0 until nThreads).map { _ =>
            val t = new Thread(() => {
                while (running.get()) {
                    Thread.sleep(1)
                }
            })
            t.setDaemon(true)
            t.start()
            t
        }

        try {
            // Let pressure build
            Thread.sleep(100)

            // Warmup
            for (_ <- 0 until 20) {
                val s = System.nanoTime()
                Sleep(1)
                System.nanoTime() - s
            }

            // Collect
            for (i <- 0 until samples) {
                val start = System.nanoTime()
                Sleep(1)
                val elapsed = System.nanoTime() - start - 1000000
                measures(i) = elapsed
            }
        } finally {
            running.set(false)
            threads.foreach(_.join(1000))
        }

        val avg = measures.sum.toDouble / samples
        val variance = measures.map(m => {
            val diff = m - avg
            diff * diff
        }).sum / samples
        val stddev = Math.sqrt(variance)

        val threshold = Concurrency.defaultConfig.jitterUpperThreshold * 3
        assert(
            stddev < threshold,
            s"Sleep jitter stddev=${stddev.toLong}ns (avg=${avg.toLong}ns) exceeds regulator threshold ${threshold.toLong}ns"
        )
    }
}
