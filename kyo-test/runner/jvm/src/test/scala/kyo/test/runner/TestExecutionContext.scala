package kyo.test.runner

import scala.concurrent.ExecutionContext

/** JVM: uses the global thread-pool executor for AsyncFreeSpec tests. */
object TestExecutionContext:
    val executionContext: ExecutionContext = ExecutionContext.global
end TestExecutionContext
