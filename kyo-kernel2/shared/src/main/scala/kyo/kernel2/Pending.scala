package kyo.kernel2

import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.kernel2.internal.Context
import kyo.kernel2.internal.EffectTrace
import kyo.kernel2.internal.Handler
import kyo.kernel2.internal.Handlers
import kyo.kernel2.internal.Kyo
import kyo.kernel2.internal.Safepoint
import language.implicitConversions
import scala.annotation.nowarn
import scala.annotation.static
import scala.annotation.tailrec
import scala.util.control.NonFatal

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
                def run[C, S3](v: Any, context: Context, handlers: Handlers, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                    val w = f(v.asInstanceOf[A])
                    cont match
                        case o: Arrow.Offset[Any, Any, Any, Any] @unchecked if !w.isInstanceOf[Kyo[?, ?]] =>
                            o.head.run(Kyo.unnest(w), context, handlers, o.next).asInstanceOf[C < (S2 & S3)]
                        case _ =>
                            cont(w, context, handlers)
                    end match
                end run
            // construction-time eager entry: the just-minted transform cannot consume
            // a context, so the empty argument is inert
            arrow(self, Context.empty, Handlers.empty)
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
                def run[C, S3](v: Any, context: Context, handlers: Handlers, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                    val w = f(v.asInstanceOf[A])
                    cont match
                        case o: Arrow.Offset[Any, Any, Any, Any] @unchecked if !w.isInstanceOf[Kyo[?, ?]] =>
                            o.head.run(Kyo.unnest(w), context, handlers, o.next).asInstanceOf[C < (S2 & S3)]
                        case _ =>
                            cont(w, context, handlers)
                    end match
                end run
            arrow(self, Context.empty, Handlers.empty)
        end flatMap

        /** Executes this computation, discards its result, and then executes another computation. */
        @nowarn
        inline def andThen[B, S2](inline f: => B < S2)(using inline _frame: Frame): B < (S & S2) =
            val arrow = new Arrow.Transform[A, B, S2]:
                def frame = _frame
                def run[C, S3](v: Any, context: Context, handlers: Handlers, cont: Arrow[B, C, S3]): C < (S2 & S3) =
                    val w = f
                    cont match
                        case o: Arrow.Offset[Any, Any, Any, Any] @unchecked if !w.isInstanceOf[Kyo[?, ?]] =>
                            o.head.run(Kyo.unnest(w), context, handlers, o.next).asInstanceOf[C < (S2 & S3)]
                        case _ =>
                            cont(w, context, handlers)
                    end match
                end run
            arrow(self, Context.empty, Handlers.empty)
        end andThen

        /** Executes this computation and discards its result. */
        @nowarn
        inline def unit(using inline _frame: Frame): Unit < S =
            val arrow = new Arrow.Transform[A, Unit, Any]:
                def frame = _frame
                def run[C, S3](v: Any, context: Context, handlers: Handlers, cont: Arrow[Unit, C, S3]): C < (Any & S3) =
                    cont match
                        case o: Arrow.Offset[Any, Any, Any, Any] @unchecked =>
                            o.head.run((), context, handlers, o.next).asInstanceOf[C < (Any & S3)]
                        case _ =>
                            cont((), context, handlers)
                    end match
                end run
            arrow(self, Context.empty, Handlers.empty)
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
          * The drive runs Masked: eval must return `A`, so it cannot hand back a remainder, and a non-polling drive would spin on a
          * pending request; it absorbs requests and re-issues them at exit so an enclosing slice still sees them. All effects must
          * have handlers installed; reaching a suspension with no matching delimiter is a defect and throws.
          */
        def eval: A =
            if self.isInstanceOf[Kyo[?, ?]] then
                evalLoop(self.asInstanceOf[Any < Any], EvalMasked, Context.empty, Handlers.empty) match
                    case pending: Kyo[?, ?] => kyo.bug.failTag(pending.asInstanceOf[Any < Any], Tag[Any])
                    case v                  => Kyo.unnest(v).asInstanceOf[A]
            else Kyo.unnest(self).asInstanceOf[A]

        /** Evaluates until the computation completes or a preemption request is consumed, returning the remainder.
          *
          * The clause-free Preemptible drive: the caller re-schedules the remainder and drives it again. The request is consumed once
          * at exit, so the caller's authoritative check after this returns observes every condition published before the request.
          */
        private[kyo] def evalPartial: A < Any =
            evalLoop(self.asInstanceOf[Any < Any], EvalPreemptible, Context.empty, Handlers.empty).asInstanceOf[A < Any]

    end extension

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
            def run[C, S2](x: Any, context: Context, handlers: Handlers, cont: Arrow[A, C, S2]): C < (Any & S2) =
                cont(lifted.asInstanceOf[A < Any], context, handlers)
        end new
    end yieldValue

    final private[kyo] class Finalize[R, A, S](val bracket: Kyo.Bracket[R, ?, S], val value: R)
        extends Arrow.Transform[A, A, S]:
        def frame = bracket.frame
        def run[C, S2](v: Any, context: Context, handlers: Handlers, cont: Arrow[A, C, S2]): C < (S & S2) =
            cont(yieldValue(v.asInstanceOf[A])(bracket.release(value), context, handlers), context, handlers)
    end Finalize

    private def constant(v: Any < Any): Arrow[Any, Any, Any] =
        new Arrow.Transform[Any, Any, Any]:
            def frame = Frame.internal
            def run[C, S2](x: Any, context: Context, handlers: Handlers, cont: Arrow[Any, C, S2]): C < (Any & S2) =
                cont(v, context, handlers)

    private def reacquire(bracket: Kyo.Bracket[Any, Any, Any]): Arrow[Any, Any, Any] =
        new Arrow.Transform[Any, Any, Any]:
            def frame = Frame.internal
            def run[C, S2](r: Any, context: Context, handlers: Handlers, cont: Arrow[Any, C, S2]): C < (Any & S2) =
                cont(
                    new Kyo.Bracket[Any, Any, Any]:
                        def acquire         = r
                        def release(x: Any) = bracket.release(x)
                        def cont            = bracket.cont
                        def frame =
                            bracket.frame
                    ,
                    context,
                    handlers
                )

    private def cleanup(bracket: Kyo.Bracket[Any, Any, Any], resource: Any, t: Throwable): Unit =
        try
            val _ = bracket.release(resource).eval
        catch
            case t2: Throwable =>
                EffectTrace.attach(t2, "release", bracket.frame)
                t.addSuppressed(t2)

    /** The three drive modes. Preemptible is the slice boundary: it stops on a request and its caller consumes it once at exit.
      * Masked cannot hand back a remainder, so it absorbs requests at each poll and re-issues them at exit. Cascade is an inner drive
      * that neither consumes nor re-issues: it returns the remainder so the request reaches the enclosing slice.
      */
    private[kyo] inline def EvalPreemptible = 0
    private[kyo] inline def EvalMasked      = 1
    private[kyo] inline def EvalCascade     = 2

    // context is a constant of the drive: parked remainders carry their re-armed
    // interceptors, so plain re-application from the entry context reconstructs
    // the in-scope bindings on every bounce
    private[kyo] def evalLoop(v0: Any < Any, mode: Int, context: Context, handlers: Handlers): Any < Any =
        def recur(v: Any < Any, depth: Int): Any < Any =
            @tailrec def loop(curr: Any < Any): Any < Any =
                // the poll sits at the top of the loop: it covers Defer pops, suspension dispatches, and the bare arm with one
                // site, and every mode must poll, because a drive that ignores a pending request cannot make progress through a
                // lone-transform Defer (every enter refuses and the identical rescue comes back)
                if Safepoint.pollPreempt() then
                    if mode == EvalMasked then Safepoint.maskPreempt()
                    else return curr
                curr match
                    case bracket: Kyo.Bracket[Any, Any, Any] @unchecked =>
                        if depth >= BracketDepth then bracket
                        else
                            recur(bracket.acquire, depth + 1) match
                                case suspended: Kyo[Any, Any] @unchecked =>
                                    val wrapped = suspended.map(reacquire(bracket))
                                    if depth == 0 then loop(wrapped) else wrapped
                                case acquired =>
                                    val resource = Kyo.unnest(acquired)
                                    val result =
                                        try recur(bracket.cont(acquired, context, handlers), depth + 1)
                                        catch
                                            case t: Throwable =>
                                                cleanup(bracket, resource, t)
                                                throw t
                                    result match
                                        case suspended: Kyo[Any, Any] @unchecked =>
                                            val wrapped = suspended.map(new Finalize[Any, Any, Any](bracket, resource))
                                            if depth == 0 then loop(wrapped) else wrapped
                                        case _ =>
                                            // release runs masked, mirroring the current kernel: neither a time slice nor an
                                            // interrupt cuts a finalizer that is already running
                                            Safepoint.maskPreempt()
                                            val released =
                                                try recur(bracket.release(resource), depth + 1)
                                                finally Safepoint.unmaskPreempt()
                                            released match
                                                case suspended: Kyo[Any, Any] @unchecked =>
                                                    val wrapped = suspended.map(constant(result))
                                                    if depth == 0 then loop(wrapped) else wrapped
                                                case _ =>
                                                    result
                                            end match
                                    end match
                    case defer: Kyo.Defer[Any, Any, Any] @unchecked =>
                        loop(defer.cont(defer.value, context, handlers))
                    case c: Kyo.Continue[?, ?, ?] @unchecked if depth == 0 =>
                        val chain = c.cont.asInstanceOf[Arrow[Any, Any, Any]].optimize
                        evalOperation(c.suspend.erasedTag, c.suspend.input, chain, context, handlers) match
                            case Maybe.Present(next) => loop(next)
                            case _                   => curr
                    case s: Kyo.Suspend[?, ?, ?, ?] @unchecked if depth == 0 =>
                        // a bare operation has no chain yet: dispatch it with the empty arrow
                        evalOperation(s.erasedTag, s.input, Arrow[Any], context, handlers) match
                            case Maybe.Present(next) => loop(next)
                            case _                   => curr
                        end match
                    case kyo: Kyo[Any, Any] @unchecked =>
                        curr
                    case _ =>
                        curr
                end match
            end loop
            loop(v)
        end recur
        // the drive is a fresh trampoline: it runs with its own depth budget so frames the caller
        // already committed cannot starve it into re-rescuing the same step forever
        val safepoint = Safepoint.get
        val saved     = safepoint.openDrive()
        try
            val result = recur(v0, 0)
            // the Preemptible boundary consumes exactly once, at exit: consuming at the poll site would break the bracket
            // cascade, which decides whether to keep driving by observing that the request is still pending
            if mode == EvalPreemptible then
                val _ = Safepoint.clearPreempt()
            result
        catch
            case ex: Throwable =>
                EffectTrace.install(ex)
                throw ex
        finally
            if mode == EvalMasked then Safepoint.unmaskPreempt()
            safepoint.closeDrive(saved)
        end try
    end evalLoop

    /** Finds the innermost matching delimiter in the suspension's chain and applies its format.
      *
      * The walk flattens nested pre-linked chains with an explicit pending stack, collecting the prefix (the continuation up to the
      * delimiter) only because the capturing formats need it. The casts below are justified by the tag match: a delimiter constructed for
      * `E` matched a suspension of `E`, so the clause's erased input and continuation have the types the public API established.
      */
    private def evalOperation(
        suspendTag: Tag[Any],
        input: Any,
        chain: Arrow[Any, Any, Any],
        context: Context,
        handlers: Handlers
    ): Maybe[Any < Any] =

        def compose(rest: Arrow[Any, Any, Any], pending: List[Arrow[Any, Any, Any]]): Arrow[Any, Any, Any] =
            pending.foldLeft(rest)((acc, next) => Arrow.map(acc)(next))

        def prefixArrow(prefixRev: List[Arrow.Transform[Any, Any, Any]]): Arrow[Any, Any, Any] =
            prefixRev.foldLeft(Arrow[Any])((acc, t) => new Arrow.Offset[Any, Any, Any, Any](t, acc))

        // The casts below are the erased boundary the typed handler surface funnels into. Each is justified by the tag match that
        // selected the delimiter: the suspension's operation is of the delimiter's effect, so its input has the clause's input type,
        // the captured prefix is the continuation from the operation's output to the delimiter's region type, and the results re-enter
        // the drive's erased currency.
        def act(
            h: Handler.ArrowHandler[?, ?, ?, ?, ?, ?],
            rest: Arrow[Any, Any, Any],
            pending: List[Arrow[Any, Any, Any]],
            prefixRev: List[Arrow.Transform[Any, Any, Any]]
        ): Maybe[Any < Any] =
            val fullRest = compose(rest, pending)
            // A clause that throws is a failed step of the computation: the exception unwinds to the innermost Catching
            // interceptor enclosing the operation (the prefix element nearest the delimiter), matching how a throw inside an
            // evaluated step is intercepted. With no interceptor in scope it propagates to the drive.
            def guarded(compute: => Any < Any): Maybe[Any < Any] =
                try Maybe(compute)
                catch
                    case ex if NonFatal(ex) =>
                        EffectTrace.attach(ex, "handle", h.frame)
                        @tailrec def unwind(rev: List[Arrow.Transform[Any, Any, Any]]): Maybe[Any < Any] =
                            rev match
                                case (c: Effect.Catching) :: _ => Maybe(c.handler(ex))
                                case _ :: tail                 => unwind(tail)
                                case Nil                       => throw ex
                        unwind(prefixRev)
            end guarded
            h match
                case h: Handler.Resume[i, o, e, a, s, s2] =>
                    guarded(chain(h.clause[Any](input.asInstanceOf[i[Any]]), context, handlers).asInstanceOf[Any < Any])
                case h: Handler.Stop[i, o, e, a, s, s2] =>
                    val reinstalled = new Arrow.Offset[Any, Any, Any, Any](h.asInstanceOf[Arrow.Transform[Any, Any, Any]], fullRest)
                    guarded(reinstalled(h.clause[Any](input.asInstanceOf[i[Any]]).asInstanceOf[Any < Any], context, handlers))
                case h: Handler.Cont[i, o, e, a, s, s2] =>
                    val k           = prefixArrow(prefixRev)
                    val resume      = (x: o[Any]) => k(Kyo.lift(x), context, handlers).asInstanceOf[a < (e & s & s2)]
                    val reinstalled = new Arrow.Offset[Any, Any, Any, Any](h.asInstanceOf[Arrow.Transform[Any, Any, Any]], fullRest)
                    guarded(reinstalled(h.clause[Any](input.asInstanceOf[i[Any]], resume).asInstanceOf[Any < Any], context, handlers))
                case h: Handler.First[i, o, e, a, b, s, s2] =>
                    val k      = prefixArrow(prefixRev)
                    val resume = (x: o[Any]) => k(Kyo.lift(x), context, handlers).asInstanceOf[a < (e & s)]
                    guarded(fullRest(h.clause[Any](input.asInstanceOf[i[Any]], resume).asInstanceOf[Any < Any], context, handlers))
                case h: Handler.Loop[i, o, e, a, b, s, s2, st] =>
                    val k      = prefixArrow(prefixRev)
                    val resume = (x: o[Any]) => k(Kyo.lift(x), context, handlers).asInstanceOf[a < (e & s)]
                    guarded(Arrow.map(outcomeStep(h))(fullRest)(
                        h.clause[Any](
                            input.asInstanceOf[i[Any]],
                            h.state,
                            resume
                        ).asInstanceOf[Any < Any],
                        context,
                        handlers
                    ))
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
                        case h: Handler.ArrowHandler[?, ?, ?, ?, ?, ?] if h.erasedTag <:< suspendTag =>
                            act(h, o.next, pending, prefixRev)
                        case inner: Arrow.Offset[Any, Any, Any, Any] @unchecked if inner.hasHandler =>
                            search(inner, o.next :: pending, prefixRev)
                        case t =>
                            search(o.next, pending, t :: prefixRev)
                case at: Arrow.AndThen[?, ?, ?, ?] =>
                    search(at.asInstanceOf[Arrow[Any, Any, Any]].optimize, pending, prefixRev)
                case h: Handler.ArrowHandler[?, ?, ?, ?, ?, ?] if h.erasedTag <:< suspendTag =>
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

    /** Interprets a loop clause's Outcome once it materializes.
      *
      * This transform sits in the chain before the suffix past the delimiter, so effects raised while the outcome itself is computed
      * dispatch to outer handlers, never to this loop. Continue re-applies a replacement delimiter carrying the next state to the next
      * computation: if it suspends, the delimiter travels in its chain (deep with fresh state); if it is already a value, the delimiter's
      * run applies done with that state. Done leaves the region: the result flows to the suffix and done is not applied, the clause
      * already produced the final value.
      */
    private def outcomeStep(h: Handler.Loop[?, ?, ?, ?, ?, ?, ?, ?]): Arrow[Any, Any, Any] =
        new Arrow.Transform[Any, Any, Any]:
            def frame = h.frame
            def run[C, S2](v: Any, context: Context, handlers: Handlers, cont: Arrow[Any, C, S2]): C < (Any & S2) =
                v match
                    case next: Loop.Continue2[?, ?] @unchecked =>
                        // re-install the delimiter carrying the clause's next state around the next computation
                        val h2 = h.replaceState(next._1).asInstanceOf[Arrow[Any, Any, Any]]
                        cont(h2(next._2.asInstanceOf[Any < Any], context, handlers), context, handlers)
                    case b =>
                        cont(Kyo.lift(b), context, handlers)
    end outcomeStep

end `<`

extension (self: kyo.bug.type)
    private[kyo] def failTag[A, B, S](
        kyo: A < S,
        expected: Tag[B]
    ): Nothing =
        self(s"Unexpected pending effect while handling ${expected.show}: " + kyo)
end extension
