package kyo.internal

import kyo.*
import kyo.kernel.Isolate

abstract private[kyo] class FlowInterpreter[S]:

    def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]): V < S

    def onOutput[V](name: String, computation: V < Sync, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]): V < S

    /** Read a stored field by name. Returns Absent if the field does not exist. */
    def getField[V](name: String)(using Tag[V], Schema[V]): Maybe[V] < S

    def onStep(name: String, computation: Unit < Sync, frame: Frame, meta: Flow.Meta): Unit < S

    /** Run one iteration of an unscheduled loop under the node's own policy, recording nothing.
      *
      * The bracket [[onStep]] gives a step, without the two events and the completion mark. An unscheduled loop's iteration count is
      * unbounded, so a checkpoint per iteration is unbounded history, which is the cost [[kyo.Flow.loop]]'s scaladoc states and
      * declines to pay. What an iteration still gets is everything the node declared: a `timeout` that bounds the single slow
      * iteration rather than the sum of every fast one, a `retry` that re-asks one iteration, and the node boundary's own check, so an
      * execution somebody asked to cancel stops rather than going on running a body nothing ever looked at.
      */
    def onIteration[V](name: String, computation: V < Sync, frame: Frame, meta: Flow.Meta): V < S

    /** Record durably which branch of a dispatch is about to run, BEFORE it runs.
      *
      * A dispatch persists only the branch's value, so a crash between the branch's side effects and that write lets replay re-ask the
      * conditions, and a condition that answers differently (time moved, another writer changed the data) runs a SECOND branch's side
      * effects beside the first's, with nothing durable saying either ran. Recording the choice first makes the record the truth of what
      * ran: replay re-enters the recorded branch without evaluating any condition, and the conditions' determinism stops being a
      * correctness requirement.
      *
      * The record is a field under [[FlowInterpreter.chosenKey]] carrying the branch's NAME, which is durable and part of the flow's
      * version identity, so it survives everything a stored value survives. Replay reads it back through [[getField]].
      */
    def onChoice(name: String, branch: String, frame: Frame, meta: Flow.Meta): Unit < S

    /** Record durably the value a subflow's mapper supplied for one of the child's inputs, BEFORE the child's first node runs.
      *
      * The same rule [[onChoice]] follows, one level up. A subflow that rebuilt the child's input record from the mapper on every
      * attempt and persisted none of it would let a mapper answering differently after a crash run the child's remaining nodes
      * against values its recorded outputs were never computed from, with nothing durable saying so. Recording each input first makes
      * the record the truth of what the child ran against: an attempt that finds every declared input recorded re-enters the child
      * without running the mapper at all, so the mapper's determinism is not a correctness requirement.
      *
      * The record is an ordinary field under the input's own durable path (`review~amount`), carrying the value, so replay reads it
      * back the way it reads any other field and the assembled record a reader receives carries the field its type promises. The
      * answer says whether THIS attempt was the writer, which is what the shared-name race corner reads: two arms of a race
      * embedding one subflow name both write, the first lands, and the second reads the recorded value back rather than running its
      * child against a value nothing kept.
      *
      * @return
      *   true when this attempt recorded the value, false when the path already held one
      */
    def onInputSupplied[V](name: String, value: V, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]): Boolean < S

    /** Record that an input node found its value and went on, which is what discharges the wait it had recorded.
      *
      * The only node whose satisfied path writes anything. Every other node that can wait discharges through work it was going to record
      * anyway, while a satisfied `Input` proceeds with the value already in its record; without a transition of its own, the row it wrote
      * while parking would never be cleared and the execution would be handed back on every poll.
      */
    def onInputDischarged(name: String, frame: Frame, meta: Flow.Meta): Unit < S

    def onSleep(name: String, duration: Duration, frame: Frame, meta: Flow.Meta): Unit < S

    def onZip(
        left: Record[Any] < S,
        right: Record[Any] < S,
        ctx: Record[Any],
        isolate: Isolate[Any, Abort[FlowException] & Async, Any]
    ): Record[Any] < S

    def onRace(
        left: Record[Any] < S,
        right: Record[Any] < S,
        isolate: Isolate[Any, Abort[FlowException] & Async, Any]
    ): Record[Any] < S

    /** Check if the execution has been cancelled. Used by loops to break between iterations. */
    def checkCancelled: Boolean < S = false

    /** Called before compensation handlers run, carrying the verdict that started the unwind. */
    def onCompensationStart(cause: Flow.Cause): Unit < S = ()

    /** Called after all compensation handlers complete. */
    def onCompensationComplete: Unit < S = ()

    /** Called when a compensation handler throws. */
    def onCompensationFailed(error: Throwable): Unit < S = ()

    /** Called when one node's compensation handler has run to completion, so a resumed unwind can skip it.
      *
      * An unwind that is interrupted part-way leaves handlers run and handlers not run, and the three compensation events are
      * per-UNWIND, so nothing among them tells the two apart and a recovered execution could only run every handler again. Recording
      * each one as it lands is what makes "re-run only the handlers with no recorded completion" a fact about the store rather than a
      * hope about how far the last attempt got.
      */
    def onCompensated(name: String): Unit < S = ()

    /** Re-raises what ended the flow, once its compensations have run.
      *
      * The interpreter owns this because the interpreter owns the effect row: whether a [[FlowException]] can go back out on a typed
      * channel depends on whether `S` has one, which only the interpreter knows. Abstract rather than defaulted to a panic for that
      * reason: `Abort.panic` is itself an effect, and one an arbitrary `S` does not have.
      */
    def onUnwind(error: Throwable): Nothing < S

    /** Raise a declared failure of the flow, on the typed channel the interpreter's row has for one.
      *
      * The sibling of [[onUnwind]], and abstract for the same reason: whether a [[FlowException]] can go back out as a FAILURE
      * rather than as a panic depends on whether `S` carries a channel for it, which only the interpreter knows.
      *
      * The difference is not cosmetic. A panic reaches the engine as an escaped throwable with no declared kind, so the status it
      * writes keeps only a message and "how many executions ended this way" becomes a search over message text; a failure carries
      * the exception's own kind into the status, where it can be grouped by. A verdict the engine itself reaches, a loop that ran
      * its schedule out as much as a step that exceeded its timeout, is a declared failure of the flow and belongs here.
      *
      * It is raised INTO the flow rather than around it, so everything that already ran still unwinds before the failure reaches
      * the engine.
      *
      * `path` is the durable name of the node the verdict is about, and it is a parameter because the exception does not carry one an
      * implementation can trust: it holds whatever name reads well in a message, while the store is keyed by the composition path. An
      * implementation records the failure under `path` before raising, so a reader can say WHICH node ended the execution rather than
      * inferring it from where a walk happens to stop.
      */
    def onFailure(path: String, error: FlowException): Nothing < S

end FlowInterpreter

private[kyo] object FlowInterpreter:

    /** The durable key a dispatch records its chosen branch under.
      *
      * It rides `#`, which the engine reserves for the keys it derives itself and which registration refuses in a user's node name, so
      * the key can never collide with one a flow declared. It is written by [[FlowInterpreter.onChoice]] and read back by the dispatch
      * arm through [[FlowInterpreter.getField]]; nothing else addresses it.
      */
    def chosenKey(dispatch: String): String = s"$dispatch#chosen"

    /** The durable key a node's compensation handler records its completion under.
      *
      * It rides `#` for the same reason [[chosenKey]] does, and it has to be a key of its own rather than the node's: progress is
      * write-once per path, and the node's forward completion already occupies the node's path, so a handler writing there would be
      * answered already-recorded and record nothing.
      */
    def compensatedKey(node: String): String = s"$node#compensated"

end FlowInterpreter
