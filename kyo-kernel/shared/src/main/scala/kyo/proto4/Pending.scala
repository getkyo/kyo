package kyo.proto4

import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import language.implicitConversions
import scala.annotation.nowarn
import scala.annotation.static
import scala.annotation.tailrec

opaque type <[+A, -S] = A | Kyo[A, S] | Kyo.Nested[A]

object `<`:

    // Inline with compile-time elision: a value type is provably not a computation
    // (Kyo, Nested, and the opaque < all erase to references bounded by Any), so
    // those sites lift by identity with no call and no tests. Everything else
    // keeps the runtime check in liftSlow.
    implicit inline def lift[A](v: A): A < Any =
        scala.compiletime.summonFrom {
            case _: (A <:< AnyVal) => v.asInstanceOf[A < Any]
            case _: (A <:< String) => v.asInstanceOf[A < Any]
            case _                 => liftSlow(v)
        }

    def liftSlow[A](v: A): A < Any =
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
                        case o: Arrow.Offset[Any, Any, Any, Any] @unchecked if !w.isInstanceOf[Kyo[?, ?]] =>
                            o.head.run(Kyo.unwrap(w), o.next).asInstanceOf[C < (S2 & S3)]
                        case _ =>
                            cont(w)
                    end match
                end run
            arrow(self)
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
            @tailrec def loop(o: Arrow.Offset[Any, Any, Any, Any], cur: Any): Any =
                o.head match
                    case jump: Arrow.Offset[Any, Any, Any, Any] @unchecked if Arrow.isEmpty(o.next) =>
                        loop(jump, cur)
                    case t =>
                        observer(t.frame, cur)
                        val w = t.run(cur, Arrow[Any])
                        if w.isInstanceOf[Kyo[?, ?]] then
                            val rest =
                                if Arrow.isEmpty(o.next) then Arrow.of[Any, Any, Any](this)
                                else Arrow.map(Arrow.of[Any, Any, Any](this))(o.next)
                            w.asInstanceOf[Kyo[Any, Any]].map(rest)
                        else
                            (o.next: Any) match
                                case n: Arrow.Offset[Any, Any, Any, Any] @unchecked => loop(n, Kyo.unwrap(w))
                                case _                                              => Kyo.unwrap(w)
                        end if
            (cont: Any) match
                case o: Arrow.Offset[Any, Any, Any, Any] @unchecked =>
                    loop(o, v).asInstanceOf[C < (Any & S2)]
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

    inline def evalPartial[I[_], O[_], E <: Effect[I, O], A](
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

    inline def eval[I[_], O[_], E <: Effect[I, O], A](
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
            case o: Arrow.Offset[Any, Any, Any, Any] @unchecked =>
                discardChain(o)
            case at: Arrow.AndThen[?, ?, ?, ?] =>
                discardArrow(at.a) ++ discardArrow(at.b)
            case _ =>
                Nil

    private def discardChain(o: Arrow.Offset[Any, Any, Any, Any]): List[Throwable] =
        @tailrec def loop(cur: Any, errors: List[Throwable]): List[Throwable] =
            cur match
                case o: Arrow.Offset[Any, Any, Any, Any] @unchecked => loop(o.next, errors ++ discardArrow(o.head))
                case _                                              => errors
        loop(o, Nil)
    end discardChain

    private def yieldValue[A](v: A): Arrow[Unit, A, Any] =
        Arrow.of(
            new Arrow.Transform[Unit, A, Any]:
                def frame = Frame.internal
                def run[C, S2](x: Any, cont: Arrow[A, C, S2]): C < (Any & S2) =
                    cont(v.asInstanceOf[A < Any])
        )

    // public because the inline trampoline's bracket arm expands at user sites
    final class Finalize[R, A, S](val bracket: Kyo.Bracket[R, ?, S], val value: R)
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

    // Inline so every eval site gets a private copy of the trampoline: the
    // handle(kyo) dispatch and the suspension-shape tests then profile per
    // handler instead of pooling across every eval in the program.
    private inline def evalLoop(
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
                                case Maybe.Present(next) =>
                                    if n == 0 then
                                        if preempt() then next
                                        else loop(next, stride - 1)
                                    else loop(next, n - 1)
                                case _ => kyo
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
