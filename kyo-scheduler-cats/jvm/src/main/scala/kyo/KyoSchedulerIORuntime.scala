package kyo

import cats.effect.unsafe.IORuntime
import cats.effect.unsafe.IORuntime.global

object KyoSchedulerIORuntime {
    implicit lazy val global: IORuntime = {
        val exec = kyo.scheduler.Scheduler.get.asExecutionContext
        IORuntime(exec, exec, IORuntime.global.scheduler, () => (), IORuntime.global.config)
    }
}
