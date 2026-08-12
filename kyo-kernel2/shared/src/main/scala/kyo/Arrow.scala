package kyo

import kyo.kernel.*
import kyo.kernel.internal.Kyo
import scala.annotation.nowarn
import scala.annotation.static
import scala.annotation.tailrec
import scala.collection.mutable.ArrayDeque

sealed abstract class Arrow[-A, +B, -S]:
    self =>

    def apply(v: A): B < S

    def step: Arrow.Step[A, B, S]

    private[kyo] def applyTo[S2](v: A < S2): B < (S & S2) =
        val s = step
        s.head(v, s.tail)

    final def chain[C, S2](next: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
        if self eq Arrow.identity then next.asInstanceOf[Arrow[A, C, S & S2]]
        else if next eq Arrow.identity then self.asInstanceOf[Arrow[A, C, S & S2]]
        else
            self match
                case t: Arrow.Transform[A, B, S] @unchecked =>
                    Arrow.Step(t, next)
                case _ =>
                    new Arrow.AndThen[A, B, C, S & S2](self, next)
end Arrow

object Arrow:

    def apply[A]: Arrow.Step[A, A, Any] = identity.asInstanceOf[Arrow.Step[A, A, Any]]

    @static private val identity: Transform[Any, Any, Any] =
        new Transform[Any, Any, Any]:
            def frame = Frame.internal
            def apply[C, S2](v: Any < S2, next: Arrow[Any, C, S2]): C < S2 =
                if next eq this then v.asInstanceOf[C < S2]
                else
                    val step = next.step
                    step.head(v, step.tail)

    final private class Scratch:
        val pending            = new ArrayDeque[Arrow[Any, Any, Any]]
        private var region     = new Array[Arrow[Any, Any, Any]](256)
        private[Arrow] var top = 0
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

    abstract class Step[-A, +B, -S] extends Arrow[A, B, S]:
        type X
        def head: Transform[A, X, S]
        def tail: Arrow[X, B, S]
        final def step = this

        // renders the shape plus the first transform's frame only: composed
        // chains can be arbitrarily large and walking them from toString has
        // broken tools that stringify values, like kyo-test
        override def toString: String = s"Arrow.Step(${head.frameInfo})"
    end Step

    object Step:
        @nowarn("msg=anonymous")
        private[Arrow] def apply[A, B, C, S](h: Transform[A, B, S], t: Arrow[B, C, S]): Step[A, C, S] =
            new Step[A, C, S]:
                type X = B
                val head        = h
                val tail        = t
                def apply(v: A) = head(v, tail)
    end Step

    abstract class Transform[-A, B, -S] extends Step[A, B, S]:
        type X = B
        def frame: Frame
        final def head = this
        final def tail = identity.asInstanceOf[Step[B, B, S]]

        final def apply(v: A) =
            apply(v, Arrow[B])

        def apply[C, S2](v: A < S2, next: Arrow[B, C, S2]): C < (S & S2)

        private[Arrow] def frameInfo: String =
            if this eq identity then "identity"
            else s"${frame.position.show}, ${frame.snippetShort}"

        override def toString: String = s"Arrow($frameInfo)"
    end Transform

    final private[Arrow] class AndThen[-A, B, +C, -S](val a: Arrow[A, B, S], val b: Arrow[B, C, S]) extends Arrow[A, C, S]:

        def apply(v: A) =
            applyTo(v.asInstanceOf[A < Any])

        override private[kyo] def applyTo[S2](v: A < S2): C < (S & S2) =
            v match
                case kyo: Kyo[A, S2] @unchecked => kyo.map(this)
                case _                          => drive(v)

        private def drive[S2](v0: A < S2): C < (S & S2) =
            val s    = scratch.get
            val mark = s.top
            unrollUnits(s)
            val end = s.top
            @tailrec def loop(i: Int, v: Any < Nothing): Any < Nothing =
                if i == end then v
                else
                    s(i).applyTo(v) match
                        case kyo: Kyo[Any, Nothing] @unchecked =>
                            remainder(s, i + 1, end) match
                                case rest if rest eq identity => kyo
                                case rest                     => kyo.map(rest)
                        case r =>
                            loop(i + 1, r)
            try loop(mark, v0).asInstanceOf[C < (S & S2)]
            finally s.top = mark
        end drive

        private def remainder(s: Scratch, from: Int, end: Int): Arrow[Any, Any, Any] =
            @tailrec def link(j: Int, acc: Arrow[Any, Any, Any]): Arrow[Any, Any, Any] =
                if j < from then acc
                else link(j - 1, s(j).chain(acc))
            link(end - 1, identity)
        end remainder

        // composition nodes unroll; pre-linked Step chains stay opaque units whose fused
        // head(v, tail) execution attaches its own remainder through the arrow captures,
        // so a minted chain is never re-expanded or re-minted
        private def unrollUnits(s: Scratch): Unit =
            val pending = s.pending
            @tailrec def loop(n: Int): Unit =
                if n > 0 then
                    pending.removeHead() match
                        case at: AndThen[Any, Any, Any, Any] @unchecked =>
                            pending.prepend(at.b)
                            pending.prepend(at.a)
                            loop(n + 1)
                        case u =>
                            if u ne identity then s.push(u)
                            loop(n - 1)
            pending.prepend(this.asInstanceOf[Arrow[Any, Any, Any]])
            loop(1)
        end unrollUnits

        override def toString: String = s"Arrow.AndThen($a, $b)"

        def step =
            val s    = scratch.get
            val mark = s.top
            unrollTransforms(s)
            val res = remainder(s, mark, s.top)
            s.top = mark
            res.asInstanceOf[Step[A, C, S]]
        end step

        // step's contract is a fully linked chain, so pre-linked Steps unroll too
        private def unrollTransforms(s: Scratch): Unit =
            val pending = s.pending
            @tailrec def loop(n: Int): Unit =
                if n > 0 then
                    pending.removeHead() match
                        case at: AndThen[Any, Any, Any, Any] @unchecked =>
                            pending.prepend(at.b)
                            pending.prepend(at.a)
                            loop(n + 1)
                        case t: Transform[Any, Any, Any] @unchecked =>
                            if t ne identity then s.push(t)
                            loop(n - 1)
                        case st: Step[Any, Any, Any] @unchecked =>
                            s.push(st.head)
                            pending.prepend(st.tail.asInstanceOf[Arrow[Any, Any, Any]])
                            loop(n)
            pending.prepend(this.asInstanceOf[Arrow[Any, Any, Any]])
            loop(1)
        end unrollTransforms
    end AndThen

end Arrow
