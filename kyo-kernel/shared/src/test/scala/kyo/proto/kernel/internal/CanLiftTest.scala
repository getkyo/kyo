package kyo.proto.kernel.internal

import kyo.Const
import kyo.discard
import kyo.proto.kernel.<
import kyo.proto.kernel.ArrowEffect
import org.scalatest.freespec.AnyFreeSpec
import scala.compiletime.testing.typeCheckErrors

class CanLiftTest extends AnyFreeSpec:

    sealed trait TestEffect extends ArrowEffect[Const[Int], Const[Int]]

    private inline def rejects(inline code: String): Unit =
        val errors = typeCheckErrors(code)
        discard(assert(
            errors.exists(_.message.contains("may contain a nested effect computation")),
            s"expected the nested-computation message, got ${errors.map(_.message)}"
        ))
    end rejects

    "resolves for non-pending types" in {
        discard(summon[CanLift[Int]])
        discard(summon[CanLift[String]])
        discard(summon[CanLift[List[Int]]])
        succeed
    }

    "resolves for an abstract type in a generic context" in {
        def genericContext[A]: CanLift[A] = summon[CanLift[A]]
        discard(genericContext[Int < Any])
        succeed
    }

    "rejects a known pending type" in {
        rejects("summon[CanLift[Int < Any]]")
        rejects("summon[CanLift[String < TestEffect]]")
    }

    "resolves for Unit and Nothing" in {
        discard(summon[CanLift[Unit]])
        discard(summon[CanLift[Nothing]])
        succeed
    }

    "sees through a type alias" in {
        type MyAlias[A] = A
        discard(summon[CanLift[MyAlias[Int]]])
        rejects("summon[CanLift[MyAlias[Int < Any]]]")
    }

    "resolves for higher-kinded types" in {
        trait HigherKinded[F[_]]
        discard(summon[CanLift[HigherKinded[List]]])
        discard(summon[CanLift[HigherKinded[[A] =>> A < Any]]])
        succeed
    }

    "resolves for a multi-parameter type" in {
        trait Complex[A, B, C[_]]
        discard(summon[CanLift[Complex[Int, String, List]]])
        succeed
    }

    "gates an extension method" in {
        extension [A](a: A)(using CanLift[A])
            def weakMethod: String = "weak method called"

        assert(42.weakMethod == "weak method called")
        assert("hello".weakMethod == "weak method called")
        val errors = typeCheckErrors("(42: Int < Any).weakMethod")
        assert(errors.nonEmpty, "expected a type error, code compiled")
    }

    "resolves under a type bound" in {
        def boundedMethod[A <: AnyVal: CanLift](a: A): String =
            discard(a)
            "bounded method called"
        assert(boundedMethod(42) == "bounded method called")
    }

    "resolves for a union, including one with a pending arm" in {
        type Union = Int | String
        discard(summon[CanLift[Union]])
        discard(summon[CanLift[Int | (String < Any)]])
        succeed
    }

    "resolves for an intersection of non-pending types and rejects one with a pending arm" in {
        trait A
        trait B
        type Intersection = A & B
        discard(summon[CanLift[Intersection]])
        rejects("summon[CanLift[A & (B < Any)]]")
    }

    "case objects lift without reaching the macro" in {
        discard(summon[CanLift[kyo.Maybe.Absent.type]])
        succeed
    }

    "a kyo module object does not lift" in {
        val errors = typeCheckErrors("summon[CanLift[ArrowEffect.type]]")
        assert(errors.nonEmpty, "expected a type error, code compiled")
    }

end CanLiftTest
