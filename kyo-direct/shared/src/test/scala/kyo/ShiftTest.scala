package kyo

import kyo.internal.BaseKyoCoreTest
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec
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
    "Iterable" - {
        val xs: Iterable[Int < Any] = Seq(1, 2, 3, 4)
        val xsValues: Iterable[Int] = Seq(1, 2, 3, 4)
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
        /*"reduceOption" in {
            val d: Option[Int] < Any = defer:
                xs.reduceOption(_.now + _.now).map(_.now)

            assert(d.eval == Option(10))
        }*/
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
                xsValues.dropWhile(f(_).now).toSeq

            assert(d.eval == Seq(3, 4))
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

    "Chunk" - {
        "map < Any" in {
            val xs = Chunk(1, 2, 3)

            def f(i: Int): Int < Any = i + 1
            val d = defer:
                xs.map(i => f(i).now)

            assert(d.eval == Chunk(2, 3, 4))
        }
        "map < Abort" in {
            val xs = Chunk(1, 2, 3)
            def f(i: Int): Int < Abort[String] =
                if i == 2 then Abort.fail("2") else i

            val d = defer:
                xs.map(i => f(i).now)

            assert(Abort.run(d).eval == Result.fail("2"))
        }

        val xs = Chunk.fill(n = 0xffff + 1)(elem = 2)
        "find" in {
            def f(i: Int): Boolean < Any = i == 2
            val d = defer:
                xs.find(i => f(i).now)

            assert(d.eval == Option(2))
        }
        "count" in {
            def f(i: Int): Boolean < Any = i > 0
            val d: Int < Any = defer:
                xs.count(i => f(i).now)

            assert(d.eval == 65536)
        }
        "foldLeft" in {
            def f(i: Int): Int < Any = i + 1
            val d = defer:
                xs.foldLeft(0)((acc, e) => acc + f(e).now)

            assert(d.eval == 196608)
        }

    }

    "Array" - {
        "foldLeft" in {
            val it: Iterable[Int] = Array(1, 4)

            val zeroOrOne: Int < Choice = Choice.eval(List(0, 1))

            val effect: Int < kyo.Choice = defer:
                it.foldLeft(0)((acc, n) => acc + zeroOrOne.now)

            val result = Choice.run(effect).eval

            assert(result.contains(0))
            assert(result.contains(1))
            assert(result.contains(2))
            assert(result.size == 4)
        }

    }

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

    "IterableOnce" - {
        "foldLeft" in {
            val it: Iterable[Int] = oneShot(1, 2, 3)

            val zeroOrOne: Int < Choice = Choice.eval(List(0, 1))

            val effect: Int < kyo.Choice = defer:
                it.foldLeft(0)((acc, n) => acc + zeroOrOne.now)

            val result = Choice.run(effect).eval

            assert(result.contains(0))
            assert(result.contains(1))
            assert(result.contains(2))
            assert(result.contains(3))
            assert(result.size == 8)
        }
    }
    "Choice" - {

        "foreach" in {

            val result = Choice.run(
                defer:
                    List("x", "y").map(str =>
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
                List("x", "y").map { str =>
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

        "foldLeft" in {
            val result = Choice.run(
                defer:
                    List(1, 1).foldLeft(0)((acc, _) => Choice.eval(List(0, 1)).map(n => acc + n).now)
            ).eval

            assert(result.contains(0))
            assert(result.contains(1))
            assert(result.contains(2))
            assert(result.size == 4)
        }

        /*
        //failing
        "foreach - vector" in {
            val result = Choice.run(
                defer:
                    Vector("x", "y").map(str =>
                        Choice.eval(Seq(true, false)).map(b =>
                            if b then str.toUpperCase else str
                        ).now
                    )
            ).eval

            assert(result.contains(Chunk("X", "Y")))
            assert(result.contains(Chunk("X", "y")))
            assert(result.contains(Chunk("x", "Y")))
            assert(result.contains(Chunk("x", "y")))
            assert(result.size == 4)
        }*/
        // failing
        "collect - Vector" in {
            val effectVector =
                Vector("xx", "yy").map { str =>
                    Choice.eval(Seq(true, false)).map(b =>
                        if b then str.toUpperCase else str
                    )
                }
            val result = Choice.run(defer(effectVector.map(_.now))).eval

            assert(result.contains(Chunk("XX", "YY")))
            assert(result.contains(Chunk("XX", "yy")))
            assert(result.contains(Chunk("xx", "YY")))
            assert(result.contains(Chunk("xx", "yy")))
            assert(result.size == 4)
        }

        "collect - Vector as Seq" in {
            val effects: Seq[String < Choice] =
                Vector("x", "y").map { str =>
                    Choice.eval(Seq(true, false)).map(b =>
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

        "collect - List" in {
            val effects =
                List("x", "y").map { str =>
                    Choice.eval(Seq(true, false)).map(b =>
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

        "foldLeft - array" in {
            val result = Choice.run(
                defer:
                    Array(1, 1).foldLeft(0)((acc, _) =>
                        Choice.eval(Seq(0, 1)).map(n => acc + n).now
                    )
            ).eval

            assert(result.contains(0))
            assert(result.contains(1))
            assert(result.contains(2))
            assert(result.size == 4)
        }
    }

end ShiftMethodSupportTest
