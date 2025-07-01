package kyo

import Tagged.*
import kyo.kernel.*
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.collection.Iterable
import scala.collection.IterableOps

class KyoTest extends Test:

    sealed trait TestEffect1 extends ArrowEffect[Const[Int], Const[Int]]
    object TestEffect1:
        def apply(i: Int): Int < TestEffect1 =
            ArrowEffect.suspend[Any](Tag[TestEffect1], i)

        def run[A, S](v: A < (TestEffect1 & S)): A < S =
            ArrowEffect.handle(Tag[TestEffect1], v)([C] => (input, cont) => cont(input + 1))
    end TestEffect1

    sealed trait TestEffect2 extends ArrowEffect[Const[String], Const[String]]
    object TestEffect2:
        def apply(s: String): String < TestEffect2 =
            ArrowEffect.suspend[Any](Tag[TestEffect2], s)

        def run[A, S](v: A < (TestEffect2 & S)): A < S =
            ArrowEffect.handle(Tag[TestEffect2], v)([C] => (input, cont) => cont(input.toUpperCase))
    end TestEffect2

    def widen[A](v: A): A < Any = v

    "toString" in run {
        assert(TestEffect1(1).map(_ + 1).toString ==
            "Kyo(kyo.KyoTest.TestEffect1, Input(1), KyoTest.scala:33:41, assert(TestEffect1(1).map(_ + 1))")
        assert(
            TestEffect1(1).map(_ + 1).map(_ + 2).toString ==
                "Kyo(kyo.KyoTest.TestEffect1, Input(1), KyoTest.scala:36:49, TestEffect1(1).map(_ + 1).map(_ + 2))"
        )
    }

    "eval" in {
        assert(TestEffect1.run(TestEffect1(1).map(_ + 1)).eval == 3)
        typeCheckFailure("TestEffect1(1).eval")(
            "value eval is not a member of Int < KyoTest.this.TestEffect1"
        )
        assert(widen(TypeMap(1, true)).eval.get[Boolean])
    }

    "eval widened" in {
        val x = widen(TestEffect1(1).map(_ + 1)).eval
        val y = TestEffect1.run(x).eval
        assert(y == 3)
    }

    "map" in {
        assert(TestEffect1.run(TestEffect1(1).map(_ + 1)).eval == 3)
        assert(TestEffect1.run(TestEffect1(1).map(v => TestEffect1(v + 1))).eval == 4)
    }

    "flatMap" in {
        assert(TestEffect1.run(TestEffect1(1).flatMap(_ + 1)).eval == 3)
        assert(TestEffect1.run(TestEffect1(1).flatMap(v => TestEffect1(v + 1))).eval == 4)
    }

    "unit" in {
        assert(TestEffect2.run(TestEffect2("test").unit).eval == ())
    }

    "flatten" in {
        def test[A](v: A)                      = TestEffect1(1).map(_ => v)
        val a: Int < TestEffect1 < TestEffect1 = test(TestEffect1(1))
        val b: Int < TestEffect1               = a.flatten
        assert(TestEffect1.run(b).eval == 2)
    }

    "andThen" in {
        assert(TestEffect1.run(TestEffect1(1).andThen(2)).eval == 2)
    }

    "zip" in {
        val result1 = TestEffect1.run(
            Kyo.zip(TestEffect1(1), TestEffect1(2))
        ).eval
        assert(result1 == (2, 3))

        val result2 = TestEffect1.run(
            Kyo.zip(TestEffect1(1), TestEffect1(2), TestEffect1(3))
        ).eval
        assert(result2 == (2, 3, 4))

        val result3 = TestEffect2.run(TestEffect1.run(
            Kyo.zip(TestEffect1(1), TestEffect2("test"), TestEffect1(3))
        )).eval
        assert(result3 == (2, "TEST", 4))
    }

    "nested" - {
        def lift[A](v: A): A < Any                                          = widen(v)
        def add(v: Int < TestEffect1)                                       = v.map(_ + 1)
        def transform[A, B](v: A < TestEffect1, f: A => B): B < TestEffect1 = v.map(f(_))
        val io: Int < TestEffect1 < TestEffect1                             = lift(TestEffect1(1))

        "map + flatten" in {
            val a: Int < TestEffect1 < TestEffect1 =
                transform[Int < TestEffect1, Int < TestEffect1](io, add(_))
            val b: Int < TestEffect1 = a.flatten
            assert(TestEffect1.run(b).eval == 3)
        }

        "eval" in {
            val x = TestEffect1.run(io).eval
            val y = TestEffect1.run(x).eval
            assert(y == 2)
        }
    }

    "stack safety" - {
        val n = 100000

        "recursive method" - {
            def countDown[S](v: Int < S): Int < S =
                v.map {
                    case 0 => 0
                    case n => countDown(n - 1)
                }

            "no effect" in {
                assert(countDown(n).eval == 0)
            }

            "effect at the start" in {
                assert(TestEffect1.run(countDown(TestEffect1(n))).eval == 0)
            }

            "effect at the end" in {
                assert(TestEffect1.run(countDown(n).map(n => TestEffect1(n + n))).eval == 1)
            }

            "multiple effects" in {
                def countDown(v: Int < TestEffect1): Int < TestEffect1 =
                    v.map {
                        case 0 => 0
                        case n if n % 32 == 0 => countDown(n - 1).map(TestEffect1(_))
                        case n => countDown(n - 1)
                    }
                assert(TestEffect1.run(countDown(n)).eval == 3125)
            }
        }

        "dynamic computation" - {
            @tailrec def incr[S](v: Int < S, n: Int): Int < S =
                n match
                    case 0 => v
                    case n => incr(v.map(_ + 1), n - 1)

            "no effect" in {
                assert(incr(0, n).eval == n)
            }

            "suspension at the start" taggedAs notNative in pendingUntilFixed {
                try
                    assert(TestEffect1.run(incr(TestEffect1(n), n)).eval == 0)
                catch
                    case ex: StackOverflowError => fail()
                end try
                ()
            }

            "effect at the end" in {
                assert(TestEffect1.run(incr(n, n).map(n => TestEffect1(n + n))).eval == n * 4 + 1)
            }

            "multiple effects" taggedAs notNative in pendingUntilFixed {
                @tailrec def incr(v: Int < TestEffect1, n: Int): Int < TestEffect1 =
                    n match
                        case 0 => v
                        case n if n % 32 == 0 =>
                            incr(v.map(v => TestEffect1(v + 1)), n - 1)
                        case n => incr(v.map(_ + 1), n - 1)

                try assert(TestEffect1.run(incr(0, n)).eval == 0)
                catch
                    case ex: StackOverflowError => fail()
                end try
                ()
            }
        }
    }

    "when" - {
        "true" in {
            val trueEffect = Kyo.when(Kyo.lift(true))(Kyo.lift(1), Kyo.lift(2))
            assert(trueEffect.eval == 1)
        }
        "false" in {
            val falseEffect = Kyo.when(Kyo.lift(false))(Kyo.lift(1), Kyo.lift(2))
            assert(falseEffect.eval == 2)
        }
        "effectful true" in {
            val trueEffect = Kyo.when(TestEffect1(1).andThen(true))(TestEffect1(2), TestEffect1(10))
            assert(TestEffect1.run(trueEffect).eval == 3)
        }
        "effectful false" in {
            val falseEffect = Kyo.when(TestEffect1(1).andThen(false))(TestEffect1(2), TestEffect1(10))
            assert(TestEffect1.run(falseEffect).eval == 11)
        }
        "single branch" - {
            "true" in {
                val trueEffect = Kyo.when(Kyo.lift(true))(Kyo.lift(1))
                assert(trueEffect.eval == Present(1))
            }
            "false" in {
                val falseEffect = Kyo.when(Kyo.lift(false))(Kyo.lift(1))
                assert(falseEffect.eval == Absent)
            }
            "effectful true" in {
                val trueEffect = Kyo.when(TestEffect1(1).andThen(true))(TestEffect1(2))
                assert(TestEffect1.run(trueEffect).eval == Present(3))
            }
            "effectful false" in {
                val falseEffect = Kyo.when(TestEffect1(1).andThen(false))(TestEffect1(2))
                assert(TestEffect1.run(falseEffect).eval == Absent)
            }
        }
    }

    "unless" - {
        "true" in {
            val trueEffect = Kyo.unless(Kyo.lift(true))(Kyo.lift(1))
            assert(trueEffect.eval == Absent)
        }
        "false" in {
            val falseEffect = Kyo.unless(Kyo.lift(false))(Kyo.lift(1))
            assert(falseEffect.eval == Present(1))
        }
        "effectful true" in {
            val trueEffect = Kyo.unless(TestEffect1(1).andThen(true))(TestEffect1(2))
            assert(TestEffect1.run(trueEffect).eval == Absent)
        }
        "effectful false" in {
            val falseEffect = Kyo.unless(TestEffect1(1).andThen(false))(TestEffect1(2))
            assert(TestEffect1.run(falseEffect).eval == Present(3))
        }
    }

    "seq" - {
        "collect" in {
            assert(Kyo.collectAll(Seq.empty).eval == Chunk.empty)
            assert(TestEffect1.run(Kyo.collectAll(Seq(TestEffect1(1))).map(_.head)).eval == 2)
            assert(TestEffect2.run(TestEffect1.run(Kyo.collectAll(Seq(TestEffect1(1), TestEffect1(2))).map(c => (c(0), c(1))))).eval == (
                2,
                3
            ))
            assert(TestEffect1.run(Kyo.collectAll(Seq.fill(100)(TestEffect1(1))).map(_.size)).eval == 100)
            assert(TestEffect2.run(TestEffect1.run(Kyo.collectAll(List(TestEffect1(1), TestEffect1(2), TestEffect1(3))).map(c =>
                (c(0), c(1), c(2))
            ))).eval == (2, 3, 4))
            assert(TestEffect2.run(TestEffect1.run(Kyo.collectAll(Vector(TestEffect1(1), TestEffect1(2), TestEffect1(3))).map(c =>
                (c(0), c(1), c(2))
            ))).eval == (2, 3, 4))
        }

        "collectDiscard" in {
            var count = 0
            val io    = TestEffect1(1).map(_ => count += 1)
            TestEffect1.run(Kyo.collectAllDiscard(Seq.empty)).eval
            assert(count == 0)
            TestEffect1.run(Kyo.collectAllDiscard(Seq(io))).eval
            assert(count == 1)
            TestEffect1.run(Kyo.collectAllDiscard(List.fill(42)(io))).eval
            assert(count == 43)
            TestEffect1.run(Kyo.collectAllDiscard(Vector.fill(10)(io))).eval
            assert(count == 53)
        }

        "foreach" in {
            assert(TestEffect1.run(Kyo.foreach(Seq.empty[Int])(i => TestEffect1(i))).eval == Chunk.empty)
            assert(TestEffect1.run(Kyo.foreach(Seq(1))(i => TestEffect1(i))).map(_.head).eval == 2)
            assert(TestEffect1.run(Kyo.foreach(Seq(1, 2))(i => TestEffect1(i))).map(c => (c(0), c(1))).eval == (2, 3))
            assert(TestEffect1.run(Kyo.foreach(Seq.fill(100)(1))(i => TestEffect1(i))).map(_.size).eval == 100)
        }

        "foreachDiscard" in {
            var acc = Seq.empty[Int]
            TestEffect1.run(Kyo.foreachDiscard(Seq.empty[Int])(v => TestEffect1(v).map(i => acc :+= i))).eval
            assert(acc == Chunk.empty)
            acc = Seq.empty
            TestEffect1.run(Kyo.foreachDiscard(Seq(1))(v => TestEffect1(v).map(i => acc :+= i))).eval
            assert(acc == Seq(2))
            acc = Seq.empty
            TestEffect1.run(Kyo.foreachDiscard(Seq(1, 2))(v => TestEffect1(v).map(i => acc :+= i))).eval
            assert(acc == Seq(2, 3))
        }

        "foldLeft" in {
            assert(TestEffect1.run(Kyo.foldLeft(Seq.empty[Int])(0)((acc, i) => TestEffect1(acc + i))).eval == 0)
            assert(TestEffect1.run(Kyo.foldLeft(Seq(1))(0)((acc, i) => TestEffect1(acc + i))).eval == 2)
            assert(TestEffect1.run(Kyo.foldLeft(Seq(1, 2, 3))(0)((acc, i) => TestEffect1(acc + i))).eval == 9)
            assert(TestEffect1.run(Kyo.foldLeft(Seq.fill(100)(1))(0)((acc, i) => TestEffect1(acc + i))).eval == 200)
        }

        "fill" in {
            assert(TestEffect1.run(Kyo.fill(0)(TestEffect1(1))).eval == Chunk.empty)
            assert(TestEffect1.run(Kyo.fill(1)(TestEffect1(1))).map(_.head).eval == 2)
            assert(TestEffect1.run(Kyo.fill(3)(TestEffect1(1))).map(c => (c(0), c(1), c(2))).eval == (2, 2, 2))
            assert(TestEffect1.run(Kyo.fill(100)(TestEffect1(1))).map(_.size).eval == 100)
        }

        "dropWhile" in {
            def f(i: Int): Boolean < Any = true

            def isEven(i: Int): Boolean < Any = i % 2 == 0

            def lessThanThree(i: Int): Boolean < Any = i < 3

            assert(Kyo.dropWhile(Seq.empty[Int])(a => f(a)).eval == Seq.empty)

            assert(Kyo.dropWhile(Chunk(1, 2, 3, 4))(x => x < 3).eval == Chunk(3, 4))
            assert(Kyo.dropWhile(List(1, 2, 3, 4))(x => x < 3).eval == Chunk(3, 4))

            assert(Kyo.dropWhile(Seq.empty[Int])(x => f(x)).eval == Seq.empty)
            assert(Kyo.dropWhile(Chunk(1, 2, 3))(x => f(x)).eval == Seq.empty)
            assert(Kyo.dropWhile(Chunk(2, 4, 5, 6))(x => isEven(x)).eval == Chunk(5, 6))
            assert(Kyo.dropWhile(Chunk(1, 2, 3, 4))(x => lessThanThree(x)).eval == Chunk(3, 4))
            assert(Kyo.dropWhile(Chunk(2, 4, 5))(x => isEven(x)).eval == Chunk(5))
            assert(Kyo.dropWhile(Chunk(1, 2, -1, 3))(x => lessThanThree(x)).eval == Chunk(3))
            assert(Kyo.dropWhile(Chunk(4, 1, 2))(x => lessThanThree(x)).eval == Chunk(4, 1, 2))
        }

        "takeWhile" in {
            def f(i: Int): Boolean < Any             = true
            def isEven(i: Int): Boolean < Any        = i % 2 == 0
            def lessThanThree(i: Int): Boolean < Any = i < 3

            assert(Kyo.takeWhile(Seq.empty[Int])(a => f(a)).eval == Seq.empty)
            assert(Kyo.takeWhile(Chunk.fill(1)(2))(a => f(a).map(!_)).eval == Seq.empty)

            assert(Kyo.takeWhile(Seq.empty[Int])(x => f(x)).eval == Seq.empty)
            assert(Kyo.takeWhile(Chunk(1, 2, 3))(x => f(x)).eval == Chunk(1, 2, 3))
            assert(Kyo.takeWhile(Chunk(2, 4, 5, 6))(x => isEven(x)).eval == Chunk(2, 4))
            assert(Kyo.takeWhile(Chunk(1, 2, 3, 4))(x => lessThanThree(x)).eval == Chunk(1, 2))
            assert(Kyo.takeWhile(Chunk(2, 4, 5))(x => isEven(x)).eval == Chunk(2, 4))
            assert(Kyo.takeWhile(Chunk(1, 2, -1, 3))(x => lessThanThree(x)).eval == Chunk(1, 2, -1))
            assert(Kyo.takeWhile(Chunk(4, 1, 2))(x => lessThanThree(x)).eval == Seq.empty)
        }

        "shiftedWhile" in {
            def isEven(i: Int): Boolean < Any = i % 2 == 0

            def lessThanThree(i: Int): Boolean < Any = i < 3

            val countWhile = Kyo.shiftedWhile(Chunk(1, 2, 3, 4, 5))(
                prolog = 0,
                f = lessThanThree,
                acc = (count, include, _) => count + 1,
                epilog = identity
            )
            assert(countWhile.eval == 3)

            val sumWithMessage = Kyo.shiftedWhile(Chunk(1, 2, -1, 3, 4))(
                prolog = 0,
                f = lessThanThree,
                acc = (sum, include, curr) => if include then sum + curr else sum,
                epilog = sum => s"Sum: $sum"
            )
            assert(sumWithMessage.eval == "Sum: 2") // 1 + 2 + (-1) = 2

            val collectUntilOdd = Kyo.shiftedWhile(Chunk(2, 4, 6, 7, 8))(
                prolog = List.empty,
                f = isEven,
                acc = (list, include, curr) => if include then curr :: list else list,
                epilog = _.reverse // Maintain original order
            )
            assert(collectUntilOdd.eval == List(2, 4, 6))

            val emptyTest = Kyo.shiftedWhile(Seq.empty[Int])(
                prolog = "Default",
                f = _ => true,
                acc = (str, _, curr) => str + curr.toString,
                epilog = _.toUpperCase
            )
            assert(emptyTest.eval == "DEFAULT")

            @nowarn("msg=deprecated")
            val arrayTest = Kyo.shiftedWhile(Array(1, 2, 3, 4))(
                prolog = "",
                f = lessThanThree,
                acc = (str, include, curr) => if include then str + curr.toString else str,
                epilog = _.length
            )
            assert(arrayTest.eval == 2)
        }

        "stack safety" - {
            val n = 1000

            "collect" in {
                assert(TestEffect1.run(Kyo.collectAll(Seq.fill(n)(TestEffect1(1)))).map(_.size).eval == n)
            }

            "collectDiscard" in {
                var count = 0
                val io    = TestEffect1(1).map(_ => count += 1)
                TestEffect1.run(Kyo.collectAllDiscard(Seq.fill(n)(io))).eval
                assert(count == n)
            }

            "foreach" in {
                assert(TestEffect1.run(Kyo.foreach(Seq.fill(n)(1))(i => TestEffect1(i))).map(_.size).eval == n)
            }

            "foreachDiscard" in {
                var acc = Seq.empty[Int]
                TestEffect1.run(Kyo.foreachDiscard(Seq.fill(n)(1))(v => TestEffect1(v).map(i => acc :+= i))).eval
                assert(acc.size == n)
            }

            "foldLeft" in {
                assert(TestEffect1.run(Kyo.foldLeft(Seq.fill(n)(1))(0)((acc, i) => TestEffect1(acc + i))).eval == n * 2)
            }

            "fill" in {
                assert(TestEffect1.run(Kyo.fill(n)(TestEffect1(1))).map(_.size).eval == n)
            }
        }

        "foreachIndexed" in {
            assert(Kyo.foreachIndexed(Seq.empty[Int])((idx, v) => (idx, v)).eval == Chunk.empty)
            assert(Kyo.foreachIndexed(Seq(1))((idx, v) => (idx, v)).eval == Chunk((0, 1)))
            assert(Kyo.foreachIndexed(Seq(1, 2))((idx, v) => (idx, v)).eval == Chunk((0, 1), (1, 2)))
            assert(Kyo.foreachIndexed(List(1, 2, 3))((idx, v) => (idx, v)).eval == Chunk((0, 1), (1, 2), (2, 3)))
            assert(Kyo.foreachIndexed(Vector(1, 2, 3))((idx, v) => (idx, v)).eval == Chunk((0, 1), (1, 2), (2, 3)))

            // Test with a larger sequence
            val largeSeq = Seq.tabulate(100)(identity)
            assert(Kyo.foreachIndexed(largeSeq)((idx, v) => idx == v).eval == Chunk.fill(100)(true))
        }

        def collectionTests[Coll[X] <: Iterable[X] & IterableOps[X, Coll, Coll[X]]](
            name: String,
            builder: [X] => Seq[X] => Coll[X]
        ): Unit =

            given [L, R]: CanEqual[L, R] = CanEqual.derived

            object Coll:
                def empty[X]: Coll[X]                 = apply()
                def apply[X](x: X*): Coll[X]          = builder(x)
                def from[X](xs: Iterable[X]): Coll[X] = builder(xs.toSeq)
            end Coll

            name - {
                "span" - {
                    "empty sequence" in {
                        val result = Kyo.span(Coll.empty[Int])(x => x < 3).eval
                        assert(result == (Coll.empty, Coll.empty))
                    }

                    "all elements satisfy predicate" in {
                        val result = Kyo.span(Coll(1, 2))(x => x < 3).eval
                        assert(result == (Coll(1, 2), Coll.empty))
                    }

                    "no elements satisfy predicate" in {
                        val result = Kyo.span(Coll(3, 4))(x => x < 3).eval
                        assert(result == (Coll.empty, Coll(3, 4)))
                    }

                    "split in middle" in {
                        val result = Kyo.span(Coll(1, 2, 3, 4))(x => x < 3).eval
                        assert(result == (Coll(1, 2), Coll(3, 4)))
                    }

                    "works with effects" in {
                        val result = TestEffect1.run(
                            Kyo.span(Coll(1, 2, 3, 4))(x => TestEffect1(x).map(_ < 3))
                        ).eval
                        assert(result == (Coll(1), Coll(2, 3, 4)))
                    }
                }

                "partition" - {
                    "empty sequence" in {
                        val result = Kyo.partition(Coll.empty[Int])(x => x % 2 == 0).eval
                        assert(result == (Coll.empty, Coll.empty))
                    }

                    "all elements satisfy predicate" in {
                        val result = Kyo.partition(Coll(2, 4, 6))(x => x % 2 == 0).eval
                        assert(result == (Coll(2, 4, 6), Coll.empty))
                    }

                    "no elements satisfy predicate" in {
                        val result = Kyo.partition(Coll(1, 3, 5))(x => x % 2 == 0).eval
                        assert(result == (Coll.empty, Coll(1, 3, 5)))
                    }

                    "mixed elements" in {
                        val result = Kyo.partition(Coll(1, 2, 3, 4))(x => x % 2 == 0).eval
                        assert(result == (Coll(2, 4), Coll(1, 3)))
                    }

                    "works with effects" in {
                        val result = TestEffect1.run(
                            Kyo.partition(Coll(1, 2, 3, 4))(x => TestEffect1(x).map(_ % 2 == 0))
                        ).eval
                        assert(result == (Coll(1, 3), Coll(2, 4)))
                    }
                }

                "partitionMap" - {
                    "empty sequence" in {
                        val result = Kyo.partitionMap(Coll.empty[Int])(x => Left(x.toString)).eval
                        assert(result == (Coll.empty, Coll.empty))
                    }

                    "all Left" in {
                        val result = Kyo.partitionMap(Coll(1, 2, 3))(x => Left(x.toString)).eval
                        assert(result == (Coll("1", "2", "3"), Coll.empty))
                    }

                    "all Right" in {
                        val result = Kyo.partitionMap(Coll(1, 2, 3))(x => Right(x * 2)).eval
                        assert(result == (Coll.empty, Coll(2, 4, 6)))
                    }

                    "mixed Left and Right" in {
                        val result = Kyo.partitionMap(Coll(1, 2, 3, 4))(x =>
                            if x % 2 == 0 then Right(x * 2) else Left(x.toString)
                        ).eval
                        assert(result == (Coll("1", "3"), Coll(4, 8)))
                    }

                    "works with effects" in {
                        val result = TestEffect1.run(
                            Kyo.partitionMap(Coll(1, 2, 3, 4))(x =>
                                TestEffect1(x).map(v => if v % 2 == 0 then Right(v * 2) else Left(v.toString))
                            )
                        ).eval
                        assert(result == (Coll("3", "5"), Coll(4, 8)))
                    }
                }

                "scanLeft" - {
                    "empty sequence" in {
                        val result = Kyo.scanLeft(Coll.empty[Int])(0)((acc, x) => acc + x).eval
                        assert(result == Coll(0))
                    }

                    "single element" in {
                        val result = Kyo.scanLeft(Coll(1))(0)((acc, x) => acc + x).eval
                        assert(result == Coll(0, 1))
                    }

                    "multiple elements" in {
                        val result = Kyo.scanLeft(Coll(1, 2, 3))(0)((acc, x) => acc + x).eval
                        assert(result == Coll(0, 1, 3, 6))
                    }

                    "works with effects" in {
                        val result = TestEffect1.run(
                            Kyo.scanLeft(Coll(1, 2, 3))(0)((acc, x) => TestEffect1(acc + x))
                        ).eval
                        assert(result == Coll(0, 2, 5, 9))
                    }
                }

                "groupBy" - {
                    "empty sequence" in {
                        val result = Kyo.groupBy(Coll.empty[Int])(x => x % 2).eval
                        assert(result == Map.empty)
                    }

                    "single group" in {
                        val result = Kyo.groupBy(Coll(2, 4, 6))(x => x % 2).eval
                        assert(result == Map(0 -> Coll(2, 4, 6)))
                    }

                    "multiple groups" in {
                        val result = Kyo.groupBy(Coll(1, 2, 3, 4))(x => x % 2).eval
                        assert(result == Map(
                            1 -> Coll(1, 3),
                            0 -> Coll(2, 4)
                        ))
                    }

                    "works with effects" in {
                        val result = TestEffect1.run(
                            Kyo.groupBy(Coll(1, 2, 3, 4))(x => TestEffect1(x).map(_ % 2))
                        ).eval
                        assert(result == Map(
                            0 -> Coll(1, 3),
                            1 -> Coll(2, 4)
                        ))
                    }
                }

                "groupMap" - {
                    "empty sequence" in {
                        val result = Kyo.groupMap(Coll.empty[Int])(x => x % 2)(x => x * 2).eval
                        assert(result == Map.empty)
                    }

                    "single group" in {
                        val result = Kyo.groupMap(Coll(2, 4, 6))(x => x % 2)(x => x * 2).eval
                        assert(result == Map(0 -> Coll(4, 8, 12)))
                    }

                    "multiple groups" in {
                        val result = Kyo.groupMap(Coll(1, 2, 3, 4))(x => x % 2)(x => x * 2).eval
                        assert(result == Map(
                            1 -> Coll(2, 6),
                            0 -> Coll(4, 8)
                        ))
                    }

                    "works with effects" in {
                        val result = TestEffect1.run(
                            Kyo.groupMap(Coll(1, 2, 3, 4))(x => TestEffect1(x).map(_ % 2))(x => TestEffect1(x * 2))
                        ).eval
                        assert(result == Map(
                            0 -> Coll(3, 7),
                            1 -> Coll(5, 9)
                        ))
                    }
                }

                "collect" - {
                    "empty sequence" in {
                        val result = Kyo.collect(Coll.empty[Int])(v => Maybe(v)).eval
                        assert(result == Coll.empty)
                    }

                    "single element - present" in {
                        val result = Kyo.collect(Coll(1))(v => Maybe(v)).eval
                        assert(result == Coll(1))
                    }

                    "single element - absent" in {
                        val result = Kyo.collect(Coll(1))(v => Maybe.empty).eval
                        assert(result == Coll.empty)
                    }

                    "multiple elements - all present" in {
                        val result = Kyo.collect(Coll(1, 2, 3))(v => Maybe(v)).eval
                        assert(result == Coll(1, 2, 3))
                    }

                    "multiple elements - some absent" in {
                        val result = Kyo.collect(Coll(1, 2, 3))(v => if v % 2 == 0 then Maybe(v) else Maybe.empty).eval
                        assert(result == Coll(2))
                    }

                    "works with effects" in {
                        val result = TestEffect1.run(
                            Kyo.collect(Coll(1, 2, 3)) { v =>
                                TestEffect1(v).map(r => Maybe.when(r % 2 == 0)(r))
                            }
                        ).eval
                        assert(result == Coll(2, 4))
                        assert(result == Coll(2, 4))
                    }

                    "stack safety" in {
                        val n      = 1000
                        val result = Kyo.collect(Coll.from(0 until n))(v => Maybe(v)).eval
                        assert(result.size == n)
                        assert(result == Coll.from(0 until n))
                    }
                }
            }
        end collectionTests

        collectionTests[Vector]("Vector", [X] => seq => Vector.from(seq))
        collectionTests[Chunk]("Chunk", [X] => seq => Chunk.from(seq))
        collectionTests[List]("List", [X] => seq => List.from(seq))
        collectionTests[Set]("Set", [X] => seq => Set.from(seq))
        collectionTests[Seq]("Seq", [X] => seq => Seq.from(seq))
    }

    "lift" - {
        "should create a pure effect" in {
            val effect = Kyo.lift[Int, Any](42)
            assert(effect.eval == 42)
        }

        "should work with different types" in {
            assert(Kyo.lift[String, Any]("hello").eval == "hello")
            assert(Kyo.lift[Boolean, Any](true).eval == true)
            assert(Kyo.lift[List[Int], Any](List(1, 2, 3)).eval == List(1, 2, 3))
        }

        "should work with effects" in {
            val effect = Kyo.lift[Int < TestEffect1, Any](TestEffect1(1))
            val result = TestEffect1.run(effect.flatten)
            assert(result.eval == 2)
        }
    }

    "findFirst" - {
        "empty sequence" in {
            assert(Kyo.findFirst(Seq.empty[Int])(v => Maybe(v)).eval == Maybe.empty)
        }

        "single element - found" in {
            assert(Kyo.findFirst(Seq(1))(v => Maybe(v)).eval == Maybe(1))
        }

        "single element - not found" in {
            assert(Kyo.findFirst(Seq(1))(v => Maybe.empty).eval == Maybe.empty)
        }

        "multiple elements - first match" in {
            assert(Kyo.findFirst(Seq(1, 2, 3))(v => if v > 0 then Maybe(v) else Maybe.empty).eval == Maybe(1))
        }

        "multiple elements - middle match" in {
            assert(Kyo.findFirst(Seq(1, 2, 3))(v => if v == 2 then Maybe(v) else Maybe.empty).eval == Maybe(2))
        }

        "multiple elements - no match" in {
            assert(Kyo.findFirst(Seq(1, 2, 3))(v => if v > 5 then Maybe(v) else Maybe.empty).eval == Maybe.empty)
        }

        "works with effects" in {
            var count = 0
            val result = TestEffect1.run(
                Kyo.findFirst(Seq(1, 2, 3)) { v =>
                    TestEffect1(41).map { r =>
                        count += 1
                        if v == r then Maybe(v) else Maybe.empty
                    }
                }
            ).eval
            assert(result == Maybe.empty)
            assert(count == 3)
        }

        "short circuits" in {
            var count = 0
            val result = Kyo.findFirst(Seq(1, 2, 3, 4, 5)) { v =>
                count += 1
                if v == 2 then Maybe(v) else Maybe.empty
            }.eval
            assert(result == Maybe(2))
            assert(count == 2)
        }

        "works with different sequence types" in {
            val pred = (v: Int) => if v == 2 then Maybe(v) else Maybe.empty
            assert(Kyo.findFirst(List(1, 2, 3))(pred).eval == Maybe(2))
            assert(Kyo.findFirst(Vector(1, 2, 3))(pred).eval == Maybe(2))
            assert(Kyo.findFirst(Chunk(1, 2, 3))(pred).eval == Maybe(2))
        }
    }

end KyoTest
