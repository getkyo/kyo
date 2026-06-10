package kyo.kernel.internal

import kyo.*

class CanLiftTest extends kyo.test.Test[Any]:

    "compile for non-Kyo types" in {
        implicitly[CanLift[Int]]
        implicitly[CanLift[String]]
        implicitly[CanLift[List[Int]]]
        succeed("compile-time check: CanLift instances exist for non-Kyo types")
    }

    "compile for Kyo types in generic contexts" in {
        def genericContext[A]: CanLift[A] = implicitly[CanLift[A]]
        genericContext[Int < Any]
        succeed("compile-time check: CanLift works in generic contexts")
    }

    "not compile for known Kyo types" in {
        val error = "may contain a nested effect computation"
        typeCheckFailure("implicitly[CanLift[Int < Any]]")(error)
        typeCheckFailure("implicitly[CanLift[String < Env[Int]]]")(error)
    }

    "compile for Unit and Nothing" in {
        implicitly[CanLift[Unit]]
        implicitly[CanLift[Nothing]]
        succeed("compile-time check: CanLift exists for Unit and Nothing")
    }

    "work with type aliases" in {
        type MyAlias[A] = A
        implicitly[CanLift[MyAlias[Int]]]
        typeCheckFailure("implicitly[CanLift[MyAlias[Int < Any]]]")("may contain a nested effect computation")
        // typeCheckFailure already counts as an assertion
    }

    "work with higher-kinded types" in {
        trait HigherKinded[F[_]]
        implicitly[CanLift[HigherKinded[List]]]
        implicitly[CanLift[HigherKinded[λ[A => A < Any]]]]
        succeed("compile-time check: CanLift works with higher-kinded types")
    }

    "work in complex type scenarios" in {
        trait Complex[A, B, C[_]]
        implicitly[CanLift[Complex[Int, String, List]]]
        succeed("compile-time check: CanLift works with complex type scenarios")
    }

    "be usable in extension methods" in {
        extension [A](a: A)(using CanLift[A])
            def weakMethod: String = "Weak method called"

        42.weakMethod
        "hello".weakMethod
        typeCheckFailure("(42: Int < Any).weakMethod")("may contain a nested effect computation")
        // typeCheckFailure already counts as an assertion
    }

    "work with type bounds" in {
        def boundedMethod[A <: AnyVal: CanLift](a: A): String =
            discard(a)
            "Bounded method called"
        boundedMethod(42)
        boundedMethod("hello")
        succeed("compile-time check: CanLift works with type bounds")
    }

    "work with union types" in {
        type Union = Int | String
        implicitly[CanLift[Union]]
        implicitly[CanLift[Int | (String < Any)]]
        succeed("compile-time check: CanLift works with union types")
    }

    "work with intersection types" in {
        trait A
        trait B
        type Intersection = A & B
        implicitly[CanLift[Intersection]]
        typeCheckFailure("implicitly[CanLift[A & (B < Any)]]")("may contain a nested effect computation.")
        // typeCheckFailure already counts as an assertion
    }
end CanLiftTest
