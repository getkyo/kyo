package kyo

import Tagged.*
import kyo.kernel.*
import scala.annotation.tailrec

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
        assert(TestEffect1(1).map(_ + 1).toString() ==
            "Kyo(kyo.KyoTest.TestEffect1, Input(1), KyoTest.scala:30:41, assert(TestEffect1(1).map(_ + 1))")
        assert(
            TestEffect1(1).map(_ + 1).map(_ + 2).toString() ==
                "Kyo(kyo.KyoTest.TestEffect1, Input(1), KyoTest.scala:33:49, TestEffect1(1).map(_ + 1).map(_ + 2))"
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

        "collect" - {
            "empty sequence" in {
                assert(Kyo.collect(Seq.empty[Int])(v => Maybe(v)).eval == Chunk.empty)
            }

            "single element - present" in {
                assert(Kyo.collect(Seq(1))(v => Maybe(v)).eval == Chunk(1))
            }

            "single element - absent" in {
                assert(Kyo.collect(Seq(1))(v => Maybe.empty).eval == Chunk.empty)
            }

            "multiple elements - all present" in {
                assert(Kyo.collect(Seq(1, 2, 3))(v => Maybe(v)).eval == Chunk(1, 2, 3))
            }

            "multiple elements - some absent" in {
                assert(Kyo.collect(Seq(1, 2, 3))(v => if v % 2 == 0 then Maybe(v) else Maybe.empty).eval == Chunk(2))
            }

            "works with effects" in {
                val result = TestEffect1.run(
                    Kyo.collect(Seq(1, 2, 3)) { v =>
                        TestEffect1(v).map(r => Maybe.when(r % 2 == 0)(r))
                    }
                ).eval
                assert(result == Chunk(2, 4))
            }

            "works with different sequence types" in {
                val f = (v: Int) => if v % 2 == 0 then Maybe(v) else Maybe.empty
                assert(Kyo.collect(List(1, 2, 3))(f).eval == Chunk(2))
                assert(Kyo.collect(Vector(1, 2, 3))(f).eval == Chunk(2))
                assert(Kyo.collect(Chunk(1, 2, 3))(f).eval == Chunk(2))
            }

            "stack safety" in {
                val n      = 1000
                val result = Kyo.collect(Seq.range(0, n))(v => Maybe(v)).eval
                assert(result.size == n)
                assert(result == Chunk.from(0 until n))
            }
        }
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
