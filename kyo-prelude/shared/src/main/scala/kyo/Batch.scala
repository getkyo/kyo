package kyo

import Batch.internal.*
import Batch.internal.Pending.Expanded
import Batch.internal.Pending.ToExpand
import kyo.Tag
import kyo.kernel.*

/** The Batch effect provides a mechanism for efficiently grouping and executing operations together.
  *
  * Batch enables automatic collection and processing of operations that would otherwise execute individually, resulting in improved
  * performance when interacting with external systems like databases, APIs, or file systems. The key benefit is that you can write code as
  * if each operation runs independently, while the Batch effect automatically optimizes execution by grouping compatible operations.
  *
  * This effect is valuable when dealing with N+1 query problems, API rate limiting, or any scenario where performing operations in bulk is
  * more efficient than individual execution. Batch sources define how multiple inputs are processed together, while maintaining the ability
  * to return individualized results.
  *
  * The implementation handles complex cases such as nested batches and operations with different sources, intelligently grouping compatible
  * operations while preserving correct execution order and dependencies.
  *
  * @see
  *   [[kyo.Batch.source]] for creating batched computations from functions processing multiple inputs
  * @see
  *   [[kyo.Batch.sourceMap]] for batched operations returning maps
  * @see
  *   [[kyo.Batch.sourceSeq]] for batched operations returning sequences
  * @see
  *   [[kyo.Batch.run]] for executing batched operations
  * @see
  *   [[kyo.Batch.eval]] for evaluating sequences of values in batches
  */
sealed trait Batch extends ArrowEffect[Op, Id]

object Batch:

    import internal.*

    /** Creates a batched computation from a source function.
      *
      * @param f
      *   The source function with the following signature:
      *   - Input: `Seq[A]` - A sequence of input values to be processed in batch
      *   - Output: `(A => B < S) < S` - A function that, when evaluated, produces another function:
      *     - This inner function takes a single input `A` and returns a value `B` with effects `S`, allowing effects on each element
      *       individually.
      *     - The outer `< S` indicates that the creation of this function may itself involve effects `S`
      *
      * @return
      *   A function that takes a single input `A` and returns a batched computation `B < Batch[S]`
      *
      * This method allows for efficient batching of operations by processing multiple inputs at once, while still providing individual
      * results for each input.
      */
    inline def source[A, B, S](f: Seq[A] => (A => B < S) < S)(using inline frame: Frame): A => B < (Batch & S) =
        (v: A) => ArrowEffect.suspendWith(Tag[Batch], Call(v, f))(identity)

    /** Creates a batched computation from a source function that returns a Map.
      *
      * @param f
      *   The source function that takes a sequence of inputs and returns a Map of results
      * @return
      *   A function that takes a single input and returns a batched computation
      */
    inline def sourceMap[A, B, S](f: Seq[A] => Map[A, B] < S)(using inline frame: Frame): A => B < (Batch & S) =
        source[A, B, S] { input =>
            f(input).map { output =>
                require(
                    input.size == output.size,
                    s"Source created at ${frame.position.show} returned a different number of elements than input: ${input.size} != ${output.size}"
                )
                ((a: A) => output(a): B < S)
            }
        }

    /** Creates a batched computation from a source function that returns a Sequence.
      *
      * @param f
      *   The source function that takes a sequence of inputs and returns a sequence of results
      * @return
      *   A function that takes a single input and returns a batched computation
      */
    inline def sourceSeq[A, B, S](f: Seq[A] => Seq[B] < S)(using inline frame: Frame): A => B < (Batch & S) =
        sourceMap[A, B, S] { input =>
            f(input).map { output =>
                require(
                    input.size == output.size,
                    s"Source created at ${frame.position.show} returned a different number of elements than input: ${input.size} != ${output.size}"
                )
                input.zip(output).toMap
            }
        }

    /** Evaluates a sequence of values in a batch.
      *
      * @param seq
      *   The sequence of values to evaluate
      * @return
      *   A batched operation that produces a single value from the sequence
      */
    inline def eval[A](seq: Seq[A])(using inline frame: Frame): A < Batch =
        ArrowEffect.suspend[A](Tag[Batch], Eval(seq))

    /** Applies a function to each element of a sequence in a batched context.
      *
      * This method is similar to `Kyo.foreach`, but instead of returning a `Seq[B]`, it returns a single value of type `B`.
      *
      * @param seq
      *   The sequence of values to process
      * @param f
      *   The function to apply to each element
      * @return
      *   A batched computation that produces a single value of type B
      */
    inline def foreach[A, B, S](seq: Seq[A])(inline f: A => B < S): B < (Batch & S) =
        ArrowEffect.suspendWith[A](Tag[Batch], Eval(seq))(f)

    /** Runs a computation with Batch effect, executing all batched operations.
      *
      * @param v
      *   The computation to run
      * @return
      *   A sequence of results from executing the batched operations
      */
    def run[A, S](v: A < (Batch & S))(using Frame): Chunk[A] < S =
        // An item can be a final value (`A`),
        // a sequence from `Batch.eval` (`ToExpand`),
        // or a call to a source (`Expanded`).
        type Item = A | ToExpand[A, S] | Expanded[A, S]

        // Transforms effect suspensions into an item.
        // Captures the continuation in the `Item` objects for `ToExpand` and `Expanded` cases.
        def capture(v: Item < (Batch & S)): Item < S =
            ArrowEffect.handle(Tag[Batch], v) {
                [C] =>
                    (input, cont) =>
                        val contAny = cont.asInstanceOf[ContAny[A, S]]
                        input match
                            case Call(v, source) => Expanded(v, source.asInstanceOf[SourceAny[S]], contAny)
                            case Eval(v)         => ToExpand(v, contAny)
            }

        // Expands any `Batch.eval` calls, capturing items for each element in the sequence.
        // Returns a `Chunk[Chunk[A]]` to reduce `map` calls.
        def expand(items: Chunk[Item]): Chunk[Chunk[Item]] < S =
            Kyo.foreach(items) {
                case ToExpand[A @unchecked, S @unchecked](seq: Seq[Any], cont) =>
                    Kyo.foreach(seq)(v => capture(cont(v)))
                case item => Chunk(item)
            }

        // Groups all source calls (`Expanded`), calls their source functions, and reassembles the results.
        // Returns a `Chunk[Chunk[A]]` to reduce `map` calls.
        def flush(items: Chunk[Item]): Chunk[Chunk[Item]] < S =
            val pending: Map[SourceAny[S] | Unit, Seq[Item]] =
                items.groupBy {
                    case (Expanded[A @unchecked, S @unchecked](_, source, _)) => source
                    case _ => () // Used as a placeholder for items that aren't source calls
                }
            Kyo.foreach(pending.toSeq) { tuple =>
                (tuple: @unchecked) match
                    case (_: Unit, items) =>
                        // No need for flushing
                        Chunk.from(items)
                    case (source: SourceAny[S], items: Seq[Expanded[A, S]] @unchecked) =>
                        // Only request distinct items from the source
                        source(items.map(_.value).distinct).map { results =>
                            // Reassemble the results by iterating on the original collection
                            Kyo.foreach(items) { e =>
                                // Note how each value can have its own effects
                                capture(e.cont(results(e.value)))
                            }
                        }
            }
        end flush

        // The main evaluation loop that expands and flushes items until all values are final.
        def loop(items: Chunk[Item]): Chunk[A] < S =
            if !items.exists((_: @unchecked).isInstanceOf[Pending[A, S]]) then
                // All values are final, done
                items.asInstanceOf[Chunk[A]]
            else
                // The code repetition in the branches is a performance optimization to reduce
                // `map` calls.
                if items.exists((_: @unchecked).isInstanceOf[ToExpand[A, S]]) then
                    // Expand `Batch.eval` calls if present
                    expand(items).map { expanded =>
                        flush(expanded.flattenChunk)
                            .map(c => loop(c.flattenChunk))
                    }
                else
                    // No `Batch.eval` calls to expand, flush source calls directly
                    flush(items).map(c => loop(c.flattenChunk))
        end loop

        capture(v).map(initial => loop(Chunk(initial)))
    end run

    object internal:
        type Source[A, B, S] = Seq[A] => (A => B < S) < S
        type SourceAny[S]    = Source[Any, Any, S]

        sealed trait Op[A]
        case class Eval[A](seq: Seq[A])                         extends Op[A]
        case class Call[A, B, S](v: A, source: Source[A, B, S]) extends Op[B < (Batch & S)]

        enum Pending[A, S]:
            case ToExpand(op: Seq[Any], cont: ContAny[A, S])
            case Expanded(value: Any, source: SourceAny[S], cont: ContAny[A, S])

        type ContAny[A, S] = Any < (Batch & S) => (ToExpand[A, S] | Expanded[A, S] | A) < (Batch & S)
    end internal
end Batch
