package kyo.internal

import kyo.*
import kyo.Flow.BranchInfo
import kyo.Flow.Meta
import kyo.Flow.internal.*

/** A total fold over a flow's AST, one arm per node kind and one per combinator.
  *
  * Every arm is abstract, so a node kind added to `Flow` forces every implementer to say what it contributes rather than letting it fold
  * away unnoticed. That matters most for computations whose answer is only as complete as the arms they implement, the structural hash
  * above all: a missing arm there is a flow whose change no engine can detect.
  *
  * **Every arm that persists a value receives that value's type.** A dispatch, a loop and a foreach each write a field replay decodes,
  * exactly as an input and an output do, and an arm carrying only the name would force anything needing those types to walk the AST a
  * second time. A loop receives two, because a scheduled one checkpoints the state its next iteration starts from as well as its value.
  */
private[kyo] trait FlowVisitor[R]:
    def onInput[V](name: String, frame: Frame, meta: Meta)(using Tag[V], Schema[V]): R
    def onOutput[V](name: String, frame: Frame, meta: Meta)(using Tag[V], Schema[V]): R
    def onStep(name: String, frame: Frame, meta: Meta): R
    def onSleep(name: String, duration: Duration, frame: Frame, meta: Meta): R
    def onDispatch[V](name: String, branches: Seq[BranchInfo], frame: Frame, meta: Meta)(using Tag[V], Schema[V]): R
    def onLoop[V, State](name: String, frame: Frame, meta: Meta)(using Tag[V], Schema[V], Tag[State], Schema[State]): R
    def onForEach[V](name: String, concurrency: Int, frame: Frame, meta: Meta)(using Tag[V], Schema[V]): R
    def onInit(name: String, frame: Frame, meta: Meta): R
    def onRace(left: R, right: R, frame: Frame): R

    /** A subflow, with both the child flow itself and this same visitor's answer for it.
      *
      * Both, because the two kinds of visitor need different halves. One answers about a single definition's own nodes and ignores
      * `child` entirely; the other answers about everything the parent will durably write, and that includes the child's nodes under
      * the parent's path, so it re-keys `child` rather than walking the AST a second time.
      */
    def onSubflow(name: String, childFlow: Flow[?, ?, ?], child: R, frame: Frame, meta: Meta): R
    def onAndThen(first: R, second: R, frame: Frame): R
    def onZip(left: R, right: R, frame: Frame): R
    def onGather(flows: Seq[R], frame: Frame): R
end FlowVisitor

/** A visitor that collects a monoidal result and defaults every arm it is not given.
  *
  * Convenient for a query that cares about a few node kinds, such as gathering every name of one kind. It is the wrong base for anything
  * that must be complete, because a node kind it does not override contributes `empty` and every combinator folds with the same `combine`,
  * so both an unhandled node and two different compositions are indistinguishable in the result.
  */
abstract private[kyo] class FlowVisitorCollect[R](empty: R, combine: (R, R) => R) extends FlowVisitor[R]:
    def onInit(name: String, frame: Frame, meta: Meta): R                                                               = empty
    def onInput[V](name: String, frame: Frame, meta: Meta)(using Tag[V], Schema[V]): R                                  = empty
    def onOutput[V](name: String, frame: Frame, meta: Meta)(using Tag[V], Schema[V]): R                                 = empty
    def onStep(name: String, frame: Frame, meta: Meta): R                                                               = empty
    def onSleep(name: String, duration: Duration, frame: Frame, meta: Meta): R                                          = empty
    def onDispatch[V](name: String, branches: Seq[BranchInfo], frame: Frame, meta: Meta)(using Tag[V], Schema[V]): R    = empty
    def onLoop[V, State](name: String, frame: Frame, meta: Meta)(using Tag[V], Schema[V], Tag[State], Schema[State]): R = empty
    def onForEach[V](name: String, concurrency: Int, frame: Frame, meta: Meta)(using Tag[V], Schema[V]): R              = empty
    def onRace(left: R, right: R, frame: Frame): R                                               = combine(left, right)
    def onSubflow(name: String, childFlow: Flow[?, ?, ?], child: R, frame: Frame, meta: Meta): R = empty
    def onAndThen(first: R, second: R, frame: Frame): R                                          = combine(first, second)
    def onZip(left: R, right: R, frame: Frame): R                                                = combine(left, right)
    def onGather(flows: Seq[R], frame: Frame): R                                                 = flows.foldLeft(empty)(combine)
end FlowVisitorCollect

/** Runs a [[FlowVisitor]] over a flow, bottom up.
  *
  * The walk descends into a subflow: `onSubflow` receives the child flow AND the visitor's answer for it, so a visitor that answers about
  * everything the parent durably writes re-keys the child's answer under the subflow's path, and one that answers about a single
  * definition's own nodes ignores it. Descending here rather than leaving each visitor to fold the child itself is what keeps the
  * duplicate-name check and the structural hash looking at the same tree.
  */
private[kyo] object FlowFold:

    def apply[R](flow: Flow[?, ?, ?])(visitor: FlowVisitor[R]): R =
        def loop(f: Flow[?, ?, ?]): R =
            f match
                case n: Init => visitor.onInit(n.name, n.frame, n.meta)
                case n: Output[?, ?, ?, ?, ?] @unchecked =>
                    visitor.onOutput(n.name, n.frame, n.meta)(using n.tag, n.schema)
                case n: Step[?, ?] @unchecked  => visitor.onStep(n.name, n.frame, n.meta)
                case n: Input[?, ?] @unchecked => visitor.onInput(n.name, n.frame, n.meta)(using n.tag, n.schema)
                case n: Sleep                  => visitor.onSleep(n.name, n.duration, n.frame, n.meta)
                case n: Dispatch[?, ?, ?, ?, ?] @unchecked =>
                    val infos = n.branches.toSeq.map(b => BranchInfo(b.name, b.frame, b.meta)) :+
                        BranchInfo(n.defaultName, n.defaultFrame, Meta())
                    visitor.onDispatch(n.name, infos, n.frame, n.meta)(using n.tag, n.schema)
                case n: LoopNode[?, ?, ?, ?, ?] @unchecked =>
                    visitor.onLoop(n.name, n.frame, n.meta)(using n.tag, n.schema, n.stateTag, n.stateSchema)
                case n: ForEach[?, ?, ?, ?, ?] @unchecked =>
                    visitor.onForEach(n.name, n.concurrency, n.frame, n.meta)(using n.tag, n.schema)
                case n: Race[?, ?, ?, ?, ?, ?] @unchecked =>
                    visitor.onRace(loop(n.left), loop(n.right), n.frame)
                case n: Subflow[?, ?, ?, ?, ?, ?] @unchecked =>
                    visitor.onSubflow(n.name, n.childFlow, loop(n.childFlow), n.frame, n.meta)
                case n: AndThen[?, ?, ?, ?, ?, ?] @unchecked =>
                    visitor.onAndThen(loop(n.first), loop(n.second), n.frame)
                case n: Zip[?, ?, ?, ?, ?, ?] @unchecked =>
                    visitor.onZip(loop(n.left), loop(n.right), n.frame)
                case n: Gather[?, ?, ?] @unchecked =>
                    visitor.onGather(n.flows.toSeq.map(loop), n.frame)
        loop(flow)
    end apply

end FlowFold
