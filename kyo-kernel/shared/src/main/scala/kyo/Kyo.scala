package kyo

import kernel.Loop
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.collection.IterableOps
import scala.util.Failure
import scala.util.Success
import scala.util.Try

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
      * This is exactly equivalent to `lift(())`, as both lift the Unit value into the effect context without introducing any effect
      * suspension.
      *
      * @tparam S
      *   The effect context (can be Any)
      * @return
      *   A computation that directly produces Unit without suspension
      */
    inline def unit: Unit < Any = ()

    /** Run one of two effects based on the result of an effectful condition. An effectful if/then/else expression.
      *
      * @param condition
      *   Effectful boolean condition
      * @param ifTrue
      *   Effect to run if condition evaluates to true
      * @param ifFalse
      *   Effect to run if condition evaluates to false
      * @return
      *   An effect that runs ifTrue when the condition is evaluated to true, otherwise ifFalse
      */
    def when[S](condition: Boolean < S)[A, S1](ifTrue: => A < S1, ifFalse: => A < S1)(using Frame): A < (S & S1) =
        condition.map(if _ then ifTrue else ifFalse)

    /** Run an effect if an effectful condition evaluates to true. Returns a [[Maybe]] which is [[Present]] if the conditional effect is run
      * and [[Absent]] if not.
      *
      * @param condition
      *   Effectful boolean condition
      * @param ifTrue
      *   Effect to run if condition evaluates to true
      * @return
      *   An effect that runs `ifTrue` if `condition` evaluates to true
      */
    def when[S](condition: Boolean < S)[A, S1](ifTrue: => A < S1)(using Frame): Maybe[A] < (S & S1) =
        condition.map(if _ then ifTrue.map(Present(_)) else Absent)

    /** Run an effect if an effectful condition evaluates to false. Returns a [[Maybe]] which is [[Present]] if the conditional effect is
      * run and [[Absent]] if not.
      *
      * @param condition
      *   Effectful boolean condition
      * @param ifFalse
      *   Effect to run if condition evaluates to false
      * @return
      *   An effect that runs `ifFalse` if `condition` evaluates to false
      */
    def unless[S](condition: Boolean < S)[A, S1](ifFalse: => A < S1)(using Frame): Maybe[A] < (S & S1) =
        condition.map(if _ then Absent else ifFalse.map(Present(_)))

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
    def foreach[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: A => B < S)(using
        Frame
    ): CC[B] < S =
        Kyo.foreach(Chunk.from(source))(f).map: resultChunk =>
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
    def foreachConcat[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: A => IterableOnce[B] < S)(
        using Frame
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
    def foreachIndexed[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: (Int, A) => B < S)(using
        Frame
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
    def foreachDiscard[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: A => Any < S)(using
        Frame
    ): Unit < S =
        Kyo.foreachDiscard(Chunk.from(source))(f)
    end foreachDiscard

    /** Applies an effect-producing function to each element of a collection along with its index, discarding the results.
      *
      * @param source
      *   The input collection
      * @param f
      *   The effect-producing function to apply to each element and its index
      * @return
      *   A new effect that produces Unit
      */
    def foreachIndexedDiscard[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(
        f: (Int, A) => Any < S
    )(using Frame): Unit < S =
        Kyo.foreachIndexedDiscard(Chunk.from(source))(f)
    end foreachIndexedDiscard

    /** Filters elements of a collection based on an effect-producing predicate.
      *
      * @param source
      *   The input collection
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Chunk of filtered elements
      */
    def filter[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: A => Boolean < S)(using
        Frame
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
    def foldLeft[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(acc: B)(f: (B, A) => B < S)(using
        Frame
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
    def collect[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: A => Maybe[B] < S)(using
        Frame
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
    def collectAll[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A < S])(using Frame): CC[A] < S =
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
    def collectAllDiscard[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A < S])(using Frame): Unit < S =
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
    def findFirst[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: A => Maybe[B] < S)(using
        Frame
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
    def takeWhile[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: A => Boolean < S)(using
        Frame
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
    def span[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: A => Boolean < S)(using
        Frame
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
    def dropWhile[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: A => Boolean < S)(using
        Frame
    ): CC[A] < S =
        Kyo.dropWhile(Chunk.from(source))(f).map: resultChunk =>
            source.iterableFactory.from(resultChunk)
    end dropWhile

    /** Splits the collection into two, depending on the result of the predicate, keeping the source's own collection type.
      *
      * @return
      *   A tuple `(lefts, rights)` where:
      *   - `lefts`: All elements that satisfy the predicate
      *   - `rights`: All elements that do not satisfy the predicate
      */
    def partition[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: A => Boolean < S)(using
        Frame
    ): (CC[A], CC[A]) < S =
        Kyo.partition(Chunk.from(source))(f).map: (leftChunk, rightChunk) =>
            (source.iterableFactory.from(leftChunk), source.iterableFactory.from(rightChunk))
    end partition

    /** Applies `f` to every element and splits the results by which side of the `Either` they landed on, keeping the source's collection type.
      *
      * @return
      *   A tuple `(lefts, rights)` of the `Left` and `Right` results, each in order
      */
    def partitionMap[
        CC[X] <: Iterable[X] &
            IterableOps[
                X,
                CC,
                CC[X]
            ],
        A,
        A1,
        A2,
        S
    ](source: CC[A])(f: A => Either[A1, A2] < S)(using Frame): (CC[A1], CC[A2]) < S =
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
    def scanLeft[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(z: B)(op: (B, A) => B < S)(using
        Frame
    ): CC[B] < S =
        Kyo.scanLeft(Chunk.from(source))(z)(op).map: resultChunk =>
            source.iterableFactory.from(resultChunk)
    end scanLeft

    /** Groups the elements by the key `f` computes for each, keeping the source's own collection type for the groups.
      *
      * @return
      *   A `Map` from key to the elements that produced it, each group keeping the source's relative order
      */
    def groupBy[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, K, S](source: CC[A])(f: A => K < S)(using
        Frame
    ): Map[K, CC[A]] < S =
        Kyo.groupBy(Chunk.from(source))(f).map: resultChunk =>
            Map.from(resultChunk.view.mapValues(source.iterableFactory.from(_)))
    end groupBy

    /** Groups the elements by `key` and maps each through `f` in the same pass, keeping the source's own collection type for the groups.
      *
      * @return
      *   A `Map` from key to the transformed elements that produced it, each group keeping the source's relative order
      */
    def groupMap[
        CC[X] <: Iterable[X] &
            IterableOps[
                X,
                CC,
                CC[X]
            ],
        A,
        K,
        B,
        S
    ](source: CC[A])(key: A => K < S)(f: A => B < S)(using Frame): Map[K, CC[B]] < S =
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
    def fill[A, S](n: Int)(v: A < S)(using Frame): Chunk[A] < S =
        Loop.indexed(Chunk.empty[A]) { (idx, acc) =>
            if idx == n then Loop.done(acc)
            else v.map(t => Loop.continue(acc.append(t)))
        }

    /** Keeps the elements of a collection for which an effect-producing predicate does not hold.
      *
      * @param source
      *   The input collection
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces the collection without the elements the predicate accepted
      */
    def filterNot[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: A => Boolean < S)(using Frame): CC[A] < S =
        Kyo.filter(source)(a => f(a).map(!_))

    /** Finds the first element of a collection for which an effect-producing predicate holds, stopping at that element.
      *
      * @param source
      *   The input collection
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces the first accepted element, or Absent when none is accepted
      */
    def find[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: A => Boolean < S)(using Frame): Maybe[A] < S =
        Kyo.findFirst(source)(a => f(a).map(accepted => if accepted then Maybe(a) else Maybe.empty))

    /** Tests whether an effect-producing predicate holds for some element of a collection, stopping at the first that satisfies it.
      *
      * @param source
      *   The input collection
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces true when some element is accepted
      */
    def exists[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: A => Boolean < S)(using Frame): Boolean < S =
        Kyo.find(source)(f).map(_.isDefined)

    /** Tests whether an effect-producing predicate holds for every element of a collection, stopping at the first that fails it.
      *
      * @param source
      *   The input collection
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces true when every element is accepted
      */
    def forall[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: A => Boolean < S)(using Frame): Boolean < S =
        Kyo.exists(source)(a => f(a).map(!_)).map(!_)

    /** Counts the elements of a collection for which an effect-producing predicate holds.
      *
      * @param source
      *   The input collection
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces the number of accepted elements
      */
    def count[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: A => Boolean < S)(using Frame): Int < S =
        Kyo.foldLeft(source)(0)((n, a) => f(a).map(accepted => if accepted then n + 1 else n))

    /** Folds over a collection from the right with an effect-producing function.
      *
      * @param source
      *   The input collection
      * @param acc
      *   The initial accumulator value
      * @param f
      *   The effect-producing folding function, taking the element and the accumulator
      * @return
      *   A new effect that produces the final accumulated value
      */
    def foldRight[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(acc: B)(f: (A, B) => B < S)(using
        Frame
    ): B < S =
        Kyo.foldLeft(Chunk.from(source).reverse)(acc)((b, a) => f(a, b))

    /** Produces the running accumulations of an effect-producing function from the right, the final one first.
      *
      * @param source
      *   The input collection
      * @param z
      *   The initial accumulator value
      * @param op
      *   The effect-producing accumulation function, taking the element and the accumulator
      * @return
      *   A new effect that produces the accumulations as a collection of the same type
      */
    def scanRight[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(z: B)(op: (A, B) => B < S)(using
        Frame
    ): CC[B] < S =
        Kyo.scanLeft(Chunk.from(source).reverse)(z)((b, a) => op(a, b)).map(accumulated => source.iterableFactory.from(accumulated.reverse))

    /** Groups the elements of a collection by an effect-producing key, maps each with an effect-producing function, and reduces each
      * group with an effect-producing operation.
      *
      * @param source
      *   The input collection
      * @param key
      *   The effect-producing key function
      * @param f
      *   The effect-producing mapping function
      * @param reduce
      *   The effect-producing reduction of two mapped values
      * @return
      *   A new effect that produces the reduced value of each group
      */
    def groupMapReduce[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, K, B, S](source: CC[A])(key: A => K < S)(f: A => B < S)(
        reduce: (B, B) => B < S
    )(using Frame): Map[K, B] < S =
        Kyo.foldLeft(source)(Map.empty[K, B]) { (groups, a) =>
            key(a).map { k =>
                f(a).map { b =>
                    groups.get(k) match
                        case Some(previous) => reduce(previous, b).map(merged => groups.updated(k, merged))
                        case None           => groups.updated(k, b)
                }
            }
        }

    /** Finds the element of a collection with the greatest effect-producing key.
      *
      * @param source
      *   The input collection
      * @param f
      *   The effect-producing key function
      * @return
      *   A new effect that produces the first element with the greatest key, or Absent for an empty collection
      */
    def maxBy[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: A => B < S)(using
        Frame,
        Ordering[B]
    ): Maybe[A] < S =
        val ordering = summon[Ordering[B]]
        Kyo.foldLeft(source)(Maybe.empty[(A, B)]) { (best, a) =>
            f(a).map { key =>
                best match
                    case Present((_, bestKey)) if ordering.gteq(bestKey, key) => best
                    case _                                                    => Maybe((a, key))
            }
        }.map(_.map(_._1))
    end maxBy

    /** Tests whether two collections have the same length and an effect-producing relation holds for every pair of elements at the
      * same position, stopping at the first pair that fails it.
      *
      * @param source
      *   The input collection
      * @param that
      *   The collection to compare with
      * @param f
      *   The effect-producing relation
      * @return
      *   A new effect that produces true when the lengths match and every pair is related
      */
    def corresponds[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(that: IterableOnce[B])(
        f: (A, B) => Boolean < S
    )(using Frame): Boolean < S =
        val left  = Chunk.from(source)
        val right = Chunk.from(that)
        if left.size != right.size then false
        else Kyo.exists(left.zip(right))((a, b) => f(a, b).map(!_)).map(!_)
    end corresponds

    /** Applies an effect-producing function to each element of a collection for its effect and produces the collection unchanged.
      *
      * @param source
      *   The input collection
      * @param f
      *   The effect-producing function to apply to each element
      * @return
      *   A new effect that produces the input collection
      */
    def tapEach[CC[X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: A => B < S)(using Frame): CC[A] < S =
        Kyo.foreach(source)(a => f(a).andThen(a))

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
    def foreach[A, B, S](source: List[A])(f: A => B < S)(using Frame): List[B] < S =
        source match
            case Nil         => Nil
            case head :: Nil => f(head).map(_ :: Nil)
            case list        =>
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
    def foreachConcat[A, B, S](source: List[A])(f: A => IterableOnce[B] < S)(using
        Frame
    ): List[B] < S =
        source match
            case Nil         => Nil
            case head :: Nil => f(head).map(List.from(_))
            case list        =>
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
    def foreachIndexed[A, B, S](source: List[A])(f: (Int, A) => B < S)(using Frame): List[B] < S =
        source match
            case Nil         => Nil
            case head :: Nil => f(0, head).map(_ :: Nil)
            case list        =>
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
    def foreachDiscard[A, B, S](source: List[A])(f: A => Any < S)(using Frame): Unit < S =
        source match
            case Nil         => ()
            case head :: Nil => f(head).unit
            case list        =>
                Loop(list): curList =>
                    curList match
                        case head :: tail => f(head).andThen(Loop.continue(tail))
                        case Nil          => Loop.done
        end match
    end foreachDiscard

    /** Applies an effect-producing function to each element of a `List` along with its index, discarding the results.
      *
      * @param source
      *   The input `List`
      * @param f
      *   The effect-producing function to apply to each element and its index
      * @return
      *   A new effect that produces Unit
      */
    def foreachIndexedDiscard[A, B, S](source: List[A])(f: (Int, A) => Any < S)(using Frame): Unit < S =
        source match
            case Nil         => ()
            case head :: Nil => f(0, head).unit
            case list        =>
                Loop.indexed(list): (idx, curList) =>
                    curList match
                        case head :: tail => f(idx, head).andThen(Loop.continue(tail))
                        case Nil          => Loop.done
        end match
    end foreachIndexedDiscard

    /** Filters elements of an `List` based on an effect-producing predicate.
      *
      * @param source
      *   The input `List`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a List of filtered elements
      */
    def filter[A, S](source: List[A])(f: A => Boolean < S)(using Frame): List[A] < S =
        source match
            case Nil         => Nil
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
    def foldLeft[A, B, S](source: List[A])(acc: B)(f: (B, A) => B < S)(using Frame): B < S =
        source match
            case Nil         => acc
            case head :: Nil => f(acc, head)
            case list        =>
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
    def collect[A, B, S](source: List[A])(f: A => Maybe[B] < S)(using Frame): List[B] < S =
        source match
            case Nil         => Nil
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
    def collectAll[A, S](source: List[A < S])(using Frame): List[A] < S =
        source match
            case Nil         => Nil
            case head :: Nil => head.map(_ :: Nil)
            case list        =>
                Loop(list, Nil): (curList, accList) =>
                    curList match
                        case head :: tail => head.map(u => Loop.continue(tail, u :: accList))
                        case Nil          => Loop.done(accList.reverse)
        end match
    end collectAll

    /** Collects the results of a `List` of effects, discarding the results.
      *
      * @param source
      *   The `List` of effects
      * @return
      *   A new effect that produces Unit
      */
    def collectAllDiscard[A, S](source: List[A < S])(using Frame): Unit < S =
        source match
            case Nil         => ()
            case head :: Nil => head.unit
            case list        =>
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
    def findFirst[A, B, S](source: List[A])(f: A => Maybe[B] < S)(using Frame): Maybe[B] < S =
        source match
            case Nil         => Absent
            case head :: Nil => f(head)
            case list        =>
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
    def takeWhile[A, S](source: List[A])(f: A => Boolean < S)(using Frame): List[A] < S =
        source match
            case Nil  => Nil
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
    def span[A, S](source: List[A])(f: A => Boolean < S)(using Frame): (List[A], List[A]) < S =
        source match
            case Nil         => (Nil, Nil)
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
    def dropWhile[A, S](source: List[A])(f: A => Boolean < S)(using Frame): List[A] < S =
        source match
            case Nil  => Nil
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
    def partition[S, A](source: List[A])(f: A => Boolean < S)(using
        Frame
    ): (List[A], List[A]) < S =
        source match
            case Nil         => (Nil, Nil)
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
    def partitionMap[S, A, A1, A2](source: List[A])(f: A => Either[A1, A2] < S)(using
        Frame
    ): (List[A1], List[A2]) < S =
        source match
            case Nil         => (Nil, Nil)
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
    def scanLeft[S, A, B](source: List[A])(z: B)(op: (B, A) => B < S)(using
        Frame
    ): List[B] < S =
        source match
            case Nil         => z :: Nil
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
    def groupBy[S, A, K](source: List[A])(f: A => K < S)(using
        Frame
    ): Map[K, List[A]] < S =
        source match
            case Nil         => Map.empty[K, List[A]]
            case head :: Nil => f(head).map(k => Map(k -> (head :: Nil)))
            case list        =>
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
    def groupMap[S, A, K, B](source: List[A])(key: A => K < S)(f: A => B < S)(using
        Frame
    ): Map[K, List[B]] < S =
        source match
            case Nil         => Map.empty[K, List[B]]
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

    // -----------------------------------------------------------------------------------------------------------------
    // Seq
    // -----------------------------------------------------------------------------------------------------------------

    /** Applies an effect-producing function to each element of a `Seq`.
      *
      * @param source
      *   The input `Seq`
      * @param f
      *   The effect-producing function to apply to each element
      * @return
      *   A new effect that produces a Seq of results
      */
    inline def foreach[A, B, S](source: Seq[A])(f: A => B < S)(using Frame): Seq[B] < S =
        foreach(Chunk.from(source))(f)
    end foreach

    /** Applies an effect-producing function to each element of a `Seq`, and concatenates the resulting collections.
      *
      * @param source
      *   The input `Seq`
      * @param f
      *   The effect-producing function that returns a collection of results per element
      * @return
      *   A new effect that produces a flattened Seq of all results
      */
    inline def foreachConcat[A, B, S](source: Seq[A])(f: A => IterableOnce[B] < S)(using
        Frame
    ): Seq[B] < S =
        foreachConcat(Chunk.from(source))(f)
    end foreachConcat

    /** Applies an effect-producing function to each element of a sequence along with its index.
      *
      * @param source
      *   The input `Seq`
      * @param f
      *   The effect-producing function to apply to each element and its index
      * @return
      *   A new effect that produces a Seq of results
      */
    inline def foreachIndexed[A, B, S](source: Seq[A])(f: (Int, A) => B < S)(using Frame): Seq[B] < S =
        foreachIndexed(Chunk.from(source))(f)
    end foreachIndexed

    /** Applies an effect-producing function to each element of a `Seq`, discarding the results.
      *
      * @param source
      *   The input `Seq`
      * @param f
      *   The effect-producing function to apply to each element
      * @return
      *   A new effect that produces Unit
      */
    inline def foreachDiscard[A, B, S](source: Seq[A])(f: A => Any < S)(using Frame): Unit < S =
        foreachDiscard(Chunk.from(source))(f)
    end foreachDiscard

    /** Applies an effect-producing function to each element of a `Seq` along with its index, discarding the results.
      *
      * @param source
      *   The input `Seq`
      * @param f
      *   The effect-producing function to apply to each element and its index
      * @return
      *   A new effect that produces Unit
      */
    inline def foreachIndexedDiscard[A, B, S](source: Seq[A])(f: (Int, A) => Any < S)(using Frame): Unit < S =
        foreachIndexedDiscard(Chunk.from(source))(f)
    end foreachIndexedDiscard

    /** Filters elements of a `Seq` based on an effect-producing predicate.
      *
      * @param source
      *   The input `Seq`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Vector of filtered elements
      */
    inline def filter[A, S](source: Seq[A])(f: A => Boolean < S)(using Frame): Seq[A] < S =
        filter(Chunk.from(source))(f)
    end filter

    /** Folds over a `Seq` with an effect-producing function.
      *
      * @param source
      *   The input `Seq`
      * @param acc
      *   The initial accumulator value
      * @param f
      *   The effect-producing folding function
      * @return
      *   A new effect that produces the final accumulated value
      */
    inline def foldLeft[A, B, S](source: Seq[A])(acc: B)(f: (B, A) => B < S)(using Frame): B < S =
        foldLeft(Chunk.from(source))(acc)(f)
    end foldLeft

    /** Collects and transforms elements from a `Seq` using an effect-producing function that returns Maybe values.
      *
      * This method applies the given function to each element in the `Seq` and collects only the Present values into a Seq. It's similar to
      * a combination of flatMap and filter, where elements are both transformed and filtered in a single pass.
      *
      * @param source
      *   The input `Seq`
      * @param f
      *   The effect-producing function that returns Maybe values
      * @return
      *   A new effect that produces a Vector containing only the Present values after transformation
      */
    inline def collect[A, B, S](source: Seq[A])(f: A => Maybe[B] < S)(using Frame): Seq[B] < S =
        collect(Chunk.from(source))(f)
    end collect

    /** Collects the results of a `Seq` of effects into a single effect.
      *
      * @param source
      *   The `Seq` of effects
      * @return
      *   A new effect that produces a Seq of results
      */
    inline def collectAll[A, S](source: Seq[A < S])(using Frame): Seq[A] < S =
        collectAll(Chunk.from(source))
    end collectAll

    /** Collects the results of a `Seq` of effects, discarding the results.
      *
      * @param source
      *   The `Seq` of effects
      * @return
      *   A new effect that produces Unit
      */
    inline def collectAllDiscard[A, S](source: Seq[A < S])(using Frame): Unit < S =
        collectAllDiscard(Chunk.from(source))
    end collectAllDiscard

    /** Finds the first element in a `Seq` that satisfies a predicate.
      *
      * @param source
      *   The input `Seq`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces Maybe of the first matching element
      */
    inline def findFirst[A, B, S](source: Seq[A])(f: A => Maybe[B] < S)(using Frame): Maybe[B] < S =
        findFirst(Chunk.from(source))(f)
    end findFirst

    /** Takes elements from a `Seq` while a predicate holds true.
      *
      * @param source
      *   The input `Seq`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Vector of taken elements
      */
    inline def takeWhile[A, S](source: Seq[A])(f: A => Boolean < S)(using Frame): Seq[A] < S =
        takeWhile(Chunk.from(source))(f)
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
    inline def span[A, S](source: Seq[A])(f: A => Boolean < S)(using Frame): (Seq[A], Seq[A]) < S =
        span(Chunk.from(source))(f)
    end span

    /** Drops elements from a `Seq` while a predicate holds true.
      *
      * @param source
      *   The input `Seq`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Seq of remaining elements
      */
    inline def dropWhile[A, S](source: Seq[A])(f: A => Boolean < S)(using Frame): Seq[A] < S =
        dropWhile(Chunk.from(source))(f)
    end dropWhile

    /** Splits the collection into two vectors, depending on the result of the predicate.
      *
      * @param source
      *   The input `Seq`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A tuple `(lefts, rights)` where:
      *   - `lefts`: All elements that satisfy the predicate
      *   - `rights`: All elements that do not satisfy the predicate
      */
    inline def partition[S, A](source: Seq[A])(f: A => Boolean < S)(using
        Frame
    ): (Seq[A], Seq[A]) < S =
        partition(Chunk.from(source))(f)
    end partition

    /** Splits the collection into two vectors, depending on the result of the effect-producing function.
      *
      * @param source
      *   The input `Seq`
      * @param f
      *   The effect-producing function that returns an Either
      * @return
      *   A tuple `(lefts, rights)` where:
      *   - `lefts`: All elements that are Left
      *   - `rights`: All elements that are Right
      */
    inline def partitionMap[S, A, A1, A2](source: Seq[A])(f: A => Either[A1, A2] < S)(using
        Frame
    ): (Seq[A1], Seq[A2]) < S =
        partitionMap(Chunk.from(source))(f)
    end partitionMap

    /** Computes a prefix scan of the collection.
      *
      * @param z
      *   Initial accumulator value
      * @param op
      *   Effectful operation that combines accumulator with each element
      * @return
      *   Seq containing all intermediate accumulator states
      */
    inline def scanLeft[S, A, B](source: Seq[A])(z: B)(op: (B, A) => B < S)(using
        Frame
    ): Seq[B] < S =
        scanLeft(Chunk.from(source))(z)(op)
    end scanLeft

    /** Groups elements of the collection by the result of the function.
      *
      * @param source
      *   The input `Seq`
      * @param f
      *   The effect-producing function that returns the key for each element
      * @return
      *   A Map where keys are the results of the function and values are vectors of elements
      */
    inline def groupBy[S, A, K](source: Seq[A])(f: A => K < S)(using
        Frame
    ): Map[K, Seq[A]] < S =
        groupBy(Chunk.from(source))(f)
    end groupBy

    /** Groups elements of the collection by the result of the function and applies a transformation to each element.
      *
      * @param source
      *   The input `Seq`
      * @param key
      *   The effect-producing function that returns the key for each element
      * @param f
      *   The effect-producing function that returns the value for each element
      * @return
      *   A Map where keys are the results of the function and values are vectors of transformed elements
      */
    inline def groupMap[S, A, K, B](source: Seq[A])(key: A => K < S)(f: A => B < S)(using
        Frame
    ): Map[K, Seq[B]] < S =
        groupMap(Chunk.from(source))(key)(f)
    end groupMap

    /** Processes elements of a `Seq` while a predicate holds true, maintaining an accumulator state. This function implements a stateful
      * iteration pattern where:
      *   1. The predicate function `f` determines whether to continue processing
      *   2. The accumulator function `acc` updates state based on the predicate result
      *   3. The epilog function transforms the final accumulator state into the result
      *
      * @param source
      *   The input `Seq` to process
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
    def foreach[A, B, S](source: Chunk[A])(f: A => B < S)(using Frame): Chunk[B] < S =
        val chunk = source.toIndexed
        val len   = chunk.length
        len match
            case 0 => Chunk.empty
            case 1 => f(chunk.head).map(Chunk.Indexed.single(_))
            case _ =>
                Loop.indexed(Chunk.empty[B]): (index, acc) =>
                    if index == len then
                        Loop.done(acc.toIndexed)
                    else
                        f(chunk(index)).map: u =>
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
    def foreachConcat[A, B, S](source: Chunk[A])(f: A => IterableOnce[B] < S)(using
        Frame
    ): Chunk[B] < S =
        val chunk = source.toIndexed
        val len   = chunk.length
        len match
            case 0 => Chunk.empty
            case _ =>
                Loop.indexed(Chunk.empty[Chunk[B]]): (idx, acc) =>
                    if idx == len then Loop.done(acc.flattenChunk)
                    else f(chunk(idx)).map(iterOnce => Loop.continue(acc.appended(Chunk.from(iterOnce))))
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
    def foreachIndexed[A, B, S](source: Chunk[A])(f: (Int, A) => B < S)(using Frame): Chunk[B] < S =
        val chunk = source.toIndexed
        val len   = chunk.length
        len match
            case 0 => Chunk.empty
            case 1 => f(0, chunk.head).map(Chunk.Indexed.single(_))
            case _ =>
                Loop.indexed(Chunk.empty[B]): (index, acc) =>
                    if index == len then
                        Loop.done(acc.toIndexed)
                    else
                        f(index, chunk(index)).map: u =>
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
    def foreachDiscard[A, B, S](source: Chunk[A])(f: A => Any < S)(using Frame): Unit < S =
        val chunk = source.toIndexed
        val len   = chunk.length
        len match
            case 0 => ()
            case 1 => f(chunk.head).unit
            case _ =>
                Loop.indexed: index =>
                    if index == len then Loop.done
                    else f(chunk(index)).andThen(Loop.continue)
        end match
    end foreachDiscard

    /** Applies an effect-producing function to each element of a `Chunk` along with its index, discarding the results.
      *
      * @param source
      *   The input `Chunk`
      * @param f
      *   The effect-producing function to apply to each element and its index
      * @return
      *   A new effect that produces Unit
      */
    def foreachIndexedDiscard[A, B, S](source: Chunk[A])(f: (Int, A) => Any < S)(using Frame): Unit < S =
        val chunk = source.toIndexed
        val len   = chunk.length
        len match
            case 0 => ()
            case 1 => f(0, chunk.head).unit
            case _ =>
                Loop.indexed: index =>
                    if index == len then Loop.done
                    else f(index, chunk(index)).andThen(Loop.continue)
        end match
    end foreachIndexedDiscard

    /** Filters elements of a `Chunk` based on an effect-producing predicate.
      *
      * @param source
      *   The input `Chunk`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Chunk of filtered elements
      */
    def filter[A, S](source: Chunk[A])(f: A => Boolean < S)(using Frame): Chunk[A] < S =
        val chunk = source.toIndexed
        val len   = chunk.length
        len match
            case 0 => Chunk.empty
            case 1 =>
                f(chunk.head).map:
                    case true  => Chunk.Indexed.single(chunk.head)
                    case false => Chunk.empty
            case _ =>
                Loop.indexed(Chunk.empty[A]): (idx, acc) =>
                    if idx == len then Loop.done(acc.toIndexed)
                    else
                        val current = chunk(idx)
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
    def foldLeft[A, B, S](source: Chunk[A])(acc: B)(f: (B, A) => B < S)(using Frame): B < S =
        val chunk = source.toIndexed
        val len   = chunk.length
        len match
            case 0 => acc
            case 1 => f(acc, chunk.head)
            case _ =>
                Loop.indexed(acc): (idx, acc) =>
                    if idx == len then Loop.done(acc)
                    else f(acc, chunk(idx)).map(Loop.continue(_))
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
    def collect[A, B, S](source: Chunk[A])(f: A => Maybe[B] < S)(using Frame): Chunk[B] < S =
        val chunk = source.toIndexed
        val len   = chunk.length
        len match
            case 0 => Chunk.empty
            case 1 =>
                f(chunk.head).map:
                    case Absent     => Chunk.empty
                    case Present(v) => Chunk.Indexed.single(v)
            case _ =>
                Loop.indexed(Chunk.empty[B]): (idx, acc) =>
                    if idx == len then Loop.done(acc.toIndexed)
                    else
                        val current = chunk(idx)
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
    def collectAll[A, S](source: Chunk[A < S])(using Frame): Chunk[A] < S =
        val chunk = source.toIndexed
        val len   = chunk.length
        len match
            case 0 => Chunk.empty
            case 1 => chunk.head.map(Chunk.Indexed.single(_))
            case _ =>
                Loop.indexed(Chunk.empty[A]): (idx, acc) =>
                    if idx == len then Loop.done(acc.toIndexed)
                    else
                        chunk(idx).map: v =>
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
    def collectAllDiscard[A, S](source: Chunk[A < S])(using Frame): Unit < S =
        val chunk = source.toIndexed
        val len   = chunk.length
        len match
            case 0 => ()
            case 1 => chunk.head.unit
            case _ =>
                Loop.indexed: idx =>
                    if idx == len then Loop.done
                    else chunk(idx).andThen(Loop.continue)
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
    def findFirst[A, B, S](source: Chunk[A])(f: A => Maybe[B] < S)(using Frame): Maybe[B] < S =
        val chunk = source.toIndexed
        val len   = chunk.length
        len match
            case 0 => Absent
            case 1 => f(chunk.head)
            case _ =>
                Loop.indexed: idx =>
                    if idx == len then Loop.done(Absent)
                    else
                        f(chunk(idx)).map:
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
    def takeWhile[A, S](source: Chunk[A])(f: A => Boolean < S)(using Frame): Chunk[A] < S =
        val chunk = source.toIndexed
        val len   = chunk.length
        len match
            case 0 => Chunk.empty
            case 1 =>
                f(chunk.head).map:
                    case true  => Chunk.Indexed.single(chunk.head)
                    case false => Chunk.empty[A]
            case _ =>
                Loop.indexed(Chunk.empty[A]): (idx, acc) =>
                    if idx == len then Loop.done(acc.toIndexed)
                    else
                        val current = chunk(idx)
                        f(current).map:
                            case true  => Loop.continue(acc.appended(current))
                            case false => Loop.done(acc.toIndexed)
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
    def span[A, S](source: Chunk[A])(f: A => Boolean < S)(using Frame): (Chunk[A], Chunk[A]) < S =
        val chunk = source.toIndexed
        val len   = chunk.length
        len match
            case 0 => (Chunk.empty, Chunk.empty)
            case 1 =>
                f(chunk.head).map:
                    case true  => (Chunk.Indexed.single(chunk.head), Chunk.empty)
                    case false => (Chunk.empty, Chunk.Indexed.single(chunk.head))
            case _ =>
                Loop.indexed(Chunk.empty[A]): (idx, acc) =>
                    if idx == len then Loop.done((acc.toIndexed, Chunk.empty))
                    else
                        val current = chunk(idx)
                        f(current).map:
                            case true  => Loop.continue(acc.appended(current))
                            case false => Loop.done((acc.toIndexed, chunk.drop(idx).toIndexed))
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
    def dropWhile[A, S](source: Chunk[A])(f: A => Boolean < S)(using Frame): Chunk[A] < S =
        val chunk = source.toIndexed
        val len   = chunk.length
        len match
            case 0 => Chunk.empty
            case 1 =>
                f(chunk.head).map:
                    case true  => Chunk.empty
                    case false => Chunk.Indexed.single(chunk.head)
            case _ =>
                Loop.indexed: idx =>
                    if idx == len then Loop.done(Chunk.empty)
                    else
                        f(chunk(idx)).map:
                            case true  => Loop.continue
                            case false => Loop.done(chunk.drop(idx).toIndexed)
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
    def partition[S, A](source: Chunk[A])(f: A => Boolean < S)(using
        Frame
    ): (Chunk[A], Chunk[A]) < S =
        val chunk = source.toIndexed
        val len   = chunk.length
        len match
            case 0 => (Chunk.empty, Chunk.empty)
            case 1 =>
                f(chunk.head).map:
                    case true  => (Chunk.Indexed.single(chunk.head), Chunk.empty)
                    case false => (Chunk.empty, Chunk.Indexed.single(chunk.head))
            case _ =>
                Loop.indexed(Chunk.empty[A], Chunk.empty[A]): (idx, trues, falses) =>
                    if idx == len then Loop.done((trues.toIndexed, falses.toIndexed))
                    else
                        val current = chunk(idx)
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
    def partitionMap[S, A, A1, A2](source: Chunk[A])(f: A => Either[A1, A2] < S)(using
        Frame
    ): (Chunk[A1], Chunk[A2]) < S =
        val chunk = source.toIndexed
        val len   = chunk.length
        len match
            case 0 => (Chunk.empty, Chunk.empty)
            case 1 =>
                f(chunk.head).map:
                    case Left(a1)  => (Chunk.Indexed.single(a1), Chunk.empty)
                    case Right(a2) => (Chunk.empty, Chunk.Indexed.single(a2))
            case _ =>
                Loop.indexed(Chunk.empty[A1], Chunk.empty[A2]): (idx, lefts, rights) =>
                    if idx == len then Loop.done((lefts.toIndexed, rights.toIndexed))
                    else
                        val current = chunk(idx)
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
    def scanLeft[S, A, B](source: Chunk[A])(z: B)(op: (B, A) => B < S)(using
        Frame
    ): Chunk[B] < S =
        val chunk = source.toIndexed
        val len   = chunk.length
        len match
            case 0 => Chunk.Indexed.single(z)
            case _ =>
                Loop.indexed[Chunk[B], B, Chunk[B], S](Chunk.Indexed.single(z), z): (idx, acc, current) =>
                    if idx == len then Loop.done(acc.toIndexed)
                    else
                        op(current, chunk(idx)).map: next =>
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
    def groupBy[S, A, K](source: Chunk[A])(f: A => K < S)(using
        Frame
    ): Map[K, Chunk[A]] < S =
        val chunk = source.toIndexed
        val len   = chunk.length
        len match
            case 0 => Map.empty[K, Chunk[A]]
            case 1 => f(chunk.head).map(k => Map(k -> Chunk.Indexed.single(chunk.head)))
            case _ =>
                Loop.indexed(Map.empty[K, Chunk[A]]): (idx, acc) =>
                    if idx == len then Loop.done(acc.view.mapValues(_.toIndexed).toMap)
                    else
                        val current = chunk(idx)
                        f(current).map: k =>
                            Loop.continue(
                                acc.updatedWith(k) {
                                    case Some(current) => Some(current.appended(chunk(idx)))
                                    case None          => Some(Chunk.Indexed.single(chunk(idx)))
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
    def groupMap[S, A, K, B](source: Chunk[A])(key: A => K < S)(f: A => B < S)(using
        Frame
    ): Map[K, Chunk[B]] < S =
        val chunk = source.toIndexed
        val len   = chunk.length
        len match
            case 0 => Map.empty[K, Chunk[B]]
            case 1 =>
                for
                    k <- key(chunk.head)
                    b <- f(chunk.head)
                yield Map(k -> Chunk.Indexed.single(b))
            case _ =>
                Loop.indexed(Map.empty[K, Chunk[B]]): (idx, acc) =>
                    if idx == len then Loop.done(acc.view.mapValues(_.toIndexed).toMap)
                    else
                        for
                            k <- key(chunk(idx))
                            b <- f(chunk(idx))
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
    def foreach[A, B, S](source: Set[A])(f: A => B < S)(using Frame): Set[B] < S =
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
    def foreachConcat[A, B, S](source: Set[A])(f: A => IterableOnce[B] < S)(using
        Frame
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
    def foreachIndexed[A, B, S](source: Set[A])(f: (Int, A) => B < S)(using Frame): Set[B] < S =
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
    def foreachDiscard[A, B, S](source: Set[A])(f: A => Any < S)(using Frame): Unit < S =
        if source.isEmpty then ()
        else
            Loop(source): curSet =>
                if curSet.isEmpty then Loop.done
                else
                    val current = curSet.head
                    f(current).andThen(Loop.continue(curSet - current))
    end foreachDiscard

    /** Applies an effect-producing function to each element of a `Set` along with its index, discarding the results.
      *
      * @param source
      *   The input `Set`
      * @param f
      *   The effect-producing function to apply to each element and its index
      * @return
      *   A new effect that produces Unit
      */
    def foreachIndexedDiscard[A, B, S](source: Set[A])(f: (Int, A) => Any < S)(using Frame): Unit < S =
        if source.isEmpty then ()
        else
            Loop.indexed(source): (idx, curSet) =>
                if curSet.isEmpty then Loop.done
                else
                    val current = curSet.head
                    f(idx, current).andThen(Loop.continue(curSet - current))
    end foreachIndexedDiscard

    /** Filters elements of a `Set` based on an effect-producing predicate.
      *
      * @param source
      *   The input `Set`
      * @param f
      *   The effect-producing predicate function
      * @return
      *   A new effect that produces a Set of filtered elements
      */
    def filter[A, S](source: Set[A])(f: A => Boolean < S)(using Frame): Set[A] < S =
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
    def foldLeft[A, B, S](source: Set[A])(acc: B)(f: (B, A) => B < S)(using Frame): B < S =
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
    def collect[A, B, S](source: Set[A])(f: A => Maybe[B] < S)(using Frame): Set[B] < S =
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
    def collectAll[A, S](source: Set[A < S])(using Frame): Set[A] < S =
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
    def collectAllDiscard[A, S](source: Set[A < S])(using Frame): Unit < S =
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
    def findFirst[A, B, S](source: Set[A])(f: A => Maybe[B] < S)(using Frame): Maybe[B] < S =
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
    def takeWhile[A, S](source: Set[A])(f: A => Boolean < S)(using Frame): Set[A] < S =
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
    def span[A, S](source: Set[A])(f: A => Boolean < S)(using Frame): (Set[A], Set[A]) < S =
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
    def dropWhile[A, S](source: Set[A])(f: A => Boolean < S)(using Frame): Set[A] < S =
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
    def partition[S, A](source: Set[A])(f: A => Boolean < S)(using
        Frame
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
    def partitionMap[S, A, A1, A2](source: Set[A])(f: A => Either[A1, A2] < S)(using
        Frame
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
    def scanLeft[S, A, B](source: Set[A])(z: B)(op: (B, A) => B < S)(using
        Frame
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
    def groupBy[S, A, K](source: Set[A])(f: A => K < S)(using
        Frame
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
    def groupMap[S, A, K, B](source: Set[A])(key: A => K < S)(f: A => B < S)(using
        Frame
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
    def foreach[K1, V1, K2, V2, S](source: Map[K1, V1])(f: ((K1, V1)) => (K2, V2) < S)(using
        Frame
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
    def foreach[K1, V1, B, S](source: Map[K1, V1])(f: ((K1, V1)) => B < S)(using
        Frame
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
    def foreachConcat[K1, V1, K2, V2, S](source: Map[K1, V1])(f: ((K1, V1)) => IterableOnce[(K2, V2)] < S)(using
        Frame
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
    def foreachConcat[K1, V1, B, S](source: Map[K1, V1])(f: ((K1, V1)) => IterableOnce[B] < S)(using
        Frame
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
    def foreachDiscard[K1, V1, S](source: Map[K1, V1])(f: ((K1, V1)) => Any < S)(using Frame): Unit < S =
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
    def filter[K1, V1, S](source: Map[K1, V1])(f: ((K1, V1)) => Boolean < S)(using Frame): Map[K1, V1] < S =
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
    def filterKeys[K1, V1, S](source: Map[K1, V1])(f: K1 => Boolean < S)(using Frame): Map[K1, V1] < S =
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
    def foldLeft[K1, V1, B, S](source: Map[K1, V1])(acc: B)(f: (B, (K1, V1)) => B < S)(using Frame): B < S =
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
    def collect[K1, V1, K2, V2, S](source: Map[K1, V1])(f: ((K1, V1)) => Maybe[(K2, V2)] < S)(using
        Frame
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
    def collect[K1, V1, B, S](source: Map[K1, V1])(f: ((K1, V1)) => Maybe[B] < S)(using
        Frame
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
    def collectAll[K1, V1, S](source: Map[K1, V1 < S])(using Frame): Map[K1, V1] < S =
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
    def collectAllDiscard[K1, V1, S](source: Map[K1, V1 < S])(using Frame): Unit < S =
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
    def findFirst[K1, V1, B, S](source: Map[K1, V1])(f: ((K1, V1)) => Maybe[B] < S)(using Frame): Maybe[B] < S =
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
    def takeWhile[K1, V1, S](source: Map[K1, V1])(f: ((K1, V1)) => Boolean < S)(using Frame): Map[K1, V1] < S =
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
    def span[K1, V1, S](source: Map[K1, V1])(f: ((K1, V1)) => Boolean < S)(using
        Frame
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
    def dropWhile[K1, V1, S](source: Map[K1, V1])(f: ((K1, V1)) => Boolean < S)(using Frame): Map[K1, V1] < S =
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
    def partition[K1, V1, S](source: Map[K1, V1])(f: ((K1, V1)) => Boolean < S)(using
        Frame
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
    def partitionMap[K1, V1, K2, V2, K3, V3, S](source: Map[K1, V1])(f: ((K1, V1)) => Either[(K2, V2), (K3, V3)] < S)(using
        Frame
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
    def scanLeft[K1, V1, B, S](source: Map[K1, V1])(z: B)(op: (B, (K1, V1)) => B < S)(using
        Frame
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
    def groupBy[K1, V1, K2, S](source: Map[K1, V1])(f: ((K1, V1)) => K2 < S)(using
        Frame
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
    def groupMap[K1, V1, K2, V2, S](source: Map[K1, V1])(key: ((K1, V1)) => K2 < S)(f: ((K1, V1)) => V2 < S)(
        using Frame
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

    // -----------------------------------------------------------------------------------------------------------------
    // Maybe
    // -----------------------------------------------------------------------------------------------------------------

    /** Applies an effect-producing function to the value of a `Maybe`, when present. */
    def foreach[A, B, S](source: Maybe[A])(f: A => B < S)(using Frame): Maybe[B] < S =
        source match
            case Present(a) => f(a).map(Maybe(_))
            case Absent     => Maybe.empty

    /** Applies an effect-producing function returning a `Maybe` to the value of a `Maybe`, when present, and flattens the result. */
    def foreachConcat[A, B, S](source: Maybe[A])(f: A => Maybe[B] < S)(using Frame): Maybe[B] < S =
        source match
            case Present(a) => f(a)
            case Absent     => Maybe.empty

    /** Applies an effect-producing function to the value of a `Maybe`, when present, for its effect. */
    def foreachDiscard[A, S](source: Maybe[A])(f: A => Any < S)(using Frame): Unit < S =
        source match
            case Present(a) => f(a).unit
            case Absent     => ()

    /** Keeps the value of a `Maybe` when an effect-producing predicate holds for it. */
    def filter[A, S](source: Maybe[A])(f: A => Boolean < S)(using Frame): Maybe[A] < S =
        source match
            case Present(a) => f(a).map(accepted => if accepted then source else Maybe.empty)
            case Absent     => Maybe.empty

    /** Keeps the value of a `Maybe` when an effect-producing predicate does not hold for it. */
    def filterNot[A, S](source: Maybe[A])(f: A => Boolean < S)(using Frame): Maybe[A] < S =
        Kyo.filter(source)(a => f(a).map(!_))

    /** Tests whether an effect-producing predicate holds for the value of a `Maybe`; false when absent. */
    def exists[A, S](source: Maybe[A])(f: A => Boolean < S)(using Frame): Boolean < S =
        source match
            case Present(a) => f(a)
            case Absent     => false

    /** Tests whether an effect-producing predicate holds for the value of a `Maybe`; true when absent. */
    def forall[A, S](source: Maybe[A])(f: A => Boolean < S)(using Frame): Boolean < S =
        source match
            case Present(a) => f(a)
            case Absent     => true

    /** Applies an effect-producing partial function, given as a function to `Maybe`, to the value of a `Maybe`. */
    def collect[A, B, S](source: Maybe[A])(f: A => Maybe[B] < S)(using Frame): Maybe[B] < S =
        Kyo.foreachConcat(source)(f)

    /** Folds a `Maybe` with an effect-producing alternative for the absent case and an effect-producing function for the present one. */
    def fold[A, B, S](source: Maybe[A])(ifEmpty: => B < S)(f: A => B < S)(using Frame): B < S =
        source match
            case Present(a) => f(a)
            case Absent     => ifEmpty

    /** Produces a `Maybe`, or an effect-producing alternative when it is absent. */
    def orElse[A, B >: A, S](source: Maybe[A])(alternative: => Maybe[B] < S)(using Frame): Maybe[B] < S =
        source match
            case Present(_) => source
            case Absent     => alternative

    /** Produces the value of a `Maybe`, or an effect-producing default when it is absent. */
    def getOrElse[A, B >: A, S](source: Maybe[A])(default: => B < S)(using Frame): B < S =
        source match
            case Present(a) => a
            case Absent     => default

    // -----------------------------------------------------------------------------------------------------------------
    // Result: an error, Panic included, passes through unchanged. The target names keep these apart from the Maybe
    // overloads, which erase the same way.
    // -----------------------------------------------------------------------------------------------------------------

    /** Applies an effect-producing function to the success value of a `Result`. */
    @targetName("resultForeach")
    def foreach[E, A, B, S](source: Result[E, A])(f: A => B < S)(using Frame): Result[E, B] < S =
        source match
            case Result.Success(a)                 => f(a).map(Result.succeed)
            case error: Result.Error[E] @unchecked => error

    /** Applies an effect-producing function returning a `Result` to the success value of a `Result`, and flattens the result. */
    @targetName("resultForeachConcat")
    def foreachConcat[E, A, E2, B, S](source: Result[E, A])(f: A => Result[E2, B] < S)(using Frame): Result[E | E2, B] < S =
        source match
            case Result.Success(a)                 => f(a)
            case error: Result.Error[E] @unchecked => error

    /** Applies an effect-producing function to the success value of a `Result` for its effect. */
    @targetName("resultForeachDiscard")
    def foreachDiscard[E, A, S](source: Result[E, A])(f: A => Any < S)(using Frame): Unit < S =
        source match
            case Result.Success(a) => f(a).unit
            case _                 => ()

    /** Keeps the success value of a `Result` when an effect-producing predicate holds for it, failing otherwise. */
    @targetName("resultFilter")
    def filter[E, A, S](source: Result[E, A])(f: A => Boolean < S)(using Frame): Result[E | NoSuchElementException, A] < S =
        source match
            case Result.Success(a) =>
                f(a).map(accepted =>
                    if accepted then source else Result.fail(new NoSuchElementException("Predicate does not hold for " + a))
                )
            case error: Result.Error[E] @unchecked => error

    /** Tests whether an effect-producing predicate holds for the success value of a `Result`; false for an error. */
    @targetName("resultExists")
    def exists[E, A, S](source: Result[E, A])(f: A => Boolean < S)(using Frame): Boolean < S =
        source match
            case Result.Success(a) => f(a)
            case _                 => false

    /** Tests whether an effect-producing predicate holds for the success value of a `Result`; true for an error. */
    @targetName("resultForall")
    def forall[E, A, S](source: Result[E, A])(f: A => Boolean < S)(using Frame): Boolean < S =
        source match
            case Result.Success(a) => f(a)
            case _                 => true

    /** Folds a `Result` with an effect-producing function per case. */
    @targetName("resultFold")
    def fold[E, A, B, S](source: Result[E, A])(onSuccess: A => B < S, onFailure: E => B < S, onPanic: Throwable => B < S)(using
        Frame
    ): B < S =
        source match
            case Result.Success(a)     => onSuccess(a)
            case Result.Failure(error) => onFailure(error)
            case Result.Panic(error)   => onPanic(error)

    /** Produces a `Result`, or an effect-producing alternative when it is an error. */
    @targetName("resultOrElse")
    def orElse[E, A, E2, B >: A, S](source: Result[E, A])(alternative: => Result[E2, B] < S)(using Frame): Result[E | E2, B] < S =
        source match
            case Result.Success(_) => source
            case _                 => alternative

    /** Produces the success value of a `Result`, or an effect-producing default when it is an error. */
    @targetName("resultGetOrElse")
    def getOrElse[E, A, B >: A, S](source: Result[E, A])(default: => B < S)(using Frame): B < S =
        source match
            case Result.Success(a) => a
            case _                 => default

    // -----------------------------------------------------------------------------------------------------------------
    // Option
    // -----------------------------------------------------------------------------------------------------------------

    /** Applies an effect-producing function to the value of an `Option`, when defined. */
    def foreach[A, B, S](source: Option[A])(f: A => B < S)(using Frame): Option[B] < S =
        source match
            case Some(a) => f(a).map(Some(_))
            case None    => None

    /** Applies an effect-producing function returning an `Option` to the value of an `Option`, when defined, and flattens the result. */
    def foreachConcat[A, B, S](source: Option[A])(f: A => Option[B] < S)(using Frame): Option[B] < S =
        source match
            case Some(a) => f(a)
            case None    => None

    /** Applies an effect-producing function to the value of an `Option`, when defined, for its effect. */
    def foreachDiscard[A, B, S](source: Option[A])(f: A => B < S)(using Frame): Unit < S =
        source match
            case Some(a) => f(a).unit
            case None    => ()

    /** Keeps the value of an `Option` when an effect-producing predicate holds for it. */
    def filter[A, S](source: Option[A])(f: A => Boolean < S)(using Frame): Option[A] < S =
        source match
            case Some(a) => f(a).map(accepted => if accepted then source else None)
            case None    => None

    /** Keeps the value of an `Option` when an effect-producing predicate does not hold for it. */
    def filterNot[A, S](source: Option[A])(f: A => Boolean < S)(using Frame): Option[A] < S =
        Kyo.filter(source)(a => f(a).map(!_))

    /** Tests whether an effect-producing predicate holds for the value of an `Option`; false when empty. */
    def exists[A, S](source: Option[A])(f: A => Boolean < S)(using Frame): Boolean < S =
        source match
            case Some(a) => f(a)
            case None    => false

    /** Tests whether an effect-producing predicate holds for the value of an `Option`; true when empty. */
    def forall[A, S](source: Option[A])(f: A => Boolean < S)(using Frame): Boolean < S =
        source match
            case Some(a) => f(a)
            case None    => true

    /** Applies an effect-producing partial function, given as a function to `Maybe`, to the value of an `Option`. */
    def collect[A, B, S](source: Option[A])(f: A => Maybe[B] < S)(using Frame): Option[B] < S =
        source match
            case Some(a) => f(a).map(_.toOption)
            case None    => None

    /** Folds an `Option` with an effect-producing alternative for the empty case and an effect-producing function for the defined one. */
    def fold[A, B, S](source: Option[A])(ifEmpty: => B < S)(f: A => B < S)(using Frame): B < S =
        source match
            case Some(a) => f(a)
            case None    => ifEmpty

    /** Produces an `Option`, or an effect-producing alternative when it is empty. */
    def orElse[A, B >: A, S](source: Option[A])(alternative: => Option[B] < S)(using Frame): Option[B] < S =
        source match
            case Some(_) => source
            case None    => alternative

    /** Produces the value of an `Option`, or an effect-producing default when it is empty. */
    def getOrElse[A, B >: A, S](source: Option[A])(default: => B < S)(using Frame): B < S =
        source match
            case Some(a) => a
            case None    => default

    // -----------------------------------------------------------------------------------------------------------------
    // Try: a failure passes through unchanged
    // -----------------------------------------------------------------------------------------------------------------

    /** Applies an effect-producing function to the value of a successful `Try`. */
    def foreach[A, B, S](source: Try[A])(f: A => B < S)(using Frame): Try[B] < S =
        source match
            case Success(a)          => f(a).map(Success(_))
            case failure: Failure[?] => failure.asInstanceOf[Try[B]]

    /** Applies an effect-producing function returning a `Try` to the value of a successful `Try`, and flattens the result. */
    def foreachConcat[A, B, S](source: Try[A])(f: A => Try[B] < S)(using Frame): Try[B] < S =
        source match
            case Success(a)          => f(a)
            case failure: Failure[?] => failure.asInstanceOf[Try[B]]

    /** Applies an effect-producing function to the value of a successful `Try` for its effect. */
    def foreachDiscard[A, B, S](source: Try[A])(f: A => B < S)(using Frame): Unit < S =
        source match
            case Success(a) => f(a).unit
            case _          => ()

    /** Keeps the value of a successful `Try` when an effect-producing predicate holds for it, failing otherwise. */
    def filter[A, S](source: Try[A])(f: A => Boolean < S)(using Frame): Try[A] < S =
        source match
            case Success(a) =>
                f(a).map(accepted => if accepted then source else Failure(new NoSuchElementException("Predicate does not hold for " + a)))
            case _ => source

    /** Applies an effect-producing partial function, given as a function to `Maybe`, to the value of a successful `Try`, failing when
      * it does not apply.
      */
    def collect[A, B, S](source: Try[A])(f: A => Maybe[B] < S)(using Frame): Try[B] < S =
        source match
            case Success(a) =>
                f(a).map(_.fold(Failure(new NoSuchElementException("Predicate does not hold for " + a)))(Success(_)))
            case failure: Failure[?] => failure.asInstanceOf[Try[B]]

    /** Folds a `Try` with an effect-producing function per case. */
    def fold[A, B, S](source: Try[A])(onFailure: Throwable => B < S, onSuccess: A => B < S)(using Frame): B < S =
        source match
            case Success(a)     => onSuccess(a)
            case Failure(error) => onFailure(error)

    /** Produces a `Try`, or an effect-producing alternative when it is a failure. */
    def orElse[A, B >: A, S](source: Try[A])(alternative: => Try[B] < S)(using Frame): Try[B] < S =
        source match
            case Success(_) => source
            case _          => alternative

    /** Produces the value of a successful `Try`, or an effect-producing default when it is a failure. */
    def getOrElse[A, B >: A, S](source: Try[A])(default: => B < S)(using Frame): B < S =
        source match
            case Success(a) => a
            case _          => default

    /** Recovers a failed `Try` with an effect-producing partial function, given as a function to `Maybe`; a failure it does not apply
      * to passes through unchanged.
      */
    def recover[A, B >: A, S](source: Try[A])(f: Throwable => Maybe[B] < S)(using Frame): Try[B] < S =
        source match
            case Failure(error) => f(error).map(_.fold(source)(Success(_)))
            case _              => source

    /** Recovers a failed `Try` with an effect-producing partial function returning a `Try`, given as a function to `Maybe`; a failure
      * it does not apply to passes through unchanged.
      */
    def recoverWith[A, B >: A, S](source: Try[A])(f: Throwable => Maybe[Try[B]] < S)(using Frame): Try[B] < S =
        source match
            case Failure(error) => f(error).map(_.getOrElse(source))
            case _              => source

    // -----------------------------------------------------------------------------------------------------------------
    // Either: a Left passes through unchanged
    // -----------------------------------------------------------------------------------------------------------------

    /** Applies an effect-producing function to the `Right` value of an `Either`. */
    def foreach[A, B, B1, S](source: Either[A, B])(f: B => B1 < S)(using Frame): Either[A, B1] < S =
        source match
            case Right(b)         => f(b).map(Right(_))
            case left: Left[?, ?] => left.asInstanceOf[Either[A, B1]]

    /** Applies an effect-producing function returning an `Either` to the `Right` value of an `Either`, and flattens the result. */
    def foreachConcat[A, B, A1 >: A, B1, S](source: Either[A, B])(f: B => Either[A1, B1] < S)(using Frame): Either[A1, B1] < S =
        source match
            case Right(b)         => f(b)
            case left: Left[?, ?] => left.asInstanceOf[Either[A1, B1]]

    /** Applies an effect-producing function to the `Right` value of an `Either` for its effect. */
    def foreachDiscard[A, B, B1, S](source: Either[A, B])(f: B => B1 < S)(using Frame): Unit < S =
        source match
            case Right(b) => f(b).unit
            case _        => ()

    /** Tests whether an effect-producing predicate holds for the `Right` value of an `Either`; false for a `Left`. */
    def exists[A, B, S](source: Either[A, B])(f: B => Boolean < S)(using Frame): Boolean < S =
        source match
            case Right(b) => f(b)
            case _        => false

    /** Tests whether an effect-producing predicate holds for the `Right` value of an `Either`; true for a `Left`. */
    def forall[A, B, S](source: Either[A, B])(f: B => Boolean < S)(using Frame): Boolean < S =
        source match
            case Right(b) => f(b)
            case _        => true

    /** Folds an `Either` with an effect-producing function per side. */
    def fold[A, B, C, S](source: Either[A, B])(onLeft: A => C < S, onRight: B => C < S)(using Frame): C < S =
        source match
            case Right(b) => onRight(b)
            case Left(a)  => onLeft(a)

    /** Produces an `Either`, or an effect-producing alternative when it is a `Left`. */
    def orElse[A, B, A1 >: A, B1 >: B, S](source: Either[A, B])(alternative: => Either[A1, B1] < S)(using Frame): Either[A1, B1] < S =
        source match
            case Right(_) => source
            case _        => alternative

    /** Produces the `Right` value of an `Either`, or an effect-producing default when it is a `Left`. */
    def getOrElse[A, B, B1 >: B, S](source: Either[A, B])(default: => B1 < S)(using Frame): B1 < S =
        source match
            case Right(b) => b
            case _        => default

    /** Keeps the `Right` value of an `Either` when an effect-producing predicate holds for it, or produces an effect-producing `Left`
      * value otherwise.
      */
    def filterOrElse[A, B, A1 >: A, S](source: Either[A, B])(f: B => Boolean < S, zero: => A1 < S)(using Frame): Either[A1, B] < S =
        source match
            case Right(b) => f(b).map(accepted => if accepted then source else zero.map(Left(_)))
            case _        => source

    // -----------------------------------------------------------------------------------------------------------------
    // Either.LeftProjection: a Right passes through unchanged
    // -----------------------------------------------------------------------------------------------------------------

    /** Applies an effect-producing function to the `Left` value of a projected `Either`. */
    def foreach[A, B, A1, S](source: Either.LeftProjection[A, B])(f: A => A1 < S)(using Frame): Either[A1, B] < S =
        source.e match
            case Left(a)            => f(a).map(Left(_))
            case right: Right[?, ?] => right.asInstanceOf[Either[A1, B]]

    /** Applies an effect-producing function returning an `Either` to the `Left` value of a projected `Either`, and flattens the
      * result.
      */
    def foreachConcat[A, B, A1, B1 >: B, S](source: Either.LeftProjection[A, B])(f: A => Either[A1, B1] < S)(using
        Frame
    ): Either[A1, B1] < S =
        source.e match
            case Left(a)            => f(a)
            case right: Right[?, ?] => right.asInstanceOf[Either[A1, B1]]

    /** Applies an effect-producing function to the `Left` value of a projected `Either` for its effect. */
    def foreachDiscard[A, B, A1, S](source: Either.LeftProjection[A, B])(f: A => A1 < S)(using Frame): Unit < S =
        source.e match
            case Left(a) => f(a).unit
            case _       => ()

    /** Tests whether an effect-producing predicate holds for the `Left` value of a projected `Either`; false for a `Right`. */
    def exists[A, B, S](source: Either.LeftProjection[A, B])(f: A => Boolean < S)(using Frame): Boolean < S =
        source.e match
            case Left(a) => f(a)
            case _       => false

    /** Tests whether an effect-producing predicate holds for the `Left` value of a projected `Either`; true for a `Right`. */
    def forall[A, B, S](source: Either.LeftProjection[A, B])(f: A => Boolean < S)(using Frame): Boolean < S =
        source.e match
            case Left(a) => f(a)
            case _       => true

    /** Keeps a projected `Either` when an effect-producing predicate holds for its `Left` value; a `Right` is kept, a rejected `Left` is
      * dropped.
      */
    def filterToOption[A, B, B1, S](source: Either.LeftProjection[A, B])(f: A => Boolean < S)(using Frame): Option[Either[A, B1]] < S =
        source.e match
            case Left(a)            => f(a).map(accepted => if accepted then Some(Left(a)) else None)
            case right: Right[?, ?] => Some(right.asInstanceOf[Either[A, B1]])

    /** Produces the `Left` value of a projected `Either`, or an effect-producing default when it is a `Right`. */
    def getOrElse[A, B, A1 >: A, S](source: Either.LeftProjection[A, B])(default: => A1 < S)(using Frame): A1 < S =
        source.e match
            case Left(a) => a
            case _       => default
end Kyo
