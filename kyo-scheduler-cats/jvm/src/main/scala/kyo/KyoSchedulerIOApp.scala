package kyo

import cats.effect.IOApp
import cats.effect.unsafe.IORuntime

trait KyoSchedulerIOApp extends IOApp {
    override def runtime = KyoSchedulerIORuntime.global
}
