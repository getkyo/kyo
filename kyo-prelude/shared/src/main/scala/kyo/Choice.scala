package kyo

import kyo.Tag
import kyo.kernel.*

/** The Choice effect represents branching computations with multiple possible outcomes.
  *
  * Choice allows for expressing computations that can take different paths or produce multiple results. It's useful for modeling scenarios
  * where there are multiple options or branches to be explored in a computation.
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
            case seq       => ArrowEffect.suspendMap[A](Tag[Choice], seq)(f)

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
                    Kyo.foreach(input)(v => Choice.run(cont(v))).map(_.flatten.flatten)
        }

end Choice
