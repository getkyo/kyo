package kyo.internal.tasty.query

import kyo.*
import kyo.Tasty.SymbolId
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotReader

/** Probe each jar root for an embedded KRFL snapshot at `META-INF/kyo-tasty/snapshot.krfl`.
  *
  * INV-004: `withClasspath(roots, ...)` ALWAYS calls this probe before cold-loading. On a hit, the bundled snapshot is decoded and its
  * SymbolId space is remapped into the merged classpath. On a miss, the root is cold-loaded via the existing Phase A/B/C pipeline.
  *
  * INV-007 (verify-then-fallback): when the snapshot's embedded digest does not match the recomputed digest for the jar, the probe raises
  * `TastyError.DigestMismatch`. The caller's error mode decides whether to propagate (FailFast) or fall back to cold load (SoftFail).
  *
  * Scaladoc: 8-35 lines.
  */
private[kyo] object BundledSnapshotProbe:

    /** Internal snapshot entry path within the jar. */
    val snapshotEntryPath: String = "META-INF/kyo-tasty/snapshot.krfl"

    /** Probe `root` for an embedded KRFL snapshot.
      *
      * Returns `Maybe.Present(bytes)` with the raw snapshot bytes when the snapshot is present and its digest matches the recomputed digest
      * for `root`. Returns `Maybe.Absent` when no snapshot is present (the caller must cold-load). Raises
      * `Abort[TastyError.DigestMismatch]` when the embedded digest does not match the recomputed digest (stale snapshot).
      *
      * The probe is a no-op for non-jar roots (directories, jrt:/) because `FileSource.openZip` returns `Maybe.Absent` for those paths.
      */
    def probe(
        root: String,
        source: FileSource
    )(using Frame): Maybe[Array[Byte]] < (Sync & Scope & Abort[TastyError]) =
        source.openZip(root).flatMap:
            case Maybe.Absent => Maybe.Absent
            case Maybe.Present(handle) =>
                handle.readEntry(snapshotEntryPath).flatMap:
                    case Maybe.Absent => Maybe.Absent
                    case Maybe.Present(snapshotBytes) =>
                        Sync.defer:
                            val embeddedDigest   = SnapshotReader.readInputDigest(snapshotBytes)
                            val recomputedDigest = DigestComputer.digestForRoot(root)
                            if embeddedDigest != recomputedDigest then
                                val expectedHex = DigestComputer.toHexString(DigestComputer.longToBytes(embeddedDigest))
                                val actualHex   = DigestComputer.toHexString(DigestComputer.longToBytes(recomputedDigest))
                                Abort.fail(TastyError.DigestMismatch(expected = expectedHex, actual = actualHex))
                            else
                                Maybe.Present(snapshotBytes)
                            end if

    /** Merge a bundled snapshot's Classpath into an accumulator, remapping all SymbolIds by `idOffset`.
      *
      * All SymbolId-bearing id/ownerId/declarationId/typeParamId fields in each symbol from `partial` are shifted by `idOffset` so they are
      * disjoint from any previously accumulated symbols. The function builds a new Classpath combining `existing` symbols plus the remapped
      * `partial` symbols, merging all index maps.
      *
      * INV-005: after this call, every SymbolId from `partial` maps to `original + idOffset` in the result.
      */
    def mergePartialInto(
        existing: Tasty.Classpath,
        partial: Tasty.Classpath
    ): Tasty.Classpath =
        val offset = existing.symbols.size
        // Remap all symbols from partial with shifted ids.
        val remapped: Chunk[Tasty.Symbol] = partial.symbols.map(remapSymbol(_, offset))
        // Build merged symbol chunk.
        val allSymbols: Chunk[Tasty.Symbol] = existing.symbols ++ remapped
        // Merge byFqn: partial SymbolIds must be shifted.
        val mergedByFqn: Dict[String, SymbolId] =
            existing.indices.byFqn ++
                partial.indices.byFqn.map((fqn, id) => fqn -> SymbolId(id.value + offset))
        // Merge packageIndex.
        val mergedPkgIdx: Dict[String, SymbolId] =
            existing.indices.packageIndex ++
                partial.indices.packageIndex.map((fqn, id) => fqn -> SymbolId(id.value + offset))
        // Merge topLevelClassIds.
        val mergedTopIds: Chunk[SymbolId] =
            existing.indices.topLevelClassIds ++ partial.indices.topLevelClassIds.map(id => SymbolId(id.value + offset))
        // Merge packageIds.
        val mergedPkgIds: Chunk[SymbolId] =
            existing.indices.packageIds ++ partial.indices.packageIds.map(id => SymbolId(id.value + offset))
        // Merge subclassIndex.
        val mergedSubclass: Dict[SymbolId, Chunk[SymbolId]] =
            existing.indices.subclassIndex ++
                partial.indices.subclassIndex.map((k, vs) =>
                    SymbolId(k.value + offset) -> vs.map(v => SymbolId(v.value + offset))
                )
        // Merge companionIndex.
        val mergedCompanion: Dict[SymbolId, SymbolId] =
            existing.indices.companionIndex ++
                partial.indices.companionIndex.map((k, v) => SymbolId(k.value + offset) -> SymbolId(v.value + offset))
        // Merge bySimpleName.
        val mergedSimple: Dict[String, Chunk[SymbolId]] =
            val b = scala.collection.mutable.HashMap.empty[String, Chunk[SymbolId]]
            existing.indices.bySimpleName.foreach((k, v) => b(k) = v)
            partial.indices.bySimpleName.foreach: (k, v) =>
                val shifted = v.map(id => SymbolId(id.value + offset))
                b(k) = b.getOrElse(k, Chunk.empty) ++ shifted
            Dict.from(b.toMap)
        end mergedSimple
        // Merge errors and modules.
        val mergedErrors: Chunk[TastyError]                    = existing.errors ++ partial.errors
        val mergedModules: Chunk[Tasty.Java.Module.Descriptor] = existing.modules ++ partial.modules
        // rootSymbolId: keep existing if valid, else use partial shifted.
        val rootId =
            if existing.symbols.nonEmpty then existing.rootSymbolId
            else if partial.symbols.nonEmpty then SymbolId(partial.rootSymbolId.value + offset)
            else SymbolId(-1)
        Tasty.Classpath(
            symbols = allSymbols,
            indices = Tasty.Classpath.Indices(
                byFqn = mergedByFqn,
                bySimpleName = mergedSimple,
                packageIndex = mergedPkgIdx,
                subclassIndex = mergedSubclass,
                companionIndex = mergedCompanion,
                modulesIndex = existing.indices.modulesIndex ++ partial.indices.modulesIndex,
                topLevelClassIds = mergedTopIds,
                packageIds = mergedPkgIds,
                unresolvedFqnByNegId = existing.indices.unresolvedFqnByNegId ++ partial.indices.unresolvedFqnByNegId,
                diagnostics = existing.indices.diagnostics ++ partial.indices.diagnostics
            ),
            errors = mergedErrors,
            modules = mergedModules,
            rootSymbolId = rootId
        )
    end mergePartialInto

    /** Remap all SymbolId fields and all Type-bearing fields in `sym` by adding `offset`.
      *
      * Every SymbolId reference in the bundled partial is a finalized id in range [0, N). Adding
      * offset shifts the entire id space to [offset, offset+N) so it is disjoint from the ids
      * already in `existing`. This includes: id, ownerId, declarationIds, typeParamIds,
      * permittedSubclassIds, paramListIds, defaultArgId, memberIds, and every SymbolId embedded
      * inside a Type field (Named, ThisType, TypeLambda.paramIds, ParamRef.binderId).
      */
    private def remapSymbol(sym: Tasty.Symbol, offset: Int): Tasty.Symbol =
        val newId      = SymbolId(sym.id.value + offset)
        val newOwnerId = SymbolId(sym.ownerId.value + offset)
        sym match
            case p: Tasty.Symbol.Package =>
                p.copy(
                    id = newId,
                    ownerId = newOwnerId,
                    memberIds = p.memberIds.map(id => SymbolId(id.value + offset))
                )
            case c: Tasty.Symbol.Class =>
                c.copy(
                    id = newId,
                    ownerId = newOwnerId,
                    typeParamIds = c.typeParamIds.map(id => SymbolId(id.value + offset)),
                    declarationIds = c.declarationIds.map(id => SymbolId(id.value + offset)),
                    permittedSubclassIds = c.permittedSubclassIds.map(_.map(id => SymbolId(id.value + offset))),
                    parentTypes = c.parentTypes.map(remapType(_, offset)),
                    annotations = c.annotations.map(remapAnnotation(_, offset))
                )
            case t: Tasty.Symbol.Trait =>
                t.copy(
                    id = newId,
                    ownerId = newOwnerId,
                    typeParamIds = t.typeParamIds.map(id => SymbolId(id.value + offset)),
                    declarationIds = t.declarationIds.map(id => SymbolId(id.value + offset)),
                    permittedSubclassIds = t.permittedSubclassIds.map(_.map(id => SymbolId(id.value + offset))),
                    parentTypes = t.parentTypes.map(remapType(_, offset)),
                    annotations = t.annotations.map(remapAnnotation(_, offset))
                )
            case o: Tasty.Symbol.Object =>
                o.copy(
                    id = newId,
                    ownerId = newOwnerId,
                    typeParamIds = o.typeParamIds.map(id => SymbolId(id.value + offset)),
                    declarationIds = o.declarationIds.map(id => SymbolId(id.value + offset)),
                    parentTypes = o.parentTypes.map(remapType(_, offset)),
                    annotations = o.annotations.map(remapAnnotation(_, offset))
                )
            case e: Tasty.Symbol.EnumCase =>
                e.copy(
                    id = newId,
                    ownerId = newOwnerId,
                    typeParamIds = e.typeParamIds.map(id => SymbolId(id.value + offset)),
                    declarationIds = e.declarationIds.map(id => SymbolId(id.value + offset)),
                    permittedSubclassIds = e.permittedSubclassIds.map(_.map(id => SymbolId(id.value + offset))),
                    parentTypes = e.parentTypes.map(remapType(_, offset)),
                    annotations = e.annotations.map(remapAnnotation(_, offset))
                )
            case m: Tasty.Symbol.Method =>
                m.copy(
                    id = newId,
                    ownerId = newOwnerId,
                    paramListIds = m.paramListIds.map(_.map(id => SymbolId(id.value + offset))),
                    typeParamIds = m.typeParamIds.map(id => SymbolId(id.value + offset)),
                    declaredType = m.declaredType.map(remapType(_, offset)),
                    annotations = m.annotations.map(remapAnnotation(_, offset))
                )
            case v: Tasty.Symbol.Val =>
                v.copy(
                    id = newId,
                    ownerId = newOwnerId,
                    declaredType = v.declaredType.map(remapType(_, offset)),
                    annotations = v.annotations.map(remapAnnotation(_, offset))
                )
            case w: Tasty.Symbol.Var =>
                w.copy(
                    id = newId,
                    ownerId = newOwnerId,
                    declaredType = w.declaredType.map(remapType(_, offset)),
                    annotations = w.annotations.map(remapAnnotation(_, offset))
                )
            case f: Tasty.Symbol.Field =>
                f.copy(
                    id = newId,
                    ownerId = newOwnerId,
                    declaredType = f.declaredType.map(remapType(_, offset))
                )
            case ta: Tasty.Symbol.TypeAlias =>
                ta.copy(
                    id = newId,
                    ownerId = newOwnerId,
                    typeParamIds = ta.typeParamIds.map(id => SymbolId(id.value + offset)),
                    body = remapType(ta.body, offset),
                    annotations = ta.annotations.map(remapAnnotation(_, offset))
                )
            case ot: Tasty.Symbol.OpaqueType =>
                ot.copy(
                    id = newId,
                    ownerId = newOwnerId,
                    typeParamIds = ot.typeParamIds.map(id => SymbolId(id.value + offset)),
                    body = remapType(ot.body, offset),
                    bounds = remapTypeBounds(ot.bounds, offset),
                    annotations = ot.annotations.map(remapAnnotation(_, offset))
                )
            case ab: Tasty.Symbol.AbstractType =>
                ab.copy(
                    id = newId,
                    ownerId = newOwnerId,
                    bounds = remapTypeBounds(ab.bounds, offset),
                    annotations = ab.annotations.map(remapAnnotation(_, offset))
                )
            case tp: Tasty.Symbol.TypeParam =>
                tp.copy(
                    id = newId,
                    ownerId = newOwnerId,
                    bounds = remapTypeBounds(tp.bounds, offset)
                )
            case pr: Tasty.Symbol.Parameter =>
                pr.copy(
                    id = newId,
                    ownerId = newOwnerId,
                    defaultArgId = pr.defaultArgId.map(did => SymbolId(did.value + offset)),
                    declaredType = remapType(pr.declaredType, offset),
                    annotations = pr.annotations.map(remapAnnotation(_, offset))
                )
            case u: Tasty.Symbol.Unresolved =>
                u.copy(id = newId, ownerId = newOwnerId)
        end match
    end remapSymbol

    /** Remap all SymbolId references embedded in a Type by adding `offset`.
      *
      * All SymbolIds in a bundled partial are finalized ids in [0, N). This walk shifts every
      * Named.symbolId, ThisType.clsId, TypeLambda.paramIds, and ParamRef.binderId by offset so
      * they refer to the post-merge id space. Pure structural cases (ConstantType, Nothing, Any,
      * Unknown) carry no ids and are returned unchanged.
      */
    private def remapType(t: Tasty.Type, offset: Int): Tasty.Type =
        t match
            case Tasty.Type.Named(sid) =>
                Tasty.Type.Named(SymbolId(sid.value + offset))
            case Tasty.Type.ThisType(clsId) =>
                Tasty.Type.ThisType(SymbolId(clsId.value + offset))
            case Tasty.Type.ParamRef(binderId, idx) =>
                Tasty.Type.ParamRef(SymbolId(binderId.value + offset), idx)
            case Tasty.Type.TypeLambda(paramIds, body) =>
                Tasty.Type.TypeLambda(
                    paramIds.map(id => SymbolId(id.value + offset)),
                    remapType(body, offset)
                )
            case Tasty.Type.Applied(base, args) =>
                Tasty.Type.Applied(remapType(base, offset), args.map(remapType(_, offset)))
            case Tasty.Type.Function(params, result) =>
                Tasty.Type.Function(params.map(remapType(_, offset)), remapType(result, offset))
            case Tasty.Type.ContextFunction(params, result) =>
                Tasty.Type.ContextFunction(params.map(remapType(_, offset)), remapType(result, offset))
            case Tasty.Type.Tuple(elements) =>
                Tasty.Type.Tuple(elements.map(remapType(_, offset)))
            case Tasty.Type.ByName(underlying) =>
                Tasty.Type.ByName(remapType(underlying, offset))
            case Tasty.Type.Repeated(elem) =>
                Tasty.Type.Repeated(remapType(elem, offset))
            case Tasty.Type.Array(elem) =>
                Tasty.Type.Array(remapType(elem, offset))
            case Tasty.Type.Refinement(parent, name, info) =>
                Tasty.Type.Refinement(remapType(parent, offset), name, remapType(info, offset))
            case Tasty.Type.Rec(parent) =>
                Tasty.Type.Rec(remapType(parent, offset))
            case Tasty.Type.RecThis(rec) =>
                Tasty.Type.RecThis(remapType(rec, offset))
            case Tasty.Type.AndType(left, right) =>
                Tasty.Type.AndType(remapType(left, offset), remapType(right, offset))
            case Tasty.Type.OrType(left, right) =>
                Tasty.Type.OrType(remapType(left, offset), remapType(right, offset))
            case Tasty.Type.Annotated(underlying, ann) =>
                Tasty.Type.Annotated(remapType(underlying, offset), remapAnnotation(ann, offset))
            case Tasty.Type.SuperType(self, mixin) =>
                Tasty.Type.SuperType(remapType(self, offset), remapType(mixin, offset))
            case Tasty.Type.Wildcard(lo, hi) =>
                Tasty.Type.Wildcard(remapType(lo, offset), remapType(hi, offset))
            case Tasty.Type.Bounds(lo, hi) =>
                Tasty.Type.Bounds(remapType(lo, offset), remapType(hi, offset))
            case Tasty.Type.Skolem(underlying) =>
                Tasty.Type.Skolem(remapType(underlying, offset))
            case Tasty.Type.MatchType(bound, scrut, cases) =>
                Tasty.Type.MatchType(remapType(bound, offset), remapType(scrut, offset), cases.map(remapType(_, offset)))
            case Tasty.Type.MatchCase(pat, rhs) =>
                Tasty.Type.MatchCase(remapType(pat, offset), remapType(rhs, offset))
            case Tasty.Type.FlexibleType(underlying) =>
                Tasty.Type.FlexibleType(remapType(underlying, offset))
            case Tasty.Type.TermRef(prefix, name) =>
                Tasty.Type.TermRef(remapType(prefix, offset), name)
            case Tasty.Type.TypeRef(qual, name) =>
                Tasty.Type.TypeRef(remapType(qual, offset), name)
            case _ => t
    end remapType

    private def remapTypeBounds(tb: Tasty.TypeBounds, offset: Int): Tasty.TypeBounds =
        Tasty.TypeBounds(remapType(tb.lower, offset), remapType(tb.upper, offset))

    private def remapAnnotation(ann: Tasty.Annotation, offset: Int): Tasty.Annotation =
        ann.copy(annotationType = remapType(ann.annotationType, offset))

end BundledSnapshotProbe
