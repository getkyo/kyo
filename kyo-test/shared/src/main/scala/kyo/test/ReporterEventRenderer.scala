package zio.test

import zio.Chunk
import zio.Trace
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.test.render.ConsoleRenderer
import zio.test.render.IntelliJRenderer

trait ReporterEventRenderer:
    def render(event: ExecutionEvent)(implicit trace: Trace): Chunk[String]
object ReporterEventRenderer:
    object ConsoleEventRenderer extends ReporterEventRenderer:
        override def render(executionEvent: ExecutionEvent)(implicit trace: Trace): Chunk[String] =
            Chunk.fromIterable(
                ConsoleRenderer
                    .render(executionEvent, includeCause = true)
            )
    end ConsoleEventRenderer

    object IntelliJEventRenderer extends ReporterEventRenderer:
        override def render(executionEvent: ExecutionEvent)(implicit trace: Trace): Chunk[String] =
            Chunk.fromIterable(
                IntelliJRenderer
                    .render(executionEvent, includeCause = true)
            )
    end IntelliJEventRenderer
end ReporterEventRenderer
