package kyo.test.results

import kyo.ZIO
import kyo.ZLayer
import kyo.test.ExecutionEvent

trait ResultPrinter:
    def print[E](event: ExecutionEvent.Test[E]): ZIO[Any, Nothing, Unit]

object ResultPrinter:
    val json: ZLayer[Any, Nothing, ResultPrinter] = ResultPrinterJson.live
