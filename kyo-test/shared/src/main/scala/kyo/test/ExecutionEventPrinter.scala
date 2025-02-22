package zio.test

import zio.Console
import zio.ZIO
import zio.ZLayer
import zio.test.results.ResultPrinter

private[test] trait ExecutionEventPrinter:
    def print(event: ExecutionEvent): ZIO[Any, Nothing, Unit]

private[test] object ExecutionEventPrinter:
    case class Live(console: ExecutionEventConsolePrinter, file: ResultPrinter) extends ExecutionEventPrinter:
        override def print(event: ExecutionEvent): ZIO[Any, Nothing, Unit] =
            console.print(event) *>
                (event match
                    case testResult: ExecutionEvent.Test[?] => file.print(testResult)
                    case _                                  => ZIO.unit)
    end Live

    def live(console: Console, eventRenderer: ReporterEventRenderer): ZLayer[Any, Nothing, ExecutionEventPrinter] =
        ZLayer.make[ExecutionEventPrinter](
            ResultPrinter.json,
            ExecutionEventConsolePrinter.live(eventRenderer),
            TestLogger.fromConsole(console),
            ZLayer.fromFunction(Live.apply _)
        )

    def print(event: ExecutionEvent): ZIO[ExecutionEventPrinter, Nothing, Unit] =
        ZIO.serviceWithZIO(_.print(event))
end ExecutionEventPrinter
