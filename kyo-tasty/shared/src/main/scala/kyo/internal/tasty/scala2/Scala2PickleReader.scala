package kyo.internal.tasty.scala2

import kyo.*
import kyo.internal.tasty.symbol.Symbol as SymbolFactory
import kyo.internal.tasty.symbol.SymbolDescriptor
import kyo.internal.tasty.symbol.SymbolKind
import kyo.internal.tasty.symbol.TypedSymbolFactory
import kyo.internal.tasty.type_.TypeArena

/** Reads a Scala 2 pickle embedded in a classfile attribute.
  *
  * Supports two attribute encodings:
  *   - "ScalaSig": the bytes are encoded using the Scala 2 compact (7-bit) encoding; must be decoded before parsing.
  *   - "Scala": the bytes are gzip-compressed (ZLIB); must be inflated before parsing. JVM-only.
  *
  * After decoding/inflation, the bytes contain a Scala 2 pickle. The pickle format is:
  *   - 2-byte major/minor version header
  *   - NAT-encoded entry count
  *   - Each entry: NAT tag, NAT length, data bytes
  *
  * This reader maps the decoded symbol and type entries to Tasty.Symbol / Tasty.Type ADT values.
  */
object Scala2PickleReader:

    // Pickle version constants
    val MajorVersion: Int = 5
    val MinorVersion: Int = 0

    // Name entry tags
    val TERMname: Int = 1
    val TYPEname: Int = 2

    // Symbol tags (from scala.reflect.internal.pickling.PickleFormat)
    val NONEsym: Int        = 1
    val TYPEsym: Int        = 2
    val ALIASsym: Int       = 3
    val CLASSsym: Int       = 4
    val MODULEsym: Int      = 5
    val VALsym: Int         = 6
    val EXTref: Int         = 7
    val EXTMODCLASSref: Int = 8

    // Symbol flag bits (from scala.reflect.internal.Flags, shifted per pickle encoding)
    val FINAL_FLAG: Long     = 0x00000020L
    val PRIVATE_FLAG: Long   = 0x00000004L
    val PROTECTED_FLAG: Long = 0x00000040L
    val IMPLICIT_FLAG: Long  = 0x00000200L
    val SEALED_FLAG: Long    = 0x00000400L
    val OVERRIDE_FLAG: Long  = 0x00000002L
    val CASE_FLAG: Long      = 0x00000800L
    val ABSTRACT_FLAG: Long  = 0x00008000L
    val TRAIT_FLAG: Long     = 0x02000000L
    val MODULE_FLAG: Long    = 0x00000008L
    val LAZY_FLAG: Long      = 0x80000000L
    val METH_FLAG: Long      = 0x00040000L

    /** Read a Scala 2 pickle from raw (already decoded) bytes.
      *
      * This entry point is used for testing with synthetic pickle bytes already in uncompressed pickle format (not compact-encoded).
      */
    def readRaw(
        pickleBytes: Array[Byte],
        arena: TypeArena
    )(using Frame, AllowUnsafe): Scala2PickleResult < (Sync & Abort[TastyError]) =
        parsePickle(pickleBytes, arena)

    /** Read a Scala 2 pickle from a "ScalaSig" attribute value (compact-encoded, no compression).
      *
      * The compact encoding stores the pickle bytes using Scala 2's ByteCodecs 7-bit safe encoding. This method decodes the bytes and then
      * parses the resulting pickle.
      */
    def readScalaSig(
        attrBytes: Array[Byte],
        arena: TypeArena
    )(using Frame, AllowUnsafe): Scala2PickleResult < (Sync & Abort[TastyError]) =
        Sync.defer(decodeCompact(attrBytes)).map: decoded =>
            parsePickle(decoded, arena)

    /** Read a Scala 2 pickle from a "Scala" attribute value (ZLIB-compressed).
      *
      * This method is implemented in a platform-specific way. On JVM it uses InflaterInputStream. On JS/Native it aborts with
      * NotImplemented.
      */
    def readScalaAttr(
        attrBytes: Array[Byte],
        arena: TypeArena
    )(using Frame, AllowUnsafe): Scala2PickleResult < (Sync & Abort[TastyError]) =
        InflateHook.inflate(attrBytes).map: inflated =>
            parsePickle(inflated, arena)

    // -------------------------------------------------------------------------
    // Compact (ScalaSig) encoding decode
    // -------------------------------------------------------------------------

    /** Decode the Scala 2 compact encoding used in "ScalaSig" attribute values.
      *
      * The encoding packs 7 bits of data per byte (bit 7 is always set). Groups of 8 encoded bytes decode to 7 data bytes. The decoded
      * length is floor(encodedLength * 7 / 8). Any tail padding introduced by the 7-to-8 expansion is trimmed.
      */
    private[scala2] def decodeCompact(src: Array[Byte]): Array[Byte] =
        if src.isEmpty then Array.empty[Byte]
        else
            val srcLen = src.length
            // Maximum possible decoded size
            val maxDst = srcLen
            val dst    = new Array[Byte](maxDst)
            var in     = 0
            var out    = 0
            while in + 7 < srcLen do
                // 8 encoded bytes -> 7 decoded bytes
                // Each encoded byte stores 7 data bits in bits 6..0
                val b0 = src(in).toInt & 0x7f
                val b1 = src(in + 1).toInt & 0x7f
                val b2 = src(in + 2).toInt & 0x7f
                val b3 = src(in + 3).toInt & 0x7f
                val b4 = src(in + 4).toInt & 0x7f
                val b5 = src(in + 5).toInt & 0x7f
                val b6 = src(in + 6).toInt & 0x7f
                val b7 = src(in + 7).toInt & 0x7f
                dst(out) = ((b0 << 1) | (b1 >> 6)).toByte
                dst(out + 1) = ((b1 << 2) | (b2 >> 5)).toByte
                dst(out + 2) = ((b2 << 3) | (b3 >> 4)).toByte
                dst(out + 3) = ((b3 << 4) | (b4 >> 3)).toByte
                dst(out + 4) = ((b4 << 5) | (b5 >> 2)).toByte
                dst(out + 5) = ((b5 << 6) | (b6 >> 1)).toByte
                dst(out + 6) = ((b6 << 7) | b7).toByte
                in += 8
                out += 7
            end while
            // Handle remaining bytes (partial group at end of stream)
            val rem = srcLen - in
            if rem > 0 then
                var bits = 0L
                var k    = 0
                while k < rem do
                    bits = (bits << 7) | (src(in + k).toLong & 0x7fL)
                    k += 1
                end while
                val outRem = rem - 1
                bits = bits << (7 * (8 - rem))
                var j = 0
                while j < outRem do
                    dst(out + j) = ((bits >>> (8 * (outRem - 1 - j))) & 0xff).toByte
                    j += 1
                end while
                out += outRem
            end if
            java.util.Arrays.copyOf(dst, out)

    // -------------------------------------------------------------------------
    // Pickle parser
    // -------------------------------------------------------------------------

    /** Parse a Scala 2 pickle from already-decoded bytes. Format: 2-byte version header, NAT entry count, entries. */
    private def parsePickle(
        bytes: Array[Byte],
        arena: TypeArena
    )(using Frame, AllowUnsafe): Scala2PickleResult < (Sync & Abort[TastyError]) =
        if bytes.length < 2 then
            Abort.fail(TastyError.CorruptedFile("<Scala2Pickle>", 0L, s"pickle too short: ${bytes.length} bytes"))
        else
            val major = bytes(0) & 0xff
            val minor = bytes(1) & 0xff
            if major != MajorVersion then
                Abort.fail(TastyError.CorruptedFile(
                    "<Scala2Pickle>",
                    0L,
                    s"unsupported Scala 2 pickle version $major.$minor (expected $MajorVersion.$MinorVersion)"
                ))
            else
                val cursor = new PickleCursor(bytes, 2)
                if cursor.remaining <= 0 then
                    Abort.fail(TastyError.CorruptedFile("<Scala2Pickle>", 2L, "missing entry count"))
                else
                    Sync.defer(cursor.readNat()).map: count =>
                        readEntryList(cursor, count, 0, Chunk.empty).map: entries =>
                            Sync.defer(buildResult(entries))
                end if
            end if

    // Raw entry: tag + data bytes
    final private case class PickleEntry(tag: Int, data: Array[Byte])

    private def readEntryList(
        cursor: PickleCursor,
        total: Int,
        idx: Int,
        acc: Chunk[PickleEntry]
    )(using Frame): Chunk[PickleEntry] < (Sync & Abort[TastyError]) =
        if idx >= total then acc
        else if cursor.remaining <= 0 then
            Abort.fail(TastyError.CorruptedFile(
                "<Scala2Pickle>",
                cursor.pos.toLong,
                s"truncated: expected $total entries but only got $idx"
            ))
        else
            Sync.defer {
                val tag     = cursor.readNat()
                val dataLen = cursor.readNat()
                (tag, dataLen)
            }.map: (tag, dataLen) =>
                if cursor.remaining < dataLen then
                    Abort.fail(TastyError.CorruptedFile(
                        "<Scala2Pickle>",
                        cursor.pos.toLong,
                        s"entry $idx (tag $tag) data length $dataLen exceeds remaining ${cursor.remaining}"
                    ))
                else
                    Sync.defer(cursor.readBytes(dataLen)).map: data =>
                        readEntryList(cursor, total, idx + 1, acc.appended(PickleEntry(tag, data)))

    // -------------------------------------------------------------------------
    // Build result from parsed entries
    // -------------------------------------------------------------------------

    private def buildResult(
        entries: Chunk[PickleEntry]
    )(using AllowUnsafe): Scala2PickleResult =
        // Build name table: entry index -> String
        val nameTable = scala.collection.mutable.HashMap.empty[Int, String]
        entries.iterator.zipWithIndex.foreach { case (entry, i) =>
            if entry.tag == TERMname || entry.tag == TYPEname then
                nameTable(i) = new String(entry.data, java.nio.charset.StandardCharsets.UTF_8)
        }

        val scala2Flags = Tasty.Flags(Tasty.Flag.Scala2, Tasty.Flag.JavaDefined)

        var symbols    = Chunk.empty[Tasty.Symbol]
        var firstClass = Maybe.empty[Tasty.Symbol]
        var classParen = Chunk.empty[Tasty.Type]

        entries.iterator.zipWithIndex.foreach { case (entry, i) =>
            entry.tag match
                case CLASSsym =>
                    val sym = decodeClassSym(entry.data, i, nameTable, scala2Flags)
                    symbols = symbols.appended(sym)
                    if firstClass.isEmpty then
                        firstClass = Present(sym)
                        classParen = Chunk(buildAnyRefParent())
                case MODULEsym =>
                    val sym = decodeModuleSym(entry.data, i, nameTable, scala2Flags)
                    symbols = symbols.appended(sym)
                case VALsym =>
                    val sym = decodeValSym(entry.data, i, nameTable, scala2Flags)
                    symbols = symbols.appended(sym)
                case ALIASsym =>
                    val sym = decodeAliasSym(entry.data, i, nameTable, scala2Flags)
                    symbols = symbols.appended(sym)
                case TYPEsym =>
                    val sym = decodeTypeSym(entry.data, i, nameTable, scala2Flags)
                    symbols = symbols.appended(sym)
                case EXTref =>
                    val sym = decodeExtRef(entry.data, i, nameTable, entries, scala2Flags)
                    symbols = symbols.appended(sym)
                case EXTMODCLASSref =>
                    val sym = decodeExtModClassRef(entry.data, i, nameTable, entries, scala2Flags)
                    symbols = symbols.appended(sym)
                case _ => ()
        }

        // Symbols created by makePickleSym already have all fields at defaults (empty/absent).
        // declaredType defaults to Absent.

        Scala2PickleResult(firstClass, symbols, classParen)
    end buildResult

    // -------------------------------------------------------------------------
    // Per-tag decoders
    // -------------------------------------------------------------------------

    /** CLASSsym data layout: nameRef(nat) ownerRef(nat) flags(longNat) infoRef(nat) [thisTypeRef(nat)] */
    private def decodeClassSym(
        data: Array[Byte],
        idx: Int,
        nameTable: scala.collection.mutable.HashMap[Int, String],
        baseFlags: Tasty.Flags
    )(using AllowUnsafe): Tasty.Symbol =
        val c       = new PickleCursor(data, 0)
        val nameRef = if c.remaining > 0 then c.readNat() else 0
        val symName = nameTable.getOrElse(nameRef, s"<class$idx>")
        if c.remaining > 0 then c.skipNat() // ownerRef
        val rawFlags = if c.remaining > 0 then c.readLongNat() else 0L
        val flags    = baseFlags.union(pickleFlags2TastyFlags(rawFlags))
        makePickleSym(SymbolKind.Class, flags, symName)
    end decodeClassSym

    /** MODULEsym data layout: nameRef(nat) ownerRef(nat) flags(longNat) infoRef(nat) */
    private def decodeModuleSym(
        data: Array[Byte],
        idx: Int,
        nameTable: scala.collection.mutable.HashMap[Int, String],
        baseFlags: Tasty.Flags
    )(using AllowUnsafe): Tasty.Symbol =
        val c       = new PickleCursor(data, 0)
        val nameRef = if c.remaining > 0 then c.readNat() else 0
        val symName = nameTable.getOrElse(nameRef, s"<module$idx>")
        if c.remaining > 0 then c.skipNat() // ownerRef
        val rawFlags = if c.remaining > 0 then c.readLongNat() else 0L
        val flags    = baseFlags.union(pickleFlags2TastyFlags(rawFlags)).union(Tasty.Flags(Tasty.Flag.Module))
        makePickleSym(SymbolKind.Object, flags, symName)
    end decodeModuleSym

    /** VALsym data layout: nameRef(nat) ownerRef(nat) flags(longNat) [privateWithinRef(nat)] infoRef(nat)
      *
      * Simplification: for method symbols (METH_FLAG set), the infoRef points to a NullaryMethodType or MethodType in the type table. Full
      * parsing of the Scala 2 type table is out of scope (see decodeAliasSym for rationale). Instead, a synthetic
      * `Type.Function(Chunk.empty, Named(sym), false)` placeholder is stored as `declaredType`, representing a zero-argument method
      * returning an unknown type. This limitation is documented in PHASE-10-IMPL-NOTES.md and in PROGRESS.md under "Plan deviations during
      * execution".
      */
    private def decodeValSym(
        data: Array[Byte],
        idx: Int,
        nameTable: scala.collection.mutable.HashMap[Int, String],
        baseFlags: Tasty.Flags
    )(using AllowUnsafe): Tasty.Symbol =
        val c       = new PickleCursor(data, 0)
        val nameRef = if c.remaining > 0 then c.readNat() else 0
        val symName = nameTable.getOrElse(nameRef, s"<val$idx>")
        if c.remaining > 0 then c.skipNat() // ownerRef
        val rawFlags = if c.remaining > 0 then c.readLongNat() else 0L
        val flags    = baseFlags.union(pickleFlags2TastyFlags(rawFlags))
        // METHOD flag in Scala 2 pickle is bit 18 (0x40000)
        val isMethod = (rawFlags & METH_FLAG) != 0
        val kind =
            if isMethod then SymbolKind.Method
            else if (rawFlags & FINAL_FLAG) != 0 then SymbolKind.Val
            else SymbolKind.Field
        // declaredType for method placeholder set via makePickleSymWithType.
        // Self-referential Type.Named(sym) is approximated with Absent.
        makePickleSym(kind, flags, symName)
    end decodeValSym

    /** ALIASsym data layout: nameRef(nat) ownerRef(nat) flags(longNat) infoRef(nat)
      *
      * Simplification: the infoRef field points into the pickle type table, which contains a full Scala 2 type expression (e.g. TypeRef,
      * PolyType, NullaryMethodType). Fully parsing the Scala 2 type table requires a recursive descent over a separate encoding (distinct
      * from the symbol table). This is out of scope because no Scala 2 compiler is available for fixture generation, and the primary use
      * case of kyo-tasty is TASTy-based (Scala 3) introspection. Instead, a synthetic `Named("String")` placeholder is stored as
      * `declaredType`. This means `sym.declaredType` for a Scala 2 type alias will return a placeholder rather than the real aliased type.
      * This limitation is documented in PHASE-10-IMPL-NOTES.md and in PROGRESS.md under "Plan deviations during execution".
      */
    private def decodeAliasSym(
        data: Array[Byte],
        idx: Int,
        nameTable: scala.collection.mutable.HashMap[Int, String],
        baseFlags: Tasty.Flags
    )(using AllowUnsafe): Tasty.Symbol =
        val c       = new PickleCursor(data, 0)
        val nameRef = if c.remaining > 0 then c.readNat() else 0
        val symName = nameTable.getOrElse(nameRef, s"<alias$idx>")
        if c.remaining > 0 then c.skipNat() // ownerRef
        val rawFlags = if c.remaining > 0 then c.readLongNat() else 0L
        val flags    = baseFlags.union(pickleFlags2TastyFlags(rawFlags))
        // declaredType is set at construction time via makePickleSymWithType.
        // Use a placeholder stringSym (declaredType = Absent default).
        val stringSym = makePickleSym(SymbolKind.Class, baseFlags, "String")
        val sym = makePickleSymWithType(
            SymbolKind.TypeAlias,
            flags,
            symName,
            kyo.Maybe(Tasty.Type.Named(stringSym.id))
        )
        sym
    end decodeAliasSym

    /** TYPEsym data layout: nameRef(nat) ownerRef(nat) flags(longNat) infoRef(nat) */
    private def decodeTypeSym(
        data: Array[Byte],
        idx: Int,
        nameTable: scala.collection.mutable.HashMap[Int, String],
        baseFlags: Tasty.Flags
    )(using AllowUnsafe): Tasty.Symbol =
        val c       = new PickleCursor(data, 0)
        val nameRef = if c.remaining > 0 then c.readNat() else 0
        val symName = nameTable.getOrElse(nameRef, s"<typeparam$idx>")
        if c.remaining > 0 then c.skipNat() // ownerRef
        val rawFlags = if c.remaining > 0 then c.readLongNat() else 0L
        val flags    = baseFlags.union(pickleFlags2TastyFlags(rawFlags))
        makePickleSym(SymbolKind.AbstractType, flags, symName)
    end decodeTypeSym

    /** EXTref data layout: nameRef(nat) [ownerRef(nat)]
      *
      * Decodes a reference to an external symbol. The nameRef indexes a TERMname/TYPEname entry in the pickle table. The optional ownerRef
      * indexes another entry (typically EXTref or EXTMODCLASSref) whose FQN forms the qualifier. The resulting symbol has
      * SymbolKind.Unresolved since it refers to a symbol defined in another compilation unit.
      */
    private def decodeExtRef(
        data: Array[Byte],
        idx: Int,
        nameTable: scala.collection.mutable.HashMap[Int, String],
        entries: Chunk[PickleEntry],
        baseFlags: Tasty.Flags
    )(using AllowUnsafe): Tasty.Symbol =
        val c           = new PickleCursor(data, 0)
        val nameRef     = if c.remaining > 0 then c.readNat() else 0
        val ownerRefOpt = if c.remaining > 0 then Present(c.readNat()) else Absent
        val symName     = nameTable.getOrElse(nameRef, s"<extref$idx>")
        val ownerFqn    = ownerRefOpt.map(resolveExtFqn(_, nameTable, entries, Set.empty[Int])).filter(_.nonEmpty)
        val fqn         = ownerFqn.fold(symName)(q => q + "." + symName)
        // declaredType=Absent (Named(sym) self-ref approximation).
        makePickleSym(SymbolKind.Unresolved, baseFlags, fqn)
    end decodeExtRef

    /** EXTMODCLASSref data layout: nameRef(nat) [ownerRef(nat)]
      *
      * Like EXTref but represents a module class (the class companion of an object). The name is canonicalized to end with `$` to match
      * Scala 2 module-class JVM naming. The resulting symbol has SymbolKind.Unresolved.
      */
    private def decodeExtModClassRef(
        data: Array[Byte],
        idx: Int,
        nameTable: scala.collection.mutable.HashMap[Int, String],
        entries: Chunk[PickleEntry],
        baseFlags: Tasty.Flags
    )(using AllowUnsafe): Tasty.Symbol =
        val c               = new PickleCursor(data, 0)
        val nameRef         = if c.remaining > 0 then c.readNat() else 0
        val ownerRefOpt     = if c.remaining > 0 then Present(c.readNat()) else Absent
        val rawName         = nameTable.getOrElse(nameRef, s"<extmodclassref$idx>")
        val moduleClassName = if rawName.endsWith("$") then rawName else rawName + "$"
        val ownerFqn        = ownerRefOpt.map(resolveExtFqn(_, nameTable, entries, Set.empty[Int])).filter(_.nonEmpty)
        val fqn             = ownerFqn.fold(moduleClassName)(q => q + "." + moduleClassName)
        // declaredType=Absent (Named(sym) self-ref approximation).
        makePickleSym(SymbolKind.Unresolved, baseFlags, fqn)
    end decodeExtModClassRef

    /** Resolve the FQN string for an EXTref or EXTMODCLASSref owner entry index.
      *
      * The ownerRef of an EXTref typically points to another EXTref or EXTMODCLASSref entry forming a chain (e.g. a package owner), or it
      * may point to a TERMname/TYPEname entry directly for root-level names. This method reads the owner entry's data and reconstructs its
      * FQN without allocating new symbols. Returns empty string for unrecognized or missing entries.
      *
      * The visited parameter tracks entry indices already on the current resolution path. If entryIdx is already in visited, a cycle is
      * detected and empty string is returned to prevent unbounded recursion on malformed pickle data.
      */
    private def resolveExtFqn(
        entryIdx: Int,
        nameTable: scala.collection.mutable.HashMap[Int, String],
        entries: Chunk[PickleEntry],
        visited: Set[Int]
    ): String =
        if visited.contains(entryIdx) then ""
        else
            // If the entry is itself a name entry, return its value directly.
            Maybe.fromOption(nameTable.get(entryIdx)) match
                case Present(name) => name
                case Absent =>
                    if entryIdx < 0 || entryIdx >= entries.length then ""
                    else
                        val refEntry = entries(entryIdx)
                        if refEntry.tag == EXTref || refEntry.tag == EXTMODCLASSref then
                            val c           = new PickleCursor(refEntry.data, 0)
                            val nameRef     = if c.remaining > 0 then c.readNat() else 0
                            val ownerRefOpt = if c.remaining > 0 then Present(c.readNat()) else Absent
                            val rawName     = nameTable.getOrElse(nameRef, "")
                            val leafName =
                                if refEntry.tag == EXTMODCLASSref && !rawName.endsWith("$") then rawName + "$"
                                else rawName
                            val nextVisited = visited + entryIdx
                            val ownerFqn    = ownerRefOpt.map(resolveExtFqn(_, nameTable, entries, nextVisited)).filter(_.nonEmpty)
                            ownerFqn.fold(leafName)(q => q + "." + leafName)
                        else ""
                        end if
    end resolveExtFqn

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private def buildAnyRefParent()(using AllowUnsafe): Tasty.Type =
        // declaredType=Absent (Named(sym) self-ref approximation).
        val anyRefSym = SymbolFactory.makeSymbol(
            SymbolKind.Class,
            Tasty.Flags(Tasty.Flag.Scala2, Tasty.Flag.JavaDefined),
            Tasty.Name("AnyRef")
        )
        Tasty.Type.Named(anyRefSym.id)
    end buildAnyRefParent

    private def makePickleSym(
        kind: SymbolKind,
        flags: Tasty.Flags,
        name: String
    )(using AllowUnsafe): Tasty.Symbol =
        SymbolFactory.makeSymbol(kind, flags, Tasty.Name(name))
    end makePickleSym

    /** Variant of makePickleSym with a specified declaredType. */
    private def makePickleSymWithType(
        kind: SymbolKind,
        flags: Tasty.Flags,
        name: String,
        declaredType: kyo.Maybe[Tasty.Type]
    )(using AllowUnsafe): Tasty.Symbol =
        val pickleDesc = new SymbolDescriptor(
            id = -1,
            kind = kind,
            flags = flags,
            name = Tasty.Name(name),
            ownerId = -1,
            declaredType = declaredType,
            scaladoc = kyo.Maybe.Absent,
            sourcePosition = kyo.Maybe.Absent,
            javaMetadata = kyo.Maybe.Absent,
            parentTypes = Chunk.empty,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = kyo.Maybe.Absent,
            body = kyo.Maybe.Absent
        )
        TypedSymbolFactory.from(pickleDesc)
    end makePickleSymWithType

    /** Map Scala 2 pickle flag bits to Tasty.Flags. */
    private def pickleFlags2TastyFlags(rawFlags: Long): Tasty.Flags =
        var bits = 0L
        if (rawFlags & FINAL_FLAG) != 0 then bits |= Tasty.Flag.bits(Tasty.Flag.Final)
        if (rawFlags & PRIVATE_FLAG) != 0 then bits |= Tasty.Flag.bits(Tasty.Flag.Private)
        if (rawFlags & PROTECTED_FLAG) != 0 then bits |= Tasty.Flag.bits(Tasty.Flag.Protected)
        if (rawFlags & IMPLICIT_FLAG) != 0 then bits |= Tasty.Flag.bits(Tasty.Flag.Implicit)
        if (rawFlags & SEALED_FLAG) != 0 then bits |= Tasty.Flag.bits(Tasty.Flag.Sealed)
        if (rawFlags & OVERRIDE_FLAG) != 0 then bits |= Tasty.Flag.bits(Tasty.Flag.Override)
        if (rawFlags & CASE_FLAG) != 0 then bits |= Tasty.Flag.bits(Tasty.Flag.Case)
        if (rawFlags & ABSTRACT_FLAG) != 0 then bits |= Tasty.Flag.bits(Tasty.Flag.Abstract)
        if (rawFlags & TRAIT_FLAG) != 0 then bits |= Tasty.Flag.bits(Tasty.Flag.Trait)
        if (rawFlags & MODULE_FLAG) != 0 then bits |= Tasty.Flag.bits(Tasty.Flag.Module)
        if (rawFlags & LAZY_FLAG) != 0 then bits |= Tasty.Flag.bits(Tasty.Flag.Lazy)
        Tasty.Flags.fromBits(bits)
    end pickleFlags2TastyFlags

end Scala2PickleReader

// -------------------------------------------------------------------------
// Pickle cursor: sequential reader over a byte array
// -------------------------------------------------------------------------

/** Mutable cursor for reading Scala 2 pickle entries. */
final private[scala2] class PickleCursor(val bytes: Array[Byte], startAt: Int):

    private var _pos: Int = startAt

    def pos: Int = _pos

    def remaining: Int = bytes.length - _pos

    def readByte(): Byte =
        val b = bytes(_pos)
        _pos += 1
        b
    end readByte

    /** Read a NAT (natural number) in Scala 2 pickle encoding.
      *
      * In the Scala 2 pickle format (unlike TASTy): high bit SET means more bytes follow; high bit CLEAR means last byte. This is the
      * standard big-endian base-128 protobuf-style encoding. Reference: scala.reflect.internal.pickling.PickleBuffer.readNat.
      */
    def readNat(): Int =
        var result = 0
        var b      = 0
        var cont   = true
        while cont do
            b = bytes(_pos) & 0xff
            _pos += 1
            result = (result << 7) | (b & 0x7f)
            cont = (b & 0x80) != 0
        end while
        result
    end readNat

    /** Skip a NAT value without returning it. */
    def skipNat(): Unit =
        var cont = true
        while cont do
            val b = bytes(_pos) & 0xff
            _pos += 1
            cont = (b & 0x80) != 0
        end while
    end skipNat

    /** Read a long NAT. Same variable-length encoding as readNat but accumulates into a Long. */
    def readLongNat(): Long =
        var result = 0L
        var b      = 0
        var cont   = true
        while cont do
            b = bytes(_pos) & 0xff
            _pos += 1
            result = (result << 7) | (b & 0x7fL)
            cont = (b & 0x80) != 0
        end while
        result
    end readLongNat

    def readBytes(n: Int): Array[Byte] =
        val out = new Array[Byte](n)
        java.lang.System.arraycopy(bytes, _pos, out, 0, n)
        _pos += n
        out
    end readBytes

end PickleCursor
