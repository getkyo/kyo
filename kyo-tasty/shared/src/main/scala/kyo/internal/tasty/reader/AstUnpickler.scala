package kyo.internal.tasty.reader

import kyo.*
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.symbol.Flags as InternalFlags
import kyo.internal.tasty.symbol.LoadingSymbol
import kyo.internal.tasty.symbol.Symbol as InternalSymbol
import kyo.internal.tasty.symbol.SymbolKind
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
  * TYPEDEF discrimination: peek for TEMPLATE (156) tag after NameRef before reading modifiers.
  * Reference: dotty TreeUnpickler.readNewDef, which dispatches on the next tag to decide class vs type alias.
  *
  * Qualified-modifier sub-tree skip: PRIVATEqualified (98) and PROTECTEDqualified (99) are category 3
  * (tag + sub-AST). The modifier loop skips their sub-AST via skipTree(). Failing to do this corrupts the cursor.
  *
  * TypeUnpickler resolves cross-file type references using unique negative SymbolIds (< -1) tracked in
  * session.unresolvedIdToFullName. Phase C finalizeMerge resolves these via fullNameIndex. Unresolvable entries
  * are filtered out at the finalizeMerge boundary; no Named(SymbolId(-1)) sentinel survives in produced ADTs.
  */
object AstUnpickler:

    /** Result of pass 1.
      *
      * @param symbols
      *   All symbols discovered during the walk, excluding the synthetic root sentinel.
      * @param addrMap
      *   Map from TASTy byte address to symbol, for cross-symbol type references.
      * @param placeholders
      *   (unused; always Chunk.empty)
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
      * @param bodyDataByAddr
      *   Map from TASTy byte address to (bodyStart, bodyEnd) for symbols that have a body. File-level body data.
      * @param sectionBytes
      *   The raw AST section bytes for this file (shared across all symbols). For Pass C SymbolBody construction.
      * @param sectionOffset
      *   Absolute byte offset where the AST section starts. For SymbolBody.sectionOffset.
      */
    /** Fields `addrMap`, `parentsBySymbol`, `childrenByOwner`, and `typeBySymbol` use LongMap keyed on
      * LoadingSymbol.Materialising.id. Each Materialising instance has a unique id assigned at construction
      * (local per-file counter starting at 0), so LongMap keys never collide. The `Pass1Result` is produced by a
      * single decoder fiber and consumed by the single-threaded merger fiber after the channel put/take provides
      * a happens-before edge. No concurrent access occurs.
      */
    final case class Pass1Result(
        symbols: Chunk[LoadingSymbol.Materialising],
        addrMap: IntMap[LoadingSymbol.Materialising],
        rootSymbol: LoadingSymbol.Materialising,
        parentsBySymbol: mutable.LongMap[Chunk[Tasty.Type]],
        childrenByOwner: mutable.LongMap[Chunk[LoadingSymbol.Materialising]],
        typeBySymbol: mutable.LongMap[Tasty.Type],
        ownerBySymbol: mutable.LongMap[LoadingSymbol.Materialising],
        bodyDataByAddr: mutable.LongMap[(Int, Int)],
        sectionBytes: Array[Byte],
        sectionOffset: Int,
        names: Array[Tasty.Name],
        /** Cross-file fully-qualified name -> unique negative SymbolId mappings accumulated by TypeUnpickler during the per-file decode pass. The single-threaded
          * merge pass uses this to resolve Named(SymbolId(negId)) parent types to final SymbolIds via fullNameIndex.
          */
        unresolvedIdToFullName: mutable.HashMap[Int, String],
        /** Errors from eager annotation arg decodes in ANNOTATEDtype. Flows into FileResult.errors via ClasspathOrchestrator. */
        annotationDecodeErrors: Chunk[TastyError],
        /** Per-symbol annotation list decoded from ANNOTATION modifier blocks. Populated by readModifiers and scanForwardAndCollectFlags.
          * Flows through FileResult into ClasspathOrchestrator.finalizeMerge where descs(idx).annotations is set.
          */
        annotationsBySymbol: mutable.LongMap[mutable.ArrayBuffer[Tasty.Annotation]]
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
        arena: TypeArena,
        nextGlobalId: Maybe[() => Int] = Maybe.Absent
    )(using frame: Frame): Pass1Result < (Sync & Abort[TastyError]) =
        Sync.Unsafe.defer {
            try Right(runPass1(view, names, attrs, arena, nextGlobalId)(using frame, summon[AllowUnsafe]))
            catch
                case ex: ArrayIndexOutOfBoundsException =>
                    Left(TastyError.MalformedSection("ASTs", s"unexpected end: ${ex.getMessage}", view.position))
                case ex: java.lang.Error
                    if ex.getCause != null && ex.getCause.isInstanceOf[ArrayIndexOutOfBoundsException] =>
                    Left(TastyError.MalformedSection("ASTs", s"unexpected end: ${ex.getCause.getMessage}", view.position))
                case ex: Exception =>
                    Left(TastyError.MalformedSection("ASTs", s"parse error: ${ex.getMessage}", view.position))
        }.map {
            case Right(r)  => r
            case Left(err) => Abort.fail(err)
        }
    end readPass1

    /** Result-returning sibling of `readPass1` for use inside an unsafe-tier Result-flow.
      *
      * Mirrors the exception-to-error translation in `readPass1`, returning `Result[TastyError, Pass1Result]`
      * synchronously without the outer `Sync.Unsafe.defer` wrapping.
      */
    private[kyo] def readPass1Unsafe(
        view: ByteView,
        names: Array[Tasty.Name],
        attrs: FileAttributes,
        arena: TypeArena,
        nextGlobalId: Maybe[() => Int] = Maybe.Absent
    )(using frame: Frame, allow: AllowUnsafe): Result[TastyError, Pass1Result] =
        try Result.Success(runPass1(view, names, attrs, arena, nextGlobalId))
        catch
            case ex: ArrayIndexOutOfBoundsException =>
                Result.Failure(TastyError.MalformedSection("ASTs", s"unexpected end: ${ex.getMessage}", view.position))
            case ex: java.lang.Error
                if ex.getCause != null && ex.getCause.isInstanceOf[ArrayIndexOutOfBoundsException] =>
                Result.Failure(TastyError.MalformedSection("ASTs", s"unexpected end: ${ex.getCause.getMessage}", view.position))
            case ex: Exception =>
                Result.Failure(TastyError.MalformedSection("ASTs", s"parse error: ${ex.getMessage}", view.position))
    end readPass1Unsafe

    private def runPass1(
        view: ByteView,
        names: Array[Tasty.Name],
        attrs: FileAttributes,
        arena: TypeArena,
        nextGlobalId: Maybe[() => Int] = Maybe.Absent
    )(using Frame, AllowUnsafe): Pass1Result =
        val addrMap    = new mutable.HashMap[Int, LoadingSymbol.Materialising]()
        val allSymbols = new mutable.ArrayBuffer[LoadingSymbol.Materialising]()
        val ownerStack = new mutable.ArrayDeque[LoadingSymbol.Materialising]()
        // LongMap keyed on LoadingSymbol.Materialising.id (unique per-instance integer); cross-platform.
        val parentsBySymbol = mutable.LongMap.empty[Chunk[Tasty.Type]]
        val typeBySymbol    = mutable.LongMap.empty[Tasty.Type]
        // ownerBySymbol and bodyDataByAddr track per-symbol data consumed by Pass C to build
        // ownerId and body fields on the final immutable Symbols.
        val ownerBySymbol  = mutable.LongMap.empty[LoadingSymbol.Materialising]
        val bodyDataByAddr = mutable.LongMap.empty[(Int, Int)]
        // Annotation accumulator threaded through walkStats.
        val annotationsBySymbol = mutable.LongMap.empty[mutable.ArrayBuffer[Tasty.Annotation]]
        // Id provider: use the external global counter when provided (ClasspathOrchestrator passes
        // an AtomicInt.Unsafe.getAndIncrement to ensure globally unique ids across concurrent decode
        // sessions). Fall back to a local per-file counter for callers that don't need global uniqueness
        // (e.g., unit tests that decode a single file).
        val nextId: () => Int =
            nextGlobalId match
                case Maybe.Present(f) => f
                case Maybe.Absent =>
                    var idCounter = 0
                    () =>
                        val id = idCounter
                        idCounter += 1
                        id

        // Synthetic root: a Package symbol with empty name; owns itself (self-referential sentinel).
        val rootName: Tasty.Name = Tasty.Name("")
        val root = InternalSymbol.makeSymbol(id = nextId(), kind = SymbolKind.Package, flags = Tasty.Flags.empty, name = rootName)
        ownerStack.append(root)
        allSymbols += root

        val sectionOffset = view.positionInt
        val sectionEnd    = view.position + view.remaining
        val sectionBytes  = view.allBytes
        // Collect symbols. The typeSession holds the live addrMap so type decode can find
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
            annotationsBySymbol,
            nextId
        )

        // Convert the mutable addrMap to an immutable IntMap once, after walkStats completes.
        // IntMap uses primitive Int keys; no autoboxing on .get(Int) lookups.
        val intMap = IntMap.from(addrMap.iterator)

        // Build childrenByOwner: group all non-root symbols by their owner (from ownerBySymbol).
        // LongMap keyed on owner.id (unique per-instance).
        val childrenByOwnerBuf = mutable.LongMap.empty[mutable.ArrayBuffer[LoadingSymbol.Materialising]]
        for symbol <- allSymbols.tail do // skip root
            ownerBySymbol.get(symbol.id.toLong) match
                case Some(owner) =>
                    val accumulator =
                        childrenByOwnerBuf.getOrElseUpdate(owner.id.toLong, new mutable.ArrayBuffer[LoadingSymbol.Materialising]())
                    accumulator += symbol
                case None => ()
        end for

        // Convert ArrayBuffer values to Chunk, producing the final LongMap for Pass1Result.
        val childrenChunks = mutable.LongMap.empty[Chunk[LoadingSymbol.Materialising]]
        childrenByOwnerBuf.foreach { case (ownerId, accumulator) =>
            childrenChunks(ownerId) = Chunk.from(accumulator.toSeq)
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
            unresolvedIdToFullName = typeSession.unresolvedIdToFullName,
            annotationDecodeErrors = Chunk.from(typeSession.annotationDecodeErrors),
            annotationsBySymbol = annotationsBySymbol
        )
    end runPass1

    private def currentOwner(ownerStack: mutable.ArrayDeque[LoadingSymbol.Materialising]): LoadingSymbol.Materialising =
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
        addrMap: mutable.HashMap[Int, LoadingSymbol.Materialising],
        allSymbols: mutable.ArrayBuffer[LoadingSymbol.Materialising],
        ownerStack: mutable.ArrayDeque[LoadingSymbol.Materialising],
        typeSession: TypeUnpickler.DecodeSession,
        parentsBySymbol: mutable.LongMap[Chunk[Tasty.Type]],
        typeBySymbol: mutable.LongMap[Tasty.Type],
        ownerBySymbol: mutable.LongMap[LoadingSymbol.Materialising],
        bodyDataByAddr: mutable.LongMap[(Int, Int)],
        annotationsBySymbol: mutable.LongMap[mutable.ArrayBuffer[Tasty.Annotation]],
        nextId: () => Int
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
                    val symbol =
                        InternalSymbol.makeSymbol(id = nextId(), kind = SymbolKind.Package, flags = Tasty.Flags.empty, name = pkgName)
                    ownerBySymbol(symbol.id.toLong) = owner
                    bodyDataByAddr(symbol.id.toLong) = (bodyStart, Math.toIntExact(payloadEnd))
                    addrMap(nodeAddr) = symbol
                    allSymbols += symbol
                    ownerStack.append(symbol)
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
                        annotationsBySymbol,
                        nextId
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
                    val symbol               = InternalSymbol.makeSymbol(id = nextId(), kind = kind, flags = flags, name = symName)
                    ownerBySymbol(symbol.id.toLong) = owner
                    bodyDataByAddr(symbol.id.toLong) = (Math.toIntExact(payloadBody), Math.toIntExact(payloadEnd))
                    addrMap(nodeAddr) = symbol
                    allSymbols += symbol
                    valTpe match
                        case Present(t) => typeBySymbol(symbol.id.toLong) = t
                        case Absent     => ()
                    if pendAnns.nonEmpty then
                        val annBuf = annotationsBySymbol.getOrElseUpdate(symbol.id.toLong, new mutable.ArrayBuffer[Tasty.Annotation]())
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
                    val symbol = InternalSymbol.makeSymbol(id = nextId(), kind = SymbolKind.Method, flags = flags, name = symName)
                    ownerBySymbol(symbol.id.toLong) = owner
                    bodyDataByAddr(symbol.id.toLong) = (Math.toIntExact(payloadBody), Math.toIntExact(payloadEnd))
                    addrMap(nodeAddr) = symbol
                    allSymbols += symbol
                    // Pre-scan the DEFDEF payload to record parameter-list boundaries. Dotty emits PARAM
                    // nodes partitioned by EMPTYCLAUSE / SPLITCLAUSE markers; the partition shape encodes
                    // the source-order parameter-list grouping. This pass records each PARAM node's absolute
                    // byte address (nodeAddr). After walkStats assigns real symbol ids to those nodes via
                    // addrMap, the addresses are resolved to symbol ids below.
                    // Carrier decision: nodeAddr-keyed partition stored temporarily in symbol.paramListIds as
                    // a Chunk[Chunk[Int]] of raw addresses. The addrMap post-resolution step (after
                    // walkStats) replaces each address with the assigned symbol id in a single pass.
                    // This avoids mutating paramListIds during the PARAM arm walk and keeps the per-DEFDEF
                    // logic entirely local to this arm. Runs single-fibered inside the existing AllowUnsafe scope.
                    val partitionScan   = view.subView(payloadBody, payloadEnd)
                    val paramAddrGroups = recordParamListPartitions(partitionScan, payloadEnd)
                    // Recurse into DEFDEF body to discover type params and value params.
                    // Use a forked sub-view of the payload body.
                    val innerView = view.subView(payloadBody, payloadEnd)
                    ownerStack.append(symbol)
                    // Sync ownerStack and ownerAddrStack into typeSession so the THIS-type
                    // decode branch can find the enclosing class/trait/object when decoding the DEFDEF
                    // return type. ownerAddrStack stores the Phase-B address-encoded id for Phase C remap.
                    typeSession.ownerStack.append(symbol)
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
                        annotationsBySymbol,
                        nextId
                    )
                    discard(ownerStack.removeLast())
                    discard(typeSession.ownerStack.removeLast())
                    discard(typeSession.ownerAddrStack.removeLast())
                    // Resolve the pre-scanned PARAM nodeAddrs to symbol ids via addrMap. Each address was
                    // recorded before walkStats; now addrMap has been populated by the PARAM arms inside
                    // walkStats so each address resolves to the assigned LoadingSymbol.Materialising.
                    // Type params do not appear in paramAddrGroups (they were skipped by
                    // recordParamListPartitions), so no TypeParam ids enter paramListIds.
                    if paramAddrGroups.nonEmpty then
                        symbol.paramListIds = paramAddrGroups.map { inner =>
                            inner.flatMap { address =>
                                addrMap.get(address) match
                                    case Some(paramSym) => Chunk(paramSym.id)
                                    case None           => Chunk.empty
                            }
                        }
                    end if
                    if defAnns.nonEmpty then
                        val defAnnBuf = annotationsBySymbol.getOrElseUpdate(symbol.id.toLong, new mutable.ArrayBuffer[Tasty.Annotation]())
                        defAnnBuf ++= defAnns
                    end if
                    // Capture the DEFDEF return type by scanning a fresh sub-view:
                    // layout is TYPEPARAM* PARAM* returnType RHS? modifier*.
                    // We skip TYPEPARAM and PARAM nodes, then read one type node.
                    val returnTypeScanView = view.subView(payloadBody, payloadEnd)
                    val retTpe             = readDefDefReturnType(returnTypeScanView, payloadEnd, typeSession, sectionOffset)
                    retTpe match
                        case Present(t) => typeBySymbol(symbol.id.toLong) = t
                        case Absent     => ()
                    view.goto(payloadEnd)

                case TastyFormat.TYPEDEF =>
                    // Peek for TEMPLATE tag after NameRef.
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
                        val symbol               = InternalSymbol.makeSymbol(id = nextId(), kind = kind, flags = flags, name = symName)
                        ownerBySymbol(symbol.id.toLong) = owner
                        bodyDataByAddr(symbol.id.toLong) = (Math.toIntExact(templateBodyStart), Math.toIntExact(templatePayloadEnd))
                        addrMap(nodeAddr) = symbol
                        allSymbols += symbol
                        if tmplAnns.nonEmpty then
                            val tmplAnnBuf =
                                annotationsBySymbol.getOrElseUpdate(symbol.id.toLong, new mutable.ArrayBuffer[Tasty.Annotation]())
                            tmplAnnBuf ++= tmplAnns
                        end if
                        // Record parent types for this class symbol (used by mergeResults for _parents assignment).
                        if decodedParents.nonEmpty then
                            parentsBySymbol(symbol.id.toLong) = Chunk.from(decodedParents)
                        // Walk template body to discover members (type params, constructor params, member defs).
                        val templateFork = view.subView(templateBodyStart, templatePayloadEnd)
                        ownerStack.append(symbol)
                        // Sync class symbol into typeSession.ownerStack and ownerAddrStack
                        // so THIS-type decode inside member DEFDEFs can find the enclosing class symbol
                        // and its Phase-B address for Phase C remapping.
                        typeSession.ownerStack.append(symbol)
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
                            annotationsBySymbol,
                            nextId
                        )
                        discard(ownerStack.removeLast())
                        discard(typeSession.ownerStack.removeLast())
                        discard(typeSession.ownerAddrStack.removeLast())
                        // Class-like TYPEDEF: declaredType is Type.Named(symbol) assigned in mergeResults.
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
                        val symbol = InternalSymbol.makeSymbol(id = nextId(), kind = kind, flags = flags, name = symName)
                        ownerBySymbol(symbol.id.toLong) = owner
                        addrMap(nodeAddr) = symbol
                        allSymbols += symbol
                        typeLevelTpe match
                            case Present(t) => typeBySymbol(symbol.id.toLong) = t
                            case Absent     => ()
                        if typeAnns.nonEmpty then
                            val typeAnnBuf =
                                annotationsBySymbol.getOrElseUpdate(symbol.id.toLong, new mutable.ArrayBuffer[Tasty.Annotation]())
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
                    val symbol = InternalSymbol.makeSymbol(id = nextId(), kind = SymbolKind.TypeParam, flags = flags, name = symName)
                    ownerBySymbol(symbol.id.toLong) = owner
                    addrMap(nodeAddr) = symbol
                    allSymbols += symbol
                    tpBounds match
                        case Present(t) => typeBySymbol(symbol.id.toLong) = t
                        case Absent     => ()
                    if tpAnns.nonEmpty then
                        val tpAnnBuf = annotationsBySymbol.getOrElseUpdate(symbol.id.toLong, new mutable.ArrayBuffer[Tasty.Annotation]())
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
                    val symbol = InternalSymbol.makeSymbol(id = nextId(), kind = SymbolKind.Parameter, flags = flags, name = symName)
                    ownerBySymbol(symbol.id.toLong) = owner
                    addrMap(nodeAddr) = symbol
                    allSymbols += symbol
                    paramTpe match
                        case Present(t) => typeBySymbol(symbol.id.toLong) = t
                        case Absent     => ()
                    if paramAnns.nonEmpty then
                        val paramAnnBuf = annotationsBySymbol.getOrElseUpdate(symbol.id.toLong, new mutable.ArrayBuffer[Tasty.Annotation]())
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
      * TPT tags (IDENTtpt, APPLIEDtpt, TYPEBOUNDStpt, ANNOTATEDtpt, SELECTin, REFINEDtpt, LAMBDAtpt, MATCHtpt,
      * MATCHCASEtype) are wire-level tree nodes that carry a Type. They must go to TreeUnpickler.decodeTptAsType, not TypeUnpickler.
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
                // TPT tags route to TreeUnpickler.decodeTptAsType.
                try Present(TreeUnpickler.decodeTptAsType(view, typeSession, nextTag, sectionOffset))
                catch
                    case _: Exception =>
                        view.goto(end)
                        Absent
            else if isTermTagInTypePosition(nextTag) then
                // TERM tags (NEW=95, SELECT=112, SHAREDterm=60, TYPEAPPLY=137,
                // APPLY=136, CASEDEF=155, SINGLETONtpt=101, BYNAMEtpt=94) can appear in TYPE
                // position inside ANNOTATEDtype payloads, TEMPLATE parent expressions, and
                // INLINED-as-type contexts. Route to decodeTermTagInTypePosition instead of
                // TypeUnpickler.decodeTag which would throw TastyError.UnknownTagInPosition.
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
      * contexts: NEW=95, SELECT=112, SHAREDterm=60, TYPEAPPLY=137, APPLY=136, CASEDEF=155, SINGLETONtpt=101, BYNAMEtpt=94, QUALTHIS=91,
      * INLINED=147. Routes to TreeUnpickler.decodeTermTagInTypePosition.
      *
      * SINGLETONtpt and BYNAMEtpt are TPT-flavored tags; they are grouped here with the term-position tags because they appear in type
      * positions via the same dispatch path.
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
      * The DEFDEF return-type slot may contain a METHODtype or POLYtype lambda (for methods with params) or a plain type node
      * (for nullary methods). decodeOneTypeIfPresent routes to TypeUnpickler.readTypeIntoSession for all tags, including METHODtype and
      * POLYtype. The METHODtype/POLYtype handlers in TypeUnpickler.decodeTag produce Type.TypeLambda(paramIds, resultType).
      * ClasspathOrchestrator.remapType recurses into TypeLambda so inner Named(trackedNegId) references are resolved to final SymbolIds.
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
            // and produces Type.TypeLambda(paramIds, resultType). Phase C remapType recurses into
            // TypeLambda so inner Named(trackedNegId) refs are resolved properly.
            // sectionOffset is passed so that TYPEREFsymbol / PARAMtype binder lookups can convert
            // section-relative ASTRef values to the absolute addrMap / binderAddrMap keys.
            decodeOneTypeIfPresent(returnTypeScanView, end, typeSession, sectionOffset)
        catch
            case ex: Exception => Absent
    end readDefDefReturnType

    /** Pre-scan a DEFDEF payload to record parameter-list partition boundaries.
      *
      * DEFDEF payload layout (per dotty TASTy emission): TYPEPARAM* (PARAM | EMPTYCLAUSE | SPLITCLAUSE)* returnType RHS?.
      *
      * TYPEPARAM children belong to typeParamIds (handled separately) and are skipped here. Each PARAM node's absolute
      * byte address is appended to the current inner group. EMPTYCLAUSE or SPLITCLAUSE closes the current inner group and
      * opens a new one. Scanning stops when any tag other than TYPEPARAM, PARAM, EMPTYCLAUSE, or SPLITCLAUSE is seen (the
      * returnType or RHS region begins there).
      *
      * The returned Chunk[Chunk[Int]] contains absolute byte addresses (nodeAddrs) for each PARAM node, grouped by parameter
      * list. The caller resolves these addresses to symbol ids via addrMap after walkStats populates it.
      *
      * Shape contract:
      *   - No parameter lists at all (e.g. `def name: T`): Chunk.empty
      *   - One empty clause `def foo(): T`: Chunk(Chunk.empty) with the empty inner from the EMPTYCLAUSE marker
      *   - Multi-clause `def curry(a: Int)(b: Int)`: Chunk(Chunk(addrA), Chunk(addrB))
      *
      * Does not mutate addrMap, allSymbols, or any shared accumulator; reads tag bytes only.
      */
    private def recordParamListPartitions(
        scanView: ByteView,
        payloadEnd: Long
    )(using AllowUnsafe): Chunk[Chunk[Int]] =
        val outer      = new mutable.ArrayBuffer[Chunk[Int]]()
        var inner      = new mutable.ArrayBuffer[Int]()
        var inParamRgn = false
        var done       = false
        while !done && scanView.position < payloadEnd do
            val tag = scanView.peekByte(scanView.position) & 0xff
            tag match
                case TastyFormat.TYPEPARAM =>
                    // Skip; type params go into typeParamIds, not paramListIds.
                    discard(scanView.readByte())
                    val nodeEnd = scanView.readEnd()
                    scanView.goto(nodeEnd)
                case TastyFormat.PARAM =>
                    val nodeAddr = scanView.positionInt
                    inner += nodeAddr
                    inParamRgn = true
                    discard(scanView.readByte()) // consume PARAM tag
                    val nodeEnd = scanView.readEnd()
                    scanView.goto(nodeEnd)
                case TastyFormat.EMPTYCLAUSE | TastyFormat.SPLITCLAUSE =>
                    // Close the current inner group and open a new one.
                    outer += Chunk.from(inner.toSeq)
                    inner = new mutable.ArrayBuffer[Int]()
                    inParamRgn = true
                    discard(scanView.readByte()) // consume the single-byte marker (no length payload)
                // Carve-out: TASTy tag-byte dispatch; non-parameter tags terminate the scan
                case _ =>
                    // returnType or RHS reached; parameter-list region ends.
                    done = true
            end match
        end while
        // If we saw any param region content (PARAMs or clause markers), commit the final inner group
        // (it covers the trailing params after the last SPLITCLAUSE, or the entire single-clause case
        // where no SPLITCLAUSE was ever emitted).
        if inParamRgn then
            outer += Chunk.from(inner.toSeq)
            Chunk.from(outer.toSeq)
        else
            Chunk.empty
        end if
    end recordParamListPartitions

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
      * may contain Named(SymbolId(negId)) for cross-file parents (negId < -1, tracked in session.unresolvedIdToFullName); these are resolved or
      * filtered out during finalizeMerge so no negative SymbolId survives in produced ADT parentTypes.
      */
    private def decodeTemplateParents(
        parentScanView: ByteView,
        end: Long,
        sectionOffset: Int = 0,
        typeSession: TypeUnpickler.DecodeSession
    )(using Frame, AllowUnsafe): mutable.ArrayBuffer[Tasty.Type] =
        val collected = new mutable.ArrayBuffer[Tasty.Type]()
        // Step 1: skip leading TYPEPARAM and PARAM nodes (class own type/term params).
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
        // Step 2: decode parent_Type* entries.
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
                // The first child is typically SELECT(NEW(type_ref), <init>).
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
                // Also guard direct (non-APPLY-headed) parent expressions.
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
      * ANNOTATION blocks are decoded (tycon type + args bytes captured) rather than skipped.
      * The decoded annotations are returned alongside the flag bits for the caller to attribute to the owning symbol.
      *
      * PRIVATEqualified (98) and PROTECTEDqualified (99) must skip their sub-AST.
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
                            case Present(annotation) => annBuf += annotation
                            case Absent              => ()
                    case TastyFormat.PRIVATEqualified =>
                        flagBits |= Tasty.Flag.Private.bit
                        skipTree(view)
                    case TastyFormat.PROTECTEDqualified =>
                        flagBits |= Tasty.Flag.Protected.bit
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
      * ANNOTATION blocks are decoded (tycon + args) rather than skipped.
      *
      * PRIVATEqualified (98) and PROTECTEDqualified (99) are category 3 (tag + sub-AST). Must skip their sub-AST.
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
                        case Present(annotation) => annBuf += annotation
                        case Absent              => ()
                case TastyFormat.PRIVATEqualified =>
                    flagBits |= Tasty.Flag.Private.bit
                    skipTree(view)
                case TastyFormat.PROTECTEDqualified =>
                    flagBits |= Tasty.Flag.Protected.bit
                    skipTree(view)
                case modTag if modTag >= 1 && modTag <= 59 =>
                    flagBits |= InternalFlags.fromTastyModifierTag(modTag).bits
                // Carve-out: TASTy modifier tag-byte dispatch; non-modifier tags stop collection
                case _ =>
                    // Non-modifier encountered; stop collecting. This happens if we overran into a body.
                    view.goto(end)
            end match
        end while
        (flagBits, annBuf)
    end readModifiers

    /** Decode one ANNOTATION modifier block (tag already consumed, annEnd already read).
      *
      * Wire format after tag+Length: tycon_Type fullAnnotation_Term. Decodes the tycon using the same
      * TPT-tag dispatch as decodeOneTypeIfPresent (TPT tags route to TreeUnpickler.decodeTptAsType;
      * regular type tags route to TypeUnpickler.readTypeIntoSession). Skips the remaining term bytes.
      * Returns the Annotation on success; Absent on decode failure or when cursor overruns annEnd.
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
                    // Annotation tycons can arrive as term tags in INLINED-as-type contexts.
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
                // For @scala.annotation.internal.Child[T] annotations, decode the
                // fullAnnotation_Term to extract the permitted-subclass TypeRef T. This is needed
                // by the permit-extraction loop in ClasspathOrchestrator.finalizeMerge.
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
                            // TYPEREF emits TypeRef; handle @Child from TypeRef tycons too.
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
                        case _: Tasty.Type.Named | _: Tasty.Type.TermRef | _: Tasty.Type.Applied |
                            _: Tasty.Type.TypeLambda | _: Tasty.Type.Function | _: Tasty.Type.ContextFunction |
                            _: Tasty.Type.Tuple | _: Tasty.Type.ByName | _: Tasty.Type.Repeated |
                            _: Tasty.Type.Array | _: Tasty.Type.Refinement | _: Tasty.Type.Rec |
                            _: Tasty.Type.RecThis | _: Tasty.Type.AndType | _: Tasty.Type.OrType |
                            _: Tasty.Type.Annotated | _: Tasty.Type.ConstantType | _: Tasty.Type.ThisType |
                            _: Tasty.Type.SuperType | _: Tasty.Type.ParamRef | _: Tasty.Type.Wildcard |
                            _: Tasty.Type.Skolem | _: Tasty.Type.MatchType | _: Tasty.Type.FlexibleType |
                            _: Tasty.Type.MatchCase | _: Tasty.Type.TypeRef | _: Tasty.Type.Bounds |
                            Tasty.Type.Nothing | Tasty.Type.Any =>
                            tycon
                view.goto(annEnd)
                // Intermediate annotation: annotationFullName placeholder set to empty; finalizeMerge resolves.
                Present(Tasty.Annotation(enrichedTycon, Chunk.empty, Tasty.Name("")))
        end match
    end decodeAnnotationBlock

    /** Extract the type argument from a @Child[T] annotation fullAnnotation_Term.
      *
      * @Child[T] encodes the permitted-subclass TypeRef T inside:
      *   APPLY Length ( TYPEAPPLY Length ( constructor_ref  T_typeref ) )
      * This helper navigates the APPLY > TYPEAPPLY wrapper, skips the constructor_ref (the Child
      * constructor reference), and decodes the T_typeref. On success returns
      * `Type.Applied(tycon, Chunk(T))` which finalizeMerge can pattern-match.
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
                    // Term tags in type-argument position of @Child annotations.
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
