package kyo.test

import kyo.*
import kyo.kernel.*
import kyo.test.ExecutionEvent
import kyo.test.TestLogger
import kyo.test.render.ReporterEventRenderer

trait ExecutionEventConsolePrinter:
    def print(event: ExecutionEvent): Unit < IO

object ExecutionEventConsolePrinter:
    def live(renderer: ReporterEventRenderer)(using Frame): Layer[TestLogger, ExecutionEventConsolePrinter] =
        Env.get[TestLogger].map(testLogger => Live(testLogger, renderer))

    case class Live(logger: TestLogger, eventRenderer: ReporterEventRenderer) extends ExecutionEventConsolePrinter:
        override def print(event: ExecutionEvent): Unit < IO =
            val rendered = eventRenderer.render(event)
            if rendered.nonEmpty then
                logger.logLine(rendered.mkString("\n"))
            else
                (
            )
            end if
        end print
    end Live
end ExecutionEventConsolePrinter
