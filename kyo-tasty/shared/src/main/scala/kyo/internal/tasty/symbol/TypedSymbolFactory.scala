package kyo.internal.tasty.symbol

import kyo.*
import kyo.Tasty
import kyo.Tasty.SymbolId
import kyo.TastyError
// SymbolKind is in the same package (kyo.internal.tasty.symbol); no import needed

/** Thrown by TypedSymbolFactory.from under FailFast when a declared type cannot be resolved.
  *
  * Uses the same stackless-exception pattern as TastyErrorException in the reader package, but is
  * scoped to the symbol package to keep the two package boundaries clean. ClasspathOrchestrator catches
  * this carrier at materializeSymbols and converts it to Abort.fail(TastyError.MissingDeclaredType).
  */
final private[kyo] class SymbolMaterializationError(val error: TastyError)
    extends RuntimeException(error.toString, null, true, false)

/** Canonical factory that converts a fully-populated SymbolDescriptor into the matching typed Symbol subtype.
  *
  * Called by ClasspathOrchestrator.materializeSymbols (Pass C), SnapshotReader (all 4 construction sites), ClassfileUnpickler (2 sites),
  * and Scala2PickleReader (1 site). Replaces the bridge chain fromDescriptor -> fromFlat -> _SymbolFlat.
  *
  * All 14 SymbolKind cases are handled. The `d.id` and `d.ownerId` fields are int-typed in SymbolDescriptor; they are wrapped as SymbolId
  * values here. Collection fields (`typeParamIds`, `declarationIds`, `paramListIds`) undergo a similar int-to-SymbolId lift.
  *
  * The optional parameters `mode`, `accErrors`, `file`, and `byteOffset` are used by
  * ClasspathOrchestrator.materializeSymbols to thread error-accumulation context. All other call sites
  * (SnapshotReader, ClassfileUnpickler, Scala2PickleReader) leave them at defaults; their SymbolDescriptors
  * always have declaredType resolved before calling from, so the error path is never triggered.
  *
  * `accErrors` uses Maybe[ArrayBuffer] rather than a null sentinel to express "no accumulator provided".
  * Maybe.Absent means silently return Maybe.Absent for absent types (clean stdlib loads, snapshot readers).
  * Maybe.Present(buf) means record errors into buf; under FailFast throw SymbolMaterializationError instead.
  */
private[kyo] object TypedSymbolFactory:

    def from(
        d: SymbolDescriptor,
        mode: Tasty.ErrorMode = Tasty.ErrorMode.SoftFail,
        accErrors: Maybe[scala.collection.mutable.ArrayBuffer[TastyError]] = Maybe.Absent,
        file: String = "<unknown>",
        byteOffset: Long = 0L
    ): Tasty.Symbol =
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
                    javaAnnotations = d.javaAnnotations
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
                    javaAnnotations = d.javaAnnotations
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
                    javaAnnotations = d.javaAnnotations
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
                    annotations = d.annotations
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
                    annotations = d.annotations
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
                    body = d.declaredType match
                        case Maybe.Present(t) => Maybe.Present(t)
                        case Maybe.Absent =>
                            accErrors match
                                case Maybe.Present(buf) =>
                                    if mode == Tasty.ErrorMode.FailFast then
                                        throw new SymbolMaterializationError(
                                            TastyError.MissingDeclaredType(SymbolId(d.id), file)
                                        )
                                    else
                                        buf += TastyError.UnknownType(
                                            file = file,
                                            byteOffset = byteOffset,
                                            reason = "TypedSymbolFactory: TypeAlias body absent"
                                        )
                                case Maybe.Absent => ()
                            end match
                            Maybe.Absent
                    ,
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
                    body = d.declaredType match
                        case Maybe.Present(t) => Maybe.Present(t)
                        case Maybe.Absent =>
                            accErrors match
                                case Maybe.Present(buf) =>
                                    if mode == Tasty.ErrorMode.FailFast then
                                        throw new SymbolMaterializationError(
                                            TastyError.MissingDeclaredType(SymbolId(d.id), file)
                                        )
                                    else
                                        buf += TastyError.UnknownType(
                                            file = file,
                                            byteOffset = byteOffset,
                                            reason = "TypedSymbolFactory: OpaqueType body absent"
                                        )
                                case Maybe.Absent => ()
                            end match
                            Maybe.Absent
                    ,
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
                    declaredType = d.declaredType match
                        case Maybe.Present(t) => Maybe.Present(t)
                        case Maybe.Absent =>
                            accErrors match
                                case Maybe.Present(buf) =>
                                    if mode == Tasty.ErrorMode.FailFast then
                                        throw new SymbolMaterializationError(
                                            TastyError.MissingDeclaredType(SymbolId(d.id), file)
                                        )
                                    else
                                        buf += TastyError.UnknownType(
                                            file = file,
                                            byteOffset = byteOffset,
                                            reason = "TypedSymbolFactory: Parameter declaredType absent"
                                        )
                                case Maybe.Absent => ()
                            end match
                            Maybe.Absent
                    ,
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
                // Emit Symbol.EnumCase as a proper subtype of Symbol.Class.
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
                    javaAnnotations = d.javaAnnotations
                )
            // SymbolKind.Unresolved was removed in an earlier iteration; this case is retained for backward compatibility
            // with SnapshotReader (ordinal 14 -> Package fallback). Treat as Package.
            case _ =>
                Tasty.Symbol.Package(sid, d.name, d.flags, ownerSid, Chunk.empty)
        end match
    end from

end TypedSymbolFactory
