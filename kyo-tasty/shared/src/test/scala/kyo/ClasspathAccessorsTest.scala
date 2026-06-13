package kyo

import kyo.Tasty.SymbolId

/** Tests for Classpath.symbol and related accessors: symbol returns Maybe, sentinelUnresolved
  * does not exist, finalizeMerge accumulates UnresolvedReference in SoftFail mode, and
  * Tasty.owner returns Absent for a root package.
  */
class ClasspathAccessorsTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

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
        val syms = Chunk.from((0 to 5).map(i => makeCls(i, s"Sym$i")))
        Tasty.Classpath.make(
            symbols = syms,
            rootSymbolId = SymbolId(0),
            topLevelClassIds = Chunk.from(syms.map(_.id)),
            packageIds = Chunk.empty,
            fullNameIndex = Dict.from(syms.zipWithIndex.map { case (s, i) => s"test.Sym$i" -> s.id }.toMap),
            packageIndex = Dict.empty[String, SymbolId],
            subclassIndex = Dict.empty[SymbolId, Chunk[SymbolId]],
            companionIndex = Dict.empty[SymbolId, SymbolId],
            moduleIndex = Dict.empty[String, Tasty.Java.Module.Descriptor],
            errors = Chunk.empty
        )
    end makeFixtureCp

    "cpSymbolReturnsMaybe: classpath.symbol returns Absent for out-of-range id and Present for valid id" in {
        val classpath = makeFixtureCp()
        val absent    = classpath.symbol(SymbolId(-1))
        assert(absent == Maybe.Absent, s"Expected Absent for SymbolId(-1) but got $absent")
        val present = classpath.symbol(SymbolId(5))
        assert(present.isDefined, s"Expected Present for SymbolId(5) but got $present")
        present match
            case Maybe.Present(symbol) =>
                import Tasty.Name.asString
                assert(symbol.id.value == 5, s"Expected symbol at index 5 but got id ${symbol.id.value}")
            case Maybe.Absent =>
                fail("Expected Present but got Absent")
        end match
        succeed
    }

    "sentinelUnresolvedDeleted: Classpath.sentinelUnresolved does not exist" in {
        val errCount = compiletime.testing.typeCheckErrors(
            "(??? : kyo.Tasty.Classpath).sentinelUnresolved"
        ).length
        assert(errCount > 0, "Classpath.sentinelUnresolved must not exist")
        succeed
    }

    "unresolvedCrossFileRefSurfacesAsTastyError: finalizeMerge emits UnresolvedReference in SoftFail mode" in {
        Abort.run[TastyError](
            kyo.internal.tasty.query.ClasspathOrchestrator.triggerUnresolvedReferenceForTest()
        ).map { result =>
            result match
                case Result.Success(classpath) =>
                    val unresolvedErrors = classpath.errors.collect {
                        case e: TastyError.UnresolvedReference => e
                    }
                    assert(
                        unresolvedErrors.length == 1,
                        s"Expected exactly one UnresolvedReference in classpath.errors but got: ${classpath.errors}"
                    )
                    val ref = unresolvedErrors.head
                    assert(
                        ref.name.contains("GhostFullName"),
                        s"Expected UnresolvedReference.name to contain 'GhostFullName' but got: ${ref.name}"
                    )
                    succeed
                case Result.Failure(e) =>
                    fail(s"Expected Success with classpath.errors containing UnresolvedReference but got Failure: $e")
                case Result.Panic(t) =>
                    fail(s"Unexpected panic: ${t.getMessage}")
        }
    }

    "ownerReturnsMaybe: classpath.owner returns Absent for a package with ownerId = SymbolId(-1)" in {
        val rootPkg = makePkg(0, "root")
        val classpath = Tasty.Classpath.make(
            symbols = Chunk(rootPkg),
            rootSymbolId = SymbolId(0),
            topLevelClassIds = Chunk.empty,
            packageIds = Chunk(SymbolId(0)),
            fullNameIndex = Dict.empty[String, SymbolId],
            packageIndex = Dict("root" -> SymbolId(0)),
            subclassIndex = Dict.empty[SymbolId, Chunk[SymbolId]],
            companionIndex = Dict.empty[SymbolId, SymbolId],
            moduleIndex = Dict.empty[String, Tasty.Java.Module.Descriptor],
            errors = Chunk.empty
        )
        val ownerMaybe = classpath.owner(rootPkg)
        assert(
            ownerMaybe == Maybe.Absent,
            s"Expected owner of root package to be Absent but got: $ownerMaybe"
        )
    }

end ClasspathAccessorsTest
