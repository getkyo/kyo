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

        def discard: Unit =
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

    extension [A, S](self: A < S)

        /** Drives the computation as far as it can go.
          *
          * Runs until a value is produced, an unhandled suspension is reached (the computation parks, waiting for a handler), or `preempt`
          * returns true at a poll point. The result is the remaining computation: a plain value when done, or a pending computation to be
          * handled or resumed later.
          */
        def drive(preempt: () => Boolean = never, period: Int = Arrow.Period): A < S =
            driveLoop(self.asInstanceOf[Any < Any], preempt, Integer.max(1, period / Arrow.Period)).asInstanceOf[A < S]
    end extension

    extension [A](self: A < Any)

        /** Evaluates the computation to its value.
          *
          * All effects must have handlers installed; reaching a suspension with no matching delimiter is a defect and throws.
          */
        def eval: A =
            if self.isInstanceOf[Kyo[?, ?]] then
                driveLoop(self.asInstanceOf[Any < Any], never, 1) match
                    case kyo: Kyo[?, ?] => throw new IllegalStateException("unhandled suspension: " + kyo)
                    case v              => Kyo.unwrap(v).asInstanceOf[A]
            else Kyo.unwrap(self).asInstanceOf[A]

    end extension

    private val never: () => Boolean = () => false

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
        val lifted = liftSlow(v)
        Arrow.of(
            new Arrow.Transform[Unit, A, Any]:
                def frame = Frame.internal
                def run[C, S2](x: Any, cont: Arrow[A, C, S2]): C < (Any & S2) =
                    cont(lifted.asInstanceOf[A < Any])
        )
    end yieldValue

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

    private def driveLoop(
        v0: Any < Any,
        preempt: () => Boolean,
        stride: Int
    ): Any < Any =
        def recur(v: Any < Any, depth: Int): Any < Any =
            @tailrec def loop(curr: Any < Any, n: Int): Any < Any =
                curr match
                    case bracket: Kyo.Bracket[Any, Any, Any] @unchecked =>
                        if depth >= BracketDepth then bracket
                        else
                            recur(bracket.acquire, depth + 1) match
                                case suspended: Kyo[Any, Any] @unchecked =>
                                    val wrapped = suspended.map(reacquire(bracket))
                                    if depth == 0 && !preempted(wrapped) then loop(wrapped, n) else wrapped
                                case acquired =>
                                    val resource = Kyo.unwrap(acquired)
                                    val result =
                                        try recur(bracket.cont(acquired), depth + 1)
                                        catch
                                            case t: Throwable =>
                                                cleanup(bracket, resource, t)
                                                throw t
                                    result match
                                        case suspended: Kyo[Any, Any] @unchecked =>
                                            val wrapped = suspended.map(new Finalize[Any, Any, Any](bracket, resource))
                                            if depth == 0 && !preempted(wrapped) then loop(wrapped, n) else wrapped
                                        case _ =>
                                            recur(bracket.release(resource), depth + 1) match
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
                    case c: Kyo.Continue[?, ?, ?, ?, ?, ?] @unchecked if depth == 0 =>
                        dispatch(c) match
                            case Maybe.Present(next) =>
                                if n == 0 then
                                    if preempt() then next
                                    else loop(next, stride - 1)
                                else loop(next, n - 1)
                            case _ => curr
                    case kyo: Kyo[Any, Any] @unchecked =>
                        curr
                    case _ =>
                        curr
            loop(v, 0)
        end recur
        try recur(v0, 0)
        catch
            case ex: Throwable =>
                KyoException.install(ex)
                throw ex
        end try
    end driveLoop

    /** Finds the innermost matching delimiter in the suspension's chain and applies its format.
      *
      * The walk flattens nested pre-linked chains with an explicit pending stack, collecting the prefix (the continuation up to the
      * delimiter) only because the capturing formats need it. The casts below are justified by the tag match: a delimiter constructed for
      * `E` matched a suspension of `E`, so the clause's erased input and continuation have the types the public API established.
      */
    private def dispatch(c: Kyo.Continue[?, ?, ?, ?, ?, ?]): Maybe[Any < Any] =
        val suspend    = c.suspend
        val suspendTag = suspend.tag.asInstanceOf[Tag[Any]]
        val input      = suspend.input
        val chain      = c.cont.asInstanceOf[Arrow[Any, Any, Any]].optimize

        def compose(rest: Arrow[Any, Any, Any], pending: List[Arrow[Any, Any, Any]]): Arrow[Any, Any, Any] =
            pending.foldLeft(rest)((acc, next) => Arrow.map(acc)(next))

        def prefixArrow(prefixRev: List[Arrow.Transform[Any, Any, Any]]): Arrow[Any, Any, Any] =
            prefixRev.foldLeft(Arrow[Any])((acc, t) => new Arrow.Offset[Any, Any, Any, Any](t, acc))

        def act(
            h: Handler,
            rest: Arrow[Any, Any, Any],
            pending: List[Arrow[Any, Any, Any]],
            prefixRev: List[Arrow.Transform[Any, Any, Any]]
        ): Maybe[Any < Any] =
            val fullRest = compose(rest, pending)
            h match
                case h: Handler.Resume =>
                    Maybe(chain(h.clause[Any](input)))
                case h: Handler.Stop =>
                    Maybe(new Arrow.Offset[Any, Any, Any, Any](h, fullRest)(h.clause[Any](input)))
                case h: Handler.Cont =>
                    val k = prefixArrow(prefixRev)
                    Maybe(new Arrow.Offset[Any, Any, Any, Any](h, fullRest)(h.clause[Any](input, k)))
                case h: Handler.First =>
                    val k = prefixArrow(prefixRev)
                    Maybe(fullRest(h.clause[Any](input, k)))
                case h: Handler.Loop =>
                    val k = prefixArrow(prefixRev)
                    Maybe(Arrow.map(outcomeStep(h))(fullRest)(h.clause[Any](h.state, input, k)))
            end match
        end act

        @tailrec def search(
            cur: Arrow[Any, Any, Any],
            pending: List[Arrow[Any, Any, Any]],
            prefixRev: List[Arrow.Transform[Any, Any, Any]]
        ): Maybe[Any < Any] =
            cur match
                case o: Arrow.Offset[Any, Any, Any, Any] @unchecked =>
                    o.head match
                        case h: Handler if h.effectTag <:< suspendTag =>
                            act(h, o.next, pending, prefixRev)
                        case inner: Arrow.Offset[Any, Any, Any, Any] @unchecked =>
                            search(inner, o.next :: pending, prefixRev)
                        case t =>
                            search(o.next, pending, t :: prefixRev)
                case at: Arrow.AndThen[?, ?, ?, ?] =>
                    search(at.asInstanceOf[Arrow[Any, Any, Any]].optimize, pending, prefixRev)
                case h: Handler if h.effectTag <:< suspendTag =>
                    act(h, Arrow[Any], pending, prefixRev)
                case t: Arrow.Transform[?, ?, ?] =>
                    val prefixRev2 =
                        if Arrow.isEmpty(t.asInstanceOf[Arrow[Any, Any, Any]]) then prefixRev
                        else t.asInstanceOf[Arrow.Transform[Any, Any, Any]] :: prefixRev
                    pending match
                        case p :: ps => search(p, ps, prefixRev2)
                        case Nil     => Maybe.Absent
            end match
        end search

        search(chain, Nil, Nil)
    end dispatch

    /** Interprets a loop clause's Outcome once it materializes.
      *
      * This transform sits in the chain before the suffix past the delimiter, so effects raised while the outcome itself is computed
      * dispatch to outer handlers, never to this loop. Continue re-applies a replacement delimiter carrying the next state to the next
      * computation: if it suspends, the delimiter travels in its chain (deep with fresh state); if it is already a value, the delimiter's
      * run applies done with that state. Done leaves the region: the result flows to the suffix and done is not applied, the clause
      * already produced the final value.
      */
    private def outcomeStep(h: Handler.Loop): Arrow[Any, Any, Any] =
        Arrow.of(
            new Arrow.Transform[Any, Any, Any]:
                def frame = h.frame
                def run[C, S2](v: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
                    v match
                        case ArrowEffect.Outcome.Continue(s2, next) =>
                            val h2 = new Handler.Loop(h.effectTag, s2, h.clause, h.done, h.frame)
                            cont(h2.asInstanceOf[Arrow[Any, Any, Any]](next.asInstanceOf[Any < Any]))
                        case ArrowEffect.Outcome.Done(b) =>
                            cont(`<`.liftSlow(b))
        )
    end outcomeStep

end `<`
