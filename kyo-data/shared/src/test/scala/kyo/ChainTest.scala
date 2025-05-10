package kyo

import scala.util.Try

class ChainTest extends Test:

    "Chain.apply" - {
        "creates a Chain from varargs" in {
            val chain = Chain(1, 2, 3)
            assert(chain.size == 3)
            assert(chain(0) == 1)
            assert(chain(1) == 2)
            assert(chain(2) == 3)
        }

        "creates an empty Chain when no arguments are provided" in {
            val chain = Chain[Int]()
            assert(chain.isEmpty)
        }
    }

    "Chain.empty" - {
        "creates an empty Chain" in {
            val chain = Chain.empty[Int]
            assert(chain.isEmpty)
        }

        "returns cached empty arrays for primitive types" in {
            val booleanChain = Chain.empty[Boolean]
            val byteChain    = Chain.empty[Byte]
            val charChain    = Chain.empty[Char]
            val doubleChain  = Chain.empty[Double]
            val floatChain   = Chain.empty[Float]
            val intChain     = Chain.empty[Int]
            val longChain    = Chain.empty[Long]
            val shortChain   = Chain.empty[Short]

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

    "Chain.from" - {
        "Array" - {
            "creates a Chain from a non-empty Array" in {
                val array = Array("a", "b", "c")
                val chain = Chain.from(array)
                assert(chain.size == 3)
                assert(chain(0) == "a")
                assert(chain(1) == "b")
                assert(chain(2) == "c")
            }

            "creates an empty Chain from an empty Array" in {
                val array = Array.empty[String]
                val chain = Chain.from(array)
                assert(chain.isEmpty)
            }

            "creates a copy of the underlying array" in {
                val array = Array("a", "b", "c")
                val chain = Chain.from(array)
                array(0) = "x"
                assert(chain(0) == "a")
            }
        }

        "Seq" - {
            "creates a Chain from a non-empty Seq" in {
                val seq   = Seq("a", "b", "c")
                val chain = Chain.from(seq)
                assert(chain.size == 3)
                assert(chain(0) == "a")
                assert(chain(1) == "b")
                assert(chain(2) == "c")
            }

            "creates an empty Chain from an empty Seq" in {
                val seq   = Seq.empty[String]
                val chain = Chain.from(seq)
                assert(chain.isEmpty)
            }

            "handles different Seq types" - {
                "List" in {
                    val list  = List(1, 2, 3)
                    val chain = Chain.from(list)
                    assert(chain.size == 3)
                    assert(chain(0) == 1)
                    assert(chain(1) == 2)
                    assert(chain(2) == 3)
                }

                "Vector" in {
                    val vector = Vector(1, 2, 3)
                    val chain  = Chain.from(vector)
                    assert(chain.size == 3)
                    assert(chain(0) == 1)
                    assert(chain(1) == 2)
                    assert(chain(2) == 3)
                }
            }
        }
    }

    "Chain.fromUnsafe" - {
        "Array" - {
            "creates a Chain from a non-empty Array" in {
                val array = Array("a", "b", "c")
                val chain = Chain.fromUnsafe(array)
                assert(chain.size == 3)
                assert(chain(0) == "a")
                assert(chain(1) == "b")
                assert(chain(2) == "c")
            }

            "creates an empty Chain from an empty Array" in {
                val array = Array.empty[String]
                val chain = Chain.fromUnsafe(array)
                assert(chain.isEmpty)
            }

            "shares the same underlying array" in {
                val array = Array("a", "b", "c")
                val chain = Chain.fromUnsafe(array)
                array(0) = "x"
                assert(chain(0) == "x")
            }
        }

        "Seq" - {
            "creates a Chain from a non-empty Seq" in {
                val seq   = Seq("a", "b", "c")
                val chain = Chain.fromUnsafe(seq)
                assert(chain.size == 3)
                assert(chain(0) == "a")
                assert(chain(1) == "b")
                assert(chain(2) == "c")
            }

            "creates an empty Chain from an empty Seq" in {
                val seq   = Seq.empty[String]
                val chain = Chain.fromUnsafe(seq)
                assert(chain.isEmpty)
            }

            "handles different Seq types" - {
                "List" in {
                    val list  = List(1, 2, 3)
                    val chain = Chain.fromUnsafe(list)
                    assert(chain.size == 3)
                    assert(chain(0) == 1)
                    assert(chain(1) == 2)
                    assert(chain(2) == 3)
                }

                "Vector" in {
                    val vector = Vector(1, 2, 3)
                    val chain  = Chain.fromUnsafe(vector)
                    assert(chain.size == 3)
                    assert(chain(0) == 1)
                    assert(chain(1) == 2)
                    assert(chain(2) == 3)
                }
            }
        }
    }

    "size" - {
        "returns the number of elements in a non-empty Chain" in {
            val chain = Chain(1, 2, 3, 4, 5)
            assert(chain.size == 5)
        }

        "returns 0 for an empty Chain" in {
            val chain = Chain.empty[Int]
            assert(chain.size == 0)
        }
    }

    "isEmpty" - {
        "returns false for a non-empty Chain" in {
            val chain = Chain(1, 2, 3)
            assert(!chain.isEmpty)
        }

        "returns true for an empty Chain" in {
            val chain = Chain.empty[Int]
            assert(chain.isEmpty)
        }
    }

    "apply" - {
        "returns correct elements from a Chain" in {
            val chain = Chain(1, 2, 3, 4, 5)
            assert(chain(0) == 1)
            assert(chain(2) == 3)
            assert(chain(4) == 5)
        }

        "throws for negative index" in {
            val chain = Chain(1, 2, 3)
            assertThrows[Throwable] {
                chain(-1)
            }
        }

        "throws for index >= size" in {
            val chain = Chain(1, 2, 3)
            assertThrows[Throwable] {
                chain(3)
            }
        }
    }

    "is" - {
        "returns true for equal Chains" in {
            val chain1 = Chain(1, 2, 3)
            val chain2 = Chain(1, 2, 3)
            assert(chain1.is(chain2))
        }

        "returns false for different Chains" in {
            val chain1 = Chain(1, 2, 3)
            val chain2 = Chain(3, 2, 1)
            assert(!chain1.is(chain2))
        }

        "returns true for empty Chains" in {
            val chain1 = Chain.empty[Int]
            val chain2 = Chain.empty[Int]
            assert(chain1.is(chain2))
        }

        "returns false for Chains of different sizes" in {
            val chain1 = Chain(1, 2, 3)
            val chain2 = Chain(1, 2)
            assertThrows[Throwable] {
                chain1.is(chain2)
            }
        }
    }

    "forall" - {
        "returns true when all elements satisfy the predicate" in {
            val chain = Chain(2, 4, 6, 8, 10)
            assert(chain.forall(_ % 2 == 0))
        }

        "returns false when at least one element does not satisfy the predicate" in {
            val chain = Chain(2, 4, 5, 8, 10)
            assert(!chain.forall(_ % 2 == 0))
        }

        "returns true for an empty Chain" in {
            val chain = Chain.empty[Int]
            assert(chain.forall(_ => false))
        }
    }

    "exists" - {
        "returns true when at least one element satisfies the predicate" in {
            val chain = Chain(1, 3, 5, 6, 9)
            assert(chain.exists(_ % 2 == 0))
        }

        "returns false when no element satisfies the predicate" in {
            val chain = Chain(1, 3, 5, 7, 9)
            assert(!chain.exists(_ % 2 == 0))
        }

        "returns false for an empty Chain" in {
            val chain = Chain.empty[Int]
            assert(!chain.exists(_ => true))
        }
    }

    "map" - {
        "transforms all elements using the provided function" in {
            val chain  = Chain(1, 2, 3, 4, 5)
            val result = chain.map(_ * 2)
            assert(result.size == 5)
            assert(result(0) == 2)
            assert(result(1) == 4)
            assert(result(2) == 6)
            assert(result(3) == 8)
            assert(result(4) == 10)
        }

        "returns an empty Chain when mapping an empty Chain" in {
            val chain  = Chain.empty[Int]
            val result = chain.map(_ * 2)
            assert(result.isEmpty)
        }

        "handles type transformations" in {
            val chain  = Chain(1, 2, 3)
            val result = chain.map(_.toString)
            assert(result.size == 3)
            assert(result(0) == "1")
            assert(result(1) == "2")
            assert(result(2) == "3")
        }
    }

    "mkString" - {
        "joins elements with the specified separator" in {
            val chain = Chain(1, 2, 3, 4, 5)
            assert(chain.mkString(", ") == "1, 2, 3, 4, 5")
        }

        "returns an empty string for an empty Chain" in {
            val chain = Chain.empty[Int]
            assert(chain.mkString(", ") == "")
        }

        "works with a single element" in {
            val chain = Chain("single")
            assert(chain.mkString(", ") == "single")
        }

        "uses toString on complex objects" in {
            case class Person(name: String)
            val chain = Chain(Person("Alice"), Person("Bob"))
            assert(chain.mkString("; ") == "Person(Alice); Person(Bob)")
        }
    }

    "Chain.exists (binary)" - {
        "returns true when at least one pair satisfies the predicate" in {
            val chain1 = Chain(1, 4, 3)
            val chain2 = Chain(3, 4, 5)
            assert(Chain.existsZip(chain1, chain2)(_ == _))
        }

        "returns false when no pair satisfies the predicate" in {
            val chain1 = Chain(1, 2, 3)
            val chain2 = Chain(4, 5, 6)
            assert(!Chain.existsZip(chain1, chain2)(_ == _))
        }

        "throws an exception for Chains of different sizes" in {
            val chain1 = Chain(1, 2, 3)
            val chain2 = Chain(4, 5)
            assertThrows[IllegalArgumentException] {
                Chain.existsZip(chain1, chain2)(_ == _)
            }
        }
    }

    "Chain.forall (binary)" - {
        "returns true when all pairs satisfy the predicate" in {
            val chain1 = Chain(1, 2, 3)
            val chain2 = Chain(1, 2, 3)
            assert(Chain.forallZip(chain1, chain2)(_ == _))
        }

        "returns false when at least one pair does not satisfy the predicate" in {
            val chain1 = Chain(1, 2, 3)
            val chain2 = Chain(1, 4, 3)
            assert(!Chain.forallZip(chain1, chain2)(_ == _))
        }

        "throws an exception for Chains of different sizes" in {
            val chain1 = Chain(1, 2, 3)
            val chain2 = Chain(1, 2)
            assertThrows[IllegalArgumentException] {
                Chain.forallZip(chain1, chain2)(_ == _)
            }
        }
    }

    "mixed" - {
        "chained operations" in {
            val chain  = Chain(1, 2, 3, 4, 5)
            val result = chain.map(_ * 2)
            assert(result.forall(_ % 2 == 0))
            assert(result.exists(_ > 8))
            assert(result.mkString("-") == "2-4-6-8-10")
        }

        "empty Chain operations" in {
            val chain = Chain.empty[Int]
            assert(chain.map(_ * 2).isEmpty)
            assert(chain.forall(_ => false))
            assert(!chain.exists(_ => true))
            assert(chain.mkString(",") == "")
        }

        "single element Chain operations" in {
            val chain = Chain(42)
            assert(chain.size == 1)
            assert(!chain.isEmpty)
            assert(chain(0) == 42)
            assert(chain.map(_ * 2)(0) == 84)
            assert(chain.forall(_ > 0))
            assert(chain.exists(_ == 42))
            assert(chain.mkString(",") == "42")
        }
    }

    "toArray and toArrayUnsafe" - {
        "toArray" - {
            "returns a copy of the underlying array" in {
                val chain = Chain(1, 2, 3)
                val array = chain.toArray
                array(0) = 99
                assert(chain(0) == 1)
            }

            "returns an empty array for an empty Chain" in {
                val chain = Chain.empty[Int]
                val array = chain.toArray
                assert(array.isEmpty)
            }
        }

        "toArrayUnsafe" - {
            "returns the underlying array without copying" in {
                val chain = Chain(1, 2, 3)
                val array = chain.toArrayUnsafe
                array(0) = 99
                assert(chain(0) == 99)
            }

            "returns an empty array for an empty Chain" in {
                val chain = Chain.empty[Int]
                val array = chain.toArrayUnsafe
                assert(array.isEmpty)
            }
        }
    }
end ChainTest
