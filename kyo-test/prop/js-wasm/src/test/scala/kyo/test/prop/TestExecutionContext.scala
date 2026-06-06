package kyo.test.prop

import org.scalajs.macrotaskexecutor.MacrotaskExecutor
import scala.concurrent.ExecutionContext

/** Scala.js: uses MacrotaskExecutor so ScalaTest's AsyncFreeSpec pumps the JS event loop between steps. */
object TestExecutionContext:
    val executionContext: ExecutionContext = MacrotaskExecutor
end TestExecutionContext
