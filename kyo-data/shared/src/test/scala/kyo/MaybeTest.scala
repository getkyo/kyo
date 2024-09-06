package kyo

import kyo.Maybe.*
import kyo.Maybe.internal.DefinedEmpty

class MaybeTest extends Test:

    "apply" - {
        "creates Defined for non-null values" in {
            assert(Maybe(1) == Defined(1))
            assert(Maybe("hello") == Defined("hello"))
        }
        "creates Empty for null values" in {
            assert(Maybe(null) == Empty)
        }
        "creates DefinedEmpty for Empty" in {
            assert(Maybe(Maybe.empty).equals(DefinedEmpty.one))
        }
    }

    "isEmpty" - {
        "returns true for Empty" in {
            assert(Empty.isEmpty)
        }
        "returns false for Defined" in {
            assert(!Defined(1).isEmpty)
        }
    }

    "isDefined" - {
        "returns false for Empty" in {
            assert(!Empty.isDefined)
        }
        "returns true for Defined" in {
            assert(Defined(1).isDefined)
        }
    }

    "get" - {
        "returns the value for Defined" in {
            assert(Defined(1).get == 1)
            assert(Defined("hello").get == "hello")
        }
        "throws NoSuchElementException for Empty" in {
            assertThrows[NoSuchElementException] {
                Empty.get
            }
        }
    }

    "getOrElse" - {
        "returns the value for Defined" in {
            assert(Defined(1).getOrElse(0) == 1)
            assert(Defined("hello").getOrElse("") == "hello")
        }
        "returns the default value for Empty" in {
            assert(Empty.getOrElse(0) == 0)
            assert(Empty.getOrElse("") == "")
        }
    }

    "fold" - {
        "applies the empty function for Empty" in {
            assert(Empty.fold(0)(_ => 1) == 0)
        }
        "applies the non-empty function for Defined" in {
            assert(Defined(1).fold(0)(x => x + 1) == 2)
        }
    }

    "flatMap" - {
        "returns Empty for Empty" in {
            assert(Maybe.empty[Int].flatMap(x => Defined(x + 1)) == Empty)
        }
        "applies the function for Defined" in {
            assert(Defined(1).flatMap(x => Defined(x + 1)) == Defined(2))
        }
    }

    "flatten" - {
        "returns Empty for Empty" in {
            assert(Empty.flatten == Empty)
        }
        "returns the nested value for Defined" in {
            assert(Defined(Defined(1)).flatten == Defined(1))
        }
    }

    "filter" - {
        "returns Empty for Empty" in {
            assert(Empty.filter(_ => true) == Empty)
        }
        "returns Empty if the predicate is false" in {
            assert(Defined(1).filter(_ > 1) == Empty)
        }
        "returns Defined if the predicate is true" in {
            assert(Defined(1).filter(_ == 1) == Defined(1))
        }
    }

    "filterNot" - {
        "returns Empty for Empty" in {
            assert(Empty.filterNot(_ => false) == Empty)
        }
        "returns Defined if the predicate is false" in {
            assert(Defined(1).filterNot(_ > 1) == Defined(1))
        }
        "returns Empty if the predicate is true" in {
            assert(Defined(1).filterNot(_ == 1) == Empty)
        }
    }

    "contains" - {
        "returns false for Empty" in {
            assert(!Empty.contains(1))
        }
        "returns true if the element is equal" in {
            assert(Defined(1).contains(1))
        }
        "returns false if the element is not equal" in {
            assert(!Defined(1).contains(2))
        }
    }

    "exists" - {
        "returns false for Empty" in {
            assert(!Empty.exists(_ => true))
        }
        "returns true if the predicate is satisfied" in {
            assert(Defined(1).exists(_ == 1))
        }
        "returns false if the predicate is not satisfied" in {
            assert(!Defined(1).exists(_ != 1))
        }
    }

    "forall" - {
        "returns true for Empty" in {
            assert(Empty.forall(_ => false))
        }
        "returns true if the predicate is satisfied" in {
            assert(Defined(1).forall(_ == 1))
        }
        "returns false if the predicate is not satisfied" in {
            assert(!Defined(1).forall(_ != 1))
        }
    }

    "foreach" - {
        "does not apply the function for Empty" in {
            var applied = false
            Empty.foreach(_ => applied = true)
            assert(!applied)
        }
        "applies the function for Defined" in {
            var result = 0
            Defined(1).foreach(result += _)
            assert(result == 1)
        }
    }

    "collect" - {
        "returns Empty for Empty" in {
            assert(Empty.collect { case _ => 1 } == Empty)
        }
        "returns Empty if the partial function is not defined" in {
            assert(Defined(1).collect { case 2 => 3 } == Empty)
        }
        "returns Defined if the partial function is defined" in {
            assert(Defined(1).collect { case 1 => 2 } == Defined(2))
        }
    }

    "orElse" - {
        "returns the fallback option for Empty" in {
            assert(Empty.orElse(Defined(1)) == Defined(1))
        }
        "returns itself for Defined" in {
            assert(Defined(1).orElse(Defined(2)) == Defined(1))
        }
    }

    "zip" - {
        "returns Empty if either option is Empty" in {
            assert(Empty.zip(Empty) == Empty)
            assert(Empty.zip(Defined(1)) == Empty)
            assert(Defined(1).zip(Empty) == Empty)
        }
        "returns Defined with a tuple if both options are Defined" in {
            assert(Defined(1).zip(Defined(2)) == Defined((1, 2)))
        }
    }

    "iterator" - {
        "returns an empty iterator for Empty" in {
            assert(Empty.iterator.isEmpty)
        }
        "returns a single element iterator for Defined" in {
            assert(Defined(1).iterator.toList == List(1))
        }
    }

    "toList" - {
        "returns an empty list for Empty" in {
            assert(Empty.toList == Nil)
        }
        "returns a single element list for Defined" in {
            assert(Defined(1).toList == List(1))
        }
    }

    "toRight" - {
        "returns Left with the argument for Empty" in {
            assert(Empty.toRight(0) == Left(0))
        }
        "returns Right with the value for Defined" in {
            assert(Defined(1).toRight(0) == Right(1))
        }
    }

    "toLeft" - {
        "returns Right with the argument for Empty" in {
            assert(Empty.toLeft(0) == Right(0))
        }
        "returns Left with the value for Defined" in {
            assert(Defined(1).toLeft(0) == Left(1))
        }
    }

    "nested Defined(Empty)" - {
        "flatten should return the nested Maybe" in {
            assert(Defined(Defined(1)).flatten == Defined(1))
            assert(Defined(Empty).flatten == Empty)
            assert(Empty.flatten == Empty)
        }

        "get should return the value of the nested Maybe" in {
            assert(Defined(Defined(1)).get == Defined(1))
            assert(Defined(Empty).get == Empty)
        }

        "getOrElse should return the value of the nested Maybe" in {
            assert(Defined(Defined(1)).getOrElse(Defined(2)) == Defined(1))
            assert(Defined(Empty).getOrElse(Defined(2)) == Empty)
        }

        "orElse should return the value of the nested Maybe" in {
            assert(Defined(Defined(1)).orElse(Defined(Defined(2))) == Defined(Defined(1)))
            assert(Defined(Empty).orElse(Defined(Defined(2))) == Defined(Empty))
        }

        "fold should apply the non-empty function to the nested Maybe" in {
            assert(Defined(Defined(1)).fold(Defined(0))(x => x.map(_ + 1)) == Defined(2))
            assert(Defined(Maybe.empty[Int]).fold(Defined(0))(x => x.map(_ + 1)) == Empty)
        }

        "flatMap should apply the function to the nested Maybe" in {
            assert(Defined(Defined(1)).flatMap(x => x.map(_ + 1)) == Defined(2))
            assert(Defined(Maybe.empty[Int]).flatMap(x => x.map(_ + 1)) == Empty)
        }

        "map should apply the function to the nested Maybe" in {
            assert(Defined(Defined(1)).map(_ => Defined(2)) == Defined(Defined(2)))
            assert(Defined(Empty).map(_ => Defined(2)) == Defined(Defined(2)))
        }

        "filter should apply the predicate to the nested Maybe" in {
            assert(Defined(Defined(1)).filter(_.contains(1)) == Defined(Defined(1)))
            assert(Defined(Defined(1)).filter(_.contains(2)) == Empty)
            assert(Defined(Empty).filter(_.contains(1)) == Empty)
        }

        "filterNot should apply the predicate to the nested Maybe" in {
            assert(Defined(Defined(1)).filterNot(_.contains(2)) == Defined(Defined(1)))
            assert(Defined(Defined(1)).filterNot(_.contains(1)) == Empty)
            assert(Defined(Empty).filterNot(_.contains(1)) == Defined(Empty))
        }

        "exists should apply the predicate to the nested Maybe" in {
            assert(Defined(Defined(1)).exists(_.contains(1)))
            assert(!Defined(Defined(1)).exists(_.contains(2)))
            assert(!Defined(Empty).exists(_.contains(1)))
        }

        "forall should apply the predicate to the nested Maybe" in {
            assert(Defined(Defined(1)).forall(_.contains(1)))
            assert(!Defined(Defined(1)).forall(_.contains(2)))
            assert(!Defined(Empty).forall(_.contains(1)))
        }
    }

    "deeplyNestedDefined" - {
        val deeplyNestedDefined = Defined(Defined(Defined(Defined(Defined(1)))))

        "get should return deeply nested value" in {
            assert(deeplyNestedDefined.get == Defined(Defined(Defined(Defined(1)))))
        }

        "flatten should flatten deeply nested Defined" in {
            assert(deeplyNestedDefined.flatten.flatten == Defined(Defined(Defined(1))))
            assert(deeplyNestedDefined.flatten.flatten.flatten == Defined(Defined(1)))
            assert(deeplyNestedDefined.flatten.flatten.flatten.flatten == Defined(1))
        }

        "flatMap should apply function and flatten result" in {
            assert(deeplyNestedDefined.flatMap(x => x.flatMap(y => y.flatMap(z => z))) == Defined(Defined(1)))
        }

        "exists should apply to deeply nested predicate" in {
            assert(deeplyNestedDefined.exists(_.exists(_.exists(_.exists(_.exists(_ == 1))))))
            assert(!deeplyNestedDefined.exists(_.exists(_.exists(_.exists(_.exists(_ == 2))))))
        }

        "forall should apply to deeply nested predicate" in {
            assert(deeplyNestedDefined.forall(_.forall(_.forall(_.forall(_.forall(_ == 1))))))
            assert(!deeplyNestedDefined.forall(_.forall(_.forall(_.forall(_.forall(_ == 2))))))
        }
    }

    "equals" - {
        "should equate two deeply nested Maybes" in {
            assert(Defined(Defined(Defined(1))) == Defined(Defined(Defined(1))))
            assert(Defined(Defined(Empty)) == Defined(Defined(Empty)))
        }

        "should not equate different nested Maybes" in {
            assert(Defined(Defined(Defined(1))) != Defined(Defined(Defined(2))))
            assert(Defined(Defined(Defined(1))) != Defined(Defined(Empty)))
            assert(Defined(Defined(Empty)) != Defined(Empty))
        }
    }

    "pattern matching" - {
        "simple match" - {
            "should match Defined and extract value" in {
                val result = Defined(1) match
                    case Defined(x) => x
                    case Empty      => 0
                assert(result == 1)
            }

            "should match Empty" in {
                val result = Empty match
                    case Defined(x) => x
                    case Empty      => 0
                assert(result == 0)
            }
        }

        "nested match" - {
            "should match nested Defined and extract inner value" in {
                val result = Defined(Defined(1)) match
                    case Defined(Defined(x)) => x
                    case _                   => 0
                assert(result == 1)
            }

            "should match outer Empty" in {
                val result = Empty match
                    case Defined(Defined(x)) => x
                    case _                   => 0
                assert(result == 0)
            }

            "should match inner Empty" in {
                val result = Defined(Empty) match
                    case Defined(Defined(x)) => x
                    case _                   => 0
                assert(result == 0)
            }
        }

        "deep matching" - {
            val nestedMaybe = Defined(Defined(Defined(Defined(1))))

            "should match deeply nested Defined and extract inner value" in {
                val result = nestedMaybe match
                    case Defined(Defined(Defined(Defined(x)))) => x
                    case _                                     => 0
                assert(result == 1)
            }

            "should return default for deep mismatch" in {
                val result = nestedMaybe match
                    case Defined(Defined(Defined(Empty))) => 1
                    case _                                => 0
                assert(result == 0)
            }

            "should match partially and extract nested Defined" in {
                val result = nestedMaybe match
                    case Defined(Defined(x)) => x
                    case _                   => Empty
                assert(result == Defined(Defined(1)))
            }
        }
    }

    "for comprehensions" - {
        "with single Defined" - {
            "should return Defined with value" in {
                val result =
                    for
                        x <- Defined(1)
                    yield x
                assert(result == Defined(1))
            }
        }

        "with single Empty" - {
            "should return Empty" in {
                val result =
                    for
                        x <- Empty
                    yield x
                assert(result == Empty)
            }
        }

        "with multiple Defined" - {
            "should return Defined with result of yield" in {
                val result =
                    for
                        x <- Defined(1)
                        y <- Defined(2)
                    yield x + y
                assert(result == Defined(3))
            }
        }

        "with multiple Defined and Empty" - {
            "should return Empty if any are Empty" in {
                val result1 =
                    for
                        _ <- Defined(1)
                        _ <- Empty
                        _ <- Defined(3)
                    yield ()
                assert(result1 == Empty)

                val result2 =
                    for
                        _ <- Empty
                        _ <- Defined(2)
                        _ <- Defined(3)
                    yield ()
                assert(result2 == Empty)

                val result3 =
                    for
                        _ <- Defined(1)
                        _ <- Defined(2)
                        _ <- Empty
                    yield ()
                assert(result3 == Empty)
            }
        }

        "with if guards" - {
            "should return Defined if guard passes" in {
                val result =
                    for
                        x <- Defined(2)
                        if x % 2 == 0
                    yield x
                assert(result == Defined(2))
            }

            "should return Empty if guard fails" in {
                val result =
                    for
                        x <- Defined(3)
                        if x % 2 == 0
                    yield x
                assert(result == Empty)
            }
        }

        "with nested for comprehensions" - {
            "should return flat Defined if all succeed" in {
                val result =
                    for
                        x <- Defined(1)
                        y <-
                            for
                                a <- Defined(2)
                                b <- Defined(3)
                            yield a + b
                    yield x + y
                assert(result == Defined(6))
            }

            "should return Empty if any inner comprehension is Empty" in {
                val result =
                    for
                        _ <- Defined(1)
                        _ <-
                            for
                                _ <- Empty
                                _ <- Defined(3)
                            yield ()
                    yield ()
                assert(result == Empty)
            }
        }

    }

    "show" - {
        "should return 'Empty' for Empty" in {
            assert(Empty.show == "Empty")
        }

        "should return 'Defined(value)' for Defined" in {
            assert(Defined(1).show == "Defined(1)")
            assert(Defined("hello").show == "Defined(hello)")
        }

        "should handle nested Defined values" in {
            assert(Defined(Empty).show == "Defined(Empty)")
        }
    }

    "DefinedEmpty.toString" - {
        "should return correct string representation" in {
            assert(DefinedEmpty(1).toString == "Defined(Empty)")
            assert(DefinedEmpty(2).toString == "Defined(Defined(Empty))")
            assert(DefinedEmpty(3).toString == "Defined(Defined(Defined(Empty)))")
        }

        "should handle large depths" in {
            val largeDepth = 10
            val expected   = "Defined(" * largeDepth + "Empty" + ")" * largeDepth
            assert(DefinedEmpty(largeDepth).toString == expected)
        }
    }

end MaybeTest
