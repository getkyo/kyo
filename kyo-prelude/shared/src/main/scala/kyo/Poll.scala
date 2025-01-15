package kyo

import kyo.kernel.ArrowEffect

/** Represents polling values from a data source with backpressure control.
  *
  * Poll is used to consume values while maintaining flow control through acknowledgements. Each poll operation takes an Ack that determines
  * how many values can be consumed, and returns Maybe[V] indicating whether a value was available.
  *
  * Key behaviors:
  *   - Each poll operation requires an Ack value that signals the consumer's readiness to receive more data
  *   - Poll returns Maybe[V], where:
  *     - Present(v) indicates a successful poll with value v
  *     - Absent indicates the end of the stream (no more values will be available)
  *   - Once Absent is received, the consumer should stop polling as the stream has terminated
  *   - Backpressure is maintained through the Ack responses:
  *     - Continue signals readiness to receive more values
  *     - Stop indicates the consumer wants to pause receiving values
  *
  * The effect enables building streaming data pipelines with controlled consumption rates. Handlers can process values at their own pace by
  * returning appropriate Ack responses, while respecting stream termination signals.
  *
  * @tparam V
  *   The type of values being polled from the data source.
  */
sealed trait Poll[V] extends ArrowEffect[Const[Unit], Const[Maybe[V]]]

object Poll:

    /** Attempts to poll a single value.
      *
      * @return
      *   A computation that produces Maybe containing the polled value if available
      */
    inline def one[V](
        using
        inline tag: Tag[Poll[V]],
        inline frame: Frame
    ): Maybe[V] < Poll[V] =
        ArrowEffect.suspend[Unit](tag, ())

    final class ValuesOps[V](dummy: Unit) extends AnyVal:

        /** Processes polled values with a function. Values are processed until n is reached or the stream completes.
          *
          * @param n
          *   Maximum number of values to process
          * @param f
          *   Function to apply to each value
          * @return
          *   A computation that processes values until completion or limit reached
          */
        def apply[S](n: Int)(f: V => Unit < S)(using tag: Tag[Poll[V]], frame: Frame): Unit < (Poll[V] & S) =
            Loop.indexed { idx =>
                if idx == n then Loop.done
                else
                    Poll.andMap[V] {
                        case Present(v) => f(v).map(Loop.continue)
                        case Absent     => Loop.done
                    }
            }

        /** Processes polled values with a function until the stream completes.
          *
          * @param f
          *   Function to apply to each value
          * @return
          *   A computation that processes values until completion
          */
        def apply[S](f: V => Unit < S)(using tag: Tag[Poll[V]], frame: Frame): Unit < (Poll[V] & S) =
            Loop(()) { _ =>
                Poll.andMap[V] {
                    case Present(v) => f(v).map(Loop.continue)
                    case Absent     => Loop.done
                }
            }
    end ValuesOps

    inline def values[V]: ValuesOps[V] = ValuesOps(())

    final case class AndMapOps[V](dummy: Unit) extends AnyVal:

        /** Applies a function to the result of polling.
          *
          * @param f
          *   Function to apply to the polled result
          * @return
          *   A computation that applies the function to the polled result
          */
        inline def apply[A, S](f: Maybe[V] => A < S)(
            using
            inline tag: Tag[Poll[V]],
            inline frame: Frame
        ): A < (Poll[V] & S) =
            ArrowEffect.suspendWith[Unit](tag, ())(f)
    end AndMapOps

    def andMap[V]: AndMapOps[V] = AndMapOps(())

    final class FoldOps[V](dummy: Unit) extends AnyVal:

        /** Folds over polled values with an accumulator.
          *
          * Processes values from the stream by combining them with an accumulator value. Continues until the stream ends, allowing stateful
          * processing of the sequence.
          *
          * @param acc
          *   Initial accumulator value
          * @param f
          *   Function to combine accumulator with each value
          * @return
          *   Final accumulator value after processing all values
          */
        def apply[A, S](acc: A)(f: (A, V) => A < S)(
            using
            tag: Tag[Poll[V]],
            frame: Frame
        ): A < (Poll[V] & S) =
            Loop(acc) { state =>
                Poll.andMap[V] {
                    case Absent     => Loop.done(state)
                    case Present(v) => f(state, v).map(Loop.continue(_))
                }
            }
    end FoldOps

    inline def fold[V]: FoldOps[V] = FoldOps(())

    /** Runs a Poll effect using the provided sequence of values.
      *
      * The Poll effect will consume values from the input chunk sequentially. Once all elements in the chunk have been consumed, subsequent
      * polls will receive Maybe.Absent, signaling the end of the stream.
      *
      * @param inputs
      *   The sequence of values to provide to the Poll effect
      * @param v
      *   The computation requiring Poll values
      * @return
      *   The result of running the Poll computation with the provided values
      */
    def run[V, A: Flat, S](inputs: Chunk[V])(v: A < (Poll[V] & S))(
        using
        tag: Tag[Poll[V]],
        frame: Frame
    ): A < S =
        ArrowEffect.handleState(tag, inputs, v)(
            [C] =>
                (unit, state, cont) => (state.drop(1), cont(state.headMaybe))
        )

    final case class RunFirstOps[V](dummy: Unit) extends AnyVal:

        /** Runs a Poll effect with a single input value, stopping after the first poll operation.
          *
          * This method provides a single input value to the Poll effect and stops after the first poll. It returns a tuple containing:
          *   - An Ack value indicating whether to continue or stop
          *   - A continuation function that can process the Maybe[V] result of the poll
          *
          * @param v
          *   The computation requiring Poll values
          * @return
          *   A tuple containing the acknowledgement and a continuation function that processes the poll result
          */
        def apply[A: Flat, S](v: A < (Poll[V] & S))(
            using
            tag: Tag[Poll[V]],
            frame: Frame
        ): (Maybe[V] => A < (Poll[V] & S)) < S =
            ArrowEffect.handleFirst(tag, v)(
                handle = [C] =>
                    (input, cont) =>
                        // Effect found, return the input an continuation
                    cont,
                done = r =>
                    // Effect not found, return empty input and a placeholder continuation
                    // that returns the result of the computation
                    (_: Maybe[V]) => r: A < (Poll[V] & S)
            )
    end RunFirstOps

    def runFirst[V]: RunFirstOps[V] = RunFirstOps(())

    /** Connects an emitting source to a polling consumer with flow control.
      *
      * The emitting source produces values that are consumed by the polling computation in a demand-driven way. The polling consumer
      * controls the flow rate by sending requests to indicate readiness for more data. The emitter responds to these signals to implement
      * backpressure.
      *
      * The flow continues until either:
      *   - The emitter completes, signaling end-of-stream to the consumer via Maybe.Absent
      *   - The consumer completes, terminating consumption
      *   - Both sides complete naturally
      *
      * @param emit
      *   The emitting computation that produces values
      * @param poll
      *   The polling computation that consumes values
      * @return
      *   A tuple containing results from both the emitter and poller
      */
    def run[V, A: Flat, B: Flat, S, S2](emit: A < (Emit[V] & S))(poll: B < (Poll[V] & S2))(
        using
        emitTag: Tag[Emit[V]],
        pollTag: Tag[Poll[V]],
        frame: Frame
    ): (A, B) < (S & S2) =
        // Start by handling the first emission
        Loop(emit, poll) { (emit, poll) =>
            ArrowEffect.handleFirst(emitTag, emit)(
                handle = [C] =>
                    (emitted, emitCont) =>
                        // Once we have an emitted value, handle the first poll operation
                        // This creates the demand-driven cycle between emit and poll
                        ArrowEffect.handleFirst(pollTag, poll)(
                            handle = [C2] =>
                                (unit, pollCont) =>
                                    // Continue the emit-poll cycle:
                                    // 1. Pass the ack back to emitter to control flow
                                    // 2. Pass the emitted value to poller for consumption
                                    // 3. Recursively continue the cycle
                                    Loop.continue(emitCont(unit), pollCont(Maybe(emitted))),
                            // Poll.run(emitCont(ack))(pollCont(Maybe(emitted))),
                            done = b =>
                                // Poller completed early (e.g., received all needed values)
                                // Discard remaining emit operations
                                Emit.runDiscard(emitCont(())).map(a => Loop.done((a, b)))
                    ),
                done = a =>
                    // Emitter completed (no more values to emit)
                    // Run remaining poll operations with empty chunk to signal completion
                    Poll.run(Chunk.empty)(poll).map(b => Loop.done((a, b)))
            )
        }
    end run

end Poll
