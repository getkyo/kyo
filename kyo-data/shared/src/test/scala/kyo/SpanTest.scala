package kyo

import scala.util.Try

class SpanTest extends Test:

    "Span.apply" - {
        "creates a Span from varargs" in {
            val arr = Span(1, 2, 3)
            assert(arr.size == 3)
            assert(arr(0) == 1)
            assert(arr(1) == 2)
            assert(arr(2) == 3)
        }

        "creates an empty Span when no arguments are provided" in {
            val arr = Span[Int]()
            assert(arr.isEmpty)
        }
    }

    "Span.empty" - {
        "creates an empty Span" in {
            val arr = Span.empty[Int]
            assert(arr.isEmpty)
        }

        "returns cached empty arrays for primitive types" in {
            val booleanChain = Span.empty[Boolean]
            val byteChain    = Span.empty[Byte]
            val charChain    = Span.empty[Char]
            val doubleChain  = Span.empty[Double]
            val floatChain   = Span.empty[Float]
            val intChain     = Span.empty[Int]
            val longChain    = Span.empty[Long]
            val shortChain   = Span.empty[Short]

            assert(booleanChain.isEmpty)
            assert(byteChain.isEmpty)
            assert(charChain.isEmpty)
            assert(doubleChain.isEmpty)
            assert(floatChain.isEmpty)
            assert(intChain.isEmpty)
            assert(longChain.isEmpty)
            assert(shortChain.isEmpty)
        }
    }

    "Span.from" - {
        "Array" - {
            "creates a Span from a non-empty Array" in {
                val array = Array("a", "b", "c")
                val arr   = Span.from(array)
                assert(arr.size == 3)
                assert(arr(0) == "a")
                assert(arr(1) == "b")
                assert(arr(2) == "c")
            }

            "creates an empty Span from an empty Array" in {
                val array = Array.empty[String]
                val arr   = Span.from(array)
                assert(arr.isEmpty)
            }

            "creates a copy of the underlying array" in {
                val array = Array("a", "b", "c")
                val arr   = Span.from(array)
                array(0) = "x"
                assert(arr(0) == "a")
            }
        }

        "Seq" - {
            "creates a Span from a non-empty Seq" in {
                val seq = Seq("a", "b", "c")
                val arr = Span.from(seq)
                assert(arr.size == 3)
                assert(arr(0) == "a")
                assert(arr(1) == "b")
                assert(arr(2) == "c")
            }

            "creates an empty Span from an empty Seq" in {
                val seq = Seq.empty[String]
                val arr = Span.from(seq)
                assert(arr.isEmpty)
            }

            "handles different Seq types" - {
                "List" in {
                    val list = List(1, 2, 3)
                    val arr  = Span.from(list)
                    assert(arr.size == 3)
                    assert(arr(0) == 1)
                    assert(arr(1) == 2)
                    assert(arr(2) == 3)
                }

                "Vector" in {
                    val vector = Vector(1, 2, 3)
                    val arr    = Span.from(vector)
                    assert(arr.size == 3)
                    assert(arr(0) == 1)
                    assert(arr(1) == 2)
                    assert(arr(2) == 3)
                }
            }
        }
    }

    "Span.fromUnsafe" - {
        "Array" - {
            "creates a Span from a non-empty Array" in {
                val array = Array("a", "b", "c")
                val arr   = Span.fromUnsafe(array)
                assert(arr.size == 3)
                assert(arr(0) == "a")
                assert(arr(1) == "b")
                assert(arr(2) == "c")
            }

            "creates an empty Span from an empty Array" in {
                val array = Array.empty[String]
                val arr   = Span.fromUnsafe(array)
                assert(arr.isEmpty)
            }

            "shares the same underlying array" in {
                val array = Array("a", "b", "c")
                val arr   = Span.fromUnsafe(array)
                array(0) = "x"
                assert(arr(0) == "x")
            }
        }

        "Seq" - {
            "creates a Span from a non-empty Seq" in {
                val seq = Seq("a", "b", "c")
                val arr = Span.from(seq)
                assert(arr.size == 3)
                assert(arr(0) == "a")
                assert(arr(1) == "b")
                assert(arr(2) == "c")
            }

            "creates an empty Span from an empty Seq" in {
                val seq = Seq.empty[String]
                val arr = Span.from(seq)
                assert(arr.isEmpty)
            }

            "handles different Seq types" - {
                "List" in {
                    val list = List(1, 2, 3)
                    val arr  = Span.from(list)
                    assert(arr.size == 3)
                    assert(arr(0) == 1)
                    assert(arr(1) == 2)
                    assert(arr(2) == 3)
                }

                "Vector" in {
                    val vector = Vector(1, 2, 3)
                    val arr    = Span.from(vector)
                    assert(arr.size == 3)
                    assert(arr(0) == 1)
                    assert(arr(1) == 2)
                    assert(arr(2) == 3)
                }
            }
        }
    }

    "size" - {
        "returns the number of elements in a non-empty Span" in {
            val arr = Span(1, 2, 3, 4, 5)
            assert(arr.size == 5)
        }

        "returns 0 for an empty Span" in {
            val arr = Span.empty[Int]
            assert(arr.size == 0)
        }
    }

    "isEmpty" - {
        "returns false for a non-empty Span" in {
            val arr = Span(1, 2, 3)
            assert(!arr.isEmpty)
        }

        "returns true for an empty Span" in {
            val arr = Span.empty[Int]
            assert(arr.isEmpty)
        }
    }

    "nonEmpty" - {
        "returns true for a non-empty Span" in {
            val arr = Span(1, 2, 3)
            assert(arr.nonEmpty)
        }

        "returns false for an empty Span" in {
            val arr = Span.empty[Int]
            assert(!arr.nonEmpty)
        }
    }

    "apply" - {
        "returns correct elements from a Span" in {
            val arr = Span(1, 2, 3, 4, 5)
            assert(arr(0) == 1)
            assert(arr(2) == 3)
            assert(arr(4) == 5)
        }

        "throws for negative index" in {
            val arr = Span(1, 2, 3)
            assertThrows[Throwable] {
                arr(-1)
            }
        }

        "throws for index >= size" in {
            val arr = Span(1, 2, 3)
            assertThrows[Throwable] {
                arr(3)
            }
        }
    }

    "head" - {
        "returns Present with first element for non-empty Span" in {
            val arr = Span(1, 2, 3)
            assert(arr.head == Present(1))
        }

        "returns Absent for empty Span" in {
            val arr = Span.empty[Int]
            assert(arr.head == Absent)
        }
    }

    "last" - {
        "returns Present with last element for non-empty Span" in {
            val arr = Span(1, 2, 3)
            assert(arr.last == Present(3))
        }

        "returns Absent for empty Span" in {
            val arr = Span.empty[Int]
            assert(arr.last == Absent)
        }

        "returns first element for single-element Span" in {
            val arr = Span(42)
            assert(arr.last == Present(42))
        }
    }

    "tail" - {
        "returns Present with all elements except first for non-empty Span" in {
            val arr        = Span(1, 2, 3, 4)
            val tailResult = arr.tail
            assert(tailResult.isDefined)
            tailResult.foreach { tail =>
                assert(tail.size == 3)
                assert(tail(0) == 2)
                assert(tail(1) == 3)
                assert(tail(2) == 4)
                ()
            }
            succeed
        }

        "returns Absent for empty Span" in {
            val arr = Span.empty[Int]
            assert(arr.tail == Absent)
        }

        "returns empty Span for single-element Span" in {
            val arr        = Span(42)
            val tailResult = arr.tail
            assert(tailResult.isDefined)
            tailResult.foreach { tail =>
                assert(tail.isEmpty)
                ()
            }
            succeed
        }
    }

    "contains" - {
        "returns true when element is present" in {
            val arr = Span(1, 2, 3, 4, 5)
            assert(arr.contains(3))
            assert(arr.contains(1))
            assert(arr.contains(5))
        }

        "returns false when element is not present" in {
            val arr = Span(1, 2, 3, 4, 5)
            assert(!arr.contains(6))
            assert(!arr.contains(0))
        }

        "returns false for empty Span" in {
            val arr = Span.empty[Int]
            assert(!arr.contains(1))
        }
    }

    "indexOf" - {
        "returns correct index when element is found" in {
            val arr = Span(1, 2, 3, 2, 5)
            assert(arr.indexOf(2).contains(1))
            assert(arr.indexOf(1).contains(0))
            assert(arr.indexOf(5).contains(4))
        }

        "returns Absent when element is not found" in {
            val arr = Span(1, 2, 3, 4, 5)
            assert(arr.indexOf(6).isEmpty)
        }

        "searches from specified index" in {
            val arr = Span(1, 2, 3, 2, 5)
            assert(arr.indexOf(2, 2).contains(3))
        }

        "returns Absent for empty Span" in {
            val arr = Span.empty[Int]
            assert(arr.indexOf(1).isEmpty)
        }
    }

    "lastIndexOf" - {
        "returns correct index when element is found" in {
            val arr = Span(1, 2, 3, 2, 5)
            assert(arr.lastIndexOf(2).contains(3))
            assert(arr.lastIndexOf(1).contains(0))
            assert(arr.lastIndexOf(5).contains(4))
        }

        "returns Absent when element is not found" in {
            val arr = Span(1, 2, 3, 4, 5)
            assert(arr.lastIndexOf(6).isEmpty)
        }

        "searches backwards from specified index" in {
            val arr = Span(1, 2, 3, 2, 5)
            assert(arr.lastIndexOf(2, 2).contains(1))
        }

        "returns Absent for empty Span" in {
            val arr = Span.empty[Int]
            assert(arr.lastIndexOf(1).isEmpty)
        }
    }

    "indexWhere" - {
        "returns correct index when predicate is satisfied" in {
            val arr = Span(1, 2, 3, 4, 5)
            assert(arr.indexWhere(_ > 3).contains(3))
            assert(arr.indexWhere(_ % 2 == 0).contains(1))
        }

        "returns Absent when predicate is never satisfied" in {
            val arr = Span(1, 2, 3, 4, 5)
            assert(arr.indexWhere(_ > 10).isEmpty)
        }

        "searches from specified index" in {
            val arr = Span(1, 2, 3, 4, 5)
            assert(arr.indexWhere(_ % 2 == 0, 3).contains(3))
        }
    }

    "lastIndexWhere" - {
        "returns correct index when predicate is satisfied" in {
            val arr = Span(1, 2, 3, 4, 5)
            assert(arr.lastIndexWhere(_ % 2 == 0).contains(3))
            assert(arr.lastIndexWhere(_ > 3).contains(4))
        }

        "returns Absent when predicate is never satisfied" in {
            val arr = Span(1, 2, 3, 4, 5)
            assert(arr.lastIndexWhere(_ > 10).isEmpty)
        }

        "searches backwards from specified index" in {
            val arr = Span(1, 2, 3, 4, 5)
            assert(arr.lastIndexWhere(_ % 2 == 0, 2).contains(1))
        }
    }

    "find" - {
        "returns Present with first matching element" in {
            val arr = Span(1, 2, 3, 4, 5)
            assert(arr.find(_ % 2 == 0) == Present(2))
            assert(arr.find(_ > 3) == Present(4))
        }

        "returns Absent when no element matches" in {
            val arr = Span(1, 2, 3, 4, 5)
            assert(arr.find(_ > 10) == Absent)
        }

        "returns Absent for empty Span" in {
            val arr = Span.empty[Int]
            assert(arr.find(_ => true) == Absent)
        }
    }

    "count" - {
        "returns correct count of matching elements" in {
            val arr = Span(1, 2, 3, 4, 5)
            assert(arr.count(_ % 2 == 0) == 2)
            assert(arr.count(_ > 3) == 2)
            assert(arr.count(_ > 0) == 5)
        }

        "returns 0 when no elements match" in {
            val arr = Span(1, 2, 3, 4, 5)
            assert(arr.count(_ > 10) == 0)
        }

        "returns 0 for empty Span" in {
            val arr = Span.empty[Int]
            assert(arr.count(_ => true) == 0)
        }
    }

    "foreach" - {
        "executes function for each element" in {
            val arr = Span(1, 2, 3)
            var sum = 0
            arr.foreach(sum += _)
            assert(sum == 6)
        }

        "does nothing for empty Span" in {
            val arr    = Span.empty[Int]
            var called = false
            arr.foreach(_ => called = true)
            assert(!called)
        }
    }

    "is" - {
        "returns true for equal Chains" in {
            val chain1 = Span(1, 2, 3)
            val chain2 = Span(1, 2, 3)
            assert(chain1.is(chain2))
        }

        "returns false for different Chains" in {
            val chain1 = Span(1, 2, 3)
            val chain2 = Span(3, 2, 1)
            assert(!chain1.is(chain2))
        }

        "returns true for empty Chains" in {
            val chain1 = Span.empty[Int]
            val chain2 = Span.empty[Int]
            assert(chain1.is(chain2))
        }

        "returns false for Chains of different sizes" in {
            val chain1 = Span(1, 2, 3)
            val chain2 = Span(1, 2)
            assert(!chain1.is(chain2))
        }
    }

    "forall" - {
        "returns true when all elements satisfy the predicate" in {
            val arr = Span(2, 4, 6, 8, 10)
            assert(arr.forall(_ % 2 == 0))
        }

        "returns false when at least one element does not satisfy the predicate" in {
            val arr = Span(2, 4, 5, 8, 10)
            assert(!arr.forall(_ % 2 == 0))
        }

        "returns true for an empty Span" in {
            val arr = Span.empty[Int]
            assert(arr.forall(_ => false))
        }
    }

    "exists" - {
        "returns true when at least one element satisfies the predicate" in {
            val arr = Span(1, 3, 5, 6, 9)
            assert(arr.exists(_ % 2 == 0))
        }

        "returns false when no element satisfies the predicate" in {
            val arr = Span(1, 3, 5, 7, 9)
            assert(!arr.exists(_ % 2 == 0))
        }

        "returns false for an empty Span" in {
            val arr = Span.empty[Int]
            assert(!arr.exists(_ => true))
        }
    }

    "map" - {
        "transforms all elements using the provided function" in {
            val arr    = Span(1, 2, 3, 4, 5)
            val result = arr.map(_ * 2)
            assert(result.size == 5)
            assert(result(0) == 2)
            assert(result(1) == 4)
            assert(result(2) == 6)
            assert(result(3) == 8)
            assert(result(4) == 10)
        }

        "returns an empty Span when mapping an empty Span" in {
            val arr    = Span.empty[Int]
            val result = arr.map(_ * 2)
            assert(result.isEmpty)
        }

        "handles type transformations" in {
            val arr    = Span(1, 2, 3)
            val result = arr.map(_.toString)
            assert(result.size == 3)
            assert(result(0) == "1")
            assert(result(1) == "2")
            assert(result(2) == "3")
        }
    }

    "flatMap" - {
        "flattens and transforms elements" in {
            val arr    = Span(1, 2, 3)
            val result = arr.flatMap(x => Span(x, x * 2))
            assert(result.size == 6)
            assert(result(0) == 1)
            assert(result(1) == 2)
            assert(result(2) == 2)
            assert(result(3) == 4)
            assert(result(4) == 3)
            assert(result(5) == 6)
        }

        "returns empty Span when original is empty" in {
            val arr    = Span.empty[Int]
            val result = arr.flatMap(x => Span(x, x * 2))
            assert(result.isEmpty)
        }

        "handles empty results from function" in {
            val arr    = Span(1, 2, 3)
            val result = arr.flatMap(_ => Span.empty[Int])
            assert(result.isEmpty)
        }
    }

    "filter" - {
        "selects elements that satisfy the predicate" in {
            val arr    = Span(1, 2, 3, 4, 5)
            val result = arr.filter(_ % 2 == 0)
            assert(result.size == 2)
            assert(result(0) == 2)
            assert(result(1) == 4)
        }

        "returns empty Span when no elements satisfy predicate" in {
            val arr    = Span(1, 3, 5)
            val result = arr.filter(_ % 2 == 0)
            assert(result.isEmpty)
        }

        "returns all elements when all satisfy predicate" in {
            val arr    = Span(2, 4, 6)
            val result = arr.filter(_ % 2 == 0)
            assert(result.size == 3)
            assert(result.is(arr))
        }
    }

    "filterNot" - {
        "selects elements that do not satisfy the predicate" in {
            val arr    = Span(1, 2, 3, 4, 5)
            val result = arr.filterNot(_ % 2 == 0)
            assert(result.size == 3)
            assert(result(0) == 1)
            assert(result(1) == 3)
            assert(result(2) == 5)
        }

        "returns empty Span when all elements satisfy predicate" in {
            val arr    = Span(2, 4, 6)
            val result = arr.filterNot(_ % 2 == 0)
            assert(result.isEmpty)
        }
    }

    "slice" - {
        "returns correct slice" in {
            val arr    = Span(1, 2, 3, 4, 5)
            val result = arr.slice(1, 4)
            assert(result.size == 3)
            assert(result(0) == 2)
            assert(result(1) == 3)
            assert(result(2) == 4)
        }

        "handles negative from index" in {
            val arr    = Span(1, 2, 3, 4, 5)
            val result = arr.slice(-1, 3)
            assert(result.size == 3)
            assert(result(0) == 1)
            assert(result(1) == 2)
            assert(result(2) == 3)
        }

        "handles until index beyond size" in {
            val arr    = Span(1, 2, 3)
            val result = arr.slice(1, 10)
            assert(result.size == 2)
            assert(result(0) == 2)
            assert(result(1) == 3)
        }

        "returns empty Span when from >= until" in {
            val arr    = Span(1, 2, 3)
            val result = arr.slice(2, 2)
            assert(result.isEmpty)
        }
    }

    "take" - {
        "takes first n elements" in {
            val arr    = Span(1, 2, 3, 4, 5)
            val result = arr.take(3)
            assert(result.size == 3)
            assert(result(0) == 1)
            assert(result(1) == 2)
            assert(result(2) == 3)
        }

        "takes all elements when n >= size" in {
            val arr    = Span(1, 2, 3)
            val result = arr.take(5)
            assert(result.size == 3)
            assert(result.is(arr))
        }

        "returns empty Span when n <= 0" in {
            val arr    = Span(1, 2, 3)
            val result = arr.take(0)
            assert(result.isEmpty)
        }
    }

    "takeRight" - {
        "takes last n elements" in {
            val arr    = Span(1, 2, 3, 4, 5)
            val result = arr.takeRight(3)
            assert(result.size == 3)
            assert(result(0) == 3)
            assert(result(1) == 4)
            assert(result(2) == 5)
        }

        "takes all elements when n >= size" in {
            val arr    = Span(1, 2, 3)
            val result = arr.takeRight(5)
            assert(result.size == 3)
            assert(result.is(arr))
        }

        "returns empty Span when n <= 0" in {
            val arr    = Span(1, 2, 3)
            val result = arr.takeRight(0)
            assert(result.isEmpty)
        }
    }

    "takeWhile" - {
        "takes elements while predicate is true" in {
            val arr    = Span(1, 2, 3, 4, 5)
            val result = arr.takeWhile(_ < 4)
            assert(result.size == 3)
            assert(result(0) == 1)
            assert(result(1) == 2)
            assert(result(2) == 3)
        }

        "returns all elements when predicate is always true" in {
            val arr    = Span(1, 2, 3)
            val result = arr.takeWhile(_ > 0)
            assert(result.size == 3)
            assert(result.is(arr))
        }

        "returns empty Span when first element fails predicate" in {
            val arr    = Span(1, 2, 3)
            val result = arr.takeWhile(_ > 5)
            assert(result.isEmpty)
        }
    }

    "drop" - {
        "drops first n elements" in {
            val arr    = Span(1, 2, 3, 4, 5)
            val result = arr.drop(2)
            assert(result.size == 3)
            assert(result(0) == 3)
            assert(result(1) == 4)
            assert(result(2) == 5)
        }

        "returns empty Span when n >= size" in {
            val arr    = Span(1, 2, 3)
            val result = arr.drop(5)
            assert(result.isEmpty)
        }

        "returns all elements when n <= 0" in {
            val arr    = Span(1, 2, 3)
            val result = arr.drop(0)
            assert(result.size == 3)
            assert(result.is(arr))
        }
    }

    "dropRight" - {
        "drops last n elements" in {
            val arr    = Span(1, 2, 3, 4, 5)
            val result = arr.dropRight(2)
            assert(result.size == 3)
            assert(result(0) == 1)
            assert(result(1) == 2)
            assert(result(2) == 3)
        }

        "returns empty Span when n >= size" in {
            val arr    = Span(1, 2, 3)
            val result = arr.dropRight(5)
            assert(result.isEmpty)
        }

        "returns all elements when n <= 0" in {
            val arr    = Span(1, 2, 3)
            val result = arr.dropRight(0)
            assert(result.size == 3)
            assert(result.is(arr))
        }
    }

    "dropWhile" - {
        "drops elements while predicate is true" in {
            val arr    = Span(1, 2, 3, 4, 5)
            val result = arr.dropWhile(_ < 4)
            assert(result.size == 2)
            assert(result(0) == 4)
            assert(result(1) == 5)
        }

        "returns empty Span when predicate is always true" in {
            val arr    = Span(1, 2, 3)
            val result = arr.dropWhile(_ > 0)
            assert(result.isEmpty)
        }

        "returns all elements when first element fails predicate" in {
            val arr    = Span(1, 2, 3)
            val result = arr.dropWhile(_ > 5)
            assert(result.size == 3)
            assert(result.is(arr))
        }
    }

    "reverse" - {
        "reverses elements" in {
            val arr    = Span(1, 2, 3, 4, 5)
            val result = arr.reverse
            assert(result.size == 5)
            assert(result(0) == 5)
            assert(result(1) == 4)
            assert(result(2) == 3)
            assert(result(3) == 2)
            assert(result(4) == 1)
        }

        "returns empty Span for empty input" in {
            val arr    = Span.empty[Int]
            val result = arr.reverse
            assert(result.isEmpty)
        }

        "returns same element for single-element Span" in {
            val arr    = Span(42)
            val result = arr.reverse
            assert(result.size == 1)
            assert(result(0) == 42)
        }
    }

    "update" - {
        "updates element at specified index" in {
            val arr    = Span(1, 2, 3)
            val result = arr.update(1, 99)
            assert(result.size == 3)
            assert(result(0) == 1)
            assert(result(1) == 99)
            assert(result(2) == 3)
            assert(arr(1) == 2)
        }
    }

    "splitAt" - {
        "splits at correct position" in {
            val arr           = Span(1, 2, 3, 4, 5)
            val (left, right) = arr.splitAt(2)
            assert(left.size == 2)
            assert(left(0) == 1)
            assert(left(1) == 2)
            assert(right.size == 3)
            assert(right(0) == 3)
            assert(right(1) == 4)
            assert(right(2) == 5)
        }

        "handles split at beginning" in {
            val arr           = Span(1, 2, 3)
            val (left, right) = arr.splitAt(0)
            assert(left.isEmpty)
            assert(right.size == 3)
            assert(right.is(arr))
        }

        "handles split at end" in {
            val arr           = Span(1, 2, 3)
            val (left, right) = arr.splitAt(3)
            assert(left.size == 3)
            assert(left.is(arr))
            assert(right.isEmpty)
        }
    }

    "span" - {
        "splits according to predicate" in {
            val arr              = Span(1, 2, 3, 4, 5)
            val (prefix, suffix) = arr.span(_ < 4)
            assert(prefix.size == 3)
            assert(prefix(0) == 1)
            assert(prefix(1) == 2)
            assert(prefix(2) == 3)
            assert(suffix.size == 2)
            assert(suffix(0) == 4)
            assert(suffix(1) == 5)
        }

        "returns empty prefix when first element fails predicate" in {
            val arr              = Span(1, 2, 3)
            val (prefix, suffix) = arr.span(_ > 5)
            assert(prefix.isEmpty)
            assert(suffix.size == 3)
            assert(suffix.is(arr))
        }

        "returns all in prefix when predicate is always true" in {
            val arr              = Span(1, 2, 3)
            val (prefix, suffix) = arr.span(_ > 0)
            assert(prefix.size == 3)
            assert(prefix.is(arr))
            assert(suffix.isEmpty)
        }
    }

    "partition" - {
        "partitions elements according to predicate" in {
            val arr           = Span(1, 2, 3, 4, 5)
            val (evens, odds) = arr.partition(_ % 2 == 0)
            assert(evens.size == 2)
            assert(evens(0) == 2)
            assert(evens(1) == 4)
            assert(odds.size == 3)
            assert(odds(0) == 1)
            assert(odds(1) == 3)
            assert(odds(2) == 5)
        }

        "returns all in first partition when predicate is always true" in {
            val arr                             = Span(1, 2, 3)
            val (truePartition, falsePartition) = arr.partition(_ > 0)
            assert(truePartition.size == 3)
            assert(truePartition.is(arr))
            assert(falsePartition.isEmpty)
        }
    }

    "startsWith" - {
        "returns true when Span starts with given prefix" in {
            val arr    = Span(1, 2, 3, 4, 5)
            val prefix = Span(1, 2)
            assert(arr.startsWith(prefix))
        }

        "returns false when Span does not start with given prefix" in {
            val arr    = Span(1, 2, 3, 4, 5)
            val prefix = Span(2, 3)
            assert(!arr.startsWith(prefix))
        }

        "returns true with offset" in {
            val arr    = Span(1, 2, 3, 4, 5)
            val prefix = Span(2, 3)
            assert(arr.startsWith(prefix, 1))
        }

        "returns true for empty prefix" in {
            val arr    = Span(1, 2, 3)
            val prefix = Span.empty[Int]
            assert(arr.startsWith(prefix))
        }
    }

    "endsWith" - {
        "returns true when Span ends with given suffix" in {
            val arr    = Span(1, 2, 3, 4, 5)
            val suffix = Span(4, 5)
            assert(arr.endsWith(suffix))
        }

        "returns false when Span does not end with given suffix" in {
            val arr    = Span(1, 2, 3, 4, 5)
            val suffix = Span(3, 4)
            assert(!arr.endsWith(suffix))
        }

        "returns true for empty suffix" in {
            val arr    = Span(1, 2, 3)
            val suffix = Span.empty[Int]
            assert(arr.endsWith(suffix))
        }
    }

    "fold" - {
        "folds elements using operator" in {
            val arr = Span(1, 2, 3, 4, 5)
            val sum = arr.fold(0)(_ + _)
            assert(sum == 15)
        }

        "returns initial value for empty Span" in {
            val arr = Span.empty[Int]
            val sum = arr.fold(10)(_ + _)
            assert(sum == 10)
        }
    }

    "foldLeft" - {
        "folds elements from left to right" in {
            val arr    = Span("a", "b", "c")
            val result = arr.foldLeft("")(_ + _)
            assert(result == "abc")
        }

        "returns initial value for empty Span" in {
            val arr    = Span.empty[String]
            val result = arr.foldLeft("init")(_ + _)
            assert(result == "init")
        }
    }

    "foldRight" - {
        "folds elements from right to left" in {
            val arr    = Span("a", "b", "c")
            val result = arr.foldRight("")(_ + _)
            assert(result == "abc")
        }

        "returns initial value for empty Span" in {
            val arr    = Span.empty[String]
            val result = arr.foldRight("init")(_ + _)
            assert(result == "init")
        }
    }

    "copyToArray" - {
        "copies elements to array" in {
            val arr    = Span(1, 2, 3, 4, 5)
            val target = new Array[Int](10)
            val copied = arr.copyToArray(target, 2, 3)
            assert(copied == 3)
            assert(target(2) == 1)
            assert(target(3) == 2)
            assert(target(4) == 3)
        }

        "handles length constraint" in {
            val arr    = Span(1, 2, 3, 4, 5)
            val target = new Array[Int](3)
            val copied = arr.copyToArray(target, 0, 10)
            assert(copied == 3)
            assert(target(0) == 1)
            assert(target(1) == 2)
            assert(target(2) == 3)
        }

        "returns 0 for empty Span" in {
            val arr    = Span.empty[Int]
            val target = new Array[Int](5)
            val copied = arr.copyToArray(target)
            assert(copied == 0)
        }
    }

    "concat and ++" - {
        "concatenates two Spans" in {
            val arr1   = Span(1, 2, 3)
            val arr2   = Span(4, 5, 6)
            val result = arr1.concat(arr2)
            assert(result.size == 6)
            assert(result(0) == 1)
            assert(result(3) == 4)
            assert(result(5) == 6)

            val result2 = arr1 ++ arr2
            assert(result.is(result2))
        }

        "handles empty Spans" in {
            val arr   = Span(1, 2, 3)
            val empty = Span.empty[Int]
            assert((arr ++ empty).is(arr))
            assert((empty ++ arr).is(arr))
        }
    }

    "append and :+" - {
        "appends element to Span" in {
            val arr    = Span(1, 2, 3)
            val result = arr.append(4)
            assert(result.size == 4)
            assert(result(0) == 1)
            assert(result(3) == 4)

            val result2 = arr :+ 4
            assert(result.is(result2))
        }

        "appends to empty Span" in {
            val arr    = Span.empty[Int]
            val result = arr :+ 42
            assert(result.size == 1)
            assert(result(0) == 42)
        }
    }

    "prepend and +:" - {
        "prepends element to Span" in {
            val arr    = Span(2, 3, 4)
            val result = arr.prepend(1)
            assert(result.size == 4)
            assert(result(0) == 1)
            assert(result(1) == 2)

            val result2 = 1 +: arr
            assert(result.is(result2))
        }

        "prepends to empty Span" in {
            val arr    = Span.empty[Int]
            val result = 42 +: arr
            assert(result.size == 1)
            assert(result(0) == 42)
        }
    }

    "collect" - {
        "collects elements using partial function" in {
            val arr = Span(1, 2, 3, 4, 5)
            val result = arr.collect {
                case x if x % 2 == 0 => x * 2
            }
            assert(result.size == 2)
            assert(result(0) == 4)
            assert(result(1) == 8)
        }

        "returns empty Span when no elements match" in {
            val arr = Span(1, 3, 5)
            val result = arr.collect {
                case x if x % 2 == 0 => x
            }
            assert(result.isEmpty)
        }
    }

    "collectFirst" - {
        "returns first matching element transformed by partial function" in {
            val arr = Span(1, 2, 3, 4, 5)
            val result = arr.collectFirst {
                case x if x % 2 == 0 => x * 2
            }
            assert(result == Present(4))
        }

        "returns Absent when no elements match" in {
            val arr = Span(1, 3, 5)
            val result = arr.collectFirst {
                case x if x % 2 == 0 => x
            }
            assert(result == Absent)
        }
    }

    "distinct" - {
        "removes duplicate elements" in {
            val arr    = Span(1, 2, 2, 3, 1, 4)
            val result = arr.distinct
            assert(result.size == 4)
            assert(result(0) == 1)
            assert(result(1) == 2)
            assert(result(2) == 3)
            assert(result(3) == 4)
        }

        "returns same Span when no duplicates" in {
            val arr    = Span(1, 2, 3, 4)
            val result = arr.distinct
            assert(result.size == 4)
            assert(result.is(arr))
        }

        "returns empty Span for empty input" in {
            val arr    = Span.empty[Int]
            val result = arr.distinct
            assert(result.isEmpty)
        }
    }

    "distinctBy" - {
        "removes duplicates based on transformation" in {
            val arr    = Span("aa", "bb", "a", "b", "cc")
            val result = arr.distinctBy(_.length)
            assert(result.size == 2)
            assert(result(0) == "aa")
            assert(result(1) == "a")
        }

        "returns same Span when transformation produces no duplicates" in {
            val arr    = Span(1, 2, 3, 4)
            val result = arr.distinctBy(identity)
            assert(result.size == 4)
            assert(result.is(arr))
        }
    }

    "sliding" - {
        "creates sliding windows" in {
            val arr     = Span(1, 2, 3, 4, 5)
            val windows = arr.sliding(3).toArray
            assert(windows.length == 5)
            assert(windows(0).is(Span(1, 2, 3)))
            assert(windows(1).is(Span(2, 3, 4)))
            assert(windows(2).is(Span(3, 4, 5)))
            assert(windows(3).is(Span(4, 5)))
            assert(windows(4).is(Span(5)))
        }

        "creates sliding windows with step" in {
            val arr     = Span(1, 2, 3, 4, 5)
            val windows = arr.sliding(2, 2).toArray
            assert(windows.length == 3)
            assert(windows(0).is(Span(1, 2)))
            assert(windows(1).is(Span(3, 4)))
            assert(windows(2).is(Span(5)))
        }

        "throws for invalid size" in {
            val arr = Span(1, 2, 3)
            assertThrows[IllegalArgumentException] {
                arr.sliding(0)
            }
        }

        "throws for invalid step" in {
            val arr = Span(1, 2, 3)
            assertThrows[IllegalArgumentException] {
                arr.sliding(2, 0)
            }
        }

        "handles window size larger than span" in {
            val arr     = Span(1, 2, 3)
            val windows = arr.sliding(5).toArray
            assert(windows.length == 3)
            assert(windows(0).is(Span(1, 2, 3)))
            assert(windows(1).is(Span(2, 3)))
            assert(windows(2).is(Span(3)))
        }

        "handles window size equal to span size" in {
            val arr     = Span(1, 2, 3)
            val windows = arr.sliding(3).toArray
            assert(windows.length == 3)
            assert(windows(0).is(Span(1, 2, 3)))
            assert(windows(1).is(Span(2, 3)))
            assert(windows(2).is(Span(3)))
        }

        "handles step size larger than window size" in {
            val arr     = Span(1, 2, 3, 4, 5, 6)
            val windows = arr.sliding(2, 3).toArray
            assert(windows.length == 2)
            assert(windows(0).is(Span(1, 2)))
            assert(windows(1).is(Span(4, 5)))
        }

        "handles step size equal to span size" in {
            val arr     = Span(1, 2, 3)
            val windows = arr.sliding(2, 3).toArray
            assert(windows.length == 1)
            assert(windows(0).is(Span(1, 2)))
        }

        "handles step size larger than span size" in {
            val arr     = Span(1, 2, 3)
            val windows = arr.sliding(2, 5).toArray
            assert(windows.length == 1)
            assert(windows(0).is(Span(1, 2)))
        }

        "handles empty span" in {
            val arr     = Span.empty[Int]
            val windows = arr.sliding(2).toArray
            assert(windows.length == 0)
        }

        "handles single element span" in {
            val arr     = Span(42)
            val windows = arr.sliding(3).toArray
            assert(windows.length == 1)
            assert(windows(0).is(Span(42)))
        }

        "iterator hasNext and next work correctly" in {
            val arr = Span(1, 2, 3, 4)
            val it  = arr.sliding(2, 2)
            assert(it.hasNext)
            assert(it.next().is(Span(1, 2)))
            assert(it.hasNext)
            assert(it.next().is(Span(3, 4)))
            assert(!it.hasNext)
        }

        "iterator throws when next called after exhaustion" in {
            val arr = Span(1, 2, 3)
            val it  = arr.sliding(3)
            assert(it.hasNext)
            assert(it.next().is(Span(1, 2, 3)))
            assert(it.hasNext)
            assert(it.next().is(Span(2, 3)))
            assert(it.hasNext)
            assert(it.next().is(Span(3)))
            assert(!it.hasNext)
            assertThrows[NoSuchElementException] {
                it.next()
            }
        }

        "handles large spans efficiently" in {
            val arr     = Span.range(1, 1001)
            val windows = arr.sliding(10, 100)
            assert(windows.hasNext)
            val firstWindow = windows.next()
            assert(firstWindow.size == 10)
            assert(firstWindow(0) == 1)
        }

        "handles overlapping windows correctly" in {
            val arr     = Span(1, 2, 3, 4)
            val windows = arr.sliding(3, 1).toArray
            assert(windows.length == 4)
            assert(windows(0).is(Span(1, 2, 3)))
            assert(windows(1).is(Span(2, 3, 4)))
            assert(windows(2).is(Span(3, 4)))
            assert(windows(3).is(Span(4)))
        }
    }

    "scan, scanLeft, scanRight" - {
        "scan produces prefix scan" in {
            val arr    = Span(1, 2, 3, 4)
            val result = arr.scan(0)(_ + _)
            assert(result.size == 5)
            assert(result(0) == 0)
            assert(result(1) == 1)
            assert(result(2) == 3)
            assert(result(3) == 6)
            assert(result(4) == 10)
        }

        "scanLeft produces left scan" in {
            val arr    = Span(1, 2, 3)
            val result = arr.scanLeft("")((acc, x) => acc + x.toString)
            assert(result.size == 4)
            assert(result(0) == "")
            assert(result(1) == "1")
            assert(result(2) == "12")
            assert(result(3) == "123")
        }

        "scanRight produces right scan" in {
            val arr    = Span(1, 2, 3)
            val result = arr.scanRight("")((x, acc) => x.toString + acc)
            assert(result.size == 4)
            assert(result(0) == "123")
            assert(result(1) == "23")
            assert(result(2) == "3")
            assert(result(3) == "")
        }
    }

    "flatten" - {
        "flattens nested structures" in {
            val arr    = Span(Span(1, 2), Span(3, 4), Span(5))
            val result = arr.flatten
            assert(result.size == 5)
            assert(result(0) == 1)
            assert(result(1) == 2)
            assert(result(2) == 3)
            assert(result(3) == 4)
            assert(result(4) == 5)
        }

        "returns empty Span for empty input" in {
            val arr    = Span.empty[Span[Int]]
            val result = arr.flatten
            assert(result.isEmpty)
        }
    }

    "padTo" - {
        "pads Span to specified length" in {
            val arr    = Span(1, 2, 3)
            val result = arr.padTo(5, 0)
            assert(result.size == 5)
            assert(result(0) == 1)
            assert(result(1) == 2)
            assert(result(2) == 3)
            assert(result(3) == 0)
            assert(result(4) == 0)
        }

        "returns same Span when already long enough" in {
            val arr    = Span(1, 2, 3, 4, 5)
            val result = arr.padTo(3, 0)
            assert(result.size == 5)
            assert(result.is(arr))
        }
    }

    "mkString" - {
        "joins elements with the specified separator" in {
            val arr = Span(1, 2, 3, 4, 5)
            assert(arr.mkString(", ") == "1, 2, 3, 4, 5")
        }

        "returns an empty string for an empty Span" in {
            val arr = Span.empty[Int]
            assert(arr.mkString(", ") == "")
        }

        "works with a single element" in {
            val arr = Span("single")
            assert(arr.mkString(", ") == "single")
        }

        "uses toString on complex objects" in {
            case class Person(name: String)
            val arr = Span(Person("Alice"), Person("Bob"))
            assert(arr.mkString("; ") == "Person(Alice); Person(Bob)")
        }
    }

    "Factory methods" - {
        "fill" - {
            "creates Span with repeated value" in {
                val arr = Span.fill(5)(42)
                assert(arr.size == 5)
                assert(arr.forall(_ == 42))
            }

            "returns empty Span for n <= 0" in {
                val arr = Span.fill(0)(42)
                assert(arr.isEmpty)
            }
        }

        "tabulate" - {
            "creates Span using function" in {
                val arr = Span.tabulate(5)(_ * 2)
                assert(arr.size == 5)
                assert(arr(0) == 0)
                assert(arr(1) == 2)
                assert(arr(2) == 4)
                assert(arr(3) == 6)
                assert(arr(4) == 8)
            }

            "returns empty Span for n <= 0" in {
                val arr = Span.tabulate(0)(_ * 2)
                assert(arr.isEmpty)
            }
        }

        "range" - {
            "creates range with step 1" in {
                val arr = Span.range(1, 5)
                assert(arr.size == 4)
                assert(arr(0) == 1)
                assert(arr(1) == 2)
                assert(arr(2) == 3)
                assert(arr(3) == 4)
            }

            "creates range with custom step" in {
                val arr = Span.range(0, 10, 2)
                assert(arr.size == 5)
                assert(arr(0) == 0)
                assert(arr(1) == 2)
                assert(arr(2) == 4)
                assert(arr(3) == 6)
                assert(arr(4) == 8)
            }

            "creates descending range" in {
                val arr = Span.range(5, 1, -1)
                assert(arr.size == 4)
                assert(arr(0) == 5)
                assert(arr(1) == 4)
                assert(arr(2) == 3)
                assert(arr(3) == 2)
            }

            "returns empty for invalid ranges" in {
                assert(Span.range(5, 1).isEmpty)
                assert(Span.range(1, 5, -1).isEmpty)
            }

            "throws for step = 0" in {
                assertThrows[IllegalArgumentException] {
                    Span.range(1, 5, 0)
                }
            }
        }

        "iterate" - {
            "creates Span using iteration" in {
                val arr = Span.iterate(1, 5)(_ * 2)
                assert(arr.size == 5)
                assert(arr(0) == 1)
                assert(arr(1) == 2)
                assert(arr(2) == 4)
                assert(arr(3) == 8)
                assert(arr(4) == 16)
            }

            "returns empty for len <= 0" in {
                val arr = Span.iterate(1, 0)(_ * 2)
                assert(arr.isEmpty)
            }
        }

        "concat" - {
            "concatenates multiple Spans" in {
                val arr1   = Span(1, 2)
                val arr2   = Span(3, 4)
                val arr3   = Span(5, 6)
                val result = Span.concat(arr1, arr2, arr3)
                assert(result.size == 6)
                assert(result(0) == 1)
                assert(result(2) == 3)
                assert(result(4) == 5)
            }

            "returns empty for no arguments" in {
                val result = Span.concat[Int]()
                assert(result.isEmpty)
            }
        }
    }

    "Span.exists (binary)" - {
        "returns true when at least one pair satisfies the predicate" in {
            val chain1 = Span(1, 4, 3)
            val chain2 = Span(3, 4, 5)
            assert(Span.existsZip(chain1, chain2)(_ == _))
        }

        "returns false when no pair satisfies the predicate" in {
            val chain1 = Span(1, 2, 3)
            val chain2 = Span(4, 5, 6)
            assert(!Span.existsZip(chain1, chain2)(_ == _))
        }

        "throws an exception for Chains of different sizes" in {
            val chain1 = Span(1, 2, 3)
            val chain2 = Span(4, 5)
            assertThrows[IllegalArgumentException] {
                Span.existsZip(chain1, chain2)(_ == _)
            }
        }
    }

    "Span.exists (ternary)" - {
        "returns true when at least one triplet satisfies the predicate" in {
            val chain1 = Span(1, 4, 3)
            val chain2 = Span(1, 2, 3)
            val chain3 = Span(1, 4, 5)
            assert(Span.existsZip(chain1, chain2, chain3)((a, b, c) => a == b || a == c))
        }

        "returns false when no triplet satisfies the predicate" in {
            val chain1 = Span(1, 2, 3)
            val chain2 = Span(4, 5, 6)
            val chain3 = Span(7, 8, 9)
            assert(!Span.existsZip(chain1, chain2, chain3)((a, b, c) => a == b && b == c))
        }

        "throws an exception for Chains of different sizes" in {
            val chain1 = Span(1, 2, 3)
            val chain2 = Span(4, 5)
            val chain3 = Span(7, 8, 9)
            assertThrows[IllegalArgumentException] {
                Span.existsZip(chain1, chain2, chain3)((_, _, _) => true)
            }
        }
    }

    "Span.forall (binary)" - {
        "returns true when all pairs satisfy the predicate" in {
            val chain1 = Span(1, 2, 3)
            val chain2 = Span(1, 2, 3)
            assert(Span.forallZip(chain1, chain2)(_ == _))
        }

        "returns false when at least one pair does not satisfy the predicate" in {
            val chain1 = Span(1, 2, 3)
            val chain2 = Span(1, 4, 3)
            assert(!Span.forallZip(chain1, chain2)(_ == _))
        }

        "throws an exception for Chains of different sizes" in {
            val chain1 = Span(1, 2, 3)
            val chain2 = Span(1, 2)
            assertThrows[IllegalArgumentException] {
                Span.forallZip(chain1, chain2)(_ == _)
            }
        }
    }

    "Span.forall (ternary)" - {
        "returns true when all triplets satisfy the predicate" in {
            val chain1 = Span(1, 2, 3)
            val chain2 = Span(1, 2, 3)
            val chain3 = Span(1, 2, 3)
            assert(Span.forallZip(chain1, chain2, chain3)((a, b, c) => a == b && b == c))
        }

        "returns false when at least one triplet does not satisfy the predicate" in {
            val chain1 = Span(1, 2, 3)
            val chain2 = Span(1, 2, 3)
            val chain3 = Span(1, 4, 3)
            assert(!Span.forallZip(chain1, chain2, chain3)((a, b, c) => a == b && b == c))
        }

        "throws an exception for Chains of different sizes" in {
            val chain1 = Span(1, 2, 3)
            val chain2 = Span(1, 2)
            val chain3 = Span(1, 2, 3)
            assertThrows[IllegalArgumentException] {
                Span.forallZip(chain1, chain2, chain3)((_, _, _) => true)
            }
        }
    }

    "toArray and toArrayUnsafe" - {
        "toArray" - {
            "returns a copy of the underlying array" in {
                val arr   = Span(1, 2, 3)
                val array = arr.toArray
                array(0) = 99
                assert(arr(0) == 1)
            }

            "returns an empty array for an empty Span" in {
                val arr   = Span.empty[Int]
                val array = arr.toArray
                assert(array.isEmpty)
            }
        }

        "toArrayUnsafe" - {
            "returns the underlying array without copying" in {
                val arr   = Span(1, 2, 3)
                val array = arr.toArrayUnsafe
                array(0) = 99
                assert(arr(0) == 99)
            }

            "returns an empty array for an empty Span" in {
                val arr   = Span.empty[Int]
                val array = arr.toArrayUnsafe
                assert(array.isEmpty)
            }
        }
    }

    "mixed" - {
        "chained operations" in {
            val arr    = Span(1, 2, 3, 4, 5)
            val result = arr.map(_ * 2)
            assert(result.forall(_ % 2 == 0))
            assert(result.exists(_ > 8))
            assert(result.mkString("-") == "2-4-6-8-10")
        }

        "empty Span operations" in {
            val arr = Span.empty[Int]
            assert(arr.map(_ * 2).isEmpty)
            assert(arr.forall(_ => false))
            assert(!arr.exists(_ => true))
            assert(arr.mkString(",") == "")
        }

        "single element Span operations" in {
            val arr = Span(42)
            assert(arr.size == 1)
            assert(!arr.isEmpty)
            assert(arr(0) == 42)
            assert(arr.map(_ * 2)(0) == 84)
            assert(arr.forall(_ > 0))
            assert(arr.exists(_ == 42))
            assert(arr.mkString(",") == "42")
        }
    }

    "variance" - {

        given [A, B]: CanEqual[A, B] = CanEqual.derived

        sealed trait Animal
        case class Dog(name: String)  extends Animal
        case class Cat(name: String)  extends Animal
        case class Bird(name: String) extends Animal

        "basic subtyping" - {

            "allows Span[Dog] to be used as Span[Animal]" in {
                val dogs: Span[Dog]       = Span(Dog("Rex"), Dog("Max"))
                val animals: Span[Animal] = dogs
                assert(animals.size == 2)
                assert(animals(0) == Dog("Rex"))
                assert(animals(1) == Dog("Max"))
            }

            "allows Span[Cat] to be used as Span[Animal]" in {
                val cats: Span[Cat]       = Span(Cat("Whiskers"), Cat("Fluffy"))
                val animals: Span[Animal] = cats
                assert(animals.size == 2)
                assert(animals.contains(Cat("Whiskers")))
                assert(animals.contains(Cat("Fluffy")))
            }

            "preserves element access through supertype reference" in {
                val birds: Span[Bird]     = Span(Bird("Tweety"), Bird("Polly"))
                val animals: Span[Animal] = birds
                animals.foreach { animal =>
                    assert(animal.isInstanceOf[Bird])
                }
                succeed
            }

            "works with empty spans" in {
                val emptyDogs: Span[Dog]       = Span.empty[Dog]
                val emptyAnimals: Span[Animal] = emptyDogs
                assert(emptyAnimals.isEmpty)
                assert(emptyAnimals.size == 0)
            }
        }

        "method compatibility" - {
            sealed trait Animal
            case class Dog(name: String) extends Animal
            case class Cat(name: String) extends Animal

            "startsWith accepts supertype spans" in {
                val dogs                  = Span(Dog("Rex"), Dog("Max"), Dog("Buddy"))
                val animals: Span[Animal] = dogs
                val prefix                = Span(Dog("Rex"), Dog("Max"))
                assert(animals.startsWith(prefix))
            }

            "endsWith works with mixed hierarchies" in {
                val mixed  = Span[Animal](Dog("Rex"), Cat("Whiskers"), Dog("Max"))
                val suffix = Span[Animal](Cat("Whiskers"), Dog("Max"))
                assert(mixed.endsWith(suffix))
            }

            "copyToArray accepts supertype arrays" in {
                val dogs                  = Span(Dog("Rex"), Dog("Max"))
                val animals: Span[Animal] = dogs
                val animalArray           = new Array[Animal](5)
                val copied                = animals.copyToArray(animalArray, 1, 2)
                assert(copied == 2)
                assert(animalArray(1) == Dog("Rex"))
                assert(animalArray(2) == Dog("Max"))
            }
        }

        "composition operations" - {
            sealed trait Animal
            case class Dog(name: String) extends Animal
            case class Cat(name: String) extends Animal

            "concatenation infers common supertype" in {
                val dogs: Span[Dog]        = Span(Dog("Rex"))
                val cats: Span[Cat]        = Span(Cat("Whiskers"))
                val combined: Span[Animal] = dogs ++ cats
                assert(combined.size == 2)
                assert(combined(0) == Dog("Rex"))
                assert(combined(1) == Cat("Whiskers"))
            }

            "append with supertype elements" in {
                val dogs: Span[Dog]       = Span(Dog("Rex"))
                val withCat: Span[Animal] = dogs :+ Cat("Whiskers")
                assert(withCat.size == 2)
                assert(withCat(0) == Dog("Rex"))
                assert(withCat(1) == Cat("Whiskers"))
            }

            "prepend with supertype elements" in {
                val cats: Span[Cat]       = Span(Cat("Fluffy"))
                val withDog: Span[Animal] = Dog("Rex") +: cats
                assert(withDog.size == 2)
                assert(withDog(0) == Dog("Rex"))
                assert(withDog(1) == Cat("Fluffy"))
            }

            "concat multiple spans with mixed types" in {
                val dogs     = Span(Dog("Rex"))
                val cats     = Span(Cat("Whiskers"))
                val moreDogs = Span(Dog("Max"))
                val combined = Span.concat[Animal](dogs, cats, moreDogs)
                assert(combined.size == 3)
                assert(combined(0) == Dog("Rex"))
                assert(combined(1) == Cat("Whiskers"))
                assert(combined(2) == Dog("Max"))
            }
        }

        "type inference" - {

            sealed trait Shape
            case class Circle(radius: Double) extends Shape
            case class Square(side: Double)   extends Shape

            "infers most specific common supertype" in {
                val dogs = Span(Dog("Rex"), Dog("Max"))
                val cats = Span(Cat("Whiskers"))

                def processAnimals(animals: Span[Animal]): Int = animals.size

                assert(processAnimals(dogs) == 2)
                assert(processAnimals(cats) == 1)
            }

            "handles complex type hierarchies correctly" in {
                val shapes: Span[Shape] = Span(Circle(5.0), Square(3.0))
                val anys: Span[Any]     = shapes
                assert(anys.size == 2)
                assert(anys.forall(_.isInstanceOf[Shape]))
            }

            "works with explicit type annotations" in {
                val mixed: Span[Animal] = Span[Animal](Dog("Rex"), Cat("Whiskers"))
                assert(mixed.size == 2)
                assert(mixed.exists(_.isInstanceOf[Dog]))
                assert(mixed.exists(_.isInstanceOf[Cat]))
            }

            "preserves type information in transformations" in {
                val dogs: Span[Dog]       = Span(Dog("Rex"), Dog("Max"))
                val animals: Span[Animal] = dogs
                val names                 = animals.map(_.toString)
                assert(names.size == 2)
                assert(names.forall(_.contains("Dog")))
            }
        }

        "fromUnsafe safety" - {

            "fromUnsafe with covariant usage patterns" in {
                val dogArray                 = Array(Dog("Rex"), Dog("Max"))
                val dogSpan                  = Span.fromUnsafe(dogArray)
                val animalSpan: Span[Animal] = dogSpan
                assert(animalSpan.size == 2)
                assert(animalSpan(0) == Dog("Rex"))
            }

            "maintains type safety in typical scenarios" in {
                val catArray                 = Array(Cat("Whiskers"), Cat("Fluffy"))
                val catSpan                  = Span.fromUnsafe(catArray)
                val animalSpan: Span[Animal] = catSpan

                assert(animalSpan.exists(_.toString.contains("Cat")))
                assert(animalSpan.forall(_.isInstanceOf[Animal]))
            }

            "works correctly with subtype arrays" in {
                val animals: Array[Animal] = Array(Dog("Rex"), Cat("Whiskers"))
                val animalSpan             = Span.fromUnsafe(animals)
                assert(animalSpan.size == 2)
                assert(animalSpan(0).isInstanceOf[Dog])
                assert(animalSpan(1).isInstanceOf[Cat])
            }
        }
    }

end SpanTest
