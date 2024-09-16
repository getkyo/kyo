package kyo

import kernel.Frame
import kernel.Loop
import scala.annotation.tailrec

/** Object containing utility functions for working with Kyo effects. */
object Kyo:

    /** Zips two effects into a tuple.
      *
      * @param v1
      *   The first effect
      * @param v2
      *   The second effect
      * @return
      *   A new effect that produces a tuple of the results
      */
    def zip[A1, A2, S](v1: A1 < S, v2: A2 < S)(using Frame): (A1, A2) < S =
        v1.map(t1 => v2.map(t2 => (t1, t2)))

    /** Zips three effects into a tuple.
      *
      * @param v1
      *   The first effect
      * @param v2
      *   The second effect
      * @param v3
      *   The third effect
      * @return
      *   A new effect that produces a tuple of the results
      */
    def zip[A1, A2, A3, S](v1: A1 < S, v2: A2 < S, v3: A3 < S)(using Frame): (A1, A2, A3) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => (t1, t2, t3))))

    /** Zips four effects into a tuple.
      *
      * @param v1
      *   The first effect
      * @param v2
      *   The second effect
      * @param v3
      *   The third effect
      * @param v4
      *   The fourth effect
      * @return
      *   A new effect that produces a tuple of the results
      */
    def zip[A1, A2, A3, A4, S](v1: A1 < S, v2: A2 < S, v3: A3 < S, v4: A4 < S)(using Frame): (A1, A2, A3, A4) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => v4.map(t4 => (t1, t2, t3, t4)))))

    /** Applies an effect-producing function to each element of a sequence.
      *
      * @param seq
      *   The input sequence
      * @param f
      *   The effect-producing function to apply to each element
      * @return
      *   A new effect that produces a Chunk of results
      */
    def foreach[A, B, S, S2](seq: Seq[A])(f: A => B < S2)(using Frame): Chunk[B] < (S & S2) =
        seq.knownSize match
            case 0 => Chunk.empty
            case 1 => f(seq(0)).map(Chunk(_))
            case _ =>
                seq match
                    case seq: List[A] =>
                        Loop(seq, Chunk.empty[B]) { (seq, acc) =>
                            seq match
                                case Nil          => Loop.done(acc)
                                case head :: tail => f(head).map(u => Loop.continue(tail, acc.append(u)))
                        }
                    case seq =>
                        val indexed = toIndexed(seq)
                        val size    = indexed.size
                        Loop.indexed(Chunk.empty[B]) { (idx, acc) =>
                            if idx == size then Loop.done(acc)
                            else f(indexed(idx)).map(u => Loop.continue(acc.append(u)))
                        }
                end match
    /** Applies an effect-producing function to each element of a sequence, discarding the results.
      *
      * @param seq
      *   The input sequence
      * @param f
      *   The effect-producing function to apply to each element
      * @return
      *   A new effect that produces Unit
      */
    def foreachDiscard[A, B, S](seq: Seq[A])(f: A => Unit < S)(using Frame): Unit < S =
        seq.knownSize match
            case 0 =>
            case 1 => f(seq(0))
            case _ =>
                seq match
                    case seq: List[A] =>
                        Loop(seq) {
                            case Nil          => Loop.done
                            case head :: tail => f(head).andThen(Loop.continue(tail))
                        }
                    case seq =>
                        val indexed = toIndexed(seq)
                        val size    = indexed.size
                        Loop.indexed { idx =>
                            if idx == size then Loop.done
                            else f(indexed(idx)).andThen(Loop.continue)
                        }
                end match
        end match
    end foreachDiscard

    /** Filters elements of a sequence based on an effect-producing predicate.
      *
      * @param seq
      *   The input sequence
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Chunk of filtered elements
      */
    def filter[A, S, S2](seq: Seq[A])(f: A => Boolean < S2)(using Frame): Chunk[A] < (S & S2) =
        seq.knownSize match
            case 0 => Chunk.empty
            case _ =>
                seq match
                    case seq: List[A] =>
                        Loop(seq, Chunk.empty[A]) { (seq, acc) =>
                            seq match
                                case Nil => Loop.done(acc)
                                case head :: tail =>
                                    f(head).map {
                                        case true  => Loop.continue(tail, acc.append(head))
                                        case false => Loop.continue(tail, acc)
                                    }
                        }
                    case seq =>
                        val indexed = toIndexed(seq)
                        val size    = indexed.size
                        Loop.indexed(Chunk.empty[A]) { (idx, acc) =>
                            if idx == size then Loop.done(acc)
                            else
                                val curr = indexed(idx)
                                f(curr).map {
                                    case true  => Loop.continue(acc.append(curr))
                                    case false => Loop.continue(acc)
                                }
                        }
                end match
    /** Folds over a sequence with an effect-producing function.
      *
      * @param seq
      *   The input sequence
      * @param acc
      *   The initial accumulator value
      * @param f
      *   The effect-producing folding function
      * @return
      *   A new effect that produces the final accumulated value
      */
    def foldLeft[A, B, S](seq: Seq[A])(acc: B)(f: (B, A) => B < S)(using Frame): B < S =
        seq.knownSize match
            case 0 => acc
            case 1 => f(acc, seq(0))
            case _ =>
                seq match
                    case seq: List[A] =>
                        Loop(seq, acc) { (seq, acc) =>
                            seq match
                                case Nil          => Loop.done(acc)
                                case head :: tail => f(acc, head).map(Loop.continue(tail, _))
                        }
                    case seq =>
                        val indexed = toIndexed(seq)
                        val size    = indexed.size
                        Loop.indexed(acc) { (idx, acc) =>
                            if idx == size then Loop.done(acc)
                            else f(acc, indexed(idx)).map(Loop.continue(_))
                        }
                end match
        end match
    end foldLeft

    /** Collects the results of a sequence of effects into a single effect.
      *
      * @param seq
      *   The sequence of effects
      * @return
      *   A new effect that produces a Chunk of results
      */
    def collect[A, S](seq: Seq[A < S])(using Frame): Chunk[A] < S =
        seq.knownSize match
            case 0 => Chunk.empty
            case 1 => seq(0).map(Chunk(_))
            case _ =>
                seq match
                    case seq: List[A < S] =>
                        Loop(seq, Chunk.empty[A]) { (seq, acc) =>
                            seq match
                                case Nil          => Loop.done(acc)
                                case head :: tail => head.map(u => Loop.continue(tail, acc.append(u)))
                        }
                    case seq =>
                        val indexed = toIndexed(seq)
                        val size    = indexed.size
                        Loop.indexed(Chunk.empty[A]) { (idx, acc) =>
                            if idx == size then Loop.done(acc)
                            else indexed(idx).map(u => Loop.continue(acc.append(u)))
                        }
                end match
    /** Collects the results of a sequence of effects, discarding the results.
      *
      * @param seq
      *   The sequence of effects
      * @return
      *   A new effect that produces Unit
      */
    def collectDiscard[A, S](seq: Seq[A < S])(using Frame): Unit < S =
        seq.knownSize match
            case 0 =>
            case 1 => seq(0).unit
            case _ =>
                seq match
                    case seq: List[A < S] =>
                        Loop(seq) { seq =>
                            seq match
                                case Nil          => Loop.done
                                case head :: tail => head.map(_ => Loop.continue(tail))
                        }
                    case seq =>
                        val indexed = toIndexed(seq)
                        val size    = indexed.size
                        Loop.indexed { idx =>
                            if idx == size then Loop.done
                            else indexed(idx).map(_ => Loop.continue)
                        }
                end match
    /** Takes elements from a sequence while a predicate holds true.
      *
      * @param seq
      *   The input sequence
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Chunk of taken elements
      */
    def takeWhile[A, S](seq: Seq[A])(f: A => Boolean < S)(using Frame): Chunk[A] < S =
        seq.knownSize match
            case 0 => Chunk.empty
            case _ =>
                seq match
                    case seq: List[A] =>
                        Loop(seq, Chunk.empty[A]) { (seq, acc) =>
                            seq match
                                case Nil => Loop.done(acc)
                                case head :: tail =>
                                    f(head).map {
                                        case true  => Loop.continue(tail, acc.append(head))
                                        case false => Loop.done(acc)
                                    }
                        }
                    case seq =>
                        val indexed = toIndexed(seq)
                        val size    = indexed.size
                        Loop.indexed(Chunk.empty[A]) { (idx, acc) =>
                            if idx == size then Loop.done(acc)
                            else
                                val curr = indexed(idx)
                                f(curr).map {
                                    case true  => Loop.continue(acc.append(curr))
                                    case false => Loop.done(acc)
                                }
                        }
                end match
    /** Drops elements from a sequence while a predicate holds true.
      *
      * @param seq
      *   The input sequence
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Chunk of remaining elements
      */
    def dropWhile[A, S](seq: Seq[A])(f: A => Boolean < S)(using Frame): Chunk[A] < S =
        seq.knownSize match
            case 0 => Chunk.empty
            case _ =>
                seq match
                    case seq: List[A] =>
                        Loop(seq) { seq =>
                            seq match
                                case Nil => Loop.done(Chunk.empty)
                                case head :: tail =>
                                    f(head).map {
                                        case true  => Loop.continue(tail)
                                        case false => Loop.done(Chunk.from(tail))
                                    }
                        }
                    case seq =>
                        val indexed = toIndexed(seq)
                        val size    = indexed.size
                        Loop.indexed { idx =>
                            if idx == size then Loop.done(Chunk.empty)
                            else
                                val curr = indexed(idx)
                                f(curr).map {
                                    case true  => Loop.continue
                                    case false => Loop.done(Chunk.from(indexed.drop(idx)))
                                }
                        }
                end match
    /** Creates a Chunk by repeating an effect-producing value.
      *
      * @param n
      *   The number of times to repeat the value
      * @param v
      *   The effect-producing value
      * @return
      *   A new effect that produces a Chunk of repeated values
      */
    def fill[A, S](n: Int)(v: => A < S)(using Frame): Chunk[A] < S =
        Loop.indexed(Chunk.empty[A]) { (idx, acc) =>
            if idx == n then Loop.done(acc)
            else v.map(t => Loop.continue(acc.append(t)))
        }

    private def toIndexed[A](seq: Seq[A]): Seq[A] =
        seq match
            case seq: IndexedSeq[A] => seq
            case seq                => Chunk.from(seq)

end Kyo
