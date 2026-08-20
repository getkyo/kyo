package kyo.kernel.internal

import kyo.Arrow
// unqualified so the inlined drive does not select these from Arrow.type at an expansion site
// outside package kyo, where they are not accessible. See the note in Pending.scala
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
                                    try h.run(kyo.input, k(_))
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
                                                            Effect.defer(r._1.map(k), h)
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
                                                        loop(r._1.map(k))
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
                                                            Effect.defer(r._2.map(k), HandlerLoopState(h, r._1))
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
                                                        loop(r._2.map(k))
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
        try
            loop(v.asInstanceOf[Any < Nothing]).asInstanceOf[A]
        catch
            case ex: Throwable =>
                // TODO is the exception tracing mechanism assuming the enrichment can happend only at the "end" in eval? That'd be incorrect but I guess we need to add Effect.catching. Design it and validate with me
                // every throw that carries frames has already had them reconstructed at the site that
                // ran the user code, so the boundary only rewrites the exception's own trace
                EffectTrace.splice(ex)
                throw ex
        finally
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
