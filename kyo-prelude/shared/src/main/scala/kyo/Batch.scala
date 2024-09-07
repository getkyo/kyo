package kyo

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
sealed trait Batch[+S] extends ArrowEffect[Batch.Op[*, S], Id]

object Batch:

    enum Op[V, -S]:

        case Eval[A](seq: Seq[A]) extends Op[A, Any]

        case Call[A, B, S](
            v: A,
            source: Seq[A] => (A => B < S) < S,
            frame: Frame
        ) extends Op[B, S]
    end Op

    private inline def erasedTag[S]: Tag[Batch[S]] = Tag[Batch[Any]].asInstanceOf[Tag[Batch[S]]]

    /** Creates a batched computation from a source function.
      *
      * @param f
      *   The source function that takes a sequence of inputs and produces a sequence of outputs
      * @return
      *   A function that takes a single input and returns a batched computation
      */
    inline def source[A, B, S](f: Seq[A] => (A => B < S) < S)(using inline frame: Frame): A => B < Batch[S] =
        (v: A) => ArrowEffect.suspend[B](erasedTag[S], Op.Call(v, f, frame))

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
        ArrowEffect.suspend[A](erasedTag[Any], Op.Eval(seq))

    /** Runs a computation with Batch effect, executing all batched operations.
      *
      * @param v
      *   The computation to run
      * @return
      *   A sequence of results from executing the batched operations
      */
    def run[A: Flat, S, S2](v: A < (Batch[S] & S2))(using Frame): Seq[A] < (S & S2) =

        case class Cont(op: Op[?, S], cont: Any => (Cont | A) < (Batch[S] & S & S2))
        def runCont(v: (Cont | A) < (Batch[S] & S & S2)): (Cont | A) < (S & S2) =
            // TODO workaround, Flat macro isn't inferring correctly with nested classes
            import Flat.unsafe.bypass
            ArrowEffect.handle(erasedTag[S], v) {
                [C] => (input, cont) => Cont(input, v => cont(v.asInstanceOf[C]))
            }
        end runCont

        case class Expanded(v: Any, source: Seq[Any] => (Any => Any < S) < S, cont: Any => (Cont | A) < (Batch[S] & S & S2), frame: Frame)
        def expand(state: Seq[Cont | A]): Seq[Expanded | A] < (S & S2) =
            Kyo.foreach(state) {
                case Cont(Op.Eval(seq), cont) =>
                    Kyo.foreach(seq)(v => runCont(cont(v))).map(expand)
                case Cont(Op.Call(v, source, frame), cont) =>
                    Seq(Expanded(v, source.asInstanceOf, cont, frame))
                case a: A @unchecked =>
                    Seq(a)
            }.map(_.flatten)

        def loop(state: Seq[Cont | A]): Seq[A] < (S & S2) =
            expand(state).flatMap { expanded =>
                val (completed, pending) =
                    expanded.zipWithIndex.partitionMap {
                        case (e: Expanded @unchecked, idx) => Right((e, idx))
                        case (a: A @unchecked, idx)        => Left((a, idx))
                    }

                if pending.isEmpty then
                    completed.sortBy(_._2).map(_._1)
                else
                    val grouped = pending.groupBy(v => (v._1.source, v._1.frame))

                    Kyo.foreach(grouped.toSeq) { case ((source, frame), items) =>
                        val (expandedItems, indices) = items.unzip
                        val values                   = expandedItems.map(_.v)
                        val uniqueValues             = values.distinct

                        source(uniqueValues).flatMap { map =>
                            Kyo.foreach(expandedItems.zip(indices)) { case (e, idx) =>
                                runCont(map(e.v).map(e.cont)).map((_, idx))
                            }
                        }
                    }.flatMap { results =>
                        loop(completed.map(_._1) ++ results.flatten.map(_._1))
                    }
                end if
            }
        end loop
        runCont(v).map(c => loop(Seq(c)))
    end run

end Batch
