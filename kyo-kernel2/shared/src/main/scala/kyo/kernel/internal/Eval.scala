package kyo.kernel.internal

import kyo.Arrow
import kyo.Frame
import kyo.kernel.*
import kyo.kernel.internal.Handlers.Empty
import kyo.kernel.internal.Handlers.Node
import kyo.kernel.internal.Handlers.StateNode
import scala.annotation.tailrec

// public: the internal package carries the visibility intent. Any private
// qualifier, including private[kyo] that is public in bytecode, makes the
// compiler emit inline accessors for references from public inline bodies,
// and those materialize the package prefix as a runtime value, failing with
// NoClassDefFoundError: kyo/kernel/internal at every eval call site
object Eval:

    def apply[A, S](v: A < S): A < S =
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        val res   = evalLoop(v, slot, partial = false)
        Safepoint.restore(slot, saved)
        res
    end apply

    // the scheduler entry: evaluates like apply but yields instead of
    // throwing or spinning. It returns the standing computation reified with
    // its remaining regions when a Safepoint.stop request is pending on this
    // thread's slot or when a suspension has no handler; the result resumes
    // by evaluating it again. Preemption is dispatched only through the
    // thread's slot, and ArrowEffect.handlePartial cannot take this role: it
    // parks at region nodes by design, so evaluating regions without
    // throwing on a miss needs this entry
    def partial[A, S](v: A < S): A < S =
        val slot = Safepoint.get()
        if Safepoint.stopped(slot) then v
        else
            val saved = Safepoint.save(slot)
            val res   = evalLoop(v, slot, partial = true)
            Safepoint.restore(slot, saved)
            res
        end if
    end partial

    // One flat loop carries the value with its entered regions, one
    // immutable cell per region holding the handler, the scope's exit
    // continuation, and a stateful handler's current state. Entry is one
    // cell, a settled value pops through the top cell's exit, done feeds its
    // own cell's exit and discards the cells it climbs past, a stateful
    // answer replaces one cell while the handler object stays the same, and
    // a clause that suspends before producing its outcome is chained onto
    // the computation and runs under the cells outside its own, with the
    // crossed cells rebuilt around the resumption. The cells are immutable
    // and shared, so captures are a reference copy. Nothing recurses, so
    // region depth never reaches the Java stack, and answering allocates
    // nothing.
    private def evalLoop[A, S](v0: A < S, slot: Safepoint.Slot, partial: Boolean): A < S =
        @tailrec def loop(v: Any < Nothing, hs: Handlers): Any < Nothing =
            (v: @unchecked) match
                case kyo: Kyo.Handled[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any] @unchecked =>
                    loop(kyo.value, new Node(kyo.handler, kyo.cont, hs))
                case kyo: Kyo.HandledState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any, Any] @unchecked =>
                    loop(kyo.value, new StateNode(kyo.handler, kyo.cont, kyo.state, hs))
                case kyo: Kyo.Suspend[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                    hs.find(kyo.tag) match
                        case Empty =>
                            kyo.root match
                                case d: Kyo.Defaulted =>
                                    loop(walk(kyo.cont, Nested.lift(d.default)), hs)
                                case _ =>
                                    if !partial then throw new IllegalStateException(s"unhandled suspension: $kyo")
                                    else rebuild(hs, Empty, v)
                        case node: StateNode[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                            node.handler(kyo.input, node.state) match
                                case pending: Kyo[Loop.Outcome2[Any, Any < Nothing, Any], Any] @unchecked =>
                                    val hsAll = hs
                                    val kCont = kyo.cont
                                    val chained = (pending: Loop.Outcome2[Any, Any < Nothing, Any] < Any).map {
                                        case c: Loop.Continue2[Any, Any < Nothing] @unchecked =>
                                            // the region resumes around the answer with the
                                            // clause's new state substituted at its own cell
                                            new Kyo.HandledState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any, Any](
                                                rebuild(hsAll, node, walk(kCont, c._2)),
                                                node.handler,
                                                node.exit,
                                                c._1
                                            )
                                        case done =>
                                            walk(node.exit, Nested.lift(done))
                                    }(using Frame.internal)
                                    loop(chained, node.prev)
                                case outcome =>
                                    Nested.unnest[Loop.Outcome2[Any, Any < Nothing, Any]](outcome) match
                                        case c: Loop.Continue2[Any, Any < Nothing] @unchecked =>
                                            // the reference check is only an optimization: a
                                            // false negative rebuilds an identical cell
                                            val updated =
                                                if c._1.asInstanceOf[AnyRef] eq node.state.asInstanceOf[AnyRef] then node
                                                else node.withState(c._1)
                                            val hs2 = if updated eq node then hs else replace(hs, node, updated)
                                            (c._2: Any) match
                                                case p: Kyo[Any, Any] @unchecked =>
                                                    val kCont = kyo.cont
                                                    val chained = (p: Any < Any).map { a =>
                                                        rebuild(hs2, updated, walk(kCont, Nested.lift(a)))
                                                    }(using Frame.internal)
                                                    loop(chained, updated)
                                                case answer =>
                                                    loop(walk(kyo.cont, answer.asInstanceOf[Any < Nothing]), hs2)
                                            end match
                                        case done =>
                                            loop(walk(node.exit, Nested.lift(done)), node.prev)
                            end match
                        case node: Node[[X] =>> Any, [X] =>> Any, Nothing, Any, Any] @unchecked =>
                            node.handler match
                                case h: Handler.Loop[[X] =>> Any, [X] =>> Any, Nothing, Any, Any] =>
                                    h(kyo.input) match
                                        case pending: Kyo[Loop.Outcome[Any < Nothing, Any], Any] @unchecked =>
                                            val hsAll = hs
                                            val kCont = kyo.cont
                                            val chained = (pending: Loop.Outcome[Any < Nothing, Any] < Any).map {
                                                case c: Loop.Continue[Any < Nothing] @unchecked =>
                                                    rebuild(hsAll, node.prev, walk(kCont, c._1))
                                                case done =>
                                                    walk(node.exit, Nested.lift(done))
                                            }(using Frame.internal)
                                            loop(chained, node.prev)
                                        case outcome =>
                                            Nested.unnest[Loop.Outcome[Any < Nothing, Any]](outcome) match
                                                case c: Loop.Continue[Any < Nothing] @unchecked =>
                                                    (c._1: Any) match
                                                        case p: Kyo[Any, Any] @unchecked =>
                                                            val hsAll = hs
                                                            val kCont = kyo.cont
                                                            val chained = (p: Any < Any).map { a =>
                                                                rebuild(hsAll, node, walk(kCont, Nested.lift(a)))
                                                            }(using Frame.internal)
                                                            loop(chained, node)
                                                        case answer =>
                                                            loop(walk(kyo.cont, answer.asInstanceOf[Any < Nothing]), hs)
                                                case done =>
                                                    loop(walk(node.exit, Nested.lift(done)), node.prev)
                                    end match
                                case h: Handler.Cont[[X] =>> Any, [X] =>> Any, Nothing, Any, Any] =>
                                    val hsAll = hs
                                    val kCont = kyo.cont
                                    // the continuation rebuilds the crossed cells around the
                                    // resumption; each call builds a fresh value, so capture
                                    // is multi-shot by construction. The clause runs under
                                    // its own cell and the outer ones, so its re-raises are
                                    // answered by this handler and its exit applies on settle
                                    val cont: Any => Any < Nothing =
                                        o => rebuild(hsAll, node, walk(kCont, Nested.lift(o)))
                                    loop(h(kyo.input, cont), node)
                case kyo: Kyo.Defer[Any, Any, Any] @unchecked =>
                    if partial && Safepoint.stopped(slot) then
                        rebuild(hs, Empty, v)
                    else
                        Safepoint.restore(slot, 0L)
                        loop(walk(kyo.cont, kyo.value), hs)
                case v =>
                    hs match
                        case Empty                          => v
                        case n: Node[?, ?, ?, ?, ?]         => loop(walk(n.exit, v), n.prev)
                        case n: StateNode[?, ?, ?, ?, ?, ?] => loop(walk(n.exit, v), n.prev)
        loop(v0, Empty).asInstanceOf[A < S]
    end evalLoop

    private def walk(cont: Arrow[Any, Any, Any], v: Any < Nothing): Any < Nothing =
        val step = cont.step
        step.head(v, step.tail)

    // When a computation leaves the evaluated region structure as a plain
    // value, the cells it sat under must travel with it or their handlers,
    // exits, and states would be lost. This happens in three places: a
    // clause that suspends before producing its outcome (the resumed outcome
    // must still run under the cells outside its own region), a Cont
    // handler's captured continuation (each call re-enters the crossed
    // cells), and a partial evaluation yielding a residual. The walk wraps
    // the value back into one region node per cell, from the top cell down
    // to stop exclusive, so evaluating the result re-enters the same regions
    // with the same exits and states: a residual is ordinary data and
    // resumes by evaluation alone
    @tailrec private def rebuild(top: Handlers, stop: Handlers, acc: Any < Nothing): Any < Nothing =
        if top eq stop then acc
        else
            top match
                case n: Node[[X] =>> Any, [X] =>> Any, Nothing, Any, Any] @unchecked =>
                    rebuild(
                        n.prev,
                        stop,
                        new Kyo.Handled[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any](acc, n.handler, n.exit)
                    )
                case n: StateNode[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any] @unchecked =>
                    rebuild(
                        n.prev,
                        stop,
                        new Kyo.HandledState[[X] =>> Any, [X] =>> Any, Nothing, Any, Any, Any, Any, Any](
                            acc,
                            n.handler,
                            n.exit,
                            n.state
                        )
                    )
                case Empty =>
                    // stop is always a suffix of top's chain, so the walk
                    // meets it before Empty unless stop is Empty itself,
                    // which the guard already caught
                    acc
    end rebuild

    // replaces one cell of the stack: the hot case is the top cell, one
    // allocation done by the caller through withState; an interior cell,
    // reached when a stateful handler answers under unrelated inner regions,
    // path-copies the cells above it. Iterative two-pass so pathological
    // depths never reach the Java stack
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
