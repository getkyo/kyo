package kyo.kernel2.internal

import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import kyo.kernel2.*
import language.implicitConversions
import scala.annotation.nowarn
import scala.annotation.static
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.collection.Iterable
import scala.collection.IterableOps

// TODO this is meant as internal
sealed abstract class Kyo[+A, -S]:
    private[kyo] def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2)
    private[kyo] def prepend(f: Arrow.Interceptor): A < S

// TODO this should be in the kyo package
object Kyo:

    /** Lifts a value into the effect context without suspension, including nested computations: a value that is itself a computation
      * enters as data (a [[Nested]] box), not as a suspension to run. The explicit route for intentional nesting, which the implicit
      * lift rejects at compile time.
      */
    inline def lift[A, S](inline v: A): A < S =
        v match
            case v: Kyo[?, ?] => Nested(v).asInstanceOf[A < S]
            case v: Nested[?] => Nested(v).asInstanceOf[A < S]
            case v            => v.asInstanceOf[A < S]

    // Compiled as a JVM static of class Kyo: hot callers (minted arrow fragments,
    // Arrow.apply, Offset.run) reach it via invokestatic with no module load and,
    // in minted fragments, no captured reference to an enclosing object.
    @static def unnest(v: Any): Any =
        v match
            case n: Nested[?] => n.value
            case _            => v

    // a case class so re-wrapping at pass-through positions preserves value equality
    final private[kyo] case class Nested[+A](value: A)

    /** A bare suspension: an arrow-effect operation awaiting a handler clause, with no continuation attached yet.
      *
      * The one suspension kind: context reads are plain [[Defer]]s consuming the threaded context, so no read node exists.
      */
    abstract class Suspend[I[_], O[_], E <: ArrowEffect[I, O], A] extends Kyo[O[A], E]:

        def input: I[A]
        def tag: Tag[E]
        def frame: Frame

        final private[kyo] def erasedTag: Tag[Any] = tag.erased

        final private[kyo] def map[B, S2](f: Arrow[O[A], B, S2]): B < (E & S2) =
            Continue[O[A], B, E & S2](this, f)

        final private[kyo] def prepend(f: Arrow.Interceptor): O[A] < E =
            map(f.as[O[A], Any])

        final override def toString = "Suspend(" + tag.show + ", " + frame.position.show + ")"

    end Suspend

    final private[kyo] class Continue[A, +B, -S](
        val suspend: Suspend[?, ?, ?, ?],
        val cont: Arrow[A, B, S]
    ) extends Kyo[B, S]:

        private[kyo] def map[C, S2](f: Arrow[B, C, S2]): C < (S & S2) =
            Continue(suspend, cont.map(f))

        private[kyo] def prepend(f: Arrow.Interceptor): B < S =
            Continue(suspend, f.as[A, S].map(cont))

        override def toString = "Continue(" + suspend + ", " + cont + ")"

    end Continue

    abstract class Bracket[R, A, S] extends Kyo[A, S]:

        def acquire: R < S
        def release(r: R): Unit < S
        def cont: Arrow[R, A, S]
        def frame: Frame

        final private[kyo] def map[B, S2](f: Arrow[A, B, S2]): B < (S & S2) =
            val outer = this
            new Bracket[R, B, S & S2]:
                def acquire       = outer.acquire
                def release(r: R) = outer.release(r)
                def cont          = outer.cont.map(f)
                def frame         = outer.frame
            end new
        end map

        final private[kyo] def prepend(f: Arrow.Interceptor): A < S =
            val outer = this
            new Bracket[R, A, S]:
                def acquire =
                    outer.acquire match
                        case kyo: Kyo[R, S] @unchecked => kyo.prepend(f)
                        case v                         => v
                // release is wrapped too: a finalizer runs under the interceptors in
                // scope at the bracket, bindings included
                def release(r: R) =
                    outer.release(r) match
                        case kyo: Kyo[Unit, S] @unchecked => kyo.prepend(f)
                        case v                            => v
                def cont  = f.as[R, S].map(outer.cont)
                def frame = outer.frame
            end new
        end prepend

        final override def toString = "Bracket(" + frame.position.show + ")"

    end Bracket

    // public because the inline trampoline's Defer arm expands at user sites.
    // value is a pending value, not a bare A: variance then types the public
    // deferring application and the drive's pop without casts
    final class Defer[A, +B, -S](
        val value: A < S,
        val cont: Arrow[A, B, S]
    ) extends Kyo[B, S]:

        private[kyo] def map[C, S2](f: Arrow[B, C, S2]): C < (S & S2) =
            Defer(value, cont.map(f))

        private[kyo] def prepend(f: Arrow.Interceptor): B < S =
            Defer(value, f.as[A, S].map(cont))

        override def toString = "Defer(" + cont + ")"

    end Defer

    // ------------------------------------------------------------------------------------------------------------------
    // Companion utilities: the user-facing Kyo combinator surface, conformant with the current kernel's kyo.Kyo object.
    // The current kernel also ships per-collection specializations (List, Seq, Chunk, Set) of the generic variants below;
    // those are performance work for the optimization round, since overload resolution binds the generic variant
    // source-compatibly.
    // ------------------------------------------------------------------------------------------------------------------

    /** A pure effect that produces Unit. */
    inline def unit: Unit < Any = ()

    /** Run one of two effects based on the result of an effectful condition. */
    def when[S](condition: Boolean < S)[A, S1](ifTrue: => A < S1, ifFalse: => A < S1)(using Frame): A < (S & S1) =
        condition.map(if _ then ifTrue else ifFalse)

    /** Run an effect if an effectful condition evaluates to true, as a Maybe. */
    def when[S](condition: Boolean < S)[A, S1](ifTrue: => A < S1)(using Frame): Maybe[A] < (S & S1) =
        condition.map(if _ then ifTrue.map(Maybe.Present(_)) else Maybe.Absent)

    /** Run an effect if an effectful condition evaluates to false, as a Maybe. */
    def unless[S](condition: Boolean < S)[A, S1](ifFalse: => A < S1)(using Frame): Maybe[A] < (S & S1) =
        condition.map(if _ then Maybe.Absent else ifFalse.map(Maybe.Present(_)))

    /** Zips two effects into a tuple. */
    def zip[A1, A2, S](v1: A1 < S, v2: A2 < S)(using Frame): (A1, A2) < S =
        v1.map(t1 => v2.map(t2 => (t1, t2)))

    /** Zips three effects into a tuple. */
    def zip[A1, A2, A3, S](v1: A1 < S, v2: A2 < S, v3: A3 < S)(using Frame): (A1, A2, A3) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => (t1, t2, t3))))

    /** Zips four effects into a tuple. */
    def zip[A1, A2, A3, A4, S](v1: A1 < S, v2: A2 < S, v3: A3 < S, v4: A4 < S)(using Frame): (A1, A2, A3, A4) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => v4.map(t4 => (t1, t2, t3, t4)))))

    /** Zips five effects into a tuple. */
    def zip[A1, A2, A3, A4, A5, S](v1: A1 < S, v2: A2 < S, v3: A3 < S, v4: A4 < S, v5: A5 < S)(using
        Frame
    ): (A1, A2, A3, A4, A5) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => v4.map(t4 => v5.map(t5 => (t1, t2, t3, t4, t5))))))

    /** Zips six effects into a tuple. */
    def zip[A1, A2, A3, A4, A5, A6, S](v1: A1 < S, v2: A2 < S, v3: A3 < S, v4: A4 < S, v5: A5 < S, v6: A6 < S)(using
        Frame
    ): (A1, A2, A3, A4, A5, A6) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => v4.map(t4 => v5.map(t5 => v6.map(t6 => (t1, t2, t3, t4, t5, t6)))))))

    /** Zips seven effects into a tuple. */
    def zip[A1, A2, A3, A4, A5, A6, A7, S](v1: A1 < S, v2: A2 < S, v3: A3 < S, v4: A4 < S, v5: A5 < S, v6: A6 < S, v7: A7 < S)(using
        Frame
    ): (A1, A2, A3, A4, A5, A6, A7) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => v4.map(t4 => v5.map(t5 => v6.map(t6 => v7.map(t7 => (t1, t2, t3, t4, t5, t6, t7))))))))

    /** Zips eight effects into a tuple. */
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
        zip(v1, v2, v3, v4, v5, v6, v7).map(t => v8.map(t8 => (t._1, t._2, t._3, t._4, t._5, t._6, t._7, t8)))

    /** Zips nine effects into a tuple. */
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
        zip(v1, v2, v3, v4, v5, v6, v7, v8).map(t => v9.map(t9 => (t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8, t9)))

    /** Zips ten effects into a tuple. */
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
        zip(v1, v2, v3, v4, v5, v6, v7, v8, v9).map(t =>
            v10.map(t10 => (t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8, t._9, t10))
        )

    /** Produces a Chunk with the results of running the effect `n` times. */
    def fill[A, S](n: Int)(v: => A < S)(using Frame): Chunk[A] < S =
        def loop(i: Int, acc: Chunk[A]): Chunk[A] < S =
            if i == n then acc
            else v.map(a => loop(i + 1, acc.append(a)))
        loop(0, Chunk.empty)
    end fill

    /** Applies an effect-producing function to each element, preserving the collection type. */
    def foreach[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: A => B < S)(using Frame): CC[B] < S =
        val it      = source.iterator
        val builder = source.iterableFactory.newBuilder[B]
        def loop(): CC[B] < S =
            if !it.hasNext then builder.result()
            else
                f(it.next()).map { b =>
                    builder += b
                    loop()
                }
        loop()
    end foreach

    /** Applies an effect-producing function returning collections and concatenates the results. */
    def foreachConcat[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: A => IterableOnce[B] < S)(using
        Frame
    ): CC[B] < S =
        val it      = source.iterator
        val builder = source.iterableFactory.newBuilder[B]
        def loop(): CC[B] < S =
            if !it.hasNext then builder.result()
            else
                f(it.next()).map { bs =>
                    builder ++= bs
                    loop()
                }
        loop()
    end foreachConcat

    /** Applies an effect-producing function to each element with its index. */
    def foreachIndexed[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: (Int, A) => B < S)(using
        Frame
    ): CC[B] < S =
        val it      = source.iterator
        val builder = source.iterableFactory.newBuilder[B]
        def loop(i: Int): CC[B] < S =
            if !it.hasNext then builder.result()
            else
                f(i, it.next()).map { b =>
                    builder += b
                    loop(i + 1)
                }
        loop(0)
    end foreachIndexed

    /** Applies an effect-producing function to each element, discarding the results. */
    def foreachDiscard[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: A => Any < S)(using
        Frame
    ): Unit < S =
        val it = source.iterator
        def loop(): Unit < S =
            if !it.hasNext then ()
            else f(it.next()).map(_ => loop())
        loop()
    end foreachDiscard

    /** Keeps the elements whose effectful predicate evaluates to true. */
    def filter[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: A => Boolean < S)(using Frame): CC[A] < S =
        val it      = source.iterator
        val builder = source.iterableFactory.newBuilder[A]
        def loop(): CC[A] < S =
            if !it.hasNext then builder.result()
            else
                val a = it.next()
                f(a).map { keep =>
                    if keep then builder += a
                    loop()
                }
        loop()
    end filter

    /** Folds the elements with an effect-producing operator. */
    def foldLeft[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(acc: B)(f: (B, A) => B < S)(using
        Frame
    ): B < S =
        val it = source.iterator
        def loop(b: B): B < S =
            if !it.hasNext then b
            else f(b, it.next()).map(loop)
        loop(acc)
    end foldLeft

    /** Applies an effect-producing partial transformation, keeping the Present results. */
    def collect[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: A => Maybe[B] < S)(using
        Frame
    ): CC[B] < S =
        val it      = source.iterator
        val builder = source.iterableFactory.newBuilder[B]
        def loop(): CC[B] < S =
            if !it.hasNext then builder.result()
            else
                f(it.next()).map { m =>
                    m match
                        case Maybe.Present(b) => builder += b
                        case Maybe.Absent     => ()
                    loop()
                }
        loop()
    end collect

    /** Runs the effects in the collection, collecting the results. */
    def collectAll[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A < S])(using Frame): CC[A] < S =
        val it      = source.iterator
        val builder = source.iterableFactory.newBuilder[A]
        def loop(): CC[A] < S =
            if !it.hasNext then builder.result()
            else
                it.next().map { a =>
                    builder += a
                    loop()
                }
        loop()
    end collectAll

    /** Runs the effects in the collection, discarding the results. */
    def collectAllDiscard[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A < S])(using Frame): Unit < S =
        val it = source.iterator
        def loop(): Unit < S =
            if !it.hasNext then ()
            else it.next().map(_ => loop())
        loop()
    end collectAllDiscard

    /** Returns the first Present result of the effectful transformation, if any. */
    def findFirst[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: A => Maybe[B] < S)(using
        Frame
    ): Maybe[B] < S =
        val it = source.iterator
        def loop(): Maybe[B] < S =
            if !it.hasNext then Maybe.Absent
            else
                f(it.next()).map {
                    case found @ Maybe.Present(_) => (found: Maybe[B])
                    case Maybe.Absent             => loop()
                }
        loop()
    end findFirst

    /** Takes the longest prefix whose effectful predicate evaluates to true. */
    def takeWhile[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: A => Boolean < S)(using Frame): CC[A] < S =
        val it      = source.iterator
        val builder = source.iterableFactory.newBuilder[A]
        def loop(): CC[A] < S =
            if !it.hasNext then builder.result()
            else
                val a = it.next()
                f(a).map { keep =>
                    if keep then
                        builder += a
                        loop()
                    else builder.result()
                }
        loop()
    end takeWhile

    /** Splits at the first element whose effectful predicate evaluates to false. */
    def span[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: A => Boolean < S)(using
        Frame
    ): (CC[A], CC[A]) < S =
        val it     = source.iterator
        val prefix = source.iterableFactory.newBuilder[A]
        def loop(): (CC[A], CC[A]) < S =
            if !it.hasNext then (prefix.result(), source.iterableFactory.empty[A])
            else
                val a = it.next()
                f(a).map { keep =>
                    if keep then
                        prefix += a
                        loop()
                    else
                        val suffix = source.iterableFactory.newBuilder[A]
                        suffix += a
                        suffix ++= it
                        (prefix.result(), suffix.result())
                }
        loop()
    end span

    /** Drops the longest prefix whose effectful predicate evaluates to true. */
    def dropWhile[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: A => Boolean < S)(using Frame): CC[A] < S =
        span(source)(f).map(_._2)

    /** Partitions the elements by an effectful predicate: (matching, non matching). */
    def partition[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: A => Boolean < S)(using
        Frame
    ): (CC[A], CC[A]) < S =
        val it  = source.iterator
        val yes = source.iterableFactory.newBuilder[A]
        val no  = source.iterableFactory.newBuilder[A]
        def loop(): (CC[A], CC[A]) < S =
            if !it.hasNext then (yes.result(), no.result())
            else
                val a = it.next()
                f(a).map { matches =>
                    if matches then yes += a else no += a
                    loop()
                }
        loop()
    end partition

    /** Partitions the elements by an effectful Either transformation: (lefts, rights). */
    def partitionMap[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, A1, A2, S](source: CC[A])(f: A => Either[A1, A2] < S)(using
        Frame
    ): (CC[A1], CC[A2]) < S =
        val it     = source.iterator
        val lefts  = source.iterableFactory.newBuilder[A1]
        val rights = source.iterableFactory.newBuilder[A2]
        def loop(): (CC[A1], CC[A2]) < S =
            if !it.hasNext then (lefts.result(), rights.result())
            else
                f(it.next()).map { e =>
                    e match
                        case Left(a1)  => lefts += a1
                        case Right(a2) => rights += a2
                    loop()
                }
        loop()
    end partitionMap

    /** Computes the running fold of the elements, starting with `z`. */
    def scanLeft[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(z: B)(op: (B, A) => B < S)(using
        Frame
    ): CC[B] < S =
        val it      = source.iterator
        val builder = source.iterableFactory.newBuilder[B]
        builder += z
        def loop(b: B): CC[B] < S =
            if !it.hasNext then builder.result()
            else
                op(b, it.next()).map { next =>
                    builder += next
                    loop(next)
                }
        loop(z)
    end scanLeft

    /** Groups the elements by an effectful key function. */
    def groupBy[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, K, S](source: CC[A])(f: A => K < S)(using
        Frame
    ): Map[K, CC[A]] < S =
        groupMap(source)(f)(a => (a: A < S))

    /** Groups the elements by an effectful key function, transforming each with an effectful value function. */
    def groupMap[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, K, B, S](source: CC[A])(key: A => K < S)(f: A => B < S)(using
        Frame
    ): Map[K, CC[B]] < S =
        val it       = source.iterator
        val builders = scala.collection.mutable.LinkedHashMap.empty[K, scala.collection.mutable.Builder[B, CC[B]]]
        def loop(): Map[K, CC[B]] < S =
            if !it.hasNext then builders.iterator.map((k, b) => (k, b.result())).toMap
            else
                val a = it.next()
                key(a).map { k =>
                    f(a).map { b =>
                        builders.getOrElseUpdate(k, source.iterableFactory.newBuilder[B]) += b
                        loop()
                    }
                }
        loop()
    end groupMap

    /** Transforms each map entry into a new entry. */
    def foreach[K1, V1, K2, V2, S](source: Map[K1, V1])(f: ((K1, V1)) => (K2, V2) < S)(using Frame): Map[K2, V2] < S =
        val it = source.iterator
        def loop(acc: Map[K2, V2]): Map[K2, V2] < S =
            if !it.hasNext then acc
            else f(it.next()).map(kv => loop(acc + kv))
        loop(Map.empty)
    end foreach

    /** Transforms each map entry into a value, collecting the results. */
    @targetName("foreachToChunk")
    def foreach[K1, V1, B, S](source: Map[K1, V1])(f: ((K1, V1)) => B < S)(using Frame): Chunk[B] < S =
        val it = source.iterator
        def loop(acc: Chunk[B]): Chunk[B] < S =
            if !it.hasNext then acc
            else f(it.next()).map(b => loop(acc.append(b)))
        loop(Chunk.empty)
    end foreach

    /** Transforms each map entry into entries, concatenating the results. */
    def foreachConcat[K1, V1, K2, V2, S](source: Map[K1, V1])(f: ((K1, V1)) => IterableOnce[(K2, V2)] < S)(using
        Frame
    ): Map[K2, V2] < S =
        val it = source.iterator
        def loop(acc: Map[K2, V2]): Map[K2, V2] < S =
            if !it.hasNext then acc
            else f(it.next()).map(kvs => loop(acc ++ kvs))
        loop(Map.empty)
    end foreachConcat

    /** Transforms each map entry into values, concatenating the results. */
    @targetName("foreachConcatToChunk")
    def foreachConcat[K1, V1, B, S](source: Map[K1, V1])(f: ((K1, V1)) => IterableOnce[B] < S)(using Frame): Chunk[B] < S =
        val it = source.iterator
        def loop(acc: Chunk[B]): Chunk[B] < S =
            if !it.hasNext then acc
            else f(it.next()).map(bs => loop(acc.concat(Chunk.from(bs))))
        loop(Chunk.empty)
    end foreachConcat

    /** Applies an effect-producing function to each map entry, discarding the results. */
    def foreachDiscard[K1, V1, S](source: Map[K1, V1])(f: ((K1, V1)) => Any < S)(using Frame): Unit < S =
        val it = source.iterator
        def loop(): Unit < S =
            if !it.hasNext then ()
            else f(it.next()).map(_ => loop())
        loop()
    end foreachDiscard

    /** Keeps the entries whose effectful predicate evaluates to true. */
    def filter[K1, V1, S](source: Map[K1, V1])(f: ((K1, V1)) => Boolean < S)(using Frame): Map[K1, V1] < S =
        val it = source.iterator
        def loop(acc: Map[K1, V1]): Map[K1, V1] < S =
            if !it.hasNext then acc
            else
                val kv = it.next()
                f(kv).map(keep => loop(if keep then acc + kv else acc))
        loop(Map.empty)
    end filter

    /** Keeps the entries whose key passes the effectful predicate. */
    def filterKeys[K1, V1, S](source: Map[K1, V1])(f: K1 => Boolean < S)(using Frame): Map[K1, V1] < S =
        filter(source)(kv => f(kv._1))

    /** Folds the map entries with an effect-producing operator. */
    def foldLeft[K1, V1, B, S](source: Map[K1, V1])(acc: B)(f: (B, (K1, V1)) => B < S)(using Frame): B < S =
        val it = source.iterator
        def loop(b: B): B < S =
            if !it.hasNext then b
            else f(b, it.next()).map(loop)
        loop(acc)
    end foldLeft

    /** Applies an effect-producing partial transformation to entries, keeping the Present results. */
    def collect[K1, V1, K2, V2, S](source: Map[K1, V1])(f: ((K1, V1)) => Maybe[(K2, V2)] < S)(using Frame): Map[K2, V2] < S =
        val it = source.iterator
        def loop(acc: Map[K2, V2]): Map[K2, V2] < S =
            if !it.hasNext then acc
            else
                f(it.next()).map {
                    case Maybe.Present(kv) => loop(acc + kv)
                    case Maybe.Absent      => loop(acc)
                }
        loop(Map.empty)
    end collect

    /** Applies an effect-producing partial transformation to entries, keeping the Present values. */
    @targetName("collectToChunk")
    def collect[K1, V1, B, S](source: Map[K1, V1])(f: ((K1, V1)) => Maybe[B] < S)(using Frame): Chunk[B] < S =
        val it = source.iterator
        def loop(acc: Chunk[B]): Chunk[B] < S =
            if !it.hasNext then acc
            else
                f(it.next()).map {
                    case Maybe.Present(b) => loop(acc.append(b))
                    case Maybe.Absent     => loop(acc)
                }
        loop(Chunk.empty)
    end collect

    /** Runs the effects in the map's values, collecting the results. */
    def collectAll[K1, V1, S](source: Map[K1, V1 < S])(using Frame): Map[K1, V1] < S =
        val it = source.iterator
        def loop(acc: Map[K1, V1]): Map[K1, V1] < S =
            if !it.hasNext then acc
            else
                val (k, v) = it.next()
                v.map(v1 => loop(acc.updated(k, v1)))
        loop(Map.empty)
    end collectAll

    /** Runs the effects in the map's values, discarding the results. */
    def collectAllDiscard[K1, V1, S](source: Map[K1, V1 < S])(using Frame): Unit < S =
        val it = source.iterator
        def loop(): Unit < S =
            if !it.hasNext then ()
            else it.next()._2.map(_ => loop())
        loop()
    end collectAllDiscard

    /** Returns the first Present result of the effectful transformation over entries, if any. */
    def findFirst[K1, V1, B, S](source: Map[K1, V1])(f: ((K1, V1)) => Maybe[B] < S)(using Frame): Maybe[B] < S =
        val it = source.iterator
        def loop(): Maybe[B] < S =
            if !it.hasNext then Maybe.Absent
            else
                f(it.next()).map {
                    case found @ Maybe.Present(_) => (found: Maybe[B])
                    case Maybe.Absent             => loop()
                }
        loop()
    end findFirst

    /** Takes entries, in iteration order, while the effectful predicate evaluates to true. */
    def takeWhile[K1, V1, S](source: Map[K1, V1])(f: ((K1, V1)) => Boolean < S)(using Frame): Map[K1, V1] < S =
        val it = source.iterator
        def loop(acc: Map[K1, V1]): Map[K1, V1] < S =
            if !it.hasNext then acc
            else
                val kv = it.next()
                f(kv).map(keep => if keep then loop(acc + kv) else acc)
        loop(Map.empty)
    end takeWhile

    /** Splits the entries, in iteration order, at the first whose effectful predicate evaluates to false. */
    def span[K1, V1, S](source: Map[K1, V1])(f: ((K1, V1)) => Boolean < S)(using Frame): (Map[K1, V1], Map[K1, V1]) < S =
        val it = source.iterator
        def loop(prefix: Map[K1, V1]): (Map[K1, V1], Map[K1, V1]) < S =
            if !it.hasNext then (prefix, Map.empty)
            else
                val kv = it.next()
                f(kv).map { keep =>
                    if keep then loop(prefix + kv)
                    else (prefix, (Map.newBuilder[K1, V1] += kv ++= it).result())
                }
        loop(Map.empty)
    end span

    /** Drops entries, in iteration order, while the effectful predicate evaluates to true. */
    def dropWhile[K1, V1, S](source: Map[K1, V1])(f: ((K1, V1)) => Boolean < S)(using Frame): Map[K1, V1] < S =
        span(source)(f).map(_._2)

    /** Partitions the entries by an effectful predicate: (matching, non matching). */
    def partition[K1, V1, S](source: Map[K1, V1])(f: ((K1, V1)) => Boolean < S)(using
        Frame
    ): (Map[K1, V1], Map[K1, V1]) < S =
        val it = source.iterator
        def loop(yes: Map[K1, V1], no: Map[K1, V1]): (Map[K1, V1], Map[K1, V1]) < S =
            if !it.hasNext then (yes, no)
            else
                val kv = it.next()
                f(kv).map(matches => if matches then loop(yes + kv, no) else loop(yes, no + kv))
        loop(Map.empty, Map.empty)
    end partition

    /** Partitions the entries by an effectful Either transformation: (lefts, rights). */
    def partitionMap[K1, V1, K2, V2, K3, V3, S](source: Map[K1, V1])(f: ((K1, V1)) => Either[(K2, V2), (K3, V3)] < S)(using
        Frame
    ): (Map[K2, V2], Map[K3, V3]) < S =
        val it = source.iterator
        def loop(lefts: Map[K2, V2], rights: Map[K3, V3]): (Map[K2, V2], Map[K3, V3]) < S =
            if !it.hasNext then (lefts, rights)
            else
                f(it.next()).map {
                    case Left(kv)  => loop(lefts + kv, rights)
                    case Right(kv) => loop(lefts, rights + kv)
                }
        loop(Map.empty, Map.empty)
    end partitionMap

    /** Computes the running fold of the entries, starting with `z`. */
    def scanLeft[K1, V1, B, S](source: Map[K1, V1])(z: B)(op: (B, (K1, V1)) => B < S)(using Frame): Chunk[B] < S =
        val it = source.iterator
        def loop(b: B, acc: Chunk[B]): Chunk[B] < S =
            if !it.hasNext then acc
            else op(b, it.next()).map(next => loop(next, acc.append(next)))
        loop(z, Chunk(z))
    end scanLeft

    /** Groups the entries by an effectful key function. */
    def groupBy[K1, V1, K2, S](source: Map[K1, V1])(f: ((K1, V1)) => K2 < S)(using Frame): Map[K2, Map[K1, V1]] < S =
        val it = source.iterator
        def loop(acc: Map[K2, Map[K1, V1]]): Map[K2, Map[K1, V1]] < S =
            if !it.hasNext then acc
            else
                val kv = it.next()
                f(kv).map(k2 => loop(acc.updated(k2, acc.getOrElse(k2, Map.empty) + kv)))
        loop(Map.empty)
    end groupBy

    /** Groups the entries by an effectful key function, transforming each with an effectful value function. */
    def groupMap[K1, V1, K2, V2, S](source: Map[K1, V1])(key: ((K1, V1)) => K2 < S)(f: ((K1, V1)) => V2 < S)(using
        Frame
    ): Map[K2, Chunk[V2]] < S =
        val it = source.iterator
        def loop(acc: Map[K2, Chunk[V2]]): Map[K2, Chunk[V2]] < S =
            if !it.hasNext then acc
            else
                val kv = it.next()
                key(kv).map(k2 => f(kv).map(v2 => loop(acc.updated(k2, acc.getOrElse(k2, Chunk.empty).append(v2)))))
        loop(Map.empty)
    end groupMap

    // for kyo-direct
    private[kyo] def shiftedWhile[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S, B, C](source: CC[A])(
        prolog: B,
        f: A => Boolean < S,
        acc: (B, Boolean, A) => B,
        epilog: B => C
    )(using Frame): C < S =
        val it = source.iterator
        def loop(b: B): C < S =
            if !it.hasNext then epilog(b)
            else
                val a = it.next()
                f(a).map { cond =>
                    if cond then loop(acc(b, cond, a))
                    else epilog(acc(b, cond, a))
                }
        loop(prolog)
    end shiftedWhile

end Kyo
