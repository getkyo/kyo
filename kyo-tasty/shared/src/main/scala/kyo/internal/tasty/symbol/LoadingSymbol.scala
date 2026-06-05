package kyo.internal.tasty.symbol

import kyo.*
import kyo.Tasty

/** ADT for symbols in the loading phase (Pass A and Pass B of the classpath decoder).
  *
  * Loading-phase symbols are mutable: their fields are populated incrementally as the decoder scans AST sections, type sections, and
  * position sections. When all per-file decoding is done, `ClasspathOrchestrator.finalizeMerge` converts each `Materialising` into a final
  * immutable `Tasty.Symbol` via `TypedSymbolFactory.from`. Symbols that were never fully populated (cross-file references that could not be
  * resolved) remain as `Placeholder` instances and are reported as `TastyError.UnresolvedReference`.
  *
  * Visibility is `private[kyo]`: user code never sees loading-phase symbols; only the public `Tasty.Symbol` ADT is exposed.
  *
  * The `id` field on `Materialising` is unique per instance within a single decode session. It is used as the key in `LongMap` structures
  * inside the decoder pipeline, replacing the JVM-only `java.util.IdentityHashMap` that was required when placeholder symbols all had
  * `SymbolId(-1)` and could collide under field-based equality.
  */
sealed private[kyo] trait LoadingSymbol:
    def kind: SymbolKind
    def flags: Tasty.Flags
    def name: Tasty.Name
end LoadingSymbol

private[kyo] object LoadingSymbol:

    /** A cross-file reference placeholder: a symbol whose defining file is absent from the loaded classpath.
      *
      * `Placeholder` instances are NOT placed in the global symbol accumulator. They are used as type sentinels (e.g. `Type.Named(SymbolId(-1))`)
      * within the decode session and discarded after the session completes. If a `Placeholder` somehow survives to `finalizeMerge`, it is
      * reported as `TastyError.UnresolvedReference`.
      */
    final case class Placeholder(kind: SymbolKind, flags: Tasty.Flags, name: Tasty.Name) extends LoadingSymbol

    /** A symbol under construction: all known fields are populated incrementally during Pass A and Pass B.
      *
      * `id` is assigned at construction time by a local counter in `runPass1` (or the equivalent classfile decode path). It is unique within
      * a single decode session and is used as the LongMap key in `AstUnpickler.Pass1Result` and `ClasspathOrchestrator.FileResult`. The
      * global final SymbolId is assigned only by `finalizeMerge` (the index of the symbol in the ordered `allSyms` accumulator).
      *
      * All mutable fields start at their empty/absent defaults and are written by the various Pass A/B decoder stages. `finalizeMerge`
      * reads each field to build a `SymbolDescriptor` for `TypedSymbolFactory.from`.
      */
    final case class Materialising(
        var id: Int,
        kind: SymbolKind,
        flags: Tasty.Flags,
        name: Tasty.Name,
        var ownerId: Int = -1,
        var scaladoc: Maybe[String] = Maybe.Absent,
        var sourcePosition: Maybe[Tasty.Position] = Maybe.Absent,
        var declaredType: Maybe[Tasty.Type] = Maybe.Absent,
        var paramListIds: Chunk[Chunk[Int]] = Chunk.empty,
        var typeParamIds: Chunk[Int] = Chunk.empty,
        var parentTypes: Chunk[Tasty.Type] = Chunk.empty,
        var declarationIds: Chunk[Int] = Chunk.empty,
        var permittedSubclassIds: Maybe[Chunk[Int]] = Maybe.Absent,
        var annotations: Chunk[Tasty.Annotation] = Chunk.empty,
        var javaAnnotations: Chunk[Tasty.Java.Annotation] = Chunk.empty,
        var javaMetadata: Maybe[Tasty.Java.Metadata] = Maybe.Absent,
        var bounds: Maybe[Tasty.TypeBounds] = Maybe.Absent,
        var variance: Maybe[Tasty.Variance] = Maybe.Absent,
        var defaultArgId: Maybe[Int] = Maybe.Absent,
        var memberIds: Chunk[Int] = Chunk.empty
    ) extends LoadingSymbol

end LoadingSymbol
