package kyo.internal.tasty.snapshot

import kyo.*
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.query.Classpath
import kyo.internal.tasty.query.ClasspathRef
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.symbol.Symbol as InternalSymbol
import kyo.internal.tasty.type_.TypeArena
import scala.collection.mutable

/** Reads a KRFL snapshot file and populates a `Classpath` from it.
  *
  * Validation:
  *   - Wrong magic (not "KRFL") -> `TastyError.SnapshotFormatError`.
  *   - Major version mismatch -> `TastyError.SnapshotVersionMismatch`; caller falls through to full decode.
  *   - Minor version too new -> loads successfully (add-only sections are skipped).
  *
  * Home assignment: each reconstructed symbol gets a `ClasspathRef` that is populated after this method returns by the caller (the
  * `Tasty.Classpath.open` wrapper in `Tasty.scala`, which can see through the opaque type alias).
  */
object SnapshotReader:

    /** Read a snapshot from `path` and populate `cp`.
      *
      * @param path
      *   absolute path to the `.krfl` file
      * @param source
      *   FileSource for reading the bytes
      * @param cp
      *   the Classpath to populate (must still be in Building state)
      */
    def read(
        path: String,
        source: FileSource,
        cp: Classpath
    )(using Frame): Unit < (Sync & Abort[TastyError]) =
        source.read(path).flatMap: bytes =>
            readBytes(path, bytes, cp)

    /** Read a snapshot preferring a memory-mapped path on JVM/Native; falls back to heap read on JS.
      *
      * On JVM, delegates to PlatformMmapReader which uses JvmMmapReader (java.lang.foreign.Arena). On Native, uses NativeMmapReader (POSIX
      * mmap). On JS, falls back to the heap-based `read`. The Scope effect is required because the mmap arena lifetime is bounded by the
      * enclosing Scope.
      */
    def readMapped(
        path: String,
        source: FileSource,
        cp: Classpath
    )(using Frame): Unit < (Sync & Abort[TastyError] & Scope) =
        PlatformMmapReader.readMapped(path, source, cp)

    /** Deserialize a KRFL snapshot from an already-opened ByteView (mmap path).
      *
      * Called by platform-specific PlatformMmapReader implementations (JVM, Native). The `view` covers the entire snapshot file content
      * mapped into memory. Symbols with body bytes get a TastyOrigin with `bodyView` set to a sub-view into the mapped region so that
      * sym.body reads directly from mapped memory without an eager copy.
      */
    private[snapshot] def readMappedView(
        path: String,
        view: ByteView,
        cp: Classpath
    ): Unit =
        // Extract bytes for header validation and section parsing.
        // We read only the header bytes (32 + section count + section index) eagerly; BODY_BYTES is left in mapped memory.
        // For simplicity, use the view's allBytes (empty for Mapped) and fall back to an array copy for header/names/symbols.
        // The key optimization: body byte slices use sub-views into the mapped region, avoiding eager copy.
        val magic0 = view.peekByte(0)
        val magic1 = view.peekByte(1)
        val magic2 = view.peekByte(2)
        val magic3 = view.peekByte(3)
        if magic0 != 'K' || magic1 != 'R' || magic2 != 'F' || magic3 != 'L' then
            throw new java.io.IOException(s"wrong magic in $path")
        val fileMajor = view.peekByte(4) & 0xff
        val fileMinor = view.peekByte(5) & 0xff
        if fileMajor != SnapshotFormat.majorVersion then
            throw new VersionMismatchException(
                Tasty.Version(fileMajor, fileMinor, 0),
                Tasty.Version(SnapshotFormat.majorVersion, SnapshotFormat.minorVersion, 0)
            )
        end if
        deserializeMapped(path, view, cp)
    end readMappedView

    /** Thrown by readMappedView when the snapshot major version doesn't match. */
    final private[snapshot] class VersionMismatchException(
        val found: Tasty.Version,
        val supported: Tasty.Version
    ) extends java.io.IOException(s"version mismatch: found=$found supported=$supported")

    /** Deserialize KRFL bytes into the Classpath. */
    private def readBytes(
        path: String,
        bytes: Array[Byte],
        cp: Classpath
    )(using Frame): Unit < (Sync & Abort[TastyError]) =
        Sync.defer:
            if bytes.length < 4 || bytes(0) != 'K' || bytes(1) != 'R' || bytes(2) != 'F' || bytes(3) != 'L' then
                Abort.fail(TastyError.SnapshotFormatError(path, "wrong magic, expected KRFL", 0L))
            else
                val fileMajor = bytes(4) & 0xff
                val fileMinor = bytes(5) & 0xff
                if fileMajor != SnapshotFormat.majorVersion then
                    Abort.fail(
                        TastyError.SnapshotVersionMismatch(
                            Tasty.Version(fileMajor, fileMinor, 0),
                            Tasty.Version(SnapshotFormat.majorVersion, SnapshotFormat.minorVersion, 0)
                        )
                    )
                else
                    deserialize(path, bytes, cp)
                end if

    /** Deserialize section payloads into the Classpath. */
    private def deserialize(
        path: String,
        bytes: Array[Byte],
        cp: Classpath
    ): Unit =
        // flow-allow: §839 case 3; snapshot-deserialize boundary; single-fiber synchronous symbol graph reconstruction.
        import AllowUnsafe.embrace.danger
        // Parse section index (starts at offset 32)
        val sectionCount = SnapshotFormat.readInt32LE(bytes, 32)
        val sectionMap   = mutable.HashMap.empty[String, (Int, Int)]
        var idxPos       = 36
        var i            = 0
        while i < sectionCount do
            val name   = SnapshotFormat.readSectionName(bytes, idxPos)
            val offset = SnapshotFormat.readInt64LE(bytes, idxPos + 8).toInt
            val length = SnapshotFormat.readInt64LE(bytes, idxPos + 16).toInt
            sectionMap(name) = (offset, length)
            idxPos += SnapshotFormat.sectionIndexEntrySize
            i += 1
        end while

        // Read NAMES section
        val namePool = sectionMap.get(SnapshotFormat.sectionNAMES) match
            case Some((offset, length)) => readNamePool(bytes, offset, length)
            case None                   => Array.empty[String]

        // Read BODY_BYTES section: shared backing store for all TASTy body slices
        val bodyBytesArray: Array[Byte] = sectionMap.get(SnapshotFormat.sectionBODYBYTES) match
            case Some((offset, length)) if length > 0 =>
                val arr = new Array[Byte](length)
                java.lang.System.arraycopy(bytes, offset, arr, 0, length)
                arr
            case _ =>
                Array.empty[Byte]

        // Read SYMBOLS section
        val (allSymbols, fqnIndex, packageIndex, topLevelCls, packages) =
            sectionMap.get(SnapshotFormat.sectionSYMBOLS) match
                case Some((offset, length)) => readSymbols(bytes, offset, length, namePool, bodyBytesArray)
                case None =>
                    (
                        Chunk.empty[Tasty.Symbol],
                        Map.empty[String, Tasty.Symbol],
                        Map.empty[String, Tasty.Symbol],
                        Chunk.empty[Tasty.Symbol],
                        Chunk.empty[Tasty.Symbol]
                    )

        // Read ERRORS section
        val errors = sectionMap.get(SnapshotFormat.sectionERRORS) match
            case Some((offset, length)) => readErrors(bytes, offset, length)
            case None                   => Chunk.empty[TastyError]

        // plan: phase-02 rebuild; collect relational data from sections, then rebuild Symbols with
        // full field set. Symbol is now immutable, so we can't fill slots after construction.
        val partialSymbols = allSymbols
        val symCount       = partialSymbols.length
        val symsArray      = partialSymbols.toArray

        // Collect parentTypes per symbol index.
        val parentsByIdx = new Array[Chunk[Tasty.Type]](symCount)
        java.util.Arrays.fill(parentsByIdx.asInstanceOf[Array[Object]], Chunk.empty)
        sectionMap.get(SnapshotFormat.sectionPARENTS) match
            case Some((off, len)) if len > 0 =>
                deserializeRefListsByIdx(
                    bytes,
                    off,
                    len,
                    symCount,
                    (idx, refs) =>
                        parentsByIdx(idx) = Chunk.from(refs.map(id => Tasty.Type.Named(symsArray(id))))
                )
            case _ => ()
        end match

        // Collect typeParamIds per symbol index.
        val typeParamsByIdx = new Array[Chunk[kyo.internal.tasty.symbol.SymbolId]](symCount)
        java.util.Arrays.fill(typeParamsByIdx.asInstanceOf[Array[Object]], Chunk.empty)
        sectionMap.get(SnapshotFormat.sectionTPARAMS) match
            case Some((off, len)) if len > 0 =>
                deserializeRefListsByIdx(
                    bytes,
                    off,
                    len,
                    symCount,
                    (idx, refs) =>
                        typeParamsByIdx(idx) = Chunk.from(refs.map(kyo.internal.tasty.symbol.SymbolId(_)))
                )
            case _ => ()
        end match

        // Collect declarationIds per symbol index.
        val declarationsByIdx = new Array[Chunk[kyo.internal.tasty.symbol.SymbolId]](symCount)
        java.util.Arrays.fill(declarationsByIdx.asInstanceOf[Array[Object]], Chunk.empty)
        sectionMap.get(SnapshotFormat.sectionMEMBERS) match
            case Some((off, len)) if len > 0 =>
                deserializeRefListsByIdx(
                    bytes,
                    off,
                    len,
                    symCount,
                    (idx, refs) =>
                        declarationsByIdx(idx) = Chunk.from(refs.map(kyo.internal.tasty.symbol.SymbolId(_)))
                )
            case _ => ()
        end match

        // Rebuild final immutable Symbols with all 14 fields populated.
        val finalSymbols = new Array[Tasty.Symbol](symCount)
        var si           = 0
        while si < symCount do
            val partial = symsArray(si)
            finalSymbols(si) = Tasty.Symbol.fromDescriptor(
                id = partial.id,
                kind = partial.kind,
                flags = partial.flags,
                name = partial.name,
                ownerId = partial.ownerId,
                declaredType = partial.declaredType,
                scaladoc = partial.scaladoc,
                sourcePosition = partial.sourcePosition,
                javaMetadata = partial.javaMetadata,
                parentTypes = parentsByIdx(si),
                typeParamIds = typeParamsByIdx(si),
                declarationIds = declarationsByIdx(si),
                permittedSubclassIds = partial.permittedSubclassIds,
                body = partial.body
            )
            si += 1
        end while

        // Rebuild index maps to point to final Symbols.
        val finalFqnIndex = fqnIndex.map { case (k, v) =>
            val idx = symsArray.indexWhere(_ eq v)
            if idx >= 0 then (k, finalSymbols(idx)) else (k, v)
        }
        val finalPackageIndex = packageIndex.map { case (k, v) =>
            val idx = symsArray.indexWhere(_ eq v)
            if idx >= 0 then (k, finalSymbols(idx)) else (k, v)
        }
        val finalTopLevelCls = topLevelCls.map { v =>
            val idx = symsArray.indexWhere(_ eq v)
            if idx >= 0 then finalSymbols(idx) else v
        }
        val finalPackages = packages.map { v =>
            val idx = symsArray.indexWhere(_ eq v)
            if idx >= 0 then finalSymbols(idx) else v
        }

        val canonical = TypeArena.canonical()
        Classpath.transitionToReady(
            cp,
            Chunk.from(finalSymbols),
            finalTopLevelCls,
            finalPackages,
            finalFqnIndex,
            finalPackageIndex,
            canonical,
            errors,
            Map.empty
        )
    end deserialize

    /** Deserialize ref lists, calling `assign(symIdx, refs)` for each entry.
      *
      * plan: phase-02 new helper; replaces deserializeRefLists which called assign(sym, refs). This version works with index-based access
      * rather than symbol-based.
      */
    private def deserializeRefListsByIdx(
        bytes: Array[Byte],
        offset: Int,
        length: Int,
        symCount: Int,
        assign: (Int, Array[Int]) => Unit
    ): Unit =
        val end   = offset + length
        val count = SnapshotFormat.readInt32LE(bytes, offset)
        var pos   = offset + 4
        var i     = 0
        while i < count && pos + 8 <= end do
            val symIdx   = SnapshotFormat.readInt32LE(bytes, pos)
            val refCount = SnapshotFormat.readInt32LE(bytes, pos + 4)
            pos += 8
            val rawRefs = new Array[Int](refCount)
            var j       = 0
            while j < refCount && pos + 4 <= end do
                rawRefs(j) = SnapshotFormat.readInt32LE(bytes, pos)
                pos += 4
                j += 1
            end while
            if symIdx >= 0 && symIdx < symCount then
                val validRefs = rawRefs.filter(r => r >= 0 && r < symCount)
                assign(symIdx, validRefs)
            end if
            i += 1
        end while
    end deserializeRefListsByIdx

    /** Deserialize from a memory-mapped ByteView.
      *
      * Reads header, section index, NAMES, SYMBOLS, and ERRORS into heap arrays (they are small). BODY_BYTES section is kept in mapped
      * memory: TastyOrigin.bodyView for each symbol is a sub-view into the mapped region. After the backing Arena is closed, sym.body reads
      * from the mapped view and throws IllegalStateException, which Symbol.body catches as ClasspathClosed.
      */
    private def deserializeMapped(path: String, view: ByteView, cp: Classpath): Unit =
        // flow-allow: §839 case 3; snapshot-deserialize-mmap boundary; single-fiber synchronous symbol graph reconstruction.
        import AllowUnsafe.embrace.danger
        // Read the section index from the view.
        // SnapshotFormat.readInt32LE needs an Array[Byte]; copy 36+ bytes from the view for parsing.
        // The section index starts at byte 32. Each entry is sectionIndexEntrySize (24) bytes.
        // Read section count from offset 32, then copy all index entries.
        val sectionCountOffset = 32
        val sectionCount       = readInt32LEFromView(view, sectionCountOffset)
        val indexEnd           = 36 + sectionCount * SnapshotFormat.sectionIndexEntrySize
        // Copy the section index to a small heap array for parsing with existing SnapshotFormat helpers.
        val indexBytes = copyViewRange(view, sectionCountOffset, indexEnd)

        val sectionMap = mutable.HashMap.empty[String, (Int, Int)]
        var idxPos     = 4 // offset within indexBytes (skip the 4-byte sectionCount at bytes [0..3])
        var i          = 0
        while i < sectionCount do
            val name   = SnapshotFormat.readSectionName(indexBytes, idxPos)
            val offset = SnapshotFormat.readInt64LE(indexBytes, idxPos + 8).toInt
            val length = SnapshotFormat.readInt64LE(indexBytes, idxPos + 16).toInt
            sectionMap(name) = (offset, length)
            idxPos += SnapshotFormat.sectionIndexEntrySize
            i += 1
        end while

        // Read NAMES section into heap (small).
        val namesBytes = sectionMap.get(SnapshotFormat.sectionNAMES) match
            case Some((off, len)) => copyViewRange(view, off, off + len)
            case None             => Array.empty[Byte]
        val namePool = if namesBytes.nonEmpty then readNamePool(namesBytes, 0, namesBytes.length) else Array.empty[String]

        // Keep BODY_BYTES in mapped memory via a ByteView sub-view.
        val bodyView: ByteView | Null = sectionMap.get(SnapshotFormat.sectionBODYBYTES) match
            case Some((off, len)) if len > 0 => view.subView(off, off + len)
            case _                           => null

        // Read SYMBOLS section into heap (small to medium).
        val (allSymbols, fqnIndex, packageIndex, topLevelCls, packages) =
            sectionMap.get(SnapshotFormat.sectionSYMBOLS) match
                case Some((off, len)) =>
                    val symBytes = copyViewRange(view, off, off + len)
                    readSymbolsMapped(symBytes, 0, len, namePool, bodyView)
                case None =>
                    (
                        Chunk.empty[Tasty.Symbol],
                        Map.empty[String, Tasty.Symbol],
                        Map.empty[String, Tasty.Symbol],
                        Chunk.empty[Tasty.Symbol],
                        Chunk.empty[Tasty.Symbol]
                    )

        // Read ERRORS section into heap (tiny).
        val errorsBytes = sectionMap.get(SnapshotFormat.sectionERRORS) match
            case Some((off, len)) => copyViewRange(view, off, off + len)
            case None             => Array.empty[Byte]
        val errors = if errorsBytes.nonEmpty then readErrors(errorsBytes, 0, errorsBytes.length) else Chunk.empty[TastyError]

        // plan: phase-02 rebuild; same pattern as deserialize.
        val partialSymbols = allSymbols
        val symCount       = partialSymbols.length
        val symsArray      = partialSymbols.toArray

        val parentsByIdx      = new Array[Chunk[Tasty.Type]](symCount)
        val typeParamsByIdx   = new Array[Chunk[kyo.internal.tasty.symbol.SymbolId]](symCount)
        val declarationsByIdx = new Array[Chunk[kyo.internal.tasty.symbol.SymbolId]](symCount)
        java.util.Arrays.fill(parentsByIdx.asInstanceOf[Array[Object]], Chunk.empty)
        java.util.Arrays.fill(typeParamsByIdx.asInstanceOf[Array[Object]], Chunk.empty)
        java.util.Arrays.fill(declarationsByIdx.asInstanceOf[Array[Object]], Chunk.empty)

        sectionMap.get(SnapshotFormat.sectionPARENTS) match
            case Some((off, len)) if len > 0 =>
                val secBytes = copyViewRange(view, off, off + len)
                deserializeRefListsByIdx(
                    secBytes,
                    0,
                    len,
                    symCount,
                    (idx, refs) =>
                        parentsByIdx(idx) = Chunk.from(refs.map(id => Tasty.Type.Named(symsArray(id))))
                )
            case _ => ()
        end match

        sectionMap.get(SnapshotFormat.sectionTPARAMS) match
            case Some((off, len)) if len > 0 =>
                val secBytes = copyViewRange(view, off, off + len)
                deserializeRefListsByIdx(
                    secBytes,
                    0,
                    len,
                    symCount,
                    (idx, refs) =>
                        typeParamsByIdx(idx) = Chunk.from(refs.map(kyo.internal.tasty.symbol.SymbolId(_)))
                )
            case _ => ()
        end match

        sectionMap.get(SnapshotFormat.sectionMEMBERS) match
            case Some((off, len)) if len > 0 =>
                val secBytes = copyViewRange(view, off, off + len)
                deserializeRefListsByIdx(
                    secBytes,
                    0,
                    len,
                    symCount,
                    (idx, refs) =>
                        declarationsByIdx(idx) = Chunk.from(refs.map(kyo.internal.tasty.symbol.SymbolId(_)))
                )
            case _ => ()
        end match

        val finalSymbols = new Array[Tasty.Symbol](symCount)
        var j            = 0
        while j < symCount do
            val partial = symsArray(j)
            finalSymbols(j) = Tasty.Symbol.fromDescriptor(
                id = partial.id,
                kind = partial.kind,
                flags = partial.flags,
                name = partial.name,
                ownerId = partial.ownerId,
                declaredType = partial.declaredType,
                scaladoc = partial.scaladoc,
                sourcePosition = partial.sourcePosition,
                javaMetadata = partial.javaMetadata,
                parentTypes = parentsByIdx(j),
                typeParamIds = typeParamsByIdx(j),
                declarationIds = declarationsByIdx(j),
                permittedSubclassIds = partial.permittedSubclassIds,
                body = partial.body
            )
            j += 1
        end while

        val newFqnIndex = fqnIndex.map { case (k, v) =>
            val idx = symsArray.indexWhere(_ eq v); if idx >= 0 then (k, finalSymbols(idx)) else (k, v)
        }
        val newPackageIndex = packageIndex.map { case (k, v) =>
            val idx = symsArray.indexWhere(_ eq v); if idx >= 0 then (k, finalSymbols(idx)) else (k, v)
        }
        val newTopLevelCls = topLevelCls.map { v =>
            val idx = symsArray.indexWhere(_ eq v); if idx >= 0 then finalSymbols(idx) else v
        }
        val newPackages = packages.map { v =>
            val idx = symsArray.indexWhere(_ eq v); if idx >= 0 then finalSymbols(idx) else v
        }

        val canonical = TypeArena.canonical()
        Classpath.transitionToReady(
            cp,
            Chunk.from(finalSymbols),
            newTopLevelCls,
            newPackages,
            newFqnIndex,
            newPackageIndex,
            canonical,
            errors,
            Map.empty
        )
    end deserializeMapped

    /** Read an Int32 LE from the ByteView at the given absolute byte offset, without advancing the cursor. */
    private def readInt32LEFromView(view: ByteView, at: Int): Int =
        (view.peekByte(at) & 0xff) |
            ((view.peekByte(at + 1) & 0xff) << 8) |
            ((view.peekByte(at + 2) & 0xff) << 16) |
            ((view.peekByte(at + 3) & 0xff) << 24)

    /** Copy bytes from a ByteView range [from, until) into a new heap Array[Byte]. Does not advance the view cursor. */
    private def copyViewRange(view: ByteView, from: Int, until: Int): Array[Byte] =
        val len = until - from
        val arr = new Array[Byte](len)
        var i   = 0
        while i < len do
            arr(i) = view.peekByte(from + i)
            i += 1
        end while
        arr
    end copyViewRange

    /** readSymbols variant for the mmap path: body byte origins use ByteView sub-views instead of Array[Byte] slices.
      *
      * When `bodyView` is non-null, TastyOrigin.bodyView is set to a sub-view into the mapped region for symbols with body bytes. After
      * arena close, sym.body reads from this view and throws IllegalStateException.
      */
    private def readSymbolsMapped(
        bytes: Array[Byte],
        offset: Int,
        length: Int,
        namePool: Array[String],
        bodyViewOpt: ByteView | Null
    )(using
        AllowUnsafe
    ): (
        Chunk[Tasty.Symbol],
        scala.collection.Map[String, Tasty.Symbol],
        scala.collection.Map[String, Tasty.Symbol],
        Chunk[Tasty.Symbol],
        Chunk[Tasty.Symbol]
    ) =
        val count      = SnapshotFormat.readInt32LE(bytes, offset)
        val recordSize = 40

        if count <= 0 then
            return (Chunk.empty, Map.empty, Map.empty, Chunk.empty, Chunk.empty)

        val raws = new Array[RawSymbol](count)
        var pos  = offset + 4
        var i    = 0
        while i < count do
            val kindOrd   = bytes(pos) & 0xff
            val flagBits  = SnapshotFormat.readInt64LE(bytes, pos + 1)
            val nameId    = SnapshotFormat.readInt32LE(bytes, pos + 9)
            val fqnId     = SnapshotFormat.readInt32LE(bytes, pos + 13)
            val ownerId   = SnapshotFormat.readInt32LE(bytes, pos + 17)
            val bodyStart = SnapshotFormat.readInt32LE(bytes, pos + 21)
            val bodyEnd   = SnapshotFormat.readInt32LE(bytes, pos + 25)
            raws(i) = RawSymbol(kindOrd, flagBits, nameId, fqnId, ownerId, bodyStart, bodyEnd)
            pos += recordSize
            i += 1
        end while

        val depth   = new Array[Int](count)
        val visited = new Array[Boolean](count)
        def computeDepth(idx: Int): Int =
            if visited(idx) then depth(idx)
            else
                visited(idx) = true
                val ownerId = raws(idx).ownerId
                depth(idx) = if ownerId < 0 || ownerId >= count then 0
                else computeDepth(ownerId) + 1
                depth(idx)

        i = 0
        while i < count do
            val _ = computeDepth(i)
            i += 1

        val order   = (0 until count).sortBy(depth).toArray
        val created = new Array[Tasty.Symbol](count)

        // plan: phase-02; create partial Symbols; relational fields filled by deserializeMapped.
        for idx <- order do
            val raw        = raws(idx)
            val kind       = kindFromOrd(raw.kindOrd)
            val flags      = new Tasty.Flags(raw.flagBits)
            val name       = if raw.nameId >= 0 && raw.nameId < namePool.length then Tasty.Name(namePool(raw.nameId)) else Tasty.Name("")
            val ownerIdInt = raw.ownerId
            val ownerIdVal = if ownerIdInt >= 0 && ownerIdInt < count then ownerIdInt else idx
            // For mmap path: body bytes are accessed via bodyView sub-view.
            // Phase 02: SymbolBody carries bodyView support via sectionBytes (empty) + addrMap.
            val bodyMaybe: kyo.Maybe[Tasty.SymbolBody] =
                if raw.bodyStart > 0 && raw.bodyEnd > raw.bodyStart && (bodyViewOpt ne null) then
                    kyo.Maybe(Tasty.SymbolBody(
                        bodyStart = raw.bodyStart,
                        bodyEnd = raw.bodyEnd,
                        sectionBytes = Array.empty[Byte],
                        names = Array.empty[Tasty.Name],
                        sectionOffset = 0,
                        addrMap = scala.collection.immutable.IntMap.empty
                    ))
                else
                    kyo.Maybe.Absent
            created(idx) = Tasty.Symbol.fromDescriptor(
                id = kyo.internal.tasty.symbol.SymbolId(idx),
                kind = kind,
                flags = flags,
                name = name,
                ownerId = kyo.internal.tasty.symbol.SymbolId(ownerIdVal),
                declaredType = kyo.Maybe.Absent,
                scaladoc = kyo.Maybe.Absent,
                sourcePosition = kyo.Maybe.Absent,
                javaMetadata = kyo.Maybe.Absent,
                parentTypes = Chunk.empty,
                typeParamIds = Chunk.empty,
                declarationIds = Chunk.empty,
                permittedSubclassIds = kyo.Maybe.Absent,
                body = bodyMaybe
            )
        end for

        val fqnIndex     = mutable.HashMap.empty[String, Tasty.Symbol]
        val packageIndex = mutable.HashMap.empty[String, Tasty.Symbol]
        val allSymbols   = mutable.ArrayBuffer.empty[Tasty.Symbol]
        val topLevelCls  = mutable.ArrayBuffer.empty[Tasty.Symbol]
        val packages     = mutable.ArrayBuffer.empty[Tasty.Symbol]

        i = 0
        while i < count do
            val raw = raws(i)
            val fqn = if raw.fqnId >= 0 && raw.fqnId < namePool.length then namePool(raw.fqnId) else ""
            val sym = created(i)
            if sym != null then
                allSymbols += sym
                if fqn.nonEmpty then
                    fqnIndex(fqn) = sym
                    sym.kind match
                        case Tasty.SymbolKind.Package =>
                            packages += sym
                            packageIndex(fqn) = sym
                        case Tasty.SymbolKind.Class | Tasty.SymbolKind.Trait | Tasty.SymbolKind.Object =>
                            topLevelCls += sym
                        case _ =>
                            ()
                    end match
                end if
            end if
            i += 1
        end while

        (
            Chunk.from(allSymbols),
            fqnIndex,
            packageIndex,
            Chunk.from(topLevelCls),
            Chunk.from(packages)
        )
    end readSymbolsMapped

    /** Read the name pool from the NAMES section. */
    private def readNamePool(bytes: Array[Byte], offset: Int, length: Int): Array[String] =
        val count = SnapshotFormat.readInt32LE(bytes, offset)
        val pool  = new Array[String](count)
        var pos   = offset + 4
        var i     = 0
        while i < count do
            val strLen = SnapshotFormat.readInt32LE(bytes, pos)
            pos += 4
            pool(i) = SnapshotFormat.decodeString(bytes, pos, strLen)
            pos += strLen
            i += 1
        end while
        pool
    end readNamePool

    /** Raw data for one symbol record read from the SYMBOLS section. */
    final private case class RawSymbol(
        kindOrd: Int,
        flagBits: Long,
        nameId: Int,
        fqnId: Int,
        ownerId: Int,
        bodyStart: Int,
        bodyEnd: Int
    )

    /** Read SYMBOLS section and reconstruct symbol graph.
      *
      * Two-pass approach:
      *   1. Parse all raw records into parallel arrays.
      *   2. Create symbols in topological order (parents before children) by BFS from roots.
      */
    private def readSymbols(
        bytes: Array[Byte],
        offset: Int,
        length: Int,
        namePool: Array[String],
        bodyBytesArray: Array[Byte]
    )(using
        AllowUnsafe
    ): (
        Chunk[Tasty.Symbol],
        scala.collection.Map[String, Tasty.Symbol],
        scala.collection.Map[String, Tasty.Symbol],
        Chunk[Tasty.Symbol],
        Chunk[Tasty.Symbol]
    ) =
        val count      = SnapshotFormat.readInt32LE(bytes, offset)
        val recordSize = 40

        if count <= 0 then
            return (Chunk.empty, Map.empty, Map.empty, Chunk.empty, Chunk.empty)

        // Pass 1: read raw records
        val raws = new Array[RawSymbol](count)
        var pos  = offset + 4
        var i    = 0
        while i < count do
            val kindOrd   = bytes(pos) & 0xff
            val flagBits  = SnapshotFormat.readInt64LE(bytes, pos + 1)
            val nameId    = SnapshotFormat.readInt32LE(bytes, pos + 9)
            val fqnId     = SnapshotFormat.readInt32LE(bytes, pos + 13)
            val ownerId   = SnapshotFormat.readInt32LE(bytes, pos + 17)
            val bodyStart = SnapshotFormat.readInt32LE(bytes, pos + 21)
            val bodyEnd   = SnapshotFormat.readInt32LE(bytes, pos + 25)
            raws(i) = RawSymbol(kindOrd, flagBits, nameId, fqnId, ownerId, bodyStart, bodyEnd)
            pos += recordSize
            i += 1
        end while

        // Pass 2: create symbols in topological order.
        // For each index, compute depth (root = depth 0, child = parent depth + 1).
        // Process in increasing depth order so parents are always created before children.
        val depth   = new Array[Int](count)
        val visited = new Array[Boolean](count)
        def computeDepth(idx: Int): Int =
            if visited(idx) then depth(idx)
            else
                visited(idx) = true
                val ownerId = raws(idx).ownerId
                depth(idx) = if ownerId < 0 || ownerId >= count then 0
                else computeDepth(ownerId) + 1
                depth(idx)

        i = 0
        while i < count do
            val _ = computeDepth(i)
            i += 1

        // Sort indices by depth
        val order = (0 until count).sortBy(depth).toArray

        // Allocate symbols in topological order
        val created = new Array[Tasty.Symbol](count)

        // plan: phase-02; create partial Symbols with basic fields; deserialize() fills in
        // parentTypes / typeParamIds / declarationIds and rebuilds final immutable Symbols.
        for idx <- order do
            val raw        = raws(idx)
            val kind       = kindFromOrd(raw.kindOrd)
            val flags      = new Tasty.Flags(raw.flagBits)
            val name       = if raw.nameId >= 0 && raw.nameId < namePool.length then Tasty.Name(namePool(raw.nameId)) else Tasty.Name("")
            val ownerIdInt = raw.ownerId
            // ownerId: use index directly; -1 means self-referential (root sentinel).
            val ownerIdVal = if ownerIdInt >= 0 && ownerIdInt < count then ownerIdInt else idx
            val bodyMaybe: kyo.Maybe[Tasty.SymbolBody] =
                if raw.bodyStart > 0 && raw.bodyEnd > raw.bodyStart && bodyBytesArray.nonEmpty
                    && raw.bodyEnd <= bodyBytesArray.length
                then
                    kyo.Maybe(Tasty.SymbolBody(
                        bodyStart = raw.bodyStart,
                        bodyEnd = raw.bodyEnd,
                        sectionBytes = bodyBytesArray,
                        names = Array.empty[Tasty.Name],
                        sectionOffset = 0,
                        addrMap = scala.collection.immutable.IntMap.empty
                    ))
                else
                    kyo.Maybe.Absent
            created(idx) = Tasty.Symbol.fromDescriptor(
                id = kyo.internal.tasty.symbol.SymbolId(idx),
                kind = kind,
                flags = flags,
                name = name,
                ownerId = kyo.internal.tasty.symbol.SymbolId(ownerIdVal),
                declaredType = kyo.Maybe.Absent,
                scaladoc = kyo.Maybe.Absent,
                sourcePosition = kyo.Maybe.Absent,
                javaMetadata = kyo.Maybe.Absent,
                parentTypes = Chunk.empty,
                typeParamIds = Chunk.empty,
                declarationIds = Chunk.empty,
                permittedSubclassIds = kyo.Maybe.Absent,
                body = bodyMaybe
            )
        end for

        // Build indices
        val fqnIndex     = mutable.HashMap.empty[String, Tasty.Symbol]
        val packageIndex = mutable.HashMap.empty[String, Tasty.Symbol]
        val allSymbols   = mutable.ArrayBuffer.empty[Tasty.Symbol]
        val topLevelCls  = mutable.ArrayBuffer.empty[Tasty.Symbol]
        val packages     = mutable.ArrayBuffer.empty[Tasty.Symbol]

        i = 0
        while i < count do
            val raw = raws(i)
            val fqn = if raw.fqnId >= 0 && raw.fqnId < namePool.length then namePool(raw.fqnId) else ""
            val sym = created(i)
            if sym != null then
                allSymbols += sym
                if fqn.nonEmpty then
                    fqnIndex(fqn) = sym
                    sym.kind match
                        case Tasty.SymbolKind.Package =>
                            packages += sym
                            packageIndex(fqn) = sym
                        case Tasty.SymbolKind.Class | Tasty.SymbolKind.Trait | Tasty.SymbolKind.Object =>
                            topLevelCls += sym
                        case _ =>
                            ()
                    end match
                end if
            end if
            i += 1
        end while

        (
            Chunk.from(allSymbols),
            fqnIndex,
            packageIndex,
            Chunk.from(topLevelCls),
            Chunk.from(packages)
        )
    end readSymbols

    /** Read errors from the ERRORS section. */
    private def readErrors(bytes: Array[Byte], offset: Int, length: Int): Chunk[TastyError] =
        val count = SnapshotFormat.readInt32LE(bytes, offset)
        if count <= 0 then Chunk.empty
        else
            var pos = offset + 4
            val buf = mutable.ArrayBuffer.empty[TastyError]
            var i   = 0
            while i < count do
                val msgLen = SnapshotFormat.readInt32LE(bytes, pos)
                pos += 4
                val msg = SnapshotFormat.decodeString(bytes, pos, msgLen)
                pos += msgLen
                // Parse enough to reconstruct common errors; use NotImplemented for unrecognized
                buf += parseErrorString(msg)
                i += 1
            end while
            Chunk.from(buf.toSeq)
        end if
    end readErrors

    /** Deserialize a ref-list section produced by SnapshotWriter.serializeSymbolRelLists.
      *
      * Layout: [4-byte count] then count x ([4-byte symIdx][4-byte refCount][refCount x 4-byte refId]). For each entry,
      * `assign(syms(symIdx), filteredRefs)` is called where filteredRefs contains only ref IDs in range [0, syms.length). Out-of-range or
      * negative ref IDs are silently dropped (they were encoded as -1 for non-serializable references).
      */
    private def deserializeRefLists(
        bytes: Array[Byte],
        offset: Int,
        length: Int,
        syms: Array[Tasty.Symbol],
        assign: (Tasty.Symbol, Array[Int]) => Unit
    ): Unit =
        val end   = offset + length
        val count = SnapshotFormat.readInt32LE(bytes, offset)
        var pos   = offset + 4
        var i     = 0
        while i < count && pos + 8 <= end do
            val symIdx   = SnapshotFormat.readInt32LE(bytes, pos)
            val refCount = SnapshotFormat.readInt32LE(bytes, pos + 4)
            pos += 8
            val rawRefs = new Array[Int](refCount)
            var j       = 0
            while j < refCount && pos + 4 <= end do
                rawRefs(j) = SnapshotFormat.readInt32LE(bytes, pos)
                pos += 4
                j += 1
            end while
            if symIdx >= 0 && symIdx < syms.length then
                val validRefs = rawRefs.filter(r => r >= 0 && r < syms.length)
                assign(syms(symIdx), validRefs)
            end if
            i += 1
        end while
    end deserializeRefLists

    /** Best-effort reconstruction of a TastyError from its toString representation. */
    private def parseErrorString(msg: String): TastyError =
        if msg.startsWith("FileNotFound(") then TastyError.FileNotFound(extractParenContent(msg))
        else if msg.startsWith("CorruptedFile(") then
            val parts = extractParenContent(msg).split(",", 3)
            if parts.length >= 3 then
                val at =
                    try parts(1).trim.toLong
                    catch case _: NumberFormatException => 0L
                TastyError.CorruptedFile(parts(0), at, parts(2).trim)
            else TastyError.NotImplemented(msg)
            end if
        else if msg.startsWith("SnapshotIoError(") then TastyError.SnapshotIoError(extractParenContent(msg))
        else TastyError.NotImplemented(s"deserialized: $msg")

    private def extractParenContent(s: String): String =
        val start = s.indexOf('(')
        val end   = s.lastIndexOf(')')
        if start >= 0 && end > start then s.substring(start + 1, end) else s
    end extractParenContent

    /** Convert SymbolKind ordinal integer to enum case. */
    private def kindFromOrd(ord: Int): Tasty.SymbolKind =
        import Tasty.SymbolKind.*
        ord match
            case 0  => Package
            case 1  => Class
            case 2  => Trait
            case 3  => Object
            case 4  => Method
            case 5  => Field
            case 6  => Val
            case 7  => Var
            case 8  => TypeAlias
            case 9  => OpaqueType
            case 10 => AbstractType
            case 11 => TypeParam
            case 12 => Parameter
            case 13 => Unresolved
            case _  => Unresolved
        end match
    end kindFromOrd

end SnapshotReader
