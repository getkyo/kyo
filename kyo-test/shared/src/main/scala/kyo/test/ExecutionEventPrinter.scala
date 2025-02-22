package kyo.test

import kyo.*
import kyo.Combinators.*
import kyo.Constructors.*
import kyo.kernel.*
import kyo.test.ExecutionEvent
import kyo.test.ExecutionEventConsolePrinter
import kyo.test.ResultPrinter
import kyo.test.TestLogger
import kyo.test.render.ReporterEventRenderer

trait ExecutionEventPrinter:
    def print(event: ExecutionEvent): Unit < IO

object ExecutionEventPrinter:
    case class Live(console: ExecutionEventConsolePrinter, file: ResultPrinter) extends ExecutionEventPrinter:
        override def print(event: ExecutionEvent): Unit < IO =
            console.print(event) *>
                (event match
                    case testResult: ExecutionEvent.Test[?] => file.print(testResult)
                    case _                                  => IO(()))
    end Live

    // Constructs a layer that provides an ExecutionEventPrinter using dependencies TestLogger and ResultPrinter
    def live(eventRenderer: ReporterEventRenderer)(using Frame): Layer[(TestLogger, ResultPrinter), ExecutionEventPrinter] =
        for
            console <- ExecutionEventConsolePrinter.live(eventRenderer)
            file    <- ResultPrinter.json
        yield Live(console, file)

    def print(event: ExecutionEvent): Unit < (IO & Env[ExecutionEventPrinter]) =
        Env.get[ExecutionEventPrinter].map(_.print(event)).flatten
end ExecutionEventPrinter
