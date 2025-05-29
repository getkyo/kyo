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
                f(head).map(Chunk(_))
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

    /** Applies an effect-producing function to each element of an `IterableOnce`, and concatenates the resulting collections.
      *
      * @param source
      *   The input `IterableOnce`
      * @param f
      *   The effect-producing function that returns a collection of results per element
      * @return
      *   A new effect that produces a flattened Chunk of all results
      */
    def foreachConcat[A, B, S](source: IterableOnce[A])(f: Safepoint ?=> A => IterableOnce[B] < S)(using Frame, Safepoint): Chunk[B] < S =
        source.knownSize match
            case 0 => Chunk.empty
            case 1 =>
                val head = source.iterator.next()
                f(head).map(bs => Chunk.from(bs))
            case _ =>
                source match
                    case linearSeq: LinearSeq[A] =>
                        Loop(linearSeq, Chunk.empty[B]): (seq, acc) =>
                            if seq.isEmpty then Loop.done(acc)
                            else
                                f(seq.head).map(bs =>
                                    Loop.continue(seq.tail, acc ++ bs)
                                )
                    case other =>
                        val indexedSeq = toIndexed(other)
                        val size       = indexedSeq.length
                        Loop.indexed(Chunk.empty[B]): (idx, acc) =>
                            if idx == size then Loop.done(acc)
                            else
                                f(indexedSeq(idx)).map(bs =>
                                    Loop.continue(acc ++ bs)
                                )
    end foreachConcat

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
                f(0, head).map(Chunk(_))
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
            case 1 => source.iterator.next().map(Chunk(_))
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

    /** Splits the collection into prefix/suffix pair where all elements in the prefix satisfy `f`.
      *
      * @param f
      *   A predicate that returns `true` while elements should be included in the prefix
      * @return
      *   A tuple `(prefix, suffix)` where:
      *   - `prefix`: All elements before first failure of `f`
      *   - `suffix`: First failing element and all remaining elements
      * @note
      *   Optimized for both linear and indexed sequences
      */
    def span[A, S](source: IterableOnce[A])(f: Safepoint ?=> A => Boolean < S)(using Frame, Safepoint): (Chunk[A], Chunk[A]) < S =
        source.knownSize match
            case 0 => (Chunk.empty, Chunk.empty)
            case _ =>
                source match
                    case linearSeq: LinearSeq[A] =>
                        Loop(Chunk.empty[A], linearSeq): (acc, seq) =>
                            if seq.isEmpty then
                                Loop.done((acc, Chunk.empty))
                            else
                                val curr = seq.head
                                f(curr).map:
                                    case true  => Loop.continue(acc.append(curr), seq.tail)
                                    case false => Loop.done((acc, Chunk.from(seq)))

                    case other =>
                        val indexedSeq = toIndexed(other)
                        val size       = indexedSeq.length
                        Loop.indexed: idx =>
                            if idx == size then
                                Loop.done((Chunk.from(indexedSeq), Chunk.empty))
                            else
                                val curr = indexedSeq(idx)
                                f(curr).map({
                                    case true => Loop.continue
                                    case false =>
                                        Loop.done(Chunk.from(indexedSeq).splitAt(idx))
                                })
    end span

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
                                    case false => Loop.done(Chunk.from(seq))
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

    def partition[S, A](source: IterableOnce[A])(f: Safepoint ?=> A => Boolean < S)(using
        Frame,
        Safepoint
    ): (Chunk[A], Chunk[A]) < S =
        source.knownSize match
            case 0 => (Chunk.empty, Chunk.empty)
            case _ =>
                source match
                    case linearSeq: LinearSeq[A] =>
                        Loop(linearSeq, Chunk.empty[A], Chunk.empty[A]) { (seq, trues, falses) =>
                            if seq.isEmpty then Loop.done((trues, falses))
                            else
                                f(seq.head).map { matches =>
                                    if matches then
                                        Loop.continue(seq.tail, trues.append(seq.head), falses)
                                    else
                                        Loop.continue(seq.tail, trues, falses.append(seq.head))
                                }
                        }

                    case other =>
                        val arr = toIndexed(other)
                        Loop.indexed(Chunk.empty[A], Chunk.empty[A]) { (idx, trues, falses) =>
                            if idx == arr.length then
                                Loop.done((trues, falses))
                            else
                                f(arr(idx)).map { matches =>
                                    if matches then
                                        Loop.continue(trues.append(arr(idx)), falses)
                                    else
                                        Loop.continue(trues, falses.append(arr(idx)))
                                }
                        }

    def partitionMap[S, A, A1, A2](source: IterableOnce[A])(f: Safepoint ?=> A => Either[A1, A2] < S)(using
        Frame,
        Safepoint
    ): (Chunk[A1], Chunk[A2]) < S =
        source.knownSize match
            case 0 => (Chunk.empty, Chunk.empty)
            case _ =>
                source match
                    case linearSeq: LinearSeq[A] =>
                        Loop(linearSeq, Chunk.empty[A1], Chunk.empty[A2]) { (seq, lefts, rights) =>
                            if seq.isEmpty then Loop.done((lefts, rights))
                            else
                                f(seq.head).map {
                                    case Left(a1)  => Loop.continue(seq.tail, lefts.append(a1), rights)
                                    case Right(a2) => Loop.continue(seq.tail, lefts, rights.append(a2))
                                }
                        }

                    case other =>
                        val arr = toIndexed(other)
                        Loop.indexed(Chunk.empty[A1], Chunk.empty[A2]) { (idx, lefts, rights) =>
                            if idx >= arr.length then
                                Loop.done((lefts, rights))
                            else
                                f(arr(idx)).map {
                                    case Left(a1)  => Loop.continue(lefts.append(a1), rights)
                                    case Right(a2) => Loop.continue(lefts, rights.append(a2))
                                }
                        }

    /** Computes a prefix scan of the collection.
      *
      * @param z
      *   Initial accumulator value
      * @param op
      *   Effectful operation that combines accumulator with each element
      * @return
      *   Chunk containing all intermediate accumulator states
      */
    def scanLeft[S, A, B](source: IterableOnce[A])(z: B)(op: Safepoint ?=> (B, A) => B < S)(using
        Frame,
        Safepoint
    ): Chunk[B] < S =
        source.knownSize match
            case 0 => Chunk(z)
            case _ =>
                source match
                    case linearSeq: LinearSeq[A] =>
                        Loop(linearSeq, Chunk(z), z) { (seq, acc, current) =>
                            if seq.isEmpty then Loop.done(acc)
                            else
                                op(current, seq.head).map { next =>
                                    Loop.continue(seq.tail, acc.append(next), next)
                                }
                        }

                    case other =>
                        val arr = toIndexed(other)
                        Loop.indexed(Chunk(z), z) { (idx, acc, current) =>
                            if idx == arr.length then Loop.done(acc)
                            else
                                op(current, arr(idx)).map { next =>
                                    Loop.continue(acc.append(next), next)
                                }
                        }

    def groupBy[S, A, K](source: IterableOnce[A])(f: Safepoint ?=> A => K < S)(using
        Frame,
        Safepoint
    ): Map[K, Chunk[A]] < S =
        source.knownSize match
            case 0 => Map.empty[K, Chunk[A]]
            case _ =>
                source match
                    case linearSeq: LinearSeq[A] =>
                        Loop(linearSeq, Map.empty[K, Chunk[A]]) { (seq, acc) =>
                            if seq.isEmpty then
                                Loop.done(acc)
                            else
                                val a = seq.head
                                f(a).map { k =>
                                    val current = acc.getOrElse(k, Chunk.empty)
                                    Loop.continue(seq.tail, acc.updated(k, current :+ a))
                                }
                        }

                    case other =>
                        val arr = toIndexed(other)
                        Loop.indexed(Map.empty[K, Chunk[A]]) { (idx, acc) =>
                            if idx == arr.length then
                                Loop.done(acc)
                            else
                                val a = arr(idx)
                                f(a).map { k =>
                                    val current = acc.getOrElse(k, Chunk.empty)
                                    Loop.continue(acc.updated(k, current :+ a))
                                }
                        }

    def groupMap[S, A, K, B](source: IterableOnce[A])(key: Safepoint ?=> A => K < S)(f: Safepoint ?=> A => B < S)(using
        Frame,
        Safepoint
    ): Map[K, Chunk[B]] < S =
        source.knownSize match
            case 0 => Map.empty[K, Chunk[B]]
            case _ =>
                source match
                    case linearSeq: LinearSeq[A] =>
                        Loop(linearSeq, Map.empty[K, Chunk[B]]) { (seq, acc) =>
                            if seq.isEmpty then
                                Loop.done(acc)
                            else
                                val a = seq.head
                                for
                                    k <- key(a)
                                    b <- f(a)
                                    current = acc.getOrElse(k, Chunk.empty)
                                    updated = acc.updated(k, current :+ b)
                                yield Loop.continue(seq.tail, updated)
                                end for
                        }

                    case other =>
                        val arr = toIndexed(other)
                        Loop.indexed(Map.empty[K, Chunk[B]]) { (idx, acc) =>
                            if idx >= arr.length then
                                Loop.done(acc)
                            else
                                val a = arr(idx)
                                for
                                    k <- key(a)
                                    b <- f(a)
                                    current = acc.getOrElse(k, Chunk.empty)
                                    updated = acc.updated(k, current :+ b)
                                yield Loop.continue(updated)
                                end for
                        }

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

    // for kyo-direct
    private[kyo] def shiftedWhile[A, S, B, C](source: IterableOnce[A])(
        prolog: B,
        f: Safepoint ?=> A => Boolean < S,
        acc: (B, Boolean, A) => B,
        epilog: B => C
    )(using Frame, Safepoint): C < S =
        source.knownSize match
            case 0 => epilog(prolog)
            case _ =>
                source match
                    case linearSeq: LinearSeq[A] => Loop(linearSeq, prolog): (seq, b) =>
                            if seq.isEmpty then Loop.done(epilog(b))
                            else
                                val curr = seq.head
                                f(curr).map:
                                    case true  => Loop.continue(seq.tail, acc(b, true, curr))
                                    case false => Loop.done(epilog(acc(b, false, curr)))

                    case other =>
                        val indexedSeq = toIndexed(other)
                        val size       = indexedSeq.length
                        Loop.indexed(prolog): (idx, b) =>
                            if idx == size then Loop.done(epilog(b))
                            else
                                val curr = indexedSeq(idx)
                                f(curr).map:
                                    case true  => Loop.continue(acc(b, true, curr))
                                    case false => Loop.done(epilog(acc(b, false, curr)))

        end match
    end shiftedWhile
end Kyo
