package kyo.scheduler

import java.util.concurrent.Executor
import java.util.concurrent.locks.LockSupport

final private class InternalClock(executor: Executor):

    @volatile private var _stop = false

    @volatile private var millis = System.currentTimeMillis()

    val a1, a2, a3, a4, a5, a6, a7 = 0L // padding

    private var start = System.nanoTime()

    executor.execute(() =>
        while !_stop do
            update()
    )

    private def update(): Unit =
        millis = System.currentTimeMillis()
        val end     = System.nanoTime()
        val elapsed = Math.max(0, end - start)
        start = end
        LockSupport.parkNanos(1000000L - elapsed)
    end update

    def currentMillis(): Long = millis

    def stop(): Unit = _stop = true

end InternalClock
