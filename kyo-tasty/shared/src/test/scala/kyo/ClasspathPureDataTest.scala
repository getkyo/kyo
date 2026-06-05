package kyo

import kyo.Json
import kyo.Schema
import kyo.Tasty.Classpath
import kyo.Tasty.SymbolId
import kyo.internal.tasty.query.Binding

/** Phase 04 plan leaves 1-6.
  *
  * Leaf 1: Classpath.empty round-trips through Schema. Leaf 2: non-empty Classpath round-trips.
  * Leaf 3: Classpath.Indices derives Schema. Leaf 4: Classpath has no decodeCtx/canonical/mmapArena
  * field (compileErrors). Leaf 5: Binding.empty wraps Classpath.empty. Leaf 6: cross-platform
  * (leaves 1-5 are in shared/src/test, which runs on JVM, JS, and Native).
  *
  * Pins: item 30 pure-data Schema derivation; INV-008.
  */
class ClasspathPureDataTest extends Test:

    // Compile-time Schema and CanEqual summon checks for Classpath and Indices.
    val _schemaClasspath: Schema[Classpath]             = summon[Schema[Classpath]]
    val _schemaIndices: Schema[Classpath.Indices]       = summon[Schema[Classpath.Indices]]
    val _canEqClasspath: CanEqual[Classpath, Classpath] = summon[CanEqual[Classpath, Classpath]]
    val _canEqIndices: CanEqual[Classpath.Indices, Classpath.Indices] =
        summon[CanEqual[Classpath.Indices, Classpath.Indices]]

    // Leaf 1: Classpath.empty round-trips through Schema (JSON codec)
    "Classpath.empty round-trips through Schema" in {
        val encoded = Json.encode(Classpath.empty)
        val decoded = Json.decode[Classpath](encoded)
        decoded match
            case Result.Success(cp) =>
                assert(cp == Classpath.empty, s"Decoded Classpath != Classpath.empty: $cp")
                succeed
            case Result.Failure(e) =>
                fail(s"Schema round-trip failed: $e")
            case Result.Panic(t) =>
                throw t
        end match
    }

    // Leaf 2: non-empty Classpath round-trips through Schema
    "non-empty Classpath round-trips through Schema" in {
        import AllowUnsafe.embrace.danger
        val sym0 = Tasty.Symbol.Class(
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
            Chunk.empty,
            Maybe.Absent
        )
        val sym1 = Tasty.Symbol.Method(
            SymbolId(1),
            Tasty.Name("bar"),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Maybe.Absent
        )
        val indices = Classpath.Indices.empty.copy(
            byFqn = Dict("test.Foo" -> SymbolId(0))
        )
        val cp = Classpath(
            symbols = Chunk(sym0, sym1),
            indices = indices,
            errors = Chunk.empty,
            modules = Chunk.empty,
            rootSymbolId = SymbolId(0)
        )
        val encoded = Json.encode(cp)
        val decoded = Json.decode[Classpath](encoded)
        decoded match
            case Result.Success(decoded) =>
                assert(decoded.symbols.length == 2, s"Expected 2 symbols, got ${decoded.symbols.length}")
                assert(decoded.rootSymbolId == SymbolId(0), s"rootSymbolId mismatch")
                assert(decoded.indices.byFqn.contains("test.Foo"), s"byFqn missing test.Foo")
                succeed
            case Result.Failure(e) =>
                fail(s"Schema round-trip failed: $e")
            case Result.Panic(t) =>
                throw t
        end match
    }

    // Leaf 3: Classpath.Indices derives Schema; round-trips Indices.empty
    "Classpath.Indices.empty round-trips through Schema" in {
        val encoded = Json.encode(Classpath.Indices.empty)
        val decoded = Json.decode[Classpath.Indices](encoded)
        decoded match
            case Result.Success(indices) =>
                assert(indices == Classpath.Indices.empty, s"Decoded Indices != Indices.empty: $indices")
                succeed
            case Result.Failure(e) =>
                fail(s"Schema round-trip of Indices.empty failed: $e")
            case Result.Panic(t) =>
                throw t
        end match
    }

    // Leaf 4: Classpath has no decodeCtx / canonical / mmapArena field (compile-time check)
    "Classpath has no decodeCtx canonical mmapArena field" in {
        val e1 = compiletime.testing.typeCheckErrors("(null: kyo.Tasty.Classpath).decodeCtx")
        val e2 = compiletime.testing.typeCheckErrors("(null: kyo.Tasty.Classpath).canonical")
        val e3 = compiletime.testing.typeCheckErrors("(null: kyo.Tasty.Classpath).mmapArena")
        assert(e1.nonEmpty, "Expected decodeCtx to not be a member of Classpath")
        assert(e2.nonEmpty, "Expected canonical to not be a member of Classpath")
        assert(e3.nonEmpty, "Expected mmapArena to not be a member of Classpath")
        succeed
    }

    // Leaf 5: Binding is accessible from within package kyo (private[kyo]) and wraps Classpath.empty.
    // Per C5 resolution (b): from within package kyo, private[kyo] IS accessible. We verify the
    // behavioral invariant: Binding.empty returns an empty Classpath and Absent decodeCtx.
    "Binding.empty wraps Classpath.empty and has Absent decodeCtx" in {
        val b = Binding.empty
        assert(b.cp == Classpath.empty, s"Binding.empty.cp should equal Classpath.empty")
        assert(b.decodeCtx == Maybe.Absent, s"Binding.empty.decodeCtx should be Absent")
        succeed
    }

    // Leaf 6: cross-platform JVM JS Native -- the above leaves cover all platforms by
    // being placed in shared/src/test/scala. No per-platform assertions needed.

end ClasspathPureDataTest
