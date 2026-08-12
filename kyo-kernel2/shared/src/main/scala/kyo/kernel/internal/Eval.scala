package kyo.kernel.internal

import kyo.Arrow
import kyo.Frame
import kyo.kernel.*
import kyo.kernel.internal.Handlers.Empty
import kyo.kernel.internal.Handlers.Node
import kyo.kernel.internal.Handlers.StateNode
import scala.annotation.static
import scala.annotation.tailrec
import scala.collection.mutable.ArrayDeque

class Eval

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
                                    val cont: Any => Any < Nothing =
                                        o => rebuild(hsAll, node, walk(kCont, Nested.lift(o)))
                                    loop(h(kyo.input, cont), node)
                case kyo: Kyo.Defer[Any, Any, Any] @unchecked =>
                    if partial && Safepoint.consumeStopped(slot) then
                        rebuild(hs, Empty, v)
                    else
                        Safepoint.reset(slot)
                        loop(walk(kyo.cont, kyo.value), hs)
                case v =>
                    hs match
                        case Empty                          => v
                        case n: Node[?, ?, ?, ?, ?]         => loop(walk(n.exit, v), n.prev)
                        case n: StateNode[?, ?, ?, ?, ?, ?] => loop(walk(n.exit, v), n.prev)
        loop(v0, Empty).asInstanceOf[A < S]
    end evalLoop

    final private class Scratch:
        val pending        = new ArrayDeque[Arrow[Any, Any, Any]]
        private var region = new Array[Arrow[Any, Any, Any]](256)
        var top            = 0
        def push(u: Arrow[Any, Any, Any]): Unit =
            if top == region.length then region = java.util.Arrays.copyOf(region, top * 2)
            region(top) = u
            top += 1
        end push
        def apply(i: Int): Arrow[Any, Any, Any] = region(i)
    end Scratch

    @static private val scratch: ThreadLocal[Scratch] =
        new ThreadLocal[Scratch]:
            override def initialValue() = new Scratch

    private inline def BatchSize = 8

    private def walk(cont: Arrow[Any, Any, Any], v: Any < Nothing): Any < Nothing =
        cont match
            case cont: Arrow.AndThen[Any, Any, Any, Any] @unchecked =>
                v match
                    case kyo: Kyo[Any, Nothing] @unchecked => kyo.map(cont)
                    case _                                 => evalChain(cont, v)
            case cont =>
                val step = cont.step
                step.head(v, step.tail)

    private def evalChain(root: Arrow.AndThen[Any, Any, Any, Any], v0: Any < Nothing): Any < Nothing =
        val s    = scratch.get
        val mark = s.top
        unroll(root, s)
        val end = s.top
        @tailrec def loop(i: Int, v: Any < Nothing): Any < Nothing =
            if i == end then v
            else
                // a batch of units links into the head's tail arrow, so each transform
                // calls the next through its own site; a suspension inside the batch has
                // captured its unfinished part there, so the remainder starts past it
                val batchEnd = Math.min(i + BatchSize, end)
                val step     = s(i).step
                step.head(v, step.tail.chain(remainder(s, i + 1, batchEnd))) match
                    case kyo: Kyo[Any, Nothing] @unchecked =>
                        remainder(s, batchEnd, end) match
                            case rest if rest eq Arrow[Any] => kyo
                            case rest                       => kyo.map(rest)
                    case r =>
                        loop(batchEnd, r)
                end match
        try loop(mark, v0)
        finally s.top = mark
    end evalChain

    // composition nodes unroll; pre-linked Step chains stay opaque whole, and a minted
    // chain is never re-expanded or re-minted
    private def unroll(root: Arrow.AndThen[Any, Any, Any, Any], s: Scratch): Unit =
        val pending = s.pending
        @tailrec def loop(n: Int): Unit =
            if n > 0 then
                pending.removeHead() match
                    case at: Arrow.AndThen[Any, Any, Any, Any] @unchecked =>
                        pending.prepend(at.b)
                        pending.prepend(at.a)
                        loop(n + 1)
                    case u =>
                        if u ne Arrow[Any] then s.push(u)
                        loop(n - 1)
        pending.prepend(root)
        loop(1)
    end unroll

    private def remainder(s: Scratch, from: Int, end: Int): Arrow[Any, Any, Any] =
        @tailrec def link(j: Int, acc: Arrow[Any, Any, Any]): Arrow[Any, Any, Any] =
            if j < from then acc
            else link(j - 1, s(j).chain(acc))
        link(end - 1, Arrow[Any])
    end remainder

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
