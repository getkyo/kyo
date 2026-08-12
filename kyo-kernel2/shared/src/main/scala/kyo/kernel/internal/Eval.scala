package kyo.kernel.internal

import kyo.Arrow
import kyo.Frame
import kyo.kernel.*
import kyo.kernel.`<`.fromKyo
import kyo.kernel.Implicits.liftInternal
import kyo.kernel.internal.Handlers.Empty
import kyo.kernel.internal.Handlers.Node
import kyo.kernel.internal.Handlers.StateNode
import scala.annotation.tailrec

object Eval:

    def apply[A, S](v: A < S): A < S =
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        val res   = evalLoop(v, slot, partial = false)
        Safepoint.restore(slot, saved)
        res
    end apply

    def partial[A, S](v: A < S): A < S =
        val slot = Safepoint.get()
        if Safepoint.consumeStopped(slot) then v
        else
            val saved = Safepoint.save(slot)
            Safepoint.arm(slot)
            val res = evalLoop(v, slot, partial = true)
            Safepoint.restore(slot, saved)
            res
        end if
    end partial

    private def evalLoop[A, S](v0: A < S, slot: Safepoint.Slot, partial: Boolean): A < S =
        // arms ordered by expected frequency: a suspension per answered
        // operation, a defer per budget rescue or deferred effect, region
        // nodes per handler entry or crossing
        @tailrec def loop(v: Any < Nothing, hs: Handlers): Any < Nothing =
            (v: @unchecked) match
                case kyo: Kyo.Suspend[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                    hs.find(kyo.tag) match
                        case Empty =>
                            kyo.root match
                                case d: Kyo.Defaulted =>
                                    loop(resume(kyo.cont, Nested.lift(d.default)), hs)
                                case _ =>
                                    if !partial then throw new IllegalStateException(s"unhandled suspension: $kyo")
                                    else rebuild(hs, Empty, v)
                        case node: StateNode[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                            node.handler(kyo.input, node.state) match
                                case pending: Kyo[Loop.Outcome2[Any, Any < Nothing, Any], Any] @unchecked =>
                                    loop(statePending(pending, node, hs, kyo.cont), node.prev)
                                case outcome =>
                                    Nested.unnest[Loop.Outcome2[Any, Any < Nothing, Any]](outcome) match
                                        case c: Loop.Continue2[Any, Any < Nothing] @unchecked =>
                                            val updated =
                                                if c._1.asInstanceOf[AnyRef] eq node.state.asInstanceOf[AnyRef] then node
                                                else node.withState(c._1)
                                            val hs2 = if updated eq node then hs else replace(hs, node, updated)
                                            (c._2: Any) match
                                                case p: Kyo[Any, Any] @unchecked =>
                                                    loop(continuePending(p, updated, hs2, kyo.cont), updated)
                                                case answer =>
                                                    loop(resume(kyo.cont, answer.asInstanceOf[Any < Nothing]), hs2)
                                            end match
                                        case done =>
                                            loop(resume(node.exit, Nested.lift(done)), node.prev)
                            end match
                        case node: Node[[X] =>> Any, [X] =>> Any, Nothing, Any, Any] @unchecked =>
                            node.handler match
                                case h: Handler.Loop[[X] =>> Any, [X] =>> Any, Nothing, Any, Any] =>
                                    h(kyo.input) match
                                        case pending: Kyo[Loop.Outcome[Any < Nothing, Any], Any] @unchecked =>
                                            loop(loopPending(pending, node, hs, kyo.cont), node.prev)
                                        case outcome =>
                                            Nested.unnest[Loop.Outcome[Any < Nothing, Any]](outcome) match
                                                case c: Loop.Continue[Any < Nothing] @unchecked =>
                                                    (c._1: Any) match
                                                        case p: Kyo[Any, Any] @unchecked =>
                                                            loop(continuePending(p, node, hs, kyo.cont), node)
                                                        case answer =>
                                                            loop(resume(kyo.cont, answer.asInstanceOf[Any < Nothing]), hs)
                                                case done =>
                                                    loop(resume(node.exit, Nested.lift(done)), node.prev)
                                    end match
                                case h: Handler.Cont[[X] =>> Any, [X] =>> Any, Nothing, Any, Any] =>
                                    val hsAll = hs
                                    val kCont = kyo.cont
                                    val cont: Any => Any < Nothing =
                                        o => rebuild(hsAll, node, resume(kCont, Nested.lift(o)))
                                    loop(h(kyo.input, cont), node)
                case kyo: Kyo.Defer[Any, Any, Any] @unchecked =>
                    if partial && Safepoint.consumeStopped(slot) then
                        rebuild(hs, Empty, v)
                    else
                        Safepoint.reset(slot)
                        loop(walk(kyo.cont, kyo.value), hs)
                case kyo: Kyo.Handled[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any] @unchecked =>
                    kyo match
                        case kyo: RebuiltNode if kyo.node.prev eq hs =>
                            // the onion layer lands where it was built from, so
                            // the original cell re-enters the stack as is
                            loop(kyo.value, kyo.node)
                        case _ =>
                            loop(kyo.value, new Node(kyo.handler, kyo.exit, hs))
                case kyo: Kyo.HandledState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any, Any] @unchecked =>
                    kyo match
                        case kyo: RebuiltStateNode if kyo.node.prev eq hs =>
                            loop(kyo.value, kyo.node)
                        case _ =>
                            loop(kyo.value, new StateNode(kyo.handler, kyo.exit, kyo.state, hs))
                case v =>
                    hs match
                        case Empty                          => v
                        case n: Node[?, ?, ?, ?, ?]         => loop(resume(n.exit, v), n.prev)
                        case n: StateNode[?, ?, ?, ?, ?, ?] => loop(resume(n.exit, v), n.prev)
        loop(v0, Empty).asInstanceOf[A < S]
    end evalLoop

    // the pending-outcome re-entries: a handler that itself suspends chains
    // its region's reconstruction after the pending outcome. Kept out of the
    // evaluation loop so its hot arms stay small
    private def statePending(
        pending: Loop.Outcome2[Any, Any < Nothing, Any] < Any,
        node: StateNode[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any],
        hsAll: Handlers,
        kCont: Arrow[Any, Any, Any]
    ): Any < Nothing =
        pending.map {
            case c: Loop.Continue2[Any, Any < Nothing] @unchecked =>
                Kyo.HandledState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any, Any](
                    rebuild(hsAll, node, walk(kCont, c._2)),
                    node.handler,
                    node.exit,
                    c._1
                )
            case done =>
                resume(node.exit, Nested.lift(done))
        }(using Frame.internal)

    // shared by the stateless and stateful regions: rebuild only walks cells
    private def continuePending(
        p: Any < Any,
        node: Handlers,
        hsAll: Handlers,
        kCont: Arrow[Any, Any, Any]
    ): Any < Nothing =
        p.map { a =>
            rebuild(hsAll, node, resume(kCont, Nested.lift(a)))
        }(using Frame.internal)

    private def loopPending(
        pending: Loop.Outcome[Any < Nothing, Any] < Any,
        node: Node[[X] =>> Any, [X] =>> Any, Nothing, Any, Any],
        hsAll: Handlers,
        kCont: Arrow[Any, Any, Any]
    ): Any < Nothing =
        pending.map {
            case c: Loop.Continue[Any < Nothing] @unchecked =>
                rebuild(hsAll, node.prev, walk(kCont, c._1))
            case done =>
                resume(node.exit, Nested.lift(done))
        }(using Frame.internal)

    // a suspension travelling up the chain walk: the unfinished right sides compose into
    // rest one chain node per frame; created at the suspension point, consumed by the walk
    // root, never escapes
    final private class Suspended(val kyo: Kyo[Any, Nothing], var rest: Arrow[Any, Any, Any])

    private def walk(cont: Arrow[Any, Any, Any], v: Any < Nothing): Any < Nothing =
        v match
            case kyo: Kyo[Any, Nothing] @unchecked => kyo.map(cont)
            case _                                 => resume(cont, v)

    // the settled half of walk: callers use it directly when the value is
    // proven not to be a computation (lifted, or matched apart already)
    private def resume(cont: Arrow[Any, Any, Any], v: Any < Nothing): Any < Nothing =
        cont match
            case cont: Arrow.AndThen[Any, Any, Any, Any] @unchecked =>
                evalChain(cont, v)
            case cont =>
                val step = cont.step
                step.head(v, step.tail)

    private def evalChain(root: Arrow.AndThen[Any, Any, Any, Any], v0: Any < Nothing): Any < Nothing =
        val slot = Safepoint.get()
        def run(u: Arrow[Any, Any, Any], v: Any < Nothing): Any < Nothing =
            u match
                case at: Arrow.AndThen[Any, Any, Any, Any] @unchecked if Safepoint.enter(slot) =>
                    val out =
                        run(at.a, v) match
                            case sus: Suspended =>
                                sus.rest = sus.rest.chain(at.b)
                                sus
                            case r =>
                                run(at.b, r)
                    Safepoint.exit(slot)
                    out
                case u =>
                    // a unit executes fused through its own sites; past the budget the
                    // subtree flattens and executes as a linked chain that defers with
                    // progress under the transform budget
                    val step = u.step
                    step.head(v, step.tail) match
                        case kyo: Kyo[Any, Nothing] @unchecked => new Suspended(kyo, Arrow[Any])
                        case r                                 => r

        run(root, v0) match
            case sus: Suspended =>
                // the remainder links once at the suspension boundary, so the resumed part
                // executes fused as well
                sus.rest match
                    case rest if rest eq Arrow[Any] => sus.kyo
                    case rest                       => sus.kyo.map(rest.step)
            case r =>
                r
        end match
    end evalChain

    // a region layer rebuilt from an existing cell: as a value it is the
    // regular region node; when the evaluator consumes it in the position it
    // was built from, the cell re-enters the stack with no new allocation
    final private class RebuiltNode(
        val value: Any < Nothing,
        val node: Node[[X] =>> Any, [X] =>> Any, Nothing, Any, Any]
    ) extends Kyo.Handled[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any]:
        def handler = node.handler
        def exit    = node.exit
    end RebuiltNode

    final private class RebuiltStateNode(
        val value: Any < Nothing,
        val node: StateNode[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any]
    ) extends Kyo.HandledState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any, Any]:
        def handler = node.handler
        def exit    = node.exit
        def state   = node.state
    end RebuiltStateNode

    @tailrec private def rebuild(top: Handlers, stop: Handlers, acc: Any < Nothing): Any < Nothing =
        if top eq stop then acc
        else
            top match
                case n: Node[[X] =>> Any, [X] =>> Any, Nothing, Any, Any] @unchecked =>
                    rebuild(n.prev, stop, new RebuiltNode(acc, n))
                case n: StateNode[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                    rebuild(n.prev, stop, new RebuiltStateNode(acc, n))
                case Empty =>
                    acc
    end rebuild

    private def replace(top: Handlers, node: Handlers, updated: Handlers): Handlers =
        if top eq node then updated
        else
            @tailrec def count(l: Handlers, n: Int): Int =
                if l eq node then n
                else
                    l match
                        case l: Node[?, ?, ?, ?, ?]         => count(l.prev, n + 1)
                        case l: StateNode[?, ?, ?, ?, ?, ?] => count(l.prev, n + 1)
                        case Empty                          => n
            val n     = count(top, 0)
            val cells = new Array[Handlers](n)
            @tailrec def fill(l: Handlers, i: Int): Unit =
                if i < n then
                    cells(i) = l
                    l match
                        case l: Node[?, ?, ?, ?, ?]         => fill(l.prev, i + 1)
                        case l: StateNode[?, ?, ?, ?, ?, ?] => fill(l.prev, i + 1)
                        case Empty                          => ()
                    end match
            fill(top, 0)
            @tailrec def build(i: Int, acc: Handlers): Handlers =
                if i < 0 then acc
                else
                    cells(i) match
                        case c: Node[?, ?, ?, ?, ?]         => build(i - 1, c.withPrev(acc))
                        case c: StateNode[?, ?, ?, ?, ?, ?] => build(i - 1, c.withPrev(acc))
                        case Empty                          => build(i - 1, acc)
            build(n - 1, updated)
    end replace

end Eval
