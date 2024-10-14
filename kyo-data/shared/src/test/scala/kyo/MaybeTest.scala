package kyo

import kyo.Maybe.*
import kyo.Maybe.internal.DefinedEmpty

class MaybeTest extends Test:

    "apply" - {
        "creates Present for non-null values" in {
            assert(Maybe(1) == Present(1))
            assert(Maybe("hello") == Present("hello"))
        }
        "creates Absent for null values" in {
            assert(Maybe(null) == Absent)
        }
        "creates DefinedEmpty for Absent" in {
            assert(Maybe(Maybe.empty).equals(DefinedEmpty.one))
        }
    }

    "isEmpty" - {
        "returns true for Absent" in {
            assert(Absent.isEmpty)
        }
        "returns false for Present" in {
            assert(!Present(1).isEmpty)
        }
    }

    "isDefined" - {
        "returns false for Absent" in {
            assert(!Absent.isDefined)
        }
        "returns true for Present" in {
            assert(Present(1).isDefined)
        }
    }

    "get" - {
        "returns the value for Present" in {
            assert(Present(1).get == 1)
            assert(Present("hello").get == "hello")
        }
        "throws NoSuchElementException for Absent" in {
            assertThrows[NoSuchElementException] {
                Absent.get
            }
        }
    }

    "getOrElse" - {
        "returns the value for Present" in {
            assert(Present(1).getOrElse(0) == 1)
            assert(Present("hello").getOrElse("") == "hello")
        }
        "returns the default value for Absent" in {
            assert(Absent.getOrElse(0) == 0)
            assert(Absent.getOrElse("") == "")
        }
    }

    "fold" - {
        "applies the empty function for Absent" in {
            assert(Absent.fold(0)(_ => 1) == 0)
        }
        "applies the non-empty function for Present" in {
            assert(Present(1).fold(0)(x => x + 1) == 2)
        }
    }

    "flatMap" - {
        "returns Absent for Absent" in {
            assert(Maybe.empty[Int].flatMap(x => Present(x + 1)) == Absent)
        }
        "applies the function for Present" in {
            assert(Present(1).flatMap(x => Present(x + 1)) == Present(2))
        }
    }

    "flatten" - {
        "returns Absent for Absent" in {
            assert(Absent.flatten == Absent)
        }
        "returns the nested value for Present" in {
            assert(Present(Present(1)).flatten == Present(1))
        }
    }

    "filter" - {
        "returns Absent for Absent" in {
            assert(Absent.filter(_ => true) == Absent)
        }
        "returns Absent if the predicate is false" in {
            assert(Present(1).filter(_ > 1) == Absent)
        }
        "returns Present if the predicate is true" in {
            assert(Present(1).filter(_ == 1) == Present(1))
        }
    }

    "filterNot" - {
        "returns Absent for Absent" in {
            assert(Absent.filterNot(_ => false) == Absent)
        }
        "returns Present if the predicate is false" in {
            assert(Present(1).filterNot(_ > 1) == Present(1))
        }
        "returns Absent if the predicate is true" in {
            assert(Present(1).filterNot(_ == 1) == Absent)
        }
    }

    "contains" - {
        "returns false for Absent" in {
            assert(!Absent.contains(1))
        }
        "returns true if the element is equal" in {
            assert(Present(1).contains(1))
        }
        "returns false if the element is not equal" in {
            assert(!Present(1).contains(2))
        }
    }

    "exists" - {
        "returns false for Absent" in {
            assert(!Absent.exists(_ => true))
        }
        "returns true if the predicate is satisfied" in {
            assert(Present(1).exists(_ == 1))
        }
        "returns false if the predicate is not satisfied" in {
            assert(!Present(1).exists(_ != 1))
        }
    }

    "forall" - {
        "returns true for Absent" in {
            assert(Absent.forall(_ => false))
        }
        "returns true if the predicate is satisfied" in {
            assert(Present(1).forall(_ == 1))
        }
        "returns false if the predicate is not satisfied" in {
            assert(!Present(1).forall(_ != 1))
        }
    }

    "foreach" - {
        "does not apply the function for Absent" in {
            var applied = false
            Absent.foreach(_ => applied = true)
            assert(!applied)
        }
        "applies the function for Present" in {
            var result = 0
            Present(1).foreach(result += _)
            assert(result == 1)
        }
    }

    "collect" - {
        "returns Absent for Absent" in {
            assert(Absent.collect { case _ => 1 } == Absent)
        }
        "returns Absent if the partial function is not defined" in {
            assert(Present(1).collect { case 2 => 3 } == Absent)
        }
        "returns Present if the partial function is defined" in {
            assert(Present(1).collect { case 1 => 2 } == Present(2))
        }
    }

    "orElse" - {
        "returns the fallback option for Absent" in {
            assert(Absent.orElse(Present(1)) == Present(1))
        }
        "returns itself for Present" in {
            assert(Present(1).orElse(Present(2)) == Present(1))
        }
    }

    "zip" - {
        "returns Absent if either option is Absent" in {
            assert(Absent.zip(Absent) == Absent)
            assert(Absent.zip(Present(1)) == Absent)
            assert(Present(1).zip(Absent) == Absent)
        }
        "returns Present with a tuple if both options are Present" in {
            assert(Present(1).zip(Present(2)) == Present((1, 2)))
        }
    }

    "iterator" - {
        "returns an empty iterator for Absent" in {
            assert(Absent.iterator.isEmpty)
        }
        "returns a single element iterator for Present" in {
            assert(Present(1).iterator.toList == List(1))
        }
    }

    "toList" - {
        "returns an empty list for Absent" in {
            assert(Absent.toList == Nil)
        }
        "returns a single element list for Present" in {
            assert(Present(1).toList == List(1))
        }
    }

    "toRight" - {
        "returns Left with the argument for Absent" in {
            assert(Absent.toRight(0) == Left(0))
        }
        "returns Right with the value for Present" in {
            assert(Present(1).toRight(0) == Right(1))
        }
    }

    "toLeft" - {
        "returns Right with the argument for Absent" in {
            assert(Absent.toLeft(0) == Right(0))
        }
        "returns Left with the value for Present" in {
            assert(Present(1).toLeft(0) == Left(1))
        }
    }

    "nested Present(Absent)" - {
        "flatten should return the nested Maybe" in {
            assert(Present(Present(1)).flatten == Present(1))
            assert(Present(Absent).flatten == Absent)
            assert(Absent.flatten == Absent)
        }

        "get should return the value of the nested Maybe" in {
            assert(Present(Present(1)).get == Present(1))
            assert(Present(Absent).get == Absent)
        }

        "getOrElse should return the value of the nested Maybe" in {
            assert(Present(Present(1)).getOrElse(Present(2)) == Present(1))
            assert(Present(Absent).getOrElse(Present(2)) == Absent)
        }

        "orElse should return the value of the nested Maybe" in {
            assert(Present(Present(1)).orElse(Present(Present(2))) == Present(Present(1)))
            assert(Present(Absent).orElse(Present(Present(2))) == Present(Absent))
        }

        "fold should apply the non-empty function to the nested Maybe" in {
            assert(Present(Present(1)).fold(Present(0))(x => x.map(_ + 1)) == Present(2))
            assert(Present(Maybe.empty[Int]).fold(Present(0))(x => x.map(_ + 1)) == Absent)
        }

        "flatMap should apply the function to the nested Maybe" in {
            assert(Present(Present(1)).flatMap(x => x.map(_ + 1)) == Present(2))
            assert(Present(Maybe.empty[Int]).flatMap(x => x.map(_ + 1)) == Absent)
        }

        "map should apply the function to the nested Maybe" in {
            assert(Present(Present(1)).map(_ => Present(2)) == Present(Present(2)))
            assert(Present(Absent).map(_ => Present(2)) == Present(Present(2)))
        }

        "filter should apply the predicate to the nested Maybe" in {
            assert(Present(Present(1)).filter(_.contains(1)) == Present(Present(1)))
            assert(Present(Present(1)).filter(_.contains(2)) == Absent)
            assert(Present(Absent).filter(_.contains(1)) == Absent)
        }

        "filterNot should apply the predicate to the nested Maybe" in {
            assert(Present(Present(1)).filterNot(_.contains(2)) == Present(Present(1)))
            assert(Present(Present(1)).filterNot(_.contains(1)) == Absent)
            assert(Present(Absent).filterNot(_.contains(1)) == Present(Absent))
        }

        "exists should apply the predicate to the nested Maybe" in {
            assert(Present(Present(1)).exists(_.contains(1)))
            assert(!Present(Present(1)).exists(_.contains(2)))
            assert(!Present(Absent).exists(_.contains(1)))
        }

        "forall should apply the predicate to the nested Maybe" in {
            assert(Present(Present(1)).forall(_.contains(1)))
            assert(!Present(Present(1)).forall(_.contains(2)))
            assert(!Present(Absent).forall(_.contains(1)))
        }
    }

    "deeplyNestedDefined" - {
        val deeplyNestedDefined = Present(Present(Present(Present(Present(1)))))

        "get should return deeply nested value" in {
            assert(deeplyNestedDefined.get == Present(Present(Present(Present(1)))))
        }

        "flatten should flatten deeply nested Present" in {
            assert(deeplyNestedDefined.flatten.flatten == Present(Present(Present(1))))
            assert(deeplyNestedDefined.flatten.flatten.flatten == Present(Present(1)))
            assert(deeplyNestedDefined.flatten.flatten.flatten.flatten == Present(1))
        }

        "flatMap should apply function and flatten result" in {
            assert(deeplyNestedDefined.flatMap(x => x.flatMap(y => y.flatMap(z => z))) == Present(Present(1)))
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
            assert(Present(Present(Present(1))) == Present(Present(Present(1))))
            assert(Present(Present(Absent)) == Present(Present(Absent)))
        }

        "should not equate different nested Maybes" in {
            assert(Present(Present(Present(1))) != Present(Present(Present(2))))
            assert(Present(Present(Present(1))) != Present(Present(Absent)))
            assert(Present(Present(Absent)) != Present(Absent))
        }
    }

    "pattern matching" - {
        "simple match" - {
            "should match Present and extract value" in {
                val result = Present(1) match
                    case Present(x) => x
                    case Absent     => 0
                assert(result == 1)
            }

            "should match Absent" in {
                val result = Absent match
                    case Present(x) => x
                    case Absent     => 0
                assert(result == 0)
            }
        }

        "nested match" - {
            "should match nested Present and extract inner value" in {
                val result = Present(Present(1)) match
                    case Present(Present(x)) => x
                    case _                   => 0
                assert(result == 1)
            }

            "should match outer Absent" in {
                val result = Absent match
                    case Present(Present(x)) => x
                    case _                   => 0
                assert(result == 0)
            }

            "should match inner Absent" in {
                val result = Present(Absent) match
                    case Present(Present(x)) => x
                    case _                   => 0
                assert(result == 0)
            }
        }

        "deep matching" - {
            val nestedMaybe = Present(Present(Present(Present(1))))

            "should match deeply nested Present and extract inner value" in {
                val result = nestedMaybe match
                    case Present(Present(Present(Present(x)))) => x
                    case _                                     => 0
                assert(result == 1)
            }

            "should return default for deep mismatch" in {
                val result = nestedMaybe match
                    case Present(Present(Present(Absent))) => 1
                    case _                                 => 0
                assert(result == 0)
            }

            "should match partially and extract nested Present" in {
                val result = nestedMaybe match
                    case Present(Present(x)) => x
                    case _                   => Absent
                assert(result == Present(Present(1)))
            }
        }
    }

    "for comprehensions" - {
        "with single Present" - {
            "should return Present with value" in {
                val result =
                    for
                        x <- Present(1)
                    yield x
                assert(result == Present(1))
            }
        }

        "with single Absent" - {
            "should return Absent" in {
                val result =
                    for
                        x <- Absent
                    yield x
                assert(result == Absent)
            }
        }

        "with multiple Present" - {
            "should return Present with result of yield" in {
                val result =
                    for
                        x <- Present(1)
                        y <- Present(2)
                    yield x + y
                assert(result == Present(3))
            }
        }

        "with multiple Present and Absent" - {
            "should return Absent if any are Absent" in {
                val result1 =
                    for
                        _ <- Present(1)
                        _ <- Absent
                        _ <- Present(3)
                    yield ()
                assert(result1 == Absent)

                val result2 =
                    for
                        _ <- Absent
                        _ <- Present(2)
                        _ <- Present(3)
                    yield ()
                assert(result2 == Absent)

                val result3 =
                    for
                        _ <- Present(1)
                        _ <- Present(2)
                        _ <- Absent
                    yield ()
                assert(result3 == Absent)
            }
        }

        "with if guards" - {
            "should return Present if guard passes" in {
                val result =
                    for
                        x <- Present(2)
                        if x % 2 == 0
                    yield x
                assert(result == Present(2))
            }

            "should return Absent if guard fails" in {
                val result =
                    for
                        x <- Present(3)
                        if x % 2 == 0
                    yield x
                assert(result == Absent)
            }
        }

        "with nested for comprehensions" - {
            "should return flat Present if all succeed" in {
                val result =
                    for
                        x <- Present(1)
                        y <-
                            for
                                a <- Present(2)
                                b <- Present(3)
                            yield a + b
                    yield x + y
                assert(result == Present(6))
            }

            "should return Absent if any inner comprehension is Absent" in {
                val result =
                    for
                        _ <- Present(1)
                        _ <-
                            for
                                _ <- Absent
                                _ <- Present(3)
                            yield ()
                    yield ()
                assert(result == Absent)
            }
        }

    }

    "show" - {
        "should return 'Absent' for Absent" in {
            assert(Absent.show == "Absent")
        }

        "should return 'Present(value)' for Present" in {
            assert(Present(1).show == "Present(1)")
            assert(Present("hello").show == "Present(hello)")
        }

        "should handle nested Present values" in {
            assert(Present(Absent).show == "Present(Absent)")
        }
    }

    "DefinedEmpty.toString" - {
        "should return correct string representation" in {
            assert(DefinedEmpty(1).toString == "Present(Absent)")
            assert(DefinedEmpty(2).toString == "Present(Present(Absent))")
            assert(DefinedEmpty(3).toString == "Present(Present(Present(Absent)))")
        }

        "should handle large depths" in {
            val largeDepth = 10
            val expected   = "Present(" * largeDepth + "Absent" + ")" * largeDepth
            assert(DefinedEmpty(largeDepth).toString == expected)
        }
    }

end MaybeTest
