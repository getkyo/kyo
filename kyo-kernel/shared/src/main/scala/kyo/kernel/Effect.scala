package kyo.kernel

import kyo.Frame
import kyo.kernel.internal.*
import scala.annotation.nowarn

/** The base trait for all effects in the Kyo effect system.
  *
  * An effectful operation does not execute where it is written. It builds a suspended computation capturing what needs to be done, and a
  * handler later interprets it. That indirection is what makes effectful code pure and composable: the code describes the operations it
  * wants, a handler decides how they actually happen, and the same description can be run, mocked, retried or abandoned.
  *
  * An effect is used as a type, never instantiated. It is the name a suspension carries and the name a handler matches on, so declaring one
  * means declaring a type that extends one of the two kinds below.
  *
  * There are two kinds:
  *   - [[ArrowEffect]] for operations that take an input and are answered with an output.
  *   - [[ContextEffect]] for values bound around a computation and read from within it.
  *
  * @see
  *   [[Effect.defer]] For moving a block into the computation the evaluator runs
  */
abstract class Effect private[kernel] ()

object Effect:

    /** Reifies the application of `cont` to `v` as a node, rather than applying it here.
      *
      * This is what lets the evaluator own the call: the pair becomes a value it unfolds instead of `cont` running on the current stack,
      * which is where stack safety and the safepoint budget come from.
      *
      * The overloads taking two and three continuations let a caller that already holds a composition hand the links over separately, so one
      * node carries them rather than a node plus an [[Arrow.Chain]]. An identity link is dropped instead of stored.
      */
    def defer[A, B, S](v: A < S, cont: Arrow[A, B, S]): B < S =
        cont match
            case cont: Arrow.Chain[A, x, B, S] @unchecked =>
                new Pending.Defer[A, x, B, S]:
                    def frame = Frame.internal
                    def value = v
                    def contA = cont.a
                    def contB = cont.b
            case _ =>
                new Pending.Defer[A, B, B, S]:
                    def frame = Frame.internal
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
                def frame = Frame.internal
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

    /** Defers a block so it runs where the evaluator reaches it rather than where it is written.
      *
      * Taking the block by name and making it a node is the way to move ordinary code into a computation: what the block does happens when
      * the computation runs, once per run, instead of at the point the value is built.
      */
    def defer[A, S](f: => A < S)(using Frame): A < S =
        deferInline(f)

    @nowarn("msg=anonymous")
    private[kyo] inline def deferInline[A, S](inline f: => A < S)(using inline _frame: Frame): A < S =
        new Pending.DeferWith[Unit, A, S]:
            override def frame                                             = _frame
            def value                                                      = ()
            override def apply(v: Unit)                                    = f
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
