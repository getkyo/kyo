package kyo.internal.tasty.reader

import kyo.*
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.symbol.Flags as InternalFlags
import kyo.internal.tasty.symbol.Symbol as InternalSymbol
import kyo.internal.tasty.symbol.SymbolKind as InternalSymbolKind
import kyo.internal.tasty.type_.TypeArena
import scala.collection.immutable.IntMap
import scala.collection.mutable

/** Pass 1 skeleton-eager AST unpickler.
  *
  * Implements the pre-scan pattern from dotty `TreeUnpickler.indexStats` (scala3-compiler 3.7.0, line ~799). Walks the AST section
  * byte-by-byte, allocating one `Tasty.Symbol` per definition node (PACKAGE, VALDEF, DEFDEF, TYPEDEF, TYPEPARAM, PARAM). Eagerly decodes
  * name (NameRef lookup), flags (modifier tags), and owner (via owner stack). Records body slices for DEFDEF and class bodies. Skips all
  * other content.
  *
  * Forward references: symbols are allocated in AST (left-to-right byte) order. When a type bound references a sibling type param, that
  * sibling is already in addrMap because the pre-scan walked all sibling nodes first. This matches dotty's
  * `forkAt(templateStart).indexTemplateParams()` pattern.
  *
  * STEERING directives applied:
  *   - TYPEDEF discrimination (CRITICAL): peek for TEMPLATE (156) tag after NameRef before reading modifiers. Reference: dotty
  *     TreeUnpickler.readNewDef, which dispatches on the next tag to decide class vs type alias.
  *   - qualified-modifier sub-tree skip (CRITICAL): PRIVATEqualified (98) and PROTECTEDqualified (99) are category 3 (tag + sub-AST). The
  *     modifier loop skips their sub-AST via skipTree(). Failing to do this corrupts the cursor.
  *
  * Phase 4 note: TypeUnpickler resolves cross-file type references by creating synthetic unresolved symbols directly. The UnresolvedRef
  * mechanism was deleted in Phase 07; cross-file references now become Named(SymbolId(-1)) entries resolved at Phase C finalizeMerge via
  * fqnIndex lookup.
  */
object AstUnpickler:

    /** Result of pass 1.
      *
      * @param symbols
      *   All symbols discovered during the walk, excluding the synthetic root sentinel.
      * @param addrMap
      *   Map from TASTy byte address to symbol, for cross-symbol type references.
      * @param placeholders
      *   (deleted in Phase 07; UnresolvedRef mechanism removed)
      * @param rootSymbol
      *   The synthetic root Package sentinel (empty name, no owner). Used as the top-level owner for package-level definitions.
      * @param parentsBySymbol
      *   Pre-indexed map from each class-like symbol to its parent types. Consumed by Pass C.
      * @param childrenByOwner
      *   Pre-indexed map from each symbol to its directly-owned child symbols.
      * @param typeBySymbol
      *   Pre-indexed map from each symbol to its declared type. Consumed by Pass C.
      * @param ownerBySymbol
      *   Map from each symbol to its owner symbol (the partial Symbol used during Pass 1). Used by Pass C to build ownerId fields.
      *   Retained: Pass A/B pipeline still produces partial Tasty.Symbol instances; SymbolDescriptor migration is out of campaign scope.
      * @param bodyDataByAddr
      *   Map from TASTy byte address to (bodyStart, bodyEnd) for symbols that have a body. File-level body data. Retained: Pass A/B
      *   pipeline still produces partial Tasty.Symbol instances; SymbolDescriptor migration is out of campaign scope.
      * @param sectionBytes
      *   The raw AST section bytes for this file (shared across all symbols). For Pass C SymbolBody construction.
      * @param sectionOffset
      *   Absolute byte offset where the AST section starts. For SymbolBody.sectionOffset.
      */
    /** Fields `addrMap`, `parentsBySymbol`, `childrenByOwner`, and `typeBySymbol` use identity-based maps. They are safe because
      * `Pass1Result` is produced by a single decoder fiber and consumed by the single-threaded merger fiber after the channel put/take
      * provides a happens-before edge. No concurrent access occurs.
      *
      * IdentityHashMap is required because Pass1Result holds PLACEHOLDER symbols (id=-1, created by makePlaceholder). With field-based
      * Symbol equality (Phase 03), two structurally-equal placeholders (same name/kind/flags) would collide in a regular HashMap,
      * causing data loss. IdentityHashMap uses object identity for key comparison, which is always correct for placeholders.
      */
    final case class Pass1Result(
        symbols: Chunk[Tasty.Symbol],
        addrMap: IntMap[Tasty.Symbol],
        rootSymbol: Tasty.Symbol,
        parentsBySymbol: java.util.IdentityHashMap[Tasty.Symbol, Chunk[Tasty.Type]],
        childrenByOwner: java.util.IdentityHashMap[Tasty.Symbol, Chunk[Tasty.Symbol]],
        typeBySymbol: java.util.IdentityHashMap[Tasty.Symbol, Tasty.Type],
        ownerBySymbol: java.util.IdentityHashMap[Tasty.Symbol, Tasty.Symbol],
        bodyDataByAddr: java.util.IdentityHashMap[Tasty.Symbol, (Int, Int)],
        sectionBytes: Array[Byte],
        sectionOffset: Int,
        names: Array[Tasty.Name],
        /** Cross-file FQN -> unique negative SymbolId mappings accumulated by TypeUnpickler during Phase B. Phase C uses this to resolve
          * Named(SymbolId(negId)) parent types to final SymbolIds via fqnIndex.
          */
        unresolvedIdToFqn: mutable.HashMap[Int, String],
        /** Errors from eager annotation arg decodes in ANNOTATEDtype. Flows into FileResult.errors via ClasspathOrchestrator. */
        annotationDecodeErrors: Chunk[TastyError],
        /** Per-symbol annotation list decoded from ANNOTATION modifier blocks. Populated by readModifiers and scanForwardAndCollectFlags.
          * F-G-001 fix: flows through FileResult into ClasspathOrchestrator.finalizeMerge where descs(idx).annotations is set.
          */
        annotationsBySymbol: java.util.IdentityHashMap[Tasty.Symbol, mutable.ArrayBuffer[Tasty.Annotation]]
    )

    /** Run pass 1 over the AST section.
      *
      * @param view
      *   ByteView positioned at the start of the AST section payload. The view's `remaining` covers the full AST section.
      * @param names
      *   0-based name array produced by NameUnpickler.
      * @param attrs
      *   file attributes (isJava flag used to set JavaDefined on symbols).
      * @param arena
      *   Per-file TypeArena used by TypeUnpickler to hash-cons decoded types.
      *
      * Note on effect row: the return type includes `Sync` because `Sync.defer` wraps the purely-computed `Pass1Result` value on the
      * success path. The actual body (`runPass1`) is synchronous. The `Sync` widening is intentional to keep the return type uniform with
      * other Kyo-effect-returning readers in this package.
      */
    def readPass1(
        view: ByteView,
        names: Array[Tasty.Name],
        attrs: FileAttributes,
        arena: TypeArena
    )(using frame: Frame): Pass1Result < (Sync & Abort[TastyError]) =
        Sync.Unsafe.defer:
            try Right(runPass1(view, names, attrs, arena)(using frame, summon[AllowUnsafe]))
            catch
                case ex: ArrayIndexOutOfBoundsException =>
                    Left(TastyError.MalformedSection("ASTs", s"unexpected end: ${ex.getMessage}", view.position))
                case ex: java.lang.Error
                    if ex.getCause != null && ex.getCause.isInstanceOf[ArrayIndexOutOfBoundsException] =>
                    Left(TastyError.MalformedSection("ASTs", s"unexpected end: ${ex.getCause.getMessage}", view.position))
                case ex: Exception =>
                    Left(TastyError.MalformedSection("ASTs", s"parse error: ${ex.getMessage}", view.position))
        .map:
            case Right(r)  => r
            case Left(err) => Abort.fail(err)
    end readPass1

    private def runPass1(
        view: ByteView,
        names: Array[Tasty.Name],
        attrs: FileAttributes,
        arena: TypeArena
    )(using Frame, AllowUnsafe): Pass1Result =
        val addrMap    = new mutable.HashMap[Int, Tasty.Symbol]()
        val allSymbols = new mutable.ArrayBuffer[Tasty.Symbol]()
        val ownerStack = new mutable.ArrayDeque[Tasty.Symbol]()
        // IdentityHashMap: placeholder symbols (id=-1) are keyed by object identity to prevent
        // structurally-equal placeholders from colliding (Phase 03 field-based equality regression fix).
        val parentsBySymbol = new java.util.IdentityHashMap[Tasty.Symbol, Chunk[Tasty.Type]]()
        val typeBySymbol    = new java.util.IdentityHashMap[Tasty.Symbol, Tasty.Type]()
        // ownerBySymbol and bodyDataByAddr track per-symbol data consumed by Pass C to build
        // ownerId and body fields on the final immutable Symbols.
        val ownerBySymbol  = new java.util.IdentityHashMap[Tasty.Symbol, Tasty.Symbol]()
        val bodyDataByAddr = new java.util.IdentityHashMap[Tasty.Symbol, (Int, Int)]()
        // F-G-001: annotation accumulator threaded through walkStats.
        val annotationsBySymbol = new java.util.IdentityHashMap[Tasty.Symbol, mutable.ArrayBuffer[Tasty.Annotation]]()

        // Synthetic root: a Package symbol with empty name; owns itself (self-referential sentinel).
        val rootName: Tasty.Name = Tasty.Name("")
        val root                 = InternalSymbol.makeSymbol(Tasty.SymbolKind.Package, Tasty.Flags.empty, rootName)
        ownerStack.append(root)
        allSymbols += root

        val sectionOffset = view.positionInt
        val sectionEnd    = view.position + view.remaining
        val sectionBytes  = view.allBytes
        // Phase 1: collect symbols. The typeSession holds the live addrMap so type decode can find
        // locally-defined symbols as the walk progresses.
        val typeSession = new TypeUnpickler.DecodeSession(names, addrMap, arena)
        walkStats(
            view,
            sectionEnd,
            names,
            sectionBytes,
            sectionOffset,
            attrs,
            addrMap,
            allSymbols,
            ownerStack,
            typeSession,
            parentsBySymbol,
            typeBySymbol,
            ownerBySymbol,
            bodyDataByAddr,
            annotationsBySymbol
        )

        // Convert the mutable addrMap to an immutable IntMap once, after walkStats completes.
        // IntMap uses primitive Int keys; no autoboxing on .get(Int) lookups.
        val intMap = IntMap.from(addrMap.iterator)

        // Build childrenByOwner: group all non-root symbols by their owner (from ownerBySymbol).
        // IdentityHashMap: placeholder symbols keyed by identity (Phase 03 fix).
        val childrenByOwnerBuf = new java.util.IdentityHashMap[Tasty.Symbol, mutable.ArrayBuffer[Tasty.Symbol]]()
        for sym <- allSymbols.tail do // skip root
            val owner = ownerBySymbol.get(sym)
            if owner != null then
                var buf = childrenByOwnerBuf.get(owner)
                if buf == null then
                    buf = new mutable.ArrayBuffer[Tasty.Symbol]()
                    discard(childrenByOwnerBuf.put(owner, buf))
                end if
                buf += sym
            end if
        end for

        // Convert ArrayBuffer values to Chunk, producing the final IdentityHashMap for Pass1Result.
        val childrenChunks = new java.util.IdentityHashMap[Tasty.Symbol, Chunk[Tasty.Symbol]]()
        childrenByOwnerBuf.forEach { (owner, buf) =>
            discard(childrenChunks.put(owner, Chunk.from(buf.toSeq)))
        }

        Pass1Result(
            symbols = Chunk.from(allSymbols.tail), // exclude root
            addrMap = intMap,
            rootSymbol = root,
            parentsBySymbol = parentsBySymbol,
            childrenByOwner = childrenChunks,
            typeBySymbol = typeBySymbol,
            ownerBySymbol = ownerBySymbol,
            bodyDataByAddr = bodyDataByAddr,
            sectionBytes = sectionBytes,
            sectionOffset = sectionOffset,
            names = names,
            unresolvedIdToFqn = typeSession.unresolvedIdToFqn,
            annotationDecodeErrors = Chunk.from(typeSession.annotationDecodeErrors),
            annotationsBySymbol = annotationsBySymbol
        )
    end runPass1

    private def currentOwner(ownerStack: mutable.ArrayDeque[Tasty.Symbol]): Tasty.Symbol =
        ownerStack.last

    /** Walk all stats (top-level definitions) until position reaches `end`. Mirrors dotty indexStats.
      *
      * @param sectionBytes
      *   The raw AST section bytes (from view.allBytes); used by Pass C to build SymbolBody.
      * @param ownerBySymbol
      *   Accumulator: maps each symbol to its owner partial symbol; feeds Pass C ownerId.
      * @param bodyDataByAddr
      *   Accumulator: maps each symbol to (bodyStart, bodyEnd) bytes within sectionBytes.
      */
    private def walkStats(
        view: ByteView,
        end: Long,
        names: Array[Tasty.Name],
        sectionBytes: Array[Byte],
        sectionOffset: Int,
        attrs: FileAttributes,
        addrMap: mutable.HashMap[Int, Tasty.Symbol],
        allSymbols: mutable.ArrayBuffer[Tasty.Symbol],
        ownerStack: mutable.ArrayDeque[Tasty.Symbol],
        typeSession: TypeUnpickler.DecodeSession,
        parentsBySymbol: java.util.IdentityHashMap[Tasty.Symbol, Chunk[Tasty.Type]],
        typeBySymbol: java.util.IdentityHashMap[Tasty.Symbol, Tasty.Type],
        ownerBySymbol: java.util.IdentityHashMap[Tasty.Symbol, Tasty.Symbol],
        bodyDataByAddr: java.util.IdentityHashMap[Tasty.Symbol, (Int, Int)],
        annotationsBySymbol: java.util.IdentityHashMap[Tasty.Symbol, mutable.ArrayBuffer[Tasty.Annotation]]
    )(using Frame, AllowUnsafe): Unit =
        while view.position < end do
            val nodeAddr = view.positionInt
            val tag      = view.readByte() & 0xff
            tag match
                case TastyFormat.PACKAGE =>
                    val payloadEnd = view.readEnd()
                    // PACKAGE payload: Path TopLevelStat*
                    // Path is the package term reference: TERMREFpkg (cat2: tag+NameRef) or
                    // SELECT (cat4: tag+NameRef+qual) for nested packages (kyo.fixtures etc.).
                    // Decode the innermost simple name from the path.
                    val pkgName   = extractPackageName(view, names)
                    val owner     = currentOwner(ownerStack)
                    val bodyStart = view.positionInt
                    val sym       = InternalSymbol.makeSymbol(Tasty.SymbolKind.Package, Tasty.Flags.empty, pkgName)
                    ownerBySymbol.put(sym, owner)
                    bodyDataByAddr.put(sym, (bodyStart, Math.toIntExact(payloadEnd)))
                    addrMap(nodeAddr) = sym
                    allSymbols += sym
                    ownerStack.append(sym)
                    walkStats(
                        view,
                        payloadEnd,
                        names,
                        sectionBytes,
                        sectionOffset,
                        attrs,
                        addrMap,
                        allSymbols,
                        ownerStack,
                        typeSession,
                        parentsBySymbol,
                        typeBySymbol,
                        ownerBySymbol,
                        bodyDataByAddr,
                        annotationsBySymbol
                    )
                    discard(ownerStack.removeLast())
                    view.goto(payloadEnd)

                case TastyFormat.VALDEF =>
                    val payloadEnd  = view.readEnd()
                    val nameRef     = view.readNat()
                    val symName     = names(nameRef)
                    val owner       = currentOwner(ownerStack)
                    val payloadBody = view.position
                    // Decode the VALDEF signature type (first type node after NameRef).
                    // This populates typeSession.placeholders with any cross-file refs.
                    val valTpe               = decodeOneTypeIfPresent(view, payloadEnd, typeSession, sectionOffset)
                    val (flagBits, pendAnns) = scanForwardAndCollectFlags(view, payloadEnd, typeSession, sectionOffset, sectionBytes)
                    val kind                 = InternalSymbolKind.fromValdefFlags(flagBits)
                    val flags                = Tasty.Flags.fromBits(flagBits)
                    val sym                  = InternalSymbol.makeSymbol(kind, flags, symName)
                    ownerBySymbol.put(sym, owner)
                    bodyDataByAddr.put(sym, (Math.toIntExact(payloadBody), Math.toIntExact(payloadEnd)))
                    addrMap(nodeAddr) = sym
                    allSymbols += sym
                    valTpe match
                        case Present(t) => typeBySymbol.put(sym, t)
                        case Absent     => ()
                    if pendAnns.nonEmpty then
                        var annBuf = annotationsBySymbol.get(sym)
                        if annBuf == null then
                            annBuf = new mutable.ArrayBuffer[Tasty.Annotation]()
                            discard(annotationsBySymbol.put(sym, annBuf))
                        end if
                        annBuf ++= pendAnns
                    end if
                    view.goto(payloadEnd)

                case TastyFormat.DEFDEF =>
                    val payloadEnd          = view.readEnd()
                    val nameRef             = view.readNat()
                    val symName             = names(nameRef)
                    val owner               = currentOwner(ownerStack)
                    val payloadBody         = view.position
                    val (flagBits, defAnns) = scanForwardAndCollectFlags(view, payloadEnd, typeSession, sectionOffset, sectionBytes)
                    val flags               = Tasty.Flags.fromBits(flagBits)
                    val sym                 = InternalSymbol.makeSymbol(Tasty.SymbolKind.Method, flags, symName)
                    ownerBySymbol.put(sym, owner)
                    bodyDataByAddr.put(sym, (Math.toIntExact(payloadBody), Math.toIntExact(payloadEnd)))
                    addrMap(nodeAddr) = sym
                    allSymbols += sym
                    // Recurse into DEFDEF body to discover type params and value params.
                    // Use a forked sub-view of the payload body.
                    val innerView = view.subView(payloadBody, payloadEnd)
                    ownerStack.append(sym)
                    // F-A-005 fix: sync ownerStack and ownerAddrStack into typeSession so the THIS-type
                    // decode branch can find the enclosing class/trait/object when decoding the DEFDEF
                    // return type. ownerAddrStack stores the Phase-B addr-encoded id for Phase C remap.
                    typeSession.ownerStack.append(sym)
                    typeSession.ownerAddrStack.append(nodeAddr)
                    walkStats(
                        innerView,
                        payloadEnd,
                        names,
                        sectionBytes,
                        sectionOffset,
                        attrs,
                        addrMap,
                        allSymbols,
                        ownerStack,
                        typeSession,
                        parentsBySymbol,
                        typeBySymbol,
                        ownerBySymbol,
                        bodyDataByAddr,
                        annotationsBySymbol
                    )
                    discard(ownerStack.removeLast())
                    discard(typeSession.ownerStack.removeLast())
                    discard(typeSession.ownerAddrStack.removeLast())
                    if defAnns.nonEmpty then
                        var defAnnBuf = annotationsBySymbol.get(sym)
                        if defAnnBuf == null then
                            defAnnBuf = new mutable.ArrayBuffer[Tasty.Annotation]()
                            discard(annotationsBySymbol.put(sym, defAnnBuf))
                        end if
                        defAnnBuf ++= defAnns
                    end if
                    // Capture the DEFDEF return type by scanning a fresh sub-view:
                    // layout is TYPEPARAM* PARAM* returnType RHS? modifier*.
                    // We skip TYPEPARAM and PARAM nodes, then read one type node.
                    val returnTypeScanView = view.subView(payloadBody, payloadEnd)
                    val retTpe             = readDefDefReturnType(returnTypeScanView, payloadEnd, typeSession, sectionOffset)
                    retTpe match
                        case Present(t) => typeBySymbol.put(sym, t)
                        case Absent     => ()
                    view.goto(payloadEnd)

                case TastyFormat.TYPEDEF =>
                    // STEERING CRITICAL: peek for TEMPLATE tag after NameRef.
                    // Reference: dotty TreeUnpickler.readNewDef dispatches on nextByte for TEMPLATE.
                    val payloadEnd = view.readEnd()
                    val nameRef    = view.readNat()
                    val symName    = names(nameRef)
                    val owner      = currentOwner(ownerStack)

                    // Peek at the first byte after NameRef to determine class-like vs type-level.
                    val nextTag = view.peekByte(view.position) & 0xff

                    if nextTag == TastyFormat.TEMPLATE then
                        // Class-like TYPEDEF: consume TEMPLATE tag, read its payload bounds.
                        discard(view.readByte()) // consume TEMPLATE tag byte
                        val templatePayloadEnd = view.readEnd()
                        val templateBodyStart  = view.position
                        // Decode parent types inside TEMPLATE.
                        // TEMPLATE payload: TypeParam* Param* parent_Type* self_ValDef? Stat*
                        // Scan the template body with a fresh sub-view to call readTypeIntoSession
                        // on each parent_Type entry. TYPEPARAM/PARAM nodes (class type/term params)
                        // are skipped first; scanning stops at SELFDEF, VALDEF, DEFDEF, TYPEDEF, or
                        // any modifier tag (which signals the start of the stat section).
                        val parentScanView = view.subView(templateBodyStart, templatePayloadEnd)
                        val decodedParents = decodeTemplateParents(parentScanView, templatePayloadEnd, sectionOffset, typeSession)
                        // Advance the outer view past the TEMPLATE payload so that readModifiers
                        // reads modifiers from templatePayloadEnd to payloadEnd.
                        view.goto(templatePayloadEnd)
                        // Modifiers follow the TEMPLATE node.
                        val (flagBits, tmplAnns) = readModifiers(view, payloadEnd, typeSession, sectionOffset, sectionBytes)
                        val kind                 = InternalSymbolKind.fromTypedefTemplateFlags(flagBits)
                        val flags                = Tasty.Flags.fromBits(flagBits)
                        val sym                  = InternalSymbol.makeSymbol(kind, flags, symName)
                        ownerBySymbol.put(sym, owner)
                        bodyDataByAddr.put(sym, (Math.toIntExact(templateBodyStart), Math.toIntExact(templatePayloadEnd)))
                        addrMap(nodeAddr) = sym
                        allSymbols += sym
                        if tmplAnns.nonEmpty then
                            var tmplAnnBuf = annotationsBySymbol.get(sym)
                            if tmplAnnBuf == null then
                                tmplAnnBuf = new mutable.ArrayBuffer[Tasty.Annotation]()
                                discard(annotationsBySymbol.put(sym, tmplAnnBuf))
                            end if
                            tmplAnnBuf ++= tmplAnns
                        end if
                        // Record parent types for this class symbol (used by mergeResults for _parents assignment).
                        if decodedParents.nonEmpty then
                            discard(parentsBySymbol.put(sym, Chunk.from(decodedParents)))
                        // Walk template body to discover members (type params, constructor params, member defs).
                        val templateFork = view.subView(templateBodyStart, templatePayloadEnd)
                        ownerStack.append(sym)
                        // F-A-005 fix: sync class sym into typeSession.ownerStack and ownerAddrStack
                        // so THIS-type decode inside member DEFDEFs can find the enclosing class symbol
                        // and its Phase-B address for Phase C remapping.
                        typeSession.ownerStack.append(sym)
                        typeSession.ownerAddrStack.append(nodeAddr)
                        walkStats(
                            templateFork,
                            templatePayloadEnd,
                            names,
                            sectionBytes,
                            sectionOffset,
                            attrs,
                            addrMap,
                            allSymbols,
                            ownerStack,
                            typeSession,
                            parentsBySymbol,
                            typeBySymbol,
                            ownerBySymbol,
                            bodyDataByAddr,
                            annotationsBySymbol
                        )
                        discard(ownerStack.removeLast())
                        discard(typeSession.ownerStack.removeLast())
                        discard(typeSession.ownerAddrStack.removeLast())
                        // Class-like TYPEDEF: declaredType is Type.Named(sym) assigned in mergeResults.
                        view.goto(payloadEnd)
                    else
                        // Type-level TYPEDEF: decode the type body instead of skipping it.
                        val typeLevelTpe =
                            try Present(TypeUnpickler.readTypeIntoSession(view, typeSession, sectionOffset))
                            catch
                                case _: Exception =>
                                    // On error, skip to the end of the type body and proceed with modifiers.
                                    discard(view.readByte()) // consume the type sub-tree tag (already peeked)
                                    skipTreeBody(nextTag, view)
                                    Absent
                        val (flagBits, typeAnns) = readModifiers(view, payloadEnd, typeSession, sectionOffset, sectionBytes)
                        val kind                 = InternalSymbolKind.fromTypedefTypeFlagsAndBody(flagBits, nextTag)
                        val flags                = Tasty.Flags.fromBits(flagBits)
                        // Type-level TYPEDEF: no body to decode; bodyStart == bodyEnd == payloadEnd sentinel.
                        val sym = InternalSymbol.makeSymbol(kind, flags, symName)
                        ownerBySymbol.put(sym, owner)
                        addrMap(nodeAddr) = sym
                        allSymbols += sym
                        typeLevelTpe match
                            case Present(t) => typeBySymbol.put(sym, t)
                            case _          => ()
                        if typeAnns.nonEmpty then
                            var typeAnnBuf = annotationsBySymbol.get(sym)
                            if typeAnnBuf == null then
                                typeAnnBuf = new mutable.ArrayBuffer[Tasty.Annotation]()
                                discard(annotationsBySymbol.put(sym, typeAnnBuf))
                            end if
                            typeAnnBuf ++= typeAnns
                        end if
                        view.goto(payloadEnd)
                    end if

                case TastyFormat.TYPEPARAM =>
                    val payloadEnd = view.readEnd()
                    val nameRef    = view.readNat()
                    val symName    = names(nameRef)
                    val owner      = currentOwner(ownerStack)
                    // Decode type bounds (first type node after NameRef), if present.
                    val tpBounds           = decodeOneTypeIfPresent(view, payloadEnd, typeSession, sectionOffset)
                    val (flagBits, tpAnns) = readModifiers(view, payloadEnd, typeSession, sectionOffset, sectionBytes)
                    val flags              = Tasty.Flags.fromBits(flagBits)
                    val sym                = InternalSymbol.makeSymbol(Tasty.SymbolKind.TypeParam, flags, symName)
                    ownerBySymbol.put(sym, owner)
                    addrMap(nodeAddr) = sym
                    allSymbols += sym
                    tpBounds match
                        case Present(t) => typeBySymbol.put(sym, t)
                        case Absent     => ()
                    if tpAnns.nonEmpty then
                        var tpAnnBuf = annotationsBySymbol.get(sym)
                        if tpAnnBuf == null then
                            tpAnnBuf = new mutable.ArrayBuffer[Tasty.Annotation]()
                            discard(annotationsBySymbol.put(sym, tpAnnBuf))
                        end if
                        tpAnnBuf ++= tpAnns
                    end if
                    view.goto(payloadEnd)

                case TastyFormat.PARAM =>
                    val payloadEnd = view.readEnd()
                    val nameRef    = view.readNat()
                    val symName    = names(nameRef)
                    val owner      = currentOwner(ownerStack)
                    // Decode parameter type (first type node after NameRef), if present.
                    val paramTpe              = decodeOneTypeIfPresent(view, payloadEnd, typeSession, sectionOffset)
                    val (flagBits, paramAnns) = scanForwardAndCollectFlags(view, payloadEnd, typeSession, sectionOffset, sectionBytes)
                    val flags                 = Tasty.Flags.fromBits(flagBits)
                    val sym                   = InternalSymbol.makeSymbol(Tasty.SymbolKind.Parameter, flags, symName)
                    ownerBySymbol.put(sym, owner)
                    addrMap(nodeAddr) = sym
                    allSymbols += sym
                    paramTpe match
                        case Present(t) => typeBySymbol.put(sym, t)
                        case Absent     => ()
                    if paramAnns.nonEmpty then
                        var paramAnnBuf = annotationsBySymbol.get(sym)
                        if paramAnnBuf == null then
                            paramAnnBuf = new mutable.ArrayBuffer[Tasty.Annotation]()
                            discard(annotationsBySymbol.put(sym, paramAnnBuf))
                        end if
                        paramAnnBuf ++= paramAnns
                    end if
                    view.goto(payloadEnd)

                case TastyFormat.EMPTYCLAUSE | TastyFormat.SPLITCLAUSE =>
                    // Structural markers, no payload (category 1).
                    ()

                case other if other >= TastyFormat.firstLengthTreeTag =>
                    // Unknown category 5 node: skip payload entirely.
                    val end = view.readEnd()
                    view.goto(end)

                case other =>
                    // Categories 1-4: skip body according to category.
                    skipTreeBody(other, view)
            end match

    /** Decode one type node from `view` into `typeSession`, if a type node is present before `end`.
      *
      * Checks if the next byte is a type-tag (not a modifier tag). For TPT tags (type-position tree tags), dispatches to
      * TreeUnpickler.decodeTptAsType. For regular type tags, calls TypeUnpickler.readTypeIntoSession. If decoding fails (malformed type
      * tree), skips to `end` silently to avoid corrupting the outer walk.
      *
      * Returns the decoded type if present and successful, Absent otherwise.
      *
      * F-I-004 fix: TPT tags (IDENTtpt, APPLIEDtpt, TYPEBOUNDStpt, ANNOTATEDtpt, SELECTin, REFINEDtpt, LAMBDAtpt, MATCHtpt,
      * MATCHCASEtype) are wire-level tree nodes that carry a Type. Routing them to TypeUnpickler caused 47,996 unknown-tag warnings per load
      * because TypeUnpickler has no handlers for these tags. They must go to TreeUnpickler.decodeTptAsType.
      */
    private def decodeOneTypeIfPresent(
        view: ByteView,
        end: Long,
        typeSession: TypeUnpickler.DecodeSession,
        sectionOffset: Int = 0
    )(using
        Frame,
        AllowUnsafe
    ): Maybe[Tasty.Type] =
        if view.position < end then
            val nextTag = view.peekByte(view.position) & 0xff
            if isModifierTag(nextTag) then Absent
            else if isTreeTptTag(nextTag) then
                // F-I-004 fix: TPT tags route to TreeUnpickler.decodeTptAsType.
                try Present(TreeUnpickler.decodeTptAsType(view, typeSession, nextTag, sectionOffset))
                catch
                    case _: Exception =>
                        view.goto(end)
                        Absent
            else if isTermTagInTypePosition(nextTag) then
                // F-A2-001 fix: TERM tags (NEW=95, SELECT=112, SHAREDterm=60, TYPEAPPLY=137,
                // APPLY=136, CASEDEF=155, SINGLETONtpt=101, BYNAMEtpt=94) can appear in TYPE
                // position inside ANNOTATEDtype payloads, TEMPLATE parent expressions, and
                // INLINED-as-type contexts. Route to decodeTermTagInTypePosition instead of
                // TypeUnpickler.decodeTag which would throw TastyError.UnknownTagInPosition (Phase 2.04-strict).
                try Present(TreeUnpickler.decodeTermTagInTypePosition(view, typeSession, nextTag, sectionOffset))
                catch
                    case _: Exception =>
                        view.goto(end)
                        Absent
            else
                try Present(TypeUnpickler.readTypeIntoSession(view, typeSession, sectionOffset))
                catch
                    case _: Exception =>
                        view.goto(end)
                        Absent
            end if
        else Absent
    end decodeOneTypeIfPresent

    /** TASTy tags whose payload is a TREE (typed-tree position) that wraps a Type.
      *
      * These must route to TreeUnpickler.decodeTptAsType, not TypeUnpickler. Sourced from TastyFormat: IDENTtpt (111), APPLIEDtpt (162),
      * TYPEBOUNDStpt (164), ANNOTATEDtpt (154), SELECTin (176), REFINEDtpt (160), LAMBDAtpt (171), MATCHtpt (191), MATCHCASEtype (192).
      */
    private[reader] def isTreeTptTag(tag: Int): Boolean =
        tag == TastyFormat.IDENTtpt ||
            tag == TastyFormat.SELECTtpt ||
            tag == TastyFormat.APPLIEDtpt ||
            tag == TastyFormat.TYPEBOUNDStpt ||
            tag == TastyFormat.ANNOTATEDtpt ||
            tag == TastyFormat.SELECTin ||
            tag == TastyFormat.REFINEDtpt ||
            tag == TastyFormat.LAMBDAtpt ||
            tag == TastyFormat.MATCHtpt ||
            tag == TastyFormat.MATCHCASEtype
    end isTreeTptTag

    /** TASTy TERM tags that may legitimately appear in TYPE position inside ANNOTATEDtype, TEMPLATE parent expressions, and INLINED-as-type
      * contexts. Surfaced by F-A2-001 (probe-001.log) plus additional tags discovered during Phase 2.01 test runs: NEW=95, SELECT=112,
      * SHAREDterm=60, TYPEAPPLY=137, APPLY=136, CASEDEF=155, SINGLETONtpt=101, BYNAMEtpt=94, QUALTHIS=91, INLINED=147.
      * Routes to TreeUnpickler.decodeTermTagInTypePosition.
      *
      * SINGLETONtpt and BYNAMEtpt are TPT-flavored tags but were not included in isTreeTptTag from decoder-fidelity-1 Phase 03; they are
      * grouped here with the term-position tags for consistency with the F-A2-001 fix path. QUALTHIS and INLINED were discovered during
      * Phase 2.01 test runs (kyo-tasty classes contained them in type positions).
      */
    private[reader] def isTermTagInTypePosition(tag: Int): Boolean =
        tag == TastyFormat.NEW ||
            tag == TastyFormat.SELECT ||
            tag == TastyFormat.SHAREDterm ||
            tag == TastyFormat.TYPEAPPLY ||
            tag == TastyFormat.APPLY ||
            tag == TastyFormat.CASEDEF ||
            tag == TastyFormat.SINGLETONtpt ||
            tag == TastyFormat.BYNAMEtpt ||
            tag == TastyFormat.QUALTHIS ||
            tag == TastyFormat.INLINED
    end isTermTagInTypePosition

    /** Scan a DEFDEF payload sub-view to read the return type.
      *
      * DEFDEF layout (inside the payload): TYPEPARAM* PARAM* returnType RHS? modifier*
      *
      * F-A-001 fix: the DEFDEF return-type slot may contain a METHODtype or POLYtype lambda (for methods with params) or a plain type node
      * (for nullary methods). The existing decodeOneTypeIfPresent call correctly routes to TypeUnpickler.readTypeIntoSession for all tags,
      * including METHODtype and POLYtype. The METHODtype/POLYtype inline handlers in TypeUnpickler.decodeTag already produce
      * Type.TypeLambda(paramIds, resultType). Phase C's remapType previously did not recurse into TypeLambda, so inner Named(trackedNegId)
      * references were not resolved to final SymbolIds. The real fix is in ClasspathOrchestrator.remapType (now recurses into TypeLambda
      * and all other composite types), not in this function.
      *
      * Uses a fresh sub-view (returnTypeScanView) that is independent of the outer view, so the outer walk is not affected.
      *
      * Returns the decoded return type if found and decodeable, Absent otherwise. On any exception the Absent path is taken silently.
      */
    private def readDefDefReturnType(
        returnTypeScanView: ByteView,
        end: Long,
        typeSession: TypeUnpickler.DecodeSession,
        sectionOffset: Int
    )(using Frame, AllowUnsafe): Maybe[Tasty.Type] =
        try
            // Skip TYPEPARAM and PARAM nodes that precede the return type.
            var skip = true
            while skip && returnTypeScanView.position < end do
                val tag = returnTypeScanView.peekByte(returnTypeScanView.position) & 0xff
                if tag == TastyFormat.TYPEPARAM || tag == TastyFormat.PARAM || tag == TastyFormat.EMPTYCLAUSE || tag == TastyFormat.SPLITCLAUSE
                then
                    discard(returnTypeScanView.readByte()) // consume tag
                    if tag == TastyFormat.EMPTYCLAUSE || tag == TastyFormat.SPLITCLAUSE then
                        () // no payload
                    else
                        val nodeEnd = returnTypeScanView.readEnd()
                        returnTypeScanView.goto(nodeEnd)
                    end if
                else
                    skip = false
                end if
            end while
            // Read the return type node (the first non-param node). METHODtype / POLYtype are routed
            // through TypeUnpickler.readTypeIntoSession -> decodeTag which already handles both tags
            // and produces Type.TypeLambda(paramIds, resultType). Phase C remapType now recurses into
            // TypeLambda (F-A-001 fix) so inner Named(trackedNegId) refs are resolved properly.
            // sectionOffset is passed so that TYPEREFsymbol / PARAMtype binder lookups can convert
            // section-relative ASTRef values to the absolute addrMap / binderAddrMap keys.
            decodeOneTypeIfPresent(returnTypeScanView, end, typeSession, sectionOffset)
        catch
            case ex: Exception => Absent
    end readDefDefReturnType

    /** Scan the TEMPLATE body sub-view for parent_Type entries and call readTypeIntoSession on each.
      *
      * TEMPLATE grammar: TypeParam* TermParam* parent_Type* self_ValDef? Stat*
      *
      * Steps: skip TYPEPARAM and PARAM nodes (class's own type/term params), then decode each parent_Type by calling readTypeIntoSession.
      * Stops when encountering SELFDEF, VALDEF, DEFDEF, TYPEDEF, or any modifier tag (i.e. the stat section starts).
      *
      * Uses a fresh sub-view (parentScanView) that is independent of templateFork, so walkStats can still walk the same byte range from the
      * beginning.
      *
      * Returns the decoded parent types as a buffer. The caller records them into `parentsBySymbol` keyed by the class symbol. Parent types
      * may contain Named(SymbolId(-1)) for cross-file parents; these are resolved during Phase C finalizeMerge via fqnIndex lookup.
      */
    private def decodeTemplateParents(
        parentScanView: ByteView,
        end: Long,
        sectionOffset: Int = 0,
        typeSession: TypeUnpickler.DecodeSession
    )(using Frame, AllowUnsafe): mutable.ArrayBuffer[Tasty.Type] =
        val collected = new mutable.ArrayBuffer[Tasty.Type]()
        // Phase 1: skip leading TYPEPARAM and PARAM nodes (class own type/term params).
        var skipParams = true
        while skipParams && parentScanView.position < end do
            val tag = parentScanView.peekByte(parentScanView.position) & 0xff
            if tag == TastyFormat.TYPEPARAM || tag == TastyFormat.PARAM then
                discard(parentScanView.readByte()) // consume tag
                val nodeEnd = parentScanView.readEnd()
                parentScanView.goto(nodeEnd)
            else
                skipParams = false
            end if
        end while
        // Phase 2: decode parent_Type* entries.
        // Stop at SELFDEF (118), any definition tag (VALDEF=129, DEFDEF=130, TYPEDEF=131),
        // or any modifier tag (1-59, 98, 99, 173) which would indicate we overran.
        // Cross-file parents arrive as APPLY(SELECT(NEW(type_ref), <init>), args) term trees with
        // tag >= firstLengthTreeTag (128). We descend into APPLY nodes to extract the constructor
        // type reference so that buildSubclassIndex can correctly index cross-file parent edges.
        var scanParents = true
        while scanParents && parentScanView.position < end do
            val tag = parentScanView.peekByte(parentScanView.position) & 0xff
            if tag == TastyFormat.SELFDEF ||
                tag == TastyFormat.VALDEF ||
                tag == TastyFormat.DEFDEF ||
                tag == TastyFormat.TYPEDEF ||
                isModifierTag(tag)
            then
                scanParents = false
            else if tag == TastyFormat.APPLY && tag >= TastyFormat.firstLengthTreeTag then
                // APPLY-headed parent: consume tag + length, then decode the first sub-node
                // (the constructor call target) as a type reference.
                // F-A2-001 fix: the first child is typically SELECT(NEW(type_ref), <init>).
                // SELECT=112 is a term tag; route through decodeTermTagInTypePosition instead
                // of TypeUnpickler.readTypeIntoSession which has no handler for SELECT.
                try
                    discard(parentScanView.readByte()) // consume APPLY tag
                    val applyEnd = parentScanView.readEnd()
                    val childTag = parentScanView.peekByte(parentScanView.position) & 0xff
                    val decoded =
                        if isTermTagInTypePosition(childTag) then
                            TreeUnpickler.decodeTermTagInTypePosition(parentScanView, typeSession, childTag, sectionOffset)
                        else if isTreeTptTag(childTag) then
                            TreeUnpickler.decodeTptAsType(parentScanView, typeSession, childTag, sectionOffset)
                        else
                            TypeUnpickler.readTypeIntoSession(parentScanView, typeSession, sectionOffset)
                    collected += decoded
                    parentScanView.goto(applyEnd)
                catch
                    case _: Exception =>
                        // On decode error for APPLY descent, skip to end.
                        parentScanView.goto(end)
                        scanParents = false
                end try
            else
                // F-A2-001 fix: also guard direct (non-APPLY-headed) parent expressions.
                // A parent like `extends SomeTrait` may have a NEW or SELECT tag at the head.
                try
                    val parentTag = parentScanView.peekByte(parentScanView.position) & 0xff
                    val decoded =
                        if isTermTagInTypePosition(parentTag) then
                            TreeUnpickler.decodeTermTagInTypePosition(parentScanView, typeSession, parentTag, sectionOffset)
                        else if isTreeTptTag(parentTag) then
                            TreeUnpickler.decodeTptAsType(parentScanView, typeSession, parentTag, sectionOffset)
                        else
                            TypeUnpickler.readTypeIntoSession(parentScanView, typeSession, sectionOffset)
                    collected += decoded
                catch
                    case _: Exception =>
                        // On decode error, skip to end to avoid corrupting the scan.
                        parentScanView.goto(end)
                        scanParents = false
                end try
            end if
        end while
        collected
    end decodeTemplateParents

    /** Scan forward through sub-trees, collecting modifier flag bits and annotations when modifiers are reached.
      *
      * Skips all non-modifier trees (type trees, rhs body, params). Modifiers are: category-1 tags 1-59, PRIVATEqualified (98),
      * PROTECTEDqualified (99), ANNOTATION (173). Everything else is a sub-tree to skip.
      *
      * F-G-001 fix: ANNOTATION blocks are now decoded (tycon type + args bytes captured) rather than skipped.
      * The decoded annotations are returned alongside the flag bits for the caller to attribute to the owning symbol.
      *
      * STEERING CRITICAL: PRIVATEqualified (98) and PROTECTEDqualified (99) must skip their sub-AST.
      */
    private def scanForwardAndCollectFlags(
        view: ByteView,
        end: Long,
        typeSession: TypeUnpickler.DecodeSession,
        sectionOffset: Int,
        sectionBytes: Array[Byte]
    )(using Frame, AllowUnsafe): (Long, mutable.ArrayBuffer[Tasty.Annotation]) =
        var flagBits = 0L
        val annBuf   = new mutable.ArrayBuffer[Tasty.Annotation]()
        while view.position < end do
            val pos = view.position
            val tag = view.peekByte(pos) & 0xff
            if isModifierTag(tag) then
                discard(view.readByte()) // consume modifier tag
                tag match
                    case TastyFormat.ANNOTATION =>
                        val annEnd = view.readEnd()
                        decodeAnnotationBlock(view, annEnd, typeSession, sectionOffset, sectionBytes) match
                            case Present(ann) => annBuf += ann
                            case Absent       => ()
                    case TastyFormat.PRIVATEqualified =>
                        flagBits |= Tasty.Flag.bits(Tasty.Flag.Private)
                        skipTree(view)
                    case TastyFormat.PROTECTEDqualified =>
                        flagBits |= Tasty.Flag.bits(Tasty.Flag.Protected)
                        skipTree(view)
                    case modTag =>
                        flagBits |= InternalFlags.fromTastyModifierTag(modTag).bits
                end match
            else
                discard(view.readByte()) // consume tag
                skipTreeBody(tag, view)
            end if
        end while
        (flagBits, annBuf)
    end scanForwardAndCollectFlags

    /** Read modifier tags from the current position up to `end`, collecting annotations.
      *
      * Assumes cursor is already past any non-modifier sub-trees (TYPEDEF/TYPEPARAM arms call this after advancing past the TEMPLATE body).
      *
      * F-G-001 fix: ANNOTATION blocks are decoded (tycon + args) rather than skipped.
      *
      * STEERING CRITICAL: PRIVATEqualified (98) and PROTECTEDqualified (99) are category 3 (tag + sub-AST). Must skip their sub-AST.
      */
    private def readModifiers(
        view: ByteView,
        end: Long,
        typeSession: TypeUnpickler.DecodeSession,
        sectionOffset: Int,
        sectionBytes: Array[Byte]
    )(using Frame, AllowUnsafe): (Long, mutable.ArrayBuffer[Tasty.Annotation]) =
        var flagBits = 0L
        val annBuf   = new mutable.ArrayBuffer[Tasty.Annotation]()
        while view.position < end do
            val modTag = view.readByte() & 0xff
            modTag match
                case TastyFormat.ANNOTATION =>
                    val annEnd = view.readEnd()
                    decodeAnnotationBlock(view, annEnd, typeSession, sectionOffset, sectionBytes) match
                        case Present(ann) => annBuf += ann
                        case Absent       => ()
                case TastyFormat.PRIVATEqualified =>
                    flagBits |= Tasty.Flag.bits(Tasty.Flag.Private)
                    skipTree(view)
                case TastyFormat.PROTECTEDqualified =>
                    flagBits |= Tasty.Flag.bits(Tasty.Flag.Protected)
                    skipTree(view)
                case modTag if modTag >= 1 && modTag <= 59 =>
                    flagBits |= InternalFlags.fromTastyModifierTag(modTag).bits
                case _ =>
                    // Non-modifier encountered; stop collecting. This happens if we overran into a body.
                    view.goto(end)
            end match
        end while
        (flagBits, annBuf)
    end readModifiers

    /** Decode one ANNOTATION modifier block (tag already consumed, annEnd already read).
      *
      * Wire format after tag+Length: tycon_Type fullAnnotation_Term.
      * Decodes the tycon via TypeUnpickler; captures the remaining bytes as argsPickle for lazy Tree decode.
      * Returns the Annotation on success; Absent on decode failure (cursor advanced to annEnd regardless).
      *
      * F-G-001 fix (per Q-007 resolution).
      */
    /** Decode one ANNOTATION modifier block (tag already consumed, annEnd already read).
      *
      * Wire format after tag+Length: tycon_Type fullAnnotation_Term. Decodes the tycon using the same
      * TPT-tag dispatch as decodeOneTypeIfPresent (TPT tags route to TreeUnpickler.decodeTptAsType;
      * regular type tags route to TypeUnpickler.readTypeIntoSession). Skips the remaining term bytes.
      * Returns the Annotation on success; Absent on decode failure or when cursor overruns annEnd.
      *
      * F-G-001 fix (per Q-007 resolution).
      */
    private def decodeAnnotationBlock(
        view: ByteView,
        annEnd: Long,
        typeSession: TypeUnpickler.DecodeSession,
        sectionOffset: Int,
        sectionBytes: Array[Byte]
    )(using Frame, AllowUnsafe): Maybe[Tasty.Annotation] =
        if view.position >= annEnd then return Absent
        val nextTag = view.peekByte(view.position) & 0xff
        val tyconResult: Maybe[Tasty.Type] =
            try
                if isTreeTptTag(nextTag) then
                    // Annotation tycon emitted as a TPT tag (e.g. IDENTtpt, APPLIEDtpt).
                    Present(TreeUnpickler.decodeTptAsType(view, typeSession, nextTag, sectionOffset))
                else if isTermTagInTypePosition(nextTag) then
                    // F-A2-001 fix: annotation tycons can arrive as term tags in INLINED-as-type contexts.
                    Present(TreeUnpickler.decodeTermTagInTypePosition(view, typeSession, nextTag, sectionOffset))
                else if !isModifierTag(nextTag) then
                    Present(TypeUnpickler.readTypeIntoSession(view, typeSession, sectionOffset))
                else
                    view.goto(annEnd)
                    Absent
            catch
                case _: Exception =>
                    view.goto(annEnd)
                    Absent
        tyconResult match
            case Absent         => Absent
            case Present(tycon) =>
                // F-I-003 fix: for @scala.annotation.internal.Child[T] annotations, decode the
                // fullAnnotation_Term to extract the permitted-subclass TypeRef T. This is needed
                // by the permit-extraction loop in ClasspathOrchestrator.finalizeMerge (Phase 07).
                //
                // @Child[T] annotations have tycon = Type.TermRef(<pkg>, Name("Child")).
                // The fullAnnotation_Term is: APPLY Length NEW APPLIEDtpt Length <Child_tpt> <T_typeref>.
                // We decode the T_typeref and store it as Type.Applied(tycon, Chunk(T)).
                val enrichedTycon =
                    tycon match
                        case Tasty.Type.TermRef(_, name)
                            if { import Tasty.Name.asString; name.asString == "Child" } =>
                            if view.position < annEnd then
                                decodeChildAnnotationType(
                                    view,
                                    annEnd,
                                    tycon,
                                    typeSession,
                                    sectionOffset,
                                    sectionBytes
                                )
                            else tycon
                            end if
                        case Tasty.Type.TypeRef(_, name)
                            if { import Tasty.Name.asString; name.asString == "Child" } =>
                            // F-A-009: TYPEREF now emits TypeRef; handle @Child from TypeRef tycons too.
                            if view.position < annEnd then
                                decodeChildAnnotationType(
                                    view,
                                    annEnd,
                                    tycon,
                                    typeSession,
                                    sectionOffset,
                                    sectionBytes
                                )
                            else tycon
                            end if
                        case _ =>
                            tycon
                view.goto(annEnd)
                Present(Tasty.Annotation(enrichedTycon, Chunk.empty))
        end match
    end decodeAnnotationBlock

    /** Extract the type argument from a @Child[T] annotation fullAnnotation_Term.
      *
      * F-I-003: @Child[T] encodes the permitted-subclass TypeRef T inside:
      *   APPLY Length ( TYPEAPPLY Length ( constructor_ref  T_typeref ) )
      * This helper navigates the APPLY > TYPEAPPLY wrapper, skips the constructor_ref (the Child
      * constructor reference), and decodes the T_typeref. On success returns
      * `Type.Applied(tycon, Chunk(T))` which Phase 07's finalizeMerge can pattern-match.
      * On any failure returns the original tycon (graceful degradation, no sentinel injection).
      */
    private def decodeChildAnnotationType(
        view: ByteView,
        annEnd: Long,
        tycon: Tasty.Type,
        typeSession: TypeUnpickler.DecodeSession,
        sectionOffset: Int,
        sectionBytes: Array[Byte]
    )(using Frame, AllowUnsafe): Tasty.Type =
        val savedPos = view.position
        try
            // Expect APPLY (tag 136)
            if (view.peekByte(view.position) & 0xff) != TastyFormat.APPLY then return tycon
            val _        = view.readByte()
            val applyEnd = view.readEnd()
            if view.position >= applyEnd then return tycon
            val innerTag = view.peekByte(view.position) & 0xff
            // @Child[T] encodes as: APPLY(TYPEAPPLY(fn T_ref)) where T_ref is the subclass type.
            // TYPEAPPLY = 137: tag + Length + fn_Term + type_arg_Term+.
            if innerTag != TastyFormat.TYPEAPPLY then return tycon
            val _         = view.readByte()
            val tapplyEnd = view.readEnd()
            if view.position >= tapplyEnd then return tycon
            // Skip the fn_Term (the constructor reference for Child).
            skipTree(view)
            if view.position >= tapplyEnd then return tycon
            // Decode the first type argument: the permitted-subclass TypeRef T.
            // Use readTypeIntoSessionWithBytes so that SHAREDtype cache misses can re-decode
            // from sectionBytes rather than falling back to the unresolved sentinel.
            val typeArgTag = view.peekByte(view.position) & 0xff
            val subT =
                if isTreeTptTag(typeArgTag) then
                    TreeUnpickler.decodeTptAsType(view, typeSession, typeArgTag, sectionOffset)
                else if isTermTagInTypePosition(typeArgTag) then
                    // F-A2-001 fix: term tags in type-argument position of @Child annotations.
                    TreeUnpickler.decodeTermTagInTypePosition(view, typeSession, typeArgTag, sectionOffset)
                else
                    TypeUnpickler.readTypeIntoSessionWithBytes(view, typeSession, sectionBytes, sectionOffset)
            Tasty.Type.Applied(tycon, kyo.Chunk(subT))
        catch
            case _: Exception =>
                view.goto(savedPos)
                tycon
        end try
    end decodeChildAnnotationType

    private def isModifierTag(tag: Int): Boolean =
        (tag >= 1 && tag <= 59) ||
            tag == TastyFormat.PRIVATEqualified ||
            tag == TastyFormat.PROTECTEDqualified ||
            tag == TastyFormat.ANNOTATION

    /** Skip exactly one tree node from the current position (tag not yet consumed). */
    private def skipTree(view: ByteView)(using AllowUnsafe): Unit =
        val tag = (view.readByte(): Int) & 0xff
        skipTreeBody(tag, view)

    /** Skip the body of a tree node whose tag byte has already been consumed.
      *
      * Category rules (from TastyFormat): 1 (1-59): tag only; 2 (60-89): tag + Nat; 3 (90-109): tag + sub-AST; 4 (110-127): tag + Nat +
      * sub-AST; 5 (128-255): tag + Length + payload.
      */
    private def skipTreeBody(tag: Int, view: ByteView)(using AllowUnsafe): Unit =
        if tag >= TastyFormat.firstLengthTreeTag then
            val end = view.readEnd()
            view.goto(end)
        else if tag >= 110 && tag <= 127 then
            discard(view.readNat())
            skipTree(view)
        else if tag >= 90 && tag <= 109 then
            skipTree(view)
        else if tag >= 60 && tag <= 89 then
            discard(view.readNat())
        // else category 1: nothing to skip

    /** Extract the full dotted package name from a PACKAGE path.
      *
      * A PACKAGE path encodes the package name as a term reference chain:
      *   - `TERMREFpkg nameRef` (category 2): simple package name `names(nameRef)`.
      *   - `SELECT nameRef qual` (category 4): nested selection `qual.names(nameRef)`. The qualifier is another path node.
      *   - other: fallback, consume and return empty name.
      *
      * This method recursively walks the path chain to build the full dotted name (e.g. "kyo.fixtures" from
      * `SELECT(nameRef=fixtures, TERMREFpkg(nameRef=kyo))`).
      */
    private def extractPackageName(view: ByteView, names: Array[Tasty.Name])(using AllowUnsafe): Tasty.Name =
        val segments = new mutable.ArrayBuffer[String]()
        extractPackagePathSegments(view, names, segments)
        Tasty.Name(segments.mkString("."))
    end extractPackageName

    /** Recursively read a package path, prepending each segment to `segments`. */
    private def extractPackagePathSegments(view: ByteView, names: Array[Tasty.Name], segments: mutable.ArrayBuffer[String])(using
        AllowUnsafe
    ): Unit =
        val pathTag = view.readByte() & 0xff
        pathTag match
            case TastyFormat.TERMREFpkg =>
                // Leaf: tag + NameRef (category 2).
                val nameRef = view.readNat()
                val name    = names(nameRef).asString
                if name.nonEmpty then segments.prepend(name)
            case TastyFormat.SELECT | TastyFormat.SELECTtpt =>
                // Nested: tag + NameRef + qual sub-tree (category 4).
                // NameRef is the current (rightmost) segment; qual is the parent path.
                val nameRef = view.readNat()
                val name    = names(nameRef).asString
                // Recurse into qual first to collect parent segments.
                extractPackagePathSegments(view, names, segments)
                if name.nonEmpty then segments.append(name)
            case other =>
                // Unknown path form: skip body and leave segments as-is.
                skipTreeBody(other, view)
        end match
    end extractPackagePathSegments

end AstUnpickler
