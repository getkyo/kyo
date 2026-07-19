package kyo.scheduler

import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.LockSupport
import kyo.scheduler.util.ThreadUserTime

// Scratch diagnostic for the Windows CI investigation: measures the platform's
// sleep/park overshoot and CPU-time counter resolution, then watches a live
// scheduler compensate for five blocked tasks. Run via
// `sbt "kyo-schedulerJVM/Test/runMain kyo.scheduler.TimerDiag"`. Dev artifact,
// removed before the change ships.
object TimerDiag {

    private def stats(label: String, xs: Array[Long]): Unit = {
        val s            = xs.sorted
        def p(q: Double) = s(((s.length - 1) * q).toInt)
        val mean         = xs.sum.toDouble / xs.length
        val dev          = Math.sqrt(xs.map(x => (x - mean) * (x - mean)).sum / xs.length)
        println(
            f"[diag] $label%-30s min=${s.head / 1e6}%9.3fms p50=${p(0.5) / 1e6}%9.3fms p90=${p(0.9) / 1e6}%9.3fms max=${s.last / 1e6}%9.3fms stddev=${dev / 1e6}%9.3fms"
        )
    }

    def main(args: Array[String]): Unit = {
        val n      = 200
        val sleep1 = new Array[Long](n)
        var i      = 0
        while (i < n) {
            val t0 = System.nanoTime()
            Thread.sleep(1)
            sleep1(i) = System.nanoTime() - t0 - 1000000L
            i += 1
        }
        stats("Thread.sleep(1) overshoot", sleep1)

        val park2 = new Array[Long](n)
        i = 0
        while (i < n) {
            val t0 = System.nanoTime()
            LockSupport.parkNanos(2000000L)
            park2(i) = System.nanoTime() - t0 - 2000000L
            i += 1
        }
        stats("parkNanos(2ms) overshoot", park2)

        println(s"[diag] ThreadUserTime.probeResolution = ${ThreadUserTime.probeResolution() / 1e6}ms")

        val sched   = new Scheduler(TestExecutors.cached, TestExecutors.scheduled, TestExecutors.scheduled)
        val started = new CountDownLatch(5)
        (0 until 5).foreach { _ =>
            sched.schedule(TestTask(_run = () => {
                started.countDown()
                try Thread.sleep(20000)
                catch { case _: InterruptedException => () }
                Task.Done
            }))
        }
        var t = 0
        while (t < 25) {
            Thread.sleep(1000)
            val st = sched.status()
            val c  = st.concurrency.regulator
            println(
                f"[diag] t=$t%2ds started=${5 - started.getCount} cur=${st.currentWorkers} alloc=${st.allocatedWorkers} loadAvg=${st.loadAvg}%.2f " +
                    f"reg(step=${c.step} avg=${c.measurementsAvg / 1e6}%.3fms jitter=${c.measurementsJitter / 1e6}%.3fms probes=${c.probesCompleted} updates=${c.updates})"
            )
            t += 1
        }
        java.lang.System.exit(0)
    }
}
