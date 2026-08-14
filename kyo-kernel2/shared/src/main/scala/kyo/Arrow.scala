package kyo

import kyo.Frame
import kyo.kernel.<
import scala.annotation.nowarn
import scala.annotation.static
import scala.annotation.tailrec
import scala.collection.mutable.ArrayDeque

sealed abstract class Arrow[-A, +B, -S]:
    self =>

    def step: Arrow.Step[A, B, S]

    def isIdentity: Boolean = self eq Arrow.identity

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

    def apply[A]: Arrow[A, A, Any] = identity.asInstanceOf[Arrow[A, A, Any]]

    @static private val identity: Transform[Any, Any, Any] =
        new Transform[Any, Any, Any]:
            def frame = Frame.internal
            def apply[C, S2](v: Any < S2, next: Arrow[Any, C, S2]): C < S2 =
                if next eq this then v.asInstanceOf[C < S2]
                else
                    val step = next.step
                    step.head(v, step.tail)

    abstract class Step[-A, +B, -S] extends Arrow[A, B, S]:
        type X
        def head: Transform[A, X, S]
        def tail: Arrow[X, B, S]
        final def step = this
    end Step

    object Step:
        @nowarn("msg=anonymous")
        private[Arrow] def apply[A, B, C, S](h: Transform[A, B, S], t: Arrow[B, C, S]): Step[A, C, S] =
            new Step[A, C, S]:
                type X = B
                val head = h
                val tail = t
    end Step

    abstract class Transform[-A, B, -S] extends Step[A, B, S]:
        type X = B
        def frame: Frame
        final def head = this
        final def tail = Arrow[B]

        def apply[C, S2](v: A < S2, next: Arrow[B, C, S2]): C < (S & S2)
    end Transform

    private val scratch: ThreadLocal[ArrayDeque[Arrow[?, ?, ?]]] =
        new ThreadLocal[ArrayDeque[Arrow[?, ?, ?]]]:
            override def initialValue() = new ArrayDeque

    class AndThen[-A, B, +C, -S](val a: Arrow[A, B, S], val b: Arrow[B, C, S]) extends Arrow[A, C, S]:

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
                else link(Step(buffer.removeLast().asInstanceOf[Transform[Any, Any, Any]], acc))

            buffer.prepend(this)
            loop(1)
            link(identity).asInstanceOf[Step[A, C, S]]
        end step
    end AndThen

end Arrow
