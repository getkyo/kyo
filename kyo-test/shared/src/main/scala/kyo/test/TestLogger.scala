package kyo.test

import zio.{Console, UIO, URIO, ZIO, ZLayer, Trace}
import zio.stacktracer.TracingImplicits.disableAutoTrace

trait TestLogger extends Serializable {
  def logLine(line: String)(implicit trace: Trace): UIO[Unit]
}

object TestLogger {

  def fromConsole(console: Console)(implicit trace: Trace): ZLayer[Any, Nothing, TestLogger] =
    ZLayer.succeed {
      new TestLogger {
        def logLine(line: String)(implicit trace: Trace): UIO[Unit] = console.printLine(line).orDie
      }
    }

  def logLine(line: String)(implicit trace: Trace): URIO[TestLogger, Unit] =
    ZIO.serviceWithZIO(_.logLine(line))
}
