package kyo.kernel

import kyo.*
import kyo.Tag

class FlatTest extends Test:

    "ok" - {
        "concrete" in {
            implicitly[Flat[Int]]
            implicitly[Flat[String]]
            implicitly[Flat[Thread]]
            succeed
        }
        "derived from Tag" in {
            def test[A: Tag] =
                implicitly[Flat[A]]
                succeed
            test[Int]
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
            def test1[A] =
                assertDoesNotCompile("implicitly[Flat[A]]")
                assertDoesNotCompile("implicitly[Flat[A | Int]]")
                assertDoesNotCompile("implicitly[Flat[A < Options]]")
                assertDoesNotCompile("implicitly[Flat[A < Any]]")
            end test1
            test1[Int]
            succeed
        }

        "effect mismatch" in {
            def test[A: Flat](v: A < Abort[Int]): A < Abort[Int] = v
            test(1)
            test(1: Int < Abort[Int])
            assertDoesNotCompile("test(1: Int < Memo)")
        }

        "flat flat" in {
            def test[A](v: A < Memo)(using Flat[A]): A < Memo = v
            test(1)
            test(1: Int < Memo)
            assertDoesNotCompile("test(1: Int < Abort[Int])")
        }

        "any" in {
            assertDoesNotCompile("implicitly[Flat[Any]]")
            assertDoesNotCompile("implicitly[Flat[Any < IOs]]")
        }
    }
end FlatTest
