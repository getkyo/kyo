package kyo

import cats.effect.unsafe.IORuntime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

object KyoSchedulerIORuntime {
    implicit lazy val global: IORuntime = {
        val scheduler = new cats.effect.unsafe.Scheduler {
            val scheduledExecutor = Executors.newScheduledThreadPool(2)
            def sleep(delay: FiniteDuration, task: Runnable): Runnable = {
                val t = scheduledExecutor.schedule(task, delay.toNanos, TimeUnit.NANOSECONDS)
                () => {
                    t.cancel(false)
                    ()
                }
            }
            def monotonicNanos(): Long = System.nanoTime()
            def nowMillis(): Long      = System.currentTimeMillis()
        }
        val kyoExecutor = kyo.scheduler.Scheduler.get.asExecutionContext
        IORuntime(kyoExecutor, kyoExecutor, scheduler, () => (), IORuntime.global.config)
    }
}
