package kyo

import kyo.internal.tasty.query.DecodeContext

/** Exercises the Present path of `Tasty.bodyTree(Var)`.
  *
  * Loads `fixtureClassesPackageTasty` which contains `var topLevelVar: Int = 0` and verifies that the Var's
  * bodyTree returns `Maybe.Present(_)`.
  */
class VarBodyPresentTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val fixturePickle = Tasty.Pickle(
        "fixture-classes-pkg",
        Tasty.Version(28, 3, 0),
        Span.from(kyo.fixtures.Embedded.fixtureClassesPackageTasty)
    )

    "Tasty.bodyTree(Var) returns Present(Tree) for a var with an initializer" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(fixturePickle)) {
                Tasty.classpath.map { classpath =>
                    val varWithBodyOpt = classpath.symbols.collectFirst {
                        case v: Tasty.Symbol.Var => v
                    }
                    varWithBodyOpt match
                        case None =>
                            // The fixture contains `var topLevelVar: Int = 0`; the var MUST be present.
                            Kyo.lift(
                                fail("No Var found in FixtureClasses-package fixture; expected topLevelVar")
                            )
                        case Some(v) =>
                            Tasty.bodyTree(v).map { result =>
                                assert(
                                    result.isDefined,
                                    s"Tasty.bodyTree(Var) must return Present for var '${v.name.asString}' which has body bytes"
                                )
                                succeed
                            }
                    end match
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
            case Result.Panic(t)   => throw t
        }
    }

end VarBodyPresentTest
