package kyo

import scala.util.Try

class KArrayTest extends Test:

    "KArray.apply" - {
        "creates a KArray from varargs" in {
            val arr = KArray(1, 2, 3)
            assert(arr.size == 3)
            assert(arr(0) == 1)
            assert(arr(1) == 2)
            assert(arr(2) == 3)
        }

        "creates an empty KArray when no arguments are provided" in {
            val arr = KArray[Int]()
            assert(arr.isEmpty)
        }
    }

    "KArray.empty" - {
        "creates an empty KArray" in {
            val arr = KArray.empty[Int]
            assert(arr.isEmpty)
        }

        "returns cached empty arrays for primitive types" in {
            val booleanChain = KArray.empty[Boolean]
            val byteChain    = KArray.empty[Byte]
            val charChain    = KArray.empty[Char]
            val doubleChain  = KArray.empty[Double]
            val floatChain   = KArray.empty[Float]
            val intChain     = KArray.empty[Int]
            val longChain    = KArray.empty[Long]
            val shortChain   = KArray.empty[Short]

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

    "KArray.from" - {
        "Array" - {
            "creates a KArray from a non-empty Array" in {
                val array = Array("a", "b", "c")
                val arr   = KArray.from(array)
                assert(arr.size == 3)
                assert(arr(0) == "a")
                assert(arr(1) == "b")
                assert(arr(2) == "c")
            }

            "creates an empty KArray from an empty Array" in {
                val array = Array.empty[String]
                val arr   = KArray.from(array)
                assert(arr.isEmpty)
            }

            "creates a copy of the underlying array" in {
                val array = Array("a", "b", "c")
                val arr   = KArray.from(array)
                array(0) = "x"
                assert(arr(0) == "a")
            }
        }

        "Seq" - {
            "creates a KArray from a non-empty Seq" in {
                val seq = Seq("a", "b", "c")
                val arr = KArray.from(seq)
                assert(arr.size == 3)
                assert(arr(0) == "a")
                assert(arr(1) == "b")
                assert(arr(2) == "c")
            }

            "creates an empty KArray from an empty Seq" in {
                val seq = Seq.empty[String]
                val arr = KArray.from(seq)
                assert(arr.isEmpty)
            }

            "handles different Seq types" - {
                "List" in {
                    val list = List(1, 2, 3)
                    val arr  = KArray.from(list)
                    assert(arr.size == 3)
                    assert(arr(0) == 1)
                    assert(arr(1) == 2)
                    assert(arr(2) == 3)
                }

                "Vector" in {
                    val vector = Vector(1, 2, 3)
                    val arr    = KArray.from(vector)
                    assert(arr.size == 3)
                    assert(arr(0) == 1)
                    assert(arr(1) == 2)
                    assert(arr(2) == 3)
                }
            }
        }
    }

    "KArray.fromUnsafe" - {
        "Array" - {
            "creates a KArray from a non-empty Array" in {
                val array = Array("a", "b", "c")
                val arr   = KArray.fromUnsafe(array)
                assert(arr.size == 3)
                assert(arr(0) == "a")
                assert(arr(1) == "b")
                assert(arr(2) == "c")
            }

            "creates an empty KArray from an empty Array" in {
                val array = Array.empty[String]
                val arr   = KArray.fromUnsafe(array)
                assert(arr.isEmpty)
            }

            "shares the same underlying array" in {
                val array = Array("a", "b", "c")
                val arr   = KArray.fromUnsafe(array)
                array(0) = "x"
                assert(arr(0) == "x")
            }
        }

        "Seq" - {
            "creates a KArray from a non-empty Seq" in {
                val seq = Seq("a", "b", "c")
                val arr = KArray.fromUnsafe(seq)
                assert(arr.size == 3)
                assert(arr(0) == "a")
                assert(arr(1) == "b")
                assert(arr(2) == "c")
            }

            "creates an empty KArray from an empty Seq" in {
                val seq = Seq.empty[String]
                val arr = KArray.fromUnsafe(seq)
                assert(arr.isEmpty)
            }

            "handles different Seq types" - {
                "List" in {
                    val list = List(1, 2, 3)
                    val arr  = KArray.fromUnsafe(list)
                    assert(arr.size == 3)
                    assert(arr(0) == 1)
                    assert(arr(1) == 2)
                    assert(arr(2) == 3)
                }

                "Vector" in {
                    val vector = Vector(1, 2, 3)
                    val arr    = KArray.fromUnsafe(vector)
                    assert(arr.size == 3)
                    assert(arr(0) == 1)
                    assert(arr(1) == 2)
                    assert(arr(2) == 3)
                }
            }
        }
    }

    "size" - {
        "returns the number of elements in a non-empty KArray" in {
            val arr = KArray(1, 2, 3, 4, 5)
            assert(arr.size == 5)
        }

        "returns 0 for an empty KArray" in {
            val arr = KArray.empty[Int]
            assert(arr.size == 0)
        }
    }

    "isEmpty" - {
        "returns false for a non-empty KArray" in {
            val arr = KArray(1, 2, 3)
            assert(!arr.isEmpty)
        }

        "returns true for an empty KArray" in {
            val arr = KArray.empty[Int]
            assert(arr.isEmpty)
        }
    }

    "apply" - {
        "returns correct elements from a KArray" in {
            val arr = KArray(1, 2, 3, 4, 5)
            assert(arr(0) == 1)
            assert(arr(2) == 3)
            assert(arr(4) == 5)
        }

        "throws for negative index" in {
            val arr = KArray(1, 2, 3)
            assertThrows[Throwable] {
                arr(-1)
            }
        }

        "throws for index >= size" in {
            val arr = KArray(1, 2, 3)
            assertThrows[Throwable] {
                arr(3)
            }
        }
    }

    "is" - {
        "returns true for equal Chains" in {
            val chain1 = KArray(1, 2, 3)
            val chain2 = KArray(1, 2, 3)
            assert(chain1.is(chain2))
        }

        "returns false for different Chains" in {
            val chain1 = KArray(1, 2, 3)
            val chain2 = KArray(3, 2, 1)
            assert(!chain1.is(chain2))
        }

        "returns true for empty Chains" in {
            val chain1 = KArray.empty[Int]
            val chain2 = KArray.empty[Int]
            assert(chain1.is(chain2))
        }

        "returns false for Chains of different sizes" in {
            val chain1 = KArray(1, 2, 3)
            val chain2 = KArray(1, 2)
            assertThrows[Throwable] {
                chain1.is(chain2)
            }
        }
    }

    "forall" - {
        "returns true when all elements satisfy the predicate" in {
            val arr = KArray(2, 4, 6, 8, 10)
            assert(arr.forall(_ % 2 == 0))
        }

        "returns false when at least one element does not satisfy the predicate" in {
            val arr = KArray(2, 4, 5, 8, 10)
            assert(!arr.forall(_ % 2 == 0))
        }

        "returns true for an empty KArray" in {
            val arr = KArray.empty[Int]
            assert(arr.forall(_ => false))
        }
    }

    "exists" - {
        "returns true when at least one element satisfies the predicate" in {
            val arr = KArray(1, 3, 5, 6, 9)
            assert(arr.exists(_ % 2 == 0))
        }

        "returns false when no element satisfies the predicate" in {
            val arr = KArray(1, 3, 5, 7, 9)
            assert(!arr.exists(_ % 2 == 0))
        }

        "returns false for an empty KArray" in {
            val arr = KArray.empty[Int]
            assert(!arr.exists(_ => true))
        }
    }

    "map" - {
        "transforms all elements using the provided function" in {
            val arr    = KArray(1, 2, 3, 4, 5)
            val result = arr.map(_ * 2)
            assert(result.size == 5)
            assert(result(0) == 2)
            assert(result(1) == 4)
            assert(result(2) == 6)
            assert(result(3) == 8)
            assert(result(4) == 10)
        }

        "returns an empty KArray when mapping an empty KArray" in {
            val arr    = KArray.empty[Int]
            val result = arr.map(_ * 2)
            assert(result.isEmpty)
        }

        "handles type transformations" in {
            val arr    = KArray(1, 2, 3)
            val result = arr.map(_.toString)
            assert(result.size == 3)
            assert(result(0) == "1")
            assert(result(1) == "2")
            assert(result(2) == "3")
        }
    }

    "mkString" - {
        "joins elements with the specified separator" in {
            val arr = KArray(1, 2, 3, 4, 5)
            assert(arr.mkString(", ") == "1, 2, 3, 4, 5")
        }

        "returns an empty string for an empty KArray" in {
            val arr = KArray.empty[Int]
            assert(arr.mkString(", ") == "")
        }

        "works with a single element" in {
            val arr = KArray("single")
            assert(arr.mkString(", ") == "single")
        }

        "uses toString on complex objects" in {
            case class Person(name: String)
            val arr = KArray(Person("Alice"), Person("Bob"))
            assert(arr.mkString("; ") == "Person(Alice); Person(Bob)")
        }
    }

    "KArray.exists (binary)" - {
        "returns true when at least one pair satisfies the predicate" in {
            val chain1 = KArray(1, 4, 3)
            val chain2 = KArray(3, 4, 5)
            assert(KArray.existsZip(chain1, chain2)(_ == _))
        }

        "returns false when no pair satisfies the predicate" in {
            val chain1 = KArray(1, 2, 3)
            val chain2 = KArray(4, 5, 6)
            assert(!KArray.existsZip(chain1, chain2)(_ == _))
        }

        "throws an exception for Chains of different sizes" in {
            val chain1 = KArray(1, 2, 3)
            val chain2 = KArray(4, 5)
            assertThrows[IllegalArgumentException] {
                KArray.existsZip(chain1, chain2)(_ == _)
            }
        }
    }

    "KArray.forall (binary)" - {
        "returns true when all pairs satisfy the predicate" in {
            val chain1 = KArray(1, 2, 3)
            val chain2 = KArray(1, 2, 3)
            assert(KArray.forallZip(chain1, chain2)(_ == _))
        }

        "returns false when at least one pair does not satisfy the predicate" in {
            val chain1 = KArray(1, 2, 3)
            val chain2 = KArray(1, 4, 3)
            assert(!KArray.forallZip(chain1, chain2)(_ == _))
        }

        "throws an exception for Chains of different sizes" in {
            val chain1 = KArray(1, 2, 3)
            val chain2 = KArray(1, 2)
            assertThrows[IllegalArgumentException] {
                KArray.forallZip(chain1, chain2)(_ == _)
            }
        }
    }

    "mixed" - {
        "chained operations" in {
            val arr    = KArray(1, 2, 3, 4, 5)
            val result = arr.map(_ * 2)
            assert(result.forall(_ % 2 == 0))
            assert(result.exists(_ > 8))
            assert(result.mkString("-") == "2-4-6-8-10")
        }

        "empty KArray operations" in {
            val arr = KArray.empty[Int]
            assert(arr.map(_ * 2).isEmpty)
            assert(arr.forall(_ => false))
            assert(!arr.exists(_ => true))
            assert(arr.mkString(",") == "")
        }

        "single element KArray operations" in {
            val arr = KArray(42)
            assert(arr.size == 1)
            assert(!arr.isEmpty)
            assert(arr(0) == 42)
            assert(arr.map(_ * 2)(0) == 84)
            assert(arr.forall(_ > 0))
            assert(arr.exists(_ == 42))
            assert(arr.mkString(",") == "42")
        }
    }

    "toArray and toArrayUnsafe" - {
        "toArray" - {
            "returns a copy of the underlying array" in {
                val arr   = KArray(1, 2, 3)
                val array = arr.toArray
                array(0) = 99
                assert(arr(0) == 1)
            }

            "returns an empty array for an empty KArray" in {
                val arr   = KArray.empty[Int]
                val array = arr.toArray
                assert(array.isEmpty)
            }
        }

        "toArrayUnsafe" - {
            "returns the underlying array without copying" in {
                val arr   = KArray(1, 2, 3)
                val array = arr.toArrayUnsafe
                array(0) = 99
                assert(arr(0) == 99)
            }

            "returns an empty array for an empty KArray" in {
                val arr   = KArray.empty[Int]
                val array = arr.toArrayUnsafe
                assert(array.isEmpty)
            }
        }
    }
end KArrayTest
