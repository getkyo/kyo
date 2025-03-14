package kyo.test

import kyo.*
import kyo.kernel.*
import kyo.test.ExecutionEvent
import kyo.test.ExecutionEventConsolePrinter
import kyo.test.ReporterEventRenderer
import kyo.test.TestLogger
import kyo.test.results.ResultPrinter

trait ExecutionEventPrinter:
    def print(event: ExecutionEvent)(using Trace): Unit < IO

object ExecutionEventPrinter:
    case class Live(console: ExecutionEventConsolePrinter, file: ResultPrinter) extends ExecutionEventPrinter:
        override def print(event: ExecutionEvent)(using Trace): Unit < IO =
            console.print(event) *>
                (event match
                    case testResult: ExecutionEvent.Test[?] => file.print(testResult)
                    case _                                  => IO(()))
    end Live

    // Constructs a layer that provides an ExecutionEventPrinter using dependencies TestLogger and ResultPrinter
    def live(console: Console, eventRenderer: ReporterEventRenderer)(using Frame): Layer[ExecutionEventPrinter, Any] =
        Layer.init[ExecutionEventPrinter](
            ResultPrinter.json,
            ExecutionEventConsolePrinter.live(eventRenderer),
            TestLogger.fromConsole(console),
            Layer.from[ExecutionEventConsolePrinter, ResultPrinter, ExecutionEventPrinter, Any]((ev, prnt) => Live(ev, prnt))
        )

    def print(event: ExecutionEvent)(using Trace): Unit < (IO & Env[ExecutionEventPrinter]) =
        Env.get[ExecutionEventPrinter].map(_.print(event))
end ExecutionEventPrinter
