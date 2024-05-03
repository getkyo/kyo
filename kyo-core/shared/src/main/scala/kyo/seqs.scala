package kyo

import kyo.core.*

object Seqs:

    def map[T, U, S, S2](seq: Seq[T])(f: T => U < S2): Seq[U] < (S & S2) =
        seq.size match
            case 0 => Seq.empty
            case 1 => f(seq(0)).map(Seq(_))
            case size =>
                seq match
                    case seq: IndexedSeq[T] =>
                        Loops.indexed(Chunks.init[U]) { (idx, acc) =>
                            if idx == size then Loops.done(acc.toSeq)
                            else f(seq(idx)).map(u => Loops.continue(acc.append(u)))
                        }
                    case seq: List[T] =>
                        Loops.transform(seq, Chunks.init[U]) { (seq, acc) =>
                            seq match
                                case Nil          => Loops.done(acc.toSeq)
                                case head :: tail => f(head).map(u => Loops.continue(tail, acc.append(u)))
                        }
                    case seq =>
                        Chunks.initSeq(seq).map(f).map(_.toSeq)
                end match

    def foreach[T, U, S](seq: Seq[T])(f: T => Unit < S): Unit < S =
        seq.size match
            case 0 =>
            case 1 => f(seq(0))
            case size =>
                seq match
                    case seq: IndexedSeq[T] =>
                        Loops.indexed { idx =>
                            if idx == size then Loops.done
                            else f(seq(idx)).andThen(Loops.continue)
                        }
                    case seq: List[T] =>
                        Loops.transform(seq) {
                            case Nil          => Loops.done
                            case head :: tail => f(head).andThen(Loops.continue(tail))
                        }
                    case seq =>
                        Chunks.initSeq(seq).foreach(f)
                end match
        end match
    end foreach

    def foldLeft[T, U: Flat, S](seq: Seq[T])(acc: U)(f: (U, T) => U < S): U < S =
        seq.size match
            case 0 => acc
            case 1 => f(acc, seq(0))
            case size =>
                seq match
                    case seq: IndexedSeq[T] =>
                        Loops.indexed(acc) { (idx, acc) =>
                            if idx == size then Loops.done(acc)
                            else f(acc, seq(idx)).map(Loops.continue)
                        }
                    case seq: List[T] =>
                        Loops.transform(seq, acc) { (seq, acc) =>
                            seq match
                                case Nil          => Loops.done(acc)
                                case head :: tail => f(acc, head).map(Loops.continue(tail, _))
                        }
                    case seq =>
                        Chunks.initSeq(seq).foldLeft(acc)(f)
                end match
        end match
    end foldLeft

    def collect[T, S](seq: Seq[T < S]): Seq[T] < S =
        seq.size match
            case 0 => Seq.empty
            case 1 => seq(0).map(Seq(_))
            case size =>
                seq match
                    case seq: IndexedSeq[T < S] =>
                        Loops.indexed(Chunks.init[T]) { (idx, acc) =>
                            if idx == size then Loops.done(acc.toSeq)
                            else seq(idx).map(u => Loops.continue(acc.append(u)))
                        }
                    case seq: List[T < S] =>
                        Loops.transform(seq, Chunks.init[T]) { (seq, acc) =>
                            seq match
                                case Nil          => Loops.done(acc.toSeq)
                                case head :: tail => head.map(u => Loops.continue(tail, acc.append(u)))
                        }
                    case seq =>
                        Chunks.initSeq(seq).map(identity).map(_.toSeq)
                end match

    def collectUnit[T, S](seq: Seq[Unit < S]): Unit < S =
        seq.size match
            case 0 =>
            case 1 => seq(0)
            case size =>
                seq match
                    case seq: IndexedSeq[Unit < S] =>
                        Loops.indexed { idx =>
                            if idx == size then Loops.done
                            else seq(idx).map(u => Loops.continue)
                        }
                    case seq: List[Unit < S] =>
                        Loops.transform(seq) { seq =>
                            seq match
                                case Nil          => Loops.done
                                case head :: tail => head.andThen(Loops.continue(tail))
                        }
                    case seq =>
                        Chunks.initSeq(seq).foreach(identity)
                end match
    end collectUnit

    def fill[T, S](n: Int)(v: => T < S): Seq[T] < S =
        Loops.indexed(Chunks.init[T]) { (idx, acc) =>
            if idx == n then Loops.done(acc.toSeq)
            else v.map(t => Loops.continue(acc.append(t)))
        }
end Seqs
