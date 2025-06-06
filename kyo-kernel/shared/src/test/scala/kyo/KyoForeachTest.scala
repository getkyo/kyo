package kyo

import Tagged.*
import kyo.kernel.*
import scala.annotation.tailrec

class KyoForeachTest extends Test:

    import KyoForeachTest.*

    "List specialized" - {
        "collectAll" in {
            assert(Kyo.collectAll(List.empty).eval == List.empty)
            assert(TestEffect1.run(Kyo.collectAll(List(TestEffect1(1))).map(_.head)).eval == 2)
            assert(TestEffect2.run(TestEffect1.run(Kyo.collectAll(List(TestEffect1(1), TestEffect1(2))).map(c =>
                (c(0), c(1))
            ))).eval == (
                2,
                3
            ))
            assert(TestEffect1.run(Kyo.collectAll(List.fill(100)(TestEffect1(1))).map(_.size)).eval == 100)
        }
        "collectDiscard" in {
            var count = 0
            val io    = TestEffect1(1).map(_ => count += 1)
            TestEffect1.run(Kyo.collectAllDiscard(List.empty)).eval
            assert(count == 0)
            TestEffect1.run(Kyo.collectAllDiscard(List(io))).eval
            assert(count == 1)
            TestEffect1.run(Kyo.collectAllDiscard(List.fill(42)(io))).eval
            assert(count == 43)
            TestEffect1.run(Kyo.collectAllDiscard(List.fill(10)(io))).eval
            assert(count == 53)
        }
        "foreach" in {
            assert(TestEffect1.run(Kyo.foreach(List.empty)(i => TestEffect1(i))).eval == List.empty)
            assert(TestEffect1.run(Kyo.foreach(List(1))(i => TestEffect1(i))).map(_.head).eval == 2)
            assert(TestEffect1.run(Kyo.foreach(List(1, 2))(i => TestEffect1(i))).map(c => (c(0), c(1))).eval == (2, 3))
            assert(TestEffect1.run(Kyo.foreach(List.fill(100)(1))(i => TestEffect1(i))).map(_.size).eval == 100)
        }
        "foreachIndexed" in {
            assert(Kyo.foreachIndexed(List.empty[Int])((idx, v) => (idx, v)).eval == List.empty)
            assert(Kyo.foreachIndexed(List(1))((idx, v) => (idx, v)).eval == List((0, 1)))
            assert(Kyo.foreachIndexed(List(1, 2))((idx, v) => (idx, v)).eval == List((0, 1), (1, 2)))
            assert(Kyo.foreachIndexed(List(1, 2, 3))((idx, v) => (idx, v)).eval == List((0, 1), (1, 2), (2, 3)))
            // Test with a larger sequence
            assert(Kyo.foreachIndexed(List.tabulate(100)(identity))((idx, v) => idx == v).eval == List.fill(100)(true))
        }
        "foreachDiscard" in {
            var acc: List[Int] = List.empty
            TestEffect1.run(Kyo.foreachDiscard(List.empty[Int])(v => TestEffect1(v).map(i => acc :+= i))).eval
            assert(acc == List.empty[Int])
            acc = List.empty
            TestEffect1.run(Kyo.foreachDiscard(List(1))(v => TestEffect1(v).map(i => acc :+= i))).eval
            assert(acc == List(2))
            acc = List.empty
            TestEffect1.run(Kyo.foreachDiscard(List(1, 2))(v => TestEffect1(v).map(i => acc :+= i))).eval
            assert(acc == List(2, 3))
        }
        "foldLeft" in {
            assert(TestEffect1.run(Kyo.foldLeft(List.empty[Int])(0)((acc, i) => TestEffect1(acc + i))).eval == 0)
            assert(TestEffect1.run(Kyo.foldLeft(List(1))(0)((acc, i) => TestEffect1(acc + i))).eval == 2)
            assert(TestEffect1.run(Kyo.foldLeft(List(1, 2, 3))(0)((acc, i) => TestEffect1(acc + i))).eval == 9)
            assert(TestEffect1.run(Kyo.foldLeft(List.fill(100)(1))(0)((acc, i) => TestEffect1(acc + i))).eval == 200)
        }
        "dropWhile" in {
            def f(i: Int): Boolean < Any = true

            def isEven(i: Int): Boolean < Any = i % 2 == 0

            def lessThanThree(i: Int): Boolean < Any = i < 3

            assert(Kyo.dropWhile(List.empty[Int])(a => f(a)).eval == List.empty[Int])

            assert(Kyo.dropWhile(List(1, 2, 3, 4))(x => x < 3).eval == List(3, 4))
            assert(Kyo.dropWhile(List(1, 2, 3))(x => f(x)).eval == List.empty[Int])
            assert(Kyo.dropWhile(List(2, 4, 5, 6))(x => isEven(x)).eval == List(5, 6))
            assert(Kyo.dropWhile(List(1, 2, 3, 4))(x => lessThanThree(x)).eval == List(3, 4))
            assert(Kyo.dropWhile(List(2, 4, 5))(x => isEven(x)).eval == List(5))
            assert(Kyo.dropWhile(List(1, 2, -1, 3))(x => lessThanThree(x)).eval == List(3))
            assert(Kyo.dropWhile(List(4, 1, 2))(x => lessThanThree(x)).eval == List(4, 1, 2))
        }

        "takeWhile" in {
            def f(i: Int): Boolean < Any             = true
            def isEven(i: Int): Boolean < Any        = i % 2 == 0
            def lessThanThree(i: Int): Boolean < Any = i < 3

            assert(Kyo.takeWhile(List.empty[Int])(x => f(x)).eval == List.empty[Int])
            assert(Kyo.takeWhile(List(1, 2, 3))(x => f(x)).eval == List(1, 2, 3))
            assert(Kyo.takeWhile(List(2, 4, 5, 6))(x => isEven(x)).eval == List(2, 4))
            assert(Kyo.takeWhile(List(1, 2, 3, 4))(x => lessThanThree(x)).eval == List(1, 2))
            assert(Kyo.takeWhile(List(2, 4, 5))(x => isEven(x)).eval == List(2, 4))
            assert(Kyo.takeWhile(List(1, 2, -1, 3))(x => lessThanThree(x)).eval == List(1, 2, -1))
            assert(Kyo.takeWhile(List(4, 1, 2))(x => lessThanThree(x)).eval == List.empty[Int])
        }

        "shiftedWhile" in {
            def isEven(i: Int): Boolean < Any = i % 2 == 0

            def lessThanThree(i: Int): Boolean < Any = i < 3

            val countWhile = Kyo.shiftedWhile(List(1, 2, 3, 4, 5))(
                prolog = 0,
                f = lessThanThree,
                acc = (count, include, _) => count + 1,
                epilog = identity
            )
            assert(countWhile.eval == 3)

            val sumWithMessage = Kyo.shiftedWhile(List(1, 2, -1, 3, 4))(
                prolog = 0,
                f = lessThanThree,
                acc = (sum, include, curr) => if include then sum + curr else sum,
                epilog = sum => s"Sum: $sum"
            )
            assert(sumWithMessage.eval == "Sum: 2") // 1 + 2 + (-1) = 2

            val collectUntilOdd = Kyo.shiftedWhile(List(2, 4, 6, 7, 8))(
                prolog = List.empty,
                f = isEven,
                acc = (list, include, curr) => if include then curr :: list else list,
                epilog = _.reverse // Maintain original order
            )
            assert(collectUntilOdd.eval == List(2, 4, 6))

            val emptyTest = Kyo.shiftedWhile(List.empty[Int])(
                prolog = "Default",
                f = _ => true,
                acc = (str, _, curr) => str + curr.toString,
                epilog = _.toUpperCase
            )
            assert(emptyTest.eval == "DEFAULT")
        }

        "span" - {
            "empty sequence" in {
                val result = Kyo.span(List.empty[Int])(x => x < 3).eval
                assert(result == (List.empty[Int], List.empty[Int]))
            }

            "all elements satisfy predicate" in {
                val result = Kyo.span(List(1, 2))(x => x < 3).eval
                assert(result == (List(1, 2), List.empty[Int]))
            }

            "no elements satisfy predicate" in {
                val result = Kyo.span(List(3, 4))(x => x < 3).eval
                assert(result == (List.empty[Int], List(3, 4)))
            }

            "split in middle" in {
                val result = Kyo.span(List(1, 2, 3, 4))(x => x < 3).eval
                assert(result == (List(1, 2), List(3, 4)))
            }

            "works with effects" in {
                val result = TestEffect1.run(
                    Kyo.span(List(1, 2, 3, 4))(x => TestEffect1(x).map(_ < 3))
                ).eval
                assert(result == (List(1), List(2, 3, 4)))
            }
        }

        "partition" - {
            "empty sequence" in {
                val result = Kyo.partition(List.empty[Int])(x => x % 2 == 0).eval
                assert(result == (List.empty[Int], List.empty[Int]))
            }

            "all elements satisfy predicate" in {
                val result = Kyo.partition(List(2, 4, 6))(x => x % 2 == 0).eval
                assert(result == (List(2, 4, 6), List.empty[Int]))
            }

            "no elements satisfy predicate" in {
                val result = Kyo.partition(List(1, 3, 5))(x => x % 2 == 0).eval
                assert(result == (List.empty[Int], List(1, 3, 5)))
            }

            "mixed elements" in {
                val result = Kyo.partition(List(1, 2, 3, 4))(x => x % 2 == 0).eval
                assert(result == (List(2, 4), List(1, 3)))
            }

            "works with effects" in {
                val result = TestEffect1.run(
                    Kyo.partition(List(1, 2, 3, 4))(x => TestEffect1(x).map(_ % 2 == 0))
                ).eval
                assert(result == (List(1, 3), List(2, 4)))
            }
        }

        "partitionMap" - {
            "empty sequence" in {
                val result = Kyo.partitionMap(List.empty[Int])(x => Left(x.toString)).eval
                assert(result == (List.empty[String], List.empty[Int]))
            }

            "all Left" in {
                val result = Kyo.partitionMap(List(1, 2, 3))(x => Left(x.toString)).eval
                assert(result == (List("1", "2", "3"), List.empty[Int]))
            }

            "all Right" in {
                val result = Kyo.partitionMap(List(1, 2, 3))(x => Right(x * 2)).eval
                assert(result == (List.empty[Int], List(2, 4, 6)))
            }

            "mixed Left and Right" in {
                val result = Kyo.partitionMap(List(1, 2, 3, 4))(x =>
                    if x % 2 == 0 then Right(x * 2) else Left(x.toString)
                ).eval
                assert(result == (List("1", "3"), List(4, 8)))
            }

            "works with effects" in {
                val result = TestEffect1.run(
                    Kyo.partitionMap(List(1, 2, 3, 4))(x =>
                        TestEffect1(x).map(v => if v % 2 == 0 then Right(v * 2) else Left(v.toString))
                    )
                ).eval
                assert(result == (List("3", "5"), List(4, 8)))
            }
        }

        "scanLeft" - {
            "empty sequence" in {
                val result = Kyo.scanLeft(List.empty[Int])(0)((acc, x) => acc + x).eval
                assert(result == List(0))
            }

            "single element" in {
                val result = Kyo.scanLeft(List(1))(0)((acc, x) => acc + x).eval
                assert(result == List(0, 1))
            }

            "multiple elements" in {
                val result = Kyo.scanLeft(List(1, 2, 3))(0)((acc, x) => acc + x).eval
                assert(result == List(0, 1, 3, 6))
            }

            "works with effects" in {
                val result = TestEffect1.run(
                    Kyo.scanLeft(List(1, 2, 3))(0)((acc, x) => TestEffect1(acc + x))
                ).eval
                assert(result == List(0, 2, 5, 9))
            }
        }

        "groupBy" - {
            "empty sequence" in {
                val result = Kyo.groupBy(List.empty[Int])(x => x % 2).eval
                assert(result == Map.empty)
            }

            "single group" in {
                val result = Kyo.groupBy(List(2, 4, 6))(x => x % 2).eval
                assert(result == Map(0 -> List(2, 4, 6)))
            }

            "multiple groups" in {
                val result = Kyo.groupBy(List(1, 2, 3, 4))(x => x % 2).eval
                assert(result == Map(
                    1 -> List(1, 3),
                    0 -> List(2, 4)
                ))
            }

            "works with effects" in {
                val result = TestEffect1.run(
                    Kyo.groupBy(List(1, 2, 3, 4))(x => TestEffect1(x).map(_ % 2))
                ).eval
                assert(result == Map(
                    0 -> List(1, 3),
                    1 -> List(2, 4)
                ))
            }
        }

        "groupMap" - {
            "empty sequence" in {
                val result = Kyo.groupMap(List.empty[Int])(x => x % 2)(x => x * 2).eval
                assert(result == Map.empty)
            }

            "single group" in {
                val result = Kyo.groupMap(List(2, 4, 6))(x => x % 2)(x => x * 2).eval
                assert(result == Map(0 -> List(4, 8, 12)))
            }

            "multiple groups" in {
                val result = Kyo.groupMap(List(1, 2, 3, 4))(x => x % 2)(x => x * 2).eval
                assert(result == Map(
                    1 -> List(2, 6),
                    0 -> List(4, 8)
                ))
            }

            "works with effects" in {
                val result = TestEffect1.run(
                    Kyo.groupMap(List(1, 2, 3, 4))(x => TestEffect1(x).map(_ % 2))(x => TestEffect1(x * 2))
                ).eval
                assert(result == Map(
                    0 -> List(3, 7),
                    1 -> List(5, 9)
                ))
            }
        }

        "collect" - {
            "empty sequence" in {
                val result = Kyo.collect(List.empty[Int])(v => Maybe(v)).eval
                assert(result == List.empty[Int])
            }

            "single element - present" in {
                val result = Kyo.collect(List(1))(v => Maybe(v)).eval
                assert(result == List(1))
            }

            "single element - absent" in {
                val result = Kyo.collect(List(1))(v => Maybe.empty).eval
                assert(result == List.empty[Int])
            }

            "multiple elements - all present" in {
                val result = Kyo.collect(List(1, 2, 3))(v => Maybe(v)).eval
                assert(result == List(1, 2, 3))
            }

            "multiple elements - some absent" in {
                val result = Kyo.collect(List(1, 2, 3))(v => if v % 2 == 0 then Maybe(v) else Maybe.empty).eval
                assert(result == List(2))
            }

            "works with effects" in {
                val result = TestEffect1.run(
                    Kyo.collect(List(1, 2, 3)) { v =>
                        TestEffect1(v).map(r => Maybe.when(r % 2 == 0)(r))
                    }
                ).eval
                assert(result == List(2, 4))
            }

            "stack safety" in {
                val n      = 1000
                val result = Kyo.collect(List.from(0 until n))(v => Maybe(v)).eval
                assert(result.size == n)
                assert(result == List.from(0 until n))
            }
        }

        "stack safety" - {
            val n = 1000

            "collect" in {
                assert(TestEffect1.run(Kyo.collectAll(List.fill(n)(TestEffect1(1)))).map(_.size).eval == n)
            }

            "collectDiscard" in {
                var count = 0
                val io    = TestEffect1(1).map(_ => count += 1)
                TestEffect1.run(Kyo.collectAllDiscard(List.fill(n)(io))).eval
                assert(count == n)
            }

            "foreach" in {
                assert(TestEffect1.run(Kyo.foreach(List.fill(n)(1))(i => TestEffect1(i))).map(_.size).eval == n)
            }

            "foreachDiscard" in {
                var acc = List.empty[Int]
                TestEffect1.run(Kyo.foreachDiscard(List.fill(n)(1))(v => TestEffect1(v).map(i => acc :+= i))).eval
                assert(acc.size == n)
            }
        }
    }

    "Vector specialized" - {
        "collectAll" in {
            assert(Kyo.collectAll(Vector.empty).eval == Vector.empty)
            assert(TestEffect1.run(Kyo.collectAll(Vector(TestEffect1(1))).map(_.head)).eval == 2)
            assert(TestEffect2.run(TestEffect1.run(Kyo.collectAll(Vector(TestEffect1(1), TestEffect1(2))).map(c =>
                (c(0), c(1))
            ))).eval == (
                2,
                3
            ))
            assert(TestEffect1.run(Kyo.collectAll(Vector.fill(100)(TestEffect1(1))).map(_.size)).eval == 100)
        }
        "collectDiscard" in {
            var count = 0
            val io    = TestEffect1(1).map(_ => count += 1)
            TestEffect1.run(Kyo.collectAllDiscard(Vector.empty)).eval
            assert(count == 0)
            TestEffect1.run(Kyo.collectAllDiscard(Vector(io))).eval
            assert(count == 1)
            TestEffect1.run(Kyo.collectAllDiscard(Vector.fill(42)(io))).eval
            assert(count == 43)
            TestEffect1.run(Kyo.collectAllDiscard(Vector.fill(10)(io))).eval
            assert(count == 53)
        }
        "foreach" in {
            assert(TestEffect1.run(Kyo.foreach(Vector.empty)(i => TestEffect1(i))).eval == Vector.empty)
            assert(TestEffect1.run(Kyo.foreach(Vector(1))(i => TestEffect1(i))).map(_.head).eval == 2)
            assert(TestEffect1.run(Kyo.foreach(Vector(1, 2))(i => TestEffect1(i))).map(c => (c(0), c(1))).eval == (2, 3))
            assert(TestEffect1.run(Kyo.foreach(Vector.fill(100)(1))(i => TestEffect1(i))).map(_.size).eval == 100)
        }
        "foreachIndexed" in {
            assert(Kyo.foreachIndexed(Vector.empty[Int])((idx, v) => (idx, v)).eval == Vector.empty)
            assert(Kyo.foreachIndexed(Vector(1))((idx, v) => (idx, v)).eval == Vector((0, 1)))
            assert(Kyo.foreachIndexed(Vector(1, 2))((idx, v) => (idx, v)).eval == Vector((0, 1), (1, 2)))
            assert(Kyo.foreachIndexed(Vector(1, 2, 3))((idx, v) => (idx, v)).eval == Vector((0, 1), (1, 2), (2, 3)))
            // Test with a larger sequence
            assert(Kyo.foreachIndexed(Vector.tabulate(100)(identity))((idx, v) => idx == v).eval == Vector.fill(100)(true))
        }
        "foreachDiscard" in {
            var acc: Vector[Int] = Vector.empty
            TestEffect1.run(Kyo.foreachDiscard(Vector.empty[Int])(v => TestEffect1(v).map(i => acc :+= i))).eval
            assert(acc == Vector.empty[Int])
            acc = Vector.empty
            TestEffect1.run(Kyo.foreachDiscard(Vector(1))(v => TestEffect1(v).map(i => acc :+= i))).eval
            assert(acc == Vector(2))
            acc = Vector.empty
            TestEffect1.run(Kyo.foreachDiscard(Vector(1, 2))(v => TestEffect1(v).map(i => acc :+= i))).eval
            assert(acc == Vector(2, 3))
        }
        "foldLeft" in {
            assert(TestEffect1.run(Kyo.foldLeft(Vector.empty[Int])(0)((acc, i) => TestEffect1(acc + i))).eval == 0)
            assert(TestEffect1.run(Kyo.foldLeft(Vector(1))(0)((acc, i) => TestEffect1(acc + i))).eval == 2)
            assert(TestEffect1.run(Kyo.foldLeft(Vector(1, 2, 3))(0)((acc, i) => TestEffect1(acc + i))).eval == 9)
            assert(TestEffect1.run(Kyo.foldLeft(Vector.fill(100)(1))(0)((acc, i) => TestEffect1(acc + i))).eval == 200)
        }
        "dropWhile" in {
            def f(i: Int): Boolean < Any = true

            def isEven(i: Int): Boolean < Any = i % 2 == 0

            def lessThanThree(i: Int): Boolean < Any = i < 3

            assert(Kyo.dropWhile(Vector.empty[Int])(a => f(a)).eval == Vector.empty[Int])

            assert(Kyo.dropWhile(Vector(1, 2, 3, 4))(x => x < 3).eval == Vector(3, 4))
            assert(Kyo.dropWhile(Vector(1, 2, 3))(x => f(x)).eval == Vector.empty[Int])
            assert(Kyo.dropWhile(Vector(2, 4, 5, 6))(x => isEven(x)).eval == Vector(5, 6))
            assert(Kyo.dropWhile(Vector(1, 2, 3, 4))(x => lessThanThree(x)).eval == Vector(3, 4))
            assert(Kyo.dropWhile(Vector(2, 4, 5))(x => isEven(x)).eval == Vector(5))
            assert(Kyo.dropWhile(Vector(1, 2, -1, 3))(x => lessThanThree(x)).eval == Vector(3))
            assert(Kyo.dropWhile(Vector(4, 1, 2))(x => lessThanThree(x)).eval == Vector(4, 1, 2))
        }

        "takeWhile" in {
            def f(i: Int): Boolean < Any             = true
            def isEven(i: Int): Boolean < Any        = i % 2 == 0
            def lessThanThree(i: Int): Boolean < Any = i < 3

            assert(Kyo.takeWhile(Vector.empty[Int])(x => f(x)).eval == Vector.empty[Int])
            assert(Kyo.takeWhile(Vector(1, 2, 3))(x => f(x)).eval == Vector(1, 2, 3))
            assert(Kyo.takeWhile(Vector(2, 4, 5, 6))(x => isEven(x)).eval == Vector(2, 4))
            assert(Kyo.takeWhile(Vector(1, 2, 3, 4))(x => lessThanThree(x)).eval == Vector(1, 2))
            assert(Kyo.takeWhile(Vector(2, 4, 5))(x => isEven(x)).eval == Vector(2, 4))
            assert(Kyo.takeWhile(Vector(1, 2, -1, 3))(x => lessThanThree(x)).eval == Vector(1, 2, -1))
            assert(Kyo.takeWhile(Vector(4, 1, 2))(x => lessThanThree(x)).eval == Vector.empty[Int])
        }

        "shiftedWhile" in {
            def isEven(i: Int): Boolean < Any = i % 2 == 0

            def lessThanThree(i: Int): Boolean < Any = i < 3

            val countWhile = Kyo.shiftedWhile(Vector(1, 2, 3, 4, 5))(
                prolog = 0,
                f = lessThanThree,
                acc = (count, include, _) => count + 1,
                epilog = identity
            )
            assert(countWhile.eval == 3)

            val sumWithMessage = Kyo.shiftedWhile(Vector(1, 2, -1, 3, 4))(
                prolog = 0,
                f = lessThanThree,
                acc = (sum, include, curr) => if include then sum + curr else sum,
                epilog = sum => s"Sum: $sum"
            )
            assert(sumWithMessage.eval == "Sum: 2") // 1 + 2 + (-1) = 2

            val collectUntilOdd = Kyo.shiftedWhile(Vector(2, 4, 6, 7, 8))(
                prolog = Vector.empty,
                f = isEven,
                acc = (list, include, curr) => if include then curr +: list else list,
                epilog = _.reverse // Maintain original order
            )
            assert(collectUntilOdd.eval == Vector(2, 4, 6))

            val emptyTest = Kyo.shiftedWhile(Vector.empty[Int])(
                prolog = "Default",
                f = _ => true,
                acc = (str, _, curr) => str + curr.toString,
                epilog = _.toUpperCase
            )
            assert(emptyTest.eval == "DEFAULT")
        }

        "span" - {
            "empty sequence" in {
                val result = Kyo.span(Vector.empty[Int])(x => x < 3).eval
                assert(result == (Vector.empty[Int], Vector.empty[Int]))
            }

            "all elements satisfy predicate" in {
                val result = Kyo.span(Vector(1, 2))(x => x < 3).eval
                assert(result == (Vector(1, 2), Vector.empty[Int]))
            }

            "no elements satisfy predicate" in {
                val result = Kyo.span(Vector(3, 4))(x => x < 3).eval
                assert(result == (Vector.empty[Int], Vector(3, 4)))
            }

            "split in middle" in {
                val result = Kyo.span(Vector(1, 2, 3, 4))(x => x < 3).eval
                assert(result == (Vector(1, 2), Vector(3, 4)))
            }

            "works with effects" in {
                val result = TestEffect1.run(
                    Kyo.span(Vector(1, 2, 3, 4))(x => TestEffect1(x).map(_ < 3))
                ).eval
                assert(result == (Vector(1), Vector(2, 3, 4)))
            }
        }

        "partition" - {
            "empty sequence" in {
                val result = Kyo.partition(Vector.empty[Int])(x => x % 2 == 0).eval
                assert(result == (Vector.empty[Int], Vector.empty[Int]))
            }

            "all elements satisfy predicate" in {
                val result = Kyo.partition(Vector(2, 4, 6))(x => x % 2 == 0).eval
                assert(result == (Vector(2, 4, 6), Vector.empty[Int]))
            }

            "no elements satisfy predicate" in {
                val result = Kyo.partition(Vector(1, 3, 5))(x => x % 2 == 0).eval
                assert(result == (Vector.empty[Int], Vector(1, 3, 5)))
            }

            "mixed elements" in {
                val result = Kyo.partition(Vector(1, 2, 3, 4))(x => x % 2 == 0).eval
                assert(result == (Vector(2, 4), Vector(1, 3)))
            }

            "works with effects" in {
                val result = TestEffect1.run(
                    Kyo.partition(Vector(1, 2, 3, 4))(x => TestEffect1(x).map(_ % 2 == 0))
                ).eval
                assert(result == (Vector(1, 3), Vector(2, 4)))
            }
        }

        "partitionMap" - {
            "empty sequence" in {
                val result = Kyo.partitionMap(Vector.empty[Int])(x => Left(x.toString)).eval
                assert(result == (Vector.empty[String], Vector.empty[Int]))
            }

            "all Left" in {
                val result = Kyo.partitionMap(Vector(1, 2, 3))(x => Left(x.toString)).eval
                assert(result == (Vector("1", "2", "3"), Vector.empty[Int]))
            }

            "all Right" in {
                val result = Kyo.partitionMap(Vector(1, 2, 3))(x => Right(x * 2)).eval
                assert(result == (Vector.empty[Int], Vector(2, 4, 6)))
            }

            "mixed Left and Right" in {
                val result = Kyo.partitionMap(Vector(1, 2, 3, 4))(x =>
                    if x % 2 == 0 then Right(x * 2) else Left(x.toString)
                ).eval
                assert(result == (Vector("1", "3"), Vector(4, 8)))
            }

            "works with effects" in {
                val result = TestEffect1.run(
                    Kyo.partitionMap(Vector(1, 2, 3, 4))(x =>
                        TestEffect1(x).map(v => if v % 2 == 0 then Right(v * 2) else Left(v.toString))
                    )
                ).eval
                assert(result == (Vector("3", "5"), Vector(4, 8)))
            }
        }

        "scanLeft" - {
            "empty sequence" in {
                val result = Kyo.scanLeft(Vector.empty[Int])(0)((acc, x) => acc + x).eval
                assert(result == Vector(0))
            }

            "single element" in {
                val result = Kyo.scanLeft(Vector(1))(0)((acc, x) => acc + x).eval
                assert(result == Vector(0, 1))
            }

            "multiple elements" in {
                val result = Kyo.scanLeft(Vector(1, 2, 3))(0)((acc, x) => acc + x).eval
                assert(result == Vector(0, 1, 3, 6))
            }

            "works with effects" in {
                val result = TestEffect1.run(
                    Kyo.scanLeft(Vector(1, 2, 3))(0)((acc, x) => TestEffect1(acc + x))
                ).eval
                assert(result == Vector(0, 2, 5, 9))
            }
        }

        "groupBy" - {
            "empty sequence" in {
                val result = Kyo.groupBy(Vector.empty[Int])(x => x % 2).eval
                assert(result == Map.empty)
            }

            "single group" in {
                val result = Kyo.groupBy(Vector(2, 4, 6))(x => x % 2).eval
                assert(result == Map(0 -> Vector(2, 4, 6)))
            }

            "multiple groups" in {
                val result = Kyo.groupBy(Vector(1, 2, 3, 4))(x => x % 2).eval
                assert(result == Map(
                    1 -> Vector(1, 3),
                    0 -> Vector(2, 4)
                ))
            }

            "works with effects" in {
                val result = TestEffect1.run(
                    Kyo.groupBy(Vector(1, 2, 3, 4))(x => TestEffect1(x).map(_ % 2))
                ).eval
                assert(result == Map(
                    0 -> Vector(1, 3),
                    1 -> Vector(2, 4)
                ))
            }
        }

        "groupMap" - {
            "empty sequence" in {
                val result = Kyo.groupMap(Vector.empty[Int])(x => x % 2)(x => x * 2).eval
                assert(result == Map.empty)
            }

            "single group" in {
                val result = Kyo.groupMap(Vector(2, 4, 6))(x => x % 2)(x => x * 2).eval
                assert(result == Map(0 -> Vector(4, 8, 12)))
            }

            "multiple groups" in {
                val result = Kyo.groupMap(Vector(1, 2, 3, 4))(x => x % 2)(x => x * 2).eval
                assert(result == Map(
                    1 -> Vector(2, 6),
                    0 -> Vector(4, 8)
                ))
            }

            "works with effects" in {
                val result = TestEffect1.run(
                    Kyo.groupMap(Vector(1, 2, 3, 4))(x => TestEffect1(x).map(_ % 2))(x => TestEffect1(x * 2))
                ).eval
                assert(result == Map(
                    0 -> Vector(3, 7),
                    1 -> Vector(5, 9)
                ))
            }
        }

        "collect" - {
            "empty sequence" in {
                val result = Kyo.collect(Vector.empty[Int])(v => Maybe(v)).eval
                assert(result == Vector.empty[Int])
            }

            "single element - present" in {
                val result = Kyo.collect(Vector(1))(v => Maybe(v)).eval
                assert(result == Vector(1))
            }

            "single element - absent" in {
                val result = Kyo.collect(Vector(1))(v => Maybe.empty).eval
                assert(result == Vector.empty[Int])
            }

            "multiple elements - all present" in {
                val result = Kyo.collect(Vector(1, 2, 3))(v => Maybe(v)).eval
                assert(result == Vector(1, 2, 3))
            }

            "multiple elements - some absent" in {
                val result = Kyo.collect(Vector(1, 2, 3))(v => if v % 2 == 0 then Maybe(v) else Maybe.empty).eval
                assert(result == Vector(2))
            }

            "works with effects" in {
                val result = TestEffect1.run(
                    Kyo.collect(Vector(1, 2, 3)) { v =>
                        TestEffect1(v).map(r => Maybe.when(r % 2 == 0)(r))
                    }
                ).eval
                assert(result == Vector(2, 4))
            }

            "stack safety" in {
                val n      = 1000
                val result = Kyo.collect(Vector.from(0 until n))(v => Maybe(v)).eval
                assert(result.size == n)
                assert(result == Vector.from(0 until n))
            }
        }

        "stack safety" - {
            val n = 1000

            "collect" in {
                assert(TestEffect1.run(Kyo.collectAll(Vector.fill(n)(TestEffect1(1)))).map(_.size).eval == n)
            }

            "collectDiscard" in {
                var count = 0
                val io    = TestEffect1(1).map(_ => count += 1)
                TestEffect1.run(Kyo.collectAllDiscard(Vector.fill(n)(io))).eval
                assert(count == n)
            }

            "foreach" in {
                assert(TestEffect1.run(Kyo.foreach(Vector.fill(n)(1))(i => TestEffect1(i))).map(_.size).eval == n)
            }

            "foreachDiscard" in {
                var acc = Vector.empty[Int]
                TestEffect1.run(Kyo.foreachDiscard(Vector.fill(n)(1))(v => TestEffect1(v).map(i => acc :+= i))).eval
                assert(acc.size == n)
            }
        }
    }

    "Chunk specialized" - {
        "collectAll" in {
            assert(Kyo.collectAll(Chunk.empty).eval == Chunk.empty)
            assert(TestEffect1.run(Kyo.collectAll(Chunk(TestEffect1(1))).map(_.head)).eval == 2)
            assert(TestEffect2.run(TestEffect1.run(Kyo.collectAll(Chunk(TestEffect1(1), TestEffect1(2))).map(c =>
                (c(0), c(1))
            ))).eval == (
                2,
                3
            ))
            assert(TestEffect1.run(Kyo.collectAll(Chunk.fill(100)(TestEffect1(1))).map(_.size)).eval == 100)
        }
        "collectDiscard" in {
            var count = 0
            val io    = TestEffect1(1).map(_ => count += 1)
            TestEffect1.run(Kyo.collectAllDiscard(Chunk.empty)).eval
            assert(count == 0)
            TestEffect1.run(Kyo.collectAllDiscard(Chunk(io))).eval
            assert(count == 1)
            TestEffect1.run(Kyo.collectAllDiscard(Chunk.fill(42)(io))).eval
            assert(count == 43)
            TestEffect1.run(Kyo.collectAllDiscard(Chunk.fill(10)(io))).eval
            assert(count == 53)
        }
        "foreach" in {
            assert(TestEffect1.run(Kyo.foreach(Chunk.empty)(i => TestEffect1(i))).eval == Chunk.empty)
            assert(TestEffect1.run(Kyo.foreach(Chunk(1))(i => TestEffect1(i))).map(_.head).eval == 2)
            assert(TestEffect1.run(Kyo.foreach(Chunk(1, 2))(i => TestEffect1(i))).map(c => (c(0), c(1))).eval == (2, 3))
            assert(TestEffect1.run(Kyo.foreach(Chunk.fill(100)(1))(i => TestEffect1(i))).map(_.size).eval == 100)
        }
        "foreachIndexed" in {
            assert(Kyo.foreachIndexed(Chunk.empty[Int])((idx, v) => (idx, v)).eval == Chunk.empty)
            assert(Kyo.foreachIndexed(Chunk(1))((idx, v) => (idx, v)).eval == Chunk((0, 1)))
            assert(Kyo.foreachIndexed(Chunk(1, 2))((idx, v) => (idx, v)).eval == Chunk((0, 1), (1, 2)))
            assert(Kyo.foreachIndexed(Chunk(1, 2, 3))((idx, v) => (idx, v)).eval == Chunk((0, 1), (1, 2), (2, 3)))
            // Test with a larger sequence
            assert(Kyo.foreachIndexed(Chunk.tabulate(100)(identity))((idx, v) => idx == v).eval == Chunk.fill(100)(true))
        }
        "foreachDiscard" in {
            var acc: Chunk[Int] = Chunk.empty
            TestEffect1.run(Kyo.foreachDiscard(Chunk.empty[Int])(v => TestEffect1(v).map(i => acc :+= i))).eval
            assert(acc == Chunk.empty[Int])
            acc = Chunk.empty
            TestEffect1.run(Kyo.foreachDiscard(Chunk(1))(v => TestEffect1(v).map(i => acc :+= i))).eval
            assert(acc == Chunk(2))
            acc = Chunk.empty
            TestEffect1.run(Kyo.foreachDiscard(Chunk(1, 2))(v => TestEffect1(v).map(i => acc :+= i))).eval
            assert(acc == Chunk(2, 3))
        }
        "foldLeft" in {
            assert(TestEffect1.run(Kyo.foldLeft(Chunk.empty[Int])(0)((acc, i) => TestEffect1(acc + i))).eval == 0)
            assert(TestEffect1.run(Kyo.foldLeft(Chunk(1))(0)((acc, i) => TestEffect1(acc + i))).eval == 2)
            assert(TestEffect1.run(Kyo.foldLeft(Chunk(1, 2, 3))(0)((acc, i) => TestEffect1(acc + i))).eval == 9)
            assert(TestEffect1.run(Kyo.foldLeft(Chunk.fill(100)(1))(0)((acc, i) => TestEffect1(acc + i))).eval == 200)
        }
        "dropWhile" in {
            def f(i: Int): Boolean < Any = true

            def isEven(i: Int): Boolean < Any = i % 2 == 0

            def lessThanThree(i: Int): Boolean < Any = i < 3

            assert(Kyo.dropWhile(Chunk.empty[Int])(a => f(a)).eval == Chunk.empty[Int])

            assert(Kyo.dropWhile(Chunk(1, 2, 3, 4))(x => x < 3).eval == Chunk(3, 4))
            assert(Kyo.dropWhile(Chunk(1, 2, 3))(x => f(x)).eval == Chunk.empty[Int])
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

            assert(Kyo.takeWhile(Chunk.empty[Int])(x => f(x)).eval == Chunk.empty[Int])
            assert(Kyo.takeWhile(Chunk(1, 2, 3))(x => f(x)).eval == Chunk(1, 2, 3))
            assert(Kyo.takeWhile(Chunk(2, 4, 5, 6))(x => isEven(x)).eval == Chunk(2, 4))
            assert(Kyo.takeWhile(Chunk(1, 2, 3, 4))(x => lessThanThree(x)).eval == Chunk(1, 2))
            assert(Kyo.takeWhile(Chunk(2, 4, 5))(x => isEven(x)).eval == Chunk(2, 4))
            assert(Kyo.takeWhile(Chunk(1, 2, -1, 3))(x => lessThanThree(x)).eval == Chunk(1, 2, -1))
            assert(Kyo.takeWhile(Chunk(4, 1, 2))(x => lessThanThree(x)).eval == Chunk.empty[Int])
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
                prolog = Chunk.empty,
                f = isEven,
                acc = (list, include, curr) => if include then curr +: list else list,
                epilog = _.reverse // Maintain original order
            )
            assert(collectUntilOdd.eval == Chunk(2, 4, 6))

            val emptyTest = Kyo.shiftedWhile(Chunk.empty[Int])(
                prolog = "Default",
                f = _ => true,
                acc = (str, _, curr) => str + curr.toString,
                epilog = _.toUpperCase
            )
            assert(emptyTest.eval == "DEFAULT")
        }

        "span" - {
            "empty sequence" in {
                val result = Kyo.span(Chunk.empty[Int])(x => x < 3).eval
                assert(result == (Chunk.empty[Int], Chunk.empty[Int]))
            }

            "all elements satisfy predicate" in {
                val result = Kyo.span(Chunk(1, 2))(x => x < 3).eval
                assert(result == (Chunk(1, 2), Chunk.empty[Int]))
            }

            "no elements satisfy predicate" in {
                val result = Kyo.span(Chunk(3, 4))(x => x < 3).eval
                assert(result == (Chunk.empty[Int], Chunk(3, 4)))
            }

            "split in middle" in {
                val result = Kyo.span(Chunk(1, 2, 3, 4))(x => x < 3).eval
                assert(result == (Chunk(1, 2), Chunk(3, 4)))
            }

            "works with effects" in {
                val result = TestEffect1.run(
                    Kyo.span(Chunk(1, 2, 3, 4))(x => TestEffect1(x).map(_ < 3))
                ).eval
                assert(result == (Chunk(1), Chunk(2, 3, 4)))
            }
        }

        "partition" - {
            "empty sequence" in {
                val result = Kyo.partition(Chunk.empty[Int])(x => x % 2 == 0).eval
                assert(result == (Chunk.empty[Int], Chunk.empty[Int]))
            }

            "all elements satisfy predicate" in {
                val result = Kyo.partition(Chunk(2, 4, 6))(x => x % 2 == 0).eval
                assert(result == (Chunk(2, 4, 6), Chunk.empty[Int]))
            }

            "no elements satisfy predicate" in {
                val result = Kyo.partition(Chunk(1, 3, 5))(x => x % 2 == 0).eval
                assert(result == (Chunk.empty[Int], Chunk(1, 3, 5)))
            }

            "mixed elements" in {
                val result = Kyo.partition(Chunk(1, 2, 3, 4))(x => x % 2 == 0).eval
                assert(result == (Chunk(2, 4), Chunk(1, 3)))
            }

            "works with effects" in {
                val result = TestEffect1.run(
                    Kyo.partition(Chunk(1, 2, 3, 4))(x => TestEffect1(x).map(_ % 2 == 0))
                ).eval
                assert(result == (Chunk(1, 3), Chunk(2, 4)))
            }
        }

        "partitionMap" - {
            "empty sequence" in {
                val result = Kyo.partitionMap(Chunk.empty[Int])(x => Left(x.toString)).eval
                assert(result == (Chunk.empty[String], Chunk.empty[Int]))
            }

            "all Left" in {
                val result = Kyo.partitionMap(Chunk(1, 2, 3))(x => Left(x.toString)).eval
                assert(result == (Chunk("1", "2", "3"), Chunk.empty[Int]))
            }

            "all Right" in {
                val result = Kyo.partitionMap(Chunk(1, 2, 3))(x => Right(x * 2)).eval
                assert(result == (Chunk.empty[Int], Chunk(2, 4, 6)))
            }

            "mixed Left and Right" in {
                val result = Kyo.partitionMap(Chunk(1, 2, 3, 4))(x =>
                    if x % 2 == 0 then Right(x * 2) else Left(x.toString)
                ).eval
                assert(result == (Chunk("1", "3"), Chunk(4, 8)))
            }

            "works with effects" in {
                val result = TestEffect1.run(
                    Kyo.partitionMap(Chunk(1, 2, 3, 4))(x =>
                        TestEffect1(x).map(v => if v % 2 == 0 then Right(v * 2) else Left(v.toString))
                    )
                ).eval
                assert(result == (Chunk("3", "5"), Chunk(4, 8)))
            }
        }

        "scanLeft" - {
            "empty sequence" in {
                val result = Kyo.scanLeft(Chunk.empty[Int])(0)((acc, x) => acc + x).eval
                assert(result == Chunk(0))
            }

            "single element" in {
                val result = Kyo.scanLeft(Chunk(1))(0)((acc, x) => acc + x).eval
                assert(result == Chunk(0, 1))
            }

            "multiple elements" in {
                val result = Kyo.scanLeft(Chunk(1, 2, 3))(0)((acc, x) => acc + x).eval
                assert(result == Chunk(0, 1, 3, 6))
            }

            "works with effects" in {
                val result = TestEffect1.run(
                    Kyo.scanLeft(Chunk(1, 2, 3))(0)((acc, x) => TestEffect1(acc + x))
                ).eval
                assert(result == Chunk(0, 2, 5, 9))
            }
        }

        "groupBy" - {
            "empty sequence" in {
                val result = Kyo.groupBy(Chunk.empty[Int])(x => x % 2).eval
                assert(result == Map.empty)
            }

            "single group" in {
                val result = Kyo.groupBy(Chunk(2, 4, 6))(x => x % 2).eval
                assert(result == Map(0 -> Chunk(2, 4, 6)))
            }

            "multiple groups" in {
                val result = Kyo.groupBy(Chunk(1, 2, 3, 4))(x => x % 2).eval
                assert(result == Map(
                    1 -> Chunk(1, 3),
                    0 -> Chunk(2, 4)
                ))
            }

            "works with effects" in {
                val result = TestEffect1.run(
                    Kyo.groupBy(Chunk(1, 2, 3, 4))(x => TestEffect1(x).map(_ % 2))
                ).eval
                assert(result == Map(
                    0 -> Chunk(1, 3),
                    1 -> Chunk(2, 4)
                ))
            }
        }

        "groupMap" - {
            "empty sequence" in {
                val result = Kyo.groupMap(Chunk.empty[Int])(x => x % 2)(x => x * 2).eval
                assert(result == Map.empty)
            }

            "single group" in {
                val result = Kyo.groupMap(Chunk(2, 4, 6))(x => x % 2)(x => x * 2).eval
                assert(result == Map(0 -> Chunk(4, 8, 12)))
            }

            "multiple groups" in {
                val result = Kyo.groupMap(Chunk(1, 2, 3, 4))(x => x % 2)(x => x * 2).eval
                assert(result == Map(
                    1 -> Chunk(2, 6),
                    0 -> Chunk(4, 8)
                ))
            }

            "works with effects" in {
                val result = TestEffect1.run(
                    Kyo.groupMap(Chunk(1, 2, 3, 4))(x => TestEffect1(x).map(_ % 2))(x => TestEffect1(x * 2))
                ).eval
                assert(result == Map(
                    0 -> Chunk(3, 7),
                    1 -> Chunk(5, 9)
                ))
            }
        }

        "collect" - {
            "empty sequence" in {
                val result = Kyo.collect(Chunk.empty[Int])(v => Maybe(v)).eval
                assert(result == Chunk.empty[Int])
            }

            "single element - present" in {
                val result = Kyo.collect(Chunk(1))(v => Maybe(v)).eval
                assert(result == Chunk(1))
            }

            "single element - absent" in {
                val result = Kyo.collect(Chunk(1))(v => Maybe.empty).eval
                assert(result == Chunk.empty[Int])
            }

            "multiple elements - all present" in {
                val result = Kyo.collect(Chunk(1, 2, 3))(v => Maybe(v)).eval
                assert(result == Chunk(1, 2, 3))
            }

            "multiple elements - some absent" in {
                val result = Kyo.collect(Chunk(1, 2, 3))(v => if v % 2 == 0 then Maybe(v) else Maybe.empty).eval
                assert(result == Chunk(2))
            }

            "works with effects" in {
                val result = TestEffect1.run(
                    Kyo.collect(Chunk(1, 2, 3)) { v =>
                        TestEffect1(v).map(r => Maybe.when(r % 2 == 0)(r))
                    }
                ).eval
                assert(result == Chunk(2, 4))
            }

            "stack safety" in {
                val n      = 1000
                val result = Kyo.collect(Chunk.from(0 until n))(v => Maybe(v)).eval
                assert(result.size == n)
                assert(result == Chunk.from(0 until n))
            }
        }

        "stack safety" - {
            val n = 1000

            "collect" in {
                assert(TestEffect1.run(Kyo.collectAll(Chunk.fill(n)(TestEffect1(1)))).map(_.size).eval == n)
            }

            "collectDiscard" in {
                var count = 0
                val io    = TestEffect1(1).map(_ => count += 1)
                TestEffect1.run(Kyo.collectAllDiscard(Chunk.fill(n)(io))).eval
                assert(count == n)
            }

            "foreach" in {
                assert(TestEffect1.run(Kyo.foreach(Chunk.fill(n)(1))(i => TestEffect1(i))).map(_.size).eval == n)
            }

            "foreachDiscard" in {
                var acc = Chunk.empty[Int]
                TestEffect1.run(Kyo.foreachDiscard(Chunk.fill(n)(1))(v => TestEffect1(v).map(i => acc :+= i))).eval
                assert(acc.size == n)
            }
        }
    }

end KyoForeachTest

object KyoForeachTest:
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
end KyoForeachTest
