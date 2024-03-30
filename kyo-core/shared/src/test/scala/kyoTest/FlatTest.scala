package kyoTest

import kyo.*

class FlatTest extends KyoTest:

    "ok" - {
        "concrete" in {
            implicitly[Flat[Int]]
            implicitly[Flat[Int < Any]]
            implicitly[Flat[Int < Options]]
            implicitly[Flat[Int < Nothing]]
            succeed
        }
        "fiber" in {
            implicitly[Flat[Fiber[Int] < Options]]
            succeed
        }
        "by evidence" in {
            def test1[T: Flat] =
                implicitly[Flat[T < IOs]]
                implicitly[Flat[T < Options]]
                implicitly[Flat[T < Any]]
            end test1
            test1[Int]
            succeed
        }
        "derived" in {
            def test2[T](using f: Flat[T < IOs]) =
                val _: Flat[T < Options] = Flat.derive[T, Options]
                implicitly[Flat[T]]
                implicitly[Flat[T < Options]]
                implicitly[Flat[T < Any]]
                implicitly[Flat[T | Int]]
            end test2
            test2[Int]
            succeed
        }
    }

    "nok" - {

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

        "any" in {
            assertDoesNotCompile("implicitly[Flat[Any]]")
            assertDoesNotCompile("implicitly[Flat[Any < IOs]]")
        }
    }
end FlatTest
