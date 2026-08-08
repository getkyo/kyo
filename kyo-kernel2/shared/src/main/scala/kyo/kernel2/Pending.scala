package kyo.kernel2

import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.kernel2.internal.Context
import kyo.kernel2.internal.EffectTrace
import kyo.kernel2.internal.Handler
import kyo.kernel2.internal.Safepoint
import language.implicitConversions
import scala.annotation.nowarn
import scala.annotation.static
import scala.annotation.tailrec

opaque type <[+A, -S] = A | Kyo[A, S] | Kyo.Nested[A]

object `<` extends Implicits:

    implicit private[kyo] inline def fromKyo[A, S](v: Kyo[A, S]): A < S = v

    extension [A, S](self: A < S)

        // runtime machinery, not user surface: runs the finalizers a parked computation's
        // brackets carry. The scheduler calls it when dropping a continuation that will
        // never be resumed.
        private[kyo] def finalizeBracket: Unit =
            val errors = finalizeValue(self)
            errors.headMaybe match
                case Maybe.Present(t) =>
                    errors.dropLeft(1).foreach(t.addSuppressed)
                    throw t
                case Maybe.Absent => ()
            end match
        end finalizeBracket
    end extension

    extension [A, S](inline self: A < S)
        @nowarn
        inline def map[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            val arrow = new Arrow.Transform[A, B, S2]:
                def frame = _frame
                def run[C, S3](v: Any, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                    val w = f(v.asInstanceOf[A])
                    cont match
                        case o: Arrow.Offset[Any, Any, Any, Any] @unchecked if !w.isInstanceOf[Kyo[?, ?]] =>
                            o.head.run(Kyo.unnest(w), o.next).asInstanceOf[C < (S2 & S3)]
                        case _ =>
                            cont(w)
                    end match
                end run
            arrow(self)
        end map

        /** Maps the value produced by this computation to a new computation and flattens the result.
          *
          * This method exists to support for-comprehension syntax in Scala. It is identical to `map` and `map` should be preferred when not
          * using for-comprehensions.
          */
        @nowarn
        inline def flatMap[B, S2](inline f: A => B < S2)(using inline _frame: Frame): B < (S & S2) =
            val arrow = new Arrow.Transform[A, B, S2]:
                def frame = _frame
                def run[C, S3](v: Any, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                    val w = f(v.asInstanceOf[A])
                    cont match
                        case o: Arrow.Offset[Any, Any, Any, Any] @unchecked if !w.isInstanceOf[Kyo[?, ?]] =>
                            o.head.run(Kyo.unnest(w), o.next).asInstanceOf[C < (S2 & S3)]
                        case _ =>
                            cont(w)
                    end match
                end run
            arrow(self)
        end flatMap

        /** Executes this computation, discards its result, and then executes another computation. */
        @nowarn
        inline def andThen[B, S2](inline f: => B < S2)(using inline _frame: Frame): B < (S & S2) =
            val arrow = new Arrow.Transform[A, B, S2]:
                def frame = _frame
                def run[C, S3](v: Any, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                    val w = f
                    cont match
                        case o: Arrow.Offset[Any, Any, Any, Any] @unchecked if !w.isInstanceOf[Kyo[?, ?]] =>
                            o.head.run(Kyo.unnest(w), o.next).asInstanceOf[C < (S2 & S3)]
                        case _ =>
                            cont(w)
                    end match
                end run
            arrow(self)
        end andThen

        /** Executes this computation and discards its result. */
        @nowarn
        inline def unit(using inline _frame: Frame): Unit < S =
            val arrow = new Arrow.Transform[A, Unit, Any]:
                def frame = _frame
                def run[C, S3](v: Any, cont: Arrow[Unit, C, S3]): C < (Any & S3) =
                    cont match
                        case o: Arrow.Offset[Any, Any, Any, Any] @unchecked =>
                            o.head.run((), o.next).asInstanceOf[C < (Any & S3)]
                        case _ =>
                            cont(())
                    end match
                end run
            arrow(self)
        end unit

        /** Applies a transformation to this computation.
          *
          * Passes the computation to the transformation function, enabling a fluent style for effect handling:
          * `computation.handle(Abort.run, Env.run(1))` instead of `Env.run(1)(Abort.run(computation))`. The multi-parameter versions chain
          * transformations in sequence.
          */
        inline def handle[B](inline f: (=> A < S) => B): B =
            def handle1 = self
            f(handle1)
        end handle

        /** Applies two transformations to this computation in sequence. */
        inline def handle[B, C](
            inline f1: A < S => B,
            inline f2: (=> B) => C
        ): C =
            def handle2 = self.handle(f1)
            f2(handle2)
        end handle

        /** Applies three transformations to this computation in sequence. */
        inline def handle[B, C, D](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D
        ): D =
            def handle3 = self.handle(f1, f2)
            f3(handle3)
        end handle

        /** Applies four transformations to this computation in sequence. */
        inline def handle[B, C, D, E](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E
        ): E =
            def handle4 = self.handle(f1, f2, f3)
            f4(handle4)
        end handle

        /** Applies five transformations to this computation in sequence. */
        inline def handle[B, C, D, E, F](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F
        ): F =
            def handle5 = self.handle(f1, f2, f3, f4)
            f5(handle5)
        end handle

        /** Applies six transformations to this computation in sequence. */
        inline def handle[B, C, D, E, F, G](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G
        ): G =
            def handle6 = self.handle(f1, f2, f3, f4, f5)
            f6(handle6)
        end handle

        /** Applies seven transformations to this computation in sequence. */
        inline def handle[B, C, D, E, F, G, H](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G,
            inline f7: (=> G) => H
        ): H =
            def handle7 = self.handle(f1, f2, f3, f4, f5, f6)
            f7(handle7)
        end handle

        /** Applies eight transformations to this computation in sequence. */
        inline def handle[B, C, D, E, F, G, H, I](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G,
            inline f7: (=> G) => H,
            inline f8: (=> H) => I
        ): I =
            def handle8 = self.handle(f1, f2, f3, f4, f5, f6, f7)
            f8(handle8)
        end handle

        /** Applies nine transformations to this computation in sequence. */
        inline def handle[B, C, D, E, F, G, H, I, J](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G,
            inline f7: (=> G) => H,
            inline f8: (=> H) => I,
            inline f9: (=> I) => J
        ): J =
            def handle9 = self.handle(f1, f2, f3, f4, f5, f6, f7, f8)
            f9(handle9)
        end handle

        /** Applies ten transformations to this computation in sequence. */
        inline def handle[B, C, D, E, F, G, H, I, J, K](
            inline f1: A < S => B,
            inline f2: (=> B) => C,
            inline f3: (=> C) => D,
            inline f4: (=> D) => E,
            inline f5: (=> E) => F,
            inline f6: (=> F) => G,
            inline f7: (=> G) => H,
            inline f8: (=> H) => I,
            inline f9: (=> I) => J,
            inline f10: (=> J) => K
        ): K =
            def handle10 = self.handle(f1, f2, f3, f4, f5, f6, f7, f8, f9)
            f10(handle10)
        end handle

        /** The value when this computation is already complete, or Absent when it is suspended. */
        private[kyo] inline def evalNow: Maybe[A] =
            self match
                case _: Kyo[?, ?] => Maybe.empty
                case v            => Maybe(Kyo.unnest(v).asInstanceOf[A])

    end extension

    extension [A, S, S2](self: A < S < S2)
        /** Flattens a nested pending computation into a single computation. */
        def flatten(using Frame): A < (S & S2) =
            self.map(v => v)
    end extension

    extension [A](self: A < Any)

        /** Evaluates the computation to its value.
          *
          * All effects must have handlers installed; reaching a suspension with no matching delimiter is a defect and throws.
          */
        def eval: A =
            if self.isInstanceOf[Kyo[?, ?]] then
                evalLoop(self.asInstanceOf[Any < Any], neverPreempt, 1, boundary = true) match
                    case pending: Kyo[?, ?] => kyo.bug.failTag(pending.asInstanceOf[Any < Any], Tag[Any])
                    case v                  => Kyo.unnest(v).asInstanceOf[A]
            else Kyo.unnest(self).asInstanceOf[A]

        /** Evaluates within a preemption budget, returning the remaining computation. */
        // TODO I don't think the period is used anymore?
        def eval(preempt: () => Boolean, period: Int): A < Any =
            evalLoop(self.asInstanceOf[Any < Any], preempt, Integer.max(1, period / Arrow.Period), boundary = true).asInstanceOf[A < Any]

    end extension

    private[kyo] val neverPreempt: () => Boolean = () => false

    private inline def BracketDepth = 512

    private def finalizeValue[A, S](v: A < S): Chunk[Throwable] =
        v match
            case kyo: Kyo.Continue[?, ?, ?] => finalizeArrow(kyo.cont)
            case kyo: Kyo.Defer[?, ?, ?]    => finalizeArrow(kyo.cont)
            case _                          => Chunk.empty

    private def finalizeArrow(arrow: Any): Chunk[Throwable] =
        arrow match
            case finalize: Finalize[?, ?, ?] =>
                try
                    val _ = finalize.bracket.release(finalize.value).asInstanceOf[Unit < Any].eval
                    Chunk.empty
                catch
                    case t: Throwable =>
                        EffectTrace.attach(t, "release", finalize.bracket.frame)
                        Chunk(t)
            case o: Arrow.Offset[Any, Any, Any, Any] @unchecked =>
                finalizeChain(o)
            case at: Arrow.AndThen[?, ?, ?, ?] =>
                finalizeArrow(at.a).concat(finalizeArrow(at.b))
            case _ =>
                Chunk.empty

    private def finalizeChain(o: Arrow.Offset[Any, Any, Any, Any]): Chunk[Throwable] =
        @tailrec def loop(cur: Any, errors: Chunk[Throwable]): Chunk[Throwable] =
            cur match
                case o: Arrow.Offset[Any, Any, Any, Any] @unchecked => loop(o.next, errors.concat(finalizeArrow(o.head)))
                case _                                              => errors
        loop(o, Chunk.empty)
    end finalizeChain

    private def yieldValue[A](v: A): Arrow[Unit, A, Any] =
        val lifted = Kyo.lift(v)
        new Arrow.Transform[Unit, A, Any]:
            def frame = Frame.internal
            def run[C, S2](x: Any, cont: Arrow[A, C, S2]): C < (Any & S2) =
                cont(lifted.asInstanceOf[A < Any])
        end new
    end yieldValue

    final private[kyo] class Finalize[R, A, S](val bracket: Kyo.Bracket[R, ?, S], val value: R)
        extends Arrow.Transform[A, A, S]:
        def frame = bracket.frame
        def run[C, S2](v: Any, cont: Arrow[A, C, S2]): C < (S & S2) =
            cont(yieldValue(v.asInstanceOf[A])(bracket.release(value)))
    end Finalize

    private def constant(v: Any < Any): Arrow[Any, Any, Any] =
        new Arrow.Transform[Any, Any, Any]:
            def frame = Frame.internal
            def run[C, S2](x: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
                cont(v)

    private def reacquire(bracket: Kyo.Bracket[Any, Any, Any]): Arrow[Any, Any, Any] =
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

    private def cleanup(bracket: Kyo.Bracket[Any, Any, Any], resource: Any, t: Throwable): Unit =
        try
            val _ = bracket.release(resource).eval
        catch
            case t2: Throwable =>
                EffectTrace.attach(t2, "release", bracket.frame)
                t.addSuppressed(t2)

    private def preempted(v: Any < Any): Boolean =
        v.isInstanceOf[Kyo.Defer[?, ?, ?]]

    /** The drive-boundary handler of last resort: consulted only when no installed delimiter matches. */
    // TODO wtf is this? how can you remove it?
    final private[kyo] class LastResort(
        val effectTag: Tag[Any],
        val clause: [C] => (Any, Arrow[Any, Any, Any]) => Maybe[Any < Any]
    )
    private[kyo] def evalLoop(
        v0: Any < Any,
        preempt: () => Boolean,
        stride: Int, // TODO unused?
        boundary: Boolean,
        last: LastResort | Null = null
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
                                    val resource = Kyo.unnest(acquired)
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
                    case c: Kyo.Continue[?, ?, ?] @unchecked if depth == 0 =>
                        evalSuspension(c, boundary).orElse(evalBoundary(c, last)) match
                            case Maybe.Present(next) =>
                                if n == 0 then
                                    if preempt() then next
                                    else loop(next, stride - 1)
                                else loop(next, n - 1)
                            case _ => curr
                    case s: Kyo.Suspension[?, ?] @unchecked if depth == 0 =>
                        // a bare suspension has no chain yet: dispatch it as a continue with the
                        // empty arrow so boundary clauses and context defaults still apply
                        val c = new Kyo.Continue(s.asInstanceOf[Kyo.Suspension[Any, Any]], Arrow[Any])
                        evalSuspension(c, boundary).orElse(evalBoundary(c, last)) match
                            case Maybe.Present(next) =>
                                if n == 0 then
                                    if preempt() then next
                                    else loop(next, stride - 1)
                                else loop(next, n - 1)
                            case _ => curr
                        end match
                    case kyo: Kyo[Any, Any] @unchecked =>
                        curr
                    case _ =>
                        curr
            loop(v, 0)
        end recur
        // the drive is a fresh trampoline: it runs with its own depth budget so frames the caller
        // already committed cannot starve it into re-rescuing the same step forever
        val safepoint = Safepoint.get
        val saved     = safepoint.openDrive()
        try recur(v0, 0)
        catch
            case ex: Throwable =>
                EffectTrace.install(ex)
                throw ex
        finally safepoint.closeDrive(saved)
        end try
    end evalLoop

    /** Finds the innermost matching delimiter in the suspension's chain and applies its format.
      *
      * The walk flattens nested pre-linked chains with an explicit pending stack, collecting the prefix (the continuation up to the
      * delimiter) only because the capturing formats need it. The casts below are justified by the tag match: a delimiter constructed for
      * `E` matched a suspension of `E`, so the clause's erased input and continuation have the types the public API established.
      */
    /** Context reads and their defaults resolve only at boundary drives (eval, handlePartial): an intermediate handle drive parks
      * them, so bindings installed later still compose, matching the current kernel's late resolution.
      */
    private def evalSuspension(c: Kyo.Continue[?, ?, ?], boundary: Boolean): Maybe[Any < Any] =
        val chain = c.cont.asInstanceOf[Arrow[Any, Any, Any]].optimize
        c.suspend match
            case s: Kyo.Suspend[?, ?, ?, ?] =>
                evalOperation(s.tag.asInstanceOf[Tag[Any]], s.input, chain)
            case r: Kyo.ContextRead[?, ?] if boundary =>
                resolveContext(r.tag.asInstanceOf[Tag[Any]], chain) match
                    case Maybe.Present(value) => Maybe(chain(Kyo.lift(value)))
                    case Maybe.Absent =>
                        r.default match
                            case Maybe.Present(d) => Maybe(chain(Kyo.lift(d())))
                            case Maybe.Absent     => Maybe.Absent
            case s: Kyo.ContextSnapshot if boundary =>
                Maybe(chain(Kyo.lift(snapshotContext(chain))))
            case _ =>
                Maybe.Absent
        end match
    end evalSuspension

    /** Materializes every visible context binding from the chain into a Context, the fork-time snapshot.
      *
      * Per tag, the resolution is the same fold resolveContext performs for a single read: matching delimiters innermost to outermost,
      * each transform receiving the resolution of the delimiters outside it.
      */
    private def snapshotContext(chain: Arrow[Any, Any, Any]): Context =
        @tailrec def collect(
            cur: Arrow[Any, Any, Any],
            pending: List[Arrow[Any, Any, Any]],
            acc: List[Handler.Context]
        ): List[Handler.Context] =
            cur match
                case o: Arrow.Offset[Any, Any, Any, Any] @unchecked =>
                    o.head match
                        case h: Handler.Context =>
                            collect(o.next, pending, h :: acc)
                        case inner: Arrow.Offset[Any, Any, Any, Any] @unchecked if inner.hasHandler =>
                            collect(inner, o.next :: pending, acc)
                        case _ =>
                            collect(o.next, pending, acc)
                case at: Arrow.AndThen[?, ?, ?, ?] =>
                    collect(at.asInstanceOf[Arrow[Any, Any, Any]].optimize, pending, acc)
                case t: Arrow.Transform[?, ?, ?] =>
                    val acc2 =
                        t match
                            case h: Handler.Context => h :: acc
                            case _                  => acc
                    pending match
                        case p :: ps => collect(p, ps, acc2)
                        case Nil     => acc2
            end match
        end collect
        val outermostFirst = if chain.hasHandler then collect(chain, Nil, Nil) else Nil
        if outermostFirst.isEmpty then Context.empty
        else
            // built through set so the Noninheritable flag entry is maintained like any other binding write
            var ctx = Context.empty
            outermostFirst.map(_.effectTag).distinct.foreach { tag =>
                var m: Maybe[Any] = Maybe.Absent
                outermostFirst.foreach { h =>
                    if h.effectTag <:< tag then m = Maybe(h.transform(m))
                }
                m match
                    case Maybe.Present(v) => ctx = ctx.set(tag.asInstanceOf[Tag[ContextEffect[Any]]], v)
                    case Maybe.Absent     => ()
            }
            ctx
        end if
    end snapshotContext

    private def evalOperation(suspendTag: Tag[Any], input: Any, chain: Arrow[Any, Any, Any]): Maybe[Any < Any] =

        def compose(rest: Arrow[Any, Any, Any], pending: List[Arrow[Any, Any, Any]]): Arrow[Any, Any, Any] =
            pending.foldLeft(rest)((acc, next) => Arrow.map(acc)(next))

        def prefixArrow(prefixRev: List[Arrow.Transform[Any, Any, Any]]): Arrow[Any, Any, Any] =
            prefixRev.foldLeft(Arrow[Any])((acc, t) => new Arrow.Offset[Any, Any, Any, Any](t, acc))

        def act(
            h: Handler.Operation,
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
                        case h: Handler.Operation if h.effectTag <:< suspendTag =>
                            act(h, o.next, pending, prefixRev)
                        case inner: Arrow.Offset[Any, Any, Any, Any] @unchecked if inner.hasHandler =>
                            search(inner, o.next :: pending, prefixRev)
                        case t =>
                            search(o.next, pending, t :: prefixRev)
                case at: Arrow.AndThen[?, ?, ?, ?] =>
                    search(at.asInstanceOf[Arrow[Any, Any, Any]].optimize, pending, prefixRev)
                case h: Handler.Operation if h.effectTag <:< suspendTag =>
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

        if !chain.hasHandler then Maybe.Absent
        else search(chain, Nil, Nil)
    end evalOperation

    /** Consults the drive-boundary clause for a suspension no delimiter matched.
      *
      * The clause receives the operation input and the full optimized chain as the continuation, delimiters included, so a later
      * resumption re-installs every traveling handler by construction. Present continues the drive; Absent parks it with the suspension
      * still pending, typically after the clause captured the continuation for an out-of-band resume.
      */
    private def evalBoundary(c: Kyo.Continue[?, ?, ?], last: LastResort | Null): Maybe[Any < Any] =
        last match
            case null => Maybe.Absent
            case last: LastResort =>
                c.suspend match
                    case s: Kyo.Suspend[?, ?, ?, ?] if last.effectTag <:< s.tag.asInstanceOf[Tag[Any]] =>
                        last.clause[Any](s.input, c.cont.asInstanceOf[Arrow[Any, Any, Any]].optimize)
                    case _ =>
                        Maybe.Absent
    end evalBoundary

    /** Resolves a context read against the chain's binding delimiters.
      *
      * Matching delimiters are collected innermost to outermost; each transform receives the resolution of the delimiters outside it, so
      * the innermost result is the read's value. Absent when no delimiter matches.
      */
    // TODO this is quite expensive! add benchmarks. shouldn't the context be threaded as a param?
    private def resolveContext(readTag: Tag[Any], chain: Arrow[Any, Any, Any]): Maybe[Any] =
        @tailrec def collect(
            cur: Arrow[Any, Any, Any],
            pending: List[Arrow[Any, Any, Any]],
            acc: List[Maybe[Any] => Any]
        ): List[Maybe[Any] => Any] =
            cur match
                case o: Arrow.Offset[Any, Any, Any, Any] @unchecked =>
                    o.head match
                        case h: Handler.Context if h.effectTag <:< readTag =>
                            collect(o.next, pending, h.transform :: acc)
                        case inner: Arrow.Offset[Any, Any, Any, Any] @unchecked if inner.hasHandler =>
                            collect(inner, o.next :: pending, acc)
                        case _ =>
                            collect(o.next, pending, acc)
                case at: Arrow.AndThen[?, ?, ?, ?] =>
                    collect(at.asInstanceOf[Arrow[Any, Any, Any]].optimize, pending, acc)
                case t: Arrow.Transform[?, ?, ?] =>
                    val acc2 =
                        t match
                            case h: Handler.Context if h.effectTag <:< readTag => h.transform :: acc
                            case _                                             => acc
                    pending match
                        case p :: ps => collect(p, ps, acc2)
                        case Nil     => acc2
            end match
        end collect
        val matches = if chain.hasHandler then collect(chain, Nil, Nil) else Nil
        matches match
            case Nil => Maybe.Absent
            case outermostFirst =>
                var m: Maybe[Any]                 = Maybe.Absent
                var rest: List[Maybe[Any] => Any] = outermostFirst
                while rest.nonEmpty do
                    m = Maybe(rest.head(m))
                    rest = rest.tail
                m
        end match
    end resolveContext

    /** Interprets a loop clause's Outcome once it materializes.
      *
      * This transform sits in the chain before the suffix past the delimiter, so effects raised while the outcome itself is computed
      * dispatch to outer handlers, never to this loop. Continue re-applies a replacement delimiter carrying the next state to the next
      * computation: if it suspends, the delimiter travels in its chain (deep with fresh state); if it is already a value, the delimiter's
      * run applies done with that state. Done leaves the region: the result flows to the suffix and done is not applied, the clause
      * already produced the final value.
      */
    private def outcomeStep(h: Handler.Loop): Arrow[Any, Any, Any] =
        new Arrow.Transform[Any, Any, Any]:
            def frame = h.frame
            def run[C, S2](v: Any, cont: Arrow[Any, C, S2]): C < (Any & S2) =
                v match
                    case next: Loop.Continue2[?, ?] @unchecked =>
                        val h2 = new Handler.Loop(h.effectTag, next._1, h.clause, h.done, h.frame)
                        cont(h2.asInstanceOf[Arrow[Any, Any, Any]](next._2.asInstanceOf[Any < Any]))
                    case b =>
                        cont(Kyo.lift(b))
    end outcomeStep

end `<`

extension (self: kyo.bug.type)
    private[kyo] def failTag[A, B, S](
        kyo: A < S,
        expected: Tag[B]
    ): Nothing =
        self(s"Unexpected pending effect while handling ${expected.show}: " + kyo)
end extension
