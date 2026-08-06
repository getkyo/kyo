package kyo.proto2

import kyo.Frame
import kyo.Maybe
import kyo.Span
import kyo.Tag
import language.implicitConversions
import scala.annotation.nowarn
import scala.annotation.tailrec

trait Effect[I[_], O[_]]

sealed abstract class Kyo[+A, -S]:
    def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2)

object Kyo:

    case class Nested[+A](value: A):
        override def toString = "Nested"

    abstract class Suspend[I[_], O[_], E <: Effect[I, O], A] extends Kyo[O[A], E]:

        def input: I[A]
        def tag: Tag[E]
        def frame: Frame

        def map[B, S](f: Arrow[O[A], B, S]): B < (E & S) =
            Continue[I, O, E, A, B, S](this, f)

        override def toString = "Suspend(" + tag.show + ", " + frame.position.show + ")"

    end Suspend

    case class Continue[I[_], O[_], E <: Effect[I, O], A, +B, -S](
        suspend: Suspend[I, O, E, A],
        cont: Arrow[O[A], B, S]
    ) extends Kyo[B, E & S]:

        def map[C, S2](f: Arrow[B, C, S2]): C < (E & S & S2) =
            Continue(suspend, cont.map(f))

        override def toString = "Continue(" + suspend + ")"

    end Continue

end Kyo

opaque type <[+A, -S] = A | Kyo[A, S] | Kyo.Nested[A]

object `<`:

    implicit def lift[A](v: A): A < Any = v

    implicit private[kyo] inline def fromKyo[A, S](v: Kyo[A, S]): A < S = v

    extension [A, S](self: A < S)

        def unsafeGet: A =
            self match
                case Kyo.Nested(v) => v.asInstanceOf[A]
                case _             => self.asInstanceOf[A]

        @nowarn
        inline def map[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            val arrow = new Arrow.Transform[A, B, S2]:
                def frame = _frame
                def run(v: A, rest: Array[Any], from: Int): Any =
                    val w = f(v)
                    if w.isInstanceOf[Kyo[?, ?]] then
                        if from < rest.length then
                            w.asInstanceOf[Kyo[B, Any]].map(Arrow.suffix[B, Any](rest, from))
                        else w
                    else if from < rest.length then
                        rest(from).asInstanceOf[Arrow.Transform[Any, Any, Any]].run(w.unsafeGet, rest, from + 1)
                    else w
                    end if
                end run
            Arrow.of(arrow)(self)
        end map

    end extension

    extension [A](self: A < Any)
        def eval: A =
            self match
                case kyo: Kyo[?, ?] => throw new IllegalStateException("unhandled suspension: " + kyo)
                case _              => self.unsafeGet
    end extension

    def eval[I[_], O[_], E <: Effect[I, O], A](
        tag: Tag[E],
        v: A < E
    )(
        handle: [X] => (I[X], Arrow[O[X], A, E]) => Maybe[A < E]
    ): A < E =
        @tailrec def loop(curr: A < E): A < E =
            curr match
                case c: Kyo.Continue[I, O, E, Any, A, E] @unchecked =>
                    handle(c.suspend.input, Arrow.flat(c.cont)) match
                        case Maybe.Present(next) => loop(next)
                        case _                   => curr
                case s: Kyo.Suspend[I, O, E, Any] @unchecked =>
                    handle(s.input, Arrow[A].asInstanceOf[Arrow[O[Any], A, E]]) match
                        case Maybe.Present(next) => loop(next)
                        case _                   => curr
                case _ =>
                    curr
        loop(v)
    end eval

end `<`

opaque type Arrow[-A, +B, -S] = Arrow.Transform[A, B, S] | Span[Any]

object Arrow:

    abstract class Transform[-A, +B, -S]:
        def frame: Frame
        def run(v: A, rest: Array[Any], from: Int): Any
        override def toString = "Transform(" + frame.position.show + ")"
    end Transform

    final private class Suffix(span: Array[Any], from: Int) extends Transform[Any, Any, Any]:
        def frame = Frame.internal
        def run(v: Any, rest: Array[Any], from2: Int): Any =
            val w = span(from).asInstanceOf[Transform[Any, Any, Any]].run(v, span, from + 1)
            if w.isInstanceOf[Kyo[?, ?]] then
                if from2 < rest.length then
                    w.asInstanceOf[Kyo[Any, Any]].map(suffix[Any, Any](rest, from2))
                else w
            else if from2 < rest.length then
                rest(from2).asInstanceOf[Transform[Any, Any, Any]].run(unwrap(w), rest, from2 + 1)
            else w
            end if
        end run
    end Suffix

    private def unwrap(v: Any): Any =
        v match
            case n: Kyo.Nested[?] => n.value
            case _                => v

    private val emptyElems       = new Array[Any](0)
    private val empty: Span[Any] = Span.fromUnsafe(emptyElems)

    def apply[A]: Arrow[A, A, Any] = empty

    inline def of[A, B, S](t: Transform[A, B, S]): Arrow[A, B, S] = t

    def suffix[A, B](span: Array[Any], from: Int): Arrow[A, B, Any] =
        new Suffix(span, from).asInstanceOf[Arrow[A, B, Any]]

    extension [A, B, S](self: Arrow[A, B, S])

        def apply[S2](v: A < S2): B < (S & S2) =
            (self: Any) match
                case t: Transform[A, B, S] @unchecked =>
                    if v.isInstanceOf[Kyo[?, ?]] then
                        v.asInstanceOf[Kyo[A, S2]].map(self)
                    else
                        t.run(unwrap(v).asInstanceOf[A], emptyElems, 0).asInstanceOf[B < (S & S2)]
                case arr: Array[Any] @unchecked =>
                    if arr.length == 0 then
                        v.asInstanceOf[B < (S & S2)]
                    else if v.isInstanceOf[Kyo[?, ?]] then
                        v.asInstanceOf[Kyo[A, S2]].map(self)
                    else
                        val flat = flatten(arr)
                        flat(0).asInstanceOf[Transform[Any, Any, Any]]
                            .run(unwrap(v), flat, 1).asInstanceOf[B < (S & S2)]

        def map[C, S2](f: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
            if isEmpty(self) then f.asInstanceOf[Arrow[A, C, S & S2]]
            else if isEmpty(f) then self.asInstanceOf[Arrow[A, C, S & S2]]
            else
                val arr = new Array[Any](2)
                arr(0) = self
                arr(1) = f
                Span.fromUnsafe(arr).asInstanceOf[Arrow[A, C, S & S2]]

    end extension

    private[kyo] def flat[X, Y, Z](arrow: Arrow[X, Y, Z]): Arrow[X, Y, Z] =
        (arrow: Any) match
            case arr: Array[Any] @unchecked =>
                Span.fromUnsafe(flatten(arr)).asInstanceOf[Arrow[X, Y, Z]]
            case _ =>
                arrow

    private def isEmpty[X, Y, Z](f: Arrow[X, Y, Z]): Boolean =
        (f: Any) match
            case arr: Array[Any] @unchecked => arr.length == 0
            case _                          => false

    private def isFlat(arr: Array[Any]): Boolean =
        var i    = 0
        var flat = true
        while flat && i < arr.length do
            flat = !arr(i).isInstanceOf[Array[?]]
            i += 1
        flat
    end isFlat

    private val flattenStack = new ThreadLocal[java.util.ArrayDeque[Any]]:
        override def initialValue = new java.util.ArrayDeque[Any]

    private val flattenOut = new ThreadLocal[java.util.ArrayDeque[Any]]:
        override def initialValue = new java.util.ArrayDeque[Any]

    private def flatten(arr: Array[Any]): Array[Any] =
        if isFlat(arr) then arr
        else
            val stack = flattenStack.get()
            val out   = flattenOut.get()
            stack.clear()
            out.clear()
            stack.push(arr)
            while !stack.isEmpty do
                stack.pop() match
                    case a: Array[Any] @unchecked =>
                        var i = a.length - 1
                        while i >= 0 do
                            stack.push(a(i))
                            i -= 1
                    case t =>
                        val _ = out.add(t)
            end while
            val result = out.toArray.asInstanceOf[Array[Any]]
            out.clear()
            result
    end flatten

end Arrow
