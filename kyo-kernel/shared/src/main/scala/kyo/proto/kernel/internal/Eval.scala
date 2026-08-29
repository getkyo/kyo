package kyo.proto.kernel.internal

import kyo.Frame
import kyo.Maybe
import kyo.bug
import kyo.proto.Arrow
import kyo.proto.Loop
import kyo.proto.Loop.Outcome2
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect
import kyo.proto.kernel.ContextEffect
import kyo.proto.kernel.Effect
import language.implicitConversions
import scala.util.control.NonFatal

object Eval:

    /** Runs the releases a computation still owes, for a holder giving up on resuming it: the abandonment signal reaches every region the
      * machine already installed, innermost first. Anything that never acquired, or is not a computation at all, is a no-op.
      */
    def release(v: Any): Unit =
        v match
            case p: Pending[?, ?] => p.release(Discarded)
            case _                => ()

    def apply[A, S](v: A < S): A < S =
        def loop[A, B, C, S](v: A < S, contA: Arrow[A, B, S], contB: Arrow[B, C, S], ctx: Context): C < S =
            Debugger.onLoop(v, contA, contB)
            v match
                case kyo: Kyo.Defer[AX, Y, A, S] @unchecked =>
                    loop(kyo.value, kyo.contA, kyo.contB.chain(contA.chain(contB)), ctx)
                case kyo: Kyo.SuspendContext[VX, CX, A, S] @unchecked if ctx.contains(kyo.tag) =>
                    val nv = kyo.update(ctx.apply[VX, CX](kyo.tag))
                    Debugger.onContext(kyo, nv)
                    val k = kyo.cont
                    loop(k.head(nv, k.tail), contA, contB, ctx.update[VX, CX](kyo.tag, nv))
                case kyo: Kyo.SuspendContextDefault[VX, CX, A, S] @unchecked if ctx.contains(kyo.tag) =>
                    val nv = kyo.update(ctx.apply[VX, CX](kyo.tag))
                    Debugger.onContext(kyo, nv)
                    val k = kyo.cont
                    loop(k.head(nv, k.tail), contA, contB, ctx.update[VX, CX](kyo.tag, nv))
                case kyo: Kyo.Suspend[EX, A, S] @unchecked =>
                    if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then kyo.asInstanceOf[C < S]
                    else if kyo.cont.isInstanceOf[Arrow.Id[?]] && (contA.isInstanceOf[Arrow.Id[?]] || contB.isInstanceOf[Arrow.Id[?]]) then
                        // a single live register absorbs into the free slot: only the copy allocates
                        kyo.withCont(kyo.cont.chain(contA.chain(contB)))
                    else
                        // two or more live continuations: reifying through chains would allocate a copy
                        // plus a Chain per composition. One allocation fulfills every role instead: the
                        // reified suspension captures its continuation and the registers, and delivery
                        // composes by nested application; arriving through head and tail with an identity
                        // continuation, the chain law composes without allocating
                        kyo match
                            case sa: Kyo.SuspendArrow[IX, OX, EX, VX, A, S] @unchecked =>
                                val sax: Kyo.SuspendArrow[IX, OX, EX, VX, A, S] = sa
                                val k0                                          = sax.cont
                                val cA                                          = contA
                                val cB                                          = contB
                                new Kyo.SuspendArrow[IX, OX, EX, VX, C, S] with Arrow.Transform[OX[VX], C, S]:
                                    def tag   = sax.tag
                                    def input = sax.input
                                    def cont  = this
                                    override def apply[D, S2](x: OX[VX] < S2, c2: Arrow[C, D, S2]) =
                                        x match
                                            case p: Pending[OX[VX], S2] @unchecked => Effect.defer(p, this, c2)
                                            case _                                 => cA(k0(x, Arrow.id), cB.chain(c2))
                                end new
                            case sc: Kyo.SuspendContext[VX, CX, A, S] @unchecked =>
                                val scx: Kyo.SuspendContext[VX, CX, A, S] = sc
                                val k0                                    = scx.cont
                                val cA                                    = contA
                                val cB                                    = contB
                                new Kyo.SuspendContext[VX, CX, C, S] with Arrow.Transform[VX, C, S]:
                                    def tag           = scx.tag
                                    def update(v: VX) = scx.update(v)
                                    def cont          = this
                                    override def apply[D, S2](x: VX < S2, c2: Arrow[C, D, S2]) =
                                        x match
                                            case p: Pending[VX, S2] @unchecked => Effect.defer(p, this, c2)
                                            case _                             => cA(k0(x, Arrow.id), cB.chain(c2))
                                end new
                            case sd: Kyo.SuspendContextDefault[VX, CX, A, S] @unchecked =>
                                val sdx: Kyo.SuspendContextDefault[VX, CX, A, S] = sd
                                val k0                                           = sdx.cont
                                val cA                                           = contA
                                val cB                                           = contB
                                new Kyo.SuspendContextDefault[VX, CX, C, S] with Arrow.Transform[VX, C, S]:
                                    def tag           = sdx.tag
                                    def default       = sdx.default
                                    def update(v: VX) = sdx.update(v)
                                    def cont          = this
                                    override def apply[D, S2](x: VX < S2, c2: Arrow[C, D, S2]) =
                                        x match
                                            case p: Pending[VX, S2] @unchecked => Effect.defer(p, this, c2)
                                            case _                             => cA(k0(x, Arrow.id), cB.chain(c2))
                                end new
                    end if
                case kyo: Kyo.Handle[EX, AX, Y, A, S, VX] @unchecked =>
                    // TODO let's move to a separate method, not nested
                    def region[T](st: VX, v: T < (EX & S), cont: Arrow[T, AX, EX & S], ctx: Context): Y < S =
                        loop(v, cont, Arrow.id, ctx) match
                            case res: Kyo.Suspend[EX, AX, EX & S] @unchecked if !(res.tag.erased <:< kyo.handler.tag.erased) =>
                                Debugger.onForeign(res, kyo.handler)
                                // parameterized over the suspension's payload so each arm calls it with its own
                                // refined continuation, keeping the rebuilt applies typed at the true input
                                def reenter[P, D, S2](k0: Arrow[P, AX, EX & S], x: P < S2, cont2: Arrow[Y, D, S2]): D < (S & S2) =
                                    // the resumed application runs before the region re-installs, so the
                                    // extent's guard is carried here: a throw consults the same recover the
                                    // entry guard would, and the recovered outcome replaces the region
                                    // instead of re-entering it. Nothing inside the try is driven, so a
                                    // throw consults at most once
                                    try Kyo.handle[EX, AX, Y, S & S2, VX](k0.head(x, k0.tail), kyo.handler, st).chain(cont2)
                                    catch
                                        case ex if NonFatal(ex) =>
                                            val r = kyo.handler.recover(st, ex).getOrElse(throw ex)
                                            Debugger.onRecover(kyo.handler, ex)
                                            r.chain(cont2)
                                res match
                                    case sa: Kyo.SuspendArrow[IY, OY, EY, VY, AX, EX & S] @unchecked =>
                                        val sax: Kyo.SuspendArrow[IY, OY, EY, VY, AX, EX & S] = sa
                                        // one allocation fulfilling both roles: the rebuilt suspension and its re-handling transform
                                        new Kyo.SuspendArrow[IY, OY, EY, VY, Y, S] with Arrow.Transform[OY[VY], Y, S]:
                                            def tag   = sax.tag
                                            def input = sax.input
                                            def cont  = this
                                            override def release(ex: Throwable): Unit =
                                                sax.release(ex)
                                                releaseRegion(kyo.handler, st, ex)
                                            override def apply[D, S2](x: OY[VY] < S2, cont2: Arrow[Y, D, S2]) =
                                                x match
                                                    case kyo: Pending[OY[VY], S2] @unchecked =>
                                                        Effect.defer(kyo, this, cont2)
                                                    case _ =>
                                                        reenter(sax.cont, Nested.unnest[OY[VY]](x), cont2)
                                        end new
                                    case sc: Kyo.SuspendContext[VX, CX, AX, EX & S] @unchecked =>
                                        val scx: Kyo.SuspendContext[VX, CX, AX, EX & S] = sc
                                        // one allocation fulfilling both roles: the rebuilt suspension and its re-handling transform
                                        new Kyo.SuspendContext[VX, CX, Y, S] with Arrow.Transform[VX, Y, S]:
                                            def tag           = scx.tag
                                            def update(v: VX) = scx.update(v)
                                            def cont          = this
                                            override def release(ex: Throwable): Unit =
                                                scx.release(ex)
                                                releaseRegion(kyo.handler, st, ex)
                                            override def apply[D, S2](x: VX < S2, cont2: Arrow[Y, D, S2]) =
                                                x match
                                                    case kyo: Pending[VX, S2] @unchecked =>
                                                        Effect.defer(kyo, this, cont2)
                                                    case _ =>
                                                        reenter(scx.cont, Nested.unnest[VX](x), cont2)
                                        end new
                                    case sd: Kyo.SuspendContextDefault[VX, CX, AX, EX & S] @unchecked =>
                                        val sdx: Kyo.SuspendContextDefault[VX, CX, AX, EX & S] = sd
                                        // one allocation fulfilling both roles: the rebuilt suspension and its re-handling transform
                                        new Kyo.SuspendContextDefault[VX, CX, Y, S] with Arrow.Transform[VX, Y, S]:
                                            def tag           = sdx.tag
                                            def default       = sdx.default
                                            def update(v: VX) = sdx.update(v)
                                            def cont          = this
                                            override def release(ex: Throwable): Unit =
                                                sdx.release(ex)
                                                releaseRegion(kyo.handler, st, ex)
                                            override def apply[D, S2](x: VX < S2, cont2: Arrow[Y, D, S2]) =
                                                x match
                                                    case kyo: Pending[VX, S2] @unchecked =>
                                                        Effect.defer(kyo, this, cont2)
                                                    case _ =>
                                                        reenter(sdx.cont, Nested.unnest[VX](x), cont2)
                                        end new
                                end match
                            case suspend: Kyo.SuspendArrow[IX, OX, EX, VX, AX, EX & S] @unchecked =>
                                Debugger.onHandle(suspend, kyo.handler, st)
                                val next = suspend.cont
                                kyo.handler match
                                    case handler: Handler.HandlerCont[IX, OX, EX, AX, Y, S] @unchecked =>
                                        val r = handler.answer(suspend.input, next)
                                        Debugger.onResult(r)
                                        region(st, r, Arrow.id, ctx)
                                    case handler: Handler.HandlerLoop[IX, OX, EX, AX, Y, S, VX] @unchecked =>
                                        val o = handler.answer(st, suspend.input, next)
                                        Debugger.onResult(o)
                                        o match
                                            case o: Loop.Continue2[VX, OX[VX] < (EX & S)] @unchecked =>
                                                region(o._1, o._2, next, ctx)
                                            case o =>
                                                // a done outcome is its payload in the union representation
                                                Nested.unnest[Y < S](o)
                                        end match
                                    case _ =>
                                        bug(s"unhandled: ${kyo.handler}")
                                end match
                            case res: Pending[AX, S] @unchecked =>
                                bug(s"unhandled: $res")
                            case res: AX @unchecked =>
                                kyo.handler.done(st, res)
                    end region
                    // a context binding resolves at installation: it derives from whatever the
                    // enclosing scope binds for its tag, and a re-installed region derives again
                    // from wherever it stands
                    var st0 = kyo.state
                    val bound = kyo.handler match
                        case h: Handler.HandlerContext[VX, CX, AX, Y, S] @unchecked =>
                            val hc: Handler.HandlerContext[VX, CX, AX, Y, S] = h
                            st0 = hc.resolve(Maybe.when(ctx.contains(hc.tag))(ctx[VX, CX](hc.tag)))
                            ctx.update(hc.tag, st0)
                        case _ => ctx
                    Debugger.onRegionEnter(kyo.handler, st0)
                    // the extent's guard: a NonFatal throw anywhere under the region consults the
                    // handler once, with the state the region was installed with. A recovered
                    // computation replaces the region's outcome and takes the same continuation a
                    // normal result would; a decline keeps the failure unwinding through the
                    // enclosing regions' own guards
                    val res =
                        try region(st0, kyo.value, Arrow.id, bound)
                        catch
                            case ex if NonFatal(ex) =>
                                val r = kyo.handler.recover(st0, ex).getOrElse(throw ex)
                                Debugger.onRecover(kyo.handler, ex)
                                r
                    Debugger.onRegionExit(kyo.handler, res)
                    loop(res, kyo.cont, contA.chain(contB), ctx)
                case res =>
                    if contA.isInstanceOf[Arrow.Id[?]] && contB.isInstanceOf[Arrow.Id[?]] then
                        res.asInstanceOf[C < S]
                    else
                        contA match
                            case contA: Arrow.Chain[A, Any, B, S] @unchecked =>
                                loop(res, contA.a, contA.b.chain(contB), ctx)
                            case _ =>
                                loop(contA(res.asInstanceOf[A], contB), Arrow.id, Arrow.id, ctx)
            end match
        end loop
        def run(v: A < S): A < S =
            loop(v, Arrow.id, Arrow.id, Context.empty) match
                case suspend: Kyo.SuspendContextDefault[VX, CX, A, S] @unchecked =>
                    val nv = suspend.update(suspend.default)
                    Debugger.onContextDefault(suspend, nv)
                    val k = suspend.cont
                    run(k.head(nv, k.tail))
                case res =>
                    res
        run(v)
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
                            new Kyo.SuspendArrow[IY, OY, EY, VY, B, S] with Arrow.Transform[OY[VY], B, S]:
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
                            new Kyo.Defer[Out, B, B, S] with Arrow.Transform[Out, B, S]:
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
