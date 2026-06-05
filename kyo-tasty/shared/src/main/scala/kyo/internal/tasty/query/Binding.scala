package kyo.internal.tasty.query

import kyo.*
import kyo.Tasty.Classpath
import kyo.Tasty.SymbolId
import kyo.Tasty.Tree
import kyo.internal.tasty.symbol.SymbolBody

/** Internal binding wrapping a pure-data Classpath and the optional decode context.
  *
  * INV-009 site-3 (bodyTree) reads decodeCtx here. The decodeCtx carries the mmap arena,
  * body source handle, and body memo needed to decode TASTy body bytes on demand.
  *
  * private[kyo]: accessible within package kyo and kyo.* sub-packages only.
  */
final private[kyo] case class Binding(cp: Classpath, decodeCtx: Maybe[DecodeContext])

private[kyo] object Binding:
    val empty: Binding = Binding(Classpath.empty, Maybe.Absent)
end Binding

/** Carries the decode-time context needed to decode TASTy body bytes on demand.
  *
  * Populated by coldLoadBinding and loadPickles; stored in Binding.decodeCtx. Absent when a
  * Binding is created from a pre-existing Classpath (withClasspath(cp) form) or from the empty
  * fallback.
  *
  * bodyMemo: ConcurrentHashMap keyed by SymbolId; caches decoded Tree results. Each withClasspath
  * invocation creates a fresh DecodeContext so memos are never shared across calls.
  * Unsafe: ConcurrentHashMap used for thread-safe lazy body decoding (INV-009 site-3).
  *
  * bodyStore: ConcurrentHashMap keyed by SymbolId; holds raw SymbolBody blobs populated by
  * ClasspathOrchestrator.finalizeMerge before the Binding is handed to the caller. bodyTree reads
  * from bodyStore to locate the byte slice, then caches the decoded Tree in bodyMemo.
  *
  * subtypingErrors accumulates TastyError.UnhandledSubtypingCase diagnostics produced during
  * isSubtypeOf calls within a live withClasspath scope. Not synchronised: isSubtypeOf runs inside
  * a single Sync.defer block, so concurrency is not a concern.
  */
final private[kyo] class DecodeContext(
    val bodyMemo: java.util.concurrent.ConcurrentHashMap[SymbolId, Result[TastyError, Tree]],
    val bodyStore: java.util.concurrent.ConcurrentHashMap[SymbolId, SymbolBody],
    val subtypingErrors: scala.collection.mutable.ArrayBuffer[TastyError]
)

private[kyo] object DecodeContext:
    def fresh(): DecodeContext =
        // Unsafe: allocations at a Binding construction site (INV-009 site-1 / site-3).
        new DecodeContext(
            new java.util.concurrent.ConcurrentHashMap(),
            new java.util.concurrent.ConcurrentHashMap(),
            new scala.collection.mutable.ArrayBuffer()
        )

    def fresh(bodyStore: java.util.concurrent.ConcurrentHashMap[SymbolId, SymbolBody]): DecodeContext =
        // Unsafe: allocations at a Binding construction site (INV-009 site-1 / site-3).
        new DecodeContext(
            new java.util.concurrent.ConcurrentHashMap(),
            bodyStore,
            new scala.collection.mutable.ArrayBuffer()
        )
end DecodeContext
