package kyo.scheduler

import java.util.concurrent.Executor
import java.util.concurrent.locks.LockSupport
import kyo.stats.internal.MetricReceiver

final private case class InternalClock(executor: Executor) {

    @volatile private var _stop = false

    val a1, a2, a3, a4, a5, a6, a7 = 0L // padding

    @volatile private var millis = System.currentTimeMillis()

    val b1, b2, b3, b4, b5, b6, b7 = 0L // padding

    private var start = System.nanoTime()

    executor.execute(() =>
        while (!_stop) update()
    )

    private def update(): Unit = {
        millis = System.currentTimeMillis()
        val end     = System.nanoTime()
        val elapsed = Math.max(0, end - start)
        start = end
        LockSupport.parkNanos(1000000L - elapsed)
    }

    def currentMillis(): Long = millis

    def stop(): Unit = {
        gauge.close()
        _stop = true
    }

    private val gauge = {
        val receiver = MetricReceiver.get
        val scope    = statsScope("clock")
        receiver.gauge(scope, "skew")((System.currentTimeMillis() - millis).toDouble)
    }

}
