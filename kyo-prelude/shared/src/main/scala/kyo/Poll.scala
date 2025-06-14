package kyo

import kyo.kernel.ArrowEffect

/** Represents polling values from a data source with backpressure control.
  *
  * Poll implements a pull-based streaming model where consumers actively request data when they're ready to process it. This approach
  * provides natural backpressure, as values are only produced in response to explicit requests, preventing overwhelmed consumers.
  *
  * Key behaviors:
  *   - Poll returns Maybe[V], where:
  *     - Present(v) indicates a successful poll with value v
  *     - Absent indicates the end of the stream (no more values will be available)
  *   - Once Absent is received, the consumer should stop polling as the stream has terminated
  *
  * Poll is used to consume values. Each poll operation signals readiness to receive data, and returns Maybe[V] indicating whether a value
  * was available. This enables building streaming data pipelines with controlled consumption rates. Handlers can process values at their
  * own pace by polling only as needed.
  *
  * The Poll effect can be connected to an Emit effect through `Poll.run` to synchronize producer and consumer, establishing proper flow
  * control. For higher-level streaming operations with rich transformation capabilities, consider using the Stream abstraction.
  *
  * @tparam V
  *   The type of values being polled from the data source.
  *
  * @see
  *   [[kyo.Poll.one]] for polling a single value
  * @see
  *   [[kyo.Poll.values]] for processing multiple polled values
  * @see
  *   [[kyo.Poll.fold]] for folding over polled values
  * @see
  *   [[kyo.Poll.run]] for connecting Poll with Emit effects
  * @see
  *   [[kyo.Emit]] for push-based value emission
  * @see
  *   [[kyo.Stream]] for higher-level streaming operations
  */
sealed trait Poll[+V] extends ArrowEffect[Const[Unit], Const[Maybe[V]]]

object Poll:
    given eliminatePoll: Reducible.Eliminable[Poll[Any]] with {}

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

    /** Processes polled values with a function. Values are processed until n is reached or the stream completes.
      *
      * @param n
      *   Maximum number of values to process
      * @param f
      *   Function to apply to each value
      * @return
      *   A computation that processes values until completion or limit reached
      */
    def values[V](using Frame)[S](n: Int)(f: V => Any < S)(using tag: Tag[Poll[V]]): Unit < (Poll[V] & S) =
        Loop.indexed { idx =>
            if idx == n then Loop.done
            else
                Poll.andMap[V] {
                    case Present(v) => f(v).map(_ => Loop.continue)
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
    def values[V](using Frame)[S](f: V => Any < S)(using tag: Tag[Poll[V]]): Unit < (Poll[V] & S) =
        Loop.foreach:
            Poll.andMap[V] {
                case Present(v) => f(v).map(_ => Loop.continue)
                case Absent     => Loop.done
            }

    /** Applies a function to the result of polling.
      *
      * @param f
      *   Function to apply to the polled result
      * @return
      *   A computation that applies the function to the polled result
      */
    inline def andMap[V](
        using inline frame: Frame
    )[A, S](f: Maybe[V] => A < S)(
        using inline tag: Tag[Poll[V]]
    ): A < (Poll[V] & S) =
        ArrowEffect.suspendWith[Unit](tag, ())(f)

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
    def fold[V](
        using Frame
    )[A, S](acc: A)(f: (A, V) => A < S)(
        using tag: Tag[Poll[V]]
    ): A < (Poll[V] & S) =
        Loop(acc) { state =>
            Poll.andMap[V] {
                case Absent     => Loop.done(state)
                case Present(v) => f(state, v).map(Loop.continue(_))
            }
        }

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
    def run[V](inputs: Chunk[V])[A, VR, S](v: A < (Poll[V] & Poll[VR] & S))(
        using
        tag: Tag[Poll[V]],
        reduce: Reducible[Poll[VR]],
        frame: Frame
    ): A < (reduce.SReduced & S) =
        reduce:
            ArrowEffect.handleLoop(tag, inputs, v)(
                [C] =>
                    (unit, state, cont) => Loop.continue(state.drop(1), cont(state.headMaybe))
            )

    /** Runs a Poll effect with a single input value, stopping after the first poll operation.
      *
      * This method provides a single input value to the Poll effect and stops after the first poll. It returns a continuation function that
      * can process the Maybe[V] result of the poll
      *
      * @param v
      *   The computation requiring Poll values
      * @return
      *   A tuple containing the acknowledgement and a continuation function that processes the poll result
      */
    def runFirst[V](
        using Frame
    )[A, VR, S](v: A < (Poll[V] & Poll[VR] & S))(using
        tag: Tag[Poll[V]],
        reduce: Reducible[Poll[VR]]
    ): Either[A, Maybe[V] => A < (Poll[V & VR] & S)] < (reduce.SReduced & S) =
        reduce:
            ArrowEffect.handleFirst(tag, v)(
                handle = [C] =>
                    (input, cont) =>
                        // Effect found, return the input an continuation
                        Right(cont),
                done = r =>
                    // Effect not found, return empty input and a placeholder continuation
                    // that returns the result of the computation
                    Left(r)
            )

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
    def runEmit[V](
        using
        emitTag: Tag[Emit[V]],
        pollTag: Tag[Poll[V]]
    )[A, B, VRE, VRP, S, S2](emit: A < (Emit[V] & Emit[VRE] & S))(poll: B < (Poll[V] & Poll[VRP] & S2))(
        using
        reduceEmit: Reducible[Emit[VRE]],
        reducePoll: Reducible[Poll[VRP]],
        frame: Frame
    ): (A, B) < (reduceEmit.SReduced & reducePoll.SReduced & S & S2) =
        reduceEmit:
            reducePoll:
                // Start by handling the first emission
                Loop(emit, poll) { (emit, poll) =>
                    ArrowEffect.handleFirst(emitTag, emit)(
                        handle = [C] =>
                            (emitted, emitCont) =>
                                // Once we have an emitted value, handle the first poll operation
                                // This creates the demand-driven cycle between emit and poll
                                ArrowEffect.handleFirst(pollTag, poll)(
                                    handle = [C2] =>
                                        (_, pollCont) =>
                                            // Continue the emit-poll cycle:
                                            // 1. Pass the ack back to emitter to control flow
                                            // 2. Pass the emitted value to poller for consumption
                                            // 3. Recursively continue the cycle
                                            Loop.continue(emitCont(()), pollCont(Maybe(emitted))),
                                    // Poll.run(emitCont(ack))(pollCont(Maybe(emitted))),
                                    done = b =>
                                        // Poller completed early (e.g., received all needed values)
                                        // Discard remaining emit operations
                                        Emit.runDiscard[V](emitCont(())).map(a => Loop.done((a, b)))
                            ),
                        done = a =>
                            // Emitter completed (no more values to emit)
                            // Run remaining poll operations with empty chunk to signal completion
                            Poll.run[V](Chunk.empty)(poll).map(b => Loop.done((a, b)))
                    )
                }
    end runEmit

end Poll
