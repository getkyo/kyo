package kyo

import Batch.internal.*
import kyo.Tag
import kyo.kernel.*

/** The Batch effect allows for efficient batching and processing of operations.
  *
  * Batch is used to group multiple operations together and execute them in a single batch, which can lead to performance improvements,
  * especially when dealing with external systems or databases.
  *
  * @tparam S
  *   Effects from batch sources
  */
sealed trait Batch[+S] extends ArrowEffect[Op[*, S], Id]

object Batch:

    import internal.*

    private inline def erasedTag[S]: Tag[Batch[S]] = Tag[Batch[Any]].asInstanceOf[Tag[Batch[S]]]

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
    inline def source[A, B, S](f: Seq[A] => (A => B < S) < S)(using inline frame: Frame): A => B < Batch[S] =
        (v: A) => ArrowEffect.suspend[B](erasedTag[S], Call(v, f))

    /** Creates a batched computation from a source function that returns a Map.
      *
      * @param f
      *   The source function that takes a sequence of inputs and returns a Map of results
      * @return
      *   A function that takes a single input and returns a batched computation
      */
    inline def sourceMap[A, B, S](f: Seq[A] => Map[A, B] < S)(using inline frame: Frame): A => B < Batch[S] =
        source[A, B, S] { input =>
            f(input).map { output =>
                require(
                    input.size == output.size,
                    s"Source created at ${frame.parse.position} returned a different number of elements than input: ${input.size} != ${output.size}"
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
    inline def sourceSeq[A, B, S](f: Seq[A] => Seq[B] < S)(using inline frame: Frame): A => B < Batch[S] =
        sourceMap[A, B, S] { input =>
            f(input).map { output =>
                require(
                    input.size == output.size,
                    s"Source created at ${frame.parse.position} returned a different number of elements than input: ${input.size} != ${output.size}"
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
    inline def eval[A](seq: Seq[A])(using inline frame: Frame): A < Batch[Any] =
        ArrowEffect.suspend[A](erasedTag[Any], seq)

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
    inline def foreach[A, B, S](seq: Seq[A])(inline f: A => B < S): B < (Batch[Any] & S) =
        ArrowEffect.suspendMap[A](erasedTag[Any], seq)(f)

    /** Runs a computation with Batch effect, executing all batched operations.
      *
      * @param v
      *   The computation to run
      * @return
      *   A sequence of results from executing the batched operations
      */
    def run[A: Flat, S, S2](v: A < (Batch[S] & S2))(using Frame): Chunk[A] < (S & S2) =

        type SourceAny = Source[Any, Any, S]
        type ContAny   = Any => (ToExpand | Expanded | A) < (Batch[S] & S & S2)

        type Item = A | ToExpand | Expanded
        abstract class Pending
        case class ToExpand(op: Seq[Any], cont: ContAny)                  extends Pending
        case class Expanded(value: Any, source: SourceAny, cont: ContAny) extends Pending

        def capture(v: Item < (Batch[S] & S & S2)): Item < (S & S2) =
            ArrowEffect.handle(erasedTag[S], v) {
                [C] =>
                    (input, cont) =>
                        val contAny = cont.asInstanceOf[ContAny]
                        input match
                            case Call(v, source) => Expanded(v, source.asInstanceOf[SourceAny], contAny)
                            case v: Seq[C]       => ToExpand(v, contAny)
            }

        def expand(items: Chunk[Item]): Chunk[Item] < (S & S2) =
            if !items.exists((_: @unchecked).isInstanceOf[ToExpand]) then items
            else
                Kyo.foreach(items) {
                    case ToExpand(seq: Seq[Any], cont) =>
                        Kyo.foreach(seq)(v => capture(cont(v)))
                    case item => Chunk(item)
                }.map(_.flattenChunk)

        def flush(items: Chunk[Item]): Chunk[Item] < (S & S2) =
            val pending: Map[SourceAny | Unit, Seq[Item]] =
                items.groupBy {
                    case Expanded(_, source, _) => source
                    case _                      => ()
                }
            Kyo.foreach(pending.toSeq) {
                case (_: Unit, items) => Chunk.from(items)
                case (source: SourceAny, items: Seq[Expanded] @unchecked) =>
                    source(items.map(_.value).distinct).map { map =>
                        Kyo.foreach(items) { e =>
                            capture(map(e.value).map(e.cont))
                        }
                    }
            }.map(_.flattenChunk)
        end flush

        def loop(items: Chunk[Item]): Chunk[A] < (S & S2) =
            if !items.exists((_: @unchecked).isInstanceOf[Pending]) then
                items.asInstanceOf[Chunk[A]]
            else
                expand(items).map { expanded =>
                    flush(expanded).map(loop)
                }
        end loop

        capture(v).map(initial => loop(Chunk(initial)))
    end run

    object internal:
        type Source[A, B, S] = Seq[A] => (A => B < S) < S

        type Op[V, -S] = Seq[V] | Call[?, V, S]
        case class Call[A, B, -S](v: A, source: Source[A, B, S])
    end internal

end Batch
