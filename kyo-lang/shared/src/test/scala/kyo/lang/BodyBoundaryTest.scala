package kyo.lang

import kyo.*
import kyolang.*

/** The computation boundaries inside a body (kyo-lang/DESIGN.md rules 8, 9, 11), over the `Boundaries.kyo` fixture: the exact exported
  * signature of each definition and its behavior under ordinary handlers.
  */
class BodyBoundaryTest extends kyo.test.Test[Any]:

    private def run[A](value: A < Var[Int], initial: Int = 1): A = Var.run(initial)(value).eval

    "rule 8: handlers" - {
        "a handler receives the lowered block and removes its effect" in {
            Signatures.exact[Result[String, Int] < Var[Int]]("kyolang.Boundaries", "handled")
            assert(run(Boundaries.handled, 4) == Result.succeed(5))
            assert(run(Boundaries.handled, -1) == Result.fail("negative"))
        }
        "execution continues after the handler with its result" in {
            Signatures.exact[Int < Any]("kyolang.Boundaries", "handledThenContinue")
            assert(Boundaries.handledThenContinue.eval == -1)
        }
        "a call with an effectful argument as the handler argument" in {
            Signatures.exact[Result[String, Int] < Var[Int]]("kyolang.Boundaries", "handledCall")
            assert(run(Boundaries.handledCall, 3) == Result.succeed(3))
            assert(run(Boundaries.handledCall, 0) == Result.fail("non-positive"))
        }
        "a pure handler argument" in {
            Signatures.exact[Result[String, Int] < Any]("kyolang.Boundaries", "handledPure")
            assert(Boundaries.handledPure.eval == Result.succeed(42))
        }
        "branches inside a handler argument" in {
            Signatures.exact[Result[String, Int] < Var[Int]]("kyolang.Boundaries", "handledBranches")
            assert(run(Boundaries.handledBranches(true), 8) == Result.succeed(8))
            assert(run(Boundaries.handledBranches(false), 8) == Result.fail("off"))
        }
        "nested handlers keep their scopes in both orders" in {
            Signatures.exact[Result[String, Int] < Any]("kyolang.Boundaries", "nestedHandlers")
            Signatures.exact[Result[String, Int] < Any]("kyolang.Boundaries", "nestedHandlersReversed")
            assert(Boundaries.nestedHandlers.eval == Result.fail("stop"))
            assert(Boundaries.nestedHandlersReversed.eval == Result.fail("stop"))
        }
        "a method with a computation parameter receives the lowered argument" in {
            Signatures.exact[Result[String, Int] < Any]("kyolang.Boundaries", "handledByMethod")
            assert(Boundaries.handledByMethod.eval == Result.succeed(2))
            assert(Boundaries.handleWith(Abort.fail("x")).eval == Result.fail("x"))
        }
    }

    "rule 9: effectful closures" - {
        "a stored effectful closure returns a computation on each invocation" in {
            Signatures.exact[Int < Var[Int]]("kyolang.Boundaries", "storedEffectful")
            assert(run(Boundaries.storedEffectful, 10) == 23)
        }
        "a captured bound result keeps the closure pure" in {
            Signatures.exact[Int < Var[Int]]("kyolang.Boundaries", "captured")
            assert(run(Boundaries.captured, 10) == 23)
        }
        "a returned effectful closure carries its latent effect" in {
            Signatures.exact[Int => Int < Var[Int]]("kyolang.Boundaries", "returned")
            Signatures.exact[Int => Int < Var[Int]]("kyolang.Boundaries", "returnedAnnotated")
            assert(run(Boundaries.returned(3), 5) == 15)
            assert(run(Boundaries.returnedAnnotated(3), 5) == 15)
        }
        "an effectful local method" in {
            Signatures.exact[Int < Var[Int]]("kyolang.Boundaries", "localEffectful")
            assert(run(Boundaries.localEffectful, 2) == 10)
        }
        "a stored case lambda with an effectful case" in {
            Signatures.exact[Int < Var[Int]]("kyolang.Boundaries", "storedCases")
            assert(run(Boundaries.storedCases, 5) == 6)
        }
    }

    "rule 11: effect-aware receivers" - {
        "the callback body becomes a computation and the API is kept" in {
            Signatures.exact[Chunk[Int] < Var[Int]]("kyolang.Boundaries", "effectAware")
            assert(run(Boundaries.effectAware, 10) == Chunk(11, 12, 13))
        }
        "a callback whose body is one effectful call" in {
            Signatures.exact[Chunk[Int] < Var[Int]]("kyolang.Boundaries", "effectAwareDirect")
            assert(Var.runTuple(0)(Boundaries.effectAwareDirect).eval == (3, Chunk(1, 3)))
        }
        "a stored effectful closure given to an effect-aware API" in {
            Signatures.exact[Chunk[Int] < Var[Int]]("kyolang.Boundaries", "effectAwareStored")
            assert(run(Boundaries.effectAwareStored, 10) == Chunk(11, 12))
        }
        "the same callback to a collection method runs through the counterpart" in {
            Signatures.exact[Chunk[Int] < Var[Int]]("kyolang.Boundaries", "counterpart")
            assert(run(Boundaries.counterpart, 10) == Chunk(11, 12, 13))
        }
        "computations given to an API that accepts them" in {
            Signatures.exact[Chunk[Int] < Var[Int]]("kyolang.Boundaries", "collected")
            assert(run(Boundaries.collected, 1) == Chunk(1, 2))
        }
        "a binding annotated with a pending type holds the computation" in {
            Signatures.exact[Int < Var[Int]]("kyolang.Boundaries", "bound")
            assert(run(Boundaries.bound, 1) == 11)
        }
    }
end BodyBoundaryTest
