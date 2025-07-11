package kyo

@TestVariant("Coll", "List", "Chunk")
class KyoForeachCollTest extends Test:

    import KyoForeachTest.*

    @TestVariant("Seq", "List", "Chunk")
    private val Coll = Seq

    @TestVariant("Seq", "List", "Chunk")
    type Coll[X] = Seq[X]

    // @TestVariant("Coll", "List", "Chunk")
    "Coll specialized" - {
        "collectAll" in {
            assert(Kyo.collectAll(Coll.empty).eval == Coll.empty)
            assert(TestEffect1.run(Kyo.collectAll(Coll(TestEffect1(1))).map(_.head)).eval == 2)
            assert(TestEffect2.run(TestEffect1.run(Kyo.collectAll(Coll(TestEffect1(1), TestEffect1(2))).map(c =>
                (c(0), c(1))
            ))).eval == (
                2,
                3
            ))
            assert(TestEffect1.run(Kyo.collectAll(Coll.fill(100)(TestEffect1(1))).map(_.size)).eval == 100)
        }

        "collect" in {
            assert(Kyo.collectAll(Coll.empty).eval == Coll.empty)
            assert(TestEffect1.run(Kyo.collectAll(Coll(TestEffect1(1))).map(_.head)).eval == 2)
            assert(TestEffect2.run(TestEffect1.run(Kyo.collectAll(Coll(TestEffect1(1), TestEffect1(2))).map(c => (c(0), c(1))))).eval == (
                2,
                3
            ))
            assert(TestEffect1.run(Kyo.collectAll(Coll.fill(100)(TestEffect1(1))).map(_.size)).eval == 100)
            assert(TestEffect2.run(TestEffect1.run(Kyo.collectAll(Coll(TestEffect1(1), TestEffect1(2), TestEffect1(3))).map(c =>
                (c(0), c(1), c(2))
            ))).eval == (2, 3, 4))
            assert(TestEffect2.run(TestEffect1.run(Kyo.collectAll(Coll(TestEffect1(1), TestEffect1(2), TestEffect1(3))).map(c =>
                (c(0), c(1), c(2))
            ))).eval == (2, 3, 4))
        }

        "collectDiscard" in {
            var count = 0
            val io    = TestEffect1(1).map(_ => count += 1)
            TestEffect1.run(Kyo.collectAllDiscard(Coll.empty)).eval
            assert(count == 0)
            TestEffect1.run(Kyo.collectAllDiscard(Coll(io))).eval
            assert(count == 1)
            TestEffect1.run(Kyo.collectAllDiscard(Coll.fill(42)(io))).eval
            assert(count == 43)
            TestEffect1.run(Kyo.collectAllDiscard(Coll.fill(10)(io))).eval
            assert(count == 53)
        }
        "foreach" in {
            assert(TestEffect1.run(Kyo.foreach(Coll.empty)(i => TestEffect1(i))).eval == Coll.empty)
            assert(TestEffect1.run(Kyo.foreach(Coll(1))(i => TestEffect1(i))).map(_.head).eval == 2)
            assert(TestEffect1.run(Kyo.foreach(Coll(1, 2))(i => TestEffect1(i))).map(c => (c(0), c(1))).eval == (2, 3))
            assert(TestEffect1.run(Kyo.foreach(Coll.fill(100)(1))(i => TestEffect1(i))).map(_.size).eval == 100)
        }
        "foreachIndexed" in {
            assert(Kyo.foreachIndexed(Coll.empty[Int])((idx, v) => (idx, v)).eval == Coll.empty)
            assert(Kyo.foreachIndexed(Coll(1))((idx, v) => (idx, v)).eval == Coll((0, 1)))
            assert(Kyo.foreachIndexed(Coll(1, 2))((idx, v) => (idx, v)).eval == Coll((0, 1), (1, 2)))
            assert(Kyo.foreachIndexed(Coll(1, 2, 3))((idx, v) => (idx, v)).eval == Coll((0, 1), (1, 2), (2, 3)))
            // Test with a larger sequence
            assert(Kyo.foreachIndexed(Coll.tabulate(100)(identity))((idx, v) => idx == v).eval == Coll.fill(100)(true))
        }
        "foreachDiscard" in {
            var acc: Coll[Int] = Coll.empty
            TestEffect1.run(Kyo.foreachDiscard(Coll.empty[Int])(v => TestEffect1(v).map(i => acc :+= i))).eval
            assert(acc == Coll.empty[Int])
            acc = Coll.empty
            TestEffect1.run(Kyo.foreachDiscard(Coll(1))(v => TestEffect1(v).map(i => acc :+= i))).eval
            assert(acc == Coll(2))
            acc = Coll.empty
            TestEffect1.run(Kyo.foreachDiscard(Coll(1, 2))(v => TestEffect1(v).map(i => acc :+= i))).eval
            assert(acc == Coll(2, 3))
        }
        "foldLeft" in {
            assert(TestEffect1.run(Kyo.foldLeft(Coll.empty[Int])(0)((acc, i) => TestEffect1(acc + i))).eval == 0)
            assert(TestEffect1.run(Kyo.foldLeft(Coll(1))(0)((acc, i) => TestEffect1(acc + i))).eval == 2)
            assert(TestEffect1.run(Kyo.foldLeft(Coll(1, 2, 3))(0)((acc, i) => TestEffect1(acc + i))).eval == 9)
            assert(TestEffect1.run(Kyo.foldLeft(Coll.fill(100)(1))(0)((acc, i) => TestEffect1(acc + i))).eval == 200)
        }
        "dropWhile" in {
            def f(i: Int): Boolean < Any = true

            def isEven(i: Int): Boolean < Any = i % 2 == 0

            def lessThanThree(i: Int): Boolean < Any = i < 3

            assert(Kyo.dropWhile(Coll.empty[Int])(a => f(a)).eval == Coll.empty[Int])

            assert(Kyo.dropWhile(Coll(1, 2, 3, 4))(x => x < 3).eval == Coll(3, 4))
            assert(Kyo.dropWhile(Coll(1, 2, 3))(x => f(x)).eval == Coll.empty[Int])
            assert(Kyo.dropWhile(Coll(2, 4, 5, 6))(x => isEven(x)).eval == Coll(5, 6))
            assert(Kyo.dropWhile(Coll(1, 2, 3, 4))(x => lessThanThree(x)).eval == Coll(3, 4))
            assert(Kyo.dropWhile(Coll(2, 4, 5))(x => isEven(x)).eval == Coll(5))
            assert(Kyo.dropWhile(Coll(1, 2, -1, 3))(x => lessThanThree(x)).eval == Coll(3))
            assert(Kyo.dropWhile(Coll(4, 1, 2))(x => lessThanThree(x)).eval == Coll(4, 1, 2))
        }

        "takeWhile" in {
            def f(i: Int): Boolean < Any = true

            def isEven(i: Int): Boolean < Any = i % 2 == 0

            def lessThanThree(i: Int): Boolean < Any = i < 3

            assert(Kyo.takeWhile(Coll.empty[Int])(x => f(x)).eval == Coll.empty[Int])
            assert(Kyo.takeWhile(Coll(1, 2, 3))(x => f(x)).eval == Coll(1, 2, 3))
            assert(Kyo.takeWhile(Coll(2, 4, 5, 6))(x => isEven(x)).eval == Coll(2, 4))
            assert(Kyo.takeWhile(Coll(1, 2, 3, 4))(x => lessThanThree(x)).eval == Coll(1, 2))
            assert(Kyo.takeWhile(Coll(2, 4, 5))(x => isEven(x)).eval == Coll(2, 4))
            assert(Kyo.takeWhile(Coll(1, 2, -1, 3))(x => lessThanThree(x)).eval == Coll(1, 2, -1))
            assert(Kyo.takeWhile(Coll(4, 1, 2))(x => lessThanThree(x)).eval == Coll.empty[Int])
        }

        "shiftedWhile" in {
            def isEven(i: Int): Boolean < Any = i % 2 == 0

            def lessThanThree(i: Int): Boolean < Any = i < 3

            val countWhile = Kyo.shiftedWhile(Coll(1, 2, 3, 4, 5))(
                prolog = 0,
                f = lessThanThree,
                acc = (count, include, _) => count + 1,
                epilog = identity
            )
            assert(countWhile.eval == 3)

            val sumWithMessage = Kyo.shiftedWhile(Coll(1, 2, -1, 3, 4))(
                prolog = 0,
                f = lessThanThree,
                acc = (sum, include, curr) => if include then sum + curr else sum,
                epilog = sum => s"Sum: $sum"
            )
            assert(sumWithMessage.eval == "Sum: 2") // 1 + 2 + (-1) = 2

            val collectUntilOdd = Kyo.shiftedWhile(Coll(2, 4, 6, 7, 8))(
                prolog = Coll.empty,
                f = isEven,
                acc = (list, include, curr) => if include then curr +: list else list,
                epilog = _.reverse // Maintain original order
            )
            assert(collectUntilOdd.eval == Coll(2, 4, 6))

            val emptyTest = Kyo.shiftedWhile(Coll.empty[Int])(
                prolog = "Default",
                f = _ => true,
                acc = (str, _, curr) => str + curr.toString,
                epilog = _.toUpperCase
            )
            assert(emptyTest.eval == "DEFAULT")
        }

        "span" - {
            "empty sequence" in {
                val result = Kyo.span(Coll.empty[Int])(x => x < 3).eval
                assert(result == (Coll.empty[Int], Coll.empty[Int]))
            }

            "all elements satisfy predicate" in {
                val result = Kyo.span(Coll(1, 2))(x => x < 3).eval
                assert(result == (Coll(1, 2), Coll.empty[Int]))
            }

            "no elements satisfy predicate" in {
                val result = Kyo.span(Coll(3, 4))(x => x < 3).eval
                assert(result == (Coll.empty[Int], Coll(3, 4)))
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
                assert(result == (Coll.empty[Int], Coll.empty[Int]))
            }

            "all elements satisfy predicate" in {
                val result = Kyo.partition(Coll(2, 4, 6))(x => x % 2 == 0).eval
                assert(result == (Coll(2, 4, 6), Coll.empty[Int]))
            }

            "no elements satisfy predicate" in {
                val result = Kyo.partition(Coll(1, 3, 5))(x => x % 2 == 0).eval
                assert(result == (Coll.empty[Int], Coll(1, 3, 5)))
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
                assert(result == (Coll.empty[String], Coll.empty[Int]))
            }

            "all Left" in {
                val result = Kyo.partitionMap(Coll(1, 2, 3))(x => Left(x.toString)).eval
                assert(result == (Coll("1", "2", "3"), Coll.empty[Int]))
            }

            "all Right" in {
                val result = Kyo.partitionMap(Coll(1, 2, 3))(x => Right(x * 2)).eval
                assert(result == (Coll.empty[Int], Coll(2, 4, 6)))
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
                assert(result == Coll.empty[Int])
            }

            "single element - present" in {
                val result = Kyo.collect(Coll(1))(v => Maybe(v)).eval
                assert(result == Coll(1))
            }

            "single element - absent" in {
                val result = Kyo.collect(Coll(1))(v => Maybe.empty).eval
                assert(result == Coll.empty[Int])
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
            }

            "stack safety" in {
                val n      = 1000
                val result = Kyo.collect(Coll.from(0 until n))(v => Maybe(v)).eval
                assert(result.size == n)
                assert(result == Coll.from(0 until n))
            }
        }

        "stack safety" - {
            val n = 1000

            "collect" in {
                assert(TestEffect1.run(Kyo.collectAll(Coll.fill(n)(TestEffect1(1)))).map(_.size).eval == n)
            }

            "collectDiscard" in {
                var count = 0
                val io    = TestEffect1(1).map(_ => count += 1)
                TestEffect1.run(Kyo.collectAllDiscard(Coll.fill(n)(io))).eval
                assert(count == n)
            }

            "foreach" in {
                assert(TestEffect1.run(Kyo.foreach(Coll.fill(n)(1))(i => TestEffect1(i))).map(_.size).eval == n)
            }

            "foreachDiscard" in {
                var acc = Coll.empty[Int]
                TestEffect1.run(Kyo.foreachDiscard(Coll.fill(n)(1))(v => TestEffect1(v).map(i => acc :+= i))).eval
                assert(acc.size == n)
            }
        }
    }

    // @TestVariant("Coll", "List", "Chunk")
end KyoForeachCollTest
