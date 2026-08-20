package kyo.kernel.internal

import java.util.concurrent.atomic.AtomicBoolean
import kyo.Arrow
// unqualified so the inlined drive does not select these from Arrow.type at an expansion site
// outside package kyo, where they are not accessible. See the note in Pending.scala
import kyo.Arrow.Bracket
import kyo.Arrow.Chain
import kyo.Arrow.Transform
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.*
import kyo.Tag
import kyo.bug
import kyo.discard
import kyo.kernel.*
import kyo.kernel.Loop.Outcome
import kyo.kernel.internal.Handler.HandlerCont
import kyo.kernel.internal.Handler.HandlerLoop
import kyo.kernel.internal.Handler.HandlerLoopState
import kyo.kernel.internal.Kyo.Defer
import kyo.kernel.internal.Kyo.Handle
import kyo.kernel.internal.Kyo.Suspend
import scala.annotation.nowarn
import scala.annotation.static
import scala.annotation.tailrec

/** A release that has not run yet, together with the arrow that runs it.
  *
  * One object fills three roles. It is the entry a stack holds, so a drive that throws or abandons a
  * continuation can still release. It is the arrow spliced after the bracket's `use`, so a drive that
  * completes releases at the point the use ends rather than at the boundary. And it is the flag that makes
  * those two paths exclusive, which they have to be because both can be reached for the same resource.
  *
  * Atomic rather than a plain `var`: a captured continuation can be resumed on one thread while the drive
  * that created it drains on another, so the two paths genuinely race.
  *
  * The flag is set before the release runs, so a release that throws still counts as run and the drain does
  * not retry it.
  */
final private[kyo] class Finalizer[A](release: Arrow[A, Any, Any], resource: A)
    extends AtomicBoolean with Transform[Any, Any, Any]:

    def frame = Frame.internal

    def run(): Unit =
        if compareAndSet(false, true) then discard(Eval(release(resource)))

    override def apply(v: Any): Any < Any =
        run()
        v

    // defers on a pending input: while the value has not settled the use has not finished, and the release
    // is owed only once it has
    def apply[C, S2](v: Any < S2, next: Arrow[Any, C, S2]): C < S2 =
        v match
            case kyo: Kyo[Any, S2] @unchecked => Effect.defer(kyo, this, next)
            case _                            => next(apply(Nested.unnest[Any](v)), Arrow.id)
end Finalizer

object Eval:

    type IX[_]
    type OX[_]
    type EX <: ArrowEffect[IX, OX]
    type CX
    type AX
    type BX
    type StateX

    private inline given Frame = Frame.internal

    // not inline: the drive is ~555 instructions and HotSpot refuses to inline it at any call site, so an
    // inline definition bought nothing at runtime and emitted a private copy of the whole interpreter per
    // call site. PendingTest alone carried 132 of them.
    //
    // not @static either, though the rest of the boundary primitives are. Scala.js cannot emit a static
    // method that contains a lambda: genSJSIR fails with "Cannot resolve delambdafy target method $anonfun"
    // on the eta-expansion below. The other @static methods in this package hold local defs and anonymous
    // classes, never lambdas, which is why they compile. The module load this costs is one getstatic
    @nowarn("msg=anonymous")
    def apply[A, S](v: A < S): A =
        val stack = Stack.borrow()

        @tailrec def loop(curr: Any < Nothing): Any =
            curr match
                case kyo: Defer[?, ?, A, S] @unchecked =>
                    stack.push(kyo.contB)
                    stack.push(kyo.contA)
                    loop(kyo.value)
                case kyo: Suspend[IX, OX, EX, CX, A, S] @unchecked =>
                    stack.push(kyo.cont)
                    val pos = stack.find(kyo.tag)
                    if pos < 0 then
                        try bug(s"unhandled suspension: ${kyo.tag}")
                        catch
                            case ex: Throwable =>
                                EffectTrace.attach(ex, kyo, Arrow.id[Any], stack)
                                throw ex
                    else
                        stack.handler(pos) match
                            case h: HandlerCont[IX, OX, EX, AX, ?, S] @unchecked =>
                                val k = stack.dump[OX[CX], AX, EX & S](pos)
                                val next =
                                    try h.run(kyo.input, k)
                                    catch
                                        case ex: Throwable =>
                                            EffectTrace.attach(ex, kyo, k, stack)
                                            throw ex
                                loop(next)
                            case h: HandlerLoop[IX, OX, EX, AX, BX, S] @unchecked =>
                                val ran =
                                    try h.run(kyo.input)
                                    catch
                                        case ex: Throwable =>
                                            EffectTrace.attach(ex, kyo, Arrow.id[Any], stack)
                                            throw ex
                                ran match
                                    case clause: Kyo[Loop.Outcome[OX[CX] < (EX & S), BX], S] @unchecked =>
                                        val k = stack.dump(pos)
                                        discard(stack.pop())
                                        loop(
                                            new Defer[Loop.Outcome[OX[CX] < (EX & S), BX], BX, BX, EX & S]
                                                with Transform[Loop.Outcome[OX[CX] < (EX & S), BX], BX, EX & S]:
                                                def frame = Frame.internal
                                                def value = clause
                                                def contA = this
                                                def contB = Arrow.id[BX]
                                                override def apply(o: Loop.Outcome[OX[CX] < (EX & S), BX]) =
                                                    o match
                                                        case r: Loop.Continue[OX[CX] < (EX & S)] @unchecked =>
                                                            Effect.defer(r._1.map(a => k(a)), h)
                                                        case v => v.asInstanceOf[BX]
                                                def apply[D, S2](o: Loop.Outcome[OX[CX] < (EX & S), BX] < S2, next: Arrow[BX, D, S2])
                                                    : D < (EX & S & S2) =
                                                    o match
                                                        case kyo: Kyo[Loop.Outcome[OX[CX] < (EX & S), BX], S2] @unchecked =>
                                                            Effect.defer(kyo, this, next)
                                                        case _ => next(apply(Nested.unnest(o)), Arrow.id)
                                        )
                                    case o =>
                                        Nested.unnest[Any](o) match
                                            case r: Loop.Continue[OX[CX] < (EX & S)] @unchecked =>
                                                r._1 match
                                                    case _: Kyo[OX[CX], EX & S] @unchecked =>
                                                        val k = stack.dump(pos)
                                                        loop(r._1.map(a => k(a)))
                                                    case _ => loop(r._1)
                                            case _ =>
                                                stack.truncate(pos + 1)
                                                loop(o)
                                end match
                            case h: HandlerLoopState[IX, OX, EX, AX, BX, S, StateX] @unchecked =>
                                val s = stack.state(pos).getOrElse(h.initialState)
                                val ran =
                                    try h.run(s, kyo.input)
                                    catch
                                        case ex: Throwable =>
                                            EffectTrace.attach(ex, kyo, Arrow.id[Any], stack)
                                            throw ex
                                ran match
                                    case clause: Kyo[Loop.Outcome2[StateX, OX[CX] < (EX & S), BX], S] @unchecked =>
                                        val k = stack.dump(pos)
                                        discard(stack.pop())
                                        loop(
                                            new Defer[Loop.Outcome2[StateX, OX[CX] < (EX & S), BX], BX, BX, EX & S]
                                                with Transform[Loop.Outcome2[StateX, OX[CX] < (EX & S), BX], BX, EX & S]:
                                                def frame = Frame.internal
                                                def value = clause
                                                def contA = this
                                                def contB = Arrow.id[BX]
                                                override def apply(o: Loop.Outcome2[StateX, OX[CX] < (EX & S), BX]) =
                                                    o match
                                                        case r: Loop.Continue2[StateX, OX[CX] < (EX & S)] @unchecked =>
                                                            Effect.defer(r._2.map(a => k(a)), HandlerLoopState(h, r._1))
                                                        case v => v.asInstanceOf[BX]
                                                def apply[D, S2](
                                                    o: Loop.Outcome2[StateX, OX[CX] < (EX & S), BX] < S2,
                                                    next: Arrow[BX, D, S2]
                                                ): D < (EX & S & S2) =
                                                    o match
                                                        case kyo: Kyo[Loop.Outcome2[StateX, OX[CX] < (EX & S), BX], S2] @unchecked =>
                                                            Effect.defer(kyo, this, next)
                                                        case _ => next(apply(Nested.unnest(o)), Arrow.id)
                                        )
                                    case o =>
                                        Nested.unnest[Any](o) match
                                            case r: Loop.Continue2[StateX, OX[CX] < (EX & S)] @unchecked =>
                                                stack.putState(pos, r._1)
                                                r._2 match
                                                    case _: Kyo[OX[CX], EX & S] @unchecked =>
                                                        val k = stack.dump(pos)
                                                        loop(r._2.map(a => k(a)))
                                                    case _ => loop(r._2)
                                                end match
                                            case _ =>
                                                stack.truncate(pos + 1)
                                                loop(o)
                                end match
                        end match
                    end if
                case kyo: Handle[EX, ?, ?, A, S] @unchecked =>
                    stack.push(kyo.cont)
                    stack.push(kyo.handler)
                    loop(kyo.value)
                case _ =>
                    val r = Nested.unnest[Any](curr)
                    if !stack.isEmpty then
                        val s = stack.state[StateX](0)
                        stack.pop() match
                            case h: HandlerLoopState[IX, OX, EX, AX, BX, S, StateX] @unchecked =>
                                val next =
                                    try h.apply(s.getOrElse(h.initialState), r.asInstanceOf[AX])
                                    catch
                                        case ex: Throwable =>
                                            EffectTrace.attach(ex, h, Arrow.id[Any], stack)
                                            throw ex
                                loop(next)
                            case h: Handler[EX, AX, BX, S] @unchecked =>
                                val tail = stack.dump[BX, Any, EX & S]()
                                val next =
                                    try h(curr.asInstanceOf[AX < (EX & S)], tail)
                                    catch
                                        case ex: Throwable =>
                                            EffectTrace.attach(ex, h, tail, stack)
                                            throw ex
                                loop(next)
                            case c: Chain[Any, ?, Any, EX & S] @unchecked =>
                                val tail = stack.dump[Any, Any, EX & S]()
                                val next =
                                    try c(curr, tail)
                                    catch
                                        case ex: Throwable =>
                                            EffectTrace.attach(ex, c, tail, stack)
                                            throw ex
                                loop(next)
                            case b: Bracket[Any, Any, EX & S] @unchecked =>
                                // the input is the acquired resource and it has settled by here, so the
                                // release is owed from this point on. Registering before `use` runs is what
                                // makes the throwing and the abandoning paths recoverable; the same object
                                // goes into the continuation, so a use that completes releases there rather
                                // than waiting for the drain
                                val tail     = stack.dump[Any, Any, EX & S]()
                                val resource = Nested.unnest[Any](curr)
                                val fin      = new Finalizer(b.release, resource)
                                stack.pushFinalizer(fin)
                                val next =
                                    try b.use(curr, fin.chain(tail))
                                    catch
                                        case ex: Throwable =>
                                            EffectTrace.attach(ex, b, tail, stack)
                                            throw ex
                                loop(next)
                            case head =>
                                val tail = stack.dump[Any, Any, EX & S]()
                                val next =
                                    try head.asInstanceOf[Arrow[Any, ?, EX & S]](curr, tail)
                                    catch
                                        case ex: Throwable =>
                                            EffectTrace.attach(ex, head, tail, stack)
                                            throw ex
                                loop(next)
                        end match
                    else r
                    end if
        end loop

        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        // recorded so the drain below can tell a drive that is leaving on an exception from one that is
        // completing, and attach a failing release to the former rather than replacing it
        var failure: Throwable | Null = null
        try
            loop(v.asInstanceOf[Any < Nothing]).asInstanceOf[A]
        catch
            case ex: Throwable =>
                failure = ex
                // TODO is the exception tracing mechanism assuming the enrichment can happend only at the "end" in eval? That'd be incorrect but I guess we need to add Effect.catching. Design it and validate with me
                // every throw that carries frames has already had them reconstructed at the site that
                // ran the user code, so the boundary only rewrites the exception's own trace
                EffectTrace.splice(ex)
                throw ex
        finally
            // before the stack is pooled, and outside the catch above, so a release still runs when the
            // drive is leaving on an exception. One whose use completed already ran through its arrow and
            // is a no-op here
            stack.drainFinalizers(failure)
            Stack.release(stack)
            Safepoint.restore(slot, saved)
        end try
    end apply

    /** Drives until the computation parks, handing back a value that resumes on a later drive.
      *
      * A slice ends on a preemption stop, on the caller's own stop function, or on an operation no handler in the slice answers, and the
      * value returned carries the regions above the park intact. Lands with the Bracket and Park work
      * (reviews/BRACKET-PARK-DESIGN.md), which is where the node that reifies a park is decided.
      *
      * Consumers waiting on it: the parked group in EvalTest, two cases in ArrowEffectTest, and
      * SafepointConcurrencyTest's stop-observability case.
      */
    // def partial[A, S](v: A < S, stop: () => Boolean = () => false): A < S

end Eval
