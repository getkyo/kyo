package kyo

import kyo.Tasty.SymbolId

/** Plan-mandated tests for Phase 06 (leaves 110-117): typed Classpath require* throwing variants.
  *
  * Fixture layout: 0 -> Class "A" fqn "pkg.A" 1 -> Trait "T" fqn "pkg.T" 2 -> Object "O" fqn "pkg.O" 3 -> Package "pkg"
  *
  * Pins: INV-005, INV-010.
  */
class ClasspathTypedRequireTest extends Test:

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
        Sync.defer:
            val clsA = makeClass(0, "A", ownerId = 3)
            val trtT = makeTrait(1, "T", ownerId = 3)
            val objO = makeObject(2, "O", ownerId = 3)
            val pkg  = makePackage(3, "pkg", ownerId = -1, Chunk(SymbolId(0), SymbolId(1), SymbolId(2)))
            Tasty.Classpath.make(
                symbols = Chunk(clsA, trtT, objO, pkg),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(0), SymbolId(1), SymbolId(2)),
                packageIds = Chunk(SymbolId(3)),
                fqnIndex = Dict("pkg.A" -> SymbolId(0), "pkg.T" -> SymbolId(1), "pkg.O" -> SymbolId(2)),
                packageIndex = Dict("pkg" -> SymbolId(3)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )

    // ── Leaf 110: requireClass-success ───────────────────────────────────────
    // Given: fixture with pkg.A.
    // When: Abort.run(cp.requireClass("pkg.A"))
    // Then: Result.Success(c) where c is Symbol.Class
    // Pins: INV-005, INV-010
    "Leaf 110: requireClass succeeds for an existing class FQN" in run {
        buildFixture.flatMap: cp =>
            Abort.run[TastyError](cp.requireClass("pkg.A")).map:
                case Result.Success(c) =>
                    assert(c.name.asString == "A", s"Expected Class name 'A' but got '${c.name.asString}'")
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // ── Leaf 111: requireClass-missing-fails ──────────────────────────────────
    // Given: fixture with no "missing.X" symbol.
    // When: Abort.run(cp.requireClass("missing.X"))
    // Then: Result.Failure(TastyError.NotFound("missing.X"))
    // Pins: INV-010
    "Leaf 111: requireClass fails with NotFound for a missing FQN" in run {
        buildFixture.flatMap: cp =>
            Abort.run[TastyError](cp.requireClass("missing.X")).map:
                case Result.Failure(TastyError.NotFound(fqn)) =>
                    assert(fqn == "missing.X", s"Expected fqn 'missing.X' but got '$fqn'")
                case Result.Success(c) =>
                    fail(s"Expected failure but got success: $c")
                case Result.Failure(e) =>
                    fail(s"Expected NotFound but got: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // ── Leaf 112: requireTrait-success ───────────────────────────────────────
    // Given: fixture with pkg.T (Trait).
    // When: Abort.run(cp.requireTrait("pkg.T"))
    // Then: Result.Success(t) where t is Symbol.Trait
    // Pins: INV-005, INV-010
    "Leaf 112: requireTrait succeeds for an existing trait FQN" in run {
        buildFixture.flatMap: cp =>
            Abort.run[TastyError](cp.requireTrait("pkg.T")).map:
                case Result.Success(t) =>
                    assert(t.name.asString == "T", s"Expected Trait name 'T' but got '${t.name.asString}'")
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // ── Leaf 113: requireObject-success ──────────────────────────────────────
    // Given: fixture with pkg.O (Object).
    // When: Abort.run(cp.requireObject("pkg.O"))
    // Then: Result.Success(o) where o is Symbol.Object
    // Pins: INV-005
    "Leaf 113: requireObject succeeds for an existing object FQN" in run {
        buildFixture.flatMap: cp =>
            Abort.run[TastyError](cp.requireObject("pkg.O")).map:
                case Result.Success(o) =>
                    assert(o.name.asString == "O", s"Expected Object name 'O' but got '${o.name.asString}'")
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // ── Leaf 114: requireClassLike-success ───────────────────────────────────
    // Given: fixture with pkg.T (Trait).
    // When: Abort.run(cp.requireClassLike("pkg.T"))
    // Then: Result.Success(c) where c is Symbol.ClassLike
    // Pins: INV-005
    "Leaf 114: requireClassLike succeeds for a trait FQN" in run {
        buildFixture.flatMap: cp =>
            Abort.run[TastyError](cp.requireClassLike("pkg.T")).map:
                case Result.Success(c) =>
                    assert(c.name.asString == "T", s"Expected ClassLike name 'T' but got '${c.name.asString}'")
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // ── Leaf 115: requirePackage-success ─────────────────────────────────────
    // Given: fixture with "pkg" Package.
    // When: Abort.run(cp.requirePackage("pkg"))
    // Then: Result.Success(p) where p is Symbol.Package
    // Pins: INV-005
    "Leaf 115: requirePackage succeeds for an existing package FQN" in run {
        buildFixture.flatMap: cp =>
            Abort.run[TastyError](cp.requirePackage("pkg")).map:
                case Result.Success(p) =>
                    assert(p.name.asString == "pkg", s"Expected Package name 'pkg' but got '${p.name.asString}'")
                case Result.Failure(e) =>
                    fail(s"Expected success but got failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // ── Leaf 116: requireModule-not-found ────────────────────────────────────
    // Given: fixture with no module descriptors.
    // When: Abort.run(cp.requireModule("does.not.exist"))
    // Then: Result.Failure(TastyError.NotFound)
    // Pins: INV-010
    "Leaf 116: requireModule fails with NotFound for a missing module name" in run {
        buildFixture.flatMap: cp =>
            Abort.run[TastyError](cp.requireModule("does.not.exist")).map:
                case Result.Failure(TastyError.NotFound(name)) =>
                    assert(name == "does.not.exist", s"Expected 'does.not.exist' but got '$name'")
                case Result.Success(m) =>
                    fail(s"Expected failure but got success: $m")
                case Result.Failure(e) =>
                    fail(s"Expected NotFound but got: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // ── Leaf 117: require-effect-row-is-abort-only ───────────────────────────
    // Given: cp.requireClass("pkg.A") expression.
    // When: compile-time type check.
    // Then: type is Symbol.Class < Abort[TastyError] (no Sync in the row).
    // Pins: INV-010
    "Leaf 117: requireClass return type is Symbol.Class < Abort[TastyError] (compile-time check)" in run {
        buildFixture.flatMap: cp =>
            val effect: Tasty.Symbol.Class < Abort[TastyError] = cp.requireClass("pkg.A")
            Abort.run[TastyError](effect).map:
                case Result.Success(c) =>
                    assert(c.name.asString == "A", s"Expected Class name 'A' but got '${c.name.asString}'")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

end ClasspathTypedRequireTest
