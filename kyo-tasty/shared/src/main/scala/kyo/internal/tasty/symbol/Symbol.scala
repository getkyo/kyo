package kyo.internal.tasty.symbol

import kyo.Chunk
import kyo.Maybe
import kyo.Tasty
import kyo.Tasty.SymbolBody

/** Internal factory for partial `Tasty.Symbol` instances used during Pass A/B (pre-Pass C).
  *
  * Creates Symbols with only the fields knowable during AST/classfile scanning: kind, flags, name. All relational fields (parentTypes,
  * declarationIds, etc.) are left at empty defaults. Pass C replaces these partial symbols with fully-populated ones via
  * `materializeSymbols`.
  *
  * Retained as a factory shim for AstUnpickler, TypeUnpickler, TreeUnpickler, ClasspathOrchestrator, ClassfileUnpickler, and
  * JavaAnnotationUnpickler. Deletion requires migrating all Pass A/B callers to SymbolDescriptor-based construction, which is out of scope
  * for this campaign.
  */
private[kyo] object Symbol:

    /** Create a new partial `Tasty.Symbol` with the given fields.
      *
      * The returned symbol has id = SymbolId(-1) and all relational fields empty/absent. Only kind, flags, and name are meaningful on the
      * returned object. Pass C will create fresh fully-populated Symbols from SymbolDescriptors and replace the classpath contents.
      */
    def makeSymbol(
        kind: SymbolKind,
        flags: Tasty.Flags,
        name: Tasty.Name
    ): Tasty.Symbol =
        Tasty.Symbol.makePlaceholder(kind, flags, name)

end Symbol
