package kyo.kernel.internal

import kyo.*

class WeakFlatTest extends Test:

    "compile for non-Kyo types" in {
        implicitly[WeakFlat[Int]]
        implicitly[WeakFlat[String]]
        implicitly[WeakFlat[List[Int]]]
        succeed
    }

    "compile for Kyo types in generic contexts" in {
        def genericContext[A]: WeakFlat[A] = implicitly[WeakFlat[A]]
        genericContext[Int < Any]
        succeed
    }

    "not compile for known Kyo types" in {
        val error = "may contain a nested effect computation"
        typeCheckFailure("implicitly[WeakFlat[Int < Any]]")(error)
        typeCheckFailure("implicitly[WeakFlat[String < Env[Int]]]")(error)
    }

    "compile for Unit and Nothing" in {
        implicitly[WeakFlat[Unit]]
        implicitly[WeakFlat[Nothing]]
        succeed
    }

    "work with type aliases" in {
        type MyAlias[A] = A
        implicitly[WeakFlat[MyAlias[Int]]]
        typeCheckFailure("implicitly[WeakFlat[MyAlias[Int < Any]]]")("may contain a nested effect computation")
        succeed
    }

    "work with higher-kinded types" in {
        trait HigherKinded[F[_]]
        implicitly[WeakFlat[HigherKinded[List]]]
        implicitly[WeakFlat[HigherKinded[λ[A => A < Any]]]]
        succeed
    }

    "work in complex type scenarios" in {
        trait Complex[A, B, C[_]]
        implicitly[WeakFlat[Complex[Int, String, List]]]
        succeed
    }

    "be usable in extension methods" in {
        extension [A](a: A)(using WeakFlat[A])
            def weakMethod: String = "Weak method called"

        42.weakMethod
        "hello".weakMethod
        typeCheckFailure("(42: Int < Any).weakMethod")("may contain a nested effect computation")
        succeed
    }

    "work with type bounds" in {
        def boundedMethod[A <: AnyVal: WeakFlat](a: A): String =
            discard(a)
            "Bounded method called"
        boundedMethod(42)
        boundedMethod("hello")
        succeed
    }

    "work with union types" in {
        type Union = Int | String
        implicitly[WeakFlat[Union]]
        implicitly[WeakFlat[Int | (String < Any)]]
        succeed
    }

    "work with intersection types" in {
        trait A
        trait B
        type Intersection = A & B
        implicitly[WeakFlat[Intersection]]
        typeCheckFailure("implicitly[WeakFlat[A & (B < Any)]]")("may contain a nested effect computation.")
        succeed
    }
end WeakFlatTest
