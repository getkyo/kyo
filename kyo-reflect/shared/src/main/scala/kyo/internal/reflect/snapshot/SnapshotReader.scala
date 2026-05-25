package kyo.internal.reflect.snapshot

import kyo.*
import kyo.internal.reflect.binary.ByteView
import kyo.internal.reflect.query.Classpath
import kyo.internal.reflect.query.ClasspathRef
import kyo.internal.reflect.query.FileSource
import kyo.internal.reflect.symbol.Symbol as InternalSymbol
import kyo.internal.reflect.type_.TypeArena
import scala.collection.mutable

/** Reads a KRFL snapshot file and populates a `Classpath` from it.
  *
  * Validation:
  *   - Wrong magic (not "KRFL") -> `ReflectError.SnapshotFormatError`.
  *   - Major version mismatch -> `ReflectError.SnapshotVersionMismatch`; caller falls through to full decode.
  *   - Minor version too new -> loads successfully (add-only sections are skipped).
  *
  * Home assignment: each reconstructed symbol gets a `ClasspathRef` that is populated after this method returns by the caller (the
  * `Reflect.Classpath.open` wrapper in `Reflect.scala`, which can see through the opaque type alias).
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
    )(using Frame): Unit < (Sync & Abort[ReflectError]) =
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
    )(using Frame): Unit < (Sync & Abort[ReflectError] & Scope) =
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
                Reflect.Version(fileMajor, fileMinor, 0),
                Reflect.Version(SnapshotFormat.majorVersion, SnapshotFormat.minorVersion, 0)
            )
        end if
        deserializeMapped(path, view, cp)
    end readMappedView

    /** Thrown by readMappedView when the snapshot major version doesn't match. */
    final private[snapshot] class VersionMismatchException(
        val found: Reflect.Version,
        val supported: Reflect.Version
    ) extends java.io.IOException(s"version mismatch: found=$found supported=$supported")

    /** Deserialize KRFL bytes into the Classpath. */
    private def readBytes(
        path: String,
        bytes: Array[Byte],
        cp: Classpath
    )(using Frame): Unit < (Sync & Abort[ReflectError]) =
        Sync.defer:
            if bytes.length < 4 || bytes(0) != 'K' || bytes(1) != 'R' || bytes(2) != 'F' || bytes(3) != 'L' then
                Abort.fail(ReflectError.SnapshotFormatError(path, "wrong magic, expected KRFL"))
            else
                val fileMajor = bytes(4) & 0xff
                val fileMinor = bytes(5) & 0xff
                if fileMajor != SnapshotFormat.majorVersion then
                    Abort.fail(
                        ReflectError.SnapshotVersionMismatch(
                            Reflect.Version(fileMajor, fileMinor, 0),
                            Reflect.Version(SnapshotFormat.majorVersion, SnapshotFormat.minorVersion, 0)
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
                        Chunk.empty[Reflect.Symbol],
                        Map.empty[String, Reflect.Symbol],
                        Map.empty[String, Reflect.Symbol],
                        Chunk.empty[Reflect.Symbol],
                        Chunk.empty[Reflect.Symbol]
                    )

        // Read ERRORS section
        val errors = sectionMap.get(SnapshotFormat.sectionERRORS) match
            case Some((offset, length)) => readErrors(bytes, offset, length)
            case None                   => Chunk.empty[ReflectError]

        val canonical = TypeArena.canonical()
        Classpath.transitionToReady(cp, allSymbols, topLevelCls, packages, fqnIndex, packageIndex, canonical, errors, Map.empty)
        // Populate _parents, _typeParams, _declarations with empty chunks for snapshot-restored symbols.
        // The snapshot format does not yet serialize parent/member data; symbols restored here return
        // empty chunks for these accessors. A future snapshot format version will add this data.
        // Unsafe: SingleAssign.set() is an unsafe-tier helper; called here from single-threaded deserialize.
        import AllowUnsafe.embrace.danger
        for sym <- allSymbols do
            if !sym._parents.isSet then sym._parents.set(Chunk.empty)
            if !sym._typeParams.isSet then sym._typeParams.set(Chunk.empty)
            if !sym._declarations.isSet then sym._declarations.set(Chunk.empty)
        end for
    end deserialize

    /** Deserialize from a memory-mapped ByteView.
      *
      * Reads header, section index, NAMES, SYMBOLS, and ERRORS into heap arrays (they are small). BODY_BYTES section is kept in mapped
      * memory: TastyOrigin.bodyView for each symbol is a sub-view into the mapped region. After the backing Arena is closed, sym.body reads
      * from the mapped view and throws IllegalStateException, which Symbol.body catches as ClasspathClosed.
      */
    private def deserializeMapped(path: String, view: ByteView, cp: Classpath): Unit =
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
                        Chunk.empty[Reflect.Symbol],
                        Map.empty[String, Reflect.Symbol],
                        Map.empty[String, Reflect.Symbol],
                        Chunk.empty[Reflect.Symbol],
                        Chunk.empty[Reflect.Symbol]
                    )

        // Read ERRORS section into heap (tiny).
        val errorsBytes = sectionMap.get(SnapshotFormat.sectionERRORS) match
            case Some((off, len)) => copyViewRange(view, off, off + len)
            case None             => Array.empty[Byte]
        val errors = if errorsBytes.nonEmpty then readErrors(errorsBytes, 0, errorsBytes.length) else Chunk.empty[ReflectError]

        val canonical = TypeArena.canonical()
        Classpath.transitionToReady(cp, allSymbols, topLevelCls, packages, fqnIndex, packageIndex, canonical, errors, Map.empty)
        import AllowUnsafe.embrace.danger
        for sym <- allSymbols do
            if !sym._parents.isSet then sym._parents.set(Chunk.empty)
            if !sym._typeParams.isSet then sym._typeParams.set(Chunk.empty)
            if !sym._declarations.isSet then sym._declarations.set(Chunk.empty)
        end for
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
    ): (Chunk[Reflect.Symbol], Map[String, Reflect.Symbol], Map[String, Reflect.Symbol], Chunk[Reflect.Symbol], Chunk[Reflect.Symbol]) =
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
        val created = new Array[Reflect.Symbol](count)

        for idx <- order do
            val raw   = raws(idx)
            val kind  = kindFromOrd(raw.kindOrd)
            val flags = new Reflect.Flags(raw.flagBits)
            val name  = if raw.nameId >= 0 && raw.nameId < namePool.length then Reflect.Name(namePool(raw.nameId)) else Reflect.Name("")
            val owner = if raw.ownerId >= 0 && raw.ownerId < count && created(raw.ownerId) != null then created(raw.ownerId) else null
            val home  = new ClasspathRef
            val origin: Reflect.Symbol.Origin =
                if raw.bodyStart > 0 && raw.bodyEnd > raw.bodyStart && (bodyViewOpt ne null) then
                    // Mmap path: bodyView is a sub-view into the mapped BODY_BYTES region.
                    // sectionBytes is empty; body decode reads via bodyView, which fails with IllegalStateException after arena close.
                    new Reflect.Symbol.TastyOrigin(
                        raw.bodyStart,
                        raw.bodyEnd,
                        Array.empty[Byte],
                        Array.empty[Reflect.Name],
                        0,
                        bodyViewOpt
                    )
                else
                    Reflect.Symbol.JavaOrigin
            created(idx) = InternalSymbol.makeSymbol(kind, flags, name, owner, home, origin, Maybe.Absent)
        end for

        val fqnIndex     = mutable.HashMap.empty[String, Reflect.Symbol]
        val packageIndex = mutable.HashMap.empty[String, Reflect.Symbol]
        val allSymbols   = mutable.ArrayBuffer.empty[Reflect.Symbol]
        val topLevelCls  = mutable.ArrayBuffer.empty[Reflect.Symbol]
        val packages     = mutable.ArrayBuffer.empty[Reflect.Symbol]

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
                        case Reflect.SymbolKind.Package =>
                            packages += sym
                            packageIndex(fqn) = sym
                        case Reflect.SymbolKind.Class | Reflect.SymbolKind.Trait | Reflect.SymbolKind.Object =>
                            topLevelCls += sym
                        case _ =>
                            ()
                    end match
                end if
            end if
            i += 1
        end while

        (
            Chunk.from(allSymbols.toSeq),
            fqnIndex.toMap,
            packageIndex.toMap,
            Chunk.from(topLevelCls.toSeq),
            Chunk.from(packages.toSeq)
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
    ): (Chunk[Reflect.Symbol], Map[String, Reflect.Symbol], Map[String, Reflect.Symbol], Chunk[Reflect.Symbol], Chunk[Reflect.Symbol]) =
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
        val created = new Array[Reflect.Symbol](count)

        for idx <- order do
            val raw   = raws(idx)
            val kind  = kindFromOrd(raw.kindOrd)
            val flags = new Reflect.Flags(raw.flagBits)
            val name  = if raw.nameId >= 0 && raw.nameId < namePool.length then Reflect.Name(namePool(raw.nameId)) else Reflect.Name("")
            val owner = if raw.ownerId >= 0 && raw.ownerId < count && created(raw.ownerId) != null then created(raw.ownerId) else null
            val home  = new ClasspathRef
            val origin: Reflect.Symbol.Origin =
                if raw.bodyStart > 0 && raw.bodyEnd > raw.bodyStart && bodyBytesArray.nonEmpty
                    && raw.bodyEnd <= bodyBytesArray.length
                then
                    // Restore body origin: offsets are relative to the start of BODY_BYTES section.
                    // sectionOffset is 0 because bodyStart is already absolute within bodyBytesArray.
                    new Reflect.Symbol.TastyOrigin(raw.bodyStart, raw.bodyEnd, bodyBytesArray, Array.empty[Reflect.Name], 0, null)
                else
                    Reflect.Symbol.JavaOrigin
            created(idx) = InternalSymbol.makeSymbol(kind, flags, name, owner, home, origin, Maybe.Absent)
        end for

        // Build indices
        val fqnIndex     = mutable.HashMap.empty[String, Reflect.Symbol]
        val packageIndex = mutable.HashMap.empty[String, Reflect.Symbol]
        val allSymbols   = mutable.ArrayBuffer.empty[Reflect.Symbol]
        val topLevelCls  = mutable.ArrayBuffer.empty[Reflect.Symbol]
        val packages     = mutable.ArrayBuffer.empty[Reflect.Symbol]

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
                        case Reflect.SymbolKind.Package =>
                            packages += sym
                            packageIndex(fqn) = sym
                        case Reflect.SymbolKind.Class | Reflect.SymbolKind.Trait | Reflect.SymbolKind.Object =>
                            topLevelCls += sym
                        case _ =>
                            ()
                    end match
                end if
            end if
            i += 1
        end while

        (
            Chunk.from(allSymbols.toSeq),
            fqnIndex.toMap,
            packageIndex.toMap,
            Chunk.from(topLevelCls.toSeq),
            Chunk.from(packages.toSeq)
        )
    end readSymbols

    /** Read errors from the ERRORS section. */
    private def readErrors(bytes: Array[Byte], offset: Int, length: Int): Chunk[ReflectError] =
        val count = SnapshotFormat.readInt32LE(bytes, offset)
        if count <= 0 then Chunk.empty
        else
            var pos = offset + 4
            val buf = mutable.ArrayBuffer.empty[ReflectError]
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

    /** Best-effort reconstruction of a ReflectError from its toString representation. */
    private def parseErrorString(msg: String): ReflectError =
        if msg.startsWith("FileNotFound(") then ReflectError.FileNotFound(extractParenContent(msg))
        else if msg.startsWith("CorruptedFile(") then
            val parts = extractParenContent(msg).split(",", 3)
            if parts.length >= 3 then
                val at =
                    try parts(1).trim.toLong
                    catch case _: NumberFormatException => 0L
                ReflectError.CorruptedFile(parts(0), at, parts(2).trim)
            else ReflectError.NotImplemented(msg)
            end if
        else if msg.startsWith("SnapshotIoError(") then ReflectError.SnapshotIoError(extractParenContent(msg))
        else ReflectError.NotImplemented(s"deserialized: $msg")

    private def extractParenContent(s: String): String =
        val start = s.indexOf('(')
        val end   = s.lastIndexOf(')')
        if start >= 0 && end > start then s.substring(start + 1, end) else s
    end extractParenContent

    /** Convert SymbolKind ordinal integer to enum case. */
    private def kindFromOrd(ord: Int): Reflect.SymbolKind =
        import Reflect.SymbolKind.*
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
