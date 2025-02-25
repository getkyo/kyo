package kyo.test.results

import kyo.*
import kyo.test.ExecutionEvent
import kyo.test.Trace
import kyo.test.results.ResultPrinterJson

trait ResultPrinter:
    def print[E](event: ExecutionEvent.Test[E])(using Trace): Unit < IO

object ResultPrinter:
    val json: Layer[ResultPrinter, Any] = ResultPrinterJson.live
