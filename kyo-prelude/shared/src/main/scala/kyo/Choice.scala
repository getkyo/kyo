package kyo

import fansi.Str
import kyo.Tag
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
  * sequence.
  *
  * @see
  *   [[kyo.Choice.get]] for introducing choice points from sequences
  * @see
  *   [[kyo.Choice.eval]] for applying functions to multiple alternatives
  * @see
  *   [[kyo.Choice.drop]] for terminating unsuccessful computation branches
  * @see
  *   [[kyo.Choice.run]] for collecting all possible outcomes
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
    inline def get[A](seq: Seq[A])(using inline frame: Frame): A < Choice =
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
    inline def eval[A, B, S](seq: Seq[A])(inline f: A => B < S)(using inline frame: Frame): B < (Choice & S) =
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

    enum Strategy derives CanEqual:
        case DepthFirst
        case BreadthFirst

        case Iterative(
            maxDepth: Int = Int.MaxValue,
            step: Int = 1,
            minDepth: Int = 1
        )
    end Strategy

    def runAll[A, S](v: A < (Choice & S))(using Frame): Chunk[A] < S =
        runAll(Strategy.DepthFirst)(v)

    def runAll[A, S](strategy: Strategy)(v: A < (Choice & S))(using Frame): Chunk[A] < S =
        runMax(Int.MaxValue, strategy)(v)

    def runMax[A, S](n: Int, strategy: Strategy = Strategy.DepthFirst)(v: A < (Choice & S))(using Frame): Chunk[A] < S =
        runLoop[A, Chunk[A], S, Chunk[A]](Chunk.empty[A], strategy) { (acc, curr) =>
            val r = acc.concat(curr)
            if r.size >= n then
                Loop.done(r.take(n))
            else
                Loop.continue(r)
            end if
        }(v)

    def runFirst[A, S](v: A < (Choice & S))(using Frame): Maybe[A] < S =
        runFirst(Strategy.DepthFirst)(v)

    def runFirst[A, S](strategy: Strategy)(v: A < (Choice & S))(using Frame): Maybe[A] < S =
        runMax(1, strategy)(v).map(_.headMaybe)

    def runLoop[A, B, S, S2](f: Chunk[A] => Loop.Outcome[Unit, B] < S)(v: A < (Choice & S))(using Frame): B < S =
        runLoop(Strategy.DepthFirst)(f)(v)

    def runLoop[A, B, S, S2](strategy: Strategy)(f: Chunk[A] => Loop.Outcome[Unit, B] < S)(v: A < (Choice & S))(using Frame): B < S =
        runLoop((), strategy)((_, v: Chunk[A]) => f(v))(v)

    def runLoop[A, B, S, ST](
        state: ST,
        strategy: Strategy = Strategy.DepthFirst
    )(handle: (ST, Chunk[A]) => Loop.Outcome[ST, B] < S, done: (ST, Chunk[A]) => B < S)(v: A < (Choice & S))(using Frame): B < S =
        val x =
            ArrowEffect.handleLoop(Tag[Choice], state, Chunk.empty[Chunk[A] < (Choice & S)], v.map(Chunk(_)))(
                handle = [C] =>
                    (input, state, pending, cont) =>
                        if pending.isEmpty then 
                            f(state, Chunk.empty).map {

                            }
                        else
                            ???
                        // strategy match
                        //     case Strategy.BreadthFirst =>
                        //         def loop(pending: Chunk[Chunk[A] < (Choice & S)]): Loop.Outcome[ST, B] < S =
                        //             if pending.isEmpty then 
                        //                 f(state, Chunk.empty)
                        //             else
                        //                 val done = pending.flatMap(_.evalNow).flattenChunk
                        //                 if done.nonEmpty then
                        //                     f(state, done)

                        //         // Kyo.foreach(input) { v =>
                        //         //     val res = cont(v)
                        //         //     res.evalNow
                        //         // }
                        //         ???
                        //     case Strategy.DepthFirst =>
                        //         // Kyo.foreach(input)(v => Choice.runAll(cont(v))).map(_.flattenChunk.flattenChunk)
                        //         ???
                        //     case Strategy.Iterative(maxDepth, step, minDepth) =>
                        //         ???
                        // end match
                ,
                done = (state1, state2, a) => done(state1, a)
            )
        ???
    end runLoop

    def runStream[A, S](v: A < (Choice & S))(using Frame, Tag[Emit[Chunk[A]]]): Stream[A, S] =
        runStream(Strategy.DepthFirst, 1)(v)

    def runStream[A, S](strategy: Strategy = Strategy.DepthFirst, chunkSize: Int = 1)(v: A < (Choice & S))(
        using
        Frame,
        Tag[Emit[Chunk[A]]]
    ): Stream[A, S] =
        require(chunkSize >= 1, "Chunk size must be equal or greater than 1")
        Stream[A, S] {
            runLoop(Chunk.empty[A], strategy) { (acc, curr: Chunk[A]) =>
                val r = acc.concat(curr)
                if r.size < chunkSize then
                    Loop.continue(r)
                else
                    Emit.valueWith(r.take(chunkSize))(Loop.continue(r.drop(chunkSize)))
                end if
            }(v)
        }
    end runStream

end Choice
