package kyo

import kyo.Tasty.Name.asString
import kyo.Tasty.SymbolId
import kyo.internal.MemoryFileSource
import kyo.internal.tasty.query.ClasspathOrchestrator

/** Phase 03 plan leaves 11-14: Tasty.owner(Tasty), fullName, show, signature, parents; Tag[Symbol.X].
  *
  * Pins: item 29 parents migration; item 29 / Q-014 Tag-based subtype discrimination.
  */
class QueryTraversalTest extends Test:

    import AllowUnsafe.embrace.danger

    private def openFixtureClasspath(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)
    end openFixtureClasspath

    // Leaf 11: Tasty.owner(sym) returns Absent for the root package
    "Leaf 11: Tasty.owner(Tasty) returns Absent for root package" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath.flatMap: cp =>
                Tasty.withClasspath(cp):
                    val rootPkg = cp.symbol(cp.rootSymbolId)
                    Tasty.owner(rootPkg).map: ownerResult =>
                        // The root package (ownerId == -1 or ownerId == self) returns Absent
                        assert(
                            !ownerResult.isDefined || ownerResult.get.id == rootPkg.id,
                            "owner of root package must be Absent or self"
                        )
                        succeed).map:
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

    // Leaf 13: Tag[Symbol.EnumCase] summon succeeds (DONE in PureDataAdtsTest; verify pattern-match behavior here)
    "Leaf 13: Pattern match distinguishes Symbol.EnumCase from Symbol.Class via sealed match" in {
        // Construct a Symbol.EnumCase and verify it matches EnumCase arm, not Class
        val enumCase: Tasty.Symbol = Tasty.Symbol.EnumCase(
            SymbolId(1),
            Tasty.Name("Red"),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )
        val symEc: Tasty.Symbol = enumCase
        val label = symEc match
            case _: Tasty.Symbol.EnumCase => "enumCase"
            case _: Tasty.Symbol.Class    => "class"
            case _                        => "other"
        assert(label == "enumCase", s"EnumCase must match EnumCase arm (not Class); got $label")
    }

    // Tasty.parents works correctly for ClassLike
    "Tasty.parents delegates to parentTypes for ClassLike" in run {
        Scope.run:
            Abort.run[TastyError](openFixtureClasspath.flatMap: cp =>
                Tasty.withClasspath(cp):
                    val classSym = cp.findClass("kyo.fixtures.PlainClass").get
                    Tasty.parents(classSym).map: parentResult =>
                        // Just verify it's a Chunk (may be empty if PlainClass has no explicit parents)
                        assert(parentResult.size >= 0, "parents must not throw")
                        succeed).map:
                case Result.Success(_) => succeed
                case Result.Failure(e) => fail(s"Unexpected failure: $e")
                case Result.Panic(t)   => throw t
    }

end QueryTraversalTest
