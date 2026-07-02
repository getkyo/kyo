package kyo.internal.tasty.query

import kyo.*
import kyo.Tasty.Classpath
import kyo.Tasty.SymbolId
import kyo.Tasty.Tree
import kyo.internal.tasty.symbol.SymbolBody

/** Internal binding wrapping a pure-data Classpath and the optional decode context.
  *
  * Tasty.bodyTree reads decodeCtx here. The decodeCtx carries the mmap arena,
  * body source handle, and body memo needed to decode TASTy body bytes on demand.
  *
  * private[kyo]: accessible within package kyo and kyo.* sub-packages only.
  */
final private[kyo] case class Binding(classpath: Classpath, decodeCtx: Maybe[DecodeContext])

private[kyo] object Binding:
    val empty: Binding = Binding(Classpath.empty, Maybe.Absent)
end Binding

/** Carries the decode-time context needed to decode TASTy body bytes on demand.
  *
  * Populated by coldLoadBinding and loadPickles; stored in Binding.decodeCtx. Absent when a
  * Binding is created from a pre-existing Classpath (withClasspath(classpath) form) or from the empty
  * fallback.
  *
  * bodyMemo: ConcurrentHashMap keyed by SymbolId; caches decoded Tree results. Each withClasspath
  * invocation creates a fresh DecodeContext so memos are never shared across calls.
  * Unsafe: ConcurrentHashMap used for thread-safe lazy body decoding.
  *
  * bodyStore: ConcurrentHashMap keyed by SymbolId; holds raw SymbolBody blobs populated by
  * ClasspathOrchestrator.finalizeMerge before the Binding is handed to the caller. bodyTree reads
  * from bodyStore to locate the byte slice, then caches the decoded Tree in bodyMemo.
  */
final private[kyo] class DecodeContext(
    val bodyMemo: java.util.concurrent.ConcurrentHashMap[SymbolId, Result[TastyError, Tree]],
    val bodyStore: java.util.concurrent.ConcurrentHashMap[SymbolId, SymbolBody],
    // occurrenceMemo: per-source-file lazy use-site occurrence cache. Keyed by source-file path;
    // each entry is the file's fully-decoded occurrences. Mirrors bodyMemo's at-most-once contract:
    // a completed file result is written once; a cancelled drain writes nothing for the unfinished
    // file.
    // Unsafe: ConcurrentHashMap used for thread-safe lazy occurrence decoding.
    val occurrenceMemo: java.util.concurrent.ConcurrentHashMap[String, Chunk[Tasty.Occurrence]],
    // positionsStore: per-PICKLE raw Positions-section byte slice retained at load so readSpans runs
    // lazily at first query (the section is otherwise dropped after load). KEYED BY pickleId (Int),
    // not by source-file path: two top-level decls of one .scala compile to two .tasty pickles
    // sharing one SOURCEFILE (Concern 2), and each pickle's Positions bytes / sectionOffset differ,
    // so a String(sourceFile) key would last-write-wins-collide. A Span over a per-pickle copy of
    // just the Positions-section bytes (not the whole-file array); retaining it runs no decode at load.
    val positionsStore: java.util.concurrent.ConcurrentHashMap[Int, Span[Byte]],
    // declarationRangeStore: per-symbol declaration full-extent range, keyed by the FINAL SymbolId.
    // Computed at cold load (finalizeMerge aggregates each file's PositionsUnpickler ranges through the
    // same symbol-id remap as sourcePosition), read lazily by Tasty.declarationRange, and never serialized.
    // Empty on a pure-snapshot load (bodyStore empty), so declarationRange returns Absent there.
    val declarationRangeStore: java.util.concurrent.ConcurrentHashMap[SymbolId, Tasty.SourceRange],
    // parentOccurrenceStore: per-pickle extends/with parent-clause use sites, keyed by pickle index
    // (the same key positionsStore uses). Each entry is a Chunk of (absolute-address, parent SymbolId):
    // the address is in the pickle's Positions address space, so occurrencesInFile joins it against
    // that pickle's PositionMap to recover the parent name span. Computed at cold load (finalizeMerge
    // captures the parent type-ref addresses decoded eagerly by AstUnpickler.decodeTemplateParents),
    // merged into the occurrence index by occurrencesInFile, and never serialized. Empty on a
    // pure-snapshot load (bodyStore empty), so extends uses surface only on a cold-loaded classpath.
    val parentOccurrenceStore: java.util.concurrent.ConcurrentHashMap[Int, Chunk[(Int, SymbolId)]]
)

private[kyo] object DecodeContext:
    def fresh(): DecodeContext =
        // Unsafe: allocations at a Binding construction site.
        new DecodeContext(
            new java.util.concurrent.ConcurrentHashMap(),
            new java.util.concurrent.ConcurrentHashMap(),
            new java.util.concurrent.ConcurrentHashMap(),
            new java.util.concurrent.ConcurrentHashMap(),
            new java.util.concurrent.ConcurrentHashMap(),
            new java.util.concurrent.ConcurrentHashMap()
        )

    def fresh(bodyStore: java.util.concurrent.ConcurrentHashMap[SymbolId, SymbolBody]): DecodeContext =
        // Unsafe: allocations at a Binding construction site.
        new DecodeContext(
            new java.util.concurrent.ConcurrentHashMap(),
            bodyStore,
            new java.util.concurrent.ConcurrentHashMap(),
            new java.util.concurrent.ConcurrentHashMap(),
            new java.util.concurrent.ConcurrentHashMap(),
            new java.util.concurrent.ConcurrentHashMap()
        )

    def fresh(
        bodyStore: java.util.concurrent.ConcurrentHashMap[SymbolId, SymbolBody],
        positionsStore: java.util.concurrent.ConcurrentHashMap[Int, Span[Byte]],
        declarationRangeStore: java.util.concurrent.ConcurrentHashMap[SymbolId, Tasty.SourceRange],
        parentOccurrenceStore: java.util.concurrent.ConcurrentHashMap[Int, Chunk[(Int, SymbolId)]]
    ): DecodeContext =
        // Unsafe: allocations at a Binding construction site.
        new DecodeContext(
            new java.util.concurrent.ConcurrentHashMap(),
            bodyStore,
            new java.util.concurrent.ConcurrentHashMap(),
            positionsStore,
            declarationRangeStore,
            parentOccurrenceStore
        )
end DecodeContext
