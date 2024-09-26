package kyo

import kyo.Tag
import kyo.kernel.*

/** The Emit effect allows emitting values of type V during a computation.
  *
  * Emit can be used to produce a stream of values alongside the main result of a computation. Values are emitted using [[Emit.apply]] and
  * can be collected or processed using various run methods like [[Emit.run]] or [[Emit.runFold]].
  *
  * @tparam V
  *   The type of values that can be emitted
  */
sealed trait Emit[V] extends ArrowEffect[Const[V], Const[Emit.Ack]]

object Emit:

    /** An acknowledgement type used to control emission of values. */
    opaque type Ack = Int
    object Ack:
        given CanEqual[Ack, Ack] = CanEqual.derived
        inline given Flat[Ack]   = Flat.unsafe.bypass

        extension (ack: Ack)
            def maxItems(n: Int): Ack =
                ack match
                    case Stop         => Stop
                    case Continue(n0) => Math.min(n0, Math.max(0, n))

        /** Indicates to continue emitting values */
        opaque type Continue <: Ack = Int
        object Continue:
            def apply(): Continue              = Int.MaxValue
            def apply(maxItems: Int): Continue = Math.max(0, maxItems)
            def unapply(ack: Ack): Maybe.Ops[Int] =
                if ack < 0 then Maybe.empty
                else Maybe(ack)
        end Continue

        /** Indicates to stop emitting values */
        val Stop: Ack = -1
    end Ack

    /** Emits a single value.
      *
      * @param value
      *   The value to emit
      * @return
      *   An effect that emits the given value
      */
    inline def apply[V](inline value: V)(using inline tag: Tag[Emit[V]], inline frame: Frame): Ack < Emit[V] =
        ArrowEffect.suspend[Any](tag, value)

    /** Emits a single value and maps the resulting Ack.
      *
      * @param value
      *   The value to emit
      * @param f
      *   A function to apply to the resulting Ack
      * @return
      *   The result of applying f to the Ack
      */
    inline def andMap[V, A, S](inline value: V)(inline f: Ack => A < S)(
        using
        inline tag: Tag[Emit[V]],
        inline frame: Frame
    ): A < (S & Emit[V]) =
        ArrowEffect.suspendMap[Any](tag, value)(f(_))

    final class RunOps[V](dummy: Unit) extends AnyVal:
        /** Runs an Emit effect, collecting all emitted values into a Chunk.
          *
          * @param v
          *   The computation with Emit effect
          * @return
          *   A tuple of the collected values and the result of the computation
          */
        def apply[A: Flat, S](v: A < (Emit[V] & S))(using tag: Tag[Emit[V]], frame: Frame): (Chunk[V], A) < S =
            ArrowEffect.handle.state(tag, Chunk.empty[V], v)(
                handle = [C] => (input, state, cont) => (state.append(input), cont(Ack.Continue())),
                done = (state, res) => (state, res)
            )
    end RunOps

    inline def run[V >: Nothing]: RunOps[V] = RunOps(())

    final class RunFoldOps[V](dummy: Unit) extends AnyVal:
        /** Runs an Emit effect, folding over the emitted values.
          *
          * @param acc
          *   The initial accumulator value
          * @param f
          *   The folding function
          * @param v
          *   The computation with Emit effect
          * @return
          *   A tuple of the final accumulator value and the result of the computation
          */
        def apply[A, S, B: Flat, S2](acc: A)(f: (A, V) => A < S)(v: B < (Emit[V] & S2))(
            using
            tag: Tag[Emit[V]],
            frame: Frame
        ): (A, B) < (S & S2) =
            ArrowEffect.handle.state(tag, acc, v)(
                handle = [C] =>
                    (input, state, cont) =>
                        f(state, input).map((_, cont(Ack.Continue()))),
                done = (state, res) => (state, res)
            )
    end RunFoldOps

    inline def runFold[V >: Nothing]: RunFoldOps[V] = RunFoldOps(())

    final class RunDiscardOps[V](dummy: Unit) extends AnyVal:
        /** Runs an Emit effect, discarding all emitted values.
          *
          * @param v
          *   The computation with Emit effect
          * @return
          *   The result of the computation, discarding emitted values
          */
        def apply[A: Flat, S](v: A < (Emit[V] & S))(using tag: Tag[Emit[V]], frame: Frame): A < S =
            ArrowEffect.handle(tag, v)(
                handle = [C] => (input, cont) => cont(Ack.Stop)
            )
    end RunDiscardOps

    inline def runDiscard[V >: Nothing]: RunDiscardOps[V] = RunDiscardOps(())

    final class RunAckOps[V](dummy: Unit) extends AnyVal:
        /** Runs an Emit effect, allowing custom handling of each emitted value via an Ack.
          *
          * @param v
          *   The computation with Emit effect
          * @param f
          *   A function to process each emitted value and return an Ack
          * @return
          *   The result of the computation
          */
        def apply[A: Flat, S, S2](v: A < (Emit[V] & S))(f: V => Ack < S2)(using tag: Tag[Emit[V]], frame: Frame): A < (S & S2) =
            ArrowEffect.handle(tag, v)(
                [C] => (input, cont) => f(input).map(cont)
            )
    end RunAckOps

    inline def runAck[V >: Nothing]: RunAckOps[V] = RunAckOps(())

end Emit
