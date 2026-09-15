package kyo.test.runner

import org.scalajs.macrotaskexecutor.MacrotaskExecutor
import scala.concurrent.ExecutionContext

/** Scala.js: uses the MacrotaskExecutor so ScalaTest's AsyncFreeSpec can pump the JS event loop between test steps. Without this, Kyo's
  * macrotask-based fiber scheduler never gets to run and ScalaTest throws "Queue is empty while future is not completed".
  */
object TestExecutionContext:
    val executionContext: ExecutionContext = MacrotaskExecutor
end TestExecutionContext
