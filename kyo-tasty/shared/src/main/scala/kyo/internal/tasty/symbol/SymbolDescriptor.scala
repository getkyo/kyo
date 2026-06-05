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
    var kind: SymbolKind,
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
    var body: Maybe[SymbolBody],
    var annotations: Chunk[Tasty.Annotation] = Chunk.empty,
    var javaAnnotations: Chunk[Tasty.JavaAnnotation] = Chunk.empty,
    var paramListIds: Chunk[Chunk[Int]] = Chunk.empty,
    var bounds: Maybe[Tasty.TypeBounds] = Maybe.Absent,
    var defaultArgId: Maybe[Int] = Maybe.Absent
)
