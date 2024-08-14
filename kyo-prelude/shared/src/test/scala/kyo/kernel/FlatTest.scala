package kyo2.kernel

import kyo.Tag
import kyo2.*

class FlatTest extends Test:

    "ok" - {
        "concrete" in {
            implicitly[Flat[Int]]
            implicitly[Flat[String]]
            implicitly[Flat[Thread]]
            succeed
        }
        "derived from Tag" in {
            def test[T: Tag] =
                implicitly[Flat[T]]
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
            def test[T: Flat](v: T < Abort[Int]): T < Abort[Int] = v
            test(1)
            test(1: Int < Abort[Int])
            assertDoesNotCompile("test(1: Int < Memo)")
        }

        "flat flat" in {
            def test[T](v: T < Memo)(using Flat[T]): T < Memo = v
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
