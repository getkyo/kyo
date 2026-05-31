package kyo.internal.tasty.symbol

import kyo.Chunk
import kyo.Maybe
import kyo.Tasty
import kyo.Tasty.SymbolBody

/** Mutable scratch descriptor populated during Pass A and Pass B of classpath loading.
  *
  * Converted to an immutable `Tasty.Symbol` case class in Pass C `materializeSymbols`. Not user-visible.
  *
  * The `var` fields are intentional: this is a single-fiber scratch object whose mutable writes are confined to the decoder fiber. After
  * `materializeSymbols` completes and returns the immutable `IndexedSeq[Symbol]`, all `SymbolDescriptor` instances are discarded.
  */
final private[kyo] class SymbolDescriptor(
    var id: Int,
    var kind: Tasty.SymbolKind,
    var flags: Tasty.Flags,
    var name: Tasty.Name,
    var ownerId: Int,
    var declaredType: Maybe[Tasty.Type],
    var scaladoc: Maybe[String],
    var sourcePosition: Maybe[Tasty.Position],
    var javaMetadata: Maybe[Tasty.JavaMetadata],
    var parentTypes: Chunk[Tasty.Type],
    var typeParamIds: Chunk[Int],
    var declarationIds: Chunk[Int],
    var permittedSubclassIds: Maybe[Chunk[Int]],
    var body: Maybe[SymbolBody]
)

/** Internal factory for partial `Tasty.Symbol` instances used during Pass 1 (pre-Pass C).
  *
  * Creates Symbols with only the fields knowable during AST/classfile scanning: kind, flags, name. All relational fields (parentTypes,
  * declarationIds, etc.) are left at empty defaults. Pass C replaces these partial symbols with fully-populated ones via
  * `materializeSymbols`.
  *
  * The `plan: phase-02 bridge` note below indicates this object is a transitional helper whose responsibilities migrate to
  * ClasspathOrchestrator in Phase 07 once the pipeline is fully converted to SymbolDescriptor throughout.
  */
private[kyo] object Symbol:
    // plan: phase-02 bridge; Pass 1 still creates Tasty.Symbol for addrMap/comments/positions wiring;
    // Phase 07 converts the full pipeline to SymbolDescriptor so this object is deleted.

    /** Create a new partial `Tasty.Symbol` with the given fields.
      *
      * The returned symbol has id = SymbolId(-1) and all relational fields empty/absent. Only kind, flags, and name are meaningful on the
      * returned object. Pass C will create fresh fully-populated Symbols from SymbolDescriptors and replace the classpath contents.
      */
    def makeSymbol(
        kind: Tasty.SymbolKind,
        flags: Tasty.Flags,
        name: Tasty.Name
    ): Tasty.Symbol =
        Tasty.Symbol.make(kind, flags, name)

end Symbol
