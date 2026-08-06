package kyo.proto2

import kyo.Frame
import kyo.Maybe
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

    abstract class Bracket[R, A, S] extends Kyo[A, S]:

        def acquire: R < S
        def release(r: R): Unit < S
        def cont: Arrow[R, A, S]
        def frame: Frame

        final def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2) =
            val outer = this
            new Bracket[R, B, S & S2]:
                def acquire       = outer.acquire
                def release(r: R) = outer.release(r)
                def cont          = outer.cont.map(f)
                def frame         = outer.frame
            end new
        end map

        override def toString = "Bracket(" + frame.position.show + ")"

    end Bracket

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

        private[kyo] def discard: Unit =
            discardValue(self) match
                case Nil => ()
                case t :: rest =>
                    rest.foreach(t.addSuppressed)
                    throw t

        @nowarn
        inline def map[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            val arrow = new Arrow.Transform[A, B, S2]:
                def frame = _frame
                def run[C, S3](v: A, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                    val w = f(v)
                    (cont: Any) match
                        case o: Arrow.Offset if !w.isInstanceOf[Kyo[?, ?]] =>
                            o.head.run(w.unsafeGet, o.next).asInstanceOf[C < (S2 & S3)]
                        case _ =>
                            cont(w)
                    end match
                end run
            Arrow.of(arrow)(self)
        end map

    end extension

    extension [A](self: A < Any)
        def eval: A =
            if self.isInstanceOf[Kyo[?, ?]] then
                evalLoop(self.asInstanceOf[Any < Any], unhandled).unsafeGet.asInstanceOf[A]
            else self.unsafeGet
    end extension

    def eval[I[_], O[_], E <: Effect[I, O], A](
        tag: Tag[E],
        v: A < E
    )(
        handle: [X] => (I[X], Arrow[O[X], A, E]) => Maybe[A < E]
    ): A < E =
        val handler: Kyo[Any, Any] => Maybe[Any < Any] =
            case c: Kyo.Continue[I, O, E, Any, A, E] @unchecked if c.suspend.tag =:= tag =>
                handle(c.suspend.input, c.cont.optimize).asInstanceOf[Maybe[Any < Any]]
            case s: Kyo.Suspend[I, O, E, Any] @unchecked if s.tag =:= tag =>
                handle(s.input, Arrow[A].asInstanceOf[Arrow[O[Any], A, E]]).asInstanceOf[Maybe[Any < Any]]
            case _ =>
                Maybe.Absent
        end handler
        evalLoop(v.asInstanceOf[Any < Any], handler).asInstanceOf[A < E]
    end eval

    private val unhandled: Kyo[Any, Any] => Maybe[Any < Any] =
        kyo => throw new IllegalStateException("unhandled suspension: " + kyo)

    private inline def BracketDepth = 512

    private def discardValue[A, S](v: A < S): List[Throwable] =
        v match
            case kyo: Kyo.Continue[?, ?, ?, ?, ?, ?] => discardArrow(kyo.cont)
            case _                                   => Nil

    private def discardArrow(arrow: Any): List[Throwable] =
        arrow match
            case finalize: Finalize[?, ?, ?] =>
                try
                    val _ = finalize.bracket.release(finalize.value).asInstanceOf[Unit < Any].eval
                    Nil
                catch
                    case t: Throwable =>
                        t :: Nil
            case o: Arrow.Offset =>
                discardElems(o.elems, o.from)
            case arr: Array[Any] @unchecked =>
                discardElems(arr, 0)
            case _ =>
                Nil

    private def discardElems(elems: Array[?], from: Int): List[Throwable] =
        var errors = List.empty[Throwable]
        var i      = from
        while i < elems.length do
            errors = errors ++ discardArrow(elems(i))
            i += 1
        errors
    end discardElems

    private def yieldValue[A](v: A): Arrow[Unit, A, Any] =
        Arrow.of(
            new Arrow.Transform[Unit, A, Any]:
                def frame = Frame.internal
                def run[C, S2](x: Unit, cont: Arrow[A, C, S2]): C < (Any & S2) =
                    cont(v.asInstanceOf[A < Any])
        )

    final private[kyo] class Finalize[R, A, S](val bracket: Kyo.Bracket[R, ?, S], val value: R)
        extends Arrow.Transform[A, A, S]:
        def frame = bracket.frame
        def run[C, S2](v: A, cont: Arrow[A, C, S2]): C < (S & S2) =
            cont(yieldValue(v)(bracket.release(value)))
    end Finalize

    private def constant(v: Any < Any): Arrow[Any, Any, Any] =
        Arrow.of(
            new Arrow.Transform[Any, Any, Any]:
                def frame = Frame.internal
                def run[C, S2](x: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
                    cont(v)
        )

    private def reacquire(bracket: Kyo.Bracket[Any, Any, Any]): Arrow[Any, Any, Any] =
        Arrow.of(
            new Arrow.Transform[Any, Any, Any]:
                def frame = Frame.internal
                def run[C, S2](r: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
                    cont(
                        new Kyo.Bracket[Any, Any, Any]:
                            def acquire         = r
                            def release(x: Any) = bracket.release(x)
                            def cont            = bracket.cont
                            def frame           = bracket.frame
                    )
        )

    private def cleanup(bracket: Kyo.Bracket[Any, Any, Any], resource: Any, t: Throwable): Unit =
        try
            val _ = bracket.release(resource).eval
        catch
            case t2: Throwable =>
                t.addSuppressed(t2)

    private def evalLoop(
        v0: Any < Any,
        handle: Kyo[Any, Any] => Maybe[Any < Any]
    ): Any < Any =
        def drive(v: Any < Any, depth: Int): Any < Any =
            @tailrec def loop(curr: Any < Any): Any < Any =
                curr match
                    case bracket: Kyo.Bracket[Any, Any, Any] @unchecked =>
                        if depth >= BracketDepth then bracket
                        else
                            drive(bracket.acquire, depth + 1) match
                                case suspended: Kyo[Any, Any] @unchecked =>
                                    val wrapped = suspended.map(reacquire(bracket))
                                    if depth == 0 then loop(wrapped) else wrapped
                                case acquired =>
                                    val resource = acquired.unsafeGet
                                    val result =
                                        try drive(bracket.cont(acquired), depth + 1)
                                        catch
                                            case t: Throwable =>
                                                cleanup(bracket, resource, t)
                                                throw t
                                    result match
                                        case suspended: Kyo[Any, Any] @unchecked =>
                                            val wrapped = suspended.map(new Finalize[Any, Any, Any](bracket, resource))
                                            if depth == 0 then loop(wrapped) else wrapped
                                        case _ =>
                                            drive(bracket.release(resource), depth + 1) match
                                                case suspended: Kyo[Any, Any] @unchecked =>
                                                    val wrapped = suspended.map(constant(result))
                                                    if depth == 0 then loop(wrapped) else wrapped
                                                case _ =>
                                                    result
                                    end match
                    case kyo: Kyo[Any, Any] @unchecked =>
                        if depth == 0 then
                            handle(kyo) match
                                case Maybe.Present(next) => loop(next)
                                case _                   => kyo
                        else kyo
                    case _ =>
                        curr
            loop(v)
        end drive
        drive(v0, 0)
    end evalLoop

end `<`

opaque type Arrow[-A, +B, -S] = Arrow.Transform[A, B, S] | Array[?]

object Arrow:

    abstract class Transform[-A, +B, -S]:
        def frame: Frame
        def run[C, S2](v: A, cont: Arrow[B, C, S2]): C < (S & S2)
        override def toString = "Transform(" + frame.position.show + ")"
    end Transform

    private def unwrap(v: Any): Any =
        v match
            case n: Kyo.Nested[?] => n.value
            case _                => v

    private val empty = new Array[Transform[?, ?, ?]](0)

    def apply[A]: Arrow[A, A, Any] = empty

    inline def of[A, B, S](t: Transform[A, B, S]): Arrow[A, B, S] = t

    extension [A, B, S](self: Arrow[A, B, S])

        def apply[S2](v: A < S2): B < (S & S2) =
            (self: Any) match
                case t: Transform[A, B, S] @unchecked =>
                    if v.isInstanceOf[Kyo[?, ?]] then
                        v.asInstanceOf[Kyo[A, S2]].map(self)
                    else
                        t.run(unwrap(v).asInstanceOf[A], Arrow[B]).asInstanceOf[B < (S & S2)]
                case flat: Array[Transform[?, ?, ?]] @unchecked =>
                    if flat.length == 0 then
                        v.asInstanceOf[B < (S & S2)]
                    else if v.isInstanceOf[Kyo[?, ?]] then
                        v.asInstanceOf[Kyo[A, S2]].map(self)
                    else
                        flat(0).asInstanceOf[Transform[Any, Any, Any]]
                            .run(unwrap(v), tail(flat.asInstanceOf[Array[Transform[?, ?, ?]]], 1)).asInstanceOf[B < (S & S2)]
                case arr: Array[Any] @unchecked =>
                    if arr.length == 0 then
                        v.asInstanceOf[B < (S & S2)]
                    else if v.isInstanceOf[Kyo[?, ?]] then
                        v.asInstanceOf[Kyo[A, S2]].map(self)
                    else
                        self.optimize(v)

        def map[C, S2](f: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
            if isEmpty(self) then f.asInstanceOf[Arrow[A, C, S & S2]]
            else if isEmpty(f) then self.asInstanceOf[Arrow[A, C, S & S2]]
            else if self.isInstanceOf[Transform[?, ?, ?]] && f.isInstanceOf[Transform[?, ?, ?]] then
                val arr = new Array[Transform[?, ?, ?]](2)
                arr(0) = self.asInstanceOf[Transform[?, ?, ?]]
                arr(1) = f.asInstanceOf[Transform[?, ?, ?]]
                arr
            else
                val arr = new Array[Any](2)
                arr(0) = self
                arr(1) = f
                arr

        def optimize: Arrow[A, B, S] =
            def unfold(arr: Array[Any]): Array[Transform[?, ?, ?]] =
                val buffer = optimizeBuffer.get()
                buffer.clear()
                buffer.push(arr)
                var pending = 1
                while pending > 0 do
                    pending -= 1
                    buffer.pop() match
                        case a: Array[Any] @unchecked =>
                            var i = a.length - 1
                            while i >= 0 do
                                buffer.push(a(i))
                                pending += 1
                                i -= 1
                            end while
                        case t =>
                            val _ = buffer.add(t)
                    end match
                end while
                val result = buffer.toArray(new Array[Transform[?, ?, ?]](buffer.size))
                buffer.clear()
                result
            end unfold
            (self: Any) match
                case _: Array[Transform[?, ?, ?]] @unchecked =>
                    self
                case arr: Array[Any] @unchecked =>
                    unfold(arr)
                case _ =>
                    self
            end match
        end optimize

    end extension

    final class Offset private[kyo] (
        private[kyo] val elems: Array[Transform[?, ?, ?]],
        private[kyo] val from: Int
    ) extends Transform[Any, Any, Any]:
        def frame = Frame.internal

        def head: Transform[Any, Any, Any] =
            elems(from).asInstanceOf[Transform[Any, Any, Any]]

        def next: Arrow[Any, Any, Any] =
            tail(elems, from + 1)
        def run[C, S2](v: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            @tailrec def loop(es: Array[Transform[?, ?, ?]], i: Int, cur: Any): Any =
                if i == es.length then cont(cur.asInstanceOf[Any < Any])
                else
                    es(i) match
                        case o: Offset if i + 1 == es.length =>
                            loop(o.elems, o.from, cur)
                        case t =>
                            val w = t.asInstanceOf[Transform[Any, Any, Any]].run(cur, empty)
                            if w.isInstanceOf[Kyo[?, ?]] then
                                tail(es, i + 1).map(cont)(w.asInstanceOf[Any < Any])
                            else loop(es, i + 1, unwrap(w))
            loop(elems, from, v).asInstanceOf[C < (Any & S2)]
        end run
    end Offset

    private def tail(elems: Array[Transform[?, ?, ?]], from: Int): Arrow[Any, Any, Any] =
        if from >= elems.length then empty
        else new Offset(elems, from)

    private val optimizeBuffer = new ThreadLocal[java.util.ArrayDeque[Any]]:
        override def initialValue = new java.util.ArrayDeque[Any]

    private def isEmpty[X, Y, Z](f: Arrow[X, Y, Z]): Boolean =
        (f: Any) match
            case arr: Array[Any] @unchecked => arr.length == 0
            case _                          => false

end Arrow
