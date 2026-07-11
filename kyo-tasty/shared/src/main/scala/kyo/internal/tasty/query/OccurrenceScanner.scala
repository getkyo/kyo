package kyo.internal.tasty.query

import kyo.*
import kyo.Tasty
import kyo.Tasty.MemberScope
import kyo.Tasty.Name.asString
import kyo.Tasty.SymbolId
import kyo.internal.tasty.reader.PositionsUnpickler.PositionMap
import kyo.internal.tasty.reader.TreeUnpickler
import kyo.internal.tasty.reader.TypeUnpickler
import kyo.internal.tasty.symbol.SymbolBody

/** Decodes one source file's bodies into the use-site occurrences the symbol index serves.
  *
  * The body decoder does NOT resolve use-site references to final classpath SymbolIds: a local
  * same-pickle reference decodes as `Tree.TermRefDirect(address)` (a raw section-relative address, no Type),
  * a member selection as `Tree.Select(qualifier, name, Type.Wildcard)` (the Select carries NO
  * symbol info), and any `Type.Named` a node carries is still `PHASE_B_ADDR_OFFSET`-encoded
  * because the lazy decode never runs finalizeMerge's offset->final-id remap. This scanner
  * resolves each shape to a genuine final SymbolId itself:
  *   - `TermRefDirect`/`TermRefSymbol(address)`: `body.addrMap.get(body.sectionOffset + address)`.
  *     `addrMap` is the final-id map populated at load; its keys are absolute (sectionOffset +
  *     astRef), and `TermRefDirect.address` is the section-relative astRef, so we add
  *     sectionOffset (TypeUnpickler.scala:400-405 computes the same absRef).
  *   - `Ident(_, Type.Named(id))`: the lazy remap mirroring ClasspathOrchestrator.remapType ;
  *     a PHASE_B-encoded id resolves via `body.addrMap.get(id.value - PHASE_B_ADDR_OFFSET)`.
  *   - `Select`/`SelectIn(qualifier, name, _)`: resolved from the QUALIFIER's resolved type,
  *     never the (Wildcard) `Select.tpe` ; the qualifier resolves to a symbol, its declared type
  *     widens to the ClassLike whose members are in scope (a type parameter widens to its upper
  *     bound), and the named member is found via `classpath.findMember(_, _, MemberScope.All)`.
  *   - A qualifier that is itself a bare package-owned cross-file reference (a top-level module
  *     selection like `Foo.bar`) decodes as `Ident(name, Named(id))`, where `TreeUnpickler`
  *     reconstructs the TASTy `TERMREFpkg`/`TYPEREFpkg` tags this way; `id` resolves to the owning
  *     package by the fully-qualified name those tags carry (tracked in
  *     `TypeUnpickler.TreeTypeSession.unresolvedIdToFullName`, returned alongside `addrToNode` by
  *     `decodeWithAddrs`), and the member is then found via `classpath.findMember` on that package,
  *     never through the declared-type path above (`packageOwnerOf`, `selectTarget`).
  *   - A qualifier that is itself a body-local `val` (`val x = ..; x.foo`) has no Pass-1 `addrMap`
  *     entry (Pass 1 only indexes class/package/method/param-level definitions, never body-local
  *     statements), so `TermRefDirect`/`TermRefSymbol` fall back from `addrMap` to the SAME decode's
  *     `addrToNode` (already produced by `decodeWithAddrs` in `scanFile`): the local `ValDef` node at
  *     that address carries its declared type directly (`ValDef.tpt`, TreeUnpickler.scala:494-504),
  *     PHASE_B-encoded like any other lazily-decoded type, remapped the same way as `Ident`'s.
  *   - A TYPE-position reference (a symbol used AS A TYPE: `val x: Foo`, `def f(a: Foo): Bar`, a `Foo`
  *     type argument) is not a tree node the term walk carries; it is a decoded `Type` in the file's
  *     `TreeTypeSession.addrCache`, surfaced by `decodeWithAddrs` keyed by the same address space
  *     (`resolveTypeUse`): a `Type.Named` resolves as `Ident` does, and a `Type.TypeRef(qual, name)`
  *     resolves as `Select` does (the qualifier names a package/class container and `name` is its
  *     member), the generic `Named(-1)` qualifier denoting the owner's enclosing package. An
  *     `extends`/`with` parent clause is stripped before the per-file body slice, so THIS scanner never
  *     sees it; it is captured separately at cold load (the eager parent decode in
  *     `AstUnpickler.decodeTemplateParents` records the parent type-ref addresses into
  *     `parentOccurrenceStore`) and merged into the occurrence index by `Tasty.occurrencesInFile`, so a
  *     superclass relationship now surfaces BOTH by source location (that merge) and by symbol via
  *     `implementationsOf`/`parents`.
  * Each resolved id is bounds-checked against `classpath.symbols.size` (NOT a bare
  * `id.value >= 0`), so a leaked PHASE_B temp id or an unresolved cross-pickle negId is dropped,
  * never emitted as an occurrence.
  *
  * Pure under `AllowUnsafe`: the single `Sync.Unsafe.defer` boundary wrapping a call to this
  * object lives in the Tasty query layer (shared by `symbolAt` and `references`); this object
  * adds no `embrace.danger`. `private[kyo]`: never on the public surface.
  */
private[kyo] object OccurrenceScanner:

    private val phaseBOffset = TypeUnpickler.PHASE_B_ADDR_OFFSET

    /** Decode one file's bodies, collecting every term and type-position use-site occurrence.
      *
      * @param sourceFile        the file whose occurrences are collected
      * @param classpath         the active classpath, for symbol / type / member resolution
      * @param bodies            the file's symbol bodies paired with their owning `SymbolId`
      *   (resolved from `bySourceFile` ids via `bodyStore`)
      * @param positionsByPickle per-pickle `PositionMap`, keyed by `SymbolBody.pickleId`. A file
      *   spanning several pickles (two top-level declarations of one `.scala` file compile to two
      *   `.tasty` pickles sharing one source file) has one `PositionMap` per pickle, each in its own
      *   address space; a body is joined only to its own pickle's positions.
      */
    def scanFile(
        sourceFile: String,
        classpath: Tasty.Classpath,
        bodies: Chunk[(SymbolId, SymbolBody)],
        positionsByPickle: scala.collection.Map[Int, PositionMap]
    )(using AllowUnsafe): Chunk[Tasty.Occurrence] =
        // Fast path: a file with no decodable bodies or no positions contributes nothing.
        if bodies.isEmpty || positionsByPickle.isEmpty then Chunk.empty
        else
            val syms = classpath.symbols
            val out  = Chunk.newBuilder[Tasty.Occurrence]
            bodies.foreach { (ownerId, body) =>
                // Join each body to its OWN pickle's positions; skip if either is absent.
                positionsByPickle.get(body.pickleId).foreach { positions =>
                    if positions.nonEmpty then
                        classpath.symbol(ownerId) match
                            case Maybe.Present(primary) =>
                                // decodeWithAddrs returns (tree, addrToNode, unresolvedIdToFullName);
                                // addrToNode keys every decoded node by its absolute TASTy byte address
                                // (the same address space readSpans keys), and unresolvedIdToFullName
                                // carries the fully-qualified name of any package-owned cross-file
                                // reference the body's types held (a bare module selection's
                                // qualifier). The symbolLookup fallback mirrors decodeSync (out-of-range
                                // -> the body's own primary symbol).
                                val (_, addrToNode, unresolvedIdToFullName, typeAddrToType) = TreeUnpickler.decodeWithAddrs(
                                    body,
                                    primary,
                                    idx => if idx >= 0 && idx < syms.size then syms(idx) else primary
                                )
                                // Emit a resolved use site joined to its Positions span. The bounds check
                                // (NOT a bare id.value >= 0) keeps only a genuine final id: a leaked PHASE_B
                                // temp id or an unresolved cross-pickle negId drops.
                                def emit(addr: Int, id: SymbolId): Unit =
                                    if id.value >= 0 && id.value < syms.size then
                                        positions.get(addr) match
                                            case Some((sl, sc, el, ec)) =>
                                                // A zero-width span (a type-level or synthetic node carries a start
                                                // offset but no end delta in TASTy's Positions, so readSpans
                                                // degrades it to a point) is widened to the referenced name, so
                                                // find-references highlights and rename edits cover the identifier,
                                                // not a bare insertion point. A term use already carries its span.
                                                val (endLine, endColumn) =
                                                    if sl == el && sc == ec then (sl, sc + syms(id.value).simpleName.length)
                                                    else (el, ec)
                                                out += Tasty.Occurrence(Tasty.SourceRange(sourceFile, sl, sc, endLine, endColumn), id)
                                            // No Positions entry for this address (synthetic): drop.
                                            case None => ()
                                // Term use sites (method calls, field accesses): resolved from tree nodes.
                                addrToNode.foreach { (addr, node) =>
                                    resolve(node, body, classpath, addrToNode, unresolvedIdToFullName).foreach(emit(addr, _))
                                }
                                // Type-position use sites (a symbol used AS A TYPE in a signature or type
                                // argument): resolved from the decoded type node, which the term walk does not
                                // carry. Keyed in the same address space, so the Positions join is identical.
                                typeAddrToType.foreach { (addr, tpe) =>
                                    resolveTypeUse(tpe, body, primary, classpath, unresolvedIdToFullName).foreach(emit(addr, _))
                                }
                            case Maybe.Absent => ()
                        end match
                }
            }
            out.result()
        end if
    end scanFile

    /** Resolve a use-site node to the final classpath SymbolId it references, or Absent (degrade). */
    private def resolve(
        node: Tasty.Tree,
        body: SymbolBody,
        classpath: Tasty.Classpath,
        addrToNode: scala.collection.Map[Int, Tasty.Tree],
        unresolvedIdToFullName: scala.collection.Map[Int, String]
    ): Maybe[SymbolId] =
        node match
            case Tasty.Tree.TermRefDirect(address)         => directId(address, body)
            case Tasty.Tree.TermRefSymbol(address, _)      => directId(address, body)
            case Tasty.Tree.Ident(_, Tasty.Type.Named(id)) => remapNamed(id, body)
            case Tasty.Tree.Select(qual, name, _) =>
                selectTarget(qual, name, body, classpath, addrToNode, unresolvedIdToFullName)
            case Tasty.Tree.SelectIn(qual, name, _) =>
                selectTarget(qual, name, body, classpath, addrToNode, unresolvedIdToFullName)
            case _ => Maybe.Absent

    /** Resolve a decoded type to the classpath SymbolId it uses (a symbol referenced AS A TYPE, in a
      * signature or type-argument position). `Type.Named` resolves exactly as a term `Ident` (PHASE_B
      * remap, else the tracked fully-qualified name). `Type.TypeRef(qual, name)` resolves exactly as a
      * `Select`: the qualifier names a container (a package or class) and `name` is its member. Other
      * type shapes surface their constituent `Named`/`TypeRef` nodes at their own addresses, so this
      * degrades to Absent for them rather than recursing into structure.
      */
    private def resolveTypeUse(
        tpe: Tasty.Type,
        body: SymbolBody,
        owner: Tasty.Symbol,
        classpath: Tasty.Classpath,
        unresolvedIdToFullName: scala.collection.Map[Int, String]
    ): Maybe[SymbolId] =
        tpe match
            case Tasty.Type.Named(id) => resolveTypeRefId(id.value, body, classpath, unresolvedIdToFullName)
            case Tasty.Type.TypeRef(qual, name) =>
                typeQualContainer(qual, body, owner, classpath, unresolvedIdToFullName)
                    .flatMap(container => classpath.findMember(container, name.asString, MemberScope.All))
                    .map(_.id)
            case _ => Maybe.Absent

    /** The container (a package or class) a `TypeRef` qualifier names, for the member lookup. The
      * generic unresolved sentinel `Named(-1)` as a qualifier denotes the enclosing package of the
      * type's owner (a same-package cross-file reference the lazy decode could not link by address);
      * any other qualifier resolves through `resolveTypeUse` and must land on a `ClassLike`/`Package`.
      */
    private def typeQualContainer(
        qual: Tasty.Type,
        body: SymbolBody,
        owner: Tasty.Symbol,
        classpath: Tasty.Classpath,
        unresolvedIdToFullName: scala.collection.Map[Int, String]
    ): Maybe[Tasty.Symbol.ClassLike | Tasty.Symbol.Package] =
        qual match
            case Tasty.Type.Named(id) if id.value == -1 => enclosingPackageOf(owner, classpath)
            case _ =>
                resolveTypeUse(qual, body, owner, classpath, unresolvedIdToFullName)
                    .flatMap(classpath.symbol)
                    .flatMap {
                        case c: Tasty.Symbol.ClassLike => Maybe(c)
                        case p: Tasty.Symbol.Package   => Maybe(p)
                        case _                         => Maybe.Absent
                    }

    /** The nearest enclosing `Package` of `symbol`, from its owner chain. */
    private def enclosingPackageOf(symbol: Tasty.Symbol, classpath: Tasty.Classpath): Maybe[Tasty.Symbol.Package] =
        Maybe.fromOption(classpath.ownersChain(symbol).toSeq.collectFirst { case p: Tasty.Symbol.Package => p })

    /** Resolve a type-reference's raw `Named` id to a final classpath SymbolId. A PHASE_B-encoded or
      * already-final id remaps exactly as a term `Ident` does; an unresolved cross-pickle negId falls
      * back to the fully-qualified name the decoder tracked for it (`unresolvedIdToFullName`, the same
      * mechanism `packageOwnerOf` uses for a package-owned term qualifier), resolved via `findClassLike`.
      */
    private def resolveTypeRefId(
        rawId: Int,
        body: SymbolBody,
        classpath: Tasty.Classpath,
        unresolvedIdToFullName: scala.collection.Map[Int, String]
    ): Maybe[SymbolId] =
        remapNamed(SymbolId(rawId), body) match
            case Maybe.Absent =>
                unresolvedIdToFullName.get(rawId) match
                    case Some(fqn) => classpath.findClassLike(fqn).map(_.id)
                    case None      => Maybe.Absent
            case resolved => resolved

    /** A section-relative address resolved through the load-populated final-id `addrMap`
      * (keys absolute = sectionOffset + astRef; TypeUnpickler.scala:400-405).
      */
    private def directId(address: Int, body: SymbolBody): Maybe[SymbolId] =
        body.addrMap.get(body.sectionOffset + address) match
            case Some(id) => Maybe(id)
            case None     => Maybe.Absent

    /** A `Type.Named` id: PHASE_B-encoded -> final via `addrMap` (the lazy remap mirroring
      * ClasspathOrchestrator.remapType, :951-967); already-final -> kept; negId / unresolved
      * cross-pickle -> dropped (fail closed, never a name re-resolution: a plain simple-name
      * lookup would be ambiguous across scopes, so this function never attempts one). Distinct from
      * `packageOwnerOf`'s resolution of a package-owned qualifier, which is NOT a name
      * re-resolution in that sense: it looks up the exact fully-qualified name the decoder itself
      * tracked for that specific id (`unresolvedIdToFullName`), a deterministic 1:1 lookup, never a
      * guess reconstructed from a bare simple name.
      */
    private def remapNamed(id: SymbolId, body: SymbolBody): Maybe[SymbolId] =
        val v = id.value
        if v >= phaseBOffset then
            body.addrMap.get(v - phaseBOffset) match
                case Some(fid) => Maybe(fid)
                case None      => Maybe.Absent
        else if v >= 0 then Maybe(id)
        else Maybe.Absent
        end if
    end remapNamed

    /** Resolve a SELECT/SELECTin target from the QUALIFIER's resolved type, never `Select.tpe`
      * (which the decoder hardcodes to `Type.Wildcard`, TreeUnpickler.scala:392-405).
      *
      * A qualifier that is itself a bare package-owned cross-file reference (a top-level module
      * selection like `Foo.bar`, decoded as `Ident(_, Named(id))` with `id` carrying the package's
      * fully-qualified name via `unresolvedIdToFullName`; see `packageOwnerOf`) resolves the member
      * directly against that package, bypassing the declared-type detour below (a package has no
      * declared type of its own the way a value does).
      */
    private def selectTarget(
        qual: Tasty.Tree,
        name: Tasty.Name,
        body: SymbolBody,
        classpath: Tasty.Classpath,
        addrToNode: scala.collection.Map[Int, Tasty.Tree],
        unresolvedIdToFullName: scala.collection.Map[Int, String]
    ): Maybe[SymbolId] =
        packageOwnerOf(qual, unresolvedIdToFullName, classpath) match
            case Maybe.Present(pkg) =>
                classpath.findMember(pkg, name.asString, MemberScope.All).map(_.id)
            case Maybe.Absent =>
                qualifierType(qual, body, classpath, addrToNode, unresolvedIdToFullName)
                    .flatMap(t => classLikeOf(t, classpath))
                    .flatMap(cls => classpath.findMember(cls, name.asString, MemberScope.All))
                    .map(_.id)

    /** When `qual` is the `TERMREFpkg`/`TYPEREFpkg` decode artifact (`TreeUnpickler` reconstructs a
      * package-owned term reference as `Ident(name, Named(id))`, reusing the read package type as
      * the ident's own type), resolve it directly to the owning package by the fully-qualified name
      * `TERMREFpkg`/`TYPEREFpkg` read from the TASTy Names table but the lazy body decode otherwise
      * has nowhere to keep (`TypeUnpickler.TreeTypeSession.unresolvedIdToFullName`). Absent for every
      * other qualifier shape, including a genuine same-pickle local `Ident` (whose `id` resolves via
      * `remapNamed`, never this by-name path).
      */
    private def packageOwnerOf(
        qual: Tasty.Tree,
        unresolvedIdToFullName: scala.collection.Map[Int, String],
        classpath: Tasty.Classpath
    ): Maybe[Tasty.Symbol.Package] =
        qual match
            case Tasty.Tree.Ident(_, Tasty.Type.Named(id)) =>
                Maybe.fromOption(unresolvedIdToFullName.get(id.value)).flatMap(classpath.findPackage)
            case _ => Maybe.Absent

    /** The resolved (final-id) declared type of the qualifier expression. */
    private def qualifierType(
        t: Tasty.Tree,
        body: SymbolBody,
        classpath: Tasty.Classpath,
        addrToNode: scala.collection.Map[Int, Tasty.Tree],
        unresolvedIdToFullName: scala.collection.Map[Int, String]
    ): Maybe[Tasty.Type] =
        t match
            case Tasty.Tree.TermRefDirect(address) =>
                directId(address, body).flatMap(classpath.symbol).flatMap(declaredTypeOf)
                    .orElse(localValType(address, body, addrToNode))
            case Tasty.Tree.TermRefSymbol(address, _) =>
                directId(address, body).flatMap(classpath.symbol).flatMap(declaredTypeOf)
                    .orElse(localValType(address, body, addrToNode))
            case Tasty.Tree.Ident(_, Tasty.Type.Named(id)) =>
                remapNamed(id, body).flatMap(classpath.symbol).flatMap(declaredTypeOf)
            case Tasty.Tree.Select(q, n, _) =>
                selectTarget(q, n, body, classpath, addrToNode, unresolvedIdToFullName).flatMap(classpath.symbol).flatMap(declaredTypeOf)
            case Tasty.Tree.SelectIn(q, n, _) =>
                selectTarget(q, n, body, classpath, addrToNode, unresolvedIdToFullName).flatMap(classpath.symbol).flatMap(declaredTypeOf)
            case _ => Maybe.Absent

    /** A body-local `val`'s declared type, for a qualifier address that Pass 1 never indexed (so
      * `directId` misses). Looked up in the SAME decode's `addrToNode` (the address space matches
      * `addrMap`'s: `sectionOffset + address`, TypeUnpickler.scala:400-405) : the local `ValDef`
      * node there carries its declared type directly (`ValDef.tpt`), decoded but not offset-remapped
      * (the lazy decode never runs finalizeMerge's remap), so it is remapped here the same way
      * `remapNamed` remaps an `Ident`'s `Type.Named`.
      */
    private def localValType(
        address: Int,
        body: SymbolBody,
        addrToNode: scala.collection.Map[Int, Tasty.Tree]
    ): Maybe[Tasty.Type] =
        addrToNode.get(body.sectionOffset + address) match
            case Some(Tasty.Tree.ValDef(_, tpt, _)) => Maybe(remapType(tpt, body))
            case _                                  => Maybe.Absent

    /** Remaps embedded `Type.Named` ids from PHASE_B-encoded (same-pickle lazy refs) to final
      * classpath ids, recursing only into the shapes `classLikeOf` itself descends into (`Applied`'s
      * base, `TypeRef`'s qualifier). Mirrors `remapNamed`'s local-ref case and
      * `ClasspathOrchestrator.remapType`'s `Type.Named` arm; a cross-pickle negId stays unresolved
      * (fail closed, never a name re-resolution), same as `remapNamed` (see `remapNamed`'s doc for
      * why `packageOwnerOf`'s tracked-fully-qualified-name lookup is a different, deterministic
      * case, not an exception to this rule).
      */
    private def remapType(tpe: Tasty.Type, body: SymbolBody): Tasty.Type =
        tpe match
            case Tasty.Type.Named(id) =>
                remapNamed(id, body) match
                    case Maybe.Present(finalId) => Tasty.Type.Named(finalId)
                    case Maybe.Absent           => tpe
            case Tasty.Type.Applied(base, args) => Tasty.Type.Applied(remapType(base, body), args)
            case Tasty.Type.TypeRef(qual, name) => Tasty.Type.TypeRef(remapType(qual, body), name)
            case _                              => tpe

    /** The class-like symbol whose members are in scope for a selection on `tpe`: an applied type
      * unwraps to its constructor, a type parameter widens to its upper bound, and a qualified
      * reference to another pickle's class ("member type `name` of `qual`", the shape a cross-file
      * bound remaps to since `TypeUnpickler` never collapses a package-qualified reference to
      * `Type.Named`) resolves `qual` to its owning Package or ClassLike, then looks `name` up as a
      * member.
      */
    private def classLikeOf(tpe: Tasty.Type, classpath: Tasty.Classpath): Maybe[Tasty.Symbol.ClassLike] =
        tpe match
            case Tasty.Type.Applied(base, _) => classLikeOf(base, classpath)
            case Tasty.Type.TypeRef(qual, name) =>
                val qualSym = classpath.typeSymbol(qual)
                val owner: Maybe[Tasty.Symbol.ClassLike | Tasty.Symbol.Package] = qualSym match
                    case Maybe.Present(p: Tasty.Symbol.Package)   => Maybe(p)
                    case Maybe.Present(c: Tasty.Symbol.ClassLike) => Maybe(c)
                    case _                                        => Maybe.Absent
                val found = owner.flatMap(o => classpath.findMember(o, name.asString, MemberScope.All))
                found match
                    case Maybe.Present(c: Tasty.Symbol.ClassLike) => Maybe(c)
                    case _                                        => Maybe.Absent
            case _ =>
                val sym = classpath.typeSymbol(tpe)
                sym match
                    case Maybe.Present(c: Tasty.Symbol.ClassLike) => Maybe(c)
                    case Maybe.Present(tp: Tasty.Symbol.TypeParam) =>
                        classLikeOf(tp.bounds.upper, classpath)
                    case _ => Maybe.Absent
                end match
        end match
    end classLikeOf

    /** The declared type carried by a resolved symbol; a class-like symbol denotes itself as a type. */
    private def declaredTypeOf(sym: Tasty.Symbol): Maybe[Tasty.Type] =
        sym match
            case p: Tasty.Symbol.Parameter => p.declaredType
            case v: Tasty.Symbol.Val       => v.declaredType
            case v: Tasty.Symbol.Var       => v.declaredType
            case f: Tasty.Symbol.Field     => f.declaredType
            case m: Tasty.Symbol.Method    => m.declaredType
            case c: Tasty.Symbol.ClassLike => Maybe(Tasty.Type.Named(c.id))
            case _                         => Maybe.Absent

end OccurrenceScanner
