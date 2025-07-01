package kyo.kernel.internal

import kyo.*

class CanLiftTest extends Test:

    "compile for non-Kyo types" in {
        implicitly[CanLift[Int]]
        implicitly[CanLift[String]]
        implicitly[CanLift[List[Int]]]
        succeed
    }

    "compile for Kyo types in generic contexts" in {
        def genericContext[A]: CanLift[A] = implicitly[CanLift[A]]
        genericContext[Int < Any]
        succeed
    }

    "not compile for known Kyo types" in {
        val error = "may contain a nested effect computation"
        typeCheckFailure("implicitly[CanLift[Int < Any]]")(error)
        typeCheckFailure("implicitly[CanLift[String < Env[Int]]]")(error)
    }

    "compile for Unit and Nothing" in {
        implicitly[CanLift[Unit]]
        implicitly[CanLift[Nothing]]
        succeed
    }

    "work with type aliases" in {
        type MyAlias[A] = A
        implicitly[CanLift[MyAlias[Int]]]
        typeCheckFailure("implicitly[CanLift[MyAlias[Int < Any]]]")("may contain a nested effect computation")
        succeed
    }

    "work with higher-kinded types" in {
        trait HigherKinded[F[_]]
        implicitly[CanLift[HigherKinded[List]]]
        implicitly[CanLift[HigherKinded[λ[A => A < Any]]]]
        succeed
    }

    "work in complex type scenarios" in {
        trait Complex[A, B, C[_]]
        implicitly[CanLift[Complex[Int, String, List]]]
        succeed
    }

    "be usable in extension methods" in {
        extension [A](a: A)(using CanLift[A])
            def weakMethod: String = "Weak method called"

        42.weakMethod
        "hello".weakMethod
        typeCheckFailure("(42: Int < Any).weakMethod")("may contain a nested effect computation")
        succeed
    }

    "work with type bounds" in {
        def boundedMethod[A <: AnyVal: CanLift](a: A): String =
            discard(a)
            "Bounded method called"
        boundedMethod(42)
        boundedMethod("hello")
        succeed
    }

    "work with union types" in {
        type Union = Int | String
        implicitly[CanLift[Union]]
        implicitly[CanLift[Int | (String < Any)]]
        succeed
    }

    "work with intersection types" in {
        trait A
        trait B
        type Intersection = A & B
        implicitly[CanLift[Intersection]]
        typeCheckFailure("implicitly[CanLift[A & (B < Any)]]")("may contain a nested effect computation.")
        succeed
    }
end CanLiftTest
