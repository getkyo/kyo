package kyo

import kernel.<
import kernel.Frame
import kernel.Loop
import scala.annotation.tailrec

object Kyo:

    def zip[A1, A2, S](v1: A1 < S, v2: A2 < S)(using Frame): (A1, A2) < S =
        v1.map(t1 => v2.map(t2 => (t1, t2)))

    def zip[A1, A2, A3, S](v1: A1 < S, v2: A2 < S, v3: A3 < S)(using Frame): (A1, A2, A3) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => (t1, t2, t3))))

    def zip[A1, A2, A3, A4, S](v1: A1 < S, v2: A2 < S, v3: A3 < S, v4: A4 < S)(using Frame): (A1, A2, A3, A4) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => v4.map(t4 => (t1, t2, t3, t4)))))

    object seq:
        def map[A, B, S, S2](seq: Seq[A])(f: A => B < S2)(using Frame): Seq[B] < (S & S2) =
            seq.size match
                case 0 => Seq.empty
                case 1 => f(seq(0)).map(Seq(_))
                case size =>
                    seq match
                        case seq: IndexedSeq[A] =>
                            Loop.indexed(Chunk.empty[B]) { (idx, acc) =>
                                if idx == size then Loop.done(acc.toSeq)
                                else f(seq(idx)).map(u => Loop.continue(acc.append(u)))
                            }
                        case seq: List[A] =>
                            Loop(seq, Chunk.empty[B]) { (seq, acc) =>
                                seq match
                                    case Nil          => Loop.done(acc.toSeq)
                                    case head :: tail => f(head).map(u => Loop.continue(tail, acc.append(u)))
                            }
                        case seq =>
                            Chunk.from(seq).map(f).map(_.toSeq)
                    end match

        def foreach[A, B, S](seq: Seq[A])(f: A => Unit < S)(using Frame): Unit < S =
            seq.size match
                case 0 =>
                case 1 => f(seq(0))
                case size =>
                    seq match
                        case seq: IndexedSeq[A] =>
                            Loop.indexed { idx =>
                                if idx == size then Loop.done
                                else f(seq(idx)).andThen(Loop.continue)
                            }
                        case seq: List[A] =>
                            Loop(seq) {
                                case Nil          => Loop.done
                                case head :: tail => f(head).andThen(Loop.continue(tail))
                            }
                        case seq =>
                            Chunk.from(seq).foreach(f)
                    end match
            end match
        end foreach

        def foldLeft[A, B, S](seq: Seq[A])(acc: B)(f: (B, A) => B < S)(using Frame): B < S =
            seq.size match
                case 0 => acc
                case 1 => f(acc, seq(0))
                case size =>
                    seq match
                        case seq: IndexedSeq[A] =>
                            Loop.indexed(acc) { (idx, acc) =>
                                if idx == size then Loop.done(acc)
                                else f(acc, seq(idx)).map(Loop.continue(_))
                            }
                        case seq: List[A] =>
                            Loop(seq, acc) { (seq, acc) =>
                                seq match
                                    case Nil          => Loop.done(acc)
                                    case head :: tail => f(acc, head).map(Loop.continue(tail, _))
                            }
                        case seq =>
                            Chunk.from(seq).foldLeft(acc)(f)
                    end match
            end match
        end foldLeft

        def collect[A, S](seq: Seq[A < S])(using Frame): Seq[A] < S =
            seq.size match
                case 0 => Seq.empty
                case 1 => seq(0).map(Seq(_))
                case size =>
                    seq match
                        case seq: IndexedSeq[A < S] =>
                            Loop.indexed(Chunk.empty[A]) { (idx, acc) =>
                                if idx == size then Loop.done(acc.toSeq)
                                else seq(idx).map(u => Loop.continue(acc.append(u)))
                            }
                        case seq: List[A < S] =>
                            Loop(seq, Chunk.empty[A]) { (seq, acc) =>
                                seq match
                                    case Nil          => Loop.done(acc.toSeq)
                                    case head :: tail => head.map(u => Loop.continue(tail, acc.append(u)))
                            }
                        case seq =>
                            Chunk.from(seq).map(identity).map(_.toSeq)
                    end match

        def collectUnit[A, S](seq: Seq[Unit < S])(using Frame): Unit < S =
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
                            Loop(seq) { seq =>
                                seq match
                                    case Nil          => Loop.done
                                    case head :: tail => head.andThen(Loop.continue(tail))
                            }
                        case seq =>
                            Chunk.from(seq).foreach(identity)
                    end match
        end collectUnit

        def fill[A, S](n: Int)(v: => A < S)(using Frame): Seq[A] < S =
            Loop.indexed(Chunk.empty[A]) { (idx, acc) =>
                if idx == n then Loop.done(acc.toSeq)
                else v.map(t => Loop.continue(acc.append(t)))
            }
    end seq

end Kyo
