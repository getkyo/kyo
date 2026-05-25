package kyo.internal.reflect.snapshot

import kyo.*
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

        // Read SYMBOLS section
        val (allSymbols, fqnIndex, packageIndex, topLevelCls, packages) =
            sectionMap.get(SnapshotFormat.sectionSYMBOLS) match
                case Some((offset, length)) => readSymbols(bytes, offset, length, namePool)
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
        Classpath.transitionToReady(cp, allSymbols, topLevelCls, packages, fqnIndex, packageIndex, canonical, errors)
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
        ownerId: Int
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
        namePool: Array[String]
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
            val kindOrd  = bytes(pos) & 0xff
            val flagBits = SnapshotFormat.readInt64LE(bytes, pos + 1)
            val nameId   = SnapshotFormat.readInt32LE(bytes, pos + 9)
            val fqnId    = SnapshotFormat.readInt32LE(bytes, pos + 13)
            val ownerId  = SnapshotFormat.readInt32LE(bytes, pos + 17)
            raws(i) = RawSymbol(kindOrd, flagBits, nameId, fqnId, ownerId)
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
            val kind  = kindFromOrdinal(raw.kindOrd)
            val flags = new Reflect.Flags(raw.flagBits)
            val name  = if raw.nameId >= 0 && raw.nameId < namePool.length then Reflect.Name(namePool(raw.nameId)) else Reflect.Name("")
            val owner = if raw.ownerId >= 0 && raw.ownerId < count && created(raw.ownerId) != null then created(raw.ownerId) else null
            val home  = new ClasspathRef
            created(idx) = InternalSymbol.makeSymbol(kind, flags, name, owner, home, Reflect.Symbol.JavaOrigin, Maybe.Absent)
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

    /** Convert SymbolKind ordinal to enum case. */
    private def kindFromOrdinal(ordinal: Int): Reflect.SymbolKind =
        import Reflect.SymbolKind.*
        ordinal match
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
    end kindFromOrdinal

end SnapshotReader
