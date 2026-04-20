package kyo.internal

import kyo.*
import kyo.Flow.BranchInfo
import kyo.Flow.Meta
import kyo.Flow.internal.*

private[kyo] trait FlowVisitor[R]:
    def onInput[V](name: String, frame: Frame, meta: Meta)(using Tag[V], Json[V]): R
    def onOutput[V](name: String, frame: Frame, meta: Meta)(using Tag[V], Json[V]): R
    def onStep(name: String, frame: Frame, meta: Meta): R
    def onSleep(name: String, duration: Duration, frame: Frame, meta: Meta): R
    def onDispatch(name: String, branches: Seq[BranchInfo], frame: Frame, meta: Meta): R
    def onLoop(name: String, frame: Frame, meta: Meta): R
    def onForEach(name: String, concurrency: Int, frame: Frame, meta: Meta): R
    def onInit(name: String, frame: Frame, meta: Meta): R
    def onRace(left: R, right: R, frame: Frame): R
    def onSubflow(name: String, childFlow: Flow[?, ?, ?], frame: Frame, meta: Meta): R
    def onAndThen(first: R, second: R, frame: Frame): R
    def onZip(left: R, right: R, frame: Frame): R
    def onGather(flows: Seq[R], frame: Frame): R
end FlowVisitor

abstract private[kyo] class FlowVisitorCollect[R](empty: R, combine: (R, R) => R) extends FlowVisitor[R]:
    def onInit(name: String, frame: Frame, meta: Meta): R                                = empty
    def onInput[V](name: String, frame: Frame, meta: Meta)(using Tag[V], Json[V]): R     = empty
    def onOutput[V](name: String, frame: Frame, meta: Meta)(using Tag[V], Json[V]): R    = empty
    def onStep(name: String, frame: Frame, meta: Meta): R                                = empty
    def onSleep(name: String, duration: Duration, frame: Frame, meta: Meta): R           = empty
    def onDispatch(name: String, branches: Seq[BranchInfo], frame: Frame, meta: Meta): R = empty
    def onLoop(name: String, frame: Frame, meta: Meta): R                                = empty
    def onForEach(name: String, concurrency: Int, frame: Frame, meta: Meta): R           = empty
    def onRace(left: R, right: R, frame: Frame): R                                       = combine(left, right)
    def onSubflow(name: String, childFlow: Flow[?, ?, ?], frame: Frame, meta: Meta): R   = empty
    def onAndThen(first: R, second: R, frame: Frame): R                                  = combine(first, second)
    def onZip(left: R, right: R, frame: Frame): R                                        = combine(left, right)
    def onGather(flows: Seq[R], frame: Frame): R                                         = flows.foldLeft(empty)(combine)
end FlowVisitorCollect

private[kyo] object FlowFold:

    def apply[R](flow: Flow[?, ?, ?])(visitor: FlowVisitor[R]): R =
        def loop(f: Flow[?, ?, ?]): R =
            f match
                case n: Init => visitor.onInit(n.name, n.frame, n.meta)
                case n: Output[?, ?, ?, ?, ?] @unchecked =>
                    visitor.onOutput(n.name, n.frame, n.meta)(using n.tag, n.json)
                case n: Step[?, ?] @unchecked  => visitor.onStep(n.name, n.frame, n.meta)
                case n: Input[?, ?] @unchecked => visitor.onInput(n.name, n.frame, n.meta)(using n.tag, n.json)
                case n: Sleep                  => visitor.onSleep(n.name, n.duration, n.frame, n.meta)
                case n: Dispatch[?, ?, ?, ?, ?] @unchecked =>
                    val infos = n.branches.toSeq.map(b => BranchInfo(b.name, b.frame, b.meta)) :+
                        BranchInfo(n.defaultName, n.defaultFrame, Meta())
                    visitor.onDispatch(n.name, infos, n.frame, n.meta)
                case n: LoopNode[?, ?, ?, ?, ?] @unchecked => visitor.onLoop(n.name, n.frame, n.meta)
                case n: ForEach[?, ?, ?, ?, ?] @unchecked  => visitor.onForEach(n.name, n.concurrency, n.frame, n.meta)
                case n: Race[?, ?, ?, ?, ?, ?] @unchecked =>
                    visitor.onRace(loop(n.left), loop(n.right), n.frame)
                case n: Subflow[?, ?, ?, ?, ?, ?] @unchecked => visitor.onSubflow(n.name, n.childFlow, n.frame, n.meta)
                case n: AndThen[?, ?, ?, ?, ?, ?] @unchecked =>
                    visitor.onAndThen(loop(n.first), loop(n.second), n.frame)
                case n: Zip[?, ?, ?, ?, ?, ?] @unchecked =>
                    visitor.onZip(loop(n.left), loop(n.right), n.frame)
                case n: Gather[?, ?, ?] @unchecked =>
                    visitor.onGather(n.flows.toSeq.map(loop), n.frame)
        loop(flow)
    end apply

end FlowFold
