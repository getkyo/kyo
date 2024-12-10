package kyo

import cats.effect.IOApp
import cats.effect.unsafe.IORuntime
import cats.effect.unsafe.IORuntime.global

trait KyoSchedulerIOApp extends IOApp {
    override def runtime = KyoSchedulerIORuntime.global
}
