package kyo.kernel.internal

import kyo.Const
import kyo.kernel.*
import org.scalatest.freespec.AnyFreeSpec
import scala.compiletime.testing.typeCheckErrors

class CanLiftTest extends AnyFreeSpec:

    sealed trait TestEffect extends ArrowEffect[Const[Int], Const[Int]]

    private inline def typeCheckFailure(inline code: String)(expected: String): org.scalatest.Assertion =
        val errors = typeCheckErrors(code)
        assert(errors.nonEmpty, "expected a type error, code compiled")
        assert(errors.exists(_.message.contains(expected)), errors.map(_.message).mkString("\n"))
    end typeCheckFailure

    "compiles for non-Kyo types" in {
        implicitly[CanLift[Int]]
        implicitly[CanLift[String]]
        implicitly[CanLift[List[Int]]]
        succeed
    }

    "compiles for Kyo types in generic contexts" in {
        def genericContext[A]: CanLift[A] = implicitly[CanLift[A]]
        genericContext[Int < Any]
        succeed
    }

    "does not compile for known Kyo types" in {
        val error = "may contain a nested effect computation"
        typeCheckFailure("implicitly[CanLift[Int < Any]]")(error)
        typeCheckFailure("implicitly[CanLift[String < TestEffect]]")(error)
    }

    "compiles for Unit and Nothing" in {
        implicitly[CanLift[Unit]]
        implicitly[CanLift[Nothing]]
        succeed
    }

    "works with type aliases" in {
        type MyAlias[A] = A
        implicitly[CanLift[MyAlias[Int]]]
        typeCheckFailure("implicitly[CanLift[MyAlias[Int < Any]]]")("may contain a nested effect computation")
    }

    "works with higher-kinded types" in {
        trait HigherKinded[F[_]]
        implicitly[CanLift[HigherKinded[List]]]
        implicitly[CanLift[HigherKinded[[A] =>> A < Any]]]
        succeed
    }

    "works in complex type scenarios" in {
        trait Complex[A, B, C[_]]
        implicitly[CanLift[Complex[Int, String, List]]]
        succeed
    }

    "is usable in extension methods" in {
        extension [A](a: A)(using CanLift[A])
            def weakMethod: String = "weak method called"

        assert(42.weakMethod == "weak method called")
        assert("hello".weakMethod == "weak method called")
        typeCheckFailure(
            "extension [A](a: A)(using CanLift[A]) def weakMethod2: String = \"x\"; (42: Int < Any).weakMethod2"
        )("may contain a nested effect computation")
    }

    "works with type bounds" in {
        def boundedMethod[A <: AnyVal: CanLift](a: A): String =
            val _ = a
            "bounded method called"
        assert(boundedMethod(42) == "bounded method called")
        succeed
    }

    "works with union types" in {
        type Union = Int | String
        implicitly[CanLift[Union]]
        implicitly[CanLift[Int | (String < Any)]]
        succeed
    }

    "works with intersection types" in {
        trait A
        trait B
        type Intersection = A & B
        implicitly[CanLift[Intersection]]
        typeCheckFailure("implicitly[CanLift[A & (B < Any)]]")("may contain a nested effect computation")
    }

    "rejects kyo module singletons" in {
        // the macro's abort is swallowed by the given search, so the rejection
        // surfaces as the implicitNotFound text; the module-specific message is
        // unreachable today
        typeCheckFailure("implicitly[CanLift[ArrowEffect.type]]")("may contain a nested effect computation")
        typeCheckFailure("implicitly[CanLift[kyo.Kyo.type]]")("may contain a nested effect computation")
    }

    "accepts case object singletons" in {
        implicitly[CanLift[kyo.Maybe.Absent.type]]
        case object Local
        implicitly[CanLift[Local.type]]
        succeed
    }

end CanLiftTest
