package kyo.internal.tasty.symbol

import kyo.*
import kyo.Tasty
import kyo.Tasty.SymbolId
// SymbolKind is in the same package (kyo.internal.tasty.symbol); no import needed

/** Canonical factory that converts a fully-populated SymbolDescriptor into the matching typed Symbol subtype.
  *
  * Called by ClasspathOrchestrator.materializeSymbols (Pass C), SnapshotReader (all 4 construction sites), ClassfileUnpickler (2 sites),
  * and Scala2PickleReader (1 site). Replaces the Phase 01 bridge chain fromDescriptor -> fromFlat -> _SymbolFlat.
  *
  * All 14 SymbolKind cases are handled. The `d.id` and `d.ownerId` fields are int-typed in SymbolDescriptor; they are wrapped as SymbolId
  * values here. Collection fields (`typeParamIds`, `declarationIds`, `paramListIds`) undergo a similar int-to-SymbolId lift.
  */
private[kyo] object TypedSymbolFactory:

    def from(d: SymbolDescriptor): Tasty.Symbol =
        val sid      = SymbolId(d.id)
        val ownerSid = SymbolId(d.ownerId)
        d.kind match
            case SymbolKind.Class =>
                Tasty.Symbol.Class(
                    id = sid,
                    name = d.name,
                    flags = d.flags,
                    ownerId = ownerSid,
                    scaladoc = d.scaladoc,
                    sourcePosition = d.sourcePosition,
                    javaMetadata = d.javaMetadata,
                    parentTypes = d.parentTypes,
                    typeParamIds = Chunk.from(d.typeParamIds.toSeq.map(SymbolId(_))),
                    declarationIds = Chunk.from(d.declarationIds.toSeq.map(SymbolId(_))),
                    permittedSubclassIds = d.permittedSubclassIds.map(_.map(SymbolId(_))),
                    annotations = d.annotations,
                    javaAnnotations = d.javaAnnotations,
                    body = d.body
                )
            case SymbolKind.Trait =>
                Tasty.Symbol.Trait(
                    id = sid,
                    name = d.name,
                    flags = d.flags,
                    ownerId = ownerSid,
                    scaladoc = d.scaladoc,
                    sourcePosition = d.sourcePosition,
                    javaMetadata = d.javaMetadata,
                    parentTypes = d.parentTypes,
                    typeParamIds = Chunk.from(d.typeParamIds.toSeq.map(SymbolId(_))),
                    declarationIds = Chunk.from(d.declarationIds.toSeq.map(SymbolId(_))),
                    permittedSubclassIds = d.permittedSubclassIds.map(_.map(SymbolId(_))),
                    annotations = d.annotations,
                    javaAnnotations = d.javaAnnotations,
                    body = d.body
                )
            case SymbolKind.Object =>
                Tasty.Symbol.Object(
                    id = sid,
                    name = d.name,
                    flags = d.flags,
                    ownerId = ownerSid,
                    scaladoc = d.scaladoc,
                    sourcePosition = d.sourcePosition,
                    javaMetadata = d.javaMetadata,
                    parentTypes = d.parentTypes,
                    typeParamIds = Chunk.from(d.typeParamIds.toSeq.map(SymbolId(_))),
                    declarationIds = Chunk.from(d.declarationIds.toSeq.map(SymbolId(_))),
                    annotations = d.annotations,
                    javaAnnotations = d.javaAnnotations,
                    body = d.body
                )
            case SymbolKind.Method =>
                Tasty.Symbol.Method(
                    id = sid,
                    name = d.name,
                    flags = d.flags,
                    ownerId = ownerSid,
                    scaladoc = d.scaladoc,
                    sourcePosition = d.sourcePosition,
                    declaredType = d.declaredType,
                    paramListIds = d.paramListIds.map(inner => inner.map(SymbolId(_))),
                    typeParamIds = Chunk.from(d.typeParamIds.toSeq.map(SymbolId(_))),
                    annotations = d.annotations,
                    body = d.body,
                    javaMetadata = d.javaMetadata
                )
            case SymbolKind.Val =>
                Tasty.Symbol.Val(
                    id = sid,
                    name = d.name,
                    flags = d.flags,
                    ownerId = ownerSid,
                    scaladoc = d.scaladoc,
                    sourcePosition = d.sourcePosition,
                    declaredType = d.declaredType,
                    annotations = d.annotations,
                    body = d.body
                )
            case SymbolKind.Var =>
                Tasty.Symbol.Var(
                    id = sid,
                    name = d.name,
                    flags = d.flags,
                    ownerId = ownerSid,
                    scaladoc = d.scaladoc,
                    sourcePosition = d.sourcePosition,
                    declaredType = d.declaredType,
                    annotations = d.annotations,
                    body = d.body
                )
            case SymbolKind.Field =>
                Tasty.Symbol.Field(
                    id = sid,
                    name = d.name,
                    flags = d.flags,
                    ownerId = ownerSid,
                    scaladoc = d.scaladoc,
                    sourcePosition = d.sourcePosition,
                    declaredType = d.declaredType,
                    javaMetadata = d.javaMetadata,
                    javaAnnotations = d.javaAnnotations
                )
            case SymbolKind.TypeAlias =>
                Tasty.Symbol.TypeAlias(
                    id = sid,
                    name = d.name,
                    flags = d.flags,
                    ownerId = ownerSid,
                    scaladoc = d.scaladoc,
                    sourcePosition = d.sourcePosition,
                    body = d.declaredType.getOrElse(Tasty.Type.Unknown),
                    typeParamIds = Chunk.from(d.typeParamIds.toSeq.map(SymbolId(_))),
                    annotations = d.annotations
                )
            case SymbolKind.OpaqueType =>
                Tasty.Symbol.OpaqueType(
                    id = sid,
                    name = d.name,
                    flags = d.flags,
                    ownerId = ownerSid,
                    scaladoc = d.scaladoc,
                    sourcePosition = d.sourcePosition,
                    body = d.declaredType.getOrElse(Tasty.Type.Unknown),
                    bounds = d.bounds.getOrElse(Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any)),
                    typeParamIds = Chunk.from(d.typeParamIds.toSeq.map(SymbolId(_))),
                    annotations = d.annotations
                )
            case SymbolKind.AbstractType =>
                Tasty.Symbol.AbstractType(
                    id = sid,
                    name = d.name,
                    flags = d.flags,
                    ownerId = ownerSid,
                    scaladoc = d.scaladoc,
                    sourcePosition = d.sourcePosition,
                    bounds = d.bounds.getOrElse(Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any)),
                    annotations = d.annotations
                )
            case SymbolKind.TypeParam =>
                val variance =
                    if d.flags.contains(Tasty.Flag.CoVariant) then Tasty.Variance.Covariant
                    else if d.flags.contains(Tasty.Flag.ContraVariant) then Tasty.Variance.Contravariant
                    else Tasty.Variance.Invariant
                Tasty.Symbol.TypeParam(
                    id = sid,
                    name = d.name,
                    flags = d.flags,
                    ownerId = ownerSid,
                    sourcePosition = d.sourcePosition,
                    bounds = d.bounds.getOrElse(Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any)),
                    variance = variance
                )
            case SymbolKind.Parameter =>
                Tasty.Symbol.Parameter(
                    id = sid,
                    name = d.name,
                    flags = d.flags,
                    ownerId = ownerSid,
                    sourcePosition = d.sourcePosition,
                    declaredType = d.declaredType.getOrElse(Tasty.Type.Unknown),
                    defaultArgId = d.defaultArgId.map(SymbolId(_)),
                    annotations = d.annotations
                )
            case SymbolKind.Package =>
                Tasty.Symbol.Package(
                    id = sid,
                    name = d.name,
                    flags = d.flags,
                    ownerId = ownerSid,
                    memberIds = Chunk.from(d.declarationIds.toSeq.map(SymbolId(_)))
                )
            case SymbolKind.EnumCase =>
                // F-E-007 (Phase 13): emit Symbol.EnumCase as a proper subtype of Symbol.Class.
                // Symbol.Class is no longer final; Symbol.EnumCase extends it so callers matching
                // Symbol.Class still work, but callers can now discriminate via Symbol.EnumCase.
                Tasty.Symbol.EnumCase(
                    id = sid,
                    name = d.name,
                    flags = d.flags,
                    ownerId = ownerSid,
                    scaladoc = d.scaladoc,
                    sourcePosition = d.sourcePosition,
                    javaMetadata = d.javaMetadata,
                    parentTypes = d.parentTypes,
                    typeParamIds = Chunk.from(d.typeParamIds.toSeq.map(SymbolId(_))),
                    declarationIds = Chunk.from(d.declarationIds.toSeq.map(SymbolId(_))),
                    permittedSubclassIds = d.permittedSubclassIds.map(_.map(SymbolId(_))),
                    annotations = d.annotations,
                    javaAnnotations = d.javaAnnotations,
                    body = d.body
                )
            case SymbolKind.Unresolved =>
                Tasty.Symbol.Unresolved(sid, d.name, ownerSid, d.flags)
        end match
    end from

end TypedSymbolFactory
