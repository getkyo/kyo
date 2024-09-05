package kyo

import kyo.Tag
import kyo.kernel.*

sealed trait Batch[+S] extends ArrowEffect[Batch.Op[*, S], Id]

object Batch:

    enum Op[V, -S]:

        case Eval[A](seq: Seq[A]) extends Op[A, Any]

        case Call[A, B, S](
            v: A,
            source: Seq[A] => Seq[B] < S,
            frame: Frame
        ) extends Op[B, S]
    end Op

    private inline def erasedTag[S]: Tag[Batch[S]] = Tag[Batch[Any]].asInstanceOf[Tag[Batch[S]]]

    inline def source[A, B, S](f: Seq[A] => Seq[B] < S)(using inline frame: Frame): A => B < Batch[S] =
        (v: A) => ArrowEffect.suspend[B](erasedTag[S], Op.Call(v, f, frame))

    inline def eval[A](seq: Seq[A])(using inline frame: Frame): A < Batch[Any] =
        ArrowEffect.suspend[A](erasedTag[Any], Op.Eval(seq))

    def run[A: Flat, S, S2](v: A < (Batch[S] & S2))(using Frame): Seq[A] < (S & S2) =

        case class Cont(op: Op[?, S], cont: Any => (Cont | A) < (Batch[S] & S2))
        def runCont(v: (Cont | A) < (Batch[S] & S2)): (Cont | A) < S2 =
            // TODO workaround, Flat macro isn't inferring correctly with nested classes
            import Flat.unsafe.bypass
            ArrowEffect.handle(erasedTag[S], v) {
                [C] => (input, cont) => Cont(input, v => cont(v.asInstanceOf[C]))
            }
        end runCont

        case class Expanded(v: Any, source: Seq[Any] => Seq[Any] < S, cont: Any => (Cont | A) < (Batch[S] & S2), frame: Frame)
        def expand(state: Seq[Cont | A]): Seq[Expanded | A] < S2 =
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
                        val (values, conts)          = expandedItems.map(e => (e.v, e.cont)).unzip
                        val uniqueValues             = values.distinct

                        source(uniqueValues).flatMap { seq =>
                            require(
                                uniqueValues.size == seq.size,
                                s"Source created at ${frame.parse.position} returned a different number of elements than input: ${uniqueValues.size} != ${seq.size}"
                            )
                            if seq.isEmpty then Seq.empty
                            else
                                val results   = seq.toIndexedSeq
                                val resultMap = uniqueValues.zip(results).toMap
                                Kyo.foreach(expandedItems.zip(indices)) { case (e, idx) =>
                                    runCont(e.cont(resultMap(e.v.asInstanceOf[Any]))).map((_, idx))
                                }
                            end if
                        }
                    }.flatMap { results =>
                        loop((completed ++ results.flatten).map(_._1))
                    }
                end if
            }
        end loop
        runCont(v).map(c => loop(Seq(c)))
    end run

end Batch
