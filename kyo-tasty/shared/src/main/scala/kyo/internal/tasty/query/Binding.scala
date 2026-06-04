package kyo.internal.tasty.query

import kyo.*
import kyo.Tasty.Classpath
import kyo.Tasty.SymbolId
import kyo.Tasty.Tree

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
  * Populated by coldLoadBinding (Phase 05) and stored in Binding.decodeCtx. Absent
  * when a Binding is created from a pre-existing Classpath (withClasspath(cp) form)
  * or from the empty fallback.
  *
  * The bodyMemo field is a ConcurrentHashMap keyed by SymbolId. Each withClasspath
  * invocation creates a fresh DecodeContext so body memos are never shared across calls.
  * Unsafe: ConcurrentHashMap used for thread-safe lazy body decoding (INV-009 site-3).
  */
final private[kyo] class DecodeContext(
    val bodyMemo: java.util.concurrent.ConcurrentHashMap[SymbolId, Result[TastyError, Tree]]
)

private[kyo] object DecodeContext:
    def fresh(): DecodeContext =
        // Unsafe: ConcurrentHashMap allocation at a Binding construction site (INV-009 site-1 / site-3).
        new DecodeContext(new java.util.concurrent.ConcurrentHashMap())
end DecodeContext
