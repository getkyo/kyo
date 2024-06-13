package kyo2

import kernel.<
import kernel.Frame
import kernel.Loop
import scala.annotation.tailrec

object Kyo:

    def zip[T1, T2, S](v1: T1 < S, v2: T2 < S)(using Frame): (T1, T2) < S =
        v1.map(t1 => v2.map(t2 => (t1, t2)))

    def zip[T1, T2, T3, S](v1: T1 < S, v2: T2 < S, v3: T3 < S)(using Frame): (T1, T2, T3) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => (t1, t2, t3))))

    def zip[T1, T2, T3, T4, S](v1: T1 < S, v2: T2 < S, v3: T3 < S, v4: T4 < S)(using Frame): (T1, T2, T3, T4) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => v4.map(t4 => (t1, t2, t3, t4)))))

    object seq:
        def map[T, U, S, S2](seq: Seq[T])(f: T => U < S2)(using Frame): Seq[U] < (S & S2) =
            seq.size match
                case 0 => Seq.empty
                case 1 => f(seq(0)).map(Seq(_))
                case size =>
                    seq match
                        case seq: IndexedSeq[T] =>
                            Loop.indexed(Chunk.empty[U]) { (idx, acc) =>
                                if idx == size then Loop.done(acc.toSeq)
                                else f(seq(idx)).map(u => Loop.continue(acc.append(u)))
                            }
                        case seq: List[T] =>
                            Loop.transform(seq, Chunk.empty[U]) { (seq, acc) =>
                                seq match
                                    case Nil          => Loop.done(acc.toSeq)
                                    case head :: tail => f(head).map(u => Loop.continue(tail, acc.append(u)))
                            }
                        case seq =>
                            Chunk.from(seq).map(f).map(_.toSeq)
                    end match

        def foreach[T, U, S](seq: Seq[T])(f: T => Unit < S)(using Frame): Unit < S =
            seq.size match
                case 0 =>
                case 1 => f(seq(0))
                case size =>
                    seq match
                        case seq: IndexedSeq[T] =>
                            Loop.indexed { idx =>
                                if idx == size then Loop.done
                                else f(seq(idx)).andThen(Loop.continue)
                            }
                        case seq: List[T] =>
                            Loop.transform(seq) {
                                case Nil          => Loop.done
                                case head :: tail => f(head).andThen(Loop.continue(tail))
                            }
                        case seq =>
                            Chunk.from(seq).foreach(f)
                    end match
            end match
        end foreach

        def foldLeft[T, U, S](seq: Seq[T])(acc: U)(f: (U, T) => U < S)(using Frame): U < S =
            seq.size match
                case 0 => acc
                case 1 => f(acc, seq(0))
                case size =>
                    seq match
                        case seq: IndexedSeq[T] =>
                            Loop.indexed(acc) { (idx, acc) =>
                                if idx == size then Loop.done(acc)
                                else f(acc, seq(idx)).map(Loop.continue(_))
                            }
                        case seq: List[T] =>
                            Loop.transform(seq, acc) { (seq, acc) =>
                                seq match
                                    case Nil          => Loop.done(acc)
                                    case head :: tail => f(acc, head).map(Loop.continue(tail, _))
                            }
                        case seq =>
                            Chunk.from(seq).foldLeft(acc)(f)
                    end match
            end match
        end foldLeft

        def collect[T, S](seq: Seq[T < S])(using Frame): Seq[T] < S =
            seq.size match
                case 0 => Seq.empty
                case 1 => seq(0).map(Seq(_))
                case size =>
                    seq match
                        case seq: IndexedSeq[T < S] =>
                            Loop.indexed(Chunk.empty[T]) { (idx, acc) =>
                                if idx == size then Loop.done(acc.toSeq)
                                else seq(idx).map(u => Loop.continue(acc.append(u)))
                            }
                        case seq: List[T < S] =>
                            Loop.transform(seq, Chunk.empty[T]) { (seq, acc) =>
                                seq match
                                    case Nil          => Loop.done(acc.toSeq)
                                    case head :: tail => head.map(u => Loop.continue(tail, acc.append(u)))
                            }
                        case seq =>
                            Chunk.from(seq).map(identity).map(_.toSeq)
                    end match

        def collectUnit[T, S](seq: Seq[Unit < S])(using Frame): Unit < S =
            seq.size match
                case 0 =>
                case 1 => seq(0)
                case size =>
                    seq match
                        case seq: IndexedSeq[Unit < S] =>
                            Loop.indexed { idx =>
                                if idx == size then Loop.done
                                else seq(idx).map(u => Loop.continue)
                            }
                        case seq: List[Unit < S] =>
                            Loop.transform(seq) { seq =>
                                seq match
                                    case Nil          => Loop.done
                                    case head :: tail => head.andThen(Loop.continue(tail))
                            }
                        case seq =>
                            Chunk.from(seq).foreach(identity)
                    end match
        end collectUnit

        def fill[T, S](n: Int)(v: => T < S)(using Frame): Seq[T] < S =
            Loop.indexed(Chunk.empty[T]) { (idx, acc) =>
                if idx == n then Loop.done(acc.toSeq)
                else v.map(t => Loop.continue(acc.append(t)))
            }
    end seq

end Kyo
