package kyo.internal.tasty.symbol

import kyo.Chunk
import kyo.Maybe
import kyo.Tasty

/** Internal factory for loading-phase `LoadingSymbol.Materialising` instances (Pass A/B).
  *
  * Creates symbols with only the fields knowable during AST/classfile scanning: kind, flags, name. All relational fields (parentTypes,
  * declarationIds, etc.) are left at empty defaults. Pass C converts `LoadingSymbol.Materialising` instances to final immutable
  * `Tasty.Symbol` instances via `TypedSymbolFactory.from`.
  *
  * The `idCounter` is a per-call-site thread-local counter managed by the caller. Each `makeSymbol` call assigns the next counter value as
  * the unique id for the returned `Materialising`. The caller must ensure no two instances within the same decode session share an id.
  */
private[kyo] object Symbol:

    /** Create a new `LoadingSymbol.Materialising` with the given fields and a unique id.
      *
      * `id` must be unique within the current decode session. It is the caller's responsibility to supply a monotonically-increasing value.
      * The returned symbol has all relational fields empty/absent. Pass C fills in the relational fields and converts to a final
      * `Tasty.Symbol`.
      */
    def makeSymbol(
        id: Int,
        kind: SymbolKind,
        flags: Tasty.Flags,
        name: Tasty.Name
    ): LoadingSymbol.Materialising =
        LoadingSymbol.Materialising(id = id, kind = kind, flags = flags, name = name)

end Symbol
