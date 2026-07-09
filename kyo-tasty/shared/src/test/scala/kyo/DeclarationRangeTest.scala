package kyo

import kyo.Tasty.Name.asString

/** Tests for Tasty.declarationRange: the full declaration extent of a symbol, computed at cold load
  * from the TASTy Positions section and read from the active DecodeContext.
  *
  * Coverage:
  *   a method symbol's declaration range is Present and spans WIDER than its name-start
  *   sourcePosition (its start point coincides with sourcePosition, its end extends past it); and a
  *   symbol queried outside any active binding is Absent, never an Abort, mirroring bodyTree/symbolAt.
  */
class DeclarationRangeTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val someTraitPickle =
        Tasty.Pickle("some-trait", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someTraitTasty))

    private val fixtureClassesPkgPickle =
        Tasty.Pickle("fixture-classes-pkg", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.fixtureClassesPackageTasty))

    "declarationRange spans the full declaration, wider than the name-start sourcePosition" in {
        // `def bounded[A <: SomeTrait](a: A): Int = a.compute` is a single-line top-level def, so its
        // full extent shares the name-start line but reaches well past the name's column.
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(someTraitPickle, fixtureClassesPkgPickle)) {
                Tasty.classpath.map { classpath =>
                    val boundedSym = classpath.allMethods.find(_.name.asString == "bounded") match
                        case Some(m) => m
                        case None    => fail("bounded method not found in fixture classpath")
                    val declPos = boundedSym.sourcePosition match
                        case Maybe.Present(p) => p
                        case Maybe.Absent     => fail("expected 'bounded' to have a sourcePosition")
                    Tasty.declarationRange(boundedSym).map { maybeRange =>
                        val range = maybeRange match
                            case Maybe.Present(r) => r
                            case Maybe.Absent     => fail("expected a Present declaration range for 'bounded'; got Absent")
                        assert(
                            range.sourceFile == declPos.sourceFile,
                            s"expected the range's source file (${range.sourceFile}) to equal sourcePosition's (${declPos.sourceFile})"
                        )
                        assert(
                            range.startLine == declPos.line && range.startColumn == declPos.column,
                            s"expected the range start (${range.startLine},${range.startColumn}) to equal sourcePosition (${declPos.line},${declPos.column})"
                        )
                        assert(
                            range.endLine > range.startLine || range.endColumn > range.startColumn,
                            s"expected the declaration extent to be WIDER than the name-start point; got ${range.show}"
                        )
                        succeed
                    }
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "declarationRange outside any binding returns Absent (no abort)" in {
        // Resolve the symbol inside the binding, then query its declaration range AFTER withPickles
        // closes: no active binding means no DecodeContext, so declarationRange yields Absent, never
        // an Abort, exactly as bodyTree and symbolAt do.
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(someTraitPickle, fixtureClassesPkgPickle)) {
                Tasty.classpath.map { classpath =>
                    classpath.allMethods.find(_.name.asString == "bounded") match
                        case Some(m) => m
                        case None    => fail("bounded method not found in fixture classpath")
                }
            }.map { boundedSym =>
                Tasty.declarationRange(boundedSym)
            }
        ).map {
            case Result.Success(result) =>
                assert(result.isEmpty, s"expected Absent with no active binding; got $result")
                succeed
            case Result.Failure(e) => fail(s"expected no Abort with no active binding; got Failure($e)")
            case Result.Panic(t)   => throw t
        }
    }

end DeclarationRangeTest
