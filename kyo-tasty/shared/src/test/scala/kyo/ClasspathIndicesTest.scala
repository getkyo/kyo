package kyo

import kyo.Json
import kyo.Schema
import kyo.Tasty.Classpath
import kyo.Tasty.SymbolId

/** Dict round-trip and semantic equivalence tests for Classpath.Indices.
  *
  * Verifies that Dict[String, SymbolId] fields round-trip through Schema and that
  * bySimpleName aggregation is correct.
  */
class ClasspathIndicesTest extends kyo.test.Test[Any]:

    private def makeCls(id: Int, name: String): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            SymbolId(id),
            Tasty.Name(name),
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

    "dictRoundTripOverSchema" in {
        import AllowUnsafe.embrace.danger
        val cls0 = makeCls(0, "Foo")
        val cls1 = makeCls(1, "pkg")
        val cp = Classpath.make(
            symbols = Chunk(cls0, cls1),
            rootSymbolId = SymbolId(0),
            topLevelClassIds = Chunk(SymbolId(0)),
            packageIds = Chunk(SymbolId(1)),
            fqnIndex = Dict("test.Foo" -> SymbolId(0)),
            packageIndex = Dict("test" -> SymbolId(1)),
            subclassIndex = Dict.empty[SymbolId, Chunk[SymbolId]],
            companionIndex = Dict.empty[SymbolId, SymbolId],
            moduleIndex = Dict.empty[String, Tasty.Java.Module.Descriptor],
            errors = Chunk.empty
        )
        val knownFqn = "test.Foo"
        val encoded  = Json.encode(cp)
        val decoded  = Json.decode[Classpath](encoded)
        decoded match
            case Result.Success(decodedCp) =>
                val orig     = cp.indices.byFqn.get(knownFqn)
                val decoded2 = decodedCp.indices.byFqn.get(knownFqn)
                assert(orig == decoded2, s"byFqn.get($knownFqn) mismatch: orig=$orig decoded=$decoded2")
                succeed
            case Result.Failure(e) =>
                fail(s"Schema round-trip failed: $e")
            case Result.Panic(t) =>
                throw t
        end match
    }

    "dictAggregationsBuilderPreservesIds" in {
        import AllowUnsafe.embrace.danger
        val cls0 = makeCls(0, "Bar")
        val cls1 = makeCls(1, "Bar")
        val cls2 = makeCls(2, "Bar")
        val cp = Classpath.make(
            symbols = Chunk(cls0, cls1, cls2),
            rootSymbolId = SymbolId(0),
            topLevelClassIds = Chunk(SymbolId(0), SymbolId(1), SymbolId(2)),
            packageIds = Chunk.empty[SymbolId],
            fqnIndex = Dict(
                "a.Bar" -> SymbolId(0),
                "b.Bar" -> SymbolId(1),
                "c.Bar" -> SymbolId(2)
            ),
            packageIndex = Dict.empty[String, SymbolId],
            subclassIndex = Dict.empty[SymbolId, Chunk[SymbolId]],
            companionIndex = Dict.empty[SymbolId, SymbolId],
            moduleIndex = Dict.empty[String, Tasty.Java.Module.Descriptor],
            errors = Chunk.empty
        )
        val result = cp.indices.bySimpleName.get("Bar")
        result match
            case Maybe.Present(ids) =>
                assert(ids.length == 3, s"Expected 3 ids but got ${ids.length}: $ids")
                assert(ids.toSet == Set(SymbolId(0), SymbolId(1), SymbolId(2)), s"IDs mismatch: $ids")
                succeed
            case Maybe.Absent =>
                fail("Expected Maybe.Present but got Maybe.Absent for bySimpleName.get('Bar')")
        end match
    }

end ClasspathIndicesTest
