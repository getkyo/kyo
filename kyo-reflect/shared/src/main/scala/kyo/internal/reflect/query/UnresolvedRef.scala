package kyo.internal.reflect.query

import kyo.Reflect
import kyo.internal.reflect.symbol.SingleAssign

/** Decoder-internal placeholder for a cross-file type reference whose target FQN is not in the local Addr->Symbol map.
  *
  * NOT part of Reflect.Type. Lives only in the per-file decode context and in the Pass1Result.placeholders accumulator passed to Phase C.
  *
  * Phase C resolves each UnresolvedRef by looking up fqn in the merged symbol graph. If found: replaceSlot.set(Named(resolvedSym)) If not
  * found: replaceSlot.set(Named(unresolvedSym)) where unresolvedSym has kind = SymbolKind.Unresolved and name = fqn.
  *
  * Sites that hold a reference to an UnresolvedRef call replaceSlot.get() after Phase C to get the final resolved type. Callers that
  * eagerly need the type use a mutable indirection wrapper.
  */
final case class UnresolvedRef(fqn: String, replaceSlot: SingleAssign[Reflect.Type])
