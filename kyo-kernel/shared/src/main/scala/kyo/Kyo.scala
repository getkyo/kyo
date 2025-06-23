package kyo

import kernel.Loop
import kyo.kernel.internal.Safepoint
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.collection.IterableOps

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

    // -----------------------------------------------------------------------------------------------------------------
    // Generic
    // -----------------------------------------------------------------------------------------------------------------

    /** Applies an effect-producing function to each element of a collection.
      *
      * @param source
      *   The input collection
      * @param f
      *   The effect-producing function to apply to each element
      * @return
      *   A new effect that produces a collection of results
      */
    def foreach[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: Safepoint ?=> A => B < S)(using
        Frame,
        Safepoint
    ): CC[B] < S =
        foreach(Chunk.from(source))(f).map: resultChunk =>
            source.iterableFactory.from(resultChunk)
    end foreach

    /** Applies an effect-producing function to each element of a collection, and concatenates the resulting collections.
      *
      * @param source
      *   The input collection
      * @param f
      *   The effect-producing function that returns a collection of results per element
      * @return
      *   A new effect that produces a flattened Chunk of all results
      */
    def foreachConcat[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: Safepoint ?=> A => IterableOnce[B] < S)(
        using
        Frame,
        Safepoint
    ): CC[B] < S =
        Kyo.foreachConcat(Chunk.from(source))(f).map: resultChunk =>
            source.iterableFactory.from(resultChunk)
    end foreachConcat

    /** Applies an effect-producing function to each element of a sequence along with its index.
      *
      * @param source
      *   The input collection
      * @param f
      *   The effect-producing function to apply to each element and its index
      * @return
      *   A new effect that produces a Chunk of results
      */
    def foreachIndexed[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: Safepoint ?=> (Int, A) => B < S)(using
        Frame,
        Safepoint
    ): CC[B] < S =
        Kyo.foreachIndexed(Chunk.from(source))(f).map: resultChunk =>
            source.iterableFactory.from(resultChunk)
    end foreachIndexed

    /** Applies an effect-producing function to each element of a collection, discarding the results.
      *
      * @param source
      *   The input collection
      * @param f
      *   The effect-producing function to apply to each element
      * @return
      *   A new effect that produces Unit
      */
    def foreachDiscard[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: Safepoint ?=> A => Any < S)(using
        Frame,
        Safepoint
    ): Unit < S =
        Kyo.foreachDiscard(Chunk.from(source))(f)
    end foreachDiscard

    /** Filters elements of a collection based on an effect-producing predicate.
      *
      * @param source
      *   The input collection
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Chunk of filtered elements
      */
    def filter[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: Safepoint ?=> A => Boolean < S)(using
        Frame,
        Safepoint
    ): CC[A] < S =
        Kyo.filter(Chunk.from(source))(f).map: resultChunk =>
            source.iterableFactory.from(resultChunk)
    end filter

    /** Folds over a collection with an effect-producing function.
      *
      * @param source
      *   The input collection
      * @param acc
      *   The initial accumulator value
      * @param f
      *   The effect-producing folding function
      * @return
      *   A new effect that produces the final accumulated value
      */
    def foldLeft[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(acc: B)(f: Safepoint ?=> (B, A) => B < S)(using
        Frame,
        Safepoint
    ): B < S =
        Kyo.foldLeft(Chunk.from(source))(acc)(f)
    end foldLeft

    /** Collects and transforms elements from a collection using an effect-producing function that returns Maybe values.
      *
      * This method applies the given function to each element in the collection and collects only the Present values into a Chunk. It's
      * similar to a combination of flatMap and filter, where elements are both transformed and filtered in a single pass.
      *
      * @param source
      *   The input collection
      * @param f
      *   The effect-producing function that returns Maybe values
      * @return
      *   A new effect that produces a Chunk containing only the Present values after transformation
      */
    def collect[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: Safepoint ?=> A => Maybe[B] < S)(using
        Frame,
        Safepoint
    ): CC[B] < S =
        val chunk = Chunk.from(source)
        collect[A, B, S](chunk)(f).map: resultChunk =>
            source.iterableFactory.from(resultChunk)
    end collect

    /** Collects the results of a collection of effects into a single effect.
      *
      * @param source
      *   The collection of effects
      * @return
      *   A new effect that produces a Chunk of results
      */
    def collectAll[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A < S])(using Frame, Safepoint): CC[A] < S =
        Kyo.collectAll(Chunk.from(source)).map: resultChunk =>
            source.iterableFactory.from(resultChunk)
    end collectAll

    /** Collects the results of a collection of effects, discarding the results.
      *
      * @param source
      *   The collection of effects
      * @return
      *   A new effect that produces Unit
      */
    def collectAllDiscard[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A < S])(using Frame, Safepoint): Unit < S =
        Kyo.collectAllDiscard(Chunk.from(source))
    end collectAllDiscard

    /** Finds the first element in a collection that satisfies a predicate.
      *
      * @param source
      *   The input collection
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces Maybe of the first matching element
      */
    def findFirst[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: Safepoint ?=> A => Maybe[B] < S)(using
        Frame,
        Safepoint
    ): Maybe[B] < S =
        Kyo.findFirst(Chunk.from(source))(f)
    end findFirst

    /** Takes elements from a collection while a predicate holds true.
      *
      * @param source
      *   The input collection
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Chunk of taken elements
      */
    def takeWhile[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: Safepoint ?=> A => Boolean < S)(using
        Frame,
        Safepoint
    ): CC[A] < S =
        Kyo.takeWhile(Chunk.from(source))(f).map: resultChunk =>
            source.iterableFactory.from(resultChunk)
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
    def span[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: Safepoint ?=> A => Boolean < S)(using
        Frame,
        Safepoint
    ): (CC[A], CC[A]) < S =
        Kyo.span(Chunk.from(source))(f).map: (leftChunk, rightChunk) =>
            (source.iterableFactory.from(leftChunk), source.iterableFactory.from(rightChunk))
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
    def dropWhile[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: Safepoint ?=> A => Boolean < S)(using
        Frame,
        Safepoint
    ): CC[A] < S =
        Kyo.dropWhile(Chunk.from(source))(f).map: resultChunk =>
            source.iterableFactory.from(resultChunk)
    end dropWhile

    def partition[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: Safepoint ?=> A => Boolean < S)(using
        Frame,
        Safepoint
    ): (CC[A], CC[A]) < S =
        Kyo.partition(Chunk.from(source))(f).map: (leftChunk, rightChunk) =>
            (source.iterableFactory.from(leftChunk), source.iterableFactory.from(rightChunk))
    end partition

    def partitionMap[CC[+X] <: Iterable[X] & IterableOps[
        X,
        CC,
        CC[X]
    ], A, A1, A2, S](source: CC[A])(f: Safepoint ?=> A => Either[A1, A2] < S)(using Frame, Safepoint): (CC[A1], CC[A2]) < S =
        Kyo.partitionMap(Chunk.from(source))(f).map: (leftChunk, rightChunk) =>
            (source.iterableFactory.from(leftChunk), source.iterableFactory.from(rightChunk))
    end partitionMap

    /** Computes a prefix scan of the collection.
      *
      * @param z
      *   Initial accumulator value
      * @param op
      *   Effectful operation that combines accumulator with each element
      * @return
      *   Chunk containing all intermediate accumulator states
      */
    def scanLeft[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(z: B)(op: Safepoint ?=> (B, A) => B < S)(using
        Frame,
        Safepoint
    ): CC[B] < S =
        Kyo.scanLeft(Chunk.from(source))(z)(op).map: resultChunk =>
            source.iterableFactory.from(resultChunk)
    end scanLeft

    def groupBy[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, K, S](source: CC[A])(f: Safepoint ?=> A => K < S)(using
        Frame,
        Safepoint
    ): Map[K, CC[A]] < S =
        Kyo.groupBy(Chunk.from(source))(f).map: resultChunk =>
            Map.from(resultChunk.view.mapValues(source.iterableFactory.from(_)))
    end groupBy

    def groupMap[CC[+X] <: Iterable[X] & IterableOps[
        X,
        CC,
        CC[X]
    ], A, K, B, S](source: CC[A])(key: Safepoint ?=> A => K < S)(f: Safepoint ?=> A => B < S)(using Frame, Safepoint): Map[K, CC[B]] < S =
        Kyo.groupMap(Chunk.from(source))(key)(f).map: resultChunk =>
            Map.from(resultChunk.view.mapValues(source.iterableFactory.from(_)))
    end groupMap

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

    // for kyo-direct
    private[kyo] def shiftedWhile[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S, B, C](source: CC[A])(
        prolog: B,
        f: Safepoint ?=> A => Boolean < S,
        acc: (B, Boolean, A) => B,
        epilog: B => C
    )(using Frame, Safepoint): C < S =
        Kyo.shiftedWhile(Chunk.from(source))(prolog, f, acc, epilog)
    end shiftedWhile

    // -----------------------------------------------------------------------------------------------------------------
    // List
    // -----------------------------------------------------------------------------------------------------------------

    /** Applies an effect-producing function to each element of an `List`.
      *
      * @param source
      *   The input `List`
      * @param f
      *   The effect-producing function to apply to each element
      * @return
      *   A new effect that produces a List of results
      */
    def foreach[A, B, S](source: List[A])(f: Safepoint ?=> A => B < S)(using Frame, Safepoint): List[B] < S =
        source match
            case Nil         => Nil
            case head :: Nil => f(head).map(_ :: Nil)
            case list =>
                Loop(list, Nil) { (curList, accList) =>
                    curList match
                        case head :: tail => f(head).map { u => Loop.continue(tail, u :: accList) }
                        case Nil          => Loop.done(accList.reverse)
                }
        end match

    end foreach

    /** Applies an effect-producing function to each element of an `List`, and concatenates the resulting collections.
      *
      * @param source
      *   The input `List`
      * @param f
      *   The effect-producing function that returns a collection of results per element
      * @return
      *   A new effect that produces a flattened List of all results
      */
    def foreachConcat[A, B, S](source: List[A])(f: Safepoint ?=> A => IterableOnce[B] < S)(using
        Frame,
        Safepoint
    ): List[B] < S =
        source match
            case Nil         => Nil
            case head :: Nil => f(head).map(List.from(_))
            case list =>
                Loop(list, Nil) { (curList, accList) =>
                    curList match
                        case head :: tail => f(head).map { u => Loop.continue(tail, u :: accList) }
                        case Nil          => Loop.done(accList.reverse.flatMap(identity))
                }
    end foreachConcat

    /** Applies an effect-producing function to each element of a sequence along with its index.
      *
      * @param source
      *   The input `List`
      * @param f
      *   The effect-producing function to apply to each element and its index
      * @return
      *   A new effect that produces a List of results
      */
    def foreachIndexed[A, B, S](source: List[A])(f: Safepoint ?=> (Int, A) => B < S)(using Frame, Safepoint): List[B] < S =
        source match
            case Nil         => Nil
            case head :: Nil => f(0, head).map(_ :: Nil)
            case list =>
                Loop.indexed(list, Nil): (idx, curList, acc) =>
                    curList match
                        case head :: tail => f(idx, head).map { u => Loop.continue(tail, u :: acc) }
                        case Nil          => Loop.done(acc.reverse)
    end foreachIndexed

    /** Applies an effect-producing function to each element of an `List`, discarding the results.
      *
      * @param source
      *   The input `List`
      * @param f
      *   The effect-producing function to apply to each element
      * @return
      *   A new effect that produces Unit
      */
    def foreachDiscard[A, B, S](source: List[A])(f: Safepoint ?=> A => Any < S)(using Frame, Safepoint): Unit < S =
        source match
            case Nil         => ()
            case head :: Nil => f(head).unit
            case list =>
                Loop(list): curList =>
                    curList match
                        case head :: tail => f(head).andThen(Loop.continue(tail))
                        case Nil          => Loop.done
        end match
    end foreachDiscard

    /** Filters elements of an `List` based on an effect-producing predicate.
      *
      * @param source
      *   The input `List`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a List of filtered elements
      */
    def filter[A, S](source: List[A])(f: Safepoint ?=> A => Boolean < S)(using Frame, Safepoint): List[A] < S =
        source match
            case Nil => Nil
            case head :: Nil =>
                f(head).map:
                    case true  => head :: Nil
                    case false => Nil
            case list =>
                Loop(list, Nil): (curList, accList) =>
                    curList match
                        case head :: tail => f(head).map:
                                case true  => Loop.continue(tail, head :: accList)
                                case false => Loop.continue(tail, accList)
                        case Nil => Loop.done(accList.reverse)
    end filter

    /** Folds over an `List` with an effect-producing function.
      *
      * @param source
      *   The input `List`
      * @param acc
      *   The initial accumulator value
      * @param f
      *   The effect-producing folding function
      * @return
      *   A new effect that produces the final accumulated value
      */
    def foldLeft[A, B, S](source: List[A])(acc: B)(f: Safepoint ?=> (B, A) => B < S)(using Frame, Safepoint): B < S =
        source match
            case Nil         => acc
            case head :: Nil => f(acc, head)
            case list =>
                Loop(list, acc): (curList, acc) =>
                    curList match
                        case head :: tail => f(acc, head).map(Loop.continue(tail, _))
                        case Nil          => Loop.done(acc)
    end foldLeft

    /** Collects and transforms elements from an `List` using an effect-producing function that returns Maybe values.
      *
      * This method applies the given function to each element in the `List` and collects only the Present values into a List. It's similar
      * to a combination of flatMap and filter, where elements are both transformed and filtered in a single pass.
      *
      * @param source
      *   The input `List`
      * @param f
      *   The effect-producing function that returns Maybe values
      * @return
      *   A new effect that produces a List containing only the Present values after transformation
      */
    def collect[A, B, S](source: List[A])(f: Safepoint ?=> A => Maybe[B] < S)(using Frame, Safepoint): List[B] < S =
        source match
            case Nil => Nil
            case head :: Nil =>
                f(head).map:
                    case Absent     => Nil
                    case Present(v) => v :: Nil
            case list =>
                Loop(list, Nil): (curList, accList) =>
                    curList match
                        case head :: tail => f(head).map:
                                case Absent     => Loop.continue(tail, accList)
                                case Present(v) => Loop.continue(tail, v :: accList)
                        case Nil => Loop.done(accList.reverse)
    end collect

    /** Collects the results of an `List` of effects into a single effect.
      *
      * @param source
      *   The `List` of effects
      * @return
      *   A new effect that produces a Chunk of results
      */
    def collectAll[A, S](source: List[A < S])(using Frame, Safepoint): List[A] < S =
        source match
            case Nil         => Nil
            case head :: Nil => head.map(_ :: Nil)
            case list =>
                Loop(list, Nil): (curList, accList) =>
                    curList match
                        case head :: tail => head.map(u => Loop.continue(tail, u :: accList))
                        case Nil          => Loop.done(accList.reverse)
    end collectAll

    /** Collects the results of a `List` of effects, discarding the results.
      *
      * @param source
      *   The `List` of effects
      * @return
      *   A new effect that produces Unit
      */
    def collectAllDiscard[A, S](source: List[A < S])(using Frame, Safepoint): Unit < S =
        source match
            case Nil         => ()
            case head :: Nil => head.unit
            case list =>
                Loop(list): curList =>
                    curList match
                        case head :: tail => head.andThen(Loop.continue(tail))
                        case Nil          => Loop.done
    end collectAllDiscard

    /** Finds the first element in a `List` that satisfies a predicate.
      *
      * @param source
      *   The input `List`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces Maybe of the first matching element
      */
    def findFirst[A, B, S](source: List[A])(f: Safepoint ?=> A => Maybe[B] < S)(using Frame, Safepoint): Maybe[B] < S =
        source match
            case Nil         => Absent
            case head :: Nil => f(head)
            case list =>
                Loop(list): curList =>
                    curList match
                        case head :: tail =>
                            f(head).map:
                                case Absent         => Loop.continue(tail)
                                case p @ Present(_) => Loop.done(p)
                        case Nil => Loop.done(Absent)
    end findFirst

    /** Takes elements from a `List` while a predicate holds true.
      *
      * @param source
      *   The input `List`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a List of taken elements
      */
    def takeWhile[A, S](source: List[A])(f: Safepoint ?=> A => Boolean < S)(using Frame, Safepoint): List[A] < S =
        source match
            case Nil => Nil
            case list =>
                Loop(list, Nil): (curList, acc) =>
                    curList match
                        case head :: tail =>
                            f(head).map:
                                case true  => Loop.continue(tail, head :: acc)
                                case false => Loop.done(acc.reverse)
                        case Nil => Loop.done(acc.reverse)
    end takeWhile

    /** Splits the collection into prefix/suffix pair where all elements in the prefix satisfy `f`.
      *
      * @param f
      *   A predicate that returns `true` while elements should be included in the prefix
      * @return
      *   A tuple `(prefix, suffix)` where:
      *   - `prefix`: All elements before first failure of `f`
      *   - `suffix`: First failing element and all remaining elements
      */
    def span[A, S](source: List[A])(f: Safepoint ?=> A => Boolean < S)(using Frame, Safepoint): (List[A], List[A]) < S =
        source match
            case Nil => (Nil, Nil)
            case head :: Nil =>
                f(head).map:
                    case true  => (head :: Nil, Nil)
                    case false => (Nil, head :: Nil)
            case list =>
                Loop(Nil, list): (acc, curList) =>
                    curList match
                        case head :: tail =>
                            f(head).map:
                                case true  => Loop.continue(head :: acc, tail)
                                case false => Loop.done((acc.reverse, curList))
                        case Nil => Loop.done((acc.reverse, Nil))
    end span

    /** Drops elements from a `List` while a predicate holds true.
      *
      * @param source
      *   The input `List`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a List of remaining elements
      */
    def dropWhile[A, S](source: List[A])(f: Safepoint ?=> A => Boolean < S)(using Frame, Safepoint): List[A] < S =
        source match
            case Nil => Nil
            case list =>
                Loop(list): curList =>
                    curList match
                        case head :: tail =>
                            f(head).map:
                                case true  => Loop.continue(tail)
                                case false => Loop.done(curList)
                        case Nil => Loop.done(Nil)
    end dropWhile

    /** Splits the collection into two lists, depending on the result of the predicate.
      *
      * @param source
      *   The input `List`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A tuple `(lefts, rights)` where:
      *   - `lefts`: All elements that satisfy the predicate
      *   - `rights`: All elements that do not satisfy the predicate
      */
    def partition[S, A](source: List[A])(f: Safepoint ?=> A => Boolean < S)(using
        Frame,
        Safepoint
    ): (List[A], List[A]) < S =
        source match
            case Nil => (Nil, Nil)
            case head :: Nil =>
                f(head).map:
                    case true  => (head :: Nil, Nil)
                    case false => (Nil, head :: Nil)
            case list =>
                Loop(list, Nil, Nil): (curList, trues, falses) =>
                    curList match
                        case head :: tail =>
                            f(head).map:
                                case true  => Loop.continue(tail, head :: trues, falses)
                                case false => Loop.continue(tail, trues, head :: falses)
                        case Nil => Loop.done((trues.reverse, falses.reverse))
    end partition

    /** Splits the collection into two lists, depending on the result of the effect-producing function.
      *
      * @param source
      *   The input `List`
      * @param f
      *   The effect-producing function that returns an Either
      * @return
      *   A tuple `(lefts, rights)` where:
      *   - `lefts`: All elements that are Left
      *   - `rights`: All elements that are Right
      */
    def partitionMap[S, A, A1, A2](source: List[A])(f: Safepoint ?=> A => Either[A1, A2] < S)(using
        Frame,
        Safepoint
    ): (List[A1], List[A2]) < S =
        source match
            case Nil => (Nil, Nil)
            case head :: Nil =>
                f(head).map:
                    case Left(a1)  => (a1 :: Nil, Nil)
                    case Right(a2) => (Nil, a2 :: Nil)
            case list =>
                Loop(list, Nil, Nil): (curList, lefts, rights) =>
                    curList match
                        case head :: tail =>
                            f(head).map:
                                case Left(a1)  => Loop.continue(tail, a1 :: lefts, rights)
                                case Right(a2) => Loop.continue(tail, lefts, a2 :: rights)
                        case Nil => Loop.done((lefts.reverse, rights.reverse))
    end partitionMap

    /** Computes a prefix scan of the collection.
      *
      * @param z
      *   Initial accumulator value
      * @param op
      *   Effectful operation that combines accumulator with each element
      * @return
      *   List containing all intermediate accumulator states
      */
    def scanLeft[S, A, B](source: List[A])(z: B)(op: Safepoint ?=> (B, A) => B < S)(using
        Frame,
        Safepoint
    ): List[B] < S =
        source match
            case Nil => z :: Nil
            case head :: Nil =>
                op(z, head).map(z :: _ :: Nil)
            case list =>
                Loop(list, z :: Nil, z): (curList, acc, current) =>
                    curList match
                        case head :: tail =>
                            op(current, head).map: next =>
                                Loop.continue(tail, next :: acc, next)
                        case Nil => Loop.done(acc.reverse)
    end scanLeft

    /** Groups elements of the collection by the result of the function.
      *
      * @param source
      *   The input `List`
      * @param f
      *   The effect-producing function that returns the key for each element
      * @return
      *   A Map where keys are the results of the function and values are lists of elements
      */
    def groupBy[S, A, K](source: List[A])(f: Safepoint ?=> A => K < S)(using
        Frame,
        Safepoint
    ): Map[K, List[A]] < S =
        source match
            case Nil         => Map.empty[K, List[A]]
            case head :: Nil => f(head).map(k => Map(k -> (head :: Nil)))
            case list =>
                Loop(list, Map.empty[K, List[A]]): (curList, acc) =>
                    curList match
                        case head :: tail =>
                            f(head).map: k =>
                                Loop.continue(
                                    tail,
                                    acc.updatedWith(k) {
                                        case Some(current) => Some(head :: current)
                                        case None          => Some(head :: Nil)
                                    }
                                )
                        case Nil => Loop.done(acc.view.mapValues(_.reverse).toMap)
    end groupBy

    /** Groups elements of the collection by the result of the function and applies a transformation to each element.
      *
      * @param source
      *   The input `List`
      * @param key
      *   The effect-producing function that returns the key for each element
      * @param f
      *   The effect-producing function that returns the value for each element
      * @return
      *   A Map where keys are the results of the function and values are lists of transformed elements
      */
    def groupMap[S, A, K, B](source: List[A])(key: Safepoint ?=> A => K < S)(f: Safepoint ?=> A => B < S)(using
        Frame,
        Safepoint
    ): Map[K, List[B]] < S =
        source match
            case Nil => Map.empty[K, List[B]]
            case head :: Nil =>
                for
                    k <- key(head)
                    b <- f(head)
                yield Map(k -> (b :: Nil))
            case list =>
                Loop(list, Map.empty[K, List[B]]): (curList, acc) =>
                    curList match
                        case head :: tail =>
                            for
                                k <- key(head)
                                b <- f(head)
                            yield Loop.continue(
                                tail,
                                acc.updatedWith(k) {
                                    case Some(current) => Some(b :: current)
                                    case None          => Some(b :: Nil)
                                }
                            )
                            end for
                        case Nil => Loop.done(acc.view.mapValues(_.reverse).toMap)
    end groupMap

    /** Processes elements of a `List` while a predicate holds true, maintaining an accumulator state. This function implements a stateful
      * iteration pattern where:
      *   1. The predicate function `f` determines whether to continue processing
      *   2. The accumulator function `acc` updates state based on the predicate result
      *   3. The epilog function transforms the final accumulator state into the result
      *
      * @param source
      *   The input `List` to process
      * @param prolog
      *   Initial state value for the accumulator
      * @param f
      *   Effect-producing predicate that determines whether to continue processing
      * @param acc
      *   Function that updates the accumulator state based on predicate result and current element
      * @param epilog
      *   Function that transforms the final accumulator state into the result
      * @return
      *   A new effect that produces the final transformed result
      */
    private[kyo] def shiftedWhile[A, S, B, C](source: List[A])(
        prolog: B,
        f: Safepoint ?=> A => Boolean < S,
        acc: (B, Boolean, A) => B,
        epilog: B => C
    )(using Frame, Safepoint): C < S =
        source match
            case Nil => epilog(prolog)
            case head :: Nil => f(head).map: b =>
                    epilog(acc(prolog, b, head))
            case list =>
                Loop(list, prolog): (curList, b) =>
                    curList match
                        case head :: tail =>
                            f(head).map:
                                case true  => Loop.continue(tail, acc(b, true, head))
                                case false => Loop.done(epilog(acc(b, false, head)))
                        case Nil => Loop.done(epilog(b))
    end shiftedWhile

    // -----------------------------------------------------------------------------------------------------------------
    // Vector
    // -----------------------------------------------------------------------------------------------------------------

    /** Applies an effect-producing function to each element of a `Vector`.
      *
      * @param source
      *   The input `Vector`
      * @param f
      *   The effect-producing function to apply to each element
      * @return
      *   A new effect that produces a Vector of results
      */
    def foreach[A, B, S](source: Vector[A])(f: Safepoint ?=> A => B < S)(using Frame, Safepoint): Vector[B] < S =
        if source.isEmpty then
            Vector.empty
        else
            Loop(source, Vector.empty[B]): (curVec, accVec) =>
                if curVec.isEmpty then
                    Loop.done(accVec)
                else
                    f(curVec.head).map: b =>
                        Loop.continue(curVec.tail, accVec.appended(b))
        end if
    end foreach

    /** Applies an effect-producing function to each element of a `Vector`, and concatenates the resulting collections.
      *
      * @param source
      *   The input `Vector`
      * @param f
      *   The effect-producing function that returns a collection of results per element
      * @return
      *   A new effect that produces a flattened Vector of all results
      */
    def foreachConcat[A, B, S](source: Vector[A])(f: Safepoint ?=> A => IterableOnce[B] < S)(using
        Frame,
        Safepoint
    ): Vector[B] < S =
        if source.isEmpty then
            Vector.empty
        else
            Loop(source, Vector.empty[Vector[B]]): (curVec, accVec) =>
                if curVec.isEmpty then
                    Loop.done(accVec.flatten)
                else
                    f(curVec.head).map: iterOnce =>
                        Loop.continue(curVec.tail, accVec.appended(Vector.from(iterOnce)))
    end foreachConcat

    /** Applies an effect-producing function to each element of a sequence along with its index.
      *
      * @param source
      *   The input `Vector`
      * @param f
      *   The effect-producing function to apply to each element and its index
      * @return
      *   A new effect that produces a Vector of results
      */
    def foreachIndexed[A, B, S](source: Vector[A])(f: Safepoint ?=> (Int, A) => B < S)(using Frame, Safepoint): Vector[B] < S =
        if source.isEmpty then
            Vector.empty
        else
            Loop.indexed(source, Vector.empty[B]): (index, curVec, accVec) =>
                if curVec.isEmpty then
                    Loop.done(accVec)
                else
                    f(index, curVec.head).map: b =>
                        Loop.continue(curVec.tail, accVec.appended(b))
    end foreachIndexed

    /** Applies an effect-producing function to each element of a `Vector`, discarding the results.
      *
      * @param source
      *   The input `Vector`
      * @param f
      *   The effect-producing function to apply to each element
      * @return
      *   A new effect that produces Unit
      */
    def foreachDiscard[A, B, S](source: Vector[A])(f: Safepoint ?=> A => Any < S)(using Frame, Safepoint): Unit < S =
        if source.isEmpty then
            ()
        else
            Loop(source): curVec =>
                if curVec.isEmpty then
                    Loop.done
                else
                    f(curVec.head).andThen(Loop.continue(curVec.tail))
    end foreachDiscard

    /** Filters elements of a `Vector` based on an effect-producing predicate.
      *
      * @param source
      *   The input `Vector`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Vector of filtered elements
      */
    def filter[A, S](source: Vector[A])(f: Safepoint ?=> A => Boolean < S)(using Frame, Safepoint): Vector[A] < S =
        if source.isEmpty then
            Vector.empty
        else
            Loop(source, Vector.empty[A]): (curVec, accVec) =>
                if curVec.isEmpty then
                    Loop.done(accVec)
                else
                    f(curVec.head).map:
                        case true  => Loop.continue(curVec.tail, accVec.appended(curVec.head))
                        case false => Loop.continue(curVec.tail, accVec)
    end filter

    /** Folds over a `Vector` with an effect-producing function.
      *
      * @param source
      *   The input `Vector`
      * @param acc
      *   The initial accumulator value
      * @param f
      *   The effect-producing folding function
      * @return
      *   A new effect that produces the final accumulated value
      */
    def foldLeft[A, B, S](source: Vector[A])(acc: B)(f: Safepoint ?=> (B, A) => B < S)(using Frame, Safepoint): B < S =
        if source.isEmpty then
            acc
        else
            Loop(source, acc): (curVec, acc) =>
                if curVec.isEmpty then
                    Loop.done(acc)
                else
                    f(acc, curVec.head).map(Loop.continue(curVec.tail, _))
    end foldLeft

    /** Collects and transforms elements from a `Vector` using an effect-producing function that returns Maybe values.
      *
      * This method applies the given function to each element in the `Vector` and collects only the Present values into a Vector. It's
      * similar to a combination of flatMap and filter, where elements are both transformed and filtered in a single pass.
      *
      * @param source
      *   The input `Vector`
      * @param f
      *   The effect-producing function that returns Maybe values
      * @return
      *   A new effect that produces a Vector containing only the Present values after transformation
      */
    def collect[A, B, S](source: Vector[A])(f: Safepoint ?=> A => Maybe[B] < S)(using Frame, Safepoint): Vector[B] < S =
        if source.isEmpty then
            Vector.empty
        else
            Loop(source, Vector.empty[B]): (curVec, accVec) =>
                if curVec.isEmpty then
                    Loop.done(accVec)
                else
                    f(curVec.head).map:
                        case Absent     => Loop.continue(curVec.tail, accVec)
                        case Present(v) => Loop.continue(curVec.tail, accVec.appended(v))
    end collect

    /** Collects the results of a `Vector` of effects into a single effect.
      *
      * @param source
      *   The `Vector` of effects
      * @return
      *   A new effect that produces a Vector of results
      */
    def collectAll[A, S](source: Vector[A < S])(using Frame, Safepoint): Vector[A] < S =
        if source.isEmpty then
            Vector.empty
        else
            Loop(source, Vector.empty[A]): (curVec, accVec) =>
                if curVec.isEmpty then
                    Loop.done(accVec)
                else
                    curVec.head.map(u => Loop.continue(curVec.tail, accVec.appended(u)))
    end collectAll

    /** Collects the results of a `Vector` of effects, discarding the results.
      *
      * @param source
      *   The `Vector` of effects
      * @return
      *   A new effect that produces Unit
      */
    def collectAllDiscard[A, S](source: Vector[A < S])(using Frame, Safepoint): Unit < S =
        if source.isEmpty then
            ()
        else
            Loop(source): curVec =>
                if curVec.isEmpty then
                    Loop.done
                else
                    curVec.head.andThen(Loop.continue(curVec.tail))
    end collectAllDiscard

    /** Finds the first element in a `Vector` that satisfies a predicate.
      *
      * @param source
      *   The input `Vector`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces Maybe of the first matching element
      */
    def findFirst[A, B, S](source: Vector[A])(f: Safepoint ?=> A => Maybe[B] < S)(using Frame, Safepoint): Maybe[B] < S =
        if source.isEmpty then
            Absent
        else
            Loop(source): curVec =>
                if curVec.isEmpty then
                    Loop.done(Absent)
                else
                    f(curVec.head).map:
                        case Absent         => Loop.continue(curVec.tail)
                        case p @ Present(_) => Loop.done(p)
    end findFirst

    /** Takes elements from a `Vector` while a predicate holds true.
      *
      * @param source
      *   The input `Vector`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Vector of taken elements
      */
    def takeWhile[A, S](source: Vector[A])(f: Safepoint ?=> A => Boolean < S)(using Frame, Safepoint): Vector[A] < S =
        if source.isEmpty then
            Vector.empty
        else
            Loop(source, Vector.empty[A]): (curVec, accVec) =>
                if curVec.isEmpty then
                    Loop.done(accVec)
                else
                    f(curVec.head).map:
                        case true  => Loop.continue(curVec.tail, accVec.appended(curVec.head))
                        case false => Loop.done(accVec)
    end takeWhile

    /** Splits the collection into prefix/suffix pair where all elements in the prefix satisfy `f`.
      *
      * @param f
      *   A predicate that returns `true` while elements should be included in the prefix
      * @return
      *   A tuple `(prefix, suffix)` where:
      *   - `prefix`: All elements before first failure of `f`
      *   - `suffix`: First failing element and all remaining elements
      */
    def span[A, S](source: Vector[A])(f: Safepoint ?=> A => Boolean < S)(using Frame, Safepoint): (Vector[A], Vector[A]) < S =
        if source.isEmpty then
            (Vector.empty, Vector.empty)
        else
            Loop(Vector.empty[A], source): (accVec, curVec) =>
                if curVec.isEmpty then
                    Loop.done((accVec, Vector.empty))
                else
                    f(curVec.head).map:
                        case true  => Loop.continue(accVec.appended(curVec.head), curVec.tail)
                        case false => Loop.done((accVec, curVec))
    end span

    /** Drops elements from a `Vector` while a predicate holds true.
      *
      * @param source
      *   The input `Vector`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Vector of remaining elements
      */
    def dropWhile[A, S](source: Vector[A])(f: Safepoint ?=> A => Boolean < S)(using Frame, Safepoint): Vector[A] < S =
        if source.isEmpty then
            Vector.empty
        else
            Loop(source): curVec =>
                if curVec.isEmpty then
                    Loop.done(Vector.empty)
                else
                    f(curVec.head).map:
                        case true  => Loop.continue(curVec.tail)
                        case false => Loop.done(curVec)
    end dropWhile

    /** Splits the collection into two vectors, depending on the result of the predicate.
      *
      * @param source
      *   The input `Vector`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A tuple `(lefts, rights)` where:
      *   - `lefts`: All elements that satisfy the predicate
      *   - `rights`: All elements that do not satisfy the predicate
      */
    def partition[S, A](source: Vector[A])(f: Safepoint ?=> A => Boolean < S)(using
        Frame,
        Safepoint
    ): (Vector[A], Vector[A]) < S =
        if source.isEmpty then
            (Vector.empty, Vector.empty)
        else
            Loop(source, Vector.empty[A], Vector.empty[A]): (curVec, trues, falses) =>
                if curVec.isEmpty then
                    Loop.done((trues, falses))
                else
                    f(curVec.head).map:
                        case true  => Loop.continue(curVec.tail, trues.appended(curVec.head), falses)
                        case false => Loop.continue(curVec.tail, trues, falses.appended(curVec.head))
    end partition

    /** Splits the collection into two vectors, depending on the result of the effect-producing function.
      *
      * @param source
      *   The input `Vector`
      * @param f
      *   The effect-producing function that returns an Either
      * @return
      *   A tuple `(lefts, rights)` where:
      *   - `lefts`: All elements that are Left
      *   - `rights`: All elements that are Right
      */
    def partitionMap[S, A, A1, A2](source: Vector[A])(f: Safepoint ?=> A => Either[A1, A2] < S)(using
        Frame,
        Safepoint
    ): (Vector[A1], Vector[A2]) < S =
        if source.isEmpty then
            (Vector.empty, Vector.empty)
        else
            Loop(source, Vector.empty[A1], Vector.empty[A2]): (curVec, lefts, rights) =>
                if curVec.isEmpty then
                    Loop.done((lefts, rights))
                else
                    f(curVec.head).map:
                        case Left(a1)  => Loop.continue(curVec.tail, lefts.appended(a1), rights)
                        case Right(a2) => Loop.continue(curVec.tail, lefts, rights.appended(a2))
    end partitionMap

    /** Computes a prefix scan of the collection.
      *
      * @param z
      *   Initial accumulator value
      * @param op
      *   Effectful operation that combines accumulator with each element
      * @return
      *   Vector containing all intermediate accumulator states
      */
    def scanLeft[S, A, B](source: Vector[A])(z: B)(op: Safepoint ?=> (B, A) => B < S)(using
        Frame,
        Safepoint
    ): Vector[B] < S =
        if source.isEmpty then
            Vector(z)
        else
            Loop(source, Vector(z), z): (curVec, acc, current) =>
                if curVec.isEmpty then
                    Loop.done(acc)
                else
                    op(current, curVec.head).map: next =>
                        Loop.continue(curVec.tail, acc.appended(next), next)
    end scanLeft

    /** Groups elements of the collection by the result of the function.
      *
      * @param source
      *   The input `Vector`
      * @param f
      *   The effect-producing function that returns the key for each element
      * @return
      *   A Map where keys are the results of the function and values are vectors of elements
      */
    def groupBy[S, A, K](source: Vector[A])(f: Safepoint ?=> A => K < S)(using
        Frame,
        Safepoint
    ): Map[K, Vector[A]] < S =
        if source.isEmpty then
            Map.empty[K, Vector[A]]
        else
            Loop(source, Map.empty[K, Vector[A]]): (curVec, acc) =>
                if curVec.isEmpty then
                    Loop.done(acc)
                else
                    f(curVec.head).map: k =>
                        Loop.continue(
                            curVec.tail,
                            acc.updatedWith(k) {
                                case Some(current) => Some(current.appended(curVec.head))
                                case None          => Some(Vector(curVec.head))
                            }
                        )
    end groupBy

    /** Groups elements of the collection by the result of the function and applies a transformation to each element.
      *
      * @param source
      *   The input `Vector`
      * @param key
      *   The effect-producing function that returns the key for each element
      * @param f
      *   The effect-producing function that returns the value for each element
      * @return
      *   A Map where keys are the results of the function and values are vectors of transformed elements
      */
    def groupMap[S, A, K, B](source: Vector[A])(key: Safepoint ?=> A => K < S)(f: Safepoint ?=> A => B < S)(using
        Frame,
        Safepoint
    ): Map[K, Vector[B]] < S =
        if source.isEmpty then
            Map.empty[K, Vector[B]]
        else
            Loop(source, Map.empty[K, Vector[B]]): (curVec, acc) =>
                if curVec.isEmpty then
                    Loop.done(acc)
                else
                    for
                        k <- key(curVec.head)
                        b <- f(curVec.head)
                    yield Loop.continue(
                        curVec.tail,
                        acc.updatedWith(k) {
                            case Some(current) => Some(current.appended(b))
                            case None          => Some(Vector(b))
                        }
                    )
                    end for
    end groupMap

    /** Processes elements of a `Vector` while a predicate holds true, maintaining an accumulator state. This function implements a stateful
      * iteration pattern where:
      *   1. The predicate function `f` determines whether to continue processing
      *   2. The accumulator function `acc` updates state based on the predicate result
      *   3. The epilog function transforms the final accumulator state into the result
      *
      * @param source
      *   The input `Vector` to process
      * @param prolog
      *   Initial state value for the accumulator
      * @param f
      *   Effect-producing predicate that determines whether to continue processing
      * @param acc
      *   Function that updates the accumulator state based on predicate result and current element
      * @param epilog
      *   Function that transforms the final accumulator state into the result
      * @return
      *   A new effect that produces the final transformed result
      */
    private[kyo] def shiftedWhile[A, S, B, C](source: Vector[A])(
        prolog: B,
        f: Safepoint ?=> A => Boolean < S,
        acc: (B, Boolean, A) => B,
        epilog: B => C
    )(using Frame, Safepoint): C < S =
        if source.isEmpty then
            epilog(prolog)
        else
            Loop(source, prolog): (curVec, b) =>
                if curVec.isEmpty then
                    Loop.done(epilog(b))
                else
                    f(curVec.head).map:
                        case true  => Loop.continue(curVec.tail, acc(b, true, curVec.head))
                        case false => Loop.done(epilog(acc(b, false, curVec.head)))
    end shiftedWhile

    // -----------------------------------------------------------------------------------------------------------------
    // Chunk
    // -----------------------------------------------------------------------------------------------------------------

    /** Applies an effect-producing function to each element of a `Chunk`.
      *
      * @param source
      *   The input `Chunk`
      * @param f
      *   The effect-producing function to apply to each element
      * @return
      *   A new effect that produces a Chunk of results
      */
    def foreach[A, B, S](source: Chunk[A])(f: Safepoint ?=> A => B < S)(using Frame, Safepoint): Chunk[B] < S =
        val len = source.length
        len match
            case 0 => Chunk.empty
            case 1 => f(source.head).map(Chunk.Indexed.single(_))
            case _ =>
                Loop.indexed(Chunk.empty[B]): (index, acc) =>
                    if index == len then
                        Loop.done(acc)
                    else
                        f(source(index)).map: u =>
                            Loop.continue(acc.appended(u))
        end match
    end foreach

    /** Applies an effect-producing function to each element of a `Chunk`, and concatenates the resulting collections.
      *
      * @param source
      *   The input `Chunk`
      * @param f
      *   The effect-producing function that returns a collection of results per element
      * @return
      *   A new effect that produces a flattened Chunk of all results
      */
    def foreachConcat[A, B, S](source: Chunk[A])(f: Safepoint ?=> A => IterableOnce[B] < S)(using
        Frame,
        Safepoint
    ): Chunk[B] < S =
        val len = source.length
        len match
            case 0 => Chunk.empty
            case _ =>
                Loop.indexed(Chunk.empty[Chunk[B]]): (idx, acc) =>
                    if idx == len then Loop.done(acc.flattenChunk)
                    else f(source(idx)).map(iterOnce => Loop.continue(acc.appended(Chunk.from(iterOnce))))
        end match
    end foreachConcat

    /** Applies an effect-producing function to each element of a sequence along with its index.
      *
      * @param source
      *   The input `Chunk`
      * @param f
      *   The effect-producing function to apply to each element and its index
      * @return
      *   A new effect that produces a Chunk of results
      */
    def foreachIndexed[A, B, S](source: Chunk[A])(f: Safepoint ?=> (Int, A) => B < S)(using Frame, Safepoint): Chunk[B] < S =
        val len = source.length
        len match
            case 0 => Chunk.empty
            case 1 => f(0, source.head).map(Chunk.Indexed.single(_))
            case _ =>
                Loop.indexed(Chunk.empty[B]): (index, acc) =>
                    if index == len then
                        Loop.done(acc)
                    else
                        f(index, source(index)).map: u =>
                            Loop.continue(acc.appended(u))
        end match
    end foreachIndexed

    /** Applies an effect-producing function to each element of a `Chunk`, discarding the results.
      *
      * @param source
      *   The input `Chunk`
      * @param f
      *   The effect-producing function to apply to each element
      * @return
      *   A new effect that produces Unit
      */
    def foreachDiscard[A, B, S](source: Chunk[A])(f: Safepoint ?=> A => Any < S)(using Frame, Safepoint): Unit < S =
        val len = source.length
        len match
            case 0 => ()
            case 1 => f(source.head).unit
            case _ =>
                Loop.indexed: index =>
                    if index == len then Loop.done
                    else f(source(index)).andThen(Loop.continue)
        end match
    end foreachDiscard

    /** Filters elements of a `Chunk` based on an effect-producing predicate.
      *
      * @param source
      *   The input `Chunk`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Chunk of filtered elements
      */
    def filter[A, S](source: Chunk[A])(f: Safepoint ?=> A => Boolean < S)(using Frame, Safepoint): Chunk[A] < S =
        val len = source.length
        len match
            case 0 => Chunk.empty
            case 1 =>
                f(source.head).map:
                    case true  => Chunk.Indexed.single(source.head)
                    case false => Chunk.empty
            case _ =>
                Loop.indexed(Chunk.empty[A]): (idx, acc) =>
                    if idx == len then Loop.done(acc)
                    else
                        val current = source(idx)
                        f(current).map:
                            case true  => Loop.continue(acc.appended(current))
                            case false => Loop.continue(acc)
        end match
    end filter

    /** Folds over a `Chunk` with an effect-producing function.
      *
      * @param source
      *   The input `Chunk`
      * @param acc
      *   The initial accumulator value
      * @param f
      *   The effect-producing folding function
      * @return
      *   A new effect that produces the final accumulated value
      */
    def foldLeft[A, B, S](source: Chunk[A])(acc: B)(f: Safepoint ?=> (B, A) => B < S)(using Frame, Safepoint): B < S =
        val len = source.length
        len match
            case 0 => acc
            case 1 => f(acc, source.head)
            case _ =>
                Loop.indexed(acc): (idx, acc) =>
                    if idx == len then Loop.done(acc)
                    else f(acc, source(idx)).map(Loop.continue(_))
        end match
    end foldLeft

    /** Collects and transforms elements from a `Chunk` using an effect-producing function that returns Maybe values.
      *
      * This method applies the given function to each element in the `Chunk` and collects only the Present values into a Chunk. It's
      * similar to a combination of flatMap and filter, where elements are both transformed and filtered in a single pass.
      *
      * @param source
      *   The input `Chunk`
      * @param f
      *   The effect-producing function that returns Maybe values
      * @return
      *   A new effect that produces a Chunk containing only the Present values after transformation
      */
    def collect[A, B, S](source: Chunk[A])(f: Safepoint ?=> A => Maybe[B] < S)(using Frame, Safepoint): Chunk[B] < S =
        val len = source.length
        len match
            case 0 => Chunk.empty
            case 1 =>
                f(source.head).map:
                    case Absent     => Chunk.empty
                    case Present(v) => Chunk.Indexed.single(v)
            case _ =>
                Loop.indexed(Chunk.empty[B]): (idx, acc) =>
                    if idx == len then Loop.done(acc)
                    else
                        val current = source(idx)
                        f(current).map:
                            case Absent     => Loop.continue(acc)
                            case Present(v) => Loop.continue(acc.appended(v))
        end match
    end collect

    /** Collects the results of a `Chunk` of effects into a single effect.
      *
      * @param source
      *   The `Chunk` of effects
      * @return
      *   A new effect that produces a Chunk of results
      */
    def collectAll[A, S](source: Chunk[A < S])(using Frame, Safepoint): Chunk[A] < S =
        val len = source.length
        len match
            case 0 => Chunk.empty
            case 1 => source.head.map(Chunk.Indexed.single(_))
            case _ =>
                Loop.indexed(Chunk.empty[A]): (idx, acc) =>
                    if idx == len then Loop.done(acc)
                    else
                        source(idx).map: v =>
                            Loop.continue(acc.appended(v))
        end match
    end collectAll

    /** Collects the results of a `Chunk` of effects, discarding the results.
      *
      * @param source
      *   The `Chunk` of effects
      * @return
      *   A new effect that produces Unit
      */
    def collectAllDiscard[A, S](source: Chunk[A < S])(using Frame, Safepoint): Unit < S =
        val len = source.length
        len match
            case 0 => ()
            case 1 => source.head.unit
            case _ =>
                Loop.indexed: idx =>
                    if idx == len then Loop.done
                    else source(idx).andThen(Loop.continue)
        end match
    end collectAllDiscard

    /** Finds the first element in a `Chunk` that satisfies a predicate.
      *
      * @param source
      *   The input `Chunk`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces Maybe of the first matching element
      */
    def findFirst[A, B, S](source: Chunk[A])(f: Safepoint ?=> A => Maybe[B] < S)(using Frame, Safepoint): Maybe[B] < S =
        val len = source.length
        len match
            case 0 => Absent
            case 1 => f(source.head)
            case _ =>
                Loop.indexed: idx =>
                    if idx == len then Loop.done(Absent)
                    else
                        f(source(idx)).map:
                            case Absent         => Loop.continue
                            case p @ Present(_) => Loop.done(p)
        end match
    end findFirst

    /** Takes elements from a `Chunk` while a predicate holds true.
      *
      * @param source
      *   The input `Chunk`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Chunk of taken elements
      */
    def takeWhile[A, S](source: Chunk[A])(f: Safepoint ?=> A => Boolean < S)(using Frame, Safepoint): Chunk[A] < S =
        val len = source.length
        len match
            case 0 => Chunk.empty
            case 1 =>
                f(source.head).map:
                    case true  => Chunk.Indexed.single(source.head)
                    case false => Chunk.empty[A]
            case _ =>
                Loop.indexed(Chunk.empty[A]): (idx, acc) =>
                    if idx == len then Loop.done(acc)
                    else
                        val current = source(idx)
                        f(current).map:
                            case true  => Loop.continue(acc.appended(current))
                            case false => Loop.done(acc)
        end match
    end takeWhile

    /** Splits the collection into prefix/suffix pair where all elements in the prefix satisfy `f`.
      *
      * @param f
      *   A predicate that returns `true` while elements should be included in the prefix
      * @return
      *   A tuple `(prefix, suffix)` where:
      *   - `prefix`: All elements before first failure of `f`
      *   - `suffix`: First failing element and all remaining elements
      */
    def span[A, S](source: Chunk[A])(f: Safepoint ?=> A => Boolean < S)(using Frame, Safepoint): (Chunk[A], Chunk[A]) < S =
        val len = source.length
        len match
            case 0 => (Chunk.empty, Chunk.empty)
            case 1 =>
                f(source.head).map:
                    case true  => (Chunk.Indexed.single(source.head), Chunk.empty)
                    case false => (Chunk.empty, Chunk.Indexed.single(source.head))
            case _ =>
                Loop.indexed(Chunk.empty[A]): (idx, acc) =>
                    if idx == len then Loop.done((acc, Chunk.empty))
                    else
                        val current = source(idx)
                        f(current).map:
                            case true  => Loop.continue(acc.appended(current))
                            case false => Loop.done((acc, source.drop(idx)))
        end match
    end span

    /** Drops elements from a `Chunk` while a predicate holds true.
      *
      * @param source
      *   The input `Chunk`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Chunk of remaining elements
      */
    def dropWhile[A, S](source: Chunk[A])(f: Safepoint ?=> A => Boolean < S)(using Frame, Safepoint): Chunk[A] < S =
        val len = source.length
        len match
            case 0 => Chunk.empty
            case 1 =>
                f(source.head).map:
                    case true  => Chunk.empty
                    case false => Chunk.Indexed.single(source.head)
            case _ =>
                Loop.indexed: idx =>
                    if idx == len then Loop.done(Chunk.empty)
                    else
                        f(source(idx)).map:
                            case true  => Loop.continue
                            case false => Loop.done(source.drop(idx))
        end match
    end dropWhile

    /** Splits the collection into two chunks, depending on the result of the predicate.
      *
      * @param source
      *   The input `Chunk`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A tuple `(lefts, rights)` where:
      *   - `lefts`: All elements that satisfy the predicate
      *   - `rights`: All elements that do not satisfy the predicate
      */
    def partition[S, A](source: Chunk[A])(f: Safepoint ?=> A => Boolean < S)(using
        Frame,
        Safepoint
    ): (Chunk[A], Chunk[A]) < S =
        val len = source.length
        len match
            case 0 => (Chunk.empty, Chunk.empty)
            case 1 =>
                f(source.head).map:
                    case true  => (Chunk.Indexed.single(source.head), Chunk.empty)
                    case false => (Chunk.empty, Chunk.Indexed.single(source.head))
            case _ =>
                Loop.indexed(Chunk.empty[A], Chunk.empty[A]): (idx, trues, falses) =>
                    if idx == len then Loop.done((trues, falses))
                    else
                        val current = source(idx)
                        f(current).map:
                            case true  => Loop.continue(trues.appended(current), falses)
                            case false => Loop.continue(trues, falses.appended(current))
        end match
    end partition

    /** Splits the collection into two chunks, depending on the result of the effect-producing function.
      *
      * @param source
      *   The input `Chunk`
      * @param f
      *   The effect-producing function that returns an Either
      * @return
      *   A tuple `(lefts, rights)` where:
      *   - `lefts`: All elements that are Left
      *   - `rights`: All elements that are Right
      */
    def partitionMap[S, A, A1, A2](source: Chunk[A])(f: Safepoint ?=> A => Either[A1, A2] < S)(using
        Frame,
        Safepoint
    ): (Chunk[A1], Chunk[A2]) < S =
        val len = source.length
        len match
            case 0 => (Chunk.empty, Chunk.empty)
            case 1 =>
                f(source.head).map:
                    case Left(a1)  => (Chunk.Indexed.single(a1), Chunk.empty)
                    case Right(a2) => (Chunk.empty, Chunk.Indexed.single(a2))
            case _ =>
                Loop.indexed(Chunk.empty[A1], Chunk.empty[A2]): (idx, lefts, rights) =>
                    if idx == len then Loop.done((lefts, rights))
                    else
                        val current = source(idx)
                        f(current).map:
                            case Left(a1)  => Loop.continue(lefts.appended(a1), rights)
                            case Right(a2) => Loop.continue(lefts, rights.appended(a2))
        end match
    end partitionMap

    /** Computes a prefix scan of the collection.
      *
      * @param z
      *   Initial accumulator value
      * @param op
      *   Effectful operation that combines accumulator with each element
      * @return
      *   Chunk containing all intermediate accumulator states
      */
    def scanLeft[S, A, B](source: Chunk[A])(z: B)(op: Safepoint ?=> (B, A) => B < S)(using
        Frame,
        Safepoint
    ): Chunk[B] < S =
        val len = source.length
        len match
            case 0 => Chunk.Indexed.single(z)
            case _ =>
                Loop.indexed[Chunk[B], B, Chunk[B], S](Chunk.Indexed.single(z), z): (idx, acc, current) =>
                    if idx == len then Loop.done(acc)
                    else
                        op(current, source(idx)).map: next =>
                            Loop.continue(acc.appended(next), next)
        end match
    end scanLeft

    /** Groups elements of the collection by the result of the function.
      *
      * @param source
      *   The input `Chunk`
      * @param f
      *   The effect-producing function that returns the key for each element
      * @return
      *   A Map where keys are the results of the function and values are chunks of elements
      */
    def groupBy[S, A, K](source: Chunk[A])(f: Safepoint ?=> A => K < S)(using
        Frame,
        Safepoint
    ): Map[K, Chunk[A]] < S =
        val len = source.length
        len match
            case 0 => Map.empty[K, Chunk[A]]
            case 1 => f(source.head).map(k => Map(k -> Chunk.Indexed.single(source.head)))
            case _ =>
                Loop.indexed(Map.empty[K, Chunk[A]]): (idx, acc) =>
                    if idx == len then Loop.done(acc)
                    else
                        val current = source(idx)
                        f(current).map: k =>
                            Loop.continue(
                                acc.updatedWith(k) {
                                    case Some(current) => Some(current.appended(source(idx)))
                                    case None          => Some(Chunk.Indexed.single(source(idx)))
                                }
                            )
        end match
    end groupBy

    /** Groups elements of the collection by the result of the function and applies a transformation to each element.
      *
      * @param source
      *   The input `Chunk`
      * @param key
      *   The effect-producing function that returns the key for each element
      * @param f
      *   The effect-producing function that returns the value for each element
      * @return
      *   A Map where keys are the results of the function and values are chunks of transformed elements
      */
    def groupMap[S, A, K, B](source: Chunk[A])(key: Safepoint ?=> A => K < S)(f: Safepoint ?=> A => B < S)(using
        Frame,
        Safepoint
    ): Map[K, Chunk[B]] < S =
        val len = source.length
        len match
            case 0 => Map.empty[K, Chunk[B]]
            case 1 =>
                for
                    k <- key(source.head)
                    b <- f(source.head)
                yield Map(k -> Chunk.Indexed.single(b))
            case _ =>
                Loop.indexed(Map.empty[K, Chunk[B]]): (idx, acc) =>
                    if idx == len then Loop.done(acc)
                    else
                        for
                            k <- key(source(idx))
                            b <- f(source(idx))
                        yield Loop.continue(
                            acc.updatedWith(k) {
                                case Some(current) => Some(current.appended(b))
                                case None          => Some(Chunk.Indexed.single(b))
                            }
                        )
        end match
    end groupMap

    /** Processes elements of a `Chunk` while a predicate holds true, maintaining an accumulator state. This function implements a stateful
      * iteration pattern where:
      *   1. The predicate function `f` determines whether to continue processing
      *   2. The accumulator function `acc` updates state based on the predicate result
      *   3. The epilog function transforms the final accumulator state into the result
      *
      * @param source
      *   The input `Chunk` to process
      * @param prolog
      *   Initial state value for the accumulator
      * @param f
      *   Effect-producing predicate that determines whether to continue processing
      * @param acc
      *   Function that updates the accumulator state based on predicate result and current element
      * @param epilog
      *   Function that transforms the final accumulator state into the result
      * @return
      *   A new effect that produces the final transformed result
      */
    private[kyo] def shiftedWhile[A, S, B, C](source: Chunk[A])(
        prolog: B,
        f: Safepoint ?=> A => Boolean < S,
        acc: (B, Boolean, A) => B,
        epilog: B => C
    )(using Frame, Safepoint): C < S =
        val len = source.length
        len match
            case 0 => epilog(prolog)
            case 1 => f(source.head).map(b => epilog(acc(prolog, b, source.head)))
            case _ =>
                Loop.indexed(prolog): (idx, b) =>
                    if idx == len then Loop.done(epilog(b))
                    else
                        val current = source(idx)
                        f(current).map:
                            case true  => Loop.continue(acc(b, true, current))
                            case false => Loop.done(epilog(acc(b, false, current)))
        end match
    end shiftedWhile

    // -----------------------------------------------------------------------------------------------------------------
    // Set
    // -----------------------------------------------------------------------------------------------------------------

    /** Applies an effect-producing function to each element of a `Set`.
      *
      * @param source
      *   The input `Set`
      * @param f
      *   The effect-producing function to apply to each element
      * @return
      *   A new effect that produces a Set of results
      */
    def foreach[A, B, S](source: Set[A])(f: Safepoint ?=> A => B < S)(using Frame, Safepoint): Set[B] < S =
        if source.isEmpty then Set.empty
        else
            Loop(source, Set.empty[B]): (curSet, acc) =>
                if curSet.isEmpty then Loop.done(acc)
                else
                    val current = curSet.head
                    f(current).map: u =>
                        Loop.continue(curSet - current, acc + u)
        end if
    end foreach

    /** Applies an effect-producing function to each element of a `Set`, and concatenates the resulting collections.
      *
      * @param source
      *   The input `Set`
      * @param f
      *   The effect-producing function that returns a collection of results per element
      * @return
      *   A new effect that produces a flattened Set of all results
      */
    def foreachConcat[A, B, S](source: Set[A])(f: Safepoint ?=> A => IterableOnce[B] < S)(using
        Frame,
        Safepoint
    ): Set[B] < S =
        if source.isEmpty then Set.empty
        else
            Loop(source, Set.empty[B]): (curSet, acc) =>
                if curSet.isEmpty then Loop.done(acc)
                else
                    val current = curSet.head
                    f(current).map: u =>
                        Loop.continue(curSet - current, acc ++ u)
        end if
    end foreachConcat

    /** Applies an effect-producing function to each element of a sequence along with its index.
      *
      * @param source
      *   The input `Set`
      * @param f
      *   The effect-producing function to apply to each element and its index
      * @return
      *   A new effect that produces a Set of results
      */
    def foreachIndexed[A, B, S](source: Set[A])(f: Safepoint ?=> (Int, A) => B < S)(using Frame, Safepoint): Set[B] < S =
        if source.isEmpty then Set.empty
        else
            Loop.indexed(source, Set.empty[B]): (idx, curSet, acc) =>
                if curSet.isEmpty then Loop.done(acc)
                else
                    val current = curSet.head
                    f(idx, current).map: u =>
                        Loop.continue(curSet - current, acc + u)
        end if
    end foreachIndexed

    /** Applies an effect-producing function to each element of a `Set`, discarding the results.
      *
      * @param source
      *   The input `Set`
      * @param f
      *   The effect-producing function to apply to each element
      * @return
      *   A new effect that produces Unit
      */
    def foreachDiscard[A, B, S](source: Set[A])(f: Safepoint ?=> A => Any < S)(using Frame, Safepoint): Unit < S =
        if source.isEmpty then ()
        else
            Loop(source): curSet =>
                if curSet.isEmpty then Loop.done
                else
                    val current = curSet.head
                    f(current).andThen(Loop.continue(curSet - current))
    end foreachDiscard

    /** Filters elements of a `Set` based on an effect-producing predicate.
      *
      * @param source
      *   The input `Set`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Set of filtered elements
      */
    def filter[A, S](source: Set[A])(f: Safepoint ?=> A => Boolean < S)(using Frame, Safepoint): Set[A] < S =
        if source.isEmpty then Set.empty
        else
            Loop(source, Set.empty[A]): (curSet, acc) =>
                if curSet.isEmpty then Loop.done(acc)
                else
                    val current = curSet.head
                    f(current).map:
                        case true  => Loop.continue(curSet - current, acc + current)
                        case false => Loop.continue(curSet - current, acc)
        end if
    end filter

    /** Folds over a `Set` with an effect-producing function.
      *
      * @param source
      *   The input `Set`
      * @param acc
      *   The initial accumulator value
      * @param f
      *   The effect-producing folding function
      * @return
      *   A new effect that produces the final accumulated value
      */
    def foldLeft[A, B, S](source: Set[A])(acc: B)(f: Safepoint ?=> (B, A) => B < S)(using Frame, Safepoint): B < S =
        if source.isEmpty then acc
        else
            Loop(source, acc): (curSet, acc) =>
                if curSet.isEmpty then Loop.done(acc)
                else
                    val current = curSet.head
                    f(acc, current).map(Loop.continue(curSet - current, _))
        end if
    end foldLeft

    /** Collects and transforms elements from a `Set` using an effect-producing function that returns Maybe values.
      *
      * This method applies the given function to each element in the `Set` and collects only the Present values into a Set. It's similar to
      * a combination of flatMap and filter, where elements are both transformed and filtered in a single pass.
      *
      * @param source
      *   The input `Set`
      * @param f
      *   The effect-producing function that returns Maybe values
      * @return
      *   A new effect that produces a Set containing only the Present values after transformation
      */
    def collect[A, B, S](source: Set[A])(f: Safepoint ?=> A => Maybe[B] < S)(using Frame, Safepoint): Set[B] < S =
        if source.isEmpty then Set.empty
        else
            Loop(source, Set.empty[B]): (curSet, acc) =>
                if curSet.isEmpty then Loop.done(acc)
                else
                    val current = curSet.head
                    f(current).map:
                        case Absent     => Loop.continue(curSet - current, acc)
                        case Present(v) => Loop.continue(curSet - current, acc + v)
        end if
    end collect

    /** Collects the results of a `Set` of effects into a single effect.
      *
      * @param source
      *   The `Set` of effects
      * @return
      *   A new effect that produces a Set of results
      */
    def collectAll[A, S](source: Set[A < S])(using Frame, Safepoint): Set[A] < S =
        if source.isEmpty then Set.empty
        else
            Loop(source, Set.empty[A]): (curSet, acc) =>
                if curSet.isEmpty then Loop.done(acc)
                else
                    val current = curSet.head
                    current.map: v =>
                        Loop.continue(curSet - current, acc + v)
        end if
    end collectAll

    /** Collects the results of a `Set` of effects, discarding the results.
      *
      * @param source
      *   The `Set` of effects
      * @return
      *   A new effect that produces Unit
      */
    def collectAllDiscard[A, S](source: Set[A < S])(using Frame, Safepoint): Unit < S =
        if source.isEmpty then ()
        else
            Loop(source): curSet =>
                if curSet.isEmpty then Loop.done
                else
                    val current = curSet.head
                    current.unit.andThen(Loop.continue(curSet - current))
        end if
    end collectAllDiscard

    /** Finds the first element in a `Set` that satisfies a predicate.
      *
      * @param source
      *   The input `Set`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces Maybe of the first matching element
      */
    def findFirst[A, B, S](source: Set[A])(f: Safepoint ?=> A => Maybe[B] < S)(using Frame, Safepoint): Maybe[B] < S =
        if source.isEmpty then Absent
        else
            Loop(source): curSet =>
                if curSet.isEmpty then Loop.done(Absent)
                else
                    val current = curSet.head
                    f(current).map:
                        case Absent         => Loop.continue(curSet - current)
                        case p @ Present(_) => Loop.done(p)
        end if
    end findFirst

    /** Takes elements from a `Set` while a predicate holds true.
      *
      * @param source
      *   The input `Set`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Chunk of taken elements
      */
    def takeWhile[A, S](source: Set[A])(f: Safepoint ?=> A => Boolean < S)(using Frame, Safepoint): Set[A] < S =
        if source.isEmpty then Set.empty
        else
            Loop(source, Set.empty[A]): (curSet, acc) =>
                if curSet.isEmpty then Loop.done(acc)
                else
                    val current = curSet.head
                    f(current).map:
                        case true  => Loop.continue(curSet - current, acc + current)
                        case false => Loop.done(acc)
        end if
    end takeWhile

    /** Splits the collection into prefix/suffix pair where all elements in the prefix satisfy `f`.
      *
      * @param f
      *   A predicate that returns `true` while elements should be included in the prefix
      * @return
      *   A tuple `(prefix, suffix)` where:
      *   - `prefix`: All elements before first failure of `f`
      *   - `suffix`: First failing element and all remaining elements
      */
    def span[A, S](source: Set[A])(f: Safepoint ?=> A => Boolean < S)(using Frame, Safepoint): (Set[A], Set[A]) < S =
        if source.isEmpty then (Set.empty, Set.empty)
        else
            Loop(source, Set.empty[A]): (curSet, acc) =>
                if curSet.isEmpty then Loop.done(acc -> Set.empty)
                else
                    val current = curSet.head
                    f(current).map:
                        case true  => Loop.continue(curSet - current, acc + current)
                        case false => Loop.done(acc -> curSet)
        end if
    end span

    /** Drops elements from a `Set` while a predicate holds true.
      *
      * @param source
      *   The input `Set`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Set of remaining elements
      */
    def dropWhile[A, S](source: Set[A])(f: Safepoint ?=> A => Boolean < S)(using Frame, Safepoint): Set[A] < S =
        if source.isEmpty then Set.empty
        else
            Loop(source): curSet =>
                if curSet.isEmpty then Loop.done(curSet)
                else
                    val current = curSet.head
                    f(current).map:
                        case true  => Loop.continue(curSet - current)
                        case false => Loop.done(curSet)
        end if
    end dropWhile

    /** Splits the collection into two sets, depending on the result of the predicate.
      *
      * @param source
      *   The input `Set`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A tuple `(lefts, rights)` where:
      *   - `lefts`: All elements that satisfy the predicate
      *   - `rights`: All elements that do not satisfy the predicate
      */
    def partition[S, A](source: Set[A])(f: Safepoint ?=> A => Boolean < S)(using
        Frame,
        Safepoint
    ): (Set[A], Set[A]) < S =
        if source.isEmpty then (Set.empty, Set.empty)
        else
            Loop(source, Set.empty[A], Set.empty[A]): (curSet, lefts, rights) =>
                if curSet.isEmpty then Loop.done((lefts, rights))
                else
                    val current = curSet.head
                    f(current).map:
                        case true  => Loop.continue(curSet - current, lefts + current, rights)
                        case false => Loop.continue(curSet - current, lefts, rights + current)
        end if
    end partition

    /** Splits the collection into two sets, depending on the result of the effect-producing function.
      *
      * @param source
      *   The input `Set`
      * @param f
      *   The effect-producing function that returns an Either
      * @return
      *   A tuple `(lefts, rights)` where:
      *   - `lefts`: All elements that are Left
      *   - `rights`: All elements that are Right
      */
    def partitionMap[S, A, A1, A2](source: Set[A])(f: Safepoint ?=> A => Either[A1, A2] < S)(using
        Frame,
        Safepoint
    ): (Set[A1], Set[A2]) < S =
        if source.isEmpty then (Set.empty, Set.empty)
        else
            Loop(source, Set.empty[A1], Set.empty[A2]): (curSet, lefts, rights) =>
                if curSet.isEmpty then Loop.done((lefts, rights))
                else
                    val current = curSet.head
                    f(current).map:
                        case Left(a1)  => Loop.continue(curSet - current, lefts + a1, rights)
                        case Right(a2) => Loop.continue(curSet - current, lefts, rights + a2)
        end if
    end partitionMap

    /** Computes a prefix scan of the collection.
      *
      * @param z
      *   Initial accumulator value
      * @param op
      *   Effectful operation that combines accumulator with each element
      * @return
      *   Set containing all intermediate accumulator states
      */
    def scanLeft[S, A, B](source: Set[A])(z: B)(op: Safepoint ?=> (B, A) => B < S)(using
        Frame,
        Safepoint
    ): Set[B] < S =
        if source.isEmpty then Set(z)
        else
            Loop(source, z, Set(z)): (curSet, acc, accSet) =>
                if curSet.isEmpty then Loop.done(accSet)
                else
                    val current = curSet.head
                    op(acc, current).map: next =>
                        Loop.continue(curSet - current, next, accSet + next)
        end if
    end scanLeft

    /** Groups elements of the collection by the result of the function.
      *
      * @param source
      *   The input `Set`
      * @param f
      *   The effect-producing function that returns the key for each element
      * @return
      *   A Map where keys are the results of the function and values are sets of elements
      */
    def groupBy[S, A, K](source: Set[A])(f: Safepoint ?=> A => K < S)(using
        Frame,
        Safepoint
    ): Map[K, Set[A]] < S =
        if source.isEmpty then Map.empty[K, Set[A]]
        else
            Loop(source, Map.empty[K, Set[A]]): (curSet, acc) =>
                if curSet.isEmpty then Loop.done(acc)
                else
                    val current = curSet.head
                    f(current).map: k =>
                        Loop.continue(
                            curSet - current,
                            acc.updatedWith(k) {
                                case Some(old) => Some(old + current)
                                case None      => Some(Set(current))
                            }
                        )
        end if
    end groupBy

    /** Groups elements of the collection by the result of the function and applies a transformation to each element.
      *
      * @param source
      *   The input `Set`
      * @param key
      *   The effect-producing function that returns the key for each element
      * @param f
      *   The effect-producing function that returns the value for each element
      * @return
      *   A Map where keys are the results of the function and values are chunks of transformed elements
      */
    def groupMap[S, A, K, B](source: Set[A])(key: Safepoint ?=> A => K < S)(f: Safepoint ?=> A => B < S)(using
        Frame,
        Safepoint
    ): Map[K, Set[B]] < S =
        if source.isEmpty then Map.empty[K, Set[B]]
        else
            Loop(source, Map.empty[K, Set[B]]): (curSet, acc) =>
                if curSet.isEmpty then Loop.done(acc)
                else
                    val current = curSet.head
                    key(current).map: k =>
                        f(current).map: b =>
                            Loop.continue(
                                curSet - current,
                                acc.updatedWith(k) {
                                    case Some(old) => Some(old + b)
                                    case None      => Some(Set(b))
                                }
                            )
        end if
    end groupMap

    /** Processes elements of a `Set` while a predicate holds true, maintaining an accumulator state. This function implements a stateful
      * iteration pattern where:
      *   1. The predicate function `f` determines whether to continue processing
      *   2. The accumulator function `acc` updates state based on the predicate result
      *   3. The epilog function transforms the final accumulator state into the result
      *
      * @param source
      *   The input `Set` to process
      * @param prolog
      *   Initial state value for the accumulator
      * @param f
      *   Effect-producing predicate that determines whether to continue processing
      * @param acc
      *   Function that updates the accumulator state based on predicate result and current element
      * @param epilog
      *   Function that transforms the final accumulator state into the result
      * @return
      *   A new effect that produces the final transformed result
      */
    private[kyo] def shiftedWhile[A, S, B, C](source: Set[A])(
        prolog: B,
        f: Safepoint ?=> A => Boolean < S,
        acc: (B, Boolean, A) => B,
        epilog: B => C
    )(using Frame, Safepoint): C < S =
        if source.isEmpty then epilog(prolog)
        else
            Loop(source, prolog): (curSet, b) =>
                if curSet.isEmpty then Loop.done(epilog(b))
                else
                    val current = curSet.head
                    f(current).map:
                        case true  => Loop.continue(curSet - current, acc(b, true, current))
                        case false => Loop.done(epilog(acc(b, false, current)))
        end if
    end shiftedWhile

    // -----------------------------------------------------------------------------------------------------------------
    // Map
    // -----------------------------------------------------------------------------------------------------------------

    /** Applies an effect-producing function to each element of a `Map`.
      *
      * @param source
      *   The input `Map`
      * @param f
      *   The effect-producing function to apply to each element
      * @return
      *   A new effect that produces a Map of results
      */
    def foreach[K1, V1, K2, V2, S](source: Map[K1, V1])(f: Safepoint ?=> ((K1, V1)) => (K2, V2) < S)(using
        Frame,
        Safepoint
    ): Map[K2, V2] < S =
        if source.isEmpty then Map.empty
        else
            Loop(source, Map.empty[K2, V2]): (curMap, acc) =>
                if curMap.isEmpty then Loop.done(acc)
                else
                    val cur = curMap.head
                    f(cur).map: (k2, v2) =>
                        Loop.continue(curMap - cur._1, acc + (k2 -> v2))
        end if
    end foreach

    /** Applies an effect-producing function to each element of a `Map`.
      *
      * @param source
      *   The input `Map`
      * @param f
      *   The effect-producing function to apply to each element
      * @return
      *   A new effect that produces a Chunk of results
      */
    @targetName("foreachToChunk")
    def foreach[K1, V1, B, S](source: Map[K1, V1])(f: Safepoint ?=> ((K1, V1)) => B < S)(using
        Frame,
        Safepoint
    ): Chunk[B] < S =
        if source.isEmpty then Chunk.empty
        else
            Loop(source, Chunk.empty[B]): (curMap, acc) =>
                if curMap.isEmpty then Loop.done(acc)
                else
                    val cur = curMap.head
                    f(cur).map: b =>
                        Loop.continue(curMap - cur._1, acc.append(b))
        end if
    end foreach

    /** Applies an effect-producing function to each element of a `Map`, and concatenates the resulting collections.
      *
      * @param source
      *   The input `Map`
      * @param f
      *   The effect-producing function that returns a collection of results per element
      * @return
      *   A new effect that produces a flattened Map of all results
      */
    def foreachConcat[K1, V1, K2, V2, S](source: Map[K1, V1])(f: Safepoint ?=> ((K1, V1)) => IterableOnce[(K2, V2)] < S)(using
        Frame,
        Safepoint
    ): Map[K2, V2] < S =
        if source.isEmpty then Map.empty
        else
            Loop(source, Map.empty[K2, V2]): (curMap, acc) =>
                if curMap.isEmpty then Loop.done(acc)
                else
                    val cur = curMap.head
                    f(cur).map: it =>
                        Loop.continue(curMap - cur._1, acc ++ it)
        end if
    end foreachConcat

    /** Applies an effect-producing function to each element of a `Map`, and concatenates the resulting collections.
      *
      * @param source
      *   The input `Map`
      * @param f
      *   The effect-producing function that returns a collection of results per element
      * @return
      *   A new effect that produces a flattened Chunk of all results
      */
    @targetName("foreachConcatToChunk")
    def foreachConcat[K1, V1, B, S](source: Map[K1, V1])(f: Safepoint ?=> ((K1, V1)) => IterableOnce[B] < S)(using
        Frame,
        Safepoint
    ): Chunk[B] < S =
        if source.isEmpty then Chunk.empty
        else
            Loop(source, Chunk.empty[Chunk[B]]): (curMap, acc) =>
                if curMap.isEmpty then Loop.done(acc.flattenChunk)
                else
                    val cur = curMap.head
                    f(cur).map: it =>
                        Loop.continue(curMap - cur._1, acc.append(Chunk.from(it)))
        end if
    end foreachConcat

    /** Applies an effect-producing function to each element of a `Map`, discarding the results.
      *
      * @param source
      *   The input `Map`
      * @param f
      *   The effect-producing function to apply to each element
      * @return
      *   A new effect that produces Unit
      */
    def foreachDiscard[K1, V1, S](source: Map[K1, V1])(f: Safepoint ?=> ((K1, V1)) => Any < S)(using Frame, Safepoint): Unit < S =
        if source.isEmpty then ()
        else
            Loop(source): curMap =>
                if curMap.isEmpty then Loop.done
                else
                    val cur = curMap.head
                    f(cur).andThen(Loop.continue(curMap - cur._1))
    end foreachDiscard

    /** Filters elements of a `Map` based on an effect-producing predicate.
      *
      * @param source
      *   The input `Map`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Map of filtered elements
      */
    def filter[K1, V1, S](source: Map[K1, V1])(f: Safepoint ?=> ((K1, V1)) => Boolean < S)(using Frame, Safepoint): Map[K1, V1] < S =
        if source.isEmpty then Map.empty
        else
            Loop(source, Map.empty[K1, V1]): (curMap, acc) =>
                if curMap.isEmpty then Loop.done(acc)
                else
                    val cur = curMap.head
                    f(cur).map:
                        case true  => Loop.continue(curMap - cur._1, acc + cur)
                        case false => Loop.continue(curMap - cur._1, acc)
        end if
    end filter

    /** Filters elements of a `Map` based on an effect-producing predicate on keys.
      *
      * @param source
      *   The input `Map`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Map of filtered elements
      */
    def filterKeys[K1, V1, S](source: Map[K1, V1])(f: Safepoint ?=> K1 => Boolean < S)(using Frame, Safepoint): Map[K1, V1] < S =
        if source.isEmpty then Map.empty
        else
            Loop(source, Map.empty[K1, V1]): (curMap, acc) =>
                if curMap.isEmpty then Loop.done(acc)
                else
                    val cur = curMap.head
                    f(cur._1).map:
                        case true  => Loop.continue(curMap - cur._1, acc + cur)
                        case false => Loop.continue(curMap - cur._1, acc)
        end if
    end filterKeys

    /** Folds over a `Map` with an effect-producing function.
      *
      * @param source
      *   The input `Map`
      * @param acc
      *   The initial accumulator value
      * @param f
      *   The effect-producing folding function
      * @return
      *   A new effect that produces the final accumulated value
      */
    def foldLeft[K1, V1, B, S](source: Map[K1, V1])(acc: B)(f: Safepoint ?=> (B, (K1, V1)) => B < S)(using Frame, Safepoint): B < S =
        if source.isEmpty then acc
        else
            Loop(source, acc): (curMap, acc) =>
                if curMap.isEmpty then Loop.done(acc)
                else
                    val (k1, v1) = curMap.head
                    f(acc, (k1, v1)).map(Loop.continue(curMap - k1, _))
        end if
    end foldLeft

    /** Collects and transforms elements from a `Map` using an effect-producing function that returns Maybe values.
      *
      * This method applies the given function to each element in the `Map` and collects only the Present values into a Map. It's similar to
      * a combination of flatMap and filter, where elements are both transformed and filtered in a single pass.
      *
      * @param source
      *   The input `Map`
      * @param f
      *   The effect-producing function that returns Maybe values
      * @return
      *   A new effect that produces a Map containing only the Present values after transformation
      */
    def collect[K1, V1, K2, V2, S](source: Map[K1, V1])(f: Safepoint ?=> ((K1, V1)) => Maybe[(K2, V2)] < S)(using
        Frame,
        Safepoint
    ): Map[K2, V2] < S =
        if source.isEmpty then Map.empty
        else
            Loop(source, Map.empty[K2, V2]): (curMap, acc) =>
                if curMap.isEmpty then Loop.done(acc)
                else
                    val cur = curMap.head
                    f(cur).map:
                        case Absent        => Loop.continue(curMap - cur._1, acc)
                        case Present(pair) => Loop.continue(curMap - cur._1, acc + pair)
        end if
    end collect

    /** Collects and transforms elements from a `Map` using an effect-producing function that returns Maybe values.
      *
      * This method applies the given function to each element in the `Map` and collects only the Present values into a Map. It's similar to
      * a combination of flatMap and filter, where elements are both transformed and filtered in a single pass.
      *
      * @param source
      *   The input `Map`
      * @param f
      *   The effect-producing function that returns Maybe values
      * @return
      *   A new effect that produces a Chunk containing only the Present values after transformation
      */
    @targetName("collectToChunk")
    def collect[K1, V1, B, S](source: Map[K1, V1])(f: Safepoint ?=> ((K1, V1)) => Maybe[B] < S)(using
        Frame,
        Safepoint
    ): Chunk[B] < S =
        if source.isEmpty then Chunk.empty
        else
            Loop(source, Chunk.empty[B]): (curMap, acc) =>
                if curMap.isEmpty then Loop.done(acc)
                else
                    val cur = curMap.head
                    f(cur).map:
                        case Absent     => Loop.continue(curMap - cur._1, acc)
                        case Present(b) => Loop.continue(curMap - cur._1, acc.append(b))
        end if
    end collect

    /** Collects the results of a `Map` of effects into a single effect.
      *
      * @param source
      *   The `Map` of effects
      * @return
      *   A new effect that produces a Map of results
      */
    def collectAll[K1, V1, S](source: Map[K1, V1 < S])(using Frame, Safepoint): Map[K1, V1] < S =
        if source.isEmpty then Map.empty
        else
            Loop(source, Map.empty[K1, V1]): (curMap, acc) =>
                if curMap.isEmpty then Loop.done(acc)
                else
                    val cur = curMap.head
                    cur._2.map: v1 =>
                        Loop.continue(curMap - cur._1, acc + (cur._1 -> v1))
        end if
    end collectAll

    /** Collects the results of a `Map` of effects, discarding the results.
      *
      * @param source
      *   The `Map` of effects
      * @return
      *   A new effect that produces Unit
      */
    def collectAllDiscard[K1, V1, S](source: Map[K1, V1 < S])(using Frame, Safepoint): Unit < S =
        if source.isEmpty then ()
        else
            Loop(source): curMap =>
                if curMap.isEmpty then Loop.done
                else
                    val cur = curMap.head
                    cur._2.andThen(Loop.continue(curMap - cur._1))
        end if
    end collectAllDiscard

    /** Finds the first element in a `Map` that satisfies a predicate.
      *
      * @param source
      *   The input `Map`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces Maybe of the first matching element
      */
    def findFirst[K1, V1, B, S](source: Map[K1, V1])(f: Safepoint ?=> ((K1, V1)) => Maybe[B] < S)(using Frame, Safepoint): Maybe[B] < S =
        if source.isEmpty then Absent
        else
            Loop(source): curMap =>
                if curMap.isEmpty then Loop.done(Absent)
                else
                    val cur = curMap.head
                    f(cur).map:
                        case Absent         => Loop.continue(curMap - cur._1)
                        case p @ Present(_) => Loop.done(p)
        end if
    end findFirst

    /** Takes elements from a `Map` while a predicate holds true.
      *
      * @param source
      *   The input `Map`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Chunk of taken elements
      */
    def takeWhile[K1, V1, S](source: Map[K1, V1])(f: Safepoint ?=> ((K1, V1)) => Boolean < S)(using Frame, Safepoint): Map[K1, V1] < S =
        if source.isEmpty then Map.empty
        else
            Loop(source, Map.empty[K1, V1]): (curMap, acc) =>
                if curMap.isEmpty then Loop.done(acc)
                else
                    val cur = curMap.head
                    f(cur).map:
                        case true  => Loop.continue(curMap - cur._1, acc + cur)
                        case false => Loop.done(acc)
        end if
    end takeWhile

    /** Splits the collection into prefix/suffix pair where all elements in the prefix satisfy `f`.
      *
      * @param f
      *   A predicate that returns `true` while elements should be included in the prefix
      * @return
      *   A tuple `(prefix, suffix)` where:
      *   - `prefix`: All elements before first failure of `f`
      *   - `suffix`: First failing element and all remaining elements
      */
    def span[K1, V1, S](source: Map[K1, V1])(f: Safepoint ?=> ((K1, V1)) => Boolean < S)(using
        Frame,
        Safepoint
    ): (Map[K1, V1], Map[K1, V1]) < S =
        if source.isEmpty then (Map.empty, Map.empty)
        else
            Loop(source, Map.empty[K1, V1]): (curMap, acc) =>
                if curMap.isEmpty then Loop.done(acc -> Map.empty)
                else
                    val cur = curMap.head
                    f(cur).map:
                        case true  => Loop.continue(curMap - cur._1, acc + cur)
                        case false => Loop.done(acc -> curMap)
        end if
    end span

    /** Drops elements from a `Map` while a predicate holds true.
      *
      * @param source
      *   The input `Map`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Map of remaining elements
      */
    def dropWhile[K1, V1, S](source: Map[K1, V1])(f: Safepoint ?=> ((K1, V1)) => Boolean < S)(using Frame, Safepoint): Map[K1, V1] < S =
        if source.isEmpty then Map.empty
        else
            Loop(source): curMap =>
                if curMap.isEmpty then Loop.done(curMap)
                else
                    val cur = curMap.head
                    f(cur).map:
                        case true  => Loop.continue(curMap - cur._1)
                        case false => Loop.done(curMap)
        end if
    end dropWhile

    /** Splits the collection into two maps, depending on the result of the predicate.
      *
      * @param source
      *   The input `Map`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A tuple `(lefts, rights)` where:
      *   - `lefts`: All elements that satisfy the predicate
      *   - `rights`: All elements that do not satisfy the predicate
      */
    def partition[K1, V1, S](source: Map[K1, V1])(f: Safepoint ?=> ((K1, V1)) => Boolean < S)(using
        Frame,
        Safepoint
    ): (Map[K1, V1], Map[K1, V1]) < S =
        if source.isEmpty then (Map.empty, Map.empty)
        else
            Loop(source, Map.empty[K1, V1], Map.empty[K1, V1]): (curMap, lefts, rights) =>
                if curMap.isEmpty then Loop.done((lefts, rights))
                else
                    val cur = curMap.head
                    f(cur).map:
                        case true  => Loop.continue(curMap - cur._1, lefts + cur, rights)
                        case false => Loop.continue(curMap - cur._1, lefts, rights + cur)
        end if
    end partition

    /** Splits the collection into two maps, depending on the result of the effect-producing function.
      *
      * @param source
      *   The input `Map`
      * @param f
      *   The effect-producing function that returns an Either
      * @return
      *   A tuple `(lefts, rights)` where:
      *   - `lefts`: All elements that are Left
      *   - `rights`: All elements that are Right
      */
    def partitionMap[K1, V1, K2, V2, K3, V3, S](source: Map[K1, V1])(f: Safepoint ?=> ((K1, V1)) => Either[(K2, V2), (K3, V3)] < S)(using
        Frame,
        Safepoint
    ): (Map[K2, V2], Map[K3, V3]) < S =
        if source.isEmpty then (Map.empty, Map.empty)
        else
            Loop(source, Map.empty[K2, V2], Map.empty[K3, V3]): (curMap, lefts, rights) =>
                if curMap.isEmpty then Loop.done((lefts, rights))
                else
                    val cur = curMap.head
                    f(cur).map:
                        case Left((k2, v2))  => Loop.continue(curMap - cur._1, lefts + (k2 -> v2), rights)
                        case Right((k3, v3)) => Loop.continue(curMap - cur._1, lefts, rights + (k3 -> v3))
        end if
    end partitionMap

    /** Computes a prefix scan of the collection.
      *
      * @param z
      *   Initial accumulator value
      * @param op
      *   Effectful operation that combines accumulator with each element
      * @return
      *   Chunk containing all intermediate accumulator states
      */
    def scanLeft[K1, V1, B, S](source: Map[K1, V1])(z: B)(op: Safepoint ?=> (B, (K1, V1)) => B < S)(using
        Frame,
        Safepoint
    ): Chunk[B] < S =
        if source.isEmpty then Chunk(z)
        else
            Loop(source, z, Chunk(z)): (curMap, acc, accChunk) =>
                if curMap.isEmpty then Loop.done(accChunk)
                else
                    val cur = curMap.head
                    op(acc, cur).map: next =>
                        Loop.continue(curMap - cur._1, next, accChunk.append(next))
        end if
    end scanLeft

    /** Groups elements of the collection by the result of the function.
      *
      * @param source
      *   The input `Map`
      * @param f
      *   The effect-producing function that returns the key for each element
      * @return
      *   A Map where keys are the results of the function and values are sets of elements
      */
    def groupBy[K1, V1, K2, S](source: Map[K1, V1])(f: Safepoint ?=> ((K1, V1)) => K2 < S)(using
        Frame,
        Safepoint
    ): Map[K2, Map[K1, V1]] < S =
        if source.isEmpty then Map.empty[K2, Map[K1, V1]]
        else
            Loop(source, Map.empty[K2, Map[K1, V1]]): (curMap, acc) =>
                if curMap.isEmpty then Loop.done(acc)
                else
                    val cur = curMap.head
                    f(cur).map: k =>
                        Loop.continue(
                            curMap - cur._1,
                            acc.updatedWith(k) {
                                case Some(old) => Some(old + cur)
                                case None      => Some(Map(cur))
                            }
                        )
        end if
    end groupBy

    /** Groups elements of the collection by the result of the function and applies a transformation to each element.
      *
      * @param source
      *   The input `Set`
      * @param key
      *   The effect-producing function that returns the key for each element
      * @param f
      *   The effect-producing function that returns the value for each element
      * @return
      *   A Map where keys are the results of the function and values are chunks of transformed elements
      */
    def groupMap[K1, V1, K2, V2, S](source: Map[K1, V1])(key: Safepoint ?=> ((K1, V1)) => K2 < S)(f: Safepoint ?=> ((K1, V1)) => V2 < S)(
        using
        Frame,
        Safepoint
    ): Map[K2, Chunk[V2]] < S =
        if source.isEmpty then Map.empty[K2, Chunk[V2]]
        else
            Loop(source, Map.empty[K2, Chunk[V2]]): (curMap, acc) =>
                if curMap.isEmpty then Loop.done(acc)
                else
                    val cur = curMap.head
                    key(cur).map: k =>
                        f(cur).map: v2 =>
                            Loop.continue(
                                curMap - cur._1,
                                acc.updatedWith(k) {
                                    case Some(old) => Some(old.append(v2))
                                    case None      => Some(Chunk(v2))
                                }
                            )
        end if
    end groupMap

    /** Processes elements of a `Map` while a predicate holds true, maintaining an accumulator state. This function implements a stateful
      * iteration pattern where:
      *   1. The predicate function `f` determines whether to continue processing
      *   2. The accumulator function `acc` updates state based on the predicate result
      *   3. The epilog function transforms the final accumulator state into the result
      *
      * @param source
      *   The input `Map` to process
      * @param prolog
      *   Initial state value for the accumulator
      * @param f
      *   Effect-producing predicate that determines whether to continue processing
      * @param acc
      *   Function that updates the accumulator state based on predicate result and current element
      * @param epilog
      *   Function that transforms the final accumulator state into the result
      * @return
      *   A new effect that produces the final transformed result
      */
    private[kyo] def shiftedWhile[K1, V1, S, B, C](source: Map[K1, V1])(
        prolog: B,
        f: Safepoint ?=> ((K1, V1)) => Boolean < S,
        acc: (B, Boolean, (K1, V1)) => B,
        epilog: B => C
    )(using Frame, Safepoint): C < S =
        if source.isEmpty then epilog(prolog)
        else
            Loop(source, prolog): (curMap, b) =>
                if curMap.isEmpty then Loop.done(epilog(b))
                else
                    val cur = curMap.head
                    f(cur).map:
                        case true  => Loop.continue(curMap - cur._1, acc(b, true, cur))
                        case false => Loop.done(epilog(acc(b, false, cur)))
        end if
    end shiftedWhile

end Kyo
