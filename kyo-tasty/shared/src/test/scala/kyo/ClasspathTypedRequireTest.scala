package kyo

import kyo.Tasty.SymbolId

/** Tests for typed Classpath require* variants.
  *
  * Fixture: Class "A" (pkg.A), Trait "T" (pkg.T), Object "O" (pkg.O), Package "pkg".
  */
class ClasspathTypedRequireTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private def makeClass(id: Int, name: String, ownerId: Int): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )

    private def makeTrait(id: Int, name: String, ownerId: Int): Tasty.Symbol.Trait =
        Tasty.Symbol.Trait(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )

    private def makeObject(id: Int, name: String, ownerId: Int): Tasty.Symbol.Object =
        Tasty.Symbol.Object(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(ownerId),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty
        )

    private def makePackage(id: Int, name: String, ownerId: Int, members: Chunk[SymbolId]): Tasty.Symbol.Package =
        Tasty.Symbol.Package(SymbolId(id), Tasty.Name(name), Tasty.Flags.empty, SymbolId(ownerId), members)

    private def buildFixture(using Frame): Tasty.Classpath < Sync =
        Sync.defer {
            val clsA = makeClass(0, "A", ownerId = 3)
            val trtT = makeTrait(1, "T", ownerId = 3)
            val objO = makeObject(2, "O", ownerId = 3)
            val pkg  = makePackage(3, "pkg", ownerId = -1, Chunk(SymbolId(0), SymbolId(1), SymbolId(2)))
            Tasty.Classpath.make(
                symbols = Chunk(clsA, trtT, objO, pkg),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(0), SymbolId(1), SymbolId(2)),
                packageIds = Chunk(SymbolId(3)),
                fullNameIndex = Dict("pkg.A" -> SymbolId(0), "pkg.T" -> SymbolId(1), "pkg.O" -> SymbolId(2)),
                packageIndex = Dict("pkg" -> SymbolId(3)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }

    "requireClass succeeds for an existing class fully-qualified name" in {
        buildFixture.map { classpath =>
            Abort.run[TastyError](classpath.requireClass("pkg.A")).map {
                case Result.Success(c) =>
                    assert(c.name.asString == "A", s"Expected Class name 'A' but got '${c.name.asString}'")
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
            }
        }
    }

    "requireClass fails with NotFound for a missing fully-qualified name" in {
        buildFixture.map { classpath =>
            Abort.run[TastyError](classpath.requireClass("missing.X")).map {
                case Result.Failure(TastyError.NotFound(fullName)) =>
                    assert(fullName == "missing.X", s"Expected fullName 'missing.X' but got '$fullName'")
                case Result.Success(c) =>
                    fail(s"Expected failure but got success: $c")
                case Result.Failure(e) =>
                    fail(s"Expected NotFound but got: $e")
                case Result.Panic(t) =>
                    throw t
            }
        }
    }

    "requireTrait succeeds for an existing trait fully-qualified name" in {
        buildFixture.map { classpath =>
            Abort.run[TastyError](classpath.requireTrait("pkg.T")).map {
                case Result.Success(t) =>
                    assert(t.name.asString == "T", s"Expected Trait name 'T' but got '${t.name.asString}'")
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
            }
        }
    }

    "requireObject succeeds for an existing object fully-qualified name" in {
        buildFixture.map { classpath =>
            Abort.run[TastyError](classpath.requireObject("pkg.O")).map {
                case Result.Success(o) =>
                    assert(o.name.asString == "O", s"Expected Object name 'O' but got '${o.name.asString}'")
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
            }
        }
    }

    "requireClassLike succeeds for a trait fully-qualified name" in {
        buildFixture.map { classpath =>
            Abort.run[TastyError](classpath.requireClassLike("pkg.T")).map {
                case Result.Success(c) =>
                    assert(c.name.asString == "T", s"Expected ClassLike name 'T' but got '${c.name.asString}'")
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
            }
        }
    }

    "requirePackage succeeds for an existing package fully-qualified name" in {
        buildFixture.map { classpath =>
            Abort.run[TastyError](classpath.requirePackage("pkg")).map {
                case Result.Success(p) =>
                    assert(p.name.asString == "pkg", s"Expected Package name 'pkg' but got '${p.name.asString}'")
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
            }
        }
    }

    "requireModule fails with NotFound for a missing module name" in {
        buildFixture.map { classpath =>
            Abort.run[TastyError](classpath.requireModule("does.not.exist")).map {
                case Result.Failure(TastyError.NotFound(name)) =>
                    assert(name == "does.not.exist", s"Expected 'does.not.exist' but got '$name'")
                case Result.Success(m) =>
                    fail(s"Expected failure but got success: $m")
                case Result.Failure(e) =>
                    fail(s"Expected NotFound but got: $e")
                case Result.Panic(t) =>
                    throw t
            }
        }
    }

    "requireClass return type is Symbol.Class < Abort[TastyError] (compile-time check)" in {
        buildFixture.map { classpath =>
            val effect: Tasty.Symbol.Class < Abort[TastyError] = classpath.requireClass("pkg.A")
            Abort.run[TastyError](effect).map {
                case Result.Success(c) =>
                    assert(c.name.asString == "A", s"Expected Class name 'A' but got '${c.name.asString}'")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
            }
        }
    }

end ClasspathTypedRequireTest
