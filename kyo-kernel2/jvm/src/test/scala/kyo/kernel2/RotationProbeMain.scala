package kyo.kernel2

// TEMPORARY experiment E3 for the rotation/handlers design: an executable model of
// the synthesis (typed region nodes + threaded evidence + rotation on park) with all
// five handler formats, validated program by program against the OLD kernel running
// in the same process (the old kernel is on the test classpath). Deleted once the
// design round closes; results recorded in kernel2-rotation-handlers-design.md.

import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.kernel.ArrowEffect as OldArrow
import kyo.kernel.ContextEffect as OldContext
import kyo.kernel.Effect as OldEffect
import scala.language.implicitConversions

object RotationProbeMain:

    /** The model: the design's execution semantics, small enough to inspect.
      *
      * Values are trees (the model's FlatMap plays the role of kernel2's fused cont; the drive's frame stack is the fused chain). Regions
      * are typed nodes. The evidence environment threads through the drive: entering a region extends it, leaving restores it. An operation
      * resolves against the evidence at its own execution point:
      *
      *   - Resume format: the clause runs immediately at the site, under the environment captured at region entry (the Under bracket), no
      *     continuation exists at any point.
      *   - Stop format: the drive unwinds to the owning region, discarding the remainder without capturing it.
      *   - Cont/First/Loop formats: the park walks outward to the owning region; every frame crossed contributes to the captured remainder,
      *     and region/guard/bracket frames re-wrap themselves around it (the rotation law), so re-entry re-establishes evidence and guards.
      *
      * Typing discipline: handlers store AND invoke clauses at their public types. The erased boundary is exactly two currencies, both in
      * `MHandler`'s final bridge methods: the tag-keyed input recovery (the drive matched the tag first) and the trampoline currency of the
      * accumulated remainder. Inside `onPark`/`onComplete` everything type-checks.
      */
    object model:

        trait Eff[I[_], O[_]]

        sealed trait MK[+A, -S]:
            def map[B](f: A => B): MK[B, S]                      = flatMap(a => Pure(f(a)))
            def flatMap[B, S2](f: A => MK[B, S2]): MK[B, S & S2] = FlatMap(this, f)

        final case class Pure[+A](value: A)                                          extends MK[A, Any]
        final case class FlatMap[A, B, S](v: MK[A, S], f: A => MK[B, S])             extends MK[B, S]
        final case class Op[I[_], O[_], E <: Eff[I, O], C](tag: Tag[E], input: I[C]) extends MK[O[C], E]

        /** The region node: scope is structural, exactly the old kernel's wrapper nesting, with types. */
        final case class Handled[I[_], O[_], E <: Eff[I, O], A, B, S](
            inner: MK[A, E & S],
            handler: MHandler[I, O, E, A, B, S]
        ) extends MK[B, S]

        final case class Catching[A, S](inner: MK[A, S], rescue: Throwable => MK[A, S]) extends MK[A, S]

        /** The clause bracket: runs `inner` under a specific environment (a Resume clause's region-entry scope). */
        final case class Under[A, S](env: Evidence, inner: MK[A, S]) extends MK[A, S]

        sealed trait MHandler[I[_], O[_], E <: Eff[I, O], A, B, S]:
            def tag: Tag[E]

            /** completion step when the region's inner computation finishes */
            def onComplete(a: A): MK[B, S]

            /** park behavior at the owning region: operation input and captured remainder, at the handler's public types */
            def onPark(input: I[Any], k: O[Any] => MK[A, E & S]): MK[B, S]

            // The drive's currency boundary, all of it: the tag-keyed input recovery (the
            // drive matched `tag` before calling) and the trampoline currency of the walk's
            // accumulated remainder. The typed methods above never see an erased value.
            final def parkErased(input: Any, k: Any => MK[Any, Any]): MK[Any, Any] =
                onPark(input.asInstanceOf[I[Any]], o => k(o).asInstanceOf[MK[A, E & S]]).asInstanceOf[MK[Any, Any]]
            final def completeErased(a: Any): MK[Any, Any] =
                onComplete(a.asInstanceOf[A]).asInstanceOf[MK[Any, Any]]
            final def rewrapErased(inner: MK[Any, Any]): MK[Any, Any] =
                Handled[I, O, E, A, B, S](inner.asInstanceOf[MK[A, E & S]], this).asInstanceOf[MK[Any, Any]]
        end MHandler

        final case class HResume[I[_], O[_], E <: Eff[I, O], A, S](
            tag: Tag[E],
            clause: [C] => I[C] => MK[O[C], E & S]
        ) extends MHandler[I, O, E, A, A, S]:
            def onComplete(a: A): MK[A, S] = Pure(a)
            def onPark(input: I[Any], k: O[Any] => MK[A, E & S]): MK[A, S] =
                Handled[I, O, E, A, A, S](clause[Any](input).flatMap(k), this)
            final def siteErased(input: Any): MK[Any, Any] =
                clause[Any](input.asInstanceOf[I[Any]]).asInstanceOf[MK[Any, Any]]
        end HResume

        final case class HCont[I[_], O[_], E <: Eff[I, O], A, S](
            tag: Tag[E],
            clause: [C] => (I[C], O[C] => MK[A, E & S]) => MK[A, E & S]
        ) extends MHandler[I, O, E, A, A, S]:
            def onComplete(a: A): MK[A, S] = Pure(a)
            def onPark(input: I[Any], k: O[Any] => MK[A, E & S]): MK[A, S] =
                Handled[I, O, E, A, A, S](clause[Any](input, k), this)
        end HCont

        final case class HStop[I[_], O[_], E <: Eff[I, O], A, S](
            tag: Tag[E],
            clause: [C] => I[C] => MK[A, E & S]
        ) extends MHandler[I, O, E, A, A, S]:
            def onComplete(a: A): MK[A, S] = Pure(a)
            def onPark(input: I[Any], k: O[Any] => MK[A, E & S]): MK[A, S] =
                // unreachable: the drive's stop unwind discards instead of capturing
                Handled[I, O, E, A, A, S](clause[Any](input), this)
            final def stopErased(input: Any): MK[Any, Any] =
                Handled[I, O, E, A, A, S](clause[Any](input.asInstanceOf[I[Any]]), this).asInstanceOf[MK[Any, Any]]
        end HStop

        final case class HFirst[I[_], O[_], E <: Eff[I, O], A, B, S](
            tag: Tag[E],
            clause: [C] => (I[C], O[C] => MK[A, E & S]) => MK[B, S],
            done: A => MK[B, S]
        ) extends MHandler[I, O, E, A, B, S]:
            def onComplete(a: A): MK[B, S] = done(a)
            def onPark(input: I[Any], k: O[Any] => MK[A, E & S]): MK[B, S] =
                clause[Any](input, k) // shallow: the handler leaves
        end HFirst

        sealed trait LOut[+State, +A, +B]
        final case class LContinue[State, A](state: State, next: A) extends LOut[State, A, Nothing]
        final case class LDone[B](value: B)                         extends LOut[Nothing, Nothing, B]

        final case class HLoop[I[_], O[_], E <: Eff[I, O], A, B, S, State](
            tag: Tag[E],
            state: State,
            clause: [C] => (I[C], State, O[C] => MK[A, E & S]) => MK[LOut[State, MK[A, E & S], B], S],
            done: (State, A) => MK[B, S]
        ) extends MHandler[I, O, E, A, B, S]:
            def onComplete(a: A): MK[B, S] = done(state, a)
            def onPark(input: I[Any], k: O[Any] => MK[A, E & S]): MK[B, S] =
                clause[Any](input, state, k).flatMap {
                    case LContinue(st, next) => Handled[I, O, E, A, B, S](next, HLoop[I, O, E, A, B, S, State](tag, st, clause, done))
                    case LDone(b)            => Pure(b)
                }
        end HLoop

        /** One evidence entry: the handler plus the environment at its region's entry. `clauseEnv` ties the deep-handler knot: the clause
          * runs under the entry environment extended with the region itself, so effects of the region's own tag inside a clause are
          * interpreted by the same handler while everything else resolves at the scope OUTSIDE the region.
          */
        final class REntry(val handler: MHandler[?, ?, ?, ?, ?, ?], val entryEnv: Evidence):
            lazy val clauseEnv: Evidence = entryEnv.set(handler.tag.erased, this)

        /** E2b's array shape: one final class, innermost-last linear scan, reference-first tag comparison. */
        final class Evidence private (tags: Array[AnyRef], entries: Array[REntry]):
            def set(tag: Tag[Any], entry: REntry): Evidence =
                val n  = tags.length
                val t2 = java.util.Arrays.copyOf(tags, n + 1)
                val e2 = java.util.Arrays.copyOf(entries, n + 1)
                t2(n) = tag.asInstanceOf[AnyRef]
                e2(n) = entry
                new Evidence(t2, e2)
            end set
            def resolve(tag: Tag[Any]): Maybe[REntry] =
                val key = tag.asInstanceOf[AnyRef]
                var i   = tags.length - 1 // innermost wins
                while i >= 0 do
                    val t = tags(i)
                    if (t eq key) || t.equals(key) then return Maybe(entries(i))
                    i -= 1
                end while
                Maybe.Absent
            end resolve
        end Evidence
        object Evidence:
            val empty = new Evidence(Array.empty, Array.empty)

        /** An operation that reached the root with no evidence: the boundary park (what handlePartial hands the fiber runtime). */
        final case class Parked(tag: Tag[Any], input: Any, k: Any => MK[Any, Any])

        // carries a boundary park out of the drive loop; never rescued by Catching guards
        final case class ParkSignal(parked: Parked) extends RuntimeException with scala.util.control.NoStackTrace

        def tagMatches(a: Tag[Any], b: Tag[Any]): Boolean =
            val x = a.asInstanceOf[AnyRef]
            val y = b.asInstanceOf[AnyRef]
            (x eq y) || x.equals(y)
        end tagMatches

        sealed trait Frm
        final case class FCont(f: Any => MK[Any, Any])                                 extends Frm
        final case class FRegion(handler: MHandler[?, ?, ?, ?, ?, ?], saved: Evidence) extends Frm
        final case class FGuard(rescue: Throwable => MK[Any, Any])                     extends Frm
        final case class FUnder(installed: Evidence, saved: Evidence)                  extends Frm

        def drive[A, S](v0: MK[A, S]): A =
            drivePartial(v0, Evidence.empty) match
                case Right(a) => a
                case Left(p)  => throw new IllegalStateException("unhandled effect at root: " + p.tag)

        def drivePartial[A, S](v0: MK[A, S], boundary: Evidence): Either[Parked, A] =
            var cur: MK[Any, Any]      = v0.asInstanceOf[MK[Any, Any]] // trampoline currency
            var env                    = boundary
            var stack: List[Frm]       = Nil
            var out: Either[Parked, A] = null

            // the park walk: pops frames outward accumulating the remainder; region, guard and
            // bracket frames re-wrap themselves around it (rotation); the owning region's handler
            // receives the result, or the walk reaches the root and signals a boundary park
            def parkTo(op: Op[?, ?, ?, ?], t: Tag[Any], mustMatch: Boolean): Unit =
                var k: Any => MK[Any, Any] = a => Pure(a)
                var s                      = stack
                var settled                = false
                while !settled do
                    s match
                        case Nil =>
                            if mustMatch then throw new IllegalStateException("park: owning region missing for " + t)
                            stack = Nil
                            throw ParkSignal(Parked(t, op.input, k))
                        case f :: rest =>
                            s = rest
                            f match
                                case FCont(fn) =>
                                    val k0 = k
                                    k = a => FlatMap(k0(a), fn)
                                case FUnder(installed, saved) =>
                                    val k0 = k
                                    k = a => Under(installed, k0(a))
                                    env = saved
                                case FGuard(rescue) =>
                                    val k0 = k
                                    k = a => Catching(k0(a), rescue)
                                case FRegion(h, saved) =>
                                    env = saved
                                    if tagMatches(h.tag.erased, t) then
                                        cur = h.parkErased(op.input, k)
                                        settled = true
                                    else
                                        val k0 = k
                                        k = a => h.rewrapErased(k0(a))
                                    end if
                            end match
                end while
                stack = s
            end parkTo

            // the stop unwind: discards to the owning region without capturing anything;
            // crossed regions and guards are dropped, their completion steps never run
            def stopTo(hs: HStop[?, ?, ?, ?, ?], op: Op[?, ?, ?, ?], t: Tag[Any]): Unit =
                var s    = stack
                var done = false
                while !done do
                    s match
                        case Nil => throw new IllegalStateException("stop: owning region missing")
                        case f :: rest =>
                            s = rest
                            f match
                                case FRegion(h2, saved) =>
                                    env = saved
                                    if tagMatches(h2.tag.erased, t) then
                                        cur = hs.stopErased(op.input)
                                        done = true
                                case FUnder(_, saved) => env = saved
                                case _                => ()
                            end match
                end while
                stack = s
            end stopTo

            // the throw unwind: to the nearest guard; the rescue is DEFERRED through a FlatMap so
            // a throwing rescue lands back in the drive's try and reaches the remaining guards
            def unwind(ex: Throwable): Unit =
                var s     = stack
                var found = false
                while !found do
                    s match
                        case Nil => throw ex
                        case f :: rest =>
                            s = rest
                            f match
                                case FGuard(rescue) =>
                                    cur = FlatMap[Throwable, Any, Any](Pure(ex), rescue)
                                    found = true
                                case FRegion(_, saved) => env = saved
                                case FUnder(_, saved)  => env = saved
                                case _                 => ()
                            end match
                end while
                stack = s
            end unwind

            def step(): Unit =
                cur match
                    case Pure(a) =>
                        stack match
                            case Nil => out = Right(a.asInstanceOf[A])
                            case f :: rest =>
                                stack = rest
                                f match
                                    case FCont(fn)         => cur = fn(a)
                                    case FRegion(h, saved) => env = saved; cur = h.completeErased(a)
                                    case FGuard(_)         => () // value passes through, the guard expires
                                    case FUnder(_, saved)  => env = saved
                                end match
                    case fm: FlatMap[?, ?, ?] =>
                        stack = FCont(fm.f.asInstanceOf[Any => MK[Any, Any]]) :: stack
                        cur = fm.v.asInstanceOf[MK[Any, Any]]
                    case h: Handled[?, ?, ?, ?, ?, ?] =>
                        val entry = new REntry(h.handler, env)
                        stack = FRegion(h.handler, env) :: stack
                        env = entry.clauseEnv
                        cur = h.inner.asInstanceOf[MK[Any, Any]]
                    case c: Catching[?, ?] =>
                        stack = FGuard(c.rescue.asInstanceOf[Throwable => MK[Any, Any]]) :: stack
                        cur = c.inner.asInstanceOf[MK[Any, Any]]
                    case u: Under[?, ?] =>
                        stack = FUnder(u.env, env) :: stack
                        env = u.env
                        cur = u.inner.asInstanceOf[MK[Any, Any]]
                    case op: Op[?, ?, ?, ?] =>
                        val t = op.tag.erased
                        env.resolve(t) match
                            case Maybe.Present(entry) =>
                                entry.handler match
                                    case hr: HResume[?, ?, ?, ?, ?] =>
                                        // in place: no park, no capture; the clause runs under the
                                        // region's entry scope via the Under bracket
                                        stack = FUnder(entry.clauseEnv, env) :: stack
                                        env = entry.clauseEnv
                                        cur = hr.siteErased(op.input)
                                    case hs: HStop[?, ?, ?, ?, ?] =>
                                        stopTo(hs, op, t)
                                    case _ =>
                                        // Cont/First/Loop: park to the owning region with rotation
                                        parkTo(op, t, mustMatch = true)
                            case Maybe.Absent =>
                                parkTo(op, t, mustMatch = false)
                        end match
            end step

            while out == null do
                try step()
                catch
                    case ps: ParkSignal => out = Left(ps.parked)
                    case ex: Throwable  => unwind(ex)
            end while
            out
        end drivePartial

        // ==== the model's effects and helpers for the reference programs ====

        sealed trait MEnv extends Eff[Const[Unit], Const[Int]]
        sealed trait MCtx extends Eff[Const[Unit], Const[Int]]
        sealed trait MT   extends Eff[Const[Int], Const[Int]]
        sealed trait MT2  extends Eff[Const[Int], Const[Int]]
        sealed trait MS   extends Eff[Const[Int], Const[Int]]

        val menvTag = Tag[MEnv]
        val mctxTag = Tag[MCtx]
        val mtTag   = Tag[MT]
        val mt2Tag  = Tag[MT2]
        val msTag   = Tag[MS]

        def envRead: MK[Int, MEnv]    = Op[Const[Unit], Const[Int], MEnv, Any](menvTag, ())
        def mctx: MK[Int, MCtx]       = Op[Const[Unit], Const[Int], MCtx, Any](mctxTag, ())
        def mt(i: Int): MK[Int, MT]   = Op[Const[Int], Const[Int], MT, Any](mtTag, i)
        def mt2(i: Int): MK[Int, MT2] = Op[Const[Int], Const[Int], MT2, Any](mt2Tag, i)
        def msop(i: Int): MK[Int, MS] = Op[Const[Int], Const[Int], MS, Any](msTag, i)

        /** Env derived from the arrow machinery, the gist's derivation: a Resume-format handler answering with the bound value. */
        def envRun[A, S](value: Int)(v: MK[A, MEnv & S]): MK[A, S] =
            Handled[Const[Unit], Const[Int], MEnv, A, A, S](
                v,
                HResume[Const[Unit], Const[Int], MEnv, A, S](menvTag, [C] => (_: Unit) => Pure(value))
            )

    end model

    /** The old kernel, same process, as the reference semantics. */
    object oldref:

        given Frame = Frame.internal

        sealed trait OEnv extends OldContext[Int]
        sealed trait OCtx extends OldArrow[kyo.Const[Unit], kyo.Const[Int]]
        sealed trait OT   extends OldArrow[kyo.Const[Int], kyo.Const[Int]]
        sealed trait OT2  extends OldArrow[kyo.Const[Int], kyo.Const[Int]]
        sealed trait OS   extends OldArrow[kyo.Const[Int], kyo.Const[Int]]

        def oenv: kyo.kernel.<[Int, OEnv]       = OldContext.suspend(Tag[OEnv])
        def octx: kyo.kernel.<[Int, OCtx]       = OldArrow.suspend[Any](Tag[OCtx], ())
        def ot(i: Int): kyo.kernel.<[Int, OT]   = OldArrow.suspend[Any](Tag[OT], i)
        def ot2(i: Int): kyo.kernel.<[Int, OT2] = OldArrow.suspend[Any](Tag[OT2], i)
        def osop(i: Int): kyo.kernel.<[Int, OS] = OldArrow.suspend[Any](Tag[OS], i)

        def pureOld[A2](a: A2): kyo.kernel.<[A2, Any] = a

    end oldref

    var failures = 0

    def check(name: String)(m: => Any, r: => Any): Unit =
        def capture(body: => Any): Any =
            try body
            catch case ex: Throwable => s"thrown ${ex.getClass.getSimpleName}: ${ex.getMessage}"
        val mv = capture(m)
        val rv = capture(r)
        val ok = mv.equals(rv) // universal equals: the build compiles with strict equality
        if !ok then failures += 1
        println(f"$name%-28s model=$mv%-34s ref=$rv%-34s ${if ok then "OK" else "MISMATCH"}")
    end check

    def main(args: Array[String]): Unit =
        import model.*
        import oldref.*
        import oldref.given

        // p1: THE red clause-scope test: a clause's env read resolves OUTSIDE its own region
        check("p1-clause-scope-outer")(
            drive(envRun(3)(
                Handled(
                    mctx.map(_ + 1),
                    HResume[Const[Unit], Const[Int], MCtx, Int, MEnv](mctxTag, [C] => (_: Unit) => envRead.map(_ * 2))
                )
            )),
            OldContext.handle(Tag[OEnv], 3)(
                OldArrow.handle(Tag[OCtx], octx.map(_ + 1))(
                    [C] => (_, cont) => oenv.map(v => cont(v * 2))
                )
            ).eval
        )

        // p2: a rebind INSIDE the region must not leak into the clause; reads inside see it
        check("p2-clause-scope-rebind")(
            drive(envRun(100)(
                Handled(
                    envRun(42)(mctx.flatMap(x => envRead.map(e => x * 1000 + e))),
                    HResume[Const[Unit], Const[Int], MCtx, Int, MEnv](mctxTag, [C] => (_: Unit) => envRead.map(_ * 2))
                )
            )),
            OldContext.handle(Tag[OEnv], 100)(
                OldArrow.handle(Tag[OCtx], OldContext.handle(Tag[OEnv], 42)(octx.flatMap(x => oenv.map(e => x * 1000 + e))))(
                    [C] => (_, cont) => oenv.map(v => cont(v * 2))
                )
            ).eval
        )

        // p3: a throw inside the guarded region is caught
        check("p3-catch-inside")(
            drive(Catching(Pure(1).map(x => (throw new RuntimeException("boom")): Int), _ => Pure(-1))),
            OldEffect.catching(pureOld(1).map(x => (throw new RuntimeException("boom")): Int))(_ => -1).eval
        )

        // p4: a throw OUTSIDE the guarded region escapes (kernel2's flat chain wrongly catches this today)
        check("p4-catch-outside-escapes")(
            drive(Catching(Pure(1), _ => Pure(-1)).map(_ => (throw new RuntimeException("boom")): Int)),
            OldEffect.catching(pureOld(1))(_ => -1).map(_ => (throw new RuntimeException("boom")): Int).eval
        )

        // p5: a throw after a park and resume inside the guarded region is still caught (guard rotation)
        check("p5-catch-after-resume")(
            drive(Handled(
                Catching(mt(1).map(x => if x > 0 then throw new RuntimeException("boom2") else x), _ => Pure(-7)),
                HCont[Const[Int], Const[Int], MT, Int, Any](mtTag, [C] => (in, k) => k(in + 4))
            )),
            OldArrow.handle(
                Tag[OT],
                OldEffect.catching(ot(1).map(x => if x > 0 then throw new RuntimeException("boom2") else x))(_ => -7)
            )([C] => (in, cont) => cont(in + 4)).eval
        )

        // p6: multi-shot: the captured continuation replays with consistent reads; the model's
        // capture is TYPED (HFirst returns the continuation as a first-class value, no casts)
        check("p6-multi-shot")(
            {
                val program = mctx.flatMap(a => envRead.map(b => a + b))
                val bound   = envRun(5)(program)
                val k = drive(Handled(
                    bound,
                    HFirst[Const[Unit], Const[Int], MCtx, Int, Int => MK[Int, MCtx], Any](
                        mctxTag,
                        [C] => (_, k) => Pure(k),
                        a => Pure((_: Int) => Pure(a)) // unreachable: the program always suspends
                    )
                ))
                val results = (drive(k(1)), drive(k(10)), drive(k(20)))
                results
            }, {
                val program       = octx.flatMap(a => oenv.map(b => a + b))
                val bound         = OldContext.handle(Tag[OEnv], 5)(program)
                var captured: Any = null
                val first = OldArrow.handle(Tag[OCtx], bound)(
                    [C] =>
                        (in, cont) =>
                            captured = (o: Int) => cont(o)
                            cont(1)
                ).eval
                val k = captured.asInstanceOf[Int => kyo.kernel.<[Int, Any]]
                (first, k(10).eval, k(20).eval)
            }
        )

        // p7: rotation across a foreign handler: an op parks past an unrelated region, which
        // re-wraps; after resumption the crossed region still handles its own ops
        check("p7-rotation-foreign")(
            drive(Handled(
                Handled[Const[Int], Const[Int], MT, Int, Int, MT2](
                    mt2(5).flatMap(x => mt(x + 1).map(_ * 10)),
                    HCont[Const[Int], Const[Int], MT, Int, MT2](mtTag, [C] => (in, k) => k(in + 100))
                ),
                HCont[Const[Int], Const[Int], MT2, Int, Any](mt2Tag, [C] => (in, k) => k(in * 2))
            )),
            OldArrow.handle(
                Tag[OT2],
                OldArrow.handle(Tag[OT], ot2(5).flatMap(x => ot(x + 1).map(_ * 10)))(
                    [C] => (in, cont) => cont(in + 100)
                )
            )([C] => (in, cont) => cont(in * 2)).eval
        )

        // p8: Stop discards the region remainder without capture, crossing (and dropping) an
        // inner Catching region; the region's outside continuation still runs
        check("p8-stop-discards")(
            drive(
                Handled(
                    Catching(msop(5).map(_ + 999), _ => Pure(-1)),
                    HStop[Const[Int], Const[Int], MS, Int, Any](msTag, [C] => (in: Int) => Pure(in * 3))
                ).map(_ + 1)
            ),
            OldArrow.handle(
                Tag[OS],
                OldEffect.catching(osop(5).map(_ + 999))(_ => -1)
            )([C] => (in, _) => pureOld(in * 3)).map(_ + 1).eval
        )

        // p9: the crown case: a Resume clause parks mid-clause on an outer handler, and its
        // post-resume env read still resolves at the clause's scope (Under rotation), while an
        // inner rebind stays invisible throughout
        check("p9-clause-park-resume")(
            drive(Handled(
                envRun(3)(
                    Handled(
                        envRun(42)(mctx),
                        HResume[Const[Unit], Const[Int], MCtx, Int, MEnv & MT](
                            mctxTag,
                            [C] => (_: Unit) => mt(7).flatMap(x => envRead.map(e => x * 1000 + e))
                        )
                    )
                ),
                HCont[Const[Int], Const[Int], MT, Int, Any](mtTag, [C] => (in, k) => k(in + 1))
            )),
            OldArrow.handle(
                Tag[OT],
                OldContext.handle(Tag[OEnv], 3)(
                    OldArrow.handle(Tag[OCtx], OldContext.handle(Tag[OEnv], 42)(octx))(
                        [C] => (_, cont) => ot(7).flatMap(x => oenv.map(e => cont(x * 1000 + e)))
                    )
                )
            )([C] => (in, cont) => cont(in + 1)).eval
        )

        // p10: the boundary park: no evidence in scope, the walk reaches the root and hands the
        // caller the operation plus the captured remainder (what handlePartial gives the runtime);
        // out-of-band resumption re-enters the re-wrapped regions. 105 is the passing
        // handlePartial suite expectation for this exact program shape.
        check("p10-boundary-partial")(
            {
                val bound = envRun(5)(mctx.flatMap(a => envRead.map(b => a + b)))
                drivePartial(bound, Evidence.empty) match
                    case Left(p)  => drive(p.k(100))
                    case Right(_) => "expected a park"
            },
            105
        )

        // p11: Loop format: state evolves per operation without mutating any node, done sees the
        // final state; the model's replacement node mirrors kernel2's replaceState
        check("p11-loop-state")(
            {
                val prog = mt(1).flatMap(a => mt(2).flatMap(b => mt(3).map(c => a * 100 + b * 10 + c)))
                drive(Handled(
                    prog,
                    HLoop[Const[Int], Const[Int], MT, Int, (Int, Int), Any, Int](
                        mtTag,
                        5,
                        [C] => (in, st, k) => Pure(LContinue(st + 1, k(in * st))),
                        (st, a) => Pure((st, a))
                    )
                ))
            }, {
                val prog = ot(1).flatMap(a => ot(2).flatMap(b => ot(3).map(c => a * 100 + b * 10 + c)))
                OldArrow.handleLoop(Tag[OT], 5, prog)(
                    [C] => (in, st, cont) => kyo.kernel.Loop.continue(st + 1, cont(in * st)),
                    (st: Int, a: Int) => pureOld((st, a))
                ).eval
            }
        )

        println(if failures == 0 then "ALL PROGRAMS AGREE" else s"$failures MISMATCH(ES)")
        if failures > 0 then throw new IllegalStateException(s"$failures mismatches against the old kernel")
    end main
end RotationProbeMain
