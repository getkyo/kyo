package kyo

import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec
import scala.annotation.nowarn

@TestVariant("Coll", "List", "Vector", "Chunk", "Iterable", "IterableOneShot", "Set")
class CollShiftMethodSupportTest extends AnyFreeSpec with Assertions:

    extension [A](left: Iterable[A])
        @nowarn
        def ~==(right: Iterable[A]): Boolean =
            given CanEqual[A, A] = CanEqual.derived
            left match
                case set: Set[A] => set == right.toSet
                case _           => left.toSeq == right.toSeq
    end extension

    @TestVariant("Seq[X]", "List[X]", "Vector[X]", "Chunk[X]", "Iterable[X]", "Iterable[X]", "Set[X]")
    type Coll[X] = Seq[X]

    @TestVariant("x.toSeq", "x.toList", "x.toVector", "Chunk.from(x)", "x", "IterableTest.oneShot(x*)", "x.toSet")
    def Coll[X](x: X*): Coll[X] = x.toSeq

    // @TestVariant("seq", "list", "vector", "chunk", "iterable", "iterableOneShot", "set)
    "seq" - {
        def xs: Coll[Int < Any] = Coll(1, 2, 3, 4)
        def xsValues: Coll[Int] = Coll(1, 2, 3, 4)

        "collectFirst" in {
            val d: Option[Int] < Any = direct:
                xs.collectFirst:
                    case i => i.now
            d.map(res => assert(res == Option(1)))
        }
        "foreach" in {
            val d: Unit < Emit[Int] = direct:
                xs.foreach(i =>
                    val v = i.now
                    Emit.value(v).unit.now
                )

            Emit.run(d).map((vs, _) => assert(vs == Seq(1, 2, 3, 4)))
        }
        "corresponds" in {
            val d: Boolean < Any = direct:
                xs.corresponds(xs)((a, b) => a.now == b.now)

            assert(d.eval)
        }
        "count" in {
            val d: Int < Any = direct:
                xs.count(_.now > 0)

            assert(d.eval == 4)
        }
        "exists" in {
            val d: Boolean < Any = direct:
                xs.exists(_.now > 0)

            assert(d.eval)
        }
        "forall" in {
            val d: Boolean < Any = direct:
                xs.forall(_.now > 0)

            assert(d.eval)
        }
        "find" in {
            val d: Option[Int] < Any = direct:
                xs.find(_.now > 2).map(_.now)

            assert(d.eval == Option(3))
        }
        "fold" in {
            val d: Int < Any = direct:

                def inc(i: Int): Int < Any = (i + 1).later

                xsValues.fold[Int](0)((a, b) => inc(a + b).now)

            assert(d.eval == 14)
        }
        "foldLeft" in {
            val d = direct:
                xs.foldLeft(0)(_ + _.now)

            assert(d.eval == 10)
        }
        "foldRight" in {
            val d = direct:
                xs.foldRight(0)(_.now + _)

            assert(d.eval == 10)
        }
        "groupMapReduce" in {
            val d = direct:
                xs.groupMapReduce(_ => 1)(_.now)(_ + _)

            assert(d.eval == Map(1 -> 10))
        }
        "maxByOpOption" in {
            val d: Option[Int] < Any = direct:
                xs.maxByOption(_.now).map(_.now)

            assert(d.eval == Option(4))
        }
        "map" in {
            val d: Seq[Int] < Any = direct:
                xs.map(_.now).toSeq

            assert(d.eval == Seq(1, 2, 3, 4))
        }
        "flatMap" in {
            val d: Seq[Int] < Any = direct:

                def f(i: Int) = Seq(1)

                xs.flatMap(i => f(i.now)).toSeq.distinct

            assert(d.eval == Seq(1))
        }
        "collect" in {
            val d: Seq[Int] < Any = direct:
                xs.collect({
                    case i => i.now
                }).toSeq

            assert(d.eval == Seq(1, 2, 3, 4))
        }
        "map - List" in {
            val d: List[Int] < Any = direct:
                xs.map(_.now).toList

            assert(d.eval == List(1, 2, 3, 4))
        }
        "flatMap - List" in {
            val d: List[Int] < Any = direct:

                def f(i: Int) = List(1)

                xs.flatMap(i => f(i.now)).toList.distinct

            assert(d.eval == List(1))
        }
        "collect - List" in {
            val d: List[Int] < Any = direct:
                xs.collect({
                    case i => i.now
                }).toList

            assert(d.eval == List(1, 2, 3, 4))
        }
        "map - Vector" in {
            val d: Vector[Int] < Any = direct:
                xs.map(_.now).toVector

            assert(d.eval == Vector(1, 2, 3, 4))
        }
        "flatMap - Vector" in {
            val d: Vector[Int] < Any = direct:

                def f(i: Int) = Vector(1)

                xs.flatMap(i => f(i.now)).toVector.distinct

            assert(d.eval == Vector(1))
        }
        "collect - Vector" in {
            val d: Vector[Int] < Any = direct:
                xs.collect({
                    case i => i.now
                }).toVector

            assert(d.eval == Vector(1, 2, 3, 4))
        }
        "dropWhile" in {
            def f(i: Int): Boolean < Any = i < 3

            val d = direct:
                Seq(1, 2, 3, 4).dropWhile(f(_).now).toSeq

            assert(d.eval == xsValues.dropWhile(_ < 3).toSeq)
        }
        "filter" in {
            def f(i: Int): Boolean < Any = i < 3

            val d = direct:
                xsValues.filter(i => f(i).now).toSeq

            assert(d.eval == Seq(1, 2))
        }
        "filterNot" in {
            def f(i: Int): Boolean < Any = i < 3

            val d = direct:
                xsValues.filterNot(i => f(i).now).toSeq

            assert(d.eval == Seq(3, 4))
        }
        "groupBy" in {
            def f(i: Int): Boolean < Any = i < 3

            val d = direct:
                xsValues.groupBy(i => f(i).now).view.mapValues(_.toSeq).toMap

            assert(d.eval == Map(true -> Seq(1, 2), false -> Seq(3, 4)))
        }
        "groupMap" in {
            def f(i: Int): Boolean < Any = i < 3

            // TODO : catch xsValues.groupMap(i => f(i))( ...
            val d = direct:
                xsValues.groupMap(i => f(i).now)(i => f(i).now).view.mapValues(_.toSeq.distinct).toMap

            assert(d.eval == Map(true -> Seq(true), false -> Seq(false)))
        }
        "scanLeft" in {
            val d = direct:
                xs.scanLeft(0)((b, e) => b + e.now)

            assert(d.eval ~== Seq(0, 1, 3, 6, 10))
        }
        "scanRight" in {
            val d = direct:
                xs.scanRight(0)((e, b) => e.now + b)

            assert(d.eval ~== Seq(10, 9, 7, 4, 0))
        }
        "span" in {
            def f(i: Int): Boolean < Any = i < 3

            val d = direct:
                val (take, _) = xsValues.span(i => f(i).now)
                take.toSeq

            assert(d.eval == Seq(1, 2))
        }
        "takeWhile" in {
            def f(i: Int): Boolean < Any = i < 3

            val d = direct:
                xsValues.takeWhile(i => f(i).now).toSeq

            assert(d.eval == Seq(1, 2))
        }
        "partition" in {
            def f(i: Int): Boolean < Any = i < 3

            val d = direct:
                val (pTrue, _) = xsValues.partition(i => f(i).now)
                pTrue.toSeq

            assert(d.eval == Seq(1, 2))
        }
        "partitionMap" in {
            def f(i: Int): Either[String, Int] < Any = if i < 3 then
                Left(i.toString)
            else Right(i)

            val d = direct:
                val (pLeft, pRight) = xsValues.partitionMap(i => f(i).now)
                pLeft.toSeq

            assert(d.eval == Seq("1", "2"))
        }
        "tapEach" in {
            def f(i: Int): Unit < Emit[Int] = Emit.value(i)

            val d: Seq[Int] < Emit[Int] = direct:
                xsValues.tapEach(i => f(i).now).toSeq

            val (es, res) = Emit.run(d).eval
            assert(es == res)
        }
        "withFilter" in {
            def f(i: Int): Boolean < Any = i < 3

            val d = direct:
                xsValues.withFilter(i => f(i).now).map(x => x).toSeq

            assert(d.eval == Seq(1, 2))
        }

        "Choice" - {
            "map" in {
                val result = Choice.run(
                    direct:
                        Coll("x", "y").map(str =>
                            Choice.eval(true, false)
                                .map(b => if b then str.toUpperCase else str).now
                        )
                ).eval

                assert(result.exists(_ ~== Chunk("X", "Y")))
                assert(result.exists(_ ~== Chunk("X", "y")))
                assert(result.exists(_ ~== Chunk("x", "Y")))
                assert(result.exists(_ ~== Chunk("x", "y")))
                assert(result.size == 4)
            }

            "collect" in {
                val effects =
                    Coll("x", "y").map { str =>
                        Choice.eval(true, false).map(b =>
                            if b then str.toUpperCase else str
                        )
                    }
                val result = Choice.run(direct(effects.map(_.now))).eval

                assert(result.exists(_ ~== Chunk("X", "Y")))
                assert(result.exists(_ ~== Chunk("X", "y")))
                assert(result.exists(_ ~== Chunk("x", "Y")))
                assert(result.exists(_ ~== Chunk("x", "y")))
                assert(result.size == 4)
            }

            "flatMap" in {
                val effects = direct:
                    val tf = Choice.eval(true, false).later
                    Coll("x", "y").flatMap(str1 =>
                        val pred1 = tf.now
                        Coll("z").map(str2 =>
                            val pred2 = tf.now
                            if pred1 & pred2 then
                                (str1 + str2).toUpperCase
                            else if pred1 != pred2 then
                                Choice.drop.now
                            else (str1 + str2)
                            end if
                        )
                    )

                val result = Choice.run(effects).eval
                assert(result.exists(_ ~== Chunk("XZ", "YZ")))
                assert(result.exists(_ ~== Chunk("XZ", "yz")))
                assert(result.exists(_ ~== Chunk("xz", "YZ")))
                assert(result.exists(_ ~== Chunk("xz", "yz")))
                assert(result.size == 4)
            }

            "foldLeft" in {
                val result = Choice.run(
                    direct:
                        Coll(1, 3).foldLeft(0)((acc, _) => Choice.eval(0, 1).map(n => acc + n).now)
                ).eval

                assert(result.contains(0))
                assert(result.contains(1))
                assert(result.contains(2))
                assert(result.size == 4)
            }
        }
    }

    //  @TestVariant("Coll", "List", "Vector", "Chunk", "Iterable", "IterableOneShot", "Set")
end CollShiftMethodSupportTest
