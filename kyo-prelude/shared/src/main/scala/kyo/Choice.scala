package kyo

import kyo.Tag
import kyo.debug.Debug
import kyo.kernel.*

/** Represents non-deterministic computations with multiple possible outcomes.
  *
  * The `Choice` effect enables branching computations where multiple paths can be explored simultaneously. Unlike traditional control flow
  * that follows a single execution path, Choice allows expressing alternative possibilities that can be collected and processed together.
  *
  * Choice is valuable for scenarios requiring exploration of multiple alternatives, such as parsing with backtracking, search algorithms,
  * or any computation where you need to model decisions with multiple valid options. When combined with other effects like `Parse`, it
  * becomes especially powerful for expressing complex branching logic.
  *
  * The primary operations include introducing choice points with `get`, evaluating functions across alternatives with `eval`, and pruning
  * invalid paths with `drop`. Computations using Choice can be collected with `run`, which gathers all successful outcomes into a single
  * sequence, or streamed with `runStream`, which produces results incrementally as a stream.
  *
  * @see
  *   [[kyo.Choice.get]] for introducing choice points from sequences
  * @see
  *   [[kyo.Choice.eval]] for applying functions to multiple alternatives
  * @see
  *   [[kyo.Choice.drop]] for terminating unsuccessful computation branches
  * @see
  *   [[kyo.Choice.run]] for collecting all possible outcomes
  * @see
  *   [[kyo.Choice.runStream]] for streaming possible outcomes incrementally
  */
sealed trait Choice extends ArrowEffect[Seq, Id]

object Choice:

    /** Introduces a choice point by selecting values from a sequence.
      *
      * @param seq
      *   The sequence of possible values
      * @return
      *   A computation that represents the selection of values from the sequence
      */
    inline def eval[A](seq: A*)(using inline frame: Frame): A < Choice =
        ArrowEffect.suspend[A](Tag[Choice], seq)

    /** Evaluates a function for each value in a sequence, introducing multiple computation paths.
      *
      * @param seq
      *   The sequence of input values
      * @param f
      *   The function to apply to each value
      * @return
      *   A computation that represents multiple paths, one for each input value
      */
    inline def evalWith[A, B, S](seq: Seq[A])(inline f: A => B < S)(using inline frame: Frame): B < (Choice & S) =
        seq match
            case Seq(head) => f(head)
            case seq       => ArrowEffect.suspendWith[A](Tag[Choice], seq)(f)

    /** Conditionally introduces a failure branch in the computation.
      *
      * @param condition
      *   The condition to check
      * @return
      *   A computation that fails if the condition is true, otherwise continues
      */
    inline def dropIf(condition: Boolean)(using inline frame: Frame): Unit < Choice =
        if condition then drop
        else ()

    /** Introduces an immediate failure branch in the computation.
      *
      * @return
      *   A computation that always fails
      */
    inline def drop(using inline frame: Frame): Nothing < Choice =
        ArrowEffect.suspend[Nothing](Tag[Choice], Seq.empty)

    /** Handles the Choice effect by collecting all possible outcomes.
      *
      * @param v
      *   The computation with Choice effect to handle
      * @return
      *   A computation that produces a sequence of all possible outcomes
      */
    def run[A, S](v: A < (Choice & S))(using Frame): Chunk[A] < S =
        ArrowEffect.handle(Tag[Choice], v.map(Chunk[A](_))) {
            [C] =>
                (input, cont) =>
                    Kyo.foreach(input)(v => Choice.run(cont(v))).map(_.flattenChunk.flattenChunk)
        }

    /** Handles the Choice effect by streaming all possible outcomes incrementally.
      *
      * Unlike `run` which collects all outcomes at once, `runStream` produces results incrementally as a stream, which can be more
      * efficient for computations with many possible outcomes or when you want to process results as they become available.
      *
      * @param v
      *   The computation with Choice effect to handle
      * @return
      *   A stream that produces the possible outcomes incrementally
      */
    def runStream[A, S](v: A < (Choice & S))(using Frame, Tag[Emit[Chunk[A]]]): Stream[A, S] =
        Stream {
            Loop(Chunk(v)) { curr =>
                val (done, pending) = curr.partition(_.evalNow.isDefined)
                Emit
                    .valueWhen(done.nonEmpty)(done.asInstanceOf[Chunk[A]])
                    .andThen {
                        if pending.isEmpty then Loop.done
                        else
                            Kyo.foreach(pending) { v =>
                                ArrowEffect.handleFirst(Tag[Choice], v)(
                                    handle = [C] => (input, cont) => Chunk.from(input).map(cont),
                                    done = r => Chunk(r: A < (Choice & S))
                                )
                            }.map(r => Loop.continue(r.flattenChunk))
                    }
            }
        }
    end runStream

end Choice
