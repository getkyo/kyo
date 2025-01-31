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
sealed trait Emit[V] extends ArrowEffect[Const[V], Const[Unit]]

object Emit:

    /** Emits a single value.
      *
      * @param value
      *   The value to emit
      * @return
      *   An effect that emits the given value
      */
    inline def value[V](inline value: V)(using inline tag: Tag[Emit[V]], inline frame: Frame): Unit < Emit[V] =
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
    inline def valueWith[V, A, S](inline value: V)(inline f: => A < S)(
        using
        inline tag: Tag[Emit[V]],
        inline frame: Frame
    ): A < (S & Emit[V]) =
        ArrowEffect.suspendWith[Any](tag, value)(_ => f)

    final class RunOps[V](dummy: Unit) extends AnyVal:
        /** Runs an Emit effect, collecting all emitted values into a Chunk.
          *
          * @param v
          *   The computation with Emit effect
          * @return
          *   A tuple of the collected values and the result of the computation
          */
        def apply[A: Flat, S](v: A < (Emit[V] & S))(using tag: Tag[Emit[V]], frame: Frame): (Chunk[V], A) < S =
            ArrowEffect.handleState(tag, Chunk.empty[V], v)(
                handle = [C] => (input, state, cont) => (state.append(input), cont(())),
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
          *   The folding function that takes the current accumulator and emitted value, and returns an updated accumulator
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
            ArrowEffect.handleState(tag, acc, v)(
                handle = [C] =>
                    (input, state, cont) =>
                        f(state, input).map(a => (a, cont(()))),
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
                handle = [C] => (input, cont) => cont(())
            )
    end RunDiscardOps

    inline def runDiscard[V >: Nothing]: RunDiscardOps[V] = RunDiscardOps(())

    final class RunForeachOps[V](dummy: Unit) extends AnyVal:
        /** Runs an Emit effect, allowing custom handling of each emitted value.
          *
          * @param v
          *   The computation with Emit effect
          * @param f
          *   A function to process each emitted value
          * @return
          *   The result of the computation
          */
        def apply[A: Flat, S, S2](v: A < (Emit[V] & S))(f: V => Unit < S2)(using tag: Tag[Emit[V]], frame: Frame): A < (S & S2) =
            ArrowEffect.handle(tag, v)(
                [C] => (input, cont) => f(input).map(cont)
            )
    end RunForeachOps

    inline def runForeach[V >: Nothing]: RunForeachOps[V] = RunForeachOps(())

    final class RunWhileOps[V](dummy: Unit) extends AnyVal:
        /** Runs an Emit effect, allowing custom handling of each emitted value with a boolean result determining whether to continue.
          *
          * @param v
          *   The computation with Emit effect
          * @param f
          *   A function to process each emitted value
          * @return
          *   The result of the computation
          */
        def apply[A: Flat, S, S2](v: A < (Emit[V] & S))(f: V => Boolean < S2)(using tag: Tag[Emit[V]], frame: Frame): A < (S & S2) =
            ArrowEffect.handleState(tag, true, v)(
                [C] => (input, cond, cont) => if cond then f(input).map(c => (c, cont(()))) else (cond, cont(()))
            )
    end RunWhileOps

    inline def runWhile[V >: Nothing]: RunWhileOps[V] = RunWhileOps(())

    final class RunFirstOps[V](dummy: Unit) extends AnyVal:

        /** Runs an Emit effect, capturing only the first emitted value and returning a continuation.
          *
          * @param v
          *   The computation with Emit effect
          * @return
          *   A tuple containing:
          *   - Maybe[V]: The first emitted value if any (None if no values were emitted)
          *   - A continuation function that returns the remaining computation
          */
        def apply[A: Flat, S](v: A < (Emit[V] & S))(using tag: Tag[Emit[V]], frame: Frame): (Maybe[V], () => A < (Emit[V] & S)) < S =
            ArrowEffect.handleFirst(tag, v)(
                handle = [C] =>
                    (input, cont) =>
                        // Effect found, return the input an continuation
                        (Maybe(input), () => cont(())),
                done = r =>
                    // Effect not found, return empty input and a placeholder continuation
                    // that returns the result of the computation
                    (Maybe.empty[V], () => r: A < (Emit[V] & S))
            )
        end apply
    end RunFirstOps

    inline def runFirst[V >: Nothing]: RunFirstOps[V] = RunFirstOps(())

    object isolate:

        /** Creates an isolate that includes emitted values from isolated computations.
          *
          * When the isolation ends, appends all values emitted during the isolated computation to the outer context. The values are emitted
          * in their original order.
          *
          * @tparam V
          *   The type of values being emitted
          * @return
          *   An isolate that preserves emitted values
          */
        def merge[V: Tag]: Isolate[Emit[V]] =
            new Isolate[Emit[V]]:
                type State = Chunk[V]
                def use[A, S2](f: Chunk[V] => A < S2)(using Frame) = f(Chunk.empty)
                def resume[A: Flat, S2](state: Chunk[V], v: A < (Emit[V] & S2))(using Frame) =
                    Emit.run(v)
                def restore[A: Flat, S2](state: Chunk[V], v: A < S2)(using Frame) =
                    Loop(state: Seq[V]) {
                        case Seq() => Loop.done
                        case head +: tail =>
                            Emit.valueWith(head)(Loop.continue(tail))
                    }.andThen(v)
                end restore

        /** Creates an isolate that ignores emitted values.
          *
          * Allows the isolated computation to emit values freely, but discards all emissions when the isolation ends. Useful when you want
          * to prevent emissions from propagating to the outer context.
          *
          * @tparam V
          *   The type of values being emitted
          * @return
          *   An isolate that discards emitted values
          */
        def discard[V: Tag]: Isolate[Emit[V]] =
            new Isolate[Emit[V]]:
                type State = Chunk[V]
                def use[A, S2](f: Chunk[V] => A < S2)(using Frame) = f(Chunk.empty)
                def resume[A: Flat, S2](state: Chunk[V], v: A < (Emit[V] & S2))(using Frame) =
                    Emit.run(v)
                def restore[A: Flat, S2](state: Chunk[V], v: A < S2)(using Frame) =
                    v
    end isolate

end Emit
