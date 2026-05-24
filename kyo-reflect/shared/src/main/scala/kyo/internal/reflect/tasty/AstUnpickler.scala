package kyo.internal.reflect.tasty

import kyo.*
import kyo.internal.reflect.binary.ByteView
import kyo.internal.reflect.query.ClasspathRef
import kyo.internal.reflect.symbol.Flags as InternalFlags
import kyo.internal.reflect.symbol.Symbol as InternalSymbol
import kyo.internal.reflect.symbol.SymbolKind as InternalSymbolKind
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
  */
object AstUnpickler:

    /** Forward-declared placeholder for cross-file type references. Phase 4's TypeUnpickler will supply the real `UnresolvedRef` Type case;
      * this stub lets Phase 3's Pass1Result carry the field per plan line 202 so Phase 4's dependency (plan line 267) is satisfied.
      */
    final case class UnresolvedRef(addr: Int, name: Reflect.Name)

    /** Result of pass 1. */
    final case class Pass1Result(
        symbols: Chunk[Reflect.Symbol],
        addrMap: Map[Int, Reflect.Symbol],
        placeholders: Chunk[UnresolvedRef],
        rootSymbol: Reflect.Symbol
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
      *   ClasspathRef stored in each symbol. NOT called during pass 1.
      */
    def readPass1(
        view: ByteView,
        names: Array[Reflect.Name],
        attrs: FileAttributes,
        home: ClasspathRef
    )(using Frame): Pass1Result < (Sync & Abort[ReflectError]) =
        val result =
            try Right(runPass1(view, names, attrs, home))
            catch
                case ex: ArrayIndexOutOfBoundsException =>
                    Left(ReflectError.MalformedSection("ASTs", s"unexpected end: ${ex.getMessage}"))
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
        home: ClasspathRef
    ): Pass1Result =
        val addrMap    = new mutable.HashMap[Int, Reflect.Symbol]()
        val allSymbols = new mutable.ArrayBuffer[Reflect.Symbol]()
        val ownerStack = new mutable.ArrayDeque[Reflect.Symbol]()

        // Synthetic root: a Package symbol with empty name; its owner is null (termination sentinel).
        val rootName   = Reflect.Name("")
        val rootOrigin = Reflect.Symbol.TastyOrigin(Map.empty, 0, 0)
        val root       = InternalSymbol.makeSymbol(Reflect.SymbolKind.Package, Reflect.Flags.empty, rootName, null, home, rootOrigin)
        ownerStack.append(root)
        allSymbols += root

        val sectionEnd = view.position + view.remaining
        walkStats(view, sectionEnd, names, attrs, home, addrMap, allSymbols, ownerStack)

        Pass1Result(
            symbols = Chunk.from(allSymbols.tail), // exclude root
            addrMap = addrMap.toMap,
            placeholders = Chunk.empty,
            rootSymbol = root
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
        ownerStack: mutable.ArrayDeque[Reflect.Symbol]
    ): Unit =
        while view.position < end do
            val nodeAddr = view.position
            val tag      = view.readByte() & 0xff
            tag match
                case TastyFormat.PACKAGE =>
                    val payloadEnd = view.readEnd()
                    // PACKAGE payload: Path TopLevelStat*
                    // Path is the package term reference (category 2 or 4 tag + Nat)
                    val pathTag = (view.readByte(): Int) & 0xff
                    // Skip the path reference (it's a term ref, category 2: tag + Nat)
                    skipTreeBody(pathTag, view)
                    val pkgName = Reflect.Name("") // placeholder; resolved from path in Phase 4
                    val owner   = currentOwner(ownerStack)
                    val origin  = Reflect.Symbol.TastyOrigin(Map.empty, view.position, payloadEnd)
                    val sym = InternalSymbol.makeSymbol(
                        Reflect.SymbolKind.Package,
                        Reflect.Flags.empty,
                        pkgName,
                        owner,
                        home,
                        origin
                    )
                    addrMap(nodeAddr) = sym
                    allSymbols += sym
                    ownerStack.append(sym)
                    walkStats(view, payloadEnd, names, attrs, home, addrMap, allSymbols, ownerStack)
                    discard(ownerStack.removeLast())
                    view.goto(payloadEnd)

                case TastyFormat.VALDEF =>
                    val payloadEnd  = view.readEnd()
                    val nameRef     = view.readNat()
                    val symName     = names(nameRef)
                    val owner       = currentOwner(ownerStack)
                    val payloadBody = view.position
                    val flagBits    = scanForwardAndCollectFlags(view, payloadEnd)
                    val kind        = InternalSymbolKind.fromValdefFlags(flagBits)
                    val flags       = new Reflect.Flags(flagBits)
                    val origin      = Reflect.Symbol.TastyOrigin(Map.empty, payloadBody, payloadEnd)
                    val sym         = InternalSymbol.makeSymbol(kind, flags, symName, owner, home, origin)
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
                    val sym         = InternalSymbol.makeSymbol(Reflect.SymbolKind.Method, flags, symName, owner, home, origin)
                    addrMap(nodeAddr) = sym
                    allSymbols += sym
                    // Recurse into DEFDEF body to discover type params and value params.
                    // Use a forked sub-view of the payload body.
                    val innerView = view.subView(payloadBody, payloadEnd)
                    ownerStack.append(sym)
                    walkStats(innerView, payloadEnd, names, attrs, home, addrMap, allSymbols, ownerStack)
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
                        // Skip the TEMPLATE payload to reach the Modifier* region.
                        view.goto(templatePayloadEnd)
                        // Modifiers follow the TEMPLATE node.
                        val flagBits = readModifiers(view, payloadEnd)
                        val kind     = InternalSymbolKind.fromTypedefTemplateFlags(flagBits)
                        val flags    = new Reflect.Flags(flagBits)
                        val origin   = Reflect.Symbol.TastyOrigin(Map.empty, templateBodyStart, templatePayloadEnd)
                        val sym      = InternalSymbol.makeSymbol(kind, flags, symName, owner, home, origin)
                        addrMap(nodeAddr) = sym
                        allSymbols += sym
                        // Walk template body to discover members (type params, constructor params, member defs).
                        val templateFork = view.subView(templateBodyStart, templatePayloadEnd)
                        ownerStack.append(sym)
                        walkStats(templateFork, templatePayloadEnd, names, attrs, home, addrMap, allSymbols, ownerStack)
                        discard(ownerStack.removeLast())
                        view.goto(payloadEnd)
                    else
                        // Type-level TYPEDEF: skip type sub-tree, then read modifiers.
                        discard(view.readByte()) // consume the type sub-tree tag
                        skipTreeBody(nextTag, view)
                        val flagBits = readModifiers(view, payloadEnd)
                        val kind     = InternalSymbolKind.fromTypedefTypeFlags(flagBits)
                        val flags    = new Reflect.Flags(flagBits)
                        val origin   = Reflect.Symbol.TastyOrigin(Map.empty, payloadEnd, payloadEnd)
                        val sym      = InternalSymbol.makeSymbol(kind, flags, symName, owner, home, origin)
                        addrMap(nodeAddr) = sym
                        allSymbols += sym
                        view.goto(payloadEnd)
                    end if

                case TastyFormat.TYPEPARAM =>
                    val payloadEnd = view.readEnd()
                    val nameRef    = view.readNat()
                    val symName    = names(nameRef)
                    val owner      = currentOwner(ownerStack)
                    // Skip type bounds sub-tree (bounds are at current position before modifiers).
                    if view.position < payloadEnd then
                        val boundTag = view.readByte() & 0xff
                        skipTreeBody(boundTag, view)
                    val flagBits = readModifiers(view, payloadEnd)
                    val flags    = new Reflect.Flags(flagBits)
                    val origin   = Reflect.Symbol.TastyOrigin(Map.empty, payloadEnd, payloadEnd)
                    val sym      = InternalSymbol.makeSymbol(Reflect.SymbolKind.TypeParam, flags, symName, owner, home, origin)
                    addrMap(nodeAddr) = sym
                    allSymbols += sym
                    view.goto(payloadEnd)

                case TastyFormat.PARAM =>
                    val payloadEnd = view.readEnd()
                    val nameRef    = view.readNat()
                    val symName    = names(nameRef)
                    val owner      = currentOwner(ownerStack)
                    val flagBits   = scanForwardAndCollectFlags(view, payloadEnd)
                    val flags      = new Reflect.Flags(flagBits)
                    val origin     = Reflect.Symbol.TastyOrigin(Map.empty, payloadEnd, payloadEnd)
                    val sym        = InternalSymbol.makeSymbol(Reflect.SymbolKind.Parameter, flags, symName, owner, home, origin)
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

end AstUnpickler
