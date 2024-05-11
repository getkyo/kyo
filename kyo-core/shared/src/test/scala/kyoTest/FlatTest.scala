package kyoTest

import kyo.*

class FlatTest extends KyoTest:

    "ok" - {
        "concrete" in {
            implicitly[Flat[Int]]
            implicitly[Flat[String]]
            implicitly[Flat[Thread]]
            succeed
        }
        "fiber" in {
            implicitly[Flat[Fiber[Int]]]
            succeed
        }
    }

    "nok" - {

        "pending type" in {
            assertDoesNotCompile("implicitly[Flat[Int < Any]]")
            assertDoesNotCompile("implicitly[Flat[Int < Options]]")
            assertDoesNotCompile("implicitly[Flat[Int < Nothing]]")
        }

        "nested" in {
            assertDoesNotCompile("implicitly[Flat[Int < IOs < IOs]]")
            assertDoesNotCompile("implicitly[Flat[Any < IOs < IOs]]")
        }

        "nested w/ mismatch" in {
            assertDoesNotCompile("implicitly[Flat[Int < Options < IOs]]")
            assertDoesNotCompile("implicitly[Flat[Int < IOs < Options]]")
        }

        "generic" in {
            def test1[T] =
                assertDoesNotCompile("implicitly[Flat[T]]")
                assertDoesNotCompile("implicitly[Flat[T | Int]]")
                assertDoesNotCompile("implicitly[Flat[T < Options]]")
                assertDoesNotCompile("implicitly[Flat[T < Any]]")
            end test1
            test1[Int]
            succeed
        }

        "effect mismatch" in {
            def test[T: Flat](v: T < Fibers): T < Fibers = v
            test(1)
            test(1: Int < Fibers)
            assertDoesNotCompile("test(1: Int < Options)")
        }

        "flat flat" in {
            def test[T](v: T < Fibers)(using Flat[T]): T < Fibers = v
            test(1)
            test(1: Int < Fibers)
            assertDoesNotCompile("test(1: Int < Options)")
        }

        "any" in {
            assertDoesNotCompile("implicitly[Flat[Any]]")
            assertDoesNotCompile("implicitly[Flat[Any < IOs]]")
        }
    }
end FlatTest
