package kyo

import kyo.Tasty.ShowFormat
import kyo.Tasty.SymbolId

/** Cat 21: Sync.defer removal verification.
  *
  * Verifies that the three Sync.defer wrappers removed from Tasty.scala (:3345, :3520, :3524)
  * do not affect observable behavior. The public effect rows are unchanged (still `< Sync`);
  * the Sync.defer was removed because the expressions were already pure.
  *
  * Leaf 5 (showRendersSimpleNameWithoutSyncDefer): show with ShowFormat.Simple still works.
  * Leaf 6 (typeShowReturnsAbsentWithoutSyncDefer): bodyTree for a symbol with no body returns Absent.
  *
  * Pins: Cat 21; L; PRESERVE-L.
  */
class TastySyncDeferRemovalTest extends Test:

    private val cls = Tasty.Symbol.Class(
        SymbolId(0),
        Tasty.Name("Foo"),
        Tasty.Flags.empty,
        SymbolId(-1),
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

    "showRendersSimpleNameWithoutSyncDefer" in run {
        // Given: a Classpath with a Symbol.Class named "Foo".
        // When: Tasty.show(cls, ShowFormat.Simple).eval is called.
        // Then: the result is "Foo" (the simple name).
        val cp = Tasty.Classpath.fromPicklesWithSymbols(Chunk(cls))
        cp.flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.show(cls, ShowFormat.Simple).map: result =>
                    assert(result == "Foo", s"Expected 'Foo' but got '$result'")
                    succeed
    }

    "typeShowReturnsAbsentWithoutSyncDefer" in run {
        // Given: a Classpath containing cls which has no body (Maybe.Absent).
        // When: Tasty.bodyTree(cls).eval is called.
        // Then: the result is Maybe.Absent (no body available) without throwing or hanging.
        val cp = Tasty.Classpath.fromPicklesWithSymbols(Chunk(cls))
        cp.flatMap: cp =>
            Tasty.withClasspath(cp):
                Abort.run[TastyError](Tasty.bodyTree(cls)).map:
                    case Result.Success(Maybe.Absent) => succeed
                    case Result.Success(Maybe.Present(_)) =>
                        fail("Expected Maybe.Absent but got Maybe.Present")
                    case Result.Failure(e) =>
                        fail(s"Unexpected TastyError: $e")
                    case Result.Panic(t) =>
                        throw t
    }

end TastySyncDeferRemovalTest
