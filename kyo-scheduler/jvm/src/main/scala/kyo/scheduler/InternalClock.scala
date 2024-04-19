package kyo.scheduler

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.locks.LockSupport
import kyo.scheduler.util.Threads

final private[kyo] class InternalClock(executor: Executor):
    @volatile private var _stop  = false
    @volatile private var nanos  = System.nanoTime()
    @volatile private var millis = System.currentTimeMillis()

    executor.execute(() =>
        while !_stop do
            update()
    )

    private def update(): Unit =
        val prevNanos = nanos
        val nowNanos  = System.nanoTime()
        nanos = nowNanos
        millis = System.currentTimeMillis()
        val elapsed = Math.max(0, nowNanos - prevNanos)
        LockSupport.parkNanos(1000000L - elapsed)
    end update

    def currentMillis(): Long = millis

    def stop(): Unit = _stop = true

end InternalClock

private[kyo] object InternalClock:
    lazy val defaultExecutor = Executors.newSingleThreadExecutor(Threads("kyo-scheduler-clock"))
