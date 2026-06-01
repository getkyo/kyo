package kyo.internal.tasty.snapshot

import kyo.*
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.symbol.Symbol as InternalSymbol
import kyo.internal.tasty.symbol.SymbolDescriptor
import kyo.internal.tasty.symbol.SymbolId
import kyo.internal.tasty.symbol.TypedSymbolFactory
import kyo.internal.tasty.type_.TypeArena
import scala.collection.mutable

/** Reads a KRFL snapshot file and builds a `Tasty.Classpath` from it.
  *
  * Validation:
  *   - Wrong magic (not "KRFL") -> `TastyError.SnapshotFormatError`.
  *   - Major version mismatch -> `TastyError.SnapshotVersionMismatch`; caller falls through to full decode.
  *   - Minor version too new -> loads successfully (add-only sections are skipped).
  */
object SnapshotReader:

    /** Read a snapshot from `path` and return a fully-constructed `Tasty.Classpath`.
      *
      * @param path
      *   absolute path to the `.krfl` file
      * @param source
      *   FileSource for reading the bytes
      */
    def read(
        path: String,
        source: FileSource
    )(using Frame): Tasty.Classpath < (Sync & Abort[TastyError]) =
        source.read(path).flatMap: bytes =>
            readBytes(path, bytes)

    /** Read a snapshot preferring a memory-mapped path on JVM/Native; falls back to heap read on JS.
      *
      * On JVM, delegates to PlatformMmapReader which uses JvmMmapReader (java.lang.foreign.Arena). On Native, uses NativeMmapReader (POSIX
      * mmap). On JS, falls back to the heap-based `read`. The Scope effect is required because the mmap arena lifetime is bounded by the
      * enclosing Scope.
      */
    def readMapped(
        path: String,
        source: FileSource
    )(using Frame): Tasty.Classpath < (Sync & Abort[TastyError] & Scope) =
        PlatformMmapReader.readMapped(path, source)

    /** Deserialize a KRFL snapshot from an already-opened ByteView (mmap path).
      *
      * Called by platform-specific PlatformMmapReader implementations (JVM, Native). The `view` covers the entire snapshot file content
      * mapped into memory. Returns a fully-constructed Tasty.Classpath.
      */
    private[snapshot] def readMappedView(
        path: String,
        view: ByteView
    ): Tasty.Classpath =
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
        deserializeMapped(path, view)
    end readMappedView

    /** Thrown by readMappedView when the snapshot major version doesn't match. */
    final private[snapshot] class VersionMismatchException(
        val found: Tasty.Version,
        val supported: Tasty.Version
    ) extends java.io.IOException(s"version mismatch: found=$found supported=$supported")

    /** Deserialize KRFL bytes into a new Tasty.Classpath. */
    private def readBytes(
        path: String,
        bytes: Array[Byte]
    )(using Frame): Tasty.Classpath < (Sync & Abort[TastyError]) =
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
                    deserialize(path, bytes)
                end if

    /** Deserialize section payloads into a new Tasty.Classpath. */
    private def deserialize(
        path: String,
        bytes: Array[Byte]
    ): Tasty.Classpath =
        // §839 case 3; snapshot-deserialize boundary; single-fiber synchronous symbol graph reconstruction.
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

        // Collect relational data from sections, then rebuild Symbols with full field set.
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
                        parentsByIdx(idx) = Chunk.from(refs.map(id => Tasty.Type.Named(SymbolId(id))))
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
            val partial                  = symsArray(si)
            val tpIds: Chunk[SymbolId]   = typeParamsByIdx(si)
            val declIds: Chunk[SymbolId] = declarationsByIdx(si)
            val d = new SymbolDescriptor(
                id = partial.id.value,
                kind = partial.kind,
                flags = partial.flags,
                name = partial.name,
                ownerId = partial.ownerId.value,
                declaredType = partial match
                    case m: Tasty.Symbol.Method      => m.declaredType
                    case v: Tasty.Symbol.Val         => v.declaredType
                    case w: Tasty.Symbol.Var         => w.declaredType
                    case f: Tasty.Symbol.Field       => f.declaredType
                    case p: Tasty.Symbol.Parameter   => kyo.Maybe(p.declaredType)
                    case ta: Tasty.Symbol.TypeAlias  => kyo.Maybe(ta.body)
                    case ot: Tasty.Symbol.OpaqueType => kyo.Maybe(ot.body)
                    case _ =>
                        kyo.Maybe.Absent
                ,
                scaladoc = partial.scaladoc,
                sourcePosition = partial.sourcePosition,
                javaMetadata = partial match
                    case c: Tasty.Symbol.ClassLike => c.javaMetadata
                    case f: Tasty.Symbol.Field     => f.javaMetadata
                    case m: Tasty.Symbol.Method    => m.javaMetadata
                    case _ =>
                        kyo.Maybe.Absent
                ,
                parentTypes = parentsByIdx(si),
                typeParamIds = Chunk.from(tpIds.toSeq.map(_.value)),
                declarationIds = Chunk.from(declIds.toSeq.map(_.value)),
                permittedSubclassIds = partial match
                    case c: Tasty.Symbol.Class =>
                        c.permittedSubclassIds.map(ids => Chunk.from(ids.toSeq.map(_.value)))
                    case t: Tasty.Symbol.Trait =>
                        t.permittedSubclassIds.map(ids => Chunk.from(ids.toSeq.map(_.value)))
                    case _ =>
                        kyo.Maybe.Absent
                ,
                body = partial match
                    case c: Tasty.Symbol.Class  => c.body
                    case t: Tasty.Symbol.Trait  => t.body
                    case o: Tasty.Symbol.Object => o.body
                    case m: Tasty.Symbol.Method => m.body
                    case v: Tasty.Symbol.Val    => v.body
                    case w: Tasty.Symbol.Var    => w.body
                    case _                      => kyo.Maybe.Absent
            )
            finalSymbols(si) = TypedSymbolFactory.from(d)
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
        val symsChunk = Chunk.from(finalSymbols)
        val fqnIdIdx  = finalFqnIndex.map { case (k, v) => k -> v.id }.toMap
        val pkgIdIdx  = finalPackageIndex.map { case (k, v) => k -> v.id }.toMap
        val topIds    = finalTopLevelCls.map(_.id)
        val pkgIds    = finalPackages.map(_.id)
        val rootId    = if symsChunk.nonEmpty then SymbolId(0) else SymbolId(-1)
        Tasty.Classpath.make(
            symbols = symsChunk,
            rootSymbolId = rootId,
            topLevelClassIds = topIds,
            packageIds = pkgIds,
            fqnIndex = fqnIdIdx,
            packageIndex = pkgIdIdx,
            subclassIndex = Map.empty,
            companionIndex = Map.empty,
            moduleIndex = Map.empty,
            errors = errors,
            canonical = canonical
        )
    end deserialize

    /** Deserialize ref lists, calling `assign(symIdx, refs)` for each entry.
      *
      * Works with index-based access: `assign(symIdx, refs)` is called for each entry in the section.
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
    private def deserializeMapped(path: String, view: ByteView): Tasty.Classpath =
        // §839 case 3; snapshot-deserialize-mmap boundary; single-fiber synchronous symbol graph reconstruction.
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

        // Same two-pass pattern as deserialize.
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
                        parentsByIdx(idx) = Chunk.from(refs.map(id => Tasty.Type.Named(SymbolId(id))))
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
            val partial                  = symsArray(j)
            val tpIds: Chunk[SymbolId]   = typeParamsByIdx(j)
            val declIds: Chunk[SymbolId] = declarationsByIdx(j)
            val d = new SymbolDescriptor(
                id = partial.id.value,
                kind = partial.kind,
                flags = partial.flags,
                name = partial.name,
                ownerId = partial.ownerId.value,
                declaredType = partial match
                    case m: Tasty.Symbol.Method      => m.declaredType
                    case v: Tasty.Symbol.Val         => v.declaredType
                    case w: Tasty.Symbol.Var         => w.declaredType
                    case f: Tasty.Symbol.Field       => f.declaredType
                    case p: Tasty.Symbol.Parameter   => kyo.Maybe(p.declaredType)
                    case ta: Tasty.Symbol.TypeAlias  => kyo.Maybe(ta.body)
                    case ot: Tasty.Symbol.OpaqueType => kyo.Maybe(ot.body)
                    case _ =>
                        kyo.Maybe.Absent
                ,
                scaladoc = partial.scaladoc,
                sourcePosition = partial.sourcePosition,
                javaMetadata = partial match
                    case c: Tasty.Symbol.ClassLike => c.javaMetadata
                    case f: Tasty.Symbol.Field     => f.javaMetadata
                    case m: Tasty.Symbol.Method    => m.javaMetadata
                    case _ =>
                        kyo.Maybe.Absent
                ,
                parentTypes = parentsByIdx(j),
                typeParamIds = Chunk.from(tpIds.toSeq.map(_.value)),
                declarationIds = Chunk.from(declIds.toSeq.map(_.value)),
                permittedSubclassIds = partial match
                    case c: Tasty.Symbol.Class =>
                        c.permittedSubclassIds.map(ids => Chunk.from(ids.toSeq.map(_.value)))
                    case t: Tasty.Symbol.Trait =>
                        t.permittedSubclassIds.map(ids => Chunk.from(ids.toSeq.map(_.value)))
                    case _ =>
                        kyo.Maybe.Absent
                ,
                body = partial match
                    case c: Tasty.Symbol.Class  => c.body
                    case t: Tasty.Symbol.Trait  => t.body
                    case o: Tasty.Symbol.Object => o.body
                    case m: Tasty.Symbol.Method => m.body
                    case v: Tasty.Symbol.Val    => v.body
                    case w: Tasty.Symbol.Var    => w.body
                    case _                      => kyo.Maybe.Absent
            )
            finalSymbols(j) = TypedSymbolFactory.from(d)
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

        val canonical  = TypeArena.canonical()
        val symsChunk2 = Chunk.from(finalSymbols)
        val fqnIdIdx2  = newFqnIndex.map { case (k, v) => k -> v.id }.toMap
        val pkgIdIdx2  = newPackageIndex.map { case (k, v) => k -> v.id }.toMap
        val topIds2    = newTopLevelCls.map(_.id)
        val pkgIds2    = newPackages.map(_.id)
        val rootId2    = if symsChunk2.nonEmpty then SymbolId(0) else SymbolId(-1)
        Tasty.Classpath.make(
            symbols = symsChunk2,
            rootSymbolId = rootId2,
            topLevelClassIds = topIds2,
            packageIds = pkgIds2,
            fqnIndex = fqnIdIdx2,
            packageIndex = pkgIdIdx2,
            subclassIndex = Map.empty,
            companionIndex = Map.empty,
            moduleIndex = Map.empty,
            errors = errors,
            canonical = canonical
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

        // Create partial Symbols with basic fields; relational fields filled by the caller.
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
            val desc = new SymbolDescriptor(
                id = idx,
                kind = kind,
                flags = flags,
                name = name,
                ownerId = ownerIdVal,
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
            created(idx) = TypedSymbolFactory.from(desc)
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

        // Create partial Symbols with basic fields; deserialize() fills in
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
            val desc2 = new SymbolDescriptor(
                id = idx,
                kind = kind,
                flags = flags,
                name = name,
                ownerId = ownerIdVal,
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
            created(idx) = TypedSymbolFactory.from(desc2)
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
            case 13 => EnumCase
            case 14 => Unresolved
            case _  => Unresolved
        end match
    end kindFromOrd

end SnapshotReader
