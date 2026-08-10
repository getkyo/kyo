package kyo.kernel

import kyo.Frame
import scala.annotation.static
import scala.annotation.tailrec
import scala.collection.mutable.ArrayDeque

sealed abstract class Arrow[-A, +B, -S]:
    self =>

    def apply(v: A): B < S

    def step: Arrow.Step[A, B, S]

    final def chain[C, S2](next: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
        if self eq Arrow.identity then next.asInstanceOf[Arrow[A, C, S & S2]]
        else if next eq Arrow.identity then self.asInstanceOf[Arrow[A, C, S & S2]]
        else
            self match
                case t: Arrow.Transform[A, B, S] @unchecked =>
                    new Arrow.Step[A, C, S & S2]:
                        type X = B
                        val head        = t
                        val tail        = next
                        def apply(v: A) = head(v, tail)
                case _ =>
                    new Arrow.AndThen[A, B, C, S & S2](self, next)
end Arrow

object Arrow:

    abstract class Step[-A, +B, -S] extends Arrow[A, B, S]:
        type X
        def head: Transform[A, X, S]
        def tail: Arrow[X, B, S]
        final def step = this
    end Step

    abstract class Transform[-A, B, -S] extends Step[A, B, S]:
        type X = B
        def frame: Frame
        final def head = this
        final def tail = identity.asInstanceOf[Step[B, B, S]]

        final def apply(v: A) =
            apply(v, Arrow[B])

        def apply[C, S2](v: A < S2, next: Arrow[B, C, S2]): C < (S & S2)
    end Transform

    @static private val identity: Transform[Any, Any, Any] =
        new Transform[Any, Any, Any]:
            def frame = Frame.internal
            def apply[C, S2](v: Any < S2, next: Arrow[Any, C, S2]): C < S2 =
                if next eq this then v.asInstanceOf[C < S2]
                else
                    val step = next.step
                    step.head(v, step.tail)

    @static private val scratch: ThreadLocal[ArrayDeque[Arrow[?, ?, ?]]] =
        new ThreadLocal[ArrayDeque[Arrow[?, ?, ?]]]:
            override def initialValue() = new ArrayDeque

    final private[Arrow] class AndThen[-A, B, +C, -S](val a: Arrow[A, B, S], val b: Arrow[B, C, S]) extends Arrow[A, C, S]:

        def apply(v: A) =
            this.step(v)

        def step =
            val buffer = scratch.get
            buffer.clear()

            @tailrec def loop(pending: Int): Unit =
                if pending > 0 then
                    buffer.removeHead() match
                        case at: AndThen[?, ?, ?, ?] =>
                            buffer.prepend(at.b)
                            buffer.prepend(at.a)
                            loop(pending + 1)
                        case t: Transform[?, ?, ?] =>
                            if t ne identity then buffer.append(t)
                            loop(pending - 1)
                        case s: Step[?, ?, ?] =>
                            buffer.append(s.head)
                            buffer.prepend(s.tail)
                            loop(pending)

            @tailrec def link(acc: Arrow[Any, Any, Any]): Arrow[Any, Any, Any] =
                if buffer.isEmpty then acc
                else
                    val h = buffer.removeLast().asInstanceOf[Transform[Any, Any, Any]]
                    link(
                        new Step[Any, Any, Any]:
                            type X = Any
                            val head          = h
                            val tail          = acc
                            def apply(v: Any) = head(v, tail)
                    )

            buffer.prepend(this)
            loop(1)
            link(identity).asInstanceOf[Step[A, C, S]]
        end step
    end AndThen

    def apply[A]: Arrow.Step[A, A, Any] = identity.asInstanceOf[Arrow.Step[A, A, Any]]

end Arrow
