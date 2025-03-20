/*
 * Converted from zio-test ReporterEventRenderer.scala to Kyo
 * Changes:
 * - Package changed from zio.test to kyo.test
 * - Removed implicit Trace parameter
 * - Updated imports to refer to kyo-test equivalents
 */

package kyo.test

import kyo.*
import kyo.test.render.ConsoleRenderer
import kyo.test.render.IntelliJRenderer

trait ReporterEventRenderer:
    def render(event: ExecutionEvent)(implicit trace: Trace): Chunk[String]

object ReporterEventRenderer:
    object ConsoleEventRenderer extends ReporterEventRenderer:
        override def render(executionEvent: ExecutionEvent)(implicit trace: Trace): Chunk[String] =
            Chunk.Indexed.from(
                ConsoleRenderer.render(executionEvent, includeCause = true)
            )
    end ConsoleEventRenderer

    object IntelliJEventRenderer extends ReporterEventRenderer:
        override def render(executionEvent: ExecutionEvent)(implicit trace: Trace): Chunk[String] =
            Chunk.Indexed.from(
                IntelliJRenderer.render(executionEvent, includeCause = true)
            )
    end IntelliJEventRenderer
end ReporterEventRenderer
