package kyo

/** Base exception for all flow-related errors, used with `Abort[FlowException]`.
  *
  * Flow exceptions are thrown (via `Abort.fail`) by engine operations when something goes wrong: starting a workflow that isn't registered,
  * signaling an input with the wrong type, delivering to a completed execution, etc. They extend `KyoException` (which includes
  * `NoStackTrace`) and capture the call site via `Frame` for diagnostics.
  *
  * The hierarchy has intermediate sealed types for natural groupings:
  *   - `FlowWorkflowException`, workflow lookup failures
  *   - `FlowDefinitionException`, a definition refused at registration
  *   - `FlowExecutionStateException`, execution state/lifecycle failures
  *   - `FlowSignalException`, input signal delivery failures
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

// --- Definition errors ---

/** A definition refused at registration, because nothing it could do afterwards would be right.
  *
  * The failures here are properties of the flow VALUE rather than of the store or of an execution, so they are decided once, when the
  * definition is handed to an engine, and never again. Registration is the only place with both the whole definition and an effect row to
  * report on: a constructor sees one node at a time and can only throw.
  *
  * @see
  *   [[FlowDuplicateNameException]] two nodes writing one durable name
  * @see
  *   [[FlowReservedNameException]] a node name using a character the engine reserves
  * @see
  *   [[FlowUnnamedException]] a flow with no name to be registered under
  */
sealed abstract class FlowDefinitionException(message: String)(using Frame)
    extends FlowWorkflowException(message)

/** One durable name claimed by more than one node, with where each claim is and how the two are composed.
  *
  * Carried by [[FlowDuplicateNameException]] so a caller can act on the collision rather than parse a message for it. A claim is a write in
  * at least one of the two nodes: a name only read by both is no conflict, and a name one node writes while another reads it is, because
  * the write lands on the field the read already occupies.
  *
  * @param name
  *   the name the nodes share
  * @param composition
  *   how the two nodes reach each other: in sequence, or through a named combinator's branches
  * @param locations
  *   the call site of each node, in the order the fold reached them
  */
case class FlowNameConflict(name: String, composition: String, locations: Seq[String]) derives CanEqual:
    /** The one-line rendering [[FlowDuplicateNameException]] lists this conflict under. */
    def show: String = s"'$name', shared $composition, at ${locations.mkString(" and ")}"

/** Thrown when registering a flow in which one durable name is written by more than one node.
  *
  * A node's name IS its durable key: its field, its completion mark and its schema entry all use it. Two nodes writing one name therefore
  * share one field and one completion mark, so the second's value overwrites the first's and replay, which decides what is already done
  * from the set of completed names, skips the second forever after any suspension. Neither node is addressable and the record is not
  * readable, so there is no contract under which the flow means what it looks like.
  *
  * What is NOT refused is as load-bearing as what is. A name only READ, an `input` two branches wait on, is one wait and one field with
  * nothing ambiguous about it. A name written by the branches of a `race` is the canonical race rather than a collision: the result type is
  * the union of the two branches, readable downstream only through a field both carry, and replay depends on the shared key to keep its
  * winner. What guards that one narrow case is the store's write-once progress record, not this refusal.
  *
  * @param workflowId
  *   the workflow the definition was offered under
  * @param conflicts
  *   every collision found, one entry per name
  */
case class FlowDuplicateNameException(workflowId: String, conflicts: Seq[FlowNameConflict])(using Frame)
    extends FlowDefinitionException(FlowDuplicateNameException.describe(workflowId, conflicts))

object FlowDuplicateNameException:

    /** The remediation is part of the message because the obvious reading of "duplicate name" sends a user to rename their steps, and for
      * branches that run the same work that is the wrong fix: the branches are supposed to share a name, and what they need is a distinct
      * durable path each.
      */
    private[kyo] def describe(workflowId: String, conflicts: Seq[FlowNameConflict]): String =
        val listed   = conflicts.map(c => s"\n  ${c.show}").mkString
        val parallel = conflicts.exists(_.composition.contains("branches"))
        val remedy =
            if parallel then
                "\nGive each branch a durable path of its own: wrap it in a subflow with its own name, or, when the parallelism is " +
                    "over runtime data rather than over distinct flows, use foreach, whose items are identified one per item."
            else
                "\nGive the nodes different names: each name is one field and one completion mark, so the second write overwrites " +
                    "the first and replay skips the second."
        val counted =
            if conflicts.size == 1 then "one durable name is claimed by more than one node"
            else s"${conflicts.size} durable names are claimed by more than one node"
        s"Workflow '$workflowId' cannot be registered, $counted:$listed$remedy"
    end describe
end FlowDuplicateNameException

/** Thrown when registering a flow with a node name that uses a character the engine reserves.
  *
  * Two characters are reserved, because the engine builds durable keys with both. `#` separates a scheduled loop's iterations, recorded as
  * `loop#0` and `loop#1`, and a dispatch's chosen branch, recorded as `name#chosen`. `~` joins a subflow instance to a node inside it, so a
  * node called `step` inside instance `review` is durably `review~step`. A user name carrying either can collide with a key the engine
  * generates, which is a corruption no test of the flow itself would show.
  *
  * @param workflowId
  *   the workflow the definition was offered under
  * @param names
  *   every node name that uses a reserved character
  */
case class FlowReservedNameException(workflowId: String, names: Seq[String])(using Frame)
    extends FlowDefinitionException(
        s"Workflow '$workflowId' cannot be registered, ${names.map(n => s"'$n'").mkString(", ")} " +
            "use a reserved character: '#', which the engine uses to build the durable keys of loop iterations and dispatch " +
            "choices, or '~', which joins a subflow instance to the nodes inside it"
    )

/** Thrown when registering a flow that has no name.
  *
  * The name is the workflow's durable identity, so a flow without one cannot be started, resumed or found. It arrives here rather than from
  * the constructor because registration is the first point with an effect row: a caller handling `Abort[FlowDefinitionException]` sees this
  * refusal the same way it sees every other, instead of an untyped throw no union names.
  */
case class FlowUnnamedException()(using Frame)
    extends FlowDefinitionException(
        "A flow must be named before it is registered: use Flow.init(\"name\"), since the name is the workflow's durable identity"
    )

// --- Engine configuration errors ---

/** One tuning value the engine cannot work under, with what it was given and what it would do.
  *
  * Carried by [[FlowInvalidConfigException]] so a caller can act on the setting rather than parse a message for it, the same reason
  * [[FlowNameConflict]] is a value beside its exception.
  *
  * @param setting
  *   the [[FlowEngine.Config]] field that cannot be used
  * @param value
  *   what that field was given, rendered
  * @param reason
  *   what the engine would do under it
  */
case class FlowConfigProblem(setting: String, value: String, reason: String) derives CanEqual:
    /** The one-line rendering [[FlowInvalidConfigException]] lists this problem under. */
    def show: String = s"$setting = $value: $reason"

/** Thrown when an engine is asked for with a tuning under which it could never do its work.
  *
  * The values are refused at `FlowEngine.init` rather than clamped, because every one of them is a caller saying something specific that
  * the engine cannot honour, and a silently corrected tuning is an engine running under settings its operator never chose. What they have
  * in common is that the engine would keep answering its health check while making no progress: no worker to poll with, no room in a
  * batch to claim into, or a lease that is over before it is written.
  *
  * The renewal interval is the one that is harmful rather than merely inert. A `renewEvery` no shorter than `lease` means the claim has
  * already expired when the first renewal is presented, so the renewal is refused, the refusal interrupts the execution, and every step
  * longer than the lease is interrupted, released, reclaimed and re-run forever, writing several history events per turn.
  *
  * Every problem is reported at once rather than the first one found, because a caller who fixes them one at a time pays a process start
  * per problem.
  *
  * @param problems
  *   every tuning that cannot be used, one entry per setting
  */
case class FlowInvalidConfigException(problems: Seq[FlowConfigProblem])(using Frame)
    extends FlowException(FlowInvalidConfigException.describe(problems))

object FlowInvalidConfigException:
    private[kyo] def describe(problems: Seq[FlowConfigProblem]): String =
        val listed = problems.map(p => s"\n  ${p.show}").mkString
        val counted =
            if problems.size == 1 then "one setting cannot be used"
            else s"${problems.size} settings cannot be used"
        s"The engine cannot be created, $counted:$listed"
    end describe
end FlowInvalidConfigException

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

/** Thrown when delivering an input that was already delivered.
  *
  * Delivery is exactly-once, and the store is what decides: [[FlowStore.signal]] writes the field and its arrival in one transition
  * and answers [[FlowStore.SignalOutcome.AlreadyDelivered]] to every later delivery of the same name. What makes the promise true is
  * that store-judged transition rather than a check the caller runs first.
  */
case class FlowInputAlreadyDeliveredException(executionId: String, inputName: String)(using Frame)
    extends FlowSignalException(s"Input '$inputName' was already delivered in execution '$executionId'")

/** A failure of the store behind an execution: the database was unreachable, a statement was rejected, a row would not decode.
  *
  * The error channel every [[FlowStore]] method carries, and the reason it exists: a store implementation talks to something that fails,
  * and without a channel its only way to report a failure is to panic, which throws away a typed and often classified error at the SPI
  * boundary. A deadlock on the claim UPDATE makes it concrete: the database says "retry this", the store knows that, and there is no way
  * to tell the engine.
  *
  * Open, because the failures a store can have are the failures of whatever it is built on, and only the implementation can name them. A
  * store over kyo-sql wraps `SqlException`; one over a file wraps an IO failure.
  *
  * The engine treats a failure of the poll loop as transient and keeps polling, recording it on [[FlowEngine.health]] (see
  * [[FlowEngine.worker]]), and it treats a failure raised while an execution is being run the same way. A store that could not be
  * reached has said nothing about the work, so the attempt is charged to health and the execution is left exactly as claimable as it
  * was, for the next poll to try again, rather than terminalising an execution on an outage it did not cause.
  *
  * @see
  *   [[FlowStoreException.Retryable]] the marker a store puts on a failure the caller may safely retry
  */
abstract class FlowStoreException(message: String, cause: String | Throwable = "")(using Frame)
    extends KyoException(message, cause):

    /** The store's own name for this failure, so one store failure can be told from another by what it is.
      *
      * A store names its failures, and the class it gives one is the only description of it worth keeping: what a deadlock is called by
      * the store that raised it is how a deadlock is recognised again. Without it a store failure carries nothing but a message, and
      * telling two apart means matching their text.
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

// --- Step errors ---

/** Raised when a step does not finish within the `timeout` its [[Flow.Meta]] declared.
  *
  * A timeout is a policy the caller declared on the node itself, so the failure that enforces it is a declared failure of this flow and
  * carries a kind like any other: "how many executions timed out" is then a query over [[Flow.Status.Failed.kind]] rather than a LIKE
  * over message text. It reaches the engine as a failure and not as a panic, for the reason [[FlowDomainException]] states in as many
  * words about turning a typed error into one.
  *
  * It is also the one flow failure a step's retry schedule re-asks. Declaring `timeout` and `retry` together is what the two `Meta`
  * fields are for: a slow attempt is a measurement rather than a verdict, and the schedule exists to take another one. Once the
  * schedule is exhausted the timeout terminalises the execution with its kind.
  *
  * @param stepName
  *   the node whose `timeout` was exceeded
  * @param timeout
  *   the bound that was declared and exceeded
  */
case class FlowStepTimeoutException(stepName: String, timeout: Duration)(using Frame)
    extends FlowException(s"Step '$stepName' timed out after ${timeout.show}")

/** Raised when a scheduled loop's schedule runs out before its body produced a value.
  *
  * A flow declaring `N ~ V` promises a `V`, and a `loopOn` whose schedule is spent has none to store, so running out is a failure of the
  * flow rather than a success. Nothing is written under the loop's name: the last state is the loop's `A` and the name is typed `V`, and
  * writing one where the other is declared is sound only where the two coincide. It carries a kind like any other declared failure, so
  * "how many executions ran their loop out" is a query over [[Flow.Status.Failed.kind]] rather than a LIKE over message text.
  *
  * @param loopName
  *   the scheduled loop whose schedule was exhausted
  * @param iterations
  *   how many iterations ran before the schedule had no delay left
  */
case class FlowLoopExhaustedException(loopName: String, iterations: Int)(using Frame)
    extends FlowException(s"Loop '$loopName' exhausted its schedule after $iterations iteration(s) without producing a value")

/** Raised when a fan-out's collection answers a different size on the attempt that resumes it.
  *
  * A `foreach` records each item's result under the item's own durable name and the count it started with beside them, which is what lets
  * a resumed fan-out skip the items that already ran. That identity is positional, so it holds only while the collection is a
  * deterministic function of the record, and a collection that answers a different size has broken it: the results recorded under the old
  * positions belong to items the new collection may no longer contain.
  *
  * The node fails rather than running the fan-out again. Re-running it would write onto paths that already carry the previous attempt's
  * results, hand each per-item compensation handler a value from a run it did not belong to, and re-fire every item's side effects on the
  * way, which is both of the outcomes the recorded count exists to prevent. It carries a kind like any other declared failure, so "how
  * many executions had a collection change under them" is a query over [[Flow.Status.Failed.kind]] rather than a search over message text.
  *
  * @param nodeName
  *   the fan-out whose collection changed, by its durable name
  * @param recorded
  *   how many items the fan-out recorded when it started
  * @param recomputed
  *   how many the collection answered on the attempt that resumed it
  */
case class FlowNondeterministicCollectionException(nodeName: String, recorded: Int, recomputed: Int)(using Frame)
    extends FlowException(
        s"Foreach '$nodeName' recorded $recorded item(s) and its collection recomputed to $recomputed on replay; " +
            "a fan-out's collection must be a deterministic function of the record"
    )

/** The base a step's own domain failure extends, and the one branch of this hierarchy that is open.
  *
  * A step's runner has to produce `Abort[FlowException]`, so without an open branch a domain failure has nowhere typed to go: declining a
  * charge, rejecting an order, a business rule saying no. Those are not engine errors, and turning them into panics throws away the type
  * on the way to a status that keeps a string.
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

/** Raised at a node boundary when somebody has asked for the execution to be cancelled.
  *
  * A cancel is a REQUEST rather than a terminal write: raising it here is what stops the forward pass at the next boundary and takes the
  * execution into its unwind, and only when the handlers have run does the terminal `Cancelled` land.
  */
case class FlowCancelledException(executionId: String)(using Frame)
    extends FlowException(s"Flow execution '$executionId' was cancelled")

/** The verdict a resumed unwind re-raises, carrying what the interrupted attempt recorded when it entered the unwind.
  *
  * An execution that crashed mid-unwind knows only that it was unwinding, not what from, unless the transition into `Compensating` wrote
  * the cause down. It does, so a resumed attempt raises this at the first node the forward pass would otherwise RUN, and the terminal
  * status is then a total function of the cause rather than of whether the failing step failed a second time. This never leaves the
  * engine: the transition that ends the attempt reads the cause off it and writes the pair the interrupted attempt was going to write.
  */
private[kyo] case class FlowResumedUnwindException(cause: Flow.Cause)(using Frame)
    extends FlowException(s"resuming an unwind recorded as ${cause}")
