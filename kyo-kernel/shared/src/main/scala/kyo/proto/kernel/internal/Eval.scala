package kyo.proto.kernel.internal

import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.bug
import kyo.discard
import kyo.proto.Arrow
import kyo.proto.Loop
import kyo.proto.Loop.Outcome2
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect
import kyo.proto.kernel.ContextEffect
import kyo.proto.kernel.Effect
import language.implicitConversions
import scala.annotation.tailrec
import scala.util.control.NonFatal

object Eval:

    /** The releases a computation still owes, for a holder giving up on resuming it: every region the machine already installed speaks,
      * innermost first, and a settled value owes nothing. A computation rather than a run: the holder sequences the result into its own
      * stream, which is what lets the ambient context reach the releases. Typed on the pending union on purpose: bare arrows are not
      * computations and do not release.
      */
    def release[A, S](v: A < S): Any < Any =
        v match
            case p: Pending[?, ?] => p.release(Discarded)
            case _                => ()

    def apply[A, S](v: A < S): A < S = apply(v, armed = false)

    /** Evaluates until the computation parks, handing back a value that resumes on a later slice.
      *
      * A slice ends on a preemption stop, and the value returned carries the regions above the park intact, with their state. Stops are
      * Safepoint's alone: the scheduler delivers one through the slot, and the poll reads the slot, so there is no caller-supplied stop
      * function and nothing to allocate per slice.
      *
      * The row is `Any`, the same as a full evaluation: every effect must already be handled. An operation with no handler is a bug here
      * too, not something a slice can park on and have answered later.
      *
      * A stop already delivered before the slice begins ends it before it starts: the input comes straight back, and the sentinel is taken
      * so the slice after this one runs.
      */
    private[kyo] def partial[A](v: A < Any): A < Any =
        val slot = Safepoint.get()
        if Safepoint.consumeStopped(slot) then v
        else
            // the poll only reads: the sentinel stays in the slot, so the park check sees it however
            // many times it asks. It is consumed once, at the slice boundary in the finally: park,
            // completion, and failure all satisfy the stop there, so no stale sentinel survives to
            // short-circuit the next slice
            try apply(v, armed = true)
            finally discard(Safepoint.consumeStopped(slot))
        end if
    end partial

    private def apply[A, S](v: A < S, armed: Boolean): A < S =
        // the regions this eval installs. A nested eval borrows its own, so it answers for the
        // regions it installed and for none of the enclosing ones. Borrowed rather than allocated
        // because most evals install nothing and gave one away for free; returned in the finally
        // below, emptied, including when the eval leaves on an exception with regions still on it
        val stack = Stack.borrow()

        // the depth guard bounds strict recursion within one eval, so the budget is this eval's and
        // not whatever the thread had left. Inheriting a spent one is a fixed point rather than a
        // slow path: every application defers, the settled arm applies the deferral, and its
        // continuation is the application that just deferred.
        //
        // Restored in the finally below so a nested eval hands the enclosing one back what it had,
        // its part-spent depth and its armed bit included. `save` installs a fresh budget and clears
        // the armed bit as it reads, and arming after it makes the polls live for this eval alone: a
        // plain eval nested inside a slice runs unarmed and cannot park, and the restore hands the
        // slice its armed state back when the nested eval ends
        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        if armed then Safepoint.arm(slot)

        // The parked slice, as a value that resumes it: the current position reified through the
        // deferral laws, and the open regions moved into one Park node. Semantically the value
        // wrapped in one Handle per entry; the node and its packed array stand in for the wrappers,
        // and the resume arm below must stay observationally equivalent to that reading. The row
        // widens to Any the way the settled exits narrow from it: the parked value's effects are
        // answered by the regions it carries, and those travel with it
        def park[T, B, C, S2](curr: T < S2, contA: Arrow[T, B, S2], contB: Arrow[B, C, S2]): A < S =
            val v: Any < Any =
                if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then curr.asInstanceOf[Any < Any]
                else Effect.defer(curr, contA, contB).asInstanceOf[Any < Any]
            if stack.isEmpty then v.asInstanceOf[A < S]
            else new Kyo.Park[A, S](v, stack.snapshot())
        end park

        // `loop` runs the machine to its answer, which is why it returns the eval's result rather
        // than the composition of its arguments: once a region's continuation waits on the stack,
        // "v with contA then contB applied" stops describing what the call produces
        @tailrec def loop[T, B, C, S2](v: T < S2, contA: Arrow[T, B, S2], contB: Arrow[B, C, S2], ctx: Context): A < S =
            Debugger.onLoop(v, contA, contB)
            v match
                case kyo: Kyo.Defer[AX, Y, T, S2] @unchecked =>
                    // the preemption poll, on this arm because the budget makes it inevitable: every
                    // strict application gates on `Safepoint.enter`, a drained budget turns
                    // applications into deferrals, and every by-name suspension builds one outright,
                    // so a pending stop is observed within one budget period of pure strict work
                    if armed && Safepoint.stopped(slot) then park(v, contA, contB)
                    else loop(kyo.value, kyo.contA, kyo.contB.chain(contA.chain(contB)), ctx)
                case kyo: Kyo.SuspendContext[VX, CX, T, S2] @unchecked if ctx.contains(kyo.tag) =>
                    val nv = kyo.update(ctx.apply[VX, CX](kyo.tag))
                    Debugger.onContext(kyo, nv)
                    val k = kyo.cont
                    loop(k.head(nv, k.tail), contA, contB, ctx.update[VX, CX](kyo.tag, nv))
                case kyo: Kyo.SuspendContextDefault[VX, CX, T, S2] @unchecked if ctx.contains(kyo.tag) =>
                    val nv = kyo.update(ctx.apply[VX, CX](kyo.tag))
                    Debugger.onContext(kyo, nv)
                    val k = kyo.cont
                    loop(k.head(nv, k.tail), contA, contB, ctx.update[VX, CX](kyo.tag, nv))
                case kyo: Kyo.Suspend[EX, T, S2] @unchecked =>
                    // the registers absorb into the node first, so the regions are asked about one
                    // suspension carrying its whole continuation
                    val susp: Kyo.Suspend[?, ?, ?] =
                        if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then kyo
                        else if kyo.cont.isInstanceOf[Arrow.Id[?]] && (contA.isInstanceOf[Arrow.Id[?]] || contB.isInstanceOf[Arrow.Id[?]])
                        then
                            // a single live register absorbs into the free slot: only the copy allocates
                            // TODO I can't see why this is better than just letting the else execute. This will allocate two objects no?
                            kyo.withCont(kyo.cont.chain(contA.chain(contB)))
                        else
                            // two or more live continuations: reifying through chains would allocate a copy
                            // plus a Chain per composition. One allocation fulfills every role instead: the
                            // reified suspension captures its continuation and the registers, and delivery
                            // composes by nested application; arriving through head and tail with an identity
                            // continuation, the chain law composes without allocating
                            kyo match
                                // TODO could we have a method `rotate` in Suspend so these impls move out of the eval?
                                case sa: Kyo.SuspendArrow[IX, OX, EX, VX, T, S2] @unchecked =>
                                    val sax: Kyo.SuspendArrow[IX, OX, EX, VX, T, S2] = sa
                                    val k0                                           = sax.cont
                                    val cA                                           = contA
                                    val cB                                           = contB
                                    new Kyo.SuspendArrowTransform[IX, OX, EX, VX, C, S2]:
                                        def tag   = sax.tag
                                        def input = sax.input
                                        def cont  = this
                                        override def apply[D, S3](x: OX[VX] < S3, c2: Arrow[C, D, S3]) =
                                            x match
                                                case p: Pending[OX[VX], S3] @unchecked => Effect.defer(p, this, c2)
                                                case _                                 => cA(k0(x, Arrow.id), cB.chain(c2))
                                    end new
                                case sc: Kyo.SuspendContext[VX, CX, T, S2] @unchecked =>
                                    val scx: Kyo.SuspendContext[VX, CX, T, S2] = sc
                                    val k0                                     = scx.cont
                                    val cA                                     = contA
                                    val cB                                     = contB
                                    new Kyo.SuspendContextTransform[VX, CX, C, S2]:
                                        def tag           = scx.tag
                                        def update(v: VX) = scx.update(v)
                                        def cont          = this
                                        override def apply[D, S3](x: VX < S3, c2: Arrow[C, D, S3]) =
                                            x match
                                                case p: Pending[VX, S3] @unchecked => Effect.defer(p, this, c2)
                                                case _                             => cA(k0(x, Arrow.id), cB.chain(c2))
                                    end new
                                case sd: Kyo.SuspendContextDefault[VX, CX, T, S2] @unchecked =>
                                    val sdx: Kyo.SuspendContextDefault[VX, CX, T, S2] = sd
                                    val k0                                            = sdx.cont
                                    val cA                                            = contA
                                    val cB                                            = contB
                                    new Kyo.SuspendContextDefaultTransform[VX, CX, C, S2]:
                                        def tag           = sdx.tag
                                        def default       = sdx.default
                                        def update(v: VX) = sdx.update(v)
                                        def cont          = this
                                        override def apply[D, S3](x: VX < S3, c2: Arrow[C, D, S3]) =
                                            x match
                                                case p: Pending[VX, S3] @unchecked => Effect.defer(p, this, c2)
                                                case _                             => cA(k0(x, Arrow.id), cB.chain(c2))
                                    end new
                            end match
                    if stack.isEmpty then susp.asInstanceOf[A < S]
                    else
                        val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]
                        val state   = stack.state.asInstanceOf[VX]
                        if !(susp.tag.erased <:< handler.tag.erased) then
                            Debugger.onForeign(susp, handler)
                            // parameterized over the suspension's payload so each arm calls it with its own
                            // refined continuation, keeping the rebuilt applies typed at the true input
                            def reenter[P, D, S3](k0: Arrow[P, AX, EX], x: P < S3, cont2: Arrow[Y, D, S3]): D < S3 =
                                // the application is deferred rather than run here, so the region is
                                // installed around the application instead of around what it already
                                // produced. That is what makes this path ordinary: a throw reaches the
                                // eval's guard with this region on the stack, so it consults the one
                                // recover, repairs the budget where the depth is known, and completes
                                // through the same `done` the settled arm runs
                                Kyo.handle[EX, AX, Y, S3, VX](Effect.defer(x, k0), handler, state).chain(cont2)
                            // this region is foreign to the suspension, so it becomes part of the
                            // suspension's continuation and ends. Re-entering with what that produced asks
                            // the next region out the same question
                            val rebuilt: Y < Any =
                                susp match
                                    case sa: Kyo.SuspendArrow[IY, OY, EY, VY, AX, EX] @unchecked =>
                                        // TODO method in Suspend to simplify code here?
                                        val sax: Kyo.SuspendArrow[IY, OY, EY, VY, AX, EX] = sa
                                        // one allocation fulfilling both roles: the rebuilt suspension and its re-handling transform
                                        new Kyo.SuspendArrowTransform[IY, OY, EY, VY, Y, Any]:
                                            def tag   = sax.tag
                                            def input = sax.input
                                            def cont  = this
                                            override def release(ex: Throwable): Any < Any =
                                                Debugger.onRelease(handler, ex)
                                                sax.release(ex).andThen(handler.release(state, ex))(using Frame.internal)
                                            override def apply[D, S3](x: OY[VY] < S3, cont2: Arrow[Y, D, S3]) =
                                                x match
                                                    case p: Pending[OY[VY], S3] @unchecked =>
                                                        Effect.defer(p, this, cont2)
                                                    case _ =>
                                                        reenter(sax.cont, Nested.unnest[OY[VY]](x), cont2)
                                        end new
                                    case sc: Kyo.SuspendContext[VX, CX, AX, EX] @unchecked =>
                                        val scx: Kyo.SuspendContext[VX, CX, AX, EX] = sc
                                        // one allocation fulfilling both roles: the rebuilt suspension and its re-handling transform
                                        new Kyo.SuspendContextTransform[VX, CX, Y, Any]:
                                            def tag           = scx.tag
                                            def update(v: VX) = scx.update(v)
                                            def cont          = this
                                            override def release(ex: Throwable): Any < Any =
                                                Debugger.onRelease(handler, ex)
                                                scx.release(ex).andThen(handler.release(state, ex))(using Frame.internal)
                                            override def apply[D, S3](x: VX < S3, cont2: Arrow[Y, D, S3]) =
                                                x match
                                                    case p: Pending[VX, S3] @unchecked =>
                                                        Effect.defer(p, this, cont2)
                                                    case _ =>
                                                        reenter(scx.cont, Nested.unnest[VX](x), cont2)
                                        end new
                                    case sd: Kyo.SuspendContextDefault[VX, CX, AX, EX] @unchecked =>
                                        val sdx: Kyo.SuspendContextDefault[VX, CX, AX, EX] = sd
                                        // one allocation fulfilling both roles: the rebuilt suspension and its re-handling transform
                                        new Kyo.SuspendContextDefaultTransform[VX, CX, Y, Any]:
                                            def tag           = sdx.tag
                                            def default       = sdx.default
                                            def update(v: VX) = sdx.update(v)
                                            def cont          = this
                                            override def release(ex: Throwable): Any < Any =
                                                Debugger.onRelease(handler, ex)
                                                sdx.release(ex).andThen(handler.release(state, ex))(using Frame.internal)
                                            override def apply[D, S3](x: VX < S3, cont2: Arrow[Y, D, S3]) =
                                                x match
                                                    case p: Pending[VX, S3] @unchecked =>
                                                        Effect.defer(p, this, cont2)
                                                    case _ =>
                                                        reenter(sdx.cont, Nested.unnest[VX](x), cont2)
                                        end new
                                end match
                            end rebuilt
                            Debugger.onRegionExit(handler, rebuilt)
                            val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]
                            val outer = stack.ctx
                            stack.pop()
                            loop(rebuilt, cont, Arrow.id, outer)
                        else
                            susp match
                                case suspend: Kyo.SuspendArrow[IX, OX, EX, VX, AX, EX] @unchecked =>
                                    Debugger.onHandle(suspend, handler, state)
                                    val next = suspend.cont
                                    handler match
                                        case handler: Handler.HandlerCont[IX, OX, EX, AX, Y, Any] @unchecked =>
                                            val r = handler.answer(suspend.input, next)
                                            Debugger.onResult(r)
                                            // the region stays installed: its clause answered in place
                                            loop(r, Arrow.id, Arrow.id, ctx)
                                        case handler: Handler.HandlerLoop[IX, OX, EX, AX, Y, Any, VX] @unchecked =>
                                            val o = handler.answer(state, suspend.input, next)
                                            Debugger.onResult(o)
                                            o match
                                                case o: Loop.Continue2[VX, OX[VX] < EX] @unchecked =>
                                                    // the region stays installed at its successor state
                                                    stack.state = o._1
                                                    loop(o._2, next, Arrow.id, ctx)
                                                case o =>
                                                    // a done outcome is its payload, and `answerLoop` already
                                                    // took the outcome out of the union, so what stands here is
                                                    // the payload in its own union representation. Asserted
                                                    // rather than unnested: a payload that is itself a
                                                    // computation carries the wrapper that says so, and
                                                    // stripping it hands the loop work to evaluate where the
                                                    // region meant to deliver a value
                                                    val r = o.asInstanceOf[Y < Any]
                                                    Debugger.onRegionExit(handler, r)
                                                    val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]
                                                    val outer = stack.ctx
                                                    stack.pop()
                                                    loop(r, cont, Arrow.id, outer)
                                            end match
                                        case _ =>
                                            bug(s"unhandled: $handler")
                                    end match
                                case _ =>
                                    bug(s"unhandled: $susp")
                            end match
                        end if
                    end if
                case kyo: Kyo.Handle[EX, AX, Y, T, S2, VX] @unchecked =>
                    // a context binding resolves at installation: it derives from whatever the
                    // enclosing scope binds for its tag, and a re-installed region derives again
                    // from wherever it stands
                    var st0 = kyo.state
                    val bound = kyo.handler match
                        case h: Handler.HandlerContext[VX, CX, AX, Y, S2] @unchecked =>
                            val hc: Handler.HandlerContext[VX, CX, AX, Y, S2] = h
                            st0 = hc.resolve(Maybe.when(ctx.contains(hc.tag))(ctx[VX, CX](hc.tag)))
                            ctx.update(hc.tag, st0)
                        case _ => ctx
                    Debugger.onRegionEnter(kyo.handler, st0)
                    // the region is installed rather than entered: what follows it waits on the stack,
                    // so its interior is evaluated by this same loop instead of by a nested one
                    stack.push(kyo.handler, st0, ctx, kyo.cont.chain(contA.chain(contB)))
                    loop(kyo.value, Arrow.id, Arrow.id, bound)
                case kyo: Kyo.Park[?, ?] =>
                    // re-installation, not restoration: each entry is pushed the way the Handle arm
                    // just above pushes one, against the ambient context, so a binding re-resolves
                    // from where the resume stands and region exits restore resume-time contexts,
                    // never park-time ones. Mirrors that arm's installation; a change there lands
                    // here too
                    val entries = kyo.entries
                    var c       = ctx
                    var i       = 0
                    while i < entries.length do
                        val handler = entries(i).asInstanceOf[Handler[EX, AX, Y, Any, VX]]
                        var st      = entries(i + 1).asInstanceOf[VX]
                        val cont    = entries(i + 2).asInstanceOf[Arrow[Y, Any, Any]]
                        val bound = handler match
                            case h: Handler.HandlerContext[VX, CX, AX, Y, Any] @unchecked =>
                                val hc: Handler.HandlerContext[VX, CX, AX, Y, Any] = h
                                st = hc.resolve(Maybe.when(c.contains(hc.tag))(c[VX, CX](hc.tag)))
                                c.update(hc.tag, st)
                            case _ => c
                        Debugger.onRegionEnter(handler, st)
                        // the pending continuation follows the outermost region
                        if i == 0 then stack.push(handler, st, c, cont.chain(contA.chain(contB).asInstanceOf[Arrow[Any, Any, Any]]))
                        else stack.push(handler, st, c, cont)
                        c = bound
                        i += 3
                    end while
                    loop(kyo.value, Arrow.id, Arrow.id, c)
                case res =>
                    if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then
                        if stack.isEmpty then res.asInstanceOf[A < S]
                        else
                            // the innermost region completes. `done` runs with the region still
                            // installed, so a throw in it reaches the same recover its interior would
                            val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]
                            val r       = handler.done(stack.state.asInstanceOf[VX], Nested.unnest[AX](res))
                            Debugger.onRegionExit(handler, r)
                            val cont  = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]
                            val outer = stack.ctx
                            stack.pop()
                            loop(r, cont, Arrow.id, outer)
                    else
                        contA match
                            case contA: Arrow.Chain[T, Any, B, S2] @unchecked =>
                                loop(res, contA.a, contA.b.chain(contB), ctx)
                            case _ =>
                                // the value is already union currency and arrows take it as such: casting
                                // it back to a raw payload would let the lift fire and nest it a second
                                // time, which is how a computation held as data acquires a wrapper the
                                // delivery cannot strip
                                loop(contA(res, contB), Arrow.id, Arrow.id, ctx)
            end match
        end loop

        // The regions a throw unwinds, innermost first, ending in the value the region that recovered
        // resumes with. A region that declines is popped and the failure keeps unwinding through the
        // ones outside it; with none left the eval fails as its caller's does. A recover that fails
        // itself is the failure those outer regions see, which is what the per-region guards did by
        // nesting.
        //
        // The region that recovers is left on the stack, and the resume below pops it. That is what
        // lets this return one value rather than the value and the context to resume it at, which
        // would be a pair allocated on the path `Abort` runs through.
        @tailrec def recovered(ex: Throwable): A < S =
            if stack.isEmpty then throw ex
            else
                val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]
                val state   = stack.state.asInstanceOf[VX]
                val outcome =
                    try handler.recover(state, ex)
                    catch
                        case ex2 if NonFatal(ex2) =>
                            stack.pop()
                            return recovered(ex2)
                outcome match
                    case Present(r) =>
                        Debugger.onRecover(handler, ex)
                        Debugger.onRegionExit(handler, r)
                        r.chain(stack.cont.asInstanceOf[Arrow[Y, A, S]])
                    case Absent =>
                        stack.pop()
                        recovered(ex)
                end match

        // The eval and its guard. Recovering resumes here and reading a default that no region
        // answered resumes here, and both are self tail calls, so the eval that removed the frame per
        // open region reintroduces none per recovered region or per default answered.
        @tailrec def guarded(curr: A < S, ctx: Context): A < S =
            val res =
                try loop(curr, Arrow.id, Arrow.id, ctx)
                catch
                    case failure if NonFatal(failure) =>
                        // the budget counts strict applications still on the Java stack, and a throw
                        // that reaches here left every one of them without its matching exit. The eval
                        // is back at its own frame, so the true depth is zero and the counter says
                        // otherwise; left uncorrected the leak accumulates over an extent's recoveries
                        // until the budget drains, and a drained budget is a fixed point rather than a
                        // slow path
                        Safepoint.reset(slot)
                        val resumed = recovered(failure)
                        // the region that recovered is still on the stack, holding the context to
                        // resume at; taking it here is what keeps the unwind allocation free
                        val outer = stack.ctx
                        stack.pop()
                        return guarded(resumed, outer)
            res match
                case suspend: Kyo.SuspendContextDefault[VX, CX, A, S] @unchecked =>
                    val nv = suspend.update(suspend.default)
                    Debugger.onContextDefault(suspend, nv)
                    val k = suspend.cont
                    guarded(k.head(nv, k.tail), Context.empty)
                case susp: Kyo.Suspend[?, ?, ?] =>
                    // an operation with no region left to answer it. Rejecting here keeps the failure
                    // at the operation that caused it: handed back, it is a node typed as a value, and
                    // the cast that discovers it fires arbitrarily far away. The arm below this one is
                    // the reason this is not the loop's business: a context read whose default nobody
                    // answered leaves the loop the same way and is answered, not rejected
                    bug(s"unhandled suspension: $susp")
                case res => res
            end match
        end guarded

        try guarded(v, Context.empty)
        finally
            Safepoint.restore(slot, saved)
            Stack.release(stack)
        end try
    end apply

    def answerLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, State, W](
        handler: Handler.HandlerLoop[I, O, E, A, B, S, State],
        r: Outcome2[State, O[W] < (E & S), B < S] < S,
        next: Arrow[O[W], A, E & S]
    ): Outcome2[State, O[W] < (E & S), B < S] =
        r match
            case r: Pending[Outcome2[State, O[W] < (E & S), B < S], S] @unchecked =>
                type Out = Outcome2[State, O[W] < (E & S), B < S]
                def outcome[D, S2](
                    self: Arrow[Out, B, S],
                    out: Out < S2,
                    cont: Arrow[B, D, S2]
                ): D < (S & S2) =
                    out match
                        case kyo: Pending[Out, S2] @unchecked =>
                            Effect.defer(kyo, self, cont)
                        case out: Loop.Continue2[State, O[W] < (E & S)] @unchecked =>
                            Kyo.handle[E, A, B, S, State](
                                out._2.chain(next),
                                handler,
                                out._1
                            ).chain(cont)
                        case out =>
                            // a done outcome is its payload in the union representation
                            Nested.unnest[B < S](out).chain(cont)
                val out: B < S =
                    r match
                        case rs: Kyo.SuspendArrow[IY, OY, EY, VY, Out, S] @unchecked
                            if rs.cont.isInstanceOf[Arrow.Id[?]] =>
                            val rsx: Kyo.SuspendArrow[IY, OY, EY, VY, Out, S] = rs
                            // one allocation fulfilling both roles: the rebuilt suspension and its outcome dispatch
                            new Kyo.SuspendArrowTransform[IY, OY, EY, VY, B, S]:
                                def tag   = rsx.tag
                                def input = rsx.input
                                def cont  = this
                                override def apply[D, S2](out0: OY[VY] < S2, cont2: Arrow[B, D, S2]) =
                                    // cont eq Id pins the suspension's answer type OY[VY] to the outcome; the
                                    // higher-kinded pin has no pattern spelling, so the arrival and the
                                    // self-reference are re-typed by that identity
                                    outcome(this.asInstanceOf[Arrow[Out, B, S]], out0.asInstanceOf[Out < S2], cont2)
                            end new
                        case _ =>
                            // one allocation fulfilling both roles: the dispatch record and its own transform,
                            // the rescue shape: the whole outcome computation is the value and the record is
                            // the arrow that settles it
                            new Kyo.DeferTransform[Out, B, S]:
                                def value = r
                                def contA = this
                                def contB = Arrow.id
                                override def apply[D, S2](out0: Out < S2, cont2: Arrow[B, D, S2]) =
                                    outcome(this, out0, cont2)
                            end new
                // a done outcome is its payload in the union representation
                Nested.unnest[Out](out)
            case r =>
                Nested.unnest[Outcome2[State, O[W] < (E & S), B < S]](r)
    end answerLoop

    type AX
    type Y
    type IX[_]
    type OX[_]
    type EX <: ArrowEffect[IX, OX]
    type VX
    type CX <: ContextEffect[VX]
    type IY[_]
    type OY[_]
    type EY <: ArrowEffect[IY, OY]
    type VY
end Eval
