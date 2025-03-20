package kyo.test.results

import kyo.*
import kyo.test.*

private[test] object ResultPrinterJson:
    val live: Layer[ResultPrinter, Any] =
        Layer.init(
            ResultSerializer.live,
            ResultFileOps.live,
            Layer.from((serializer, resultFileOps) => LiveImpl(serializer, resultFileOps))
        )

    private case class LiveImpl(serializer: ResultSerializer, resultFileOps: ResultFileOps) extends ResultPrinter:
        override def print[E](event: ExecutionEvent.Test[E]): Unit < (Env[Any] & Abort[Nothing]) =
            resultFileOps.write(serializer.render(event), append = true).orPanic
end ResultPrinterJson
