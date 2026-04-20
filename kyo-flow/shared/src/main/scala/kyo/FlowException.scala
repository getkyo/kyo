package kyo

/** Base exception for all flow-related errors, used with `Abort[FlowException]`.
  *
  * Flow exceptions are thrown (via `Abort.fail`) by engine operations when something goes wrong: starting a workflow that isn't registered,
  * signaling an input with the wrong type, delivering to a completed execution, etc. They extend `KyoException` (which includes
  * `NoStackTrace`) and capture the call site via `Frame` for diagnostics.
  *
  * The hierarchy has intermediate sealed types for natural groupings:
  *   - `FlowWorkflowException` — workflow lookup failures
  *   - `FlowExecutionStateException` — execution state/lifecycle failures
  *   - `FlowSignalException` — input signal delivery failures
  *
  * Catch them with `Abort.run[FlowException]` (all errors), `Abort.run[FlowSignalException]` (signal errors only), etc. The HTTP API
  * translates them to appropriate status codes (404 for not-found, 409 for conflict, 400 for bad request).
  *
  * @see
  *   [[kyo.FlowEngine]] Operations that may fail with these exceptions
  * @see
  *   [[kyo.Flow]] The workflow definition DSL
  */
sealed abstract class FlowException(message: Text, cause: Text | Throwable = "")(using Frame)
    extends KyoException(message, cause)

// --- Workflow errors ---

/** Workflow lookup or registration failures. */
sealed abstract class FlowWorkflowException(message: Text)(using Frame)
    extends FlowException(message)

/** Thrown when looking up a workflow by ID in the store and it doesn't exist. */
case class FlowWorkflowNotFoundException(workflowId: String)(using Frame)
    extends FlowWorkflowException(s"Workflow '$workflowId' not found")

/** Thrown when starting an execution for a workflow that hasn't been registered with this engine. */
case class FlowWorkflowNotRegisteredException(workflowId: String)(using Frame)
    extends FlowWorkflowException(s"Workflow '$workflowId' not registered")

// --- Execution state errors ---

/** Execution state or lifecycle failures. */
sealed abstract class FlowExecutionStateException(message: Text)(using Frame)
    extends FlowException(message)

/** Thrown when creating an execution with an ID that already exists. */
case class FlowDuplicateExecutionException(executionId: String)(using Frame)
    extends FlowExecutionStateException(s"Execution '$executionId' already exists")

/** Thrown when looking up an execution by ID and it doesn't exist. */
case class FlowExecutionNotFoundException(executionId: String)(using Frame)
    extends FlowExecutionStateException(s"Execution '$executionId' not found")

/** Thrown when signaling an execution that has already reached a terminal status (Completed, Failed, or Cancelled). */
case class FlowExecutionTerminalException(executionId: String, status: Flow.Status)(using Frame)
    extends FlowExecutionStateException(s"Cannot signal execution '$executionId' in terminal status: ${status.show}")

// --- Signal errors ---

/** Input signal delivery failures. */
sealed abstract class FlowSignalException(message: Text)(using Frame)
    extends FlowException(message)

/** Thrown when signaling an input name that doesn't exist in the workflow definition. */
case class FlowSignalNotFoundException(inputName: String, executionId: String)(using Frame)
    extends FlowSignalException(s"No input '$inputName' in execution '$executionId'")

/** Thrown when the signal value type doesn't match the workflow's declared input type. */
case class FlowSignalTypeMismatchException(inputName: String, expected: String, got: String)(using Frame)
    extends FlowSignalException(s"Type mismatch for input '$inputName': expected $expected, got $got")

/** Thrown when delivering an input that was already delivered (signals are exactly-once via putFieldIfAbsent). */
case class FlowInputAlreadyDeliveredException(executionId: String, inputName: String)(using Frame)
    extends FlowSignalException(s"Input '$inputName' was already delivered in execution '$executionId'")

// --- Execution lifecycle ---

/** Thrown by `runLocal` when the flow execution fails. */
case class FlowExecutionFailedException(executionId: String, error: String)(using Frame)
    extends FlowException(s"Flow execution '$executionId' failed: $error")

/** Thrown when the engine detects a cancelled execution during input resolution. */
case class FlowCancelledException(executionId: String)(using Frame)
    extends FlowException(s"Flow execution '$executionId' was cancelled")
