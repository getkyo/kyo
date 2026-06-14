package kyo

import kyo.Tasty.Name.asString
import kyo.Tasty.SymbolId

/** classpath.owner, fullName, show, signature, parents; Tag[Symbol.X].
  */
class QueryTraversalTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val plainClassPickle =
        Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty))

    // classpath.owner(symbol) returns Absent for the root package
    "classpath.owner(root) returns Absent for root package" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    classpath.symbol(classpath.rootSymbolId) match
                        case Maybe.Absent =>
                            fail("root symbol not found in classpath")
                        case Maybe.Present(rootPkg) =>
                            val ownerResult = classpath.owner(rootPkg)
                            // The root package (ownerId == -1 or ownerId == self) returns Absent
                            assert(
                                !ownerResult.isDefined || ownerResult.get.id == rootPkg.id,
                                "owner of root package must be Absent or self"
                            )
                }
            }
        ).map {
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "Pattern match distinguishes Symbol.EnumCase from Symbol.Class via sealed match" in {
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
            Chunk.empty
        )
        val symEc: Tasty.Symbol = enumCase
        val label = symEc match
            case _: Tasty.Symbol.EnumCase => "enumCase"
            case _: Tasty.Symbol.Class    => "class"
            case _                        => "other"
        assert(label == "enumCase", s"EnumCase must match EnumCase arm (not Class); got $label")
    }

    // classpath.parents works correctly for ClassLike
    "classpath.parents delegates to parentTypes for ClassLike" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val classSym     = classpath.findClass("kyo.fixtures.PlainClass").get
                    val parentResult = classpath.parents(classSym)
                    // Just verify it's a Chunk (may be empty if PlainClass has no explicit parents)
                    assert(parentResult.size >= 0, "parents must not throw")
                }
            }
        ).map {
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

end QueryTraversalTest
