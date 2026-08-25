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
  *   - `FlowDomainException`, the open branch, extended by a step's own domain failures
  *
  * Catch them with `Abort.run[FlowException]` (all errors), `Abort.run[FlowSignalException]` (signal errors only), etc. The HTTP API
  * translates them to appropriate status codes (404 for not-found, 409 for conflict, 400 for bad request).
  *
  * @see
  *   [[kyo.FlowEngine]] Operations that may fail with these exceptions
  * @see
  *   [[kyo.Flow]] The workflow definition DSL
  */
sealed abstract class FlowException(message: String, cause: String | Throwable = "")(using Frame)
    extends KyoException(message, cause):

    /** The name this failure is recorded under in [[Flow.Status.Failed.kind]], so a persisted failure can be grouped by what it was. */
    def kind: String = getClass.getSimpleName
end FlowException

// --- Workflow errors ---

/** Workflow lookup or registration failures. */
sealed abstract class FlowWorkflowException(message: String)(using Frame)
    extends FlowException(message)

/** Thrown when looking up a workflow by ID in the store and it doesn't exist. */
case class FlowWorkflowNotFoundException(workflowId: String)(using Frame)
    extends FlowWorkflowException(s"Workflow '$workflowId' not found")

/** Thrown when starting an execution for a workflow that hasn't been registered with this engine. */
case class FlowWorkflowNotRegisteredException(workflowId: String)(using Frame)
    extends FlowWorkflowException(s"Workflow '$workflowId' not registered")

// --- Execution state errors ---

/** Execution state or lifecycle failures. */
sealed abstract class FlowExecutionStateException(message: String)(using Frame)
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
sealed abstract class FlowSignalException(message: String)(using Frame)
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

/** A failure of the store behind an execution: the database was unreachable, a statement was rejected, a row would not decode.
  *
  * The error channel every [[FlowStore]] method carries, and the reason it exists: a store implementation talks to something that fails,
  * and without a channel its only way to report a failure was to panic, which threw away a typed and often classified error at the SPI
  * boundary. A deadlock on the claim UPDATE is the case that makes it concrete: the database says "retry this", the store knows that, and
  * the engine could not be told.
  *
  * Open, because the failures a store can have are the failures of whatever it is built on, and only the implementation can name them. A
  * store over kyo-sql wraps `SqlException`; one over a file wraps an IO failure.
  *
  * The engine treats a failure of the poll loop as transient and keeps polling, recording it on [[FlowEngine.health]] (see
  * [[FlowEngine.worker]]). A failure raised while an execution is being run fails that execution, which is what a store failure mid-step
  * has always done.
  *
  * @see
  *   [[FlowStoreException.Retryable]] the marker a store puts on a failure the caller may safely retry
  */
abstract class FlowStoreException(message: String, cause: String | Throwable = "")(using Frame)
    extends KyoException(message, cause):

    /** The name this failure is recorded under in [[Flow.Status.Failed.kind]], as a [[FlowException]]'s is.
      *
      * A store names its own failures, so the class of the one that killed an execution is the only description of it worth keeping: what a
      * deadlock is called by the store that raised it is what a query over failed executions groups by. Without it every store failure is
      * recorded as an unclassified message and can only be told apart by matching its text.
      */
    def kind: String = getClass.getSimpleName
end FlowStoreException

object FlowStoreException:

    /** Property marker: this failure is transient and the operation may be retried unchanged.
      *
      * A store puts it on the failures its backend classifies that way (a deadlock, a serialization failure, a lost connection), so the
      * engine can tell "try again" from "this will fail the same way forever" without knowing what a backend's error codes mean.
      */
    trait Retryable

end FlowStoreException

/** The base a step's own domain failure extends, and the one branch of this hierarchy that is open.
  *
  * A step's runner has to produce `Abort[FlowException]`, so without an open branch a domain failure had nowhere typed to go: declining a
  * charge, rejecting an order, a business rule saying no. Those are not engine errors, and turning them into panics threw away the type on
  * the way to a status that keeps a string.
  *
  * A failure extending this reaches the engine as a failure rather than a panic, and the status it produces carries its class name as
  * [[Flow.Status.Failed.kind]], so "how many executions failed for payment reasons" is a query over a field rather than a LIKE over an
  * error message.
  *
  * {{{
  * case class ChargeDeclined(orderId: String, cents: Long)(using Frame)
  *     extends FlowDomainException(s"charge declined for \$orderId: \$cents cents over limit")
  * }}}
  */
abstract class FlowDomainException(message: String, cause: String | Throwable = "")(using Frame)
    extends FlowException(message, cause)

// --- Execution lifecycle ---

/** Thrown by `runLocal` when the flow execution fails. */
case class FlowExecutionFailedException(executionId: String, error: String)(using Frame)
    extends FlowException(s"Flow execution '$executionId' failed: $error")

/** Thrown when the engine detects a cancelled execution during input resolution. */
case class FlowCancelledException(executionId: String)(using Frame)
    extends FlowException(s"Flow execution '$executionId' was cancelled")
