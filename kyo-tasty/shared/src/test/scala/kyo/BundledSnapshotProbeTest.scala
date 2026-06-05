package kyo

import kyo.internal.tasty.query.BundledSnapshotProbe

/** Phase 13 cross-platform leaves for BundledSnapshotProbe.
  *
  * Leaf 8: remap-at-merge preserves identity (symbol id + offset == merged id).
  * Leaf 9: cross-classpath isolation -- two partials with overlapping ids produce disjoint ids after merge.
  *
  * JVM-only leaves (1, 2, 3, 11) that require real zip construction (java.util.zip, java.io.File)
  * live in BundledSnapshotProbeJvmTest.scala (jvm/src/test).
  *
  * Pins: INV-004, INV-005.
  */
class BundledSnapshotProbeTest extends Test:

    // Leaf 8: remap-at-merge preserves identity
    // Given: bundled partial with SymbolId space {0, 1, 2}; merged with offset 100.
    // When: mergePartialInto(existing100, partial3) and lookup by name.
    // Then: symbol with local id=1 in partial has id=101 in merged.
    // Pins: INV-005 remap-at-merge
    "Leaf 8: remap-at-merge shifts partial ids by existing classpath size" in run {
        val existingSyms: Chunk[Tasty.Symbol] = Chunk.from(
            (0 until 100).map: i =>
                Tasty.Symbol.Unresolved(
                    id = Tasty.SymbolId(i),
                    name = Tasty.Name(s"ex$i"),
                    ownerId = Tasty.SymbolId(0),
                    flags = Tasty.Flags.empty
                )
        )
        val existing = Tasty.Classpath(
            symbols = existingSyms,
            indices = Tasty.Classpath.Indices(
                byFqn = Dict.from(existingSyms.toSeq.zipWithIndex.map((s, i) => s"ex$i" -> Tasty.SymbolId(i)).toMap),
                bySimpleName = Dict.empty,
                packageIndex = Dict.empty,
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                modulesIndex = Dict.empty,
                topLevelClassIds = Chunk.empty,
                packageIds = Chunk.empty,
                unresolvedFqnByNegId = Dict.empty,
                diagnostics = Chunk.empty
            ),
            errors = Chunk.empty,
            modules = Chunk.empty,
            rootSymbolId = Tasty.SymbolId(0)
        )
        val partialSyms: Chunk[Tasty.Symbol] = Chunk.from(
            (0 until 3).map: i =>
                Tasty.Symbol.Unresolved(
                    id = Tasty.SymbolId(i),
                    name = Tasty.Name(s"partial$i"),
                    ownerId = Tasty.SymbolId(0),
                    flags = Tasty.Flags.empty
                )
        )
        val partial = Tasty.Classpath(
            symbols = partialSyms,
            indices = Tasty.Classpath.Indices(
                byFqn = Dict.from(partialSyms.toSeq.zipWithIndex.map((s, i) => s"partial$i" -> Tasty.SymbolId(i)).toMap),
                bySimpleName = Dict.empty,
                packageIndex = Dict.empty,
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                modulesIndex = Dict.empty,
                topLevelClassIds = Chunk.empty,
                packageIds = Chunk.empty,
                unresolvedFqnByNegId = Dict.empty,
                diagnostics = Chunk.empty
            ),
            errors = Chunk.empty,
            modules = Chunk.empty,
            rootSymbolId = Tasty.SymbolId(0)
        )
        val merged = BundledSnapshotProbe.mergePartialInto(existing, partial)
        val sym101 = merged.symbols.find(_.id == Tasty.SymbolId(101))
        assert(sym101.isDefined, "symbol with id=101 must exist after merge at offset 100")
        assert(
            sym101.get.name.asString == "partial1",
            s"symbol at id=101 must have name 'partial1', got '${sym101.get.name.asString}'"
        )
        assert(
            merged.indices.byFqn.get("partial1").contains(Tasty.SymbolId(101)),
            "byFqn entry for 'partial1' must point to id=101"
        )
    }

    // Leaf 9: cross-classpath isolation
    // Given: two bundled partials with overlapping ids {0, 1, 2}.
    // When: merged sequentially.
    // Then: post-merge ids for A and B symbols are disjoint.
    // Pins: INV-005 isolation
    "Leaf 9: two partials with overlapping ids produce disjoint ids after merge" in run {
        def makePartial(prefix: String, n: Int): Tasty.Classpath =
            val syms: Chunk[Tasty.Symbol] = Chunk.from(
                (0 until n).map: i =>
                    Tasty.Symbol.Unresolved(
                        id = Tasty.SymbolId(i),
                        name = Tasty.Name(s"$prefix$i"),
                        ownerId = Tasty.SymbolId(0),
                        flags = Tasty.Flags.empty
                    )
            )
            Tasty.Classpath(
                symbols = syms,
                indices = Tasty.Classpath.Indices(
                    byFqn = Dict.from(syms.toSeq.zipWithIndex.map((s, i) => s"$prefix$i" -> Tasty.SymbolId(i)).toMap),
                    bySimpleName = Dict.empty,
                    packageIndex = Dict.empty,
                    subclassIndex = Dict.empty,
                    companionIndex = Dict.empty,
                    modulesIndex = Dict.empty,
                    topLevelClassIds = Chunk.empty,
                    packageIds = Chunk.empty,
                    unresolvedFqnByNegId = Dict.empty,
                    diagnostics = Chunk.empty
                ),
                errors = Chunk.empty,
                modules = Chunk.empty,
                rootSymbolId = Tasty.SymbolId(0)
            )
        end makePartial
        val partialA = makePartial("a", 3)
        val partialB = makePartial("b", 3)
        val afterA   = BundledSnapshotProbe.mergePartialInto(Tasty.Classpath.empty, partialA)
        val afterAB  = BundledSnapshotProbe.mergePartialInto(afterA, partialB)
        assert(afterAB.symbols.size == 6, s"expected 6 symbols, got ${afterAB.symbols.size}")
        val ids = afterAB.symbols.map(_.id.value).toSet
        assert(ids == Set(0, 1, 2, 3, 4, 5), s"expected ids 0..5, got $ids")
        val symA0 = afterAB.symbols.find(_.id.value == 0)
        val symB0 = afterAB.symbols.find(_.id.value == 3)
        assert(symA0.exists(_.name.asString == "a0"), s"expected a0 at id=0, got ${symA0.map(_.name.asString)}")
        assert(symB0.exists(_.name.asString == "b0"), s"expected b0 at id=3, got ${symB0.map(_.name.asString)}")
    }

end BundledSnapshotProbeTest
