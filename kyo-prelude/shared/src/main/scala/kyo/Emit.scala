package kyo

import kyo.Tag
import kyo.kernel.*

/** The Emit effect allows producing multiple values alongside the main result of a computation.
  *
  * Emit implements a push-based model where values of type V are actively emitted from a producer without waiting for consumer readiness.
  * This makes it useful for event emission, logging, or any scenario where values need to be produced during computation regardless of
  * downstream consumption patterns.
  *
  * As a low-level primitive in Kyo's streaming architecture, Emit provides fundamental capabilities but lacks many conveniences. For most
  * streaming use cases, prefer the higher-level Stream abstraction which offers richer transformation capabilities, automatic chunking, and
  * better composition. Direct use of Emit should generally be reserved for specialized scenarios.
  *
  * Values are emitted using methods like `value` and collected with various run handlers such as `run`, `runFold`, or `runForeach`. The
  * effect follows a clean separation between emission and consumption, allowing for functional composition of streaming operations.
  *
  * Unlike Poll, which implements a pull-based model with backpressure, Emit is a simpler model where the producer drives the flow. When
  * sophisticated flow control is needed, Emit and Poll can be connected using `Poll.run` to establish backpressure.
  *
  * @tparam V
  *   The type of values that can be emitted
  *
  * @see
  *   [[kyo.Emit.value]], [[kyo.Emit.valueWith]] for emitting values
  * @see
  *   [[kyo.Emit.run]], [[kyo.Emit.runFold]], [[kyo.Emit.runForeach]] for handling emitted values
  * @see
  *   [[kyo.Poll]] for pull-based streaming with backpressure
  * @see
  *   [[kyo.Stream]] for higher-level streaming operations (preferred for most use cases)
  */
sealed trait Emit[-V] extends ArrowEffect[Const[V], Const[Unit]]

object Emit:
    given eliminateEmit: Reducible.Eliminable[Emit[Nothing]] with {}

    /** Emits a single value.
      *
      * @param value
      *   The value to emit
      * @return
      *   An effect that emits the given value
      */
    inline def value[V](inline value: V)(using inline tag: Tag[Emit[V]], inline frame: Frame): Unit < Emit[V] =
        ArrowEffect.suspend[Any](tag, value)

    /** Emits a single value when a condition is true.
      *
      * @param cond
      *   The condition that determines whether to emit the value
      * @param value
      *   The value to emit if the condition is true
      * @return
      *   An effect that emits the given value if the condition is true, otherwise does nothing
      */
    inline def valueWhen[V, S](cond: Boolean < S)(inline value: V)(using
        inline tag: Tag[Emit[V]],
        inline frame: Frame
    ): Unit < (Emit[V] & S) =
        cond.map {
            case false => ()
            case true  => Emit.value(value)
        }

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

    /** Runs an Emit effect, collecting all emitted values into a Chunk.
      *
      * @param v
      *   The computation with Emit effect
      * @return
      *   A tuple of the collected values and the result of the computation
      */
    def run[V](using
        tag: Tag[Emit[V]],
        fr: Frame
    )[A, VR, S](v: A < (Emit[V] & Emit[VR] & S))(using reduce: Reducible[Emit[VR]]): (Chunk[V], A) < (reduce.SReduced & S) =
        reduce:
            ArrowEffect.handleLoop(tag, Chunk.empty[V], v)(
                handle = [C] => (input, state, cont) => Loop.continue(state.append(input), cont(())),
                done = (state, res) => (state, res)
            )

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
    def runFold[V](
        using Frame
    )[A, S, VR, B, S2](acc: A)(f: (A, V) => A < S)(v: B < (Emit[V] & Emit[VR] & S2))(using
        tag: Tag[Emit[V]],
        reduce: Reducible[Emit[VR]]
    ): (A, B) < (reduce.SReduced & S & S2) =
        reduce:
            ArrowEffect.handleLoop(tag, acc, v)(
                handle = [C] =>
                    (input, state, cont) =>
                        f(state, input).map(a => Loop.continue(a, cont(()))),
                done = (state, res) => (state, res)
            )

    /** Runs an Emit effect, discarding all emitted values.
      *
      * @param v
      *   The computation with Emit effect
      * @return
      *   The result of the computation, discarding emitted values
      */
    def runDiscard[V](
        using Frame
    )[A, VR, S](v: A < (Emit[V] & Emit[VR] & S))(using tag: Tag[Emit[V]], reduce: Reducible[Emit[VR]]): A < (reduce.SReduced & S) =
        reduce:
            ArrowEffect.handle(tag, v)(
                handle = [C] => (input, cont) => cont(())
            )

    /** Runs an Emit effect, allowing custom handling of each emitted value.
      *
      * @param v
      *   The computation with Emit effect
      * @param f
      *   A function to process each emitted value
      * @return
      *   The result of the computation
      */
    def runForeach[V](
        using Frame
    )[A, VR, S, S2](v: A < (Emit[V] & Emit[VR] & S))(f: V => Any < S2)(using
        tag: Tag[Emit[V]],
        reduce: Reducible[Emit[VR]]
    ): A < (reduce.SReduced & S & S2) =
        reduce[A, S & S2]:
            ArrowEffect.handle(tag, v)(
                [C] => (input, cont) => f(input).map(_ => cont(()))
            )

    /** Runs an Emit effect, allowing custom handling of each emitted value with a boolean result determining whether to continue.
      *
      * @param v
      *   The computation with Emit effect
      * @param f
      *   A function to process each emitted value
      * @return
      *   The result of the computation
      */
    def runWhile[V](using
        Frame
    )[A, VR, S, S2](v: A < (Emit[V] & Emit[VR] & S))(f: V => Boolean < S2)(using
        tag: Tag[Emit[V]],
        reduce: Reducible[Emit[VR]]
    ): A < (reduce.SReduced & S & S2) =
        reduce:
            ArrowEffect.handleLoop(tag, true, v)(
                [C] =>
                    (input, cond, cont) =>
                        if cond then
                            f(input).map(c => Loop.continue(c, cont(())))
                        else
                            Loop.continue(cond, cont(()))
            )

    /** Runs an Emit effect, capturing only the first emitted value and returning a continuation.
      *
      * @param v
      *   The computation with Emit effect
      * @return
      *   A tuple containing:
      *   - Maybe[V]: The first emitted value if any (None if no values were emitted)
      *   - A continuation function that returns the remaining computation
      */
    def runFirst[V](using
        Frame
    )[A, VR, S](v: A < (Emit[V] & Emit[VR] & S))(using
        tag: Tag[Emit[V]],
        reduce: Reducible[Emit[VR]]
    ): (Maybe[V], () => A < (Emit[V | VR] & S)) < (reduce.SReduced & S) =
        reduce:
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
        def merge[V](using Tag[Emit[V]]): Isolate.Stateful[Emit[V], Any] =
            new Isolate.Stateful[Emit[V], Any]:

                type State = Chunk[V]

                type Transform[A] = (Chunk[V], A)

                def capture[A, S](f: State => A < S)(using Frame) =
                    f(Chunk.empty)

                def isolate[A, S](state: Chunk[V], v: A < (S & Emit[V]))(using Frame) =
                    Emit.run(v)

                def restore[A, S](v: (Chunk[V], A) < S)(using Frame) =
                    v.map { (state, result) =>
                        Loop(state: Seq[V]) {
                            case Seq() => Loop.done(result)
                            case head +: tail =>
                                Emit.valueWith(head)(Loop.continue(tail))
                        }
                    }
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
        def discard[V](using Tag[Emit[V]]): Isolate.Stateful[Emit[V], Any] =
            new Isolate.Stateful[Emit[V], Any]:

                type State = Chunk[V]

                type Transform[A] = A

                def capture[A, S](f: State => A < S)(using Frame) =
                    f(Chunk.empty)

                def isolate[A, S](state: Chunk[V], v: A < (S & Emit[V]))(using Frame) =
                    Emit.runDiscard(v)

                def restore[A, S](v: A < S)(using Frame) =
                    v
    end isolate

end Emit
