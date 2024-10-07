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

    "weak" - {
        "compile for non-Kyo types" in {
            implicitly[Flat.Weak[Int]]
            implicitly[Flat.Weak[String]]
            implicitly[Flat.Weak[List[Int]]]
            succeed
        }

        "compile for Kyo types in generic contexts" in {
            def genericContext[A]: Flat.Weak[A] = implicitly[Flat.Weak[A]]
            genericContext[Int < Any]
            genericContext[String < Env[Int]]
            succeed
        }

        "not compile for known Kyo types" in {
            assertDoesNotCompile("implicitly[Flat.Weak[Int < Any]]")
            assertDoesNotCompile("implicitly[Flat.Weak[String < Env[Int]]]")
        }

        "compile for Unit and Nothing" in {
            implicitly[Flat.Weak[Unit]]
            implicitly[Flat.Weak[Nothing]]
            succeed
        }

        "work with type aliases" in {
            type MyAlias[A] = A
            implicitly[Flat.Weak[MyAlias[Int]]]
            assertDoesNotCompile("implicitly[Flat.Weak[MyAlias[Int < Any]]]")
            succeed
        }

        "work with higher-kinded types" in {
            trait HigherKinded[F[_]]
            implicitly[Flat.Weak[HigherKinded[List]]]
            implicitly[Flat.Weak[HigherKinded[λ[A => A < Any]]]]
            succeed
        }

        "work in complex type scenarios" in {
            trait Complex[A, B, C[_]]
            implicitly[Flat.Weak[Complex[Int, String, List]]]
            implicitly[Flat.Weak[Complex[Int < Any, String, λ[A => A < Env[Int]]]]]
            succeed
        }

        "be usable in extension methods" in {
            extension [A](a: A)(using Flat.Weak[A])
                def weakMethod: String = "Weak method called"

            42.weakMethod
            "hello".weakMethod
            assertDoesNotCompile("(42: Int < Any).weakMethod")
            succeed
        }

        "work with type bounds" in {
            def boundedMethod[A <: AnyVal: Flat.Weak](a: A): String =
                discard(a)
                "Bounded method called"
            boundedMethod(42)
            boundedMethod("hello")
            succeed
        }

        "work with union types" in {
            type Union = Int | String
            implicitly[Flat.Weak[Union]]
            implicitly[Flat.Weak[Int | (String < Any)]]
            succeed
        }

        "work with intersection types" in {
            trait A
            trait B
            type Intersection = A & B
            implicitly[Flat.Weak[Intersection]]
            assertDoesNotCompile("implicitly[Flat.Weak[A & (B < Any)]]")
            succeed
        }
    }

end FlatTest
