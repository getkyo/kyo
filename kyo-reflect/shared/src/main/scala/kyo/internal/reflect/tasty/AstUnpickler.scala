package kyo.internal.reflect.tasty

import kyo.*
import kyo.internal.reflect.binary.ByteView
import kyo.internal.reflect.query.ClasspathRef
import kyo.internal.reflect.query.UnresolvedRef
import kyo.internal.reflect.symbol.Flags as InternalFlags
import kyo.internal.reflect.symbol.Symbol as InternalSymbol
import kyo.internal.reflect.symbol.SymbolKind as InternalSymbolKind
import kyo.internal.reflect.type_.TypeArena
import scala.collection.mutable

/** Pass 1 skeleton-eager AST unpickler.
  *
  * Implements the pre-scan pattern from dotty `TreeUnpickler.indexStats` (scala3-compiler 3.7.0, line ~799). Walks the AST section
  * byte-by-byte, allocating one `Reflect.Symbol` per definition node (PACKAGE, VALDEF, DEFDEF, TYPEDEF, TYPEPARAM, PARAM). Eagerly decodes
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
  * Phase 4 note: Pass1Result.placeholders carries cross-file UnresolvedRef entries accumulated by TypeUnpickler during type decode.
  * AstUnpickler itself does not populate placeholders; they are populated when TypeUnpickler.readType is called for signature/parent
  * positions.
  */
object AstUnpickler:

    /** Result of pass 1.
      *
      * @param symbols
      *   All symbols discovered during the walk, excluding the synthetic root sentinel.
      * @param addrMap
      *   Map from TASTy byte address to symbol, for cross-symbol type references.
      * @param placeholders
      *   Cross-file UnresolvedRef entries collected by TypeUnpickler during type decode.
      * @param rootSymbol
      *   The synthetic root Package sentinel (empty name, null owner). Used as the top-level owner for package-level definitions. Excluded
      *   from `symbols` (which only contains user-declared definitions) but retained here so downstream resolvers can walk the full owner
      *   chain back to root without needing to reconstruct it.
      * @param parentsBySymbol
      *   Pre-indexed map from each class-like symbol to its parent types, as decoded from TEMPLATE parent_Type entries during pass 1.
      *   Parent types may be proxy types with unresolved SingleAssign slots for cross-file parents; slots are populated during Phase C
      *   placeholder resolution in mergeResults. This map is consumed by mergeResults to assign `Symbol._parents` after G13 resolution
      *   completes.
      * @param childrenByOwner
      *   Pre-indexed map from each symbol to its directly-owned child symbols, built by grouping all symbols by their `owner` field. Used
      *   by mergeResults to assign `Symbol._declarations` (after filtering out TypeParam-kinded children per the declarations contract) and
      *   `Symbol._typeParams` (TypeParam-kinded children only).
      */
    final case class Pass1Result(
        symbols: Chunk[Reflect.Symbol],
        addrMap: Map[Int, Reflect.Symbol],
        placeholders: Chunk[UnresolvedRef],
        rootSymbol: Reflect.Symbol,
        parentsBySymbol: Map[Reflect.Symbol, Chunk[Reflect.Type]],
        childrenByOwner: Map[Reflect.Symbol, Chunk[Reflect.Symbol]]
    )

    /** Run pass 1 over the AST section.
      *
      * @param view
      *   ByteView positioned at the start of the AST section payload. The view's `remaining` covers the full AST section.
      * @param names
      *   0-based name array produced by NameUnpickler.
      * @param attrs
      *   file attributes (isJava flag used to set JavaDefined on symbols).
      * @param home
      *   ClasspathRef stored in each symbol.
      * @param arena
      *   Per-file TypeArena used by TypeUnpickler to hash-cons decoded types.
      *
      * Note on effect row: the return type includes `Sync` because `Sync.defer` wraps the purely-computed `Pass1Result` value on the
      * success path. The actual body (`runPass1`) is synchronous. The `Sync` widening is intentional to keep the return type uniform with
      * other Kyo-effect-returning readers in this package.
      */
    def readPass1(
        view: ByteView,
        names: Array[Reflect.Name],
        attrs: FileAttributes,
        home: ClasspathRef,
        arena: TypeArena
    )(using Frame): Pass1Result < (Sync & Abort[ReflectError]) =
        val result =
            try Right(runPass1(view, names, attrs, home, arena))
            catch
                case ex: ArrayIndexOutOfBoundsException =>
                    Left(ReflectError.MalformedSection("ASTs", s"unexpected end: ${ex.getMessage}"))
                case ex: java.lang.Error
                    if ex.getCause != null && ex.getCause.isInstanceOf[ArrayIndexOutOfBoundsException] =>
                    Left(ReflectError.MalformedSection("ASTs", s"unexpected end: ${ex.getCause.getMessage}"))
                case ex: Exception =>
                    Left(ReflectError.MalformedSection("ASTs", s"parse error: ${ex.getMessage}"))
        result match
            case Right(r)  => Sync.defer(r)
            case Left(err) => Abort.fail(err)
    end readPass1

    private def runPass1(
        view: ByteView,
        names: Array[Reflect.Name],
        attrs: FileAttributes,
        home: ClasspathRef,
        arena: TypeArena
    ): Pass1Result =
        val addrMap         = new mutable.HashMap[Int, Reflect.Symbol]()
        val allSymbols      = new mutable.ArrayBuffer[Reflect.Symbol]()
        val ownerStack      = new mutable.ArrayDeque[Reflect.Symbol]()
        val parentsBySymbol = new mutable.HashMap[Reflect.Symbol, Chunk[Reflect.Type]]()

        // Synthetic root: a Package symbol with empty name; its owner is null (termination sentinel).
        val rootName   = Reflect.Name("")
        val rootOrigin = Reflect.Symbol.TastyOrigin(Map.empty, 0, 0)
        val root = InternalSymbol.makeSymbol(Reflect.SymbolKind.Package, Reflect.Flags.empty, rootName, null, home, rootOrigin, Absent)
        ownerStack.append(root)
        allSymbols += root

        val sectionEnd = view.position + view.remaining
        // Phase 1: collect symbols. The typeSession holds the live addrMap so type decode can find
        // locally-defined symbols as the walk progresses. Cross-file refs produce UnresolvedRef entries.
        val typeSession = new TypeUnpickler.DecodeSession(names, addrMap, arena, home)
        walkStats(view, sectionEnd, names, attrs, home, addrMap, allSymbols, ownerStack, typeSession, parentsBySymbol)

        // Build childrenByOwner: group all non-root symbols by their owner.
        val childrenByOwner = new mutable.HashMap[Reflect.Symbol, mutable.ArrayBuffer[Reflect.Symbol]]()
        for sym <- allSymbols.tail do // skip root
            val owner = sym.owner
            if owner != null then
                childrenByOwner.getOrElseUpdate(owner, new mutable.ArrayBuffer[Reflect.Symbol]()) += sym
            end if
        end for

        Pass1Result(
            symbols = Chunk.from(allSymbols.tail), // exclude root
            addrMap = addrMap.toMap,
            placeholders = Chunk.from(typeSession.placeholders),
            rootSymbol = root,
            parentsBySymbol = parentsBySymbol.view.mapValues(identity).toMap,
            childrenByOwner = childrenByOwner.view.mapValues(buf => Chunk.from(buf.toSeq)).toMap
        )
    end runPass1

    private def currentOwner(ownerStack: mutable.ArrayDeque[Reflect.Symbol]): Reflect.Symbol =
        ownerStack.last

    /** Walk all stats (top-level definitions) until position reaches `end`. Mirrors dotty indexStats. */
    private def walkStats(
        view: ByteView,
        end: Int,
        names: Array[Reflect.Name],
        attrs: FileAttributes,
        home: ClasspathRef,
        addrMap: mutable.HashMap[Int, Reflect.Symbol],
        allSymbols: mutable.ArrayBuffer[Reflect.Symbol],
        ownerStack: mutable.ArrayDeque[Reflect.Symbol],
        typeSession: TypeUnpickler.DecodeSession,
        parentsBySymbol: mutable.HashMap[Reflect.Symbol, Chunk[Reflect.Type]]
    ): Unit =
        while view.position < end do
            val nodeAddr = view.position
            val tag      = view.readByte() & 0xff
            tag match
                case TastyFormat.PACKAGE =>
                    val payloadEnd = view.readEnd()
                    // PACKAGE payload: Path TopLevelStat*
                    // Path is the package term reference: TERMREFpkg (cat2: tag+NameRef) or
                    // SELECT (cat4: tag+NameRef+qual) for nested packages (kyo.fixtures etc.).
                    // Decode the innermost simple name from the path.
                    val pkgName = extractPackageName(view, names)
                    val owner   = currentOwner(ownerStack)
                    val origin  = Reflect.Symbol.TastyOrigin(Map.empty, view.position, payloadEnd)
                    val sym = InternalSymbol.makeSymbol(
                        Reflect.SymbolKind.Package,
                        Reflect.Flags.empty,
                        pkgName,
                        owner,
                        home,
                        origin,
                        Absent
                    )
                    addrMap(nodeAddr) = sym
                    allSymbols += sym
                    ownerStack.append(sym)
                    walkStats(view, payloadEnd, names, attrs, home, addrMap, allSymbols, ownerStack, typeSession, parentsBySymbol)
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
                    decodeOneTypeIfPresent(view, payloadEnd, typeSession)
                    val flagBits = scanForwardAndCollectFlags(view, payloadEnd)
                    val kind     = InternalSymbolKind.fromValdefFlags(flagBits)
                    val flags    = new Reflect.Flags(flagBits)
                    val origin   = Reflect.Symbol.TastyOrigin(Map.empty, payloadBody, payloadEnd)
                    val sym      = InternalSymbol.makeSymbol(kind, flags, symName, owner, home, origin, Absent)
                    addrMap(nodeAddr) = sym
                    allSymbols += sym
                    view.goto(payloadEnd)

                case TastyFormat.DEFDEF =>
                    val payloadEnd  = view.readEnd()
                    val nameRef     = view.readNat()
                    val symName     = names(nameRef)
                    val owner       = currentOwner(ownerStack)
                    val payloadBody = view.position
                    val flagBits    = scanForwardAndCollectFlags(view, payloadEnd)
                    val flags       = new Reflect.Flags(flagBits)
                    val origin      = Reflect.Symbol.TastyOrigin(Map.empty, payloadBody, payloadEnd)
                    val sym         = InternalSymbol.makeSymbol(Reflect.SymbolKind.Method, flags, symName, owner, home, origin, Absent)
                    addrMap(nodeAddr) = sym
                    allSymbols += sym
                    // Recurse into DEFDEF body to discover type params and value params.
                    // Use a forked sub-view of the payload body.
                    val innerView = view.subView(payloadBody, payloadEnd)
                    ownerStack.append(sym)
                    walkStats(innerView, payloadEnd, names, attrs, home, addrMap, allSymbols, ownerStack, typeSession, parentsBySymbol)
                    discard(ownerStack.removeLast())
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
                        val decodedParents = decodeTemplateParents(parentScanView, templatePayloadEnd, typeSession)
                        // Advance the outer view past the TEMPLATE payload so that readModifiers
                        // reads modifiers from templatePayloadEnd to payloadEnd.
                        view.goto(templatePayloadEnd)
                        // Modifiers follow the TEMPLATE node.
                        val flagBits = readModifiers(view, payloadEnd)
                        val kind     = InternalSymbolKind.fromTypedefTemplateFlags(flagBits)
                        val flags    = new Reflect.Flags(flagBits)
                        val origin   = Reflect.Symbol.TastyOrigin(Map.empty, templateBodyStart, templatePayloadEnd)
                        val sym      = InternalSymbol.makeSymbol(kind, flags, symName, owner, home, origin, Absent)
                        addrMap(nodeAddr) = sym
                        allSymbols += sym
                        // Record parent types for this class symbol (used by mergeResults for _parents assignment).
                        if decodedParents.nonEmpty then
                            parentsBySymbol(sym) = Chunk.from(decodedParents)
                        // Walk template body to discover members (type params, constructor params, member defs).
                        val templateFork = view.subView(templateBodyStart, templatePayloadEnd)
                        ownerStack.append(sym)
                        walkStats(
                            templateFork,
                            templatePayloadEnd,
                            names,
                            attrs,
                            home,
                            addrMap,
                            allSymbols,
                            ownerStack,
                            typeSession,
                            parentsBySymbol
                        )
                        discard(ownerStack.removeLast())
                        view.goto(payloadEnd)
                    else
                        // Type-level TYPEDEF: skip type sub-tree, then read modifiers.
                        // nextTag is the type body tag (e.g. TYPEBOUNDS for abstract types, TYPEREF for aliases).
                        // Pass it to fromTypedefTypeFlagsAndBody for reliable AbstractType detection.
                        discard(view.readByte()) // consume the type sub-tree tag
                        skipTreeBody(nextTag, view)
                        val flagBits = readModifiers(view, payloadEnd)
                        val kind     = InternalSymbolKind.fromTypedefTypeFlagsAndBody(flagBits, nextTag)
                        val flags    = new Reflect.Flags(flagBits)
                        val origin   = Reflect.Symbol.TastyOrigin(Map.empty, payloadEnd, payloadEnd)
                        val sym      = InternalSymbol.makeSymbol(kind, flags, symName, owner, home, origin, Absent)
                        addrMap(nodeAddr) = sym
                        allSymbols += sym
                        view.goto(payloadEnd)
                    end if

                case TastyFormat.TYPEPARAM =>
                    val payloadEnd = view.readEnd()
                    val nameRef    = view.readNat()
                    val symName    = names(nameRef)
                    val owner      = currentOwner(ownerStack)
                    // Decode type bounds (first type node after NameRef), if present.
                    decodeOneTypeIfPresent(view, payloadEnd, typeSession)
                    val flagBits = readModifiers(view, payloadEnd)
                    val flags    = new Reflect.Flags(flagBits)
                    val origin   = Reflect.Symbol.TastyOrigin(Map.empty, payloadEnd, payloadEnd)
                    val sym      = InternalSymbol.makeSymbol(Reflect.SymbolKind.TypeParam, flags, symName, owner, home, origin, Absent)
                    addrMap(nodeAddr) = sym
                    allSymbols += sym
                    view.goto(payloadEnd)

                case TastyFormat.PARAM =>
                    val payloadEnd = view.readEnd()
                    val nameRef    = view.readNat()
                    val symName    = names(nameRef)
                    val owner      = currentOwner(ownerStack)
                    // Decode parameter type (first type node after NameRef), if present.
                    decodeOneTypeIfPresent(view, payloadEnd, typeSession)
                    val flagBits = scanForwardAndCollectFlags(view, payloadEnd)
                    val flags    = new Reflect.Flags(flagBits)
                    val origin   = Reflect.Symbol.TastyOrigin(Map.empty, payloadEnd, payloadEnd)
                    val sym      = InternalSymbol.makeSymbol(Reflect.SymbolKind.Parameter, flags, symName, owner, home, origin, Absent)
                    addrMap(nodeAddr) = sym
                    allSymbols += sym
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
      * Checks if the next byte is a type-tag (not a modifier tag). If so, calls TypeUnpickler.readTypeIntoSession to decode it, collecting
      * any cross-file UnresolvedRef placeholders. If decoding fails (malformed type tree), skips to `end` silently to avoid corrupting the
      * outer walk.
      */
    private def decodeOneTypeIfPresent(view: ByteView, end: Int, typeSession: TypeUnpickler.DecodeSession): Unit =
        if view.position < end then
            val nextTag = view.peekByte(view.position) & 0xff
            if !isModifierTag(nextTag) then
                try
                    discard(TypeUnpickler.readTypeIntoSession(view, typeSession))
                catch
                    case _: Exception => view.goto(end)
            end if
        end if
    end decodeOneTypeIfPresent

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
      * may contain proxy types (UnresolvedRef slots) for cross-file parents; these are resolved during Phase C.
      */
    private def decodeTemplateParents(
        parentScanView: ByteView,
        end: Int,
        typeSession: TypeUnpickler.DecodeSession
    ): mutable.ArrayBuffer[Reflect.Type] =
        val collected = new mutable.ArrayBuffer[Reflect.Type]()
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
            else
                try
                    val decoded = TypeUnpickler.readTypeIntoSession(parentScanView, typeSession)
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

    /** Scan forward through sub-trees, collecting modifier flag bits when modifiers are reached.
      *
      * Skips all non-modifier trees (type trees, rhs body, params). Modifiers are: category-1 tags 1-59, PRIVATEqualified (98),
      * PROTECTEDqualified (99), ANNOTATION (173). Everything else is a sub-tree to skip.
      *
      * STEERING CRITICAL: PRIVATEqualified (98) and PROTECTEDqualified (99) must skip their sub-AST.
      */
    private def scanForwardAndCollectFlags(view: ByteView, end: Int): Long =
        var flagBits = 0L
        while view.position < end do
            val pos = view.position
            val tag = view.peekByte(pos) & 0xff
            if isModifierTag(tag) then
                discard(view.readByte()) // consume modifier tag
                tag match
                    case TastyFormat.ANNOTATION =>
                        val annEnd = view.readEnd()
                        view.goto(annEnd)
                    case TastyFormat.PRIVATEqualified =>
                        flagBits |= Reflect.Flag.Private.bit
                        skipTree(view)
                    case TastyFormat.PROTECTEDqualified =>
                        flagBits |= Reflect.Flag.Protected.bit
                        skipTree(view)
                    case modTag =>
                        flagBits |= InternalFlags.fromTastyModifierTag(modTag).bits
                end match
            else
                discard(view.readByte()) // consume tag
                skipTreeBody(tag, view)
            end if
        end while
        flagBits
    end scanForwardAndCollectFlags

    /** Read modifier tags from the current position up to `end`. Assumes cursor is already past any non-modifier sub-trees.
      *
      * STEERING CRITICAL: PRIVATEqualified (98) and PROTECTEDqualified (99) are category 3 (tag + sub-AST). Must skip their sub-AST.
      */
    private def readModifiers(view: ByteView, end: Int): Long =
        var flagBits = 0L
        while view.position < end do
            val modTag = view.readByte() & 0xff
            modTag match
                case TastyFormat.ANNOTATION =>
                    val annEnd = view.readEnd()
                    view.goto(annEnd)
                case TastyFormat.PRIVATEqualified =>
                    flagBits |= Reflect.Flag.Private.bit
                    skipTree(view)
                case TastyFormat.PROTECTEDqualified =>
                    flagBits |= Reflect.Flag.Protected.bit
                    skipTree(view)
                case modTag if modTag >= 1 && modTag <= 59 =>
                    flagBits |= InternalFlags.fromTastyModifierTag(modTag).bits
                case _ =>
                    // Non-modifier encountered; stop collecting. This happens if we overran into a body.
                    view.goto(end)
            end match
        end while
        flagBits
    end readModifiers

    private def isModifierTag(tag: Int): Boolean =
        (tag >= 1 && tag <= 59) ||
            tag == TastyFormat.PRIVATEqualified ||
            tag == TastyFormat.PROTECTEDqualified ||
            tag == TastyFormat.ANNOTATION

    /** Skip exactly one tree node from the current position (tag not yet consumed). */
    private def skipTree(view: ByteView): Unit =
        val tag = (view.readByte(): Int) & 0xff
        skipTreeBody(tag, view)

    /** Skip the body of a tree node whose tag byte has already been consumed.
      *
      * Category rules (from TastyFormat): 1 (1-59): tag only; 2 (60-89): tag + Nat; 3 (90-109): tag + sub-AST; 4 (110-127): tag + Nat +
      * sub-AST; 5 (128-255): tag + Length + payload.
      */
    private def skipTreeBody(tag: Int, view: ByteView): Unit =
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
    private def extractPackageName(view: ByteView, names: Array[Reflect.Name]): Reflect.Name =
        val segments = new mutable.ArrayBuffer[String]()
        extractPackagePathSegments(view, names, segments)
        Reflect.Name(segments.mkString("."))
    end extractPackageName

    /** Recursively read a package path, prepending each segment to `segments`. */
    private def extractPackagePathSegments(view: ByteView, names: Array[Reflect.Name], segments: mutable.ArrayBuffer[String]): Unit =
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
