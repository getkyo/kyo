package kyo

import kyo.Tasty.SymbolId

/** Tests for Cat 19 Classpath.symbol and related accessors.
  *
  * Coverage:
  *   cpSymbolReturnsMaybe: cp.symbol(SymbolId(-1)) returns Absent; cp.symbol(SymbolId(5)) returns Present.
  *   sentinelUnresolvedDeleted: Classpath.sentinelUnresolved does not exist.
  *   unresolvedCrossFileRefSurfacesAsTastyError: finalizeMerge accumulates UnresolvedReference in SoftFail mode.
  *   ownerReturnsMaybe: Tasty.owner(rootPkg) returns Absent for a package with ownerId = SymbolId(-1).
  */
class ClasspathAccessorsTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // ── Fixture helpers ──────────────────────────────────────────────────────

    private def makePkg(id: Int, name: String): Tasty.Symbol.Package =
        Tasty.Symbol.Package(
            id = SymbolId(id),
            name = Tasty.Name(name),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(-1),
            memberIds = Chunk.empty
        )

    private def makeCls(id: Int, name: String): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            id = SymbolId(id),
            name = Tasty.Name(name),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(-1),
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk.empty,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            annotations = Chunk.empty,
            javaAnnotations = Chunk.empty
        )

    /** Build a minimal classpath with 6 symbols (indices 0..5). */
    private def makeFixtureCp(): Tasty.Classpath =
        val syms = Chunk.from((0 to 5).map: i =>
            makeCls(i, s"Sym$i"))
        Tasty.Classpath.make(
            symbols = syms,
            rootSymbolId = SymbolId(0),
            topLevelClassIds = Chunk.from(syms.map(_.id)),
            packageIds = Chunk.empty,
            fqnIndex = Dict.from(syms.zipWithIndex.map { case (s, i) => s"test.Sym$i" -> s.id }.toMap),
            packageIndex = Dict.empty[String, SymbolId],
            subclassIndex = Dict.empty[SymbolId, Chunk[SymbolId]],
            companionIndex = Dict.empty[SymbolId, SymbolId],
            moduleIndex = Dict.empty[String, Tasty.Java.Module.Descriptor],
            errors = Chunk.empty
        )
    end makeFixtureCp

    // ── Leaf 6: cpSymbolReturnsMaybe ─────────────────────────────────────────

    // Given: a fixture classpath with rootSymbolId = SymbolId(5).
    // When: cp.symbol(SymbolId(-1)) and cp.symbol(SymbolId(5)).
    // Then: the first returns Maybe.Absent; the second returns Maybe.Present(_).
    "cpSymbolReturnsMaybe: cp.symbol returns Absent for out-of-range id and Present for valid id" in {
        val cp     = makeFixtureCp()
        val absent = cp.symbol(SymbolId(-1))
        assert(absent == Maybe.Absent, s"Expected Absent for SymbolId(-1) but got $absent")
        val present = cp.symbol(SymbolId(5))
        assert(present.isDefined, s"Expected Present for SymbolId(5) but got $present")
        present match
            case Maybe.Present(sym) =>
                import Tasty.Name.asString
                assert(sym.id.value == 5, s"Expected symbol at index 5 but got id ${sym.id.value}")
            case Maybe.Absent =>
                fail("Expected Present but got Absent")
        end match
        succeed
    }

    // ── Leaf 7: sentinelUnresolvedDeleted ────────────────────────────────────

    // Given: a compileErrors probe for "Classpath.sentinelUnresolved".
    // When: the test asserts.
    // Then: the returned string is non-empty (the method no longer exists after Cat 19).
    "sentinelUnresolvedDeleted: Classpath.sentinelUnresolved does not exist" in {
        val errCount = compiletime.testing.typeCheckErrors(
            "(??? : kyo.Tasty.Classpath).sentinelUnresolved"
        ).length
        assert(errCount > 0, "Classpath.sentinelUnresolved must not exist after ")
        succeed
    }

    // ── Leaf 8: unresolvedCrossFileRefSurfacesAsTastyError ───────────────────

    // Given: a degenerate MergeState with a ghost FQN entry (FQN not backed by any allSyms entry)
    //        loaded via triggerUnresolvedReferenceForTest which calls finalizeMerge with SoftFail.
    // When: the resulting classpath's cp.errors is inspected.
    // Then: cp.errors contains exactly one TastyError.UnresolvedReference with the ghost FQN name.
    "unresolvedCrossFileRefSurfacesAsTastyError: finalizeMerge emits UnresolvedReference in SoftFail mode" in {
        Abort.run[TastyError](
            kyo.internal.tasty.query.ClasspathOrchestrator.triggerUnresolvedReferenceForTest()
        ).map: result =>
            result match
                case Result.Success(cp) =>
                    val unresolvedErrors = cp.errors.collect:
                        case e: TastyError.UnresolvedReference => e
                    assert(
                        unresolvedErrors.length == 1,
                        s"Expected exactly one UnresolvedReference in cp.errors but got: ${cp.errors}"
                    )
                    val ref = unresolvedErrors.head
                    assert(
                        ref.name.contains("GhostFqn"),
                        s"Expected UnresolvedReference.name to contain 'GhostFqn' but got: ${ref.name}"
                    )
                    succeed
                case Result.Failure(e) =>
                    fail(s"Expected Success with cp.errors containing UnresolvedReference but got Failure: $e")
                case Result.Panic(t) =>
                    fail(s"Unexpected panic: ${t.getMessage}")
    }

    // ── Leaf 9: ownerReturnsMaybe ────────────────────────────────────────────

    // Given: a fixture classpath where a package symbol has ownerId = SymbolId(-1).
    // When: Tasty.owner(rootPkg).eval is called.
    // Then: the result is Maybe.Absent (the root package has no owner).
    "ownerReturnsMaybe: Tasty.owner returns Absent for a package with ownerId = SymbolId(-1)" in {
        val rootPkg = makePkg(0, "root")
        val cp = Tasty.Classpath.make(
            symbols = Chunk(rootPkg),
            rootSymbolId = SymbolId(0),
            topLevelClassIds = Chunk.empty,
            packageIds = Chunk(SymbolId(0)),
            fqnIndex = Dict.empty[String, SymbolId],
            packageIndex = Dict("root" -> SymbolId(0)),
            subclassIndex = Dict.empty[SymbolId, Chunk[SymbolId]],
            companionIndex = Dict.empty[SymbolId, SymbolId],
            moduleIndex = Dict.empty[String, Tasty.Java.Module.Descriptor],
            errors = Chunk.empty
        )
        Tasty.withClasspath(cp):
            Tasty.owner(rootPkg).map: ownerMaybe =>
                assert(
                    ownerMaybe == Maybe.Absent,
                    s"Expected owner of root package to be Absent but got: $ownerMaybe"
                )
                succeed
    }

end ClasspathAccessorsTest
