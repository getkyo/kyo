package kyo

import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.kernel.<
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.collection.IterableOps

object Kyo:

    inline def lift[A, S](inline v: A): A < S = v

    inline def unit: Unit < Any = ()

    def when[S](condition: Boolean < S)[A, S1](ifTrue: => A < S1, ifFalse: => A < S1)(using Frame): A < (S & S1) =
        condition.map(if _ then ifTrue else ifFalse)

    def when[S](condition: Boolean < S)[A, S1](ifTrue: => A < S1)(using Frame): Maybe[A] < (S & S1) =
        condition.map(if _ then ifTrue.map(Present(_)) else Absent)

    def unless[S](condition: Boolean < S)[A, S1](ifFalse: => A < S1)(using Frame): Maybe[A] < (S & S1) =
        condition.map(if _ then Absent else ifFalse.map(Present(_)))

    def zip[A1, A2, S](v1: A1 < S, v2: A2 < S)(using Frame): (A1, A2) < S =
        v1.map(t1 => v2.map(t2 => (t1, t2)))

    def zip[A1, A2, A3, S](v1: A1 < S, v2: A2 < S, v3: A3 < S)(using Frame): (A1, A2, A3) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => (t1, t2, t3))))

    def zip[A1, A2, A3, A4, S](v1: A1 < S, v2: A2 < S, v3: A3 < S, v4: A4 < S)(using Frame): (A1, A2, A3, A4) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => v4.map(t4 => (t1, t2, t3, t4)))))

    def zip[A1, A2, A3, A4, A5, S](v1: A1 < S, v2: A2 < S, v3: A3 < S, v4: A4 < S, v5: A5 < S)(using Frame): (A1, A2, A3, A4, A5) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => v4.map(t4 => v5.map(t5 => (t1, t2, t3, t4, t5))))))

    def zip[A1, A2, A3, A4, A5, A6, S](v1: A1 < S, v2: A2 < S, v3: A3 < S, v4: A4 < S, v5: A5 < S, v6: A6 < S)(using
        Frame
    ): (A1, A2, A3, A4, A5, A6) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => v4.map(t4 => v5.map(t5 => v6.map(t6 => (t1, t2, t3, t4, t5, t6)))))))

    def zip[A1, A2, A3, A4, A5, A6, A7, S](v1: A1 < S, v2: A2 < S, v3: A3 < S, v4: A4 < S, v5: A5 < S, v6: A6 < S, v7: A7 < S)(using
        Frame
    ): (A1, A2, A3, A4, A5, A6, A7) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => v4.map(t4 => v5.map(t5 => v6.map(t6 => v7.map(t7 => (t1, t2, t3, t4, t5, t6, t7))))))))

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

    def foreach[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: A => B < S)(using Frame): CC[B] < S =
        Kyo.foreach(Chunk.from(source))(f).map: resultChunk =>
            source.iterableFactory.from(resultChunk)
    end foreach

    def foreachConcat[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: A => IterableOnce[B] < S)(
        using Frame
    ): CC[B] < S =
        Kyo.foreachConcat(Chunk.from(source))(f).map: resultChunk =>
            source.iterableFactory.from(resultChunk)
    end foreachConcat

    def foreachIndexed[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: (Int, A) => B < S)(using
        Frame
    ): CC[B] < S =
        Kyo.foreachIndexed(Chunk.from(source))(f).map: resultChunk =>
            source.iterableFactory.from(resultChunk)
    end foreachIndexed

    def foreachDiscard[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: A => Any < S)(using Frame): Unit < S =
        Kyo.foreachDiscard(Chunk.from(source))(f)
    end foreachDiscard

    def filter[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: A => Boolean < S)(using Frame): CC[A] < S =
        Kyo.filter(Chunk.from(source))(f).map: resultChunk =>
            source.iterableFactory.from(resultChunk)
    end filter

    def foldLeft[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(acc: B)(f: (B, A) => B < S)(using
        Frame
    ): B < S =
        Kyo.foldLeft(Chunk.from(source))(acc)(f)
    end foldLeft

    def collect[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: A => Maybe[B] < S)(using Frame): CC[B] < S =
        val chunk = Chunk.from(source)
        collect[A, B, S](chunk)(f).map: resultChunk =>
            source.iterableFactory.from(resultChunk)
    end collect

    def collectAll[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A < S])(using Frame): CC[A] < S =
        Kyo.collectAll(Chunk.from(source)).map: resultChunk =>
            source.iterableFactory.from(resultChunk)
    end collectAll

    def collectAllDiscard[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A < S])(using Frame): Unit < S =
        Kyo.collectAllDiscard(Chunk.from(source))
    end collectAllDiscard

    def findFirst[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(f: A => Maybe[B] < S)(using
        Frame
    ): Maybe[B] < S =
        Kyo.findFirst(Chunk.from(source))(f)
    end findFirst

    def takeWhile[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: A => Boolean < S)(using Frame): CC[A] < S =
        Kyo.takeWhile(Chunk.from(source))(f).map: resultChunk =>
            source.iterableFactory.from(resultChunk)
    end takeWhile

    def span[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: A => Boolean < S)(using Frame): (CC[A], CC[A]) < S =
        Kyo.span(Chunk.from(source))(f).map: (leftChunk, rightChunk) =>
            (source.iterableFactory.from(leftChunk), source.iterableFactory.from(rightChunk))
    end span

    def dropWhile[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: A => Boolean < S)(using Frame): CC[A] < S =
        Kyo.dropWhile(Chunk.from(source))(f).map: resultChunk =>
            source.iterableFactory.from(resultChunk)
    end dropWhile

    def partition[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S](source: CC[A])(f: A => Boolean < S)(using
        Frame
    ): (CC[A], CC[A]) < S =
        Kyo.partition(Chunk.from(source))(f).map: (leftChunk, rightChunk) =>
            (source.iterableFactory.from(leftChunk), source.iterableFactory.from(rightChunk))
    end partition

    def partitionMap[CC[+X] <: Iterable[X] & IterableOps[
        X,
        CC,
        CC[X]
    ], A, A1, A2, S](source: CC[A])(f: A => Either[A1, A2] < S)(using Frame): (CC[A1], CC[A2]) < S =
        Kyo.partitionMap(Chunk.from(source))(f).map: (leftChunk, rightChunk) =>
            (source.iterableFactory.from(leftChunk), source.iterableFactory.from(rightChunk))
    end partitionMap

    def scanLeft[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, B, S](source: CC[A])(z: B)(op: (B, A) => B < S)(using
        Frame
    ): CC[B] < S =
        Kyo.scanLeft(Chunk.from(source))(z)(op).map: resultChunk =>
            source.iterableFactory.from(resultChunk)
    end scanLeft

    def groupBy[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, K, S](source: CC[A])(f: A => K < S)(using Frame): Map[K, CC[A]] < S =
        Kyo.groupBy(Chunk.from(source))(f).map: resultChunk =>
            Map.from(resultChunk.view.mapValues(source.iterableFactory.from(_)))
    end groupBy

    def groupMap[CC[+X] <: Iterable[X] & IterableOps[
        X,
        CC,
        CC[X]
    ], A, K, B, S](source: CC[A])(key: A => K < S)(f: A => B < S)(using Frame): Map[K, CC[B]] < S =
        Kyo.groupMap(Chunk.from(source))(key)(f).map: resultChunk =>
            Map.from(resultChunk.view.mapValues(source.iterableFactory.from(_)))
    end groupMap

    def fill[A, S](n: Int)(v: A < S)(using Frame): Chunk[A] < S =
        Loop.indexed(Chunk.empty[A]) { (idx, acc) =>
            if idx == n then Loop.done(acc)
            else v.map(t => Loop.continue(acc.append(t)))
        }

    private[kyo] def shiftedWhile[CC[+X] <: Iterable[X] & IterableOps[X, CC, CC[X]], A, S, B, C](source: CC[A])(
        prolog: B,
        f: A => Boolean < S,
        acc: (B, Boolean, A) => B,
        epilog: B => C
    )(using Frame): C < S =
        Kyo.shiftedWhile(Chunk.from(source))(prolog, f, acc, epilog)
    end shiftedWhile

    def foreach[A, B, S](source: List[A])(f: A => B < S)(using Frame): List[B] < S =
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

    def foreachConcat[A, B, S](source: List[A])(f: A => IterableOnce[B] < S)(using Frame): List[B] < S =
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

    def foreachIndexed[A, B, S](source: List[A])(f: (Int, A) => B < S)(using Frame): List[B] < S =
        source match
            case Nil         => Nil
            case head :: Nil => f(0, head).map(_ :: Nil)
            case list =>
                Loop.indexed(list, Nil): (idx, curList, acc) =>
                    curList match
                        case head :: tail => f(idx, head).map { u => Loop.continue(tail, u :: acc) }
                        case Nil          => Loop.done(acc.reverse)
    end foreachIndexed

    def foreachDiscard[A, B, S](source: List[A])(f: A => Any < S)(using Frame): Unit < S =
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

    def filter[A, S](source: List[A])(f: A => Boolean < S)(using Frame): List[A] < S =
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

    def foldLeft[A, B, S](source: List[A])(acc: B)(f: (B, A) => B < S)(using Frame): B < S =
        source match
            case Nil         => acc
            case head :: Nil => f(acc, head)
            case list =>
                Loop(list, acc): (curList, acc) =>
                    curList match
                        case head :: tail => f(acc, head).map(Loop.continue(tail, _))
                        case Nil          => Loop.done(acc)
    end foldLeft

    def collect[A, B, S](source: List[A])(f: A => Maybe[B] < S)(using Frame): List[B] < S =
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

    def collectAll[A, S](source: List[A < S])(using Frame): List[A] < S =
        source match
            case Nil         => Nil
            case head :: Nil => head.map(_ :: Nil)
            case list =>
                Loop(list, Nil): (curList, accList) =>
                    curList match
                        case head :: tail => head.map(u => Loop.continue(tail, u :: accList))
                        case Nil          => Loop.done(accList.reverse)
        end match
    end collectAll

    def collectAllDiscard[A, S](source: List[A < S])(using Frame): Unit < S =
        source match
            case Nil         => ()
            case head :: Nil => head.unit
            case list =>
                Loop(list): curList =>
                    curList match
                        case head :: tail => head.andThen(Loop.continue(tail))
                        case Nil          => Loop.done
    end collectAllDiscard

    def findFirst[A, B, S](source: List[A])(f: A => Maybe[B] < S)(using Frame): Maybe[B] < S =
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

    def takeWhile[A, S](source: List[A])(f: A => Boolean < S)(using Frame): List[A] < S =
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

    def span[A, S](source: List[A])(f: A => Boolean < S)(using Frame): (List[A], List[A]) < S =
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

    def dropWhile[A, S](source: List[A])(f: A => Boolean < S)(using Frame): List[A] < S =
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

    def partition[S, A](source: List[A])(f: A => Boolean < S)(using Frame): (List[A], List[A]) < S =
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

    def partitionMap[S, A, A1, A2](source: List[A])(f: A => Either[A1, A2] < S)(using Frame): (List[A1], List[A2]) < S =
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

    def scanLeft[S, A, B](source: List[A])(z: B)(op: (B, A) => B < S)(using Frame): List[B] < S =
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

    def groupBy[S, A, K](source: List[A])(f: A => K < S)(using Frame): Map[K, List[A]] < S =
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

    def groupMap[S, A, K, B](source: List[A])(key: A => K < S)(f: A => B < S)(using Frame): Map[K, List[B]] < S =
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

    private[kyo] def shiftedWhile[A, S, B, C](source: List[A])(
        prolog: B,
        f: A => Boolean < S,
        acc: (B, Boolean, A) => B,
        epilog: B => C
    )(using Frame): C < S =
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

    inline def foreach[A, B, S](source: Seq[A])(f: A => B < S)(using Frame): Seq[B] < S =
        foreach(Chunk.from(source))(f)
    end foreach

    inline def foreachConcat[A, B, S](source: Seq[A])(f: A => IterableOnce[B] < S)(using Frame): Seq[B] < S =
        foreachConcat(Chunk.from(source))(f)
    end foreachConcat

    inline def foreachIndexed[A, B, S](source: Seq[A])(f: (Int, A) => B < S)(using Frame): Seq[B] < S =
        foreachIndexed(Chunk.from(source))(f)
    end foreachIndexed

    inline def foreachDiscard[A, B, S](source: Seq[A])(f: A => Any < S)(using Frame): Unit < S =
        foreachDiscard(Chunk.from(source))(f)
    end foreachDiscard

    inline def filter[A, S](source: Seq[A])(f: A => Boolean < S)(using Frame): Seq[A] < S =
        filter(Chunk.from(source))(f)
    end filter

    inline def foldLeft[A, B, S](source: Seq[A])(acc: B)(f: (B, A) => B < S)(using Frame): B < S =
        foldLeft(Chunk.from(source))(acc)(f)
    end foldLeft

    inline def collect[A, B, S](source: Seq[A])(f: A => Maybe[B] < S)(using Frame): Seq[B] < S =
        collect(Chunk.from(source))(f)
    end collect

    inline def collectAll[A, S](source: Seq[A < S])(using Frame): Seq[A] < S =
        collectAll(Chunk.from(source))
    end collectAll

    inline def collectAllDiscard[A, S](source: Seq[A < S])(using Frame): Unit < S =
        collectAllDiscard(Chunk.from(source))
    end collectAllDiscard

    inline def findFirst[A, B, S](source: Seq[A])(f: A => Maybe[B] < S)(using Frame): Maybe[B] < S =
        findFirst(Chunk.from(source))(f)
    end findFirst

    inline def takeWhile[A, S](source: Seq[A])(f: A => Boolean < S)(using Frame): Seq[A] < S =
        takeWhile(Chunk.from(source))(f)
    end takeWhile

    inline def span[A, S](source: Seq[A])(f: A => Boolean < S)(using Frame): (Seq[A], Seq[A]) < S =
        span(Chunk.from(source))(f)
    end span

    inline def dropWhile[A, S](source: Seq[A])(f: A => Boolean < S)(using Frame): Seq[A] < S =
        dropWhile(Chunk.from(source))(f)
    end dropWhile

    inline def partition[S, A](source: Seq[A])(f: A => Boolean < S)(using Frame): (Seq[A], Seq[A]) < S =
        partition(Chunk.from(source))(f)
    end partition

    inline def partitionMap[S, A, A1, A2](source: Seq[A])(f: A => Either[A1, A2] < S)(using Frame): (Seq[A1], Seq[A2]) < S =
        partitionMap(Chunk.from(source))(f)
    end partitionMap

    inline def scanLeft[S, A, B](source: Seq[A])(z: B)(op: (B, A) => B < S)(using Frame): Seq[B] < S =
        scanLeft(Chunk.from(source))(z)(op)
    end scanLeft

    inline def groupBy[S, A, K](source: Seq[A])(f: A => K < S)(using Frame): Map[K, Seq[A]] < S =
        groupBy(Chunk.from(source))(f)
    end groupBy

    inline def groupMap[S, A, K, B](source: Seq[A])(key: A => K < S)(f: A => B < S)(using Frame): Map[K, Seq[B]] < S =
        groupMap(Chunk.from(source))(key)(f)
    end groupMap

    private[kyo] inline def shiftedWhile[A, S, B, C](source: Seq[A])(
        prolog: B,
        f: A => Boolean < S,
        acc: (B, Boolean, A) => B,
        epilog: B => C
    )(using Frame): C < S =
        shiftedWhile(Chunk.from(source))(prolog, f, acc, epilog)
    end shiftedWhile

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

    def foreachConcat[A, B, S](source: Chunk[A])(f: A => IterableOnce[B] < S)(using Frame): Chunk[B] < S =
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

    def partition[S, A](source: Chunk[A])(f: A => Boolean < S)(using Frame): (Chunk[A], Chunk[A]) < S =
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

    def partitionMap[S, A, A1, A2](source: Chunk[A])(f: A => Either[A1, A2] < S)(using Frame): (Chunk[A1], Chunk[A2]) < S =
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

    def scanLeft[S, A, B](source: Chunk[A])(z: B)(op: (B, A) => B < S)(using Frame): Chunk[B] < S =
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

    def groupBy[S, A, K](source: Chunk[A])(f: A => K < S)(using Frame): Map[K, Chunk[A]] < S =
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

    def groupMap[S, A, K, B](source: Chunk[A])(key: A => K < S)(f: A => B < S)(using Frame): Map[K, Chunk[B]] < S =
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

    private[kyo] def shiftedWhile[A, S, B, C](source: Chunk[A])(
        prolog: B,
        f: A => Boolean < S,
        acc: (B, Boolean, A) => B,
        epilog: B => C
    )(using Frame): C < S =
        val chunk = source.toIndexed
        val len   = chunk.length
        len match
            case 0 => epilog(prolog)
            case 1 => f(chunk.head).map(b => epilog(acc(prolog, b, chunk.head)))
            case _ =>
                Loop.indexed(prolog): (idx, b) =>
                    if idx == len then Loop.done(epilog(b))
                    else
                        val current = chunk(idx)
                        f(current).map:
                            case true  => Loop.continue(acc(b, true, current))
                            case false => Loop.done(epilog(acc(b, false, current)))
        end match
    end shiftedWhile

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

    def foreachConcat[A, B, S](source: Set[A])(f: A => IterableOnce[B] < S)(using Frame): Set[B] < S =
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

    def foreachDiscard[A, B, S](source: Set[A])(f: A => Any < S)(using Frame): Unit < S =
        if source.isEmpty then ()
        else
            Loop(source): curSet =>
                if curSet.isEmpty then Loop.done
                else
                    val current = curSet.head
                    f(current).andThen(Loop.continue(curSet - current))
    end foreachDiscard

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

    def partition[S, A](source: Set[A])(f: A => Boolean < S)(using Frame): (Set[A], Set[A]) < S =
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

    def partitionMap[S, A, A1, A2](source: Set[A])(f: A => Either[A1, A2] < S)(using Frame): (Set[A1], Set[A2]) < S =
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

    def scanLeft[S, A, B](source: Set[A])(z: B)(op: (B, A) => B < S)(using Frame): Set[B] < S =
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

    def groupBy[S, A, K](source: Set[A])(f: A => K < S)(using Frame): Map[K, Set[A]] < S =
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

    def groupMap[S, A, K, B](source: Set[A])(key: A => K < S)(f: A => B < S)(using Frame): Map[K, Set[B]] < S =
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

    private[kyo] def shiftedWhile[A, S, B, C](source: Set[A])(
        prolog: B,
        f: A => Boolean < S,
        acc: (B, Boolean, A) => B,
        epilog: B => C
    )(using Frame): C < S =
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

    def foreach[K1, V1, K2, V2, S](source: Map[K1, V1])(f: ((K1, V1)) => (K2, V2) < S)(using Frame): Map[K2, V2] < S =
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

    @targetName("foreachToChunk")
    def foreach[K1, V1, B, S](source: Map[K1, V1])(f: ((K1, V1)) => B < S)(using Frame): Chunk[B] < S =
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

    def foreachConcat[K1, V1, K2, V2, S](source: Map[K1, V1])(f: ((K1, V1)) => IterableOnce[(K2, V2)] < S)(using Frame): Map[K2, V2] < S =
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

    @targetName("foreachConcatToChunk")
    def foreachConcat[K1, V1, B, S](source: Map[K1, V1])(f: ((K1, V1)) => IterableOnce[B] < S)(using Frame): Chunk[B] < S =
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

    def foreachDiscard[K1, V1, S](source: Map[K1, V1])(f: ((K1, V1)) => Any < S)(using Frame): Unit < S =
        if source.isEmpty then ()
        else
            Loop(source): curMap =>
                if curMap.isEmpty then Loop.done
                else
                    val cur = curMap.head
                    f(cur).andThen(Loop.continue(curMap - cur._1))
    end foreachDiscard

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

    def collect[K1, V1, K2, V2, S](source: Map[K1, V1])(f: ((K1, V1)) => Maybe[(K2, V2)] < S)(using Frame): Map[K2, V2] < S =
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

    @targetName("collectToChunk")
    def collect[K1, V1, B, S](source: Map[K1, V1])(f: ((K1, V1)) => Maybe[B] < S)(using Frame): Chunk[B] < S =
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

    def span[K1, V1, S](source: Map[K1, V1])(f: ((K1, V1)) => Boolean < S)(using Frame): (Map[K1, V1], Map[K1, V1]) < S =
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

    def partition[K1, V1, S](source: Map[K1, V1])(f: ((K1, V1)) => Boolean < S)(using Frame): (Map[K1, V1], Map[K1, V1]) < S =
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

    def scanLeft[K1, V1, B, S](source: Map[K1, V1])(z: B)(op: (B, (K1, V1)) => B < S)(using Frame): Chunk[B] < S =
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

    def groupBy[K1, V1, K2, S](source: Map[K1, V1])(f: ((K1, V1)) => K2 < S)(using Frame): Map[K2, Map[K1, V1]] < S =
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

    private[kyo] def shiftedWhile[K1, V1, S, B, C](source: Map[K1, V1])(
        prolog: B,
        f: ((K1, V1)) => Boolean < S,
        acc: (B, Boolean, (K1, V1)) => B,
        epilog: B => C
    )(using Frame): C < S =
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
