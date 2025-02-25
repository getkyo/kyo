package kyo.test

import kyo.*
import kyo.kernel.*
import kyo.test.ExecutionEvent
import kyo.test.ReporterEventRenderer
import kyo.test.TestLogger

trait ExecutionEventConsolePrinter:
    def print(event: ExecutionEvent)(using Trace): Unit < IO

object ExecutionEventConsolePrinter:
    def live(renderer: ReporterEventRenderer)(using Frame): Layer[ExecutionEventConsolePrinter, Env[TestLogger & ReporterEventRenderer]] =
        Layer.from[TestLogger, ReporterEventRenderer, ExecutionEventConsolePrinter, Any]((logger, renderer) => Live(logger, renderer))

    case class Live(logger: TestLogger, eventRenderer: ReporterEventRenderer) extends ExecutionEventConsolePrinter:
        override def print(event: ExecutionEvent)(using Trace): Unit < IO =
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
