package kyo.proto

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
    private[kyo] def prepend(f: Arrow[Any, Any, Any]): A < S

object Kyo:

    class Nested[+A](val value: A):
        override def toString = "Nested"

    abstract class Suspend[I[_], O[_], E <: Effect[I, O], A] extends Kyo[O[A], E]:

        def input: I[A]
        def tag: Tag[E]
        def frame: Frame

        final def map[B, S](f: Arrow[O[A], B, S]): B < (E & S) =
            Continue[I, O, E, A, B, S](this, f)

        final private[kyo] def prepend(f: Arrow[Any, Any, Any]): O[A] < E =
            map(f.asInstanceOf[Arrow[O[A], O[A], Any]])

        override def toString = "Suspend(" + tag.show + ", " + frame.position.show + ")"

    end Suspend

    case class Continue[I[_], O[_], E <: Effect[I, O], A, +B, -S](
        suspend: Suspend[I, O, E, A],
        cont: Arrow[O[A], B, S]
    ) extends Kyo[B, E & S]:

        def map[C, S2](f: Arrow[B, C, S2]): C < (E & S & S2) =
            Continue(suspend, cont.map(f))

        private[kyo] def prepend(f: Arrow[Any, Any, Any]): B < (E & S) =
            Continue(suspend, Arrow.AndThen(f, cont.asInstanceOf[Arrow[Any, Any, Any]]).asInstanceOf[Arrow[O[A], B, S]])

        override def toString = "Continue(" + suspend + ")"

    end Continue

    final private[kyo] case class Defer[A, +B, -S](value: A, cont: Arrow[A, B, S]) extends Kyo[B, S]:

        def map[C, S2](f: Arrow[B, C, S2]): C < (S & S2) =
            Defer(value, cont.map(f))

        private[kyo] def prepend(f: Arrow[Any, Any, Any]): B < S =
            Defer(value, Arrow.AndThen(f, cont.asInstanceOf[Arrow[Any, Any, Any]]).asInstanceOf[Arrow[A, B, S]])

        override def toString = "Defer"

    end Defer

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

        private[kyo] def prepend(f: Arrow[Any, Any, Any]): A < S =
            val outer = this
            new Bracket[R, A, S]:
                def acquire =
                    outer.acquire match
                        case kyo: Kyo[R, S] @unchecked => kyo.prepend(f)
                        case v                         => v
                def release(r: R) = outer.release(r)
                def cont          = Arrow.AndThen(f, outer.cont.asInstanceOf[Arrow[Any, Any, Any]]).asInstanceOf[Arrow[R, A, S]]
                def frame         = outer.frame
            end new
        end prepend

        override def toString = "Bracket(" + frame.position.show + ")"

    end Bracket

end Kyo

opaque type <[+A, -S] = A | Kyo[A, S] | Kyo.Nested[A]

object `<`:

    import Arrow.AndThen
    import Arrow.SpanRest
    import Arrow.Transform
    import Kyo.Bracket
    import Kyo.Continue
    import Kyo.Defer
    import Kyo.Nested
    import Kyo.Suspend

    implicit def lift[A](v: A): A < Any =
        v match
            case _: Kyo[?, ?] | _: Kyo.Nested[?] => Kyo.Nested(v).asInstanceOf[A < Any]
            case _                               => v

    implicit private[kyo] inline def fromKyo[A, S](v: Kyo[A, S]): A < S = v

    extension [A, S](self: A < S)

        def unsafeGet: A =
            self match
                case self: Nested[?] => self.value.asInstanceOf[A]
                case _               => self.asInstanceOf[A]

        private[kyo] def discard: Unit =
            discardValue(self) match
                case Nil => ()
                case t :: rest =>
                    rest.foreach(t.addSuppressed)
                    throw t

        @nowarn
        inline def map[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            val arrow = new Transform[A, B, S2]:
                def frame = _frame
                def run[C, S3](v: A, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                    val w = f(v)
                    if w.isInstanceOf[Kyo[?, ?]] then cont(w)
                    else
                        cont match
                            case at: AndThen[B, Any, C, S3] @unchecked =>
                                at.a match
                                    case t: Transform[B, Any, S3] @unchecked => t.run(w.unsafeGet, at.b)
                                    case _                                   => cont(w)
                            case sr: SpanRest =>
                                sr.head.run(w.unsafeGet, sr.next).asInstanceOf[C < (S2 & S3)]
                            case _ =>
                                cont(w)
                    end if
                end run
            arrow(self)
        end map

    end extension

    extension [A](self: A < Any)

        def eval: A =
            evalLoop(self, never, 1, unhandled).unsafeGet.asInstanceOf[A]

        def eval(preempt: () => Boolean, period: Int = Arrow.Period): A < Any =
            evalLoop(self, preempt, Integer.max(1, period / Arrow.Period), unhandled).asInstanceOf[A < Any]

    end extension

    def observe[A, S](observer: (Frame, Any) => Unit)(v: A < S): A < S =
        v match
            case kyo: Kyo[A, S] @unchecked =>
                kyo.prepend((new Observe(observer)).asInstanceOf[Arrow[Any, Any, Any]])
            case _ =>
                v
    end observe

    final private class Observe(observer: (Frame, Any) => Unit) extends Transform[Any, Any, Any]:
        def frame = Frame.internal
        def run[C, S2](v: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            def step(t: Transform[Any, Any, Any], rest: Arrow[Any, C, S2]): C < (Any & S2) =
                observer(t.frame, v)
                t.run(v, rest)
            @tailrec def peel(chain: Arrow[Any, C, S2]): C < (Any & S2) =
                chain match
                    case sr: SpanRest =>
                        step(sr.head, AndThen(this, sr.next).asInstanceOf[Arrow[Any, C, S2]])
                    case t: Transform[Any, Any, Any] @unchecked =>
                        if t.asInstanceOf[AnyRef] eq Arrow.identity then chain(v)
                        else step(t, this.asInstanceOf[Arrow[Any, C, S2]])
                    case at: AndThen[Any, Any, Any, Any] @unchecked =>
                        at.a match
                            case sr: SpanRest =>
                                step(sr.head, AndThen(this, sr.next.map(at.b)).asInstanceOf[Arrow[Any, C, S2]])
                            case t: Transform[Any, Any, Any] @unchecked =>
                                step(t, AndThen(this, at.b).asInstanceOf[Arrow[Any, C, S2]])
                            case inner: AndThen[Any, Any, Any, Any] @unchecked =>
                                peel(AndThen(inner.a, AndThen(inner.b, at.b)).asInstanceOf[Arrow[Any, C, S2]])
            peel(cont)
        end run
    end Observe

    def eval[I[_], O[_], E <: Effect[I, O], A](
        tag: Tag[E],
        v: A < E,
        preempt: () => Boolean,
        period: Int
    )(
        handle: [X] => (I[X], Arrow[O[X], A, E]) => Maybe[A < E]
    ): A < E =
        val handler: Kyo[Any, Any] => Maybe[Any < Any] =
            case c: Continue[I, O, E, Any, A, E] @unchecked =>
                handle(c.suspend.input, Arrow.flatten(c.cont)).asInstanceOf[Maybe[Any < Any]]
            case s: Suspend[I, O, E, Any] @unchecked =>
                handle(s.input, Arrow[A].asInstanceOf[Arrow[O[Any], A, E]]).asInstanceOf[Maybe[Any < Any]]
            case _ =>
                throw new IllegalStateException("unhandled suspension")
        end handler
        evalLoop(v.asInstanceOf[Any < Any], preempt, Integer.max(1, period / Arrow.Period), handler).asInstanceOf[A < E]
    end eval

    private val never: () => Boolean = () => false

    private val unhandled: Kyo[Any, Any] => Maybe[Any < Any] =
        _ => throw new IllegalStateException("unhandled suspension")

    private inline def BracketDepth = 512

    private def discardValue[A, S](v: A < S): List[Throwable] =
        v match
            case kyo: Continue[?, ?, ?, ?, ?, ?] => discardArrow(kyo.cont)
            case kyo: Defer[?, ?, ?]             => discardArrow(kyo.cont)
            case _                               => Nil

    private def discardArrow(arrow: Arrow[?, ?, ?]): List[Throwable] =
        arrow match
            case at: AndThen[?, ?, ?, ?] =>
                discardArrow(at.a) ++ discardArrow(at.b)
            case sr: SpanRest =>
                var errors = List.empty[Throwable]
                var i      = sr.from
                while i < sr.span.size do
                    errors = errors ++ discardArrow(sr.span(i).asInstanceOf[Arrow[?, ?, ?]])
                    i += 1
                errors
            case finalize: Finalize[?, ?, ?] =>
                try
                    val _ = finalize.bracket.release(finalize.value).asInstanceOf[Unit < Any].eval
                    Nil
                catch
                    case t: Throwable =>
                        KyoException.attach(t, "release", finalize.bracket.frame)
                        t :: Nil
            case _ =>
                Nil

    private def yieldValue[A](v: A): Arrow[Unit, A, Any] =
        new Transform[Unit, A, Any]:
            def frame = Frame.internal
            def run[C, S2](x: Unit, cont: Arrow[A, C, S2]): C < (Any & S2) =
                cont(v)

    final private[kyo] class Finalize[R, A, S](val bracket: Bracket[R, ?, S], val value: R) extends Transform[A, A, S]:
        def frame = bracket.frame
        def run[C, S2](v: A, cont: Arrow[A, C, S2]): C < (S & S2) =
            cont(yieldValue(v)(bracket.release(value)))
    end Finalize

    private def constant(v: Any < Any): Arrow[Any, Any, Any] =
        new Transform[Any, Any, Any]:
            def frame = Frame.internal
            def run[C, S2](x: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
                cont(v)

    private def reacquire(bracket: Bracket[Any, Any, Any]): Arrow[Any, Any, Any] =
        new Transform[Any, Any, Any]:
            def frame = bracket.frame
            def run[C, S2](r: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
                cont(
                    new Bracket[Any, Any, Any]:
                        def acquire         = r
                        def release(x: Any) = bracket.release(x)
                        def cont            = bracket.cont
                        def frame           = bracket.frame
                )

    private def cleanup(bracket: Bracket[Any, Any, Any], resource: Any, t: Throwable): Unit =
        try
            val _ = bracket.release(resource).eval
        catch
            case t2: Throwable =>
                KyoException.attach(t2, "release", bracket.frame)
                t.addSuppressed(t2)

    private def preempted(v: Any < Any): Boolean =
        v.isInstanceOf[Defer[?, ?, ?]]

    private def evalLoop(
        v0: Any < Any,
        preempt: () => Boolean,
        stride: Int,
        handle: Kyo[Any, Any] => Maybe[Any < Any]
    ): Any < Any =
        if !v0.isInstanceOf[Kyo[?, ?]] then v0
        else evalLoopSlow(v0, preempt, stride, handle)

    private def evalLoopSlow(
        v0: Any < Any,
        preempt: () => Boolean,
        stride: Int,
        handle: Kyo[Any, Any] => Maybe[Any < Any]
    ): Any < Any =
        def drive(v: Any < Any, depth: Int): Any < Any =
            @tailrec def loop(curr: Any < Any, n: Int): Any < Any =
                curr match
                    case bracket: Bracket[Any, Any, Any] @unchecked =>
                        if depth >= BracketDepth then bracket
                        else
                            drive(bracket.acquire, depth + 1) match
                                case suspended: Kyo[Any, Any] @unchecked =>
                                    val wrapped = suspended.map(reacquire(bracket))
                                    if depth == 0 && !preempted(wrapped) then loop(wrapped, n)
                                    else wrapped
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
                                            if depth == 0 && !preempted(wrapped) then loop(wrapped, n)
                                            else wrapped
                                        case _ =>
                                            drive(bracket.release(resource), depth + 1) match
                                                case suspended: Kyo[Any, Any] @unchecked =>
                                                    val wrapped = suspended.map(constant(result))
                                                    if depth == 0 && !preempted(wrapped) then loop(wrapped, n)
                                                    else wrapped
                                                case _ =>
                                                    result
                                    end match
                    case defer: Defer[Any, Any, Any] @unchecked =>
                        if n == 0 then
                            if preempt() then curr
                            else loop(defer.cont(defer.value, Arrow[Any]), stride - 1)
                        else loop(defer.cont(defer.value, Arrow[Any]), n - 1)
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
    end evalLoopSlow

end `<`

sealed abstract class Arrow[-A, +B, -S]:

    import Arrow.AndThen
    import Arrow.Transform

    final def apply[S2](v: A < S2): B < (S & S2) =
        if this eq Arrow.identity then
            v.asInstanceOf[B < (S & S2)]
        else
            v match
                case kyo: Kyo[A, S2] @unchecked =>
                    kyo.map(this)
                case _ =>
                    this match
                        case t: Transform[A, B, S] @unchecked =>
                            if Arrow.probe() then Kyo.Defer(v.unsafeGet, this)
                            else
                                try t.run(v.unsafeGet, Arrow[B])
                                catch
                                    case ex: Throwable =>
                                        KyoException.attach(ex, "map", t.frame)
                                        throw ex
                        case at: AndThen[A, Any, B, S] @unchecked =>
                            at.a match
                                case t: Transform[A, Any, S] @unchecked =>
                                    if Arrow.probe() then Kyo.Defer(v.unsafeGet, this)
                                    else
                                        try t.run(v.unsafeGet, at.b)
                                        catch
                                            case ex: Throwable =>
                                                KyoException.attach(ex, "map", t.frame)
                                                throw ex
                                case _ =>
                                    at.a(v.unsafeGet, at.b)

    final def apply[C, S2](v: A, cont: Arrow[B, C, S2]): C < (S & S2) =
        if Arrow.probe() then
            Kyo.Defer(v, this.map(cont))
        else
            this match
                case t: Transform[A, B, S] @unchecked =>
                    try t.run(v, cont)
                    catch
                        case ex: Throwable =>
                            KyoException.attach(ex, "map", t.frame)
                            throw ex
                case _ =>
                    reassoc(v, cont)

    final def reassoc[C, S2](v: A, cont: Arrow[B, C, S2]): C < (S & S2) =
        this match
            case at: AndThen[A, Any, B, S] @unchecked =>
                at.a(v, at.b.map(cont))
            case _ =>
                this(v, cont)

    final def map[C, S2](f: Arrow[B, C, S2]): Arrow[A, C, (S & S2)] =
        if this eq Arrow.identity then
            f.asInstanceOf[Arrow[A, C, (S & S2)]]
        else if f eq Arrow.identity then
            this.asInstanceOf[Arrow[A, C, (S & S2)]]
        else
            AndThen(this, f)

end Arrow

object Arrow:

    private[kyo] inline def Period = 512
    private inline def Slots       = 1024

    private val counters = new Array[Long](Slots << 3)

    private def probe(): Boolean =
        false

    abstract class Transform[-A, +B, -S] extends Arrow[A, B, S]:
        def frame: Frame
        def run[C, S2](v: A, cont: Arrow[B, C, S2]): C < (S & S2)
        override def toString = "Transform(" + frame.position.show + ")"
    end Transform

    final case class AndThen[-A, B, +C, -S](a: Arrow[A, B, S], b: Arrow[B, C, S]) extends Arrow[A, C, S]:
        override def toString = "AndThen"

    val identity = new Transform[Any, Any, Any]:
        def frame = Frame.internal
        def run[C, S2](v: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            cont(v)

    def apply[A]: Arrow[A, A, Any] = identity.asInstanceOf[Arrow[A, A, Any]]

    final class SpanRest(val span: Span[Any], val from: Int) extends Transform[Any, Any, Any]:
        def frame = Frame.internal

        def head: Transform[Any, Any, Any] =
            span(from).asInstanceOf[Transform[Any, Any, Any]]

        def next: Arrow[Any, Any, Any] =
            if from + 1 < span.size then new SpanRest(span, from + 1) else identity

        def run[C, S2](v: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            if probe() then
                Kyo.Defer(v, this.map(cont))
            else
                val rest = next.map(cont.asInstanceOf[Arrow[Any, C, Any]])
                try head.run(v, rest).asInstanceOf[C < (Any & S2)]
                catch
                    case ex: Throwable =>
                        KyoException.attach(ex, "map", head.frame)
                        throw ex
                end try
    end SpanRest

    private val flattenStack = new ThreadLocal[java.util.ArrayDeque[Any]]:
        override def initialValue = new java.util.ArrayDeque[Any]

    private val flattenOut = new ThreadLocal[java.util.ArrayDeque[Any]]:
        override def initialValue = new java.util.ArrayDeque[Any]

    private inline def SpanThreshold = 32

    private[kyo] def flatten[A, B, S](arrow: Arrow[A, B, S]): Arrow[A, B, S] =
        arrow match
            case at: AndThen[A, Any, B, S] @unchecked =>
                var node: Arrow[?, ?, ?] = at.a
                var depth                = 1
                while depth < SpanThreshold && node.isInstanceOf[AndThen[?, ?, ?, ?]] do
                    node = node.asInstanceOf[AndThen[?, ?, ?, ?]].a
                    depth += 1
                if depth < SpanThreshold then associateRight(at)
                else spanify(at)
            case _ =>
                arrow

    private def associateRight[A, B, S](at: AndThen[A, ?, B, S]): Arrow[A, B, S] =
        @tailrec def loop(a: Arrow[A, Any, S], rest: Arrow[Any, B, S]): Arrow[A, B, S] =
            a match
                case inner: AndThen[A, Any, Any, S] @unchecked =>
                    loop(inner.a, AndThen(inner.b, rest))
                case _ =>
                    AndThen(a, rest)
        loop(at.a.asInstanceOf[Arrow[A, Any, S]], at.b.asInstanceOf[Arrow[Any, B, S]])
    end associateRight

    private def spanify[A, B, S](at: AndThen[A, ?, B, S]): Arrow[A, B, S] =
        val stack = flattenStack.get()
        val out   = flattenOut.get()
        stack.clear()
        out.clear()
        stack.push(at)
        while !stack.isEmpty do
            stack.pop() match
                case inner: AndThen[Any, Any, Any, Any] @unchecked =>
                    stack.push(inner.b)
                    stack.push(inner.a)
                case t =>
                    if t.asInstanceOf[AnyRef] ne identity then
                        val _ = out.add(t)
        end while
        val result =
            out.size match
                case 0 => identity
                case 1 => out.peek()
                case _ => new SpanRest(Span.fromUnsafe(out.toArray.asInstanceOf[Array[Any]]), 0)
        out.clear()
        result.asInstanceOf[Arrow[A, B, S]]
    end spanify

end Arrow

final private[kyo] class KyoException extends Exception(null, null, false, false):
    var frames: List[(String, Frame)] = Nil
    var size: Int                     = 0
    var installed: Int                = 0
    override def getMessage =
        frames.reverse.map((op, f) => "at " + KyoException.describe(op, f) + "(" + f.position.show + ")")
            .mkString("effect trace: ", "; ", "")
end KyoException

private[kyo] object KyoException:

    def describe(op: String, f: Frame): String =
        val cls    = f.className.split('.').last.stripSuffix("$")
        val caller = if f.callerName == "$anonfun" then "<lambda>" else f.callerName
        op + " @ " + cls + "." + caller
    end describe

    def attach(ex: Throwable, op: String, frame: Frame): Unit =
        ex.getSuppressed.collectFirst { case o: KyoException => o } match
            case Some(o) =>
                o.frames = (op, frame) :: o.frames
                o.size += 1
            case None =>
                val o = new KyoException
                o.frames = (op, frame) :: Nil
                o.size = 1
                ex.addSuppressed(o)

    def install(ex: Throwable): Unit =
        ex.getSuppressed.collectFirst { case o: KyoException => o } match
            case Some(o) if o.size > o.installed =>
                val collapsed = o.frames.take(o.size - o.installed).reverse.foldRight(List.empty[(String, Frame)]) {
                    case (f, head :: tail) if f == head => head :: tail
                    case (f, acc)                       => f :: acc
                }
                val fresh = collapsed.map { (op, f) =>
                    val cls    = f.className.split('.').last.stripSuffix("$")
                    val caller = if f.callerName == "$anonfun" then "<lambda>" else f.callerName
                    StackTraceElement(op + " @ " + cls, caller, f.position.fileName, f.position.lineNumber)
                }
                val user = ex.getStackTrace.filterNot(e => e.getClassName.startsWith("kyo.proto"))
                ex.setStackTrace((fresh ++ user).toArray)
                o.installed = o.size
            case _ =>
                ()
end KyoException
