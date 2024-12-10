package kyo

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp

object KyoSchedulerIOAppTest extends KyoSchedulerIOApp {

    override def run(args: List[String]): IO[ExitCode] =
        IO {
            if (!Thread.currentThread().getName().contains("kyo")) {
                println("Not using Kyo Scheduler")
                ExitCode.Error
            } else {
                ExitCode.Success
            }
        }
}
