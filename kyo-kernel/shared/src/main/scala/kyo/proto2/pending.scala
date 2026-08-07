package kyo.proto2

import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import language.implicitConversions
import scala.annotation.nowarn
import scala.annotation.static
import scala.annotation.tailrec

trait Effect[I[_], O[_]]

sealed abstract class Kyo[+A, -S]:
    def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2)
    private[kyo] def prepend(f: Arrow[Any, Any, Any]): A < S

object Kyo:

    // Compiled as a JVM static of class Kyo: hot callers (minted arrow fragments,
    // Arrow.apply, Offset.run) reach it via invokestatic with no module load and,
    // in minted fragments, no captured reference to an enclosing object.
    @static def unwrap(v: Any): Any =
        v match
            case n: Nested[?] => n.value
            case _            => v

    final class Nested[+A](val value: A):
        override def toString = "Nested"

    abstract class Suspend[I[_], O[_], E <: Effect[I, O], A] extends Kyo[O[A], E]:

        def input: I[A]
        def tag: Tag[E]
        def frame: Frame

        final def map[B, S](f: Arrow[O[A], B, S]): B < (E & S) =
            Continue[I, O, E, A, B, S](this, f)

        final private[kyo] def prepend(f: Arrow[Any, Any, Any]): O[A] < E =
            map(f.asInstanceOf[Arrow[O[A], O[A], Any]])

        final override def toString = "Suspend(" + tag.show + ", " + frame.position.show + ")"

    end Suspend

    final class Continue[I[_], O[_], E <: Effect[I, O], A, +B, -S](
        val suspend: Suspend[I, O, E, A],
        val cont: Arrow[O[A], B, S]
    ) extends Kyo[B, E & S]:

        def map[C, S2](f: Arrow[B, C, S2]): C < (E & S & S2) =
            Continue(suspend, cont.map(f))

        private[kyo] def prepend(f: Arrow[Any, Any, Any]): B < (E & S) =
            Continue(suspend, f.map(cont.asInstanceOf[Arrow[Any, B, S]]).asInstanceOf[Arrow[O[A], B, S]])

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

        final private[kyo] def prepend(f: Arrow[Any, Any, Any]): A < S =
            val outer = this
            new Bracket[R, A, S]:
                def acquire =
                    outer.acquire match
                        case kyo: Kyo[R, S] @unchecked => kyo.prepend(f)
                        case v                         => v
                def release(r: R) = outer.release(r)
                def cont          = f.map(outer.cont.asInstanceOf[Arrow[Any, A, S]]).asInstanceOf[Arrow[R, A, S]]
                def frame         = outer.frame
            end new
        end prepend

        final override def toString = "Bracket(" + frame.position.show + ")"

    end Bracket

    final private[kyo] class Defer[A, +B, -S](
        val value: A,
        val cont: Arrow[A, B, S]
    ) extends Kyo[B, S]:

        def map[C, S2](f: Arrow[B, C, S2]): C < (S & S2) =
            Defer(value, cont.map(f))

        private[kyo] def prepend(f: Arrow[Any, Any, Any]): B < S =
            Defer(value, f.map(cont.asInstanceOf[Arrow[Any, B, S]]).asInstanceOf[Arrow[A, B, S]])

        override def toString = "Defer"

    end Defer

end Kyo

opaque type <[+A, -S] = A | Kyo[A, S] | Kyo.Nested[A]

object `<`:

    implicit def lift[A](v: A): A < Any =
        v match
            case _: Kyo[?, ?] | _: Kyo.Nested[?] => Kyo.Nested(v).asInstanceOf[A < Any]
            case _                               => v

    implicit private[kyo] inline def fromKyo[A, S](v: Kyo[A, S]): A < S = v

    extension [A, S](self: A < S)

        private[kyo] def discard: Unit =
            discardValue(self) match
                case Nil => ()
                case t :: rest =>
                    rest.foreach(t.addSuppressed)
                    throw t
    end extension

    extension [A, S](inline self: A < S)
        @nowarn
        inline def map[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            val arrow = new Arrow.Transform[A, B, S2]:
                def frame = _frame
                def run[C, S3](v: Any, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                    val w = f(v.asInstanceOf[A])
                    (cont: Any) match
                        case o: Arrow.Offset if !w.isInstanceOf[Kyo[?, ?]] =>
                            o.head.run(Kyo.unwrap(w), o.next).asInstanceOf[C < (S2 & S3)]
                        case _ =>
                            cont(w)
                    end match
                end run
            Arrow.of(arrow)(self)
        end map

    end extension

    def observe[A, S](observer: (Frame, Any) => Unit)(v: A < S): A < S =
        v match
            case kyo: Kyo[A, S] @unchecked =>
                kyo.prepend(Arrow.of(new Observe(observer)).asInstanceOf[Arrow[Any, Any, Any]])
            case _ =>
                v
    end observe

    final class Observe(observer: (Frame, Any) => Unit) extends Arrow.Transform[Any, Any, Any]:
        def frame = Frame.internal
        def run[C, S2](v: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            @tailrec def loop(es: Array[Arrow.Transform[?, ?, ?]], i: Int, cur: Any): Any =
                if i == es.length then cur
                else
                    es(i) match
                        case o: Arrow.Offset =>
                            loop(o.elems, o.from, cur)
                        case t0 =>
                            val t = t0.asInstanceOf[Arrow.Transform[Any, Any, Any]]
                            observer(t.frame, cur)
                            val w = t.run(cur, Arrow[Any])
                            if w.isInstanceOf[Kyo[?, ?]] then
                                val rest =
                                    if i + 1 < es.length then
                                        Arrow.map(Arrow.of[Any, Any, Any](this))(Arrow.tail(es, i + 1))
                                    else Arrow.of[Any, Any, Any](this)
                                w.asInstanceOf[Kyo[Any, Any]].map(rest)
                            else loop(es, i + 1, Kyo.unwrap(w))
                            end if
            (cont: Any) match
                case o: Arrow.Offset =>
                    loop(o.elems, o.from, v).asInstanceOf[C < (Any & S2)]
                case _ =>
                    cont(v.asInstanceOf[Any < Any])
            end match
        end run
    end Observe

    extension [A](self: A < Any)

        inline def eval: A =
            if self.isInstanceOf[Kyo[?, ?]] then
                Kyo.unwrap(evalLoop(self.asInstanceOf[Any < Any], never, 1, unhandled)).asInstanceOf[A]
            else Kyo.unwrap(self).asInstanceOf[A]

        def eval(preempt: () => Boolean, period: Int): A < Any =
            evalLoop(self.asInstanceOf[Any < Any], preempt, Integer.max(1, period / Arrow.Period), unhandled).asInstanceOf[A < Any]

    end extension

    def evalPartial[I[_], O[_], E <: Effect[I, O], A](
        tag: Tag[E],
        v: A < E,
        preempt: () => Boolean = never,
        period: Int = Arrow.Period
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
        evalLoop(v.asInstanceOf[Any < Any], preempt, Integer.max(1, period / Arrow.Period), handler).asInstanceOf[A < E]
    end evalPartial

    def eval[I[_], O[_], E <: Effect[I, O], A](
        tag: Tag[E],
        v: A < E
    )(
        handle: [X] => (I[X], Arrow[O[X], A, E]) => A < E
    ): A =
        Kyo.unwrap(
            evalPartial(tag, v)(
                [X] => (input: I[X], cont: Arrow[O[X], A, E]) => Maybe(handle(input, cont))
            )
        ).asInstanceOf[A]

    private val never: () => Boolean = () => false

    private val unhandled: Kyo[Any, Any] => Maybe[Any < Any] =
        kyo => throw new IllegalStateException("unhandled suspension: " + kyo)

    private inline def BracketDepth = 512

    private def discardValue[A, S](v: A < S): List[Throwable] =
        v match
            case kyo: Kyo.Continue[?, ?, ?, ?, ?, ?] => discardArrow(kyo.cont)
            case kyo: Kyo.Defer[?, ?, ?]             => discardArrow(kyo.cont)
            case _                                   => Nil

    private def discardArrow(arrow: Any): List[Throwable] =
        arrow match
            case finalize: Finalize[?, ?, ?] =>
                try
                    val _ = finalize.bracket.release(finalize.value).asInstanceOf[Unit < Any].eval
                    Nil
                catch
                    case t: Throwable =>
                        KyoException.attach(t, "release", finalize.bracket.frame)
                        t :: Nil
            case o: Arrow.Offset =>
                discardElems(o.elems, o.from)
            case arr: Array[Any] @unchecked =>
                discardElems(arr, 0)
            case _ =>
                Nil

    private def discardElems(elems: Array[?], from: Int): List[Throwable] =
        @tailrec def loop(i: Int, errors: List[Throwable]): List[Throwable] =
            if i == elems.length then errors
            else loop(i + 1, errors ++ discardArrow(elems(i)))
        loop(from, List.empty)
    end discardElems

    private def yieldValue[A](v: A): Arrow[Unit, A, Any] =
        Arrow.of(
            new Arrow.Transform[Unit, A, Any]:
                def frame = Frame.internal
                def run[C, S2](x: Any, cont: Arrow[A, C, S2]): C < (Any & S2) =
                    cont(v.asInstanceOf[A < Any])
        )

    final private[kyo] class Finalize[R, A, S](val bracket: Kyo.Bracket[R, ?, S], val value: R)
        extends Arrow.Transform[A, A, S]:
        def frame = bracket.frame
        def run[C, S2](v: Any, cont: Arrow[A, C, S2]): C < (S & S2) =
            cont(yieldValue(v.asInstanceOf[A])(bracket.release(value)))
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
                KyoException.attach(t2, "release", bracket.frame)
                t.addSuppressed(t2)

    private def preempted(v: Any < Any): Boolean =
        v.isInstanceOf[Kyo.Defer[?, ?, ?]]

    private def evalLoop(
        v0: Any < Any,
        preempt: () => Boolean,
        stride: Int,
        handle: Kyo[Any, Any] => Maybe[Any < Any]
    ): Any < Any =
        def drive(v: Any < Any, depth: Int): Any < Any =
            @tailrec def loop(curr: Any < Any, n: Int): Any < Any =
                curr match
                    case bracket: Kyo.Bracket[Any, Any, Any] @unchecked =>
                        if depth >= BracketDepth then bracket
                        else
                            drive(bracket.acquire, depth + 1) match
                                case suspended: Kyo[Any, Any] @unchecked =>
                                    val wrapped = suspended.map(reacquire(bracket))
                                    if depth == 0 && !preempted(wrapped) then loop(wrapped, n) else wrapped
                                case acquired =>
                                    val resource = Kyo.unwrap(acquired)
                                    val result =
                                        try drive(bracket.cont(acquired), depth + 1)
                                        catch
                                            case t: Throwable =>
                                                cleanup(bracket, resource, t)
                                                throw t
                                    result match
                                        case suspended: Kyo[Any, Any] @unchecked =>
                                            val wrapped = suspended.map(new Finalize[Any, Any, Any](bracket, resource))
                                            if depth == 0 && !preempted(wrapped) then loop(wrapped, n) else wrapped
                                        case _ =>
                                            drive(bracket.release(resource), depth + 1) match
                                                case suspended: Kyo[Any, Any] @unchecked =>
                                                    val wrapped = suspended.map(constant(result))
                                                    if depth == 0 && !preempted(wrapped) then loop(wrapped, n) else wrapped
                                                case _ =>
                                                    result
                                    end match
                    case defer: Kyo.Defer[Any, Any, Any] @unchecked =>
                        if n == 0 then
                            if preempt() then curr
                            else loop(defer.cont(defer.value.asInstanceOf[Any < Any]), stride - 1)
                        else loop(defer.cont(defer.value.asInstanceOf[Any < Any]), n - 1)
                    case kyo: Kyo[Any, Any] @unchecked =>
                        if depth == 0 then
                            handle(kyo) match
                                case Maybe.Present(next) => loop(next, n)
                                case _                   => kyo
                        else kyo
                    case _ =>
                        curr
            loop(v, 0)
        end drive
        try drive(v0, 0)
        catch
            case ex: Throwable =>
                KyoException.install(ex)
                throw ex
        end try
    end evalLoop

end `<`

opaque type Arrow[-A, +B, -S] = Arrow.Transform[A, B, S] | Array[?]

object Arrow:

    private[kyo] inline def Period = 512
    private inline def SmallLimit  = 32

    private[kyo] def probe(): Boolean =
        false

    abstract class Transform[-A, +B, -S]:
        def frame: Frame
        // v is Any rather than A: a typed parameter makes subclasses with a concrete
        // A carry an erasure bridge, and the extra call level halves how many fused
        // steps the JIT can inline per compilation.
        def run[C, S2](v: Any, cont: Arrow[B, C, S2]): C < (S & S2)
        override def toString = "Transform(" + frame.position.show + ")"
    end Transform

    private val empty = new Array[Transform[?, ?, ?]](0)

    def apply[A]: Arrow[A, A, Any] = empty

    def of[A, B, S](t: Transform[A, B, S]): Arrow[A, B, S] = t

    extension [A, B, S](self: Arrow[A, B, S])

        def apply[S2](v: A < S2): B < (S & S2) =
            (self: Any) match
                case o: Offset =>
                    if v.isInstanceOf[Kyo[?, ?]] then
                        v.asInstanceOf[Kyo[A, S2]].map(self)
                    else if probe() then
                        Kyo.Defer(Kyo.unwrap(v), self.asInstanceOf[Arrow[Any, Any, Any]]).asInstanceOf[B < (S & S2)]
                    else
                        o.head.run(Kyo.unwrap(v), o.next).asInstanceOf[B < (S & S2)]
                case t: Transform[A, B, S] @unchecked =>
                    if v.isInstanceOf[Kyo[?, ?]] then
                        v.asInstanceOf[Kyo[A, S2]].map(self)
                    else if probe() then
                        Kyo.Defer(Kyo.unwrap(v), self.asInstanceOf[Arrow[Any, Any, Any]]).asInstanceOf[B < (S & S2)]
                    else
                        try t.run(Kyo.unwrap(v), Arrow[B]).asInstanceOf[B < (S & S2)]
                        catch
                            case ex: Throwable =>
                                KyoException.attach(ex, "map", t.frame)
                                throw ex
                case flat: Array[Transform[?, ?, ?]] @unchecked =>
                    if flat.length == 0 then
                        v.asInstanceOf[B < (S & S2)]
                    else if v.isInstanceOf[Kyo[?, ?]] then
                        v.asInstanceOf[Kyo[A, S2]].map(self)
                    else if probe() then
                        Kyo.Defer(Kyo.unwrap(v), self.asInstanceOf[Arrow[Any, Any, Any]]).asInstanceOf[B < (S & S2)]
                    else
                        flat(0).asInstanceOf[Transform[Any, Any, Any]]
                            .run(Kyo.unwrap(v), tail(flat.asInstanceOf[Array[Transform[?, ?, ?]]], 1)).asInstanceOf[B < (S & S2)]
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
                @tailrec def push(a: Array[Any], i: Int): Int =
                    if i < 0 then a.length
                    else
                        buffer.push(a(i))
                        push(a, i - 1)
                @tailrec def drain(pending: Int): Unit =
                    if pending > 0 then
                        buffer.pop() match
                            case a: Array[Any] @unchecked =>
                                drain(pending - 1 + push(a, a.length - 1))
                            case t =>
                                val _ = buffer.add(t)
                                drain(pending - 1)
                drain(1)
                val result = buffer.toArray(new Array[Transform[?, ?, ?]](buffer.size))
                buffer.clear()
                result
            end unfold
            def count(a: Array[Any], depth: Int): Int =
                if depth > SmallLimit then -1
                else
                    @tailrec def loop(i: Int, total: Int): Int =
                        if i == a.length then
                            if total > SmallLimit then -1 else total
                        else
                            (a(i): Any) match
                                case inner: Array[Any] @unchecked =>
                                    val c = count(inner, depth + 1)
                                    if c < 0 then -1 else loop(i + 1, total + c)
                                case _ =>
                                    loop(i + 1, total + 1)
                    loop(0, 0)
            end count
            def fill(a: Array[Any], out: Array[Transform[?, ?, ?]], at: Int): Int =
                @tailrec def loop(i: Int, j: Int): Int =
                    if i == a.length then j
                    else
                        (a(i): Any) match
                            case inner: Array[Any] @unchecked =>
                                loop(i + 1, fill(inner, out, j))
                            case t =>
                                out(j) = t.asInstanceOf[Transform[?, ?, ?]]
                                loop(i + 1, j + 1)
                loop(0, at)
            end fill
            (self: Any) match
                case arr: Array[Transform[?, ?, ?]] @unchecked =>
                    tail(arr.asInstanceOf[Array[Transform[?, ?, ?]]], 0).asInstanceOf[Arrow[A, B, S]]
                case arr: Array[Any] @unchecked =>
                    val n = count(arr, 0)
                    if n == 0 then empty
                    else if n > 0 then
                        val out = new Array[Transform[?, ?, ?]](n)
                        val _   = fill(arr, out, 0)
                        tail(out, 0).asInstanceOf[Arrow[A, B, S]]
                    else tail(unfold(arr), 0).asInstanceOf[Arrow[A, B, S]]
                    end if
                case _ =>
                    self
            end match
        end optimize

    end extension

    final class Offset private[kyo] (
        private[kyo] val elems: Array[Transform[?, ?, ?]],
        private[kyo] val from: Int,
        private[kyo] val next: Arrow[Any, Any, Any]
    ) extends Transform[Any, Any, Any]:
        def frame = Frame.internal

        def head: Transform[Any, Any, Any] =
            elems(from).asInstanceOf[Transform[Any, Any, Any]]

        def run[C, S2](v: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            @tailrec def loop(o: Offset, cur: Any): Any =
                if probe() then
                    Kyo.Defer(cur, o.map(cont).asInstanceOf[Arrow[Any, Any, Any]])
                else
                    o.head match
                        case jump: Offset if isEmpty(o.next) =>
                            loop(jump, cur)
                        case t =>
                            val w =
                                try t.run(cur, empty)
                                catch
                                    case ex: Throwable =>
                                        KyoException.attach(ex, "map", t.frame)
                                        throw ex
                            if w.isInstanceOf[Kyo[?, ?]] then
                                o.next.map(cont)(w.asInstanceOf[Any < Any])
                            else
                                (o.next: Any) match
                                    case n: Offset => loop(n, Kyo.unwrap(w))
                                    case _         => cont(Kyo.unwrap(w).asInstanceOf[Any < Any])
                            end if
            loop(this, v).asInstanceOf[C < (Any & S2)]
        end run

        override def toString = "Offset(" + from + ")"
    end Offset

    private[kyo] def tail(elems: Array[Transform[?, ?, ?]], from: Int): Arrow[Any, Any, Any] =
        @tailrec def link(i: Int, next: Arrow[Any, Any, Any]): Arrow[Any, Any, Any] =
            if i < from then next
            else link(i - 1, new Offset(elems, i, next))
        if from >= elems.length then empty
        else link(elems.length - 1, empty)
    end tail

    private val optimizeBuffer = new ThreadLocal[java.util.ArrayDeque[Any]]:
        override def initialValue = new java.util.ArrayDeque[Any]

    private def isEmpty[X, Y, Z](f: Arrow[X, Y, Z]): Boolean =
        (f: Any) match
            case arr: Array[Any] @unchecked => arr.length == 0
            case _                          => false

end Arrow

final private[kyo] class KyoException extends Exception(null, null, false, false):
    var frames: Chunk[(String, Frame)] = Chunk.empty
    var installed: Int                 = 0
    override def getMessage =
        frames.map((op, f) => "at " + KyoException.describe(op, f) + "(" + f.position.show + ")")
            .mkString("effect trace: ", "; ", "")
end KyoException

private[kyo] object KyoException:

    private inline def MaxFrames = 64

    def describe(op: String, f: Frame): String =
        val cls    = f.className.split('.').last.stripSuffix("$")
        val caller = if f.callerName == "$anonfun" then "<lambda>" else f.callerName
        op + " @ " + cls + "." + caller
    end describe

    def attach(ex: Throwable, op: String, frame: Frame): Unit =
        ex.getSuppressed.collectFirst { case o: KyoException => o } match
            case Some(o) =>
                o.frames = o.frames.append((op, frame))
                if o.frames.size > MaxFrames then
                    val dropped = o.frames.size - MaxFrames
                    o.frames = o.frames.dropLeft(dropped)
                    o.installed = Integer.max(0, o.installed - dropped)
                end if
            case None =>
                val o = new KyoException
                o.frames = Chunk((op, frame))
                ex.addSuppressed(o)

    def install(ex: Throwable): Unit =
        ex.getSuppressed.collectFirst { case o: KyoException => o } match
            case Some(o) if o.frames.size > o.installed =>
                val pending = o.frames.dropLeft(o.installed)
                val collapsed = pending.foldLeft(Chunk.empty[(String, Frame)]) { (acc, f) =>
                    if acc.nonEmpty && acc.last == f then acc else acc.append(f)
                }
                val fresh = collapsed.map { (op, f) =>
                    val cls    = f.className.split('.').last.stripSuffix("$")
                    val caller = if f.callerName == "$anonfun" then "<lambda>" else f.callerName
                    StackTraceElement(op + " @ " + cls, caller, f.position.fileName, f.position.lineNumber)
                }
                val user = ex.getStackTrace.filterNot(e => e.getClassName.startsWith("kyo.proto2"))
                ex.setStackTrace((fresh.toArray ++ user))
                o.installed = o.frames.size
            case _ =>
                ()
end KyoException
