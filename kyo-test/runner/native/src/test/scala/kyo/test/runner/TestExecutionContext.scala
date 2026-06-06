package kyo.test.runner

import scala.concurrent.ExecutionContext

/** Scala Native: uses the global executor for AsyncFreeSpec tests. */
object TestExecutionContext:
    val executionContext: ExecutionContext = ExecutionContext.global
end TestExecutionContext
