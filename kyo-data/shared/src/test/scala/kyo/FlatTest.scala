package kyo

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
        "derived from Flat" - {
            "simple" in {
                def test[A: Flat] =
                    implicitly[Flat[A]]
                    succeed
                test[Int]
            }
            "union item" in {
                def test[A: Flat] =
                    implicitly[Flat[Int | A]]
                    succeed
                test[Int]
            }
            "intersection item" in {
                def test[A: Flat] =
                    implicitly[Flat[Thread & A]]
                    succeed
                test[Int]
            }
        }
        "kyo data" in {
            implicitly[Flat[Maybe[Int]]]
            implicitly[Flat[Result[String, Int]]]
            implicitly[Flat[TypeMap[String & Int]]]
            implicitly[Flat[Duration]]
            implicitly[Flat[Text]]
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
            def test1[A] =
                assertDoesNotCompile("implicitly[Flat[A]]")
                assertDoesNotCompile("implicitly[Flat[A | Int]]")
                assertDoesNotCompile("implicitly[Flat[A < Options]]")
                assertDoesNotCompile("implicitly[Flat[A < Any]]")
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
