package kyo

import kernel.Loop
import kyo.kernel.internal.Safepoint
import scala.annotation.tailrec
import scala.collection.immutable.LinearSeq

/** Object containing utility functions for working with Kyo effects.
  *
  * Provides sequential operations for working with collections and effects. For concurrent/parallel variants of these operations, see the
  * Async effect. Use the Kyo companion object methods when sequential processing is sufficient and Async when concurrent processing would
  * be beneficial.
  */
object Kyo:

    /** Explicitly lifts a pure plain value to a Kyo computation.
      *
      * While pure values are automatically lifted into Kyo computations in most cases, this method can be useful in specific scenarios,
      * such as in if/else expressions, to help with type inference.
      *
      * Note: This is a zero-cost operation that simply wraps the value in a Kyo computation type without introducing any effect suspension.
      *
      * @tparam A
      *   The type of the value
      * @tparam S
      *   The effect context (can be Any)
      * @param v
      *   The value to lift into the effect context
      * @return
      *   A computation that directly produces the given value without suspension
      */
    inline def lift[A, S](inline v: A): A < S = v

    /** Returns a pure effect that produces Unit.
      *
      * This is exactly equivalent to `pure(())`, as both simply lift the Unit value into the effect context without introducing any effect
      * suspension.
      *
      * @tparam S
      *   The effect context (can be Any)
      * @return
      *   A computation that directly produces Unit without suspension
      */
    inline def unit[S]: Unit < S = ()

    /** Zips two effects into a tuple.
      */
    def zip[A1, A2, S](v1: A1 < S, v2: A2 < S)(using Frame): (A1, A2) < S =
        v1.map(t1 => v2.map(t2 => (t1, t2)))

    /** Zips three effects into a tuple.
      */
    def zip[A1, A2, A3, S](v1: A1 < S, v2: A2 < S, v3: A3 < S)(using Frame): (A1, A2, A3) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => (t1, t2, t3))))

    /** Zips four effects into a tuple.
      */
    def zip[A1, A2, A3, A4, S](v1: A1 < S, v2: A2 < S, v3: A3 < S, v4: A4 < S)(using Frame): (A1, A2, A3, A4) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => v4.map(t4 => (t1, t2, t3, t4)))))

    /** Zips five effects into a tuple.
      */
    def zip[A1, A2, A3, A4, A5, S](v1: A1 < S, v2: A2 < S, v3: A3 < S, v4: A4 < S, v5: A5 < S)(using Frame): (A1, A2, A3, A4, A5) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => v4.map(t4 => v5.map(t5 => (t1, t2, t3, t4, t5))))))

    /** Zips six effects into a tuple.
      */
    def zip[A1, A2, A3, A4, A5, A6, S](v1: A1 < S, v2: A2 < S, v3: A3 < S, v4: A4 < S, v5: A5 < S, v6: A6 < S)(using
        Frame
    ): (A1, A2, A3, A4, A5, A6) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => v4.map(t4 => v5.map(t5 => v6.map(t6 => (t1, t2, t3, t4, t5, t6)))))))

    /** Zips seven effects into a tuple. A new effect that produces a tuple of the results
      */
    def zip[A1, A2, A3, A4, A5, A6, A7, S](v1: A1 < S, v2: A2 < S, v3: A3 < S, v4: A4 < S, v5: A5 < S, v6: A6 < S, v7: A7 < S)(using
        Frame
    ): (A1, A2, A3, A4, A5, A6, A7) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => v4.map(t4 => v5.map(t5 => v6.map(t6 => v7.map(t7 => (t1, t2, t3, t4, t5, t6, t7))))))))

    /** Zips eight effects into a tuple.
      */
    def zip[A1, A2, A3, A4, A5, A6, A7, A8, S](
        v1: A1 < S,
        v2: A2 < S,
        v3: A3 < S,
        v4: A4 < S,
        v5: A5 < S,
        v6: A6 < S,
        v7: A7 < S,
        v8: A8 < S
    )(using Frame): (A1, A2, A3, A4, A5, A6, A7, A8) < S =
        v1.map(t1 =>
            v2.map(t2 =>
                v3.map(t3 => v4.map(t4 => v5.map(t5 => v6.map(t6 => v7.map(t7 => v8.map(t8 => (t1, t2, t3, t4, t5, t6, t7, t8)))))))
            )
        )

    /** Zips nine effects into a tuple.
      */
    def zip[A1, A2, A3, A4, A5, A6, A7, A8, A9, S](
        v1: A1 < S,
        v2: A2 < S,
        v3: A3 < S,
        v4: A4 < S,
        v5: A5 < S,
        v6: A6 < S,
        v7: A7 < S,
        v8: A8 < S,
        v9: A9 < S
    )(using Frame): (A1, A2, A3, A4, A5, A6, A7, A8, A9) < S =
        v1.map(t1 =>
            v2.map(t2 =>
                v3.map(t3 =>
                    v4.map(t4 => v5.map(t5 => v6.map(t6 => v7.map(t7 => v8.map(t8 => v9.map(t9 => (t1, t2, t3, t4, t5, t6, t7, t8, t9)))))))
                )
            )
        )

    /** Zips ten effects into a tuple.
      */
    def zip[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, S](
        v1: A1 < S,
        v2: A2 < S,
        v3: A3 < S,
        v4: A4 < S,
        v5: A5 < S,
        v6: A6 < S,
        v7: A7 < S,
        v8: A8 < S,
        v9: A9 < S,
        v10: A10 < S
    )(using Frame): (A1, A2, A3, A4, A5, A6, A7, A8, A9, A10) < S =
        v1.map(t1 =>
            v2.map(t2 =>
                v3.map(t3 =>
                    v4.map(t4 =>
                        v5.map(t5 =>
                            v6.map(t6 =>
                                v7.map(t7 => v8.map(t8 => v9.map(t9 => v10.map(t10 => (t1, t2, t3, t4, t5, t6, t7, t8, t9, t10)))))
                            )
                        )
                    )
                )
            )
        )

    /** Applies an effect-producing function to each element of an `IterableOnce`.
      *
      * @param source
      *   The input `IterableOnce`
      * @param f
      *   The effect-producing function to apply to each element
      * @return
      *   A new effect that produces a Chunk of results
      */
    def foreach[A, B, S](source: IterableOnce[A])(f: Safepoint ?=> A => B < S)(using Frame, Safepoint): Chunk[B] < S =
        source.knownSize match
            case 0 => Chunk.empty
            case 1 =>
                val head = source.iterator.next()
                f(head).map(Chunk.single(_))
            case _ =>
                source match
                    case linearSeq: LinearSeq[A] =>
                        Loop(linearSeq, Chunk.empty[B]): (seq, acc) =>
                            if seq.isEmpty then Loop.done(acc)
                            else f(seq.head).map(u => Loop.continue(seq.tail, acc.append(u)))
                    case other =>
                        val indexedSeq = toIndexed(other)
                        val size       = indexedSeq.length
                        Loop.indexed(Chunk.empty[B]): (idx, acc) =>
                            if idx == size then Loop.done(acc)
                            else f(indexedSeq(idx)).map(u => Loop.continue(acc.append(u)))
    end foreach

    /** Applies an effect-producing function to each element of a sequence along with its index.
      *
      * @param source
      *   The input `IterableOnce`
      * @param f
      *   The effect-producing function to apply to each element and its index
      * @return
      *   A new effect that produces a Chunk of results
      */
    def foreachIndexed[A, B, S](source: IterableOnce[A])(f: Safepoint ?=> (Int, A) => B < S)(using Frame, Safepoint): Chunk[B] < S =
        source.knownSize match
            case 0 => Chunk.empty
            case 1 =>
                val head = source.iterator.next()
                f(0, head).map(Chunk.single(_))
            case _ =>
                source match
                    case linearSeq: LinearSeq[A] =>
                        Loop.indexed(linearSeq, Chunk.empty[B]): (idx, seq, acc) =>
                            if seq.isEmpty then Loop.done(acc)
                            else f(idx, seq.head).map(u => Loop.continue(seq.tail, acc.append(u)))
                    case other =>
                        val indexedSeq = toIndexed(other)
                        val size       = indexedSeq.length
                        Loop.indexed(Chunk.empty[B]): (idx, acc) =>
                            if idx == size then Loop.done(acc)
                            else f(idx, indexedSeq(idx)).map(u => Loop.continue(acc.append(u)))
    end foreachIndexed

    /** Applies an effect-producing function to each element of an `IterableOnce`, discarding the results.
      *
      * @param source
      *   The input `IterableOnce`
      * @param f
      *   The effect-producing function to apply to each element
      * @return
      *   A new effect that produces Unit
      */
    def foreachDiscard[A, B, S](source: IterableOnce[A])(f: Safepoint ?=> A => Any < S)(using Frame, Safepoint): Unit < S =
        source.knownSize match
            case 0 =>
            case 1 =>
                val head = source.iterator.next()
                f(head).unit
            case _ =>
                source match
                    case linearSeq: LinearSeq[A] =>
                        Loop(linearSeq): (seq) =>
                            if seq.isEmpty then Loop.done
                            else f(seq.head).andThen(Loop.continue(seq.tail))
                    case other =>
                        val indexedSeq = toIndexed(other)
                        val size       = indexedSeq.length
                        Loop.indexed: idx =>
                            if idx == size then Loop.done
                            else f(indexedSeq(idx)).andThen(Loop.continue)
        end match
    end foreachDiscard

    /** Filters elements of an `IterableOnce` based on an effect-producing predicate.
      *
      * @param source
      *   The input `IterableOnce`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Chunk of filtered elements
      */
    def filter[A, S](source: IterableOnce[A])(f: Safepoint ?=> A => Boolean < S)(using Frame, Safepoint): Chunk[A] < S =
        source.knownSize match
            case 0 => Chunk.empty
            case _ =>
                source match
                    case linearSeq: LinearSeq[A] =>
                        Loop(linearSeq, Chunk.empty[A]): (seq, acc) =>
                            if seq.isEmpty then Loop.done(acc)
                            else
                                val head = seq.head
                                f(head).map:
                                    case true  => Loop.continue(seq.tail, acc.append(head))
                                    case false => Loop.continue(seq.tail, acc)
                    case other =>
                        val indexedSeq = toIndexed(other)
                        val size       = indexedSeq.length
                        Loop.indexed(Chunk.empty[A]): (idx, acc) =>
                            if idx == size then Loop.done(acc)
                            else
                                val curr = indexedSeq(idx)
                                f(curr).map:
                                    case true  => Loop.continue(acc.append(curr))
                                    case false => Loop.continue(acc)
        end match
    end filter

    /** Folds over an `IterableOnce` with an effect-producing function.
      *
      * @param source
      *   The input `IterableOnce`
      * @param acc
      *   The initial accumulator value
      * @param f
      *   The effect-producing folding function
      * @return
      *   A new effect that produces the final accumulated value
      */
    def foldLeft[A, B, S](source: IterableOnce[A])(acc: B)(f: Safepoint ?=> (B, A) => B < S)(using Frame, Safepoint): B < S =
        source.knownSize match
            case 0 => acc
            case _ =>
                source match
                    case linearSeq: LinearSeq[A] =>
                        Loop(linearSeq, acc): (seq, acc) =>
                            if seq.isEmpty then Loop.done(acc)
                            else f(acc, seq.head).map(Loop.continue(seq.tail, _))
                    case other =>
                        val indexedSeq = toIndexed(other)
                        val size       = indexedSeq.length
                        Loop.indexed(acc): (idx, acc) =>
                            if idx == size then Loop.done(acc)
                            else f(acc, indexedSeq(idx)).map(Loop.continue(_))
        end match
    end foldLeft

    /** Collects and transforms elements from an `IterableOnce` using an effect-producing function that returns Maybe values.
      *
      * This method applies the given function to each element in the `IterableOnce` and collects only the Present values into a Chunk. It's
      * similar to a combination of flatMap and filter, where elements are both transformed and filtered in a single pass.
      *
      * @param source
      *   The input `IterableOnce`
      * @param f
      *   The effect-producing function that returns Maybe values
      * @return
      *   A new effect that produces a Chunk containing only the Present values after transformation
      */
    def collect[A, B, S](source: IterableOnce[A])(f: Safepoint ?=> A => Maybe[B] < S)(using Frame, Safepoint): Chunk[B] < S =
        source.knownSize match
            case 0 => Chunk.empty
            case _ =>
                source match
                    case linearSeq: LinearSeq[A] =>
                        Loop(linearSeq, Chunk.empty[B]): (seq, acc) =>
                            if seq.isEmpty then Loop.done(acc)
                            else
                                f(seq.head).map:
                                    case Absent     => Loop.continue(seq.tail, acc)
                                    case Present(v) => Loop.continue(seq.tail, acc.append(v))
                    case other =>
                        val indexedSeq = toIndexed(other)
                        val size       = indexedSeq.length
                        Loop.indexed(Chunk.empty[B]): (idx, acc) =>
                            if idx == size then Loop.done(acc)
                            else
                                f(indexedSeq(idx)).map:
                                    case Absent     => Loop.continue(acc)
                                    case Present(v) => Loop.continue(acc.append(v))
    end collect

    /** Collects the results of an `IterableOnce` of effects into a single effect.
      *
      * @param source
      *   The `IterableOnce` of effects
      * @return
      *   A new effect that produces a Chunk of results
      */
    def collectAll[A, S](source: IterableOnce[A < S])(using Frame, Safepoint): Chunk[A] < S =
        source.knownSize match
            case 0 => Chunk.empty
            case 1 => source.iterator.next().map(Chunk.single(_))
            case _ =>
                source match
                    case linearSeq: LinearSeq[A < S] =>
                        Loop(linearSeq, Chunk.empty[A]): (seq, acc) =>
                            if seq.isEmpty then Loop.done(acc)
                            else
                                seq.head.map(u => Loop.continue(seq.tail, acc.append(u)))
                    case other =>
                        val indexedSeq = toIndexed(other)
                        val size       = indexedSeq.length
                        Loop.indexed(Chunk.empty[A]): (idx, acc) =>
                            if idx == size then Loop.done(acc)
                            else
                                indexedSeq(idx).map(u => Loop.continue(acc.append(u)))
    end collectAll

    /** Collects the results of an `IterableOnce` of effects, discarding the results.
      *
      * @param source
      *   The `IterableOnce` of effects
      * @return
      *   A new effect that produces Unit
      */
    def collectAllDiscard[A, S](source: IterableOnce[A < S])(using Frame, Safepoint): Unit < S =
        source.knownSize match
            case 0 =>
            case 1 => source.iterator.next().unit
            case _ =>
                source match
                    case linearSeq: LinearSeq[A < S] =>
                        Loop(linearSeq): seq =>
                            if seq.isEmpty then Loop.done
                            else
                                seq.head.andThen(Loop.continue(seq.tail))
                    case other =>
                        val indexedSeq = toIndexed(other)
                        val size       = indexedSeq.length
                        Loop.indexed: idx =>
                            if idx == size then Loop.done
                            else
                                indexedSeq(idx).andThen(Loop.continue)
    end collectAllDiscard

    /** Finds the first element in an `IterableOnce` that satisfies a predicate.
      *
      * @param source
      *   The input `IterableOnce`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces Maybe of the first matching element
      */
    def findFirst[A, B, S](source: IterableOnce[A])(f: Safepoint ?=> A => Maybe[B] < S)(using Frame, Safepoint): Maybe[B] < S =
        source.knownSize match
            case 0 => Maybe.empty
            case 1 =>
                val head = source.iterator.next()
                f(head)
            case _ =>
                source match
                    case linearSeq: LinearSeq[A] =>
                        Loop(linearSeq): seq =>
                            if seq.isEmpty then Loop.done(Maybe.empty)
                            else
                                f(seq.head).map:
                                    case Absent     => Loop.continue(seq.tail)
                                    case Present(v) => Loop.done(Maybe(v))
                    case other =>
                        val indexedSeq = toIndexed(other)
                        val size       = indexedSeq.length
                        Loop.indexed: idx =>
                            if idx == size then Loop.done(Maybe.empty)
                            else
                                f(indexedSeq(idx)).map:
                                    case Absent     => Loop.continue
                                    case Present(v) => Loop.done(Maybe(v))
    end findFirst

    /** Takes elements from an `IterableOnce` while a predicate holds true.
      *
      * @param source
      *   The input `IterableOnce`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Chunk of taken elements
      */
    def takeWhile[A, S](source: IterableOnce[A])(f: Safepoint ?=> A => Boolean < S)(using Frame, Safepoint): Chunk[A] < S =
        source.knownSize match
            case 0 => Chunk.empty
            case _ =>
                source match
                    case linearSeq: LinearSeq[A] =>
                        Loop(linearSeq, Chunk.empty[A]): (seq, acc) =>
                            if seq.isEmpty then Loop.done(acc)
                            else
                                val curr = seq.head
                                f(curr).map:
                                    case true  => Loop.continue(seq.tail, acc.append(curr))
                                    case false => Loop.done(acc)
                    case other =>
                        val indexedSeq = toIndexed(other)
                        val size       = indexedSeq.length
                        Loop.indexed(Chunk.empty[A]): (idx, acc) =>
                            if idx == size then Loop.done(acc)
                            else
                                val curr = indexedSeq(idx)
                                f(curr).map:
                                    case true  => Loop.continue(acc.append(curr))
                                    case false => Loop.done(acc)
    end takeWhile

    /** Drops elements from an `IterableOnce` while a predicate holds true.
      *
      * @param source
      *   The input `IterableOnce`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Chunk of remaining elements
      */
    def dropWhile[A, S](source: IterableOnce[A])(f: Safepoint ?=> A => Boolean < S)(using Frame, Safepoint): Chunk[A] < S =
        source.knownSize match
            case 0 => Chunk.empty
            case _ =>
                source match
                    case linearSeq: LinearSeq[A] =>
                        Loop(linearSeq): seq =>
                            if seq.isEmpty then Loop.done(Chunk.empty)
                            else
                                val curr = seq.head
                                f(curr).map:
                                    case true  => Loop.continue(seq.tail)
                                    case false => Loop.done(Chunk.from(seq.tail))
                    case other =>
                        val indexedSeq = toIndexed(other)
                        val size       = indexedSeq.length
                        Loop.indexed: idx =>
                            if idx == size then Loop.done(Chunk.empty)
                            else
                                val curr = indexedSeq(idx)
                                f(curr).map:
                                    case true  => Loop.continue
                                    case false => Loop.done(Chunk.from(indexedSeq.drop(idx)))
                end match
    end dropWhile

    /** Creates a Chunk by repeating an effect-producing value.
      *
      * @param n
      *   The number of times to repeat the value
      * @param v
      *   The effect-producing value
      * @return
      *   A new effect that produces a Chunk of repeated values
      */
    def fill[A, S](n: Int)(v: Safepoint ?=> A < S)(using Frame, Safepoint): Chunk[A] < S =
        Loop.indexed(Chunk.empty[A]) { (idx, acc) =>
            if idx == n then Loop.done(acc)
            else v.map(t => Loop.continue(acc.append(t)))
        }

    private def toIndexed[A](source: IterableOnce[A]): Seq[A] =
        source match
            case seq: IndexedSeq[A] => seq
            case other              => Chunk.from(other)
end Kyo
