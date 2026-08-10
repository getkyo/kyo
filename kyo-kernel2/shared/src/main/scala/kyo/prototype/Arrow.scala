package kyo.prototype

import kyo.Frame
import kyo.Span
import kyo.discard
import scala.annotation.tailrec
import scala.collection.mutable.ArrayDeque

sealed abstract class Arrow[-A, +B, -S]:
    self =>

    def apply(v: A): B < S

    def step: Arrow.Step[A, B, S]

    def chain[C, S2](next: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
        if self eq Arrow.identity then next.asInstanceOf[Arrow[A, C, S & S2]]
        else if next eq Arrow.identity then self.asInstanceOf[Arrow[A, C, S & S2]]
        else
            new Arrow.AndThen[A, B, C, S & S2]:
                def a = self
                def b = next
end Arrow

object Arrow:

    abstract class Step[-A, +B, -S] extends Arrow[A, B, S]:
        type X
        def head: Transform[A, X, S]
        def tail: Arrow.Step[X, B, S]
        def step = this
    end Step

    abstract class Transform[-A, B, -S] extends Step[A, B, S]:
        type X = B
        def frame: Frame
        def head = this
        def tail = identity.asInstanceOf[Step[B, B, S]]

        def apply(v: A) =
            apply(v, Arrow[B])

        def apply[C, S2](v: A < S2, next: Arrow[B, C, S2]): C < (S & S2)
    end Transform

    private val identity =
        new Transform[Any, Any, Any]:
            def frame = Frame.internal
            def apply[C, S2](v: Any < S2, next: Arrow[Any, C, S2]): C < S2 =
                if next eq this then v.asInstanceOf[C < S2]
                else
                    val step = next.step
                    step.head(v, step.tail)

    abstract class Flat[-A, +B, -S] extends Step[A, B, S]:
        self =>

        type X = Any
        def span: Span[Transform[?, ?, ?]]
        def offset: Int

        def apply(v: A) = head(v, tail)
        def head        = span(offset).asInstanceOf[Transform[A, X, S]]
        def tail =
            if offset == span.size - 1 then
                identity.asInstanceOf[Step[X, B, S]]
            else
                new Flat[X, B, S]:
                    def span   = self.span
                    def offset = self.offset + 1
    end Flat

    private val scratch = new ThreadLocal[ArrayDeque[Arrow[?, ?, ?]]]:
        override def initialValue() = new ArrayDeque

    abstract private[Arrow] class AndThen[-A, B, +C, -S] extends Arrow[A, C, S]:
        def a: Arrow[A, B, S]
        def b: Arrow[B, C, S]

        def apply(v: A) =
            this.step(v)

        def step =
            val buffer = scratch.get
            buffer.clear()

            @tailrec def copy(span: Span[Transform[?, ?, ?]], i: Int): Unit =
                if i < span.size then
                    buffer.append(span(i))
                    copy(span, i + 1)

            @tailrec def loop(pending: Int): Unit =
                if pending > 0 then
                    buffer.removeHead() match
                        case at: AndThen[?, ?, ?, ?] =>
                            buffer.prepend(at.b)
                            buffer.prepend(at.a)
                            loop(pending + 1)
                        case flat: Flat[?, ?, ?] =>
                            copy(flat.span, flat.offset)
                            loop(pending - 1)
                        case t: Transform[?, ?, ?] =>
                            if t ne identity then buffer.append(t)
                            loop(pending - 1)
                        case s: Step[?, ?, ?] =>
                            buffer.append(s.head)
                            buffer.prepend(s.tail)
                            loop(pending)

            buffer.prepend(this)
            loop(1)
            val array = new Array[Transform[?, ?, ?]](buffer.size)
            discard(buffer.copyToArray(array.asInstanceOf[Array[Arrow[?, ?, ?]]]))
            buffer.clear()
            new Flat[A, C, S]:
                def span   = Span.fromUnsafe(array)
                def offset = 0
        end step
    end AndThen

    def apply[A]: Arrow.Step[A, A, Any] = identity.asInstanceOf[Arrow.Step[A, A, Any]]

end Arrow
