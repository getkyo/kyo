package kyo

import kyo.internal.BaseKyoCoreTest
import org.scalatest.Assertions
import org.scalatest.color
import org.scalatest.freespec.AnyFreeSpec
import scala.collection
import scala.collection.Factory
import scala.collection.IterableOps
import scala.util.Try

class ShiftHygieneTest extends Test:
    "invalid nested" in {
        typeCheckFailure(
            """
               val x1: Seq[Int < Any] = Seq[Int < Any](1, 2, 3)
               val x2 = defer {
                 x1.map(x =>
                   def innerF = x.now
                   innerF)
                 }"""
        )(
            "Method definitions containing .now are not supported inside `defer` blocks"
        )
    }
end ShiftHygieneTest

class ShiftTest extends AnyFreeSpec with Assertions:

    "basic" in {
        val x1: Seq[Int < Any] = Seq[Int < Any](1, 2, 3)
        val x2: Seq[Int] < Any = defer:
            x1.map(_.now)

        assert(x2.eval == Seq(1, 2, 3))
    }

    "error" in {
        val x1: Seq[Int < Abort[String]] = Seq(1, Abort.fail("error"), 3)
        val x2: Seq[Int] < Abort[String] = defer:
            x1.map(_.now)

        Abort.run(x2).map: result =>
            assert(result == Result.fail("error"))
    }

    "valid nested" in {
        val x1: Seq[Int < Any] = Seq[Int < Any](1, 2, 3)
        val x2 = defer:
            x1.map(x =>
                def innerF(i: Int) = i + 1
                innerF(x.now)
            )

        val forReference = defer:
            def innerF(i: Int) = i + 1
            IO(innerF(1)).now

    }

end ShiftTest

class ShiftMethodSupportTest extends AnyFreeSpec with Assertions:

    def iterableTests[Coll[X] <: Iterable[X] & IterableOps[X, Coll, Coll[X]]](
        name: String,
        builder: [X] => Seq[X] => Coll[X]
    ): Unit =
        def Coll[X](x: X*): Coll[X] = builder(x)

        name - {
            def xs: Coll[Int < Any] = Coll(1, 2, 3, 4)
            def xsValues: Coll[Int] = Coll(1, 2, 3, 4)

            "collectFirst" in {
                val d: Option[Int] < Any = defer:
                    xs.collectFirst:
                        case i => i.now
                d.map(res => assert(res == Option(1)))
            }
            "foreach" in {
                val d: Unit < Emit[Int] = defer:
                    xs.foreach(i =>
                        val v = i.now
                        Emit.value(v).unit.now
                    )

                Emit.run(d).map((vs, _) => assert(vs == Seq(1, 2, 3, 4)))
            }
            "corresponds" in {
                val d: Boolean < Any = defer:
                    xs.corresponds(xs)((a, b) => a.now == b.now)

                assert(d.eval)
            }
            "count" in {
                val d: Int < Any = defer:
                    xs.count(_.now > 0)

                assert(d.eval == 4)
            }
            "exists" in {
                val d: Boolean < Any = defer:
                    xs.exists(_.now > 0)

                assert(d.eval)
            }
            "forall" in {
                val d: Boolean < Any = defer:
                    xs.forall(_.now > 0)

                assert(d.eval)
            }
            "find" in {
                val d: Option[Int] < Any = defer:
                    xs.find(_.now > 2).map(_.now)

                assert(d.eval == Option(3))
            }
            "fold" in {
                val d: Int < Any = defer:

                    def inc(i: Int): Int < Any = (i + 1).later

                    xsValues.fold[Int](0)((a, b) => inc(a + b).now)

                assert(d.eval == 14)
            }
            "foldLeft" in {
                val d = defer:
                    xs.foldLeft(0)(_ + _.now)

                assert(d.eval == 10)
            }
            "foldRight" in {
                val d = defer:
                    xs.foldRight(0)(_.now + _)

                assert(d.eval == 10)
            }
            "groupMapReduce" in {
                val d = defer:
                    xs.groupMapReduce(_ => 1)(_.now)(_ + _)

                assert(d.eval == Map(1 -> 10))
            }
            "maxByOpOption" in {
                val d: Option[Int] < Any = defer:
                    xs.maxByOption(_.now).map(_.now)

                assert(d.eval == Option(4))
            }
            "map" in {
                val d: Seq[Int] < Any = defer:
                    xs.map(_.now).toSeq

                assert(d.eval == Seq(1, 2, 3, 4))
            }
            "flatMap" in {
                val d: Seq[Int] < Any = defer:

                    def f(i: Int) = Seq(1)

                    xs.flatMap(i => f(i.now)).toSeq.distinct

                assert(d.eval == Seq(1))
            }
            "collect" in {
                val d: Seq[Int] < Any = defer:
                    xs.collect({
                        case i => i.now
                    }).toSeq

                assert(d.eval == Seq(1, 2, 3, 4))
            }
            "dropWhile" in {
                def f(i: Int): Boolean < Any = i < 3

                val d = defer:
                    Seq(1, 2, 3, 4).dropWhile(f(_).now).toSeq

                assert(d.eval == xsValues.dropWhile(_ < 3).toSeq)
            }
            "filter" in {
                def f(i: Int): Boolean < Any = i < 3

                val d = defer:
                    xsValues.filter(i => f(i).now).toSeq

                assert(d.eval == Seq(1, 2))
            }
            "filterNot" in {
                def f(i: Int): Boolean < Any = i < 3

                val d = defer:
                    xsValues.filterNot(i => f(i).now).toSeq

                assert(d.eval == Seq(3, 4))
            }
            "groupBy" in {
                def f(i: Int): Boolean < Any = i < 3

                val d = defer:
                    xsValues.groupBy(i => f(i).now).view.mapValues(_.toSeq).toMap

                assert(d.eval == Map(true -> Seq(1, 2), false -> Seq(3, 4)))
            }
            "groupMap" in {
                def f(i: Int): Boolean < Any = i < 3

                // TODO : catch xsValues.groupMap(i => f(i))( ...
                val d = defer:
                    xsValues.groupMap(i => f(i).now)(i => f(i).now).view.mapValues(_.toSeq.distinct).toMap

                assert(d.eval == Map(true -> Seq(true), false -> Seq(false)))
            }
            "scanLeft" in {
                val d = defer:
                    xs.scanLeft(0)((b, e) => b + e.now).toSeq

                assert(d.eval == Seq(0, 1, 3, 6, 10))
            }
            "scanRight" in {
                val d = defer:
                    xs.scanRight(0)((e, b) => e.now + b).toSeq

                assert(d.eval == Seq(10, 9, 7, 4, 0))
            }
            "span" in {
                def f(i: Int): Boolean < Any = i < 3

                val d = defer:
                    val (take, drop) = xsValues.span(i => f(i).now)
                    take.toSeq

                assert(d.eval == Seq(1, 2))
            }
            "takeWhile" in {
                def f(i: Int): Boolean < Any = i < 3

                val d = defer:
                    xsValues.takeWhile(i => f(i).now).toSeq

                assert(d.eval == Seq(1, 2))
            }
            "partition" in {
                def f(i: Int): Boolean < Any = i < 3

                val d = defer:
                    val (pTrue, _) = xsValues.partition(i => f(i).now)
                    pTrue.toSeq

                assert(d.eval == Seq(1, 2))
            }
            "partitionMap" in {
                def f(i: Int): Either[String, Int] < Any = if i < 3 then
                    Left(i.toString)
                else Right(i)

                val d = defer:
                    val (pLeft, pRight) = xsValues.partitionMap(i => f(i).now)
                    pLeft.toSeq

                assert(d.eval == Seq("1", "2"))
            }
            "tapEach" in {
                def f(i: Int): Unit < Emit[Int] = Emit.value(i)

                val d: Seq[Int] < Emit[Int] = defer:
                    xsValues.tapEach(i => f(i).now).toSeq

                val (es, res) = Emit.run(d).eval
                assert(es == res)
            }
            "withFilter" in {
                def f(i: Int): Boolean < Any = i < 3

                val d = defer:
                    xsValues.withFilter(i => f(i).now).map(x => x).toSeq

                assert(d.eval == Seq(1, 2))
            }

            "Choice" - {
                "map" in {
                    val result = Choice.run(
                        defer:
                            Coll("x", "y").map(str =>
                                Choice.eval(List(true, false))
                                    .map(b => if b then str.toUpperCase else str).now
                            )
                    ).eval

                    assert(result.contains(Chunk("X", "Y")))
                    assert(result.contains(Chunk("X", "y")))
                    assert(result.contains(Chunk("x", "Y")))
                    assert(result.contains(Chunk("x", "y")))
                    assert(result.size == 4)
                }

                "collect" in {
                    val effects =
                        Coll("x", "y").map { str =>
                            Choice.eval(List(true, false)).map(b =>
                                if b then str.toUpperCase else str
                            )
                        }
                    val result = Choice.run(defer(effects.map(_.now))).eval

                    assert(result.contains(Chunk("X", "Y")))
                    assert(result.contains(Chunk("X", "y")))
                    assert(result.contains(Chunk("x", "Y")))
                    assert(result.contains(Chunk("x", "y")))
                    assert(result.size == 4)
                }

                "flatMap" in {
                    val effects = defer:
                        val tf = Choice.eval(List(true, false)).later
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
                    assert(result.contains(Chunk("XZ", "YZ")))
                    assert(result.contains(Chunk("XZ", "yz")))
                    assert(result.contains(Chunk("xz", "YZ")))
                    assert(result.contains(Chunk("xz", "yz")))
                    assert(result.size == 4)
                }

                "foldLeft" in {
                    val result = Choice.run(
                        defer:
                            Coll(1, 1).foldLeft(0)((acc, _) => Choice.eval(List(0, 1)).map(n => acc + n).now)
                    ).eval

                    assert(result.contains(0))
                    assert(result.contains(1))
                    assert(result.contains(2))
                    assert(result.size == 4)
                }
            }
        }
    end iterableTests

    iterableTests[Iterable]("Iterable", [X] => seq => seq)
    iterableTests[Vector]("Vector", [X] => seq => Vector.from(seq))
    iterableTests[Chunk]("Chunk", [X] => seq => Chunk.from(seq))
    iterableTests[List]("List", [X] => seq => List.from(seq))

    // fake Iterable
    def oneShot[A](a: A*): Iterable[A] =
        val used = new java.util.concurrent.atomic.AtomicBoolean(false)
        new Iterable[A]:
            def iterator: Iterator[A] =
                if !used.compareAndSet(false, true) then
                    throw new IllegalStateException("Already consumed!")
                else Iterator(a*)

        end new
    end oneShot

    iterableTests[Iterable]("IterableOnce", [X] => seq => oneShot(seq*))

    "Option" - {
        val x: Option[Int < Any] = Option(1)
        val y: Option[Int]       = Option(1)
        val z: Option[Int] < Any = None

        "filter" in {
            def f(i: Int): Boolean < Any = i < 3
            val d = defer:
                y.filter(i => f(i).now)

            assert(d.eval == Option(1))
        }

        "orElse" in {
            val d = defer:
                y.orElse(z.now)

            assert(d.eval == Option(1))
        }

        "getOrElse" in {
            val w: Int < Any = 3
            val d = defer:
                z.now.getOrElse(w.now)

            assert(d.eval == 3)
        }

        "map" in {
            val d = defer:
                x.map(_.now + 1)

            assert(d.eval == Option(2))
        }
    }

    "Try" - {
        val x: Try[Int < Any] = Try(1)
        val y: Try[Int]       = Try(1)
        val z: Try[Int]       = Try(throw new Exception("z"))

        given [X]: CanEqual[Try[X], Try[X]] = CanEqual.derived

        "filter" in {
            def f(i: Int): Boolean < Any = i < 3
            val d = defer:
                y.filter(i => f(i).now)

            assert(d.eval == Try(1))
        }

        "orElse" in {
            val default: Try[Int] < Any = Try(2)

            val d = defer:
                z.orElse(default.now)

            assert(d.eval == Try(2))
        }

        "getOrElse" in {
            val default: Int < Any = 2
            val d = defer:
                z.getOrElse(default.now)

            assert(d.eval == 2)
        }
    }
end ShiftMethodSupportTest
