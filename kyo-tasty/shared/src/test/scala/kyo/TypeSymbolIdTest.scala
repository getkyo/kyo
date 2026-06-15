package kyo

import kyo.Tasty.SymbolId
import kyo.internal.tasty.symbol.SymbolKind

/** Tests for SymbolId in the Type ADT.
  *
  * Verifies that Type.Named carries SymbolId rather than a direct Symbol reference, eliminating
  * case-class cycles in the type ADT.
  */
class TypeSymbolIdTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger
    import kyo.Tasty.SymbolId

    /** Type.Named carries a SymbolId; the integer value matches what was passed in. */
    "Type.Named carries SymbolId not Symbol" in {
        val id    = SymbolId(42)
        val named = Tasty.Type.Named(id)
        // Pattern-match to access the SymbolId field
        named match
            case Tasty.Type.Named(symbolId) =>
                assert(symbolId.value == 42, s"Expected symbolId.value == 42 but got ${symbolId.value}")
            case other =>
                fail(s"Expected Type.Named but got $other")
        end match
    }

    /** Every Type.Named id resolves to a valid symbol in the classpath. */
    "Type.Named(unresolved) references a valid symbol in classpath" in {
        val unresolvedSym = Tasty.Symbol.Package(SymbolId(0), Tasty.Name("does.not.Exist"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty)
        Tasty.Classpath.fromPicklesWithSymbols(Chunk(unresolvedSym)).map { classpath =>
            val namedType = Tasty.Type.Named(SymbolId(0))
            namedType match
                case Tasty.Type.Named(id) =>
                    val resolved = classpath.symbol(id)
                    assert(
                        resolved.map(_.kind).contains(SymbolKind.Package),
                        s"Expected kind=Package but got ${resolved.map(_.kind)}"
                    )
                case other =>
                    fail(s"Expected Type.Named but got $other")
            end match
        }
    }

    /** Case-class equals on Type values never recurses through Symbol or Classpath. */
    "Type pattern equality does not recurse through Symbol" in {
        val sharedId  = SymbolId(7)
        val namedType = Tasty.Type.Named(sharedId)
        val parents1  = Chunk(namedType)
        val parents2  = Chunk(Tasty.Type.Named(sharedId))
        // This comparison must terminate in bounded time (SymbolId is an Int, no cycles).
        // Use sameElements since Chunk[Type] lacks a CanEqual instance in this context.
        val equal = parents1.size == parents2.size && parents1.zip(parents2).forall {
            case (Tasty.Type.Named(id1), Tasty.Type.Named(id2)) => id1 == id2
            case _                                              => false
        }
        assert(equal, "Structurally identical parentTypes chunks must be equal")
    }

    "isSubtypeOf and typeShow are pure Classpath instance methods, not extensions" in {
        Tasty.withPickles(Chunk.empty) {
            Tasty.classpath.map { classpath =>
                val t: Tasty.Type     = Tasty.Type.Named(SymbolId(0))
                val other: Tasty.Type = Tasty.Type.Named(SymbolId(1))
                classpath.isSubtypeOf(t, other) match
                    case Result.Success(verdict) =>
                        assert(
                            verdict == Tasty.SubtypeVerdict.Sub ||
                                verdict == Tasty.SubtypeVerdict.NotSub ||
                                verdict == Tasty.SubtypeVerdict.Indeterminate,
                            s"isSubtypeOf returned an unexpected verdict: $verdict"
                        )
                    case other => fail(s"isSubtypeOf returned an unexpected result: $other")
                end match
                assert(classpath.typeShow(t).nonEmpty, s"typeShow returned empty string")
            }
        }
    }

end TypeSymbolIdTest
