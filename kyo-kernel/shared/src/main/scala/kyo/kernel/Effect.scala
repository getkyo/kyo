package kyo.kernel

import kyo.Frame
import kyo.kernel.internal.*
import scala.annotation.nowarn

/** The base trait for all effects in the Kyo effect system.
  *
  * When code performs an effectful operation, instead of executing immediately, effects create a suspended computation that captures what
  * needs to be done. These suspended computations can then be interpreted in different ways through effect handlers.
  *
  * This suspension mechanism is the foundation of Kyo's effect system. It allows effectful code to be pure and composable - rather than
  * performing operations directly, code builds up a description of what operations should occur. This description can then be interpreted
  * by handlers that determine how the operations are actually executed.
  *
  * There are two kinds of effects:
  *   - [[ArrowEffect]] for suspended computations involving input/output transformations.
  *   - [[ContextEffect]] for suspended computations requiring contextual values.
  */
abstract class Effect private[kernel] ()

object Effect:

    // Builds a `Pending.Defer` node around a value and the continuations that run after it.
    def defer[A, B, S](v: A < S, cont: Arrow[A, B, S]): B < S =
        cont match
            case cont: Arrow.Chain[A, x, B, S] @unchecked =>
                new Pending.Defer[A, x, B, S]:
                    def value = v
                    def contA = cont.a
                    def contB = cont.b
            case _ =>
                new Pending.Defer[A, B, B, S]:
                    def value = v
                    def contA = cont
                    def contB = Arrow.id

    def defer[A, B, C, S](v: A < S, cont1: Arrow[A, B, S], cont2: Arrow[B, C, S]): C < S =
        if cont1.isInstanceOf[Arrow.Id[?]] then
            defer(v, cont2.asInstanceOf[Arrow[A, C, S]])
        else if cont2.isInstanceOf[Arrow.Id[?]] then
            defer(v, cont1.asInstanceOf[Arrow[A, C, S]])
        else
            new Pending.Defer[A, B, C, S]:
                def value = v
                def contA = cont1
                def contB = cont2
            end new
    end defer

    def defer[A, B, C, D, S](v: A < S, cont1: Arrow[A, B, S], cont2: Arrow[B, C, S], cont3: Arrow[C, D, S]): D < S =
        if cont1.isInstanceOf[Arrow.Id[?]] then
            defer(v, cont2.asInstanceOf[Arrow[A, C, S]], cont3)
        else if cont2.isInstanceOf[Arrow.Id[?]] then
            defer(v, cont1.asInstanceOf[Arrow[A, C, S]], cont3)
        else if cont3.isInstanceOf[Arrow.Id[?]] then
            defer(v, cont1, cont2.asInstanceOf[Arrow[B, D, S]])
        else
            defer(v, cont1, cont2.chain(cont3))

    def defer[A, S](f: => A < S)(using Frame): A < S =
        deferInline(f)

    private val unitValue: Unit < Any = ()

    @nowarn("msg=anonymous")
    private[kyo] inline def deferInline[A, S](inline f: => A < S)(using inline _frame: Frame): A < S =
        new Pending.DeferWith[Unit, A, S]:
            override def frame          = _frame
            def value                   = unitValue
            override def apply(v: Unit) = f
            override def apply[C, S2](v: Unit < S2, cont: Arrow[A, C, S2]) =
                v match
                    case kyo: Pending[Unit, S2] @unchecked =>
                        defer(kyo, this, cont)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            defer(v, this, cont)
                        else
                            val out = cont.head(apply(Nested.unnest(v)), cont.tail)
                            Safepoint.exit(slot)
                            out
                        end if
end Effect
