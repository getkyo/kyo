package kyo.internal.tasty.snapshot

import kyo.*
import kyo.Tasty.SymbolId
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.symbol.Symbol as InternalSymbol
import kyo.internal.tasty.symbol.SymbolDescriptor
import kyo.internal.tasty.symbol.SymbolKind
import kyo.internal.tasty.symbol.TypedSymbolFactory
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
      * Reads the file via `kyo.Path`. Digest verification: when `expectedDigest` is Present, the 8-byte xxh64-custom hash embedded at bytes
      * 16-23 of the snapshot header is compared against the expected value. A mismatch raises `TastyError.DigestMismatch`. Pass `Absent`
      * (the default) to skip this check (e.g., for trusted pre-warmed caches). `Tasty.withClasspath(roots, Present(cacheDir))` already
      * provides digest-based selection via the filename, so it does not need to pass an `expectedDigest` here.
      *
      * @param path
      *   absolute path to the `.krfl` file
      * @param expectedDigest
      *   optional expected 8-byte xxh64-custom digest; when Present, the embedded digest is verified before deserialization
      */
    def read(
        path: String,
        expectedDigest: Maybe[Array[Byte]] = Maybe.Absent
    )(using Frame): Tasty.Classpath < (Sync & Abort[TastyError]) =
        Abort.recover[FileReadException](e => Abort.fail(TastyError.SnapshotIoError(s"read $path: ${e.getMessage}")))(
            Path(path).readBytes
        ).map { bytes =>
            expectedDigest match
                case Maybe.Absent =>
                    readBytes(path, bytes)
                case Maybe.Present(expected) =>
                    // inputDigest is at bytes [16, 24) in the header.
                    if bytes.size < 24 then
                        Abort.fail(TastyError.SnapshotFormatError(path, "header too short for digest check", 0L))
                    else
                        val actualHex   = DigestComputer.toHexString(bytes.slice(16, 24).toArray)
                        val expectedHex = DigestComputer.toHexString(expected)
                        if actualHex != expectedHex then
                            Abort.fail(TastyError.DigestMismatch(expected = expectedHex, actual = actualHex))
                        else
                            readBytes(path, bytes)
                        end if
        }

    /** Read a snapshot preferring a memory-mapped path on JVM/Native; falls back to heap read on JS.
      *
      * On JVM, delegates to PlatformMmapReader which uses JvmMmapReader (java.lang.foreign.Arena). On Native, uses NativeMmapReader (POSIX
      * mmap). On JS, falls back to the heap-based `read`. The Scope effect is required because the mmap arena lifetime is bounded by the
      * enclosing Scope.
      */
    def readMapped(
        path: String
    )(using Frame): Tasty.Classpath < (Sync & Abort[TastyError] & Scope) =
        PlatformMmapReader.readMapped(path)

    /** Deserialize a KRFL snapshot from an already-opened ByteView (mmap path).
      *
      * Called by platform-specific PlatformMmapReader implementations (JVM, Native). The `view` covers the entire snapshot file content
      * mapped into memory. Returns a fully-constructed Tasty.Classpath.
      */
    private[snapshot] def readMappedView(
        path: String,
        view: ByteView
    )(using AllowUnsafe): Tasty.Classpath =
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
        else if fileMinor < SnapshotFormat.minorVersion then
            // Any older minor version is treated as a breaking bump and rejected so the caller
            // falls through to cold re-decode. Section schema differences per minor are documented
            // in SnapshotFormat.
            throw new VersionMismatchException(
                Tasty.Version(fileMajor, fileMinor, 0),
                Tasty.Version(SnapshotFormat.majorVersion, SnapshotFormat.minorVersion, 0)
            )
        end if
        deserializeMapped(path, view)
    end readMappedView

    /** Deserialize a KRFL snapshot from raw bytes.
      *
      * Used by `BundledSnapshotProbe` to decode a snapshot that was read from a jar entry (not a standalone file). Delegates to the same
      * `readBytes` path as the file-backed `read` overload. The `path` argument is used only in error messages.
      */
    private[kyo] def readFromBytes(
        bytes: Array[Byte],
        path: String = "<bundled>"
    )(using Frame): Tasty.Classpath < (Sync & Abort[TastyError]) =
        readBytes(path, Span.fromUnsafe(bytes))

    /** Read the inputDigest field from the KRFL header at bytes [16..23] (little-endian Int64).
      *
      * Returns 0L if the bytes array is shorter than 24 bytes. No full deserialization is performed; only the 8-byte header field is
      * extracted.
      */
    private[kyo] def readInputDigest(bytes: Array[Byte]): Long =
        if bytes.length < 24 then 0L
        else DigestComputer.bytesToLong(java.util.Arrays.copyOfRange(bytes, 16, 24))

    /** Thrown by readMappedView when the snapshot major version doesn't match. */
    final private[snapshot] class VersionMismatchException(
        val found: Tasty.Version,
        val supported: Tasty.Version
    ) extends java.io.IOException(s"version mismatch: found=$found supported=$supported")

    /** Deserialize KRFL bytes into a new Tasty.Classpath.
      *
      * Uses `Sync.Unsafe.defer` to supply `AllowUnsafe` for the `deserialize` boundary: the symbol-graph
      * reconstruction inside `deserialize` allocates mutable index buffers and reads via the unsafe
      * Span/byte helpers, which the safe-tier API does not expose without suspension.
      */
    private def readBytes(
        path: String,
        bytes: Span[Byte]
    )(using Frame): Tasty.Classpath < (Sync & Abort[TastyError]) =
        Sync.Unsafe.defer {
            if bytes.size < 4 || bytes(0) != 'K' || bytes(1) != 'R' || bytes(2) != 'F' || bytes(3) != 'L' then
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
                else if fileMinor < SnapshotFormat.minorVersion then
                    // Any older minor version is treated as a breaking bump and rejected so the caller
                    // falls through to cold re-decode. Section schema differences per minor are documented
                    // in SnapshotFormat.
                    Abort.fail(
                        TastyError.SnapshotVersionMismatch(
                            Tasty.Version(fileMajor, fileMinor, 0),
                            Tasty.Version(SnapshotFormat.majorVersion, SnapshotFormat.minorVersion, 0)
                        )
                    )
                else
                    // SnapshotFormat helpers operate on Array[Byte]; convert once at the parser boundary.
                    // Unsafe: toArrayUnsafe is zero-copy on JVM/Native (same memory); safe here because
                    // the array is only read, never written.
                    val arr = bytes.toArrayUnsafe
                    try deserialize(path, arr)
                    catch
                        case ex: SectionValidator.SectionValidationException =>
                            Abort.fail(ex.error)
                        case ex: ArrayIndexOutOfBoundsException =>
                            Abort.fail(TastyError.SnapshotFormatError(
                                path,
                                s"truncated snapshot: ${ex.getClass.getSimpleName}: ${ex.getMessage}",
                                0L
                            ))
                        case ex: IndexOutOfBoundsException =>
                            Abort.fail(TastyError.SnapshotFormatError(
                                path,
                                s"truncated snapshot: ${ex.getClass.getSimpleName}: ${ex.getMessage}",
                                0L
                            ))
                        case ex: NegativeArraySizeException =>
                            Abort.fail(TastyError.SnapshotFormatError(
                                path,
                                s"corrupt section length: ${ex.getClass.getSimpleName}: ${ex.getMessage}",
                                0L
                            ))
                        case ex: java.io.IOException =>
                            Abort.fail(TastyError.SnapshotFormatError(
                                path,
                                s"corrupt header: ${ex.getClass.getSimpleName}: ${ex.getMessage}",
                                0L
                            ))
                    end try
                end if
        }

    /** Deserialize section payloads into a new Tasty.Classpath.
      *
      * Requires `AllowUnsafe` to call the `readSymbols` / `readSymbolsMapped` helpers that allocate
      * mutable index buffers. Callers wrap the invocation in `Sync.Unsafe.defer`, which supplies the
      * permission for the entire deserialize boundary.
      */
    private def deserialize(
        path: String,
        bytes: Array[Byte]
    )(using AllowUnsafe): Tasty.Classpath =
        // Parse section index (starts at offset 32)
        val sectionCount = SnapshotFormat.readInt32LE(bytes, 32)
        // Cap sectionCount before allocating / iterating to prevent OOM from a corrupt header.
        if sectionCount < 0 || sectionCount > SnapshotFormat.maxSectionCount then
            throw new java.io.IOException(
                s"corrupt section count: $sectionCount (max=${SnapshotFormat.maxSectionCount})"
            )
        end if
        val sectionMap = mutable.HashMap.empty[String, (Int, Int)]
        var idxPos     = 36
        var i          = 0
        while i < sectionCount do
            val name   = SnapshotFormat.readSectionName(bytes, idxPos)
            val offset = SnapshotFormat.readInt64LE(bytes, idxPos + 8).toInt
            val length = SnapshotFormat.readInt64LE(bytes, idxPos + 16).toInt
            // Bounds-check the section range before any copy.
            SectionValidator.validateRange(name, offset, length, bytes.length)
            sectionMap(name) = (offset, length)
            idxPos += SnapshotFormat.sectionIndexEntrySize
            i += 1
        end while

        // Read NAMES section
        val namePool = sectionMap.get(SnapshotFormat.sectionNAMES) match
            case Some((offset, length)) =>
                val sectionBytes = java.util.Arrays.copyOfRange(bytes, offset, offset + length)
                SectionValidator.validate(SnapshotFormat.sectionNAMES, sectionBytes, SectionValidator.SectionLayout.VariableLength)
                readNamePool(bytes, offset, length)
            case None => Array.empty[String]

        // Read BODY_BYTES section: shared backing store for all TASTy body slices
        val bodyBytesArray: Array[Byte] = sectionMap.get(SnapshotFormat.sectionBODYBYTES) match
            case Some((offset, length)) if length > 0 =>
                val arr = new Array[Byte](length)
                java.lang.System.arraycopy(bytes, offset, arr, 0, length)
                // BODYBYTE is a raw byte blob; VariableLength has no alignment constraint.
                SectionValidator.validate(SnapshotFormat.sectionBODYBYTES, arr, SectionValidator.SectionLayout.VariableLength)
                arr

            // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
            case _ =>
                Array.empty[Byte]

        // Read SYMBOLS section: each record is exactly 40 bytes.
        val (allSymbols, fullNameIndex, packageIndex, topLevelCls, packages) =
            sectionMap.get(SnapshotFormat.sectionSYMBOLS) match
                case Some((offset, length)) =>
                    val sectionBytes = java.util.Arrays.copyOfRange(bytes, offset, offset + length)
                    SectionValidator.validate(
                        SnapshotFormat.sectionSYMBOLS,
                        sectionBytes,
                        SectionValidator.SectionLayout.FixedRecordWithHeader(40)
                    )
                    readSymbols(bytes, offset, length, namePool, bodyBytesArray)
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
            case Some((offset, length)) =>
                val sectionBytes = java.util.Arrays.copyOfRange(bytes, offset, offset + length)
                SectionValidator.validate(SnapshotFormat.sectionERRORS, sectionBytes, SectionValidator.SectionLayout.VariableLength)
                readErrors(bytes, offset, length)
            case None => Chunk.empty[TastyError]

        // Collect relational data from sections, then rebuild Symbols with full field set.
        val partialSymbols = allSymbols
        val symCount       = partialSymbols.length
        val symsArray      = partialSymbols.toArray

        // Collect parentTypes per symbol index.
        val parentsByIdx = new Array[Chunk[Tasty.Type]](symCount)
        java.util.Arrays.fill(
            parentsByIdx.asInstanceOf[Array[Object]],
            Chunk.empty
        ) // java.util.Arrays.fill requires Array[Object]; safe covariant widening for reference types
        sectionMap.get(SnapshotFormat.sectionPARENTS) match
            case Some((off, len)) if len > 0 =>
                val sb = java.util.Arrays.copyOfRange(bytes, off, off + len)
                SectionValidator.validate(SnapshotFormat.sectionPARENTS, sb, SectionValidator.SectionLayout.Int32Array)
                deserializeRefListsByIdx(
                    bytes,
                    off,
                    len,
                    symCount,
                    (idx, refs) =>
                        parentsByIdx(idx) = Chunk.from(refs.map(id => Tasty.Type.Named(SymbolId(id))))
                )

            // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
            case _ => ()
        end match

        // Collect typeParamIds per symbol index.
        val typeParamsByIdx = new Array[Chunk[kyo.Tasty.SymbolId]](symCount)
        java.util.Arrays.fill(
            typeParamsByIdx.asInstanceOf[Array[Object]],
            Chunk.empty
        ) // java.util.Arrays.fill requires Array[Object]
        sectionMap.get(SnapshotFormat.sectionTPARAMS) match
            case Some((off, len)) if len > 0 =>
                val sb = java.util.Arrays.copyOfRange(bytes, off, off + len)
                SectionValidator.validate(SnapshotFormat.sectionTPARAMS, sb, SectionValidator.SectionLayout.Int32Array)
                deserializeRefListsByIdx(
                    bytes,
                    off,
                    len,
                    symCount,
                    (idx, refs) =>
                        typeParamsByIdx(idx) = Chunk.from(refs.map(kyo.Tasty.SymbolId(_)))
                )

            // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
            case _ => ()
        end match

        // Collect declarationIds per symbol index.
        val declarationsByIdx = new Array[Chunk[kyo.Tasty.SymbolId]](symCount)
        java.util.Arrays.fill(
            declarationsByIdx.asInstanceOf[Array[Object]],
            Chunk.empty
        ) // java.util.Arrays.fill requires Array[Object]
        sectionMap.get(SnapshotFormat.sectionMEMBERS) match
            case Some((off, len)) if len > 0 =>
                val sb = java.util.Arrays.copyOfRange(bytes, off, off + len)
                SectionValidator.validate(SnapshotFormat.sectionMEMBERS, sb, SectionValidator.SectionLayout.Int32Array)
                deserializeRefListsByIdx(
                    bytes,
                    off,
                    len,
                    symCount,
                    (idx, refs) =>
                        declarationsByIdx(idx) = Chunk.from(refs.map(kyo.Tasty.SymbolId(_)))
                )

            // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
            case _ => ()
        end match

        // Collect permittedSubclassIds per symbol index (PERMITS2 section).
        val permittedByIdx = new Array[kyo.Maybe[Chunk[Int]]](symCount)
        java.util.Arrays.fill(
            permittedByIdx.asInstanceOf[Array[Object]],
            kyo.Maybe.Absent
        ) // java.util.Arrays.fill requires Array[Object]
        sectionMap.get(SnapshotFormat.sectionPERMITS2) match
            case Some((off, len)) if len > 0 =>
                val sb = java.util.Arrays.copyOfRange(bytes, off, off + len)
                SectionValidator.validate(SnapshotFormat.sectionPERMITS2, sb, SectionValidator.SectionLayout.Int32Array)
                deserializeRefListsByIdx(
                    bytes,
                    off,
                    len,
                    symCount,
                    (idx, refs) =>
                        permittedByIdx(idx) = kyo.Maybe(Chunk.from(refs))
                )

            // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
            case _ => ()
        end match

        // Collect annotation tycon fully-qualified name pool IDs per symbol index (ANNOTS_ section).
        val annotationsByIdx = new Array[Chunk[Tasty.Annotation]](symCount)
        java.util.Arrays.fill(
            annotationsByIdx.asInstanceOf[Array[Object]],
            Chunk.empty
        ) // java.util.Arrays.fill requires Array[Object]
        sectionMap.get(SnapshotFormat.sectionANNOTS) match
            case Some((off, len)) if len > 0 =>
                deserializeAnnotationsByIdx(bytes, off, len, symCount, namePool, annotationsByIdx)

            // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
            case _ => ()
        end match

        // Collect javaMetadata accessFlags per symbol index (JAVAMETA section).
        // Only accessFlags is stored; other JavaMetadata fields are reconstructed as empty.
        val javaMetaByIdx = new Array[kyo.Maybe[Int]](symCount)
        java.util.Arrays.fill(
            javaMetaByIdx.asInstanceOf[Array[Object]],
            kyo.Maybe.Absent
        ) // java.util.Arrays.fill requires Array[Object]
        sectionMap.get(SnapshotFormat.sectionJAVAMETA) match
            case Some((off, len)) if len > 0 =>
                deserializeJavaMetaByIdx(bytes, off, len, symCount, javaMetaByIdx)

            // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
            case _ => ()
        end match

        // Collect paramListIds per symbol index (PLISTS__ section, added in minor=12).
        // Absent section (pre-minor-12 snapshot, which is rejected above) defaults all entries to Chunk.empty.
        val paramListsByIdx = new Array[Chunk[Chunk[Int]]](symCount)
        java.util.Arrays.fill(
            paramListsByIdx.asInstanceOf[Array[Object]],
            Chunk.empty
        ) // java.util.Arrays.fill requires Array[Object]
        sectionMap.get(SnapshotFormat.sectionPLISTS) match
            case Some((off, len)) if len > 0 =>
                readParamListsByIdx(bytes, off, len, symCount, paramListsByIdx)

            // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
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
                    case p: Tasty.Symbol.Parameter   => p.declaredType
                    case ta: Tasty.Symbol.TypeAlias  => ta.body
                    case ot: Tasty.Symbol.OpaqueType => ot.body
                    case _: Tasty.Symbol.Class | _: Tasty.Symbol.EnumCase | _: Tasty.Symbol.Trait |
                        _: Tasty.Symbol.Object | _: Tasty.Symbol.AbstractType | _: Tasty.Symbol.TypeParam |
                        _: Tasty.Symbol.Package =>
                        kyo.Maybe.Absent
                ,
                scaladoc = partial.scaladoc,
                sourcePosition = partial.sourcePosition,
                javaMetadata = javaMetaByIdx(si) match
                    case kyo.Maybe.Present(flags) =>
                        kyo.Maybe(Tasty.Java.Metadata(
                            throwsTypes = Chunk.empty,
                            annotations = Chunk.empty,
                            enclosingMethod = kyo.Maybe.Absent,
                            accessFlags = flags,
                            recordComponents = Chunk.empty,
                            bootstrapMethods = Chunk.empty,
                            nestHost = kyo.Maybe.Absent,
                            nestMembers = Chunk.empty,
                            paramNames = Chunk.empty,
                            runtimeTypeAnnotations = Chunk.empty
                        ))
                    case kyo.Maybe.Absent =>
                        kyo.Maybe.Absent
                ,
                parentTypes = parentsByIdx(si),
                typeParamIds = Chunk.from(tpIds.toSeq.map(_.value)),
                declarationIds = Chunk.from(declIds.toSeq.map(_.value)),
                paramListIds = paramListsByIdx(si),
                permittedSubclassIds = permittedByIdx(si) match
                    case kyo.Maybe.Present(ids) => kyo.Maybe(ids)
                    case kyo.Maybe.Absent =>
                        kyo.Maybe.Absent
                ,
                annotations = annotationsByIdx(si),
                body = kyo.Maybe.Absent
            )
            finalSymbols(si) = TypedSymbolFactory.from(d)
            si += 1
        end while

        // Rebuild index maps to point to final Symbols.
        val finalFullNameIndex = fullNameIndex.map { case (k, v) =>
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

        // FQNIDX__ section : if present, reconstruct the full fullNameIndex
        // verbatim (all keys including dual-index source fully-qualified name aliases). Overrides the per-symbol
        // single-fully-qualified-name fullNameIndex built by readSymbols. If absent, fall back to the per-symbol index.
        val qualifiedFullNameIdIdx: Dict[String, SymbolId] = sectionMap.get(SnapshotFormat.sectionFQNIDX) match
            case Some((off, len)) if len > 0 =>
                deserializeFullNameIndex(bytes, off, len, namePool, finalSymbols)

            // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
            case _ =>
                Dict.from(finalFullNameIndex.map { case (k, v) => k -> v.id }.toMap)

        // FQNMAP__ section : if present, reconstruct the
        // negId -> fully-qualified name string map so warm-loaded classpaths resolve annotation fully-qualified names on JS/Native.
        val unresolvedFullNameByNegId: Dict[SymbolId, String] = sectionMap.get(SnapshotFormat.sectionFQNMAP) match
            case Some((off, len)) if len > 0 =>
                deserializeFullNameMap(bytes, off, len, namePool)

            // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
            case _ =>
                Dict.empty[SymbolId, String]

        // SUBCIDX_ section : subclassIndex for warm-load parity.
        val warmSubclassIndex: Dict[SymbolId, Chunk[SymbolId]] =
            sectionMap.get(SnapshotFormat.sectionSUBCIDX) match
                case Some((off, len)) if len > 0 =>
                    deserializeSubclassIndex(bytes, off, len, finalSymbols.length)

                // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
                case _ =>
                    Dict.empty[SymbolId, Chunk[SymbolId]]

        // COMPIDX_ section : companionIndex for warm-load parity.
        val warmCompanionIndex: Dict[SymbolId, SymbolId] =
            sectionMap.get(SnapshotFormat.sectionCOMPIDX) match
                case Some((off, len)) if len > 0 =>
                    deserializeCompanionIndex(bytes, off, len, finalSymbols.length)

                // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
                case _ =>
                    Dict.empty[SymbolId, SymbolId]

        val symsChunk = Chunk.from(finalSymbols)
        val pkgIdIdx  = Dict.from(finalPackageIndex.map { case (k, v) => k -> v.id }.toMap)
        val topIds    = finalTopLevelCls.map(_.id)
        val pkgIds    = finalPackages.map(_.id)
        val rootId    = if symsChunk.nonEmpty then SymbolId(0) else SymbolId(-1)
        Tasty.Classpath.make(
            symbols = symsChunk,
            rootSymbolId = rootId,
            topLevelClassIds = topIds,
            packageIds = pkgIds,
            fullNameIndex = qualifiedFullNameIdIdx,
            packageIndex = pkgIdIdx,
            subclassIndex = warmSubclassIndex,
            companionIndex = warmCompanionIndex,
            moduleIndex = Dict.empty[String, Tasty.Java.Module.Descriptor],
            errors = errors,
            diagnostics = Chunk.empty,
            unresolvedFullNameByNegId = unresolvedFullNameByNegId
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

    /** Deserialize annotation tycon fully-qualified name pool IDs into per-symbol Annotation chunks.
      *
      * Layout: [4-byte count] then entries [4-byte symIdx][4-byte annCount][annCount x 4-byte tyconFullNameId].
      * For each annotation, reconstruct `Annotation(Type.Named(SymbolId(-1)), Maybe.Absent)` where the
      * fully-qualified name from the name pool is used to look up the annotation class in the `fullNameIndex`
      * built by `deserialize`. Because `fullNameIndex` is not yet available at this point in the deserialization
      * order, we construct `Annotation` with a deferred fully-qualified name string stored as a synthetic sentinel
      * and fix up in the caller loop.
      * Simpler approach: store the tycon fully-qualified name string directly and create `Type.Named(SymbolId(-1))` with the
      * fully-qualified name. The `symbolsAnnotatedWith` call resolves via `typeFullNameString`, which calls `fullName`
      * on the symbol. So we need the tycon to be a real Named symbol or have a correct fully-qualified name match.
      *
      * Given the complexity of a deferred fixup, the approach here is: after deserialize builds finalSymbols
      * and their fullNameIndex, we call `deserializeAnnotationsByIdx` BEFORE building the final symbols, passing
      * a placeholder that stores the fully-qualified name string. The caller then resolves the symbol ID from the final fullNameIndex.
      * To keep it simple: store Annotation records with Type.Named(SymbolId(-1)) and a parallel fully-qualified name array.
      * Post-process in the while-loop using `finalFullNameIndex` mapping.
      *
      * Implementation: two-phase:
      *   Phase A: populate rawAnnotFullNamesByIdx with the fully-qualified name strings.
      *   Phase B (in the final while-loop): look up each fully-qualified name in finalFullNameIndex, build Annotation.
      */
    private def deserializeAnnotationsByIdx(
        bytes: Array[Byte],
        offset: Int,
        length: Int,
        symCount: Int,
        namePool: Array[String],
        out: Array[Chunk[Tasty.Annotation]]
    )(using AllowUnsafe): Unit =
        val end   = offset + length
        val count = SnapshotFormat.readInt32LE(bytes, offset)
        var pos   = offset + 4
        var i     = 0
        while i < count && pos + 8 <= end do
            val symIdx   = SnapshotFormat.readInt32LE(bytes, pos)
            val annCount = SnapshotFormat.readInt32LE(bytes, pos + 4)
            pos += 8
            val fullNames = new Array[String](annCount)
            var j         = 0
            while j < annCount && pos + 4 <= end do
                val nameId = SnapshotFormat.readInt32LE(bytes, pos)
                pos += 4
                fullNames(j) = if nameId >= 0 && nameId < namePool.length then namePool(nameId) else ""
                j += 1
            end while
            if symIdx >= 0 && symIdx < symCount then
                // Build Annotation instances encoding the fully-qualified name as Type.TermRef(Tuple(empty), Name(fullName)).
                // typeFullNameString handles TermRef by extracting the name asString, so symbolsAnnotatedWith
                // can match via fully-qualified name string comparison without requiring a resolved SymbolId.
                val anns = new Array[Tasty.Annotation](annCount)
                var k    = 0
                while k < annCount do
                    val fullName = fullNames(k)
                    // Encode the fully-qualified name as a TermRef so typeFullNameString can extract it.
                    // TermRef(prefix=Tuple(empty), name=Name(fullName)) survives the round-trip for
                    // symbolsAnnotatedWith count equality. Tuple(empty) is not a Named/TermRef/Applied type
                    // so typeFullNameString returns "" for it, leaving the result as just name.asString == fullName.
                    anns(k) = Tasty.Annotation(
                        annotationType = Tasty.Type.TermRef(
                            Tasty.Type.Tuple(Chunk.empty),
                            Tasty.Name(fullName)
                        ),
                        arguments = Chunk.empty,
                        annotationFullName = Tasty.Name(fullName)
                    )
                    k += 1
                end while
                out(symIdx) = Chunk.from(anns)
            end if
            i += 1
        end while
    end deserializeAnnotationsByIdx

    /** Deserialize javaMetadata accessFlags per symbol (JAVAMETA section).
      *
      * Layout: [4-byte count] then entries [4-byte symIdx][4-byte accessFlags].
      */
    private def deserializeJavaMetaByIdx(
        bytes: Array[Byte],
        offset: Int,
        length: Int,
        symCount: Int,
        out: Array[kyo.Maybe[Int]]
    ): Unit =
        val end   = offset + length
        val count = SnapshotFormat.readInt32LE(bytes, offset)
        var pos   = offset + 4
        var i     = 0
        while i < count && pos + 8 <= end do
            val symIdx      = SnapshotFormat.readInt32LE(bytes, pos)
            val accessFlags = SnapshotFormat.readInt32LE(bytes, pos + 4)
            pos += 8
            if symIdx >= 0 && symIdx < symCount then
                out(symIdx) = kyo.Maybe(accessFlags)
            end if
            i += 1
        end while
    end deserializeJavaMetaByIdx

    /** Deserialize from a memory-mapped ByteView.
      *
      * Reads header, section index, NAMES, SYMBOLS, and ERRORS into heap arrays (they are small). BODY_BYTES section is kept in mapped
      * memory: TastyOrigin.bodyView for each symbol is a sub-view into the mapped region. After the backing Arena is closed, symbol.body reads
      * from the mapped view and throws IllegalStateException, which Symbol.body catches as ClasspathClosed.
      */
    private def deserializeMapped(path: String, view: ByteView)(using AllowUnsafe): Tasty.Classpath =
        // Requires AllowUnsafe to call readSymbolsMapped (mutable index buffers); supplied by the
        // caller's Sync.Unsafe.defer wrapping in PlatformMmapReader.
        // Read the section index from the view.
        // SnapshotFormat.readInt32LE needs an Array[Byte]; copy 36+ bytes from the view for parsing.
        // The section index starts at byte 32. Each entry is sectionIndexEntrySize (24) bytes.
        // Read section count from offset 32, then copy all index entries.
        val sectionCountOffset = 32
        val sectionCount       = readInt32LEFromView(view, sectionCountOffset)
        // Cap sectionCount before iterating to prevent OOM from a corrupt header.
        if sectionCount < 0 || sectionCount > SnapshotFormat.maxSectionCount then
            throw new java.io.IOException(
                s"corrupt section count: $sectionCount (max=${SnapshotFormat.maxSectionCount})"
            )
        end if
        // Total mapped file size; used for range validation below.
        val totalLen = (view.position + view.remaining).toInt
        val indexEnd = 36 + sectionCount * SnapshotFormat.sectionIndexEntrySize
        // Copy the section index to a small heap array for parsing with existing SnapshotFormat helpers.
        val indexBytes = copyViewRange(view, sectionCountOffset, indexEnd)

        val sectionMap = mutable.HashMap.empty[String, (Int, Int)]
        var idxPos     = 4 // offset within indexBytes (skip the 4-byte sectionCount at bytes [0..3])
        var i          = 0
        while i < sectionCount do
            val name   = SnapshotFormat.readSectionName(indexBytes, idxPos)
            val offset = SnapshotFormat.readInt64LE(indexBytes, idxPos + 8).toInt
            val length = SnapshotFormat.readInt64LE(indexBytes, idxPos + 16).toInt
            // Bounds-check the section range before any copy.
            SectionValidator.validateRange(name, offset, length, totalLen)
            sectionMap(name) = (offset, length)
            idxPos += SnapshotFormat.sectionIndexEntrySize
            i += 1
        end while

        // Read NAMES section into heap (small).
        val namesBytes = sectionMap.get(SnapshotFormat.sectionNAMES) match
            case Some((off, len)) => copyViewRange(view, off, off + len)
            case None             => Array.empty[Byte]
        val namePool = if namesBytes.nonEmpty then readNamePool(namesBytes, 0, namesBytes.length) else Array.empty[String]

        // Keep BODY_BYTES in mapped memory via a ByteView sub-view. Absent when the section is
        // missing or empty; in that case body bytes are not available for this snapshot.
        val bodyView: Maybe[ByteView] = sectionMap.get(SnapshotFormat.sectionBODYBYTES) match
            case Some((off, len)) if len > 0 => Maybe.Present(view.subView(off, off + len))

            // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
            case _ => Maybe.Absent

        // Read SYMBOLS section into heap (small to medium).
        val (allSymbols, fullNameIndex, packageIndex, topLevelCls, packages) =
            sectionMap.get(SnapshotFormat.sectionSYMBOLS) match
                case Some((off, len)) =>
                    val symBytes = copyViewRange(view, off, off + len)
                    SectionValidator.validate(
                        SnapshotFormat.sectionSYMBOLS,
                        symBytes,
                        SectionValidator.SectionLayout.FixedRecordWithHeader(40)
                    )
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
        val typeParamsByIdx   = new Array[Chunk[kyo.Tasty.SymbolId]](symCount)
        val declarationsByIdx = new Array[Chunk[kyo.Tasty.SymbolId]](symCount)
        java.util.Arrays.fill(
            parentsByIdx.asInstanceOf[Array[Object]],
            Chunk.empty
        ) // java.util.Arrays.fill requires Array[Object]; safe covariant widening for reference types
        java.util.Arrays.fill(
            typeParamsByIdx.asInstanceOf[Array[Object]],
            Chunk.empty
        ) // java.util.Arrays.fill requires Array[Object]
        java.util.Arrays.fill(
            declarationsByIdx.asInstanceOf[Array[Object]],
            Chunk.empty
        ) // java.util.Arrays.fill requires Array[Object]

        sectionMap.get(SnapshotFormat.sectionPARENTS) match
            case Some((off, len)) if len > 0 =>
                val secBytes = copyViewRange(view, off, off + len)
                SectionValidator.validate(SnapshotFormat.sectionPARENTS, secBytes, SectionValidator.SectionLayout.Int32Array)
                deserializeRefListsByIdx(
                    secBytes,
                    0,
                    len,
                    symCount,
                    (idx, refs) =>
                        parentsByIdx(idx) = Chunk.from(refs.map(id => Tasty.Type.Named(SymbolId(id))))
                )

            // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
            case _ => ()
        end match

        sectionMap.get(SnapshotFormat.sectionTPARAMS) match
            case Some((off, len)) if len > 0 =>
                val secBytes = copyViewRange(view, off, off + len)
                SectionValidator.validate(SnapshotFormat.sectionTPARAMS, secBytes, SectionValidator.SectionLayout.Int32Array)
                deserializeRefListsByIdx(
                    secBytes,
                    0,
                    len,
                    symCount,
                    (idx, refs) =>
                        typeParamsByIdx(idx) = Chunk.from(refs.map(kyo.Tasty.SymbolId(_)))
                )

            // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
            case _ => ()
        end match

        sectionMap.get(SnapshotFormat.sectionMEMBERS) match
            case Some((off, len)) if len > 0 =>
                val secBytes = copyViewRange(view, off, off + len)
                SectionValidator.validate(SnapshotFormat.sectionMEMBERS, secBytes, SectionValidator.SectionLayout.Int32Array)
                deserializeRefListsByIdx(
                    secBytes,
                    0,
                    len,
                    symCount,
                    (idx, refs) =>
                        declarationsByIdx(idx) = Chunk.from(refs.map(kyo.Tasty.SymbolId(_)))
                )

            // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
            case _ => ()
        end match

        // PERMITS2 section .
        val permittedByIdxM = new Array[kyo.Maybe[Chunk[Int]]](symCount)
        java.util.Arrays.fill(
            permittedByIdxM.asInstanceOf[Array[Object]],
            kyo.Maybe.Absent
        ) // java.util.Arrays.fill requires Array[Object]
        sectionMap.get(SnapshotFormat.sectionPERMITS2) match
            case Some((off, len)) if len > 0 =>
                val secBytes = copyViewRange(view, off, off + len)
                SectionValidator.validate(SnapshotFormat.sectionPERMITS2, secBytes, SectionValidator.SectionLayout.Int32Array)
                deserializeRefListsByIdx(
                    secBytes,
                    0,
                    len,
                    symCount,
                    (idx, refs) =>
                        permittedByIdxM(idx) = kyo.Maybe(Chunk.from(refs))
                )

            // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
            case _ => ()
        end match

        // ANNOTS_ section .
        val annotationsByIdxM = new Array[Chunk[Tasty.Annotation]](symCount)
        java.util.Arrays.fill(
            annotationsByIdxM.asInstanceOf[Array[Object]],
            Chunk.empty
        ) // java.util.Arrays.fill requires Array[Object]
        sectionMap.get(SnapshotFormat.sectionANNOTS) match
            case Some((off, len)) if len > 0 =>
                val secBytes = copyViewRange(view, off, off + len)
                deserializeAnnotationsByIdx(secBytes, 0, len, symCount, namePool, annotationsByIdxM)

            // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
            case _ => ()
        end match

        // JAVAMETA section .
        val javaMetaByIdxM = new Array[kyo.Maybe[Int]](symCount)
        java.util.Arrays.fill(
            javaMetaByIdxM.asInstanceOf[Array[Object]],
            kyo.Maybe.Absent
        ) // java.util.Arrays.fill requires Array[Object]
        sectionMap.get(SnapshotFormat.sectionJAVAMETA) match
            case Some((off, len)) if len > 0 =>
                val secBytes = copyViewRange(view, off, off + len)
                deserializeJavaMetaByIdx(secBytes, 0, len, symCount, javaMetaByIdxM)

            // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
            case _ => ()
        end match

        // PLISTS__ section: paramListIds per method symbol (added in minor=12).
        val paramListsByIdxM = new Array[Chunk[Chunk[Int]]](symCount)
        java.util.Arrays.fill(
            paramListsByIdxM.asInstanceOf[Array[Object]],
            Chunk.empty
        ) // java.util.Arrays.fill requires Array[Object]
        sectionMap.get(SnapshotFormat.sectionPLISTS) match
            case Some((off, len)) if len > 0 =>
                val secBytes = copyViewRange(view, off, off + len)
                readParamListsByIdx(secBytes, 0, len, symCount, paramListsByIdxM)

            // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
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
                    case p: Tasty.Symbol.Parameter   => p.declaredType
                    case ta: Tasty.Symbol.TypeAlias  => ta.body
                    case ot: Tasty.Symbol.OpaqueType => ot.body
                    case _: Tasty.Symbol.Class | _: Tasty.Symbol.EnumCase | _: Tasty.Symbol.Trait |
                        _: Tasty.Symbol.Object | _: Tasty.Symbol.AbstractType | _: Tasty.Symbol.TypeParam |
                        _: Tasty.Symbol.Package =>
                        kyo.Maybe.Absent
                ,
                scaladoc = partial.scaladoc,
                sourcePosition = partial.sourcePosition,
                javaMetadata = javaMetaByIdxM(j) match
                    case kyo.Maybe.Present(flags) =>
                        kyo.Maybe(Tasty.Java.Metadata(
                            throwsTypes = Chunk.empty,
                            annotations = Chunk.empty,
                            enclosingMethod = kyo.Maybe.Absent,
                            accessFlags = flags,
                            recordComponents = Chunk.empty,
                            bootstrapMethods = Chunk.empty,
                            nestHost = kyo.Maybe.Absent,
                            nestMembers = Chunk.empty,
                            paramNames = Chunk.empty,
                            runtimeTypeAnnotations = Chunk.empty
                        ))
                    case kyo.Maybe.Absent =>
                        kyo.Maybe.Absent
                ,
                parentTypes = parentsByIdx(j),
                typeParamIds = Chunk.from(tpIds.toSeq.map(_.value)),
                declarationIds = Chunk.from(declIds.toSeq.map(_.value)),
                paramListIds = paramListsByIdxM(j),
                permittedSubclassIds = permittedByIdxM(j) match
                    case kyo.Maybe.Present(ids) => kyo.Maybe(ids)
                    case kyo.Maybe.Absent =>
                        kyo.Maybe.Absent
                ,
                annotations = annotationsByIdxM(j),
                body = kyo.Maybe.Absent
            )
            finalSymbols(j) = TypedSymbolFactory.from(d)
            j += 1
        end while

        val newFullNameIndex = fullNameIndex.map { case (k, v) =>
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

        // FQNIDX__ section : reconstruct the full fullNameIndex verbatim.
        val qualifiedFullNameIdIdx2: Dict[String, SymbolId] = sectionMap.get(SnapshotFormat.sectionFQNIDX) match
            case Some((off, len)) if len > 0 =>
                val secBytes = copyViewRange(view, off, off + len)
                deserializeFullNameIndex(secBytes, 0, len, namePool, finalSymbols)

            // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
            case _ =>
                Dict.from(newFullNameIndex.map { case (k, v) => k -> v.id }.toMap)

        // FQNMAP__ section: reconstruct unresolvedFullNameByNegId for warm loads.
        val unresolvedFullNameByNegId2: Dict[SymbolId, String] = sectionMap.get(SnapshotFormat.sectionFQNMAP) match
            case Some((off, len)) if len > 0 =>
                val secBytes = copyViewRange(view, off, off + len)
                deserializeFullNameMap(secBytes, 0, len, namePool)

            // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
            case _ =>
                Dict.empty[SymbolId, String]

        // SUBCIDX_ section : subclassIndex for warm-load parity.
        val warmSubclassIndex2: Dict[SymbolId, Chunk[SymbolId]] =
            sectionMap.get(SnapshotFormat.sectionSUBCIDX) match
                case Some((off, len)) if len > 0 =>
                    val secBytes = copyViewRange(view, off, off + len)
                    deserializeSubclassIndex(secBytes, 0, len, finalSymbols.length)

                // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
                case _ =>
                    Dict.empty[SymbolId, Chunk[SymbolId]]

        // COMPIDX_ section : companionIndex for warm-load parity.
        val warmCompanionIndex2: Dict[SymbolId, SymbolId] =
            sectionMap.get(SnapshotFormat.sectionCOMPIDX) match
                case Some((off, len)) if len > 0 =>
                    val secBytes = copyViewRange(view, off, off + len)
                    deserializeCompanionIndex(secBytes, 0, len, finalSymbols.length)

                // Carve-out: stdlib Option from mutable.HashMap.get; absent or empty section
                case _ =>
                    Dict.empty[SymbolId, SymbolId]

        val symsChunk2 = Chunk.from(finalSymbols)
        val pkgIdIdx2  = Dict.from(newPackageIndex.map { case (k, v) => k -> v.id }.toMap)
        val topIds2    = newTopLevelCls.map(_.id)
        val pkgIds2    = newPackages.map(_.id)
        val rootId2    = if symsChunk2.nonEmpty then SymbolId(0) else SymbolId(-1)
        Tasty.Classpath.make(
            symbols = symsChunk2,
            rootSymbolId = rootId2,
            topLevelClassIds = topIds2,
            packageIds = pkgIds2,
            fullNameIndex = qualifiedFullNameIdIdx2,
            packageIndex = pkgIdIdx2,
            subclassIndex = warmSubclassIndex2,
            companionIndex = warmCompanionIndex2,
            moduleIndex = Dict.empty[String, Tasty.Java.Module.Descriptor],
            errors = errors,
            unresolvedFullNameByNegId = unresolvedFullNameByNegId2
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

    /** readSymbols variant for the mmap path.
      *
      * `bodyViewOpt` carries a sub-view into the mapped BODY_BYTES region when that section is
      * present. Body bytes are currently not propagated into SymbolDescriptor (body is always
      * Absent after a snapshot load), so the parameter is reserved for future use.
      */
    private def readSymbolsMapped(
        bytes: Array[Byte],
        offset: Int,
        length: Int,
        namePool: Array[String],
        bodyViewOpt: Maybe[ByteView]
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
            val kindOrd    = bytes(pos) & 0xff
            val flagBits   = SnapshotFormat.readInt64LE(bytes, pos + 1)
            val nameId     = SnapshotFormat.readInt32LE(bytes, pos + 9)
            val fullNameId = SnapshotFormat.readInt32LE(bytes, pos + 13)
            val ownerId    = SnapshotFormat.readInt32LE(bytes, pos + 17)
            val bodyStart  = SnapshotFormat.readInt32LE(bytes, pos + 21)
            val bodyEnd    = SnapshotFormat.readInt32LE(bytes, pos + 25)
            raws(i) = RawSymbol(kindOrd, flagBits, nameId, fullNameId, ownerId, bodyStart, bodyEnd)
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
            val raw   = raws(idx)
            val kind  = kindFromOrd(raw.kindOrd)
            val flags = Tasty.Flags.fromBits(raw.flagBits)
            val name: Tasty.Name =
                if raw.nameId >= 0 && raw.nameId < namePool.length then Tasty.Name(namePool(raw.nameId))
                else Tasty.Name("")
            val ownerIdInt = raw.ownerId
            val ownerIdVal = if ownerIdInt >= 0 && ownerIdInt < count then ownerIdInt else idx
            // Body bytes are not propagated through SymbolDescriptor.body in snapshot loads.
            // The BODY_BYTES section is read above for backward compatibility (old snapshots may
            // have non-empty BODY_BYTES) but the data is discarded here. bodyTree returns Absent
            // after a snapshot load until withClasspath(roots) re-populates DecodeContext.bodyStore.
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
                body = kyo.Maybe.Absent
            )
            created(idx) = TypedSymbolFactory.from(desc)
        end for

        val fullNameIndex = mutable.HashMap.empty[String, Tasty.Symbol]
        val packageIndex  = mutable.HashMap.empty[String, Tasty.Symbol]
        val allSymbols    = mutable.ArrayBuffer.empty[Tasty.Symbol]
        val topLevelCls   = mutable.ArrayBuffer.empty[Tasty.Symbol]
        val packages      = mutable.ArrayBuffer.empty[Tasty.Symbol]

        i = 0
        while i < count do
            val raw      = raws(i)
            val fullName = if raw.fullNameId >= 0 && raw.fullNameId < namePool.length then namePool(raw.fullNameId) else ""
            val symbol   = created(i)
            if symbol != null then
                allSymbols += symbol
                if fullName.nonEmpty then
                    fullNameIndex(fullName) = symbol
                    symbol.kind match
                        case SymbolKind.Package =>
                            packages += symbol
                            packageIndex(fullName) = symbol
                        case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Object =>
                            topLevelCls += symbol
                        case SymbolKind.EnumCase | SymbolKind.Method | SymbolKind.Field | SymbolKind.Val |
                            SymbolKind.Var | SymbolKind.TypeAlias | SymbolKind.OpaqueType |
                            SymbolKind.AbstractType | SymbolKind.TypeParam | SymbolKind.Parameter =>
                            ()
                    end match
                end if
            end if
            i += 1
        end while

        (
            Chunk.from(allSymbols),
            fullNameIndex,
            packageIndex,
            Chunk.from(topLevelCls),
            Chunk.from(packages)
        )
    end readSymbolsMapped

    /** Deserialize the FQNIDX__ section into a full Map[String, SymbolId].
      *
      * Layout: [4-byte count LE] then count entries each [4-byte namePoolId LE][4-byte snapshotIdx LE].
      * Each entry maps a fully-qualified name string (from the name pool) to the SymbolId of the symbol at snapshotIdx
      * in `finalSymbols`. Entries with an out-of-range snapshotIdx are skipped.
      *
      * This reconstructs the full fullNameIndex verbatim (all keys including dual-index source fully-qualified name aliases)
      * so that warm-load lookups via source fully-qualified name work identically to cold-load.
      */
    private def deserializeFullNameIndex(
        bytes: Array[Byte],
        offset: Int,
        length: Int,
        namePool: Array[String],
        finalSymbols: Array[Tasty.Symbol]
    ): Dict[String, SymbolId] =
        val symCount = finalSymbols.length
        val end      = offset + length
        val count    = SnapshotFormat.readInt32LE(bytes, offset)
        var pos      = offset + 4
        var i        = 0
        val builder  = DictBuilder.init[String, SymbolId]
        while i < count && pos + 8 <= end do
            val nameId      = SnapshotFormat.readInt32LE(bytes, pos)
            val snapshotIdx = SnapshotFormat.readInt32LE(bytes, pos + 4)
            pos += 8
            if nameId >= 0 && nameId < namePool.length && snapshotIdx >= 0 && snapshotIdx < symCount then
                val fullName = namePool(nameId)
                if fullName.nonEmpty then
                    discard(builder.add(fullName, finalSymbols(snapshotIdx).id))
            end if
            i += 1
        end while
        builder.result()
    end deserializeFullNameIndex

    /** Deserialize the FQNMAP__ section into a Map[Int, String] (negId -> fully-qualified name string).
      *
      * Layout: [4-byte count LE] then count entries each [4-byte negId LE][4-byte namePoolId LE]. Each entry maps a negative SymbolId to
      * the fully-qualified name string of the external annotation type. Entries with an out-of-range namePoolId or an empty string are skipped.
      */
    private def deserializeFullNameMap(
        bytes: Array[Byte],
        offset: Int,
        length: Int,
        namePool: Array[String]
    ): Dict[SymbolId, String] =
        val end     = offset + length
        val count   = SnapshotFormat.readInt32LE(bytes, offset)
        var pos     = offset + 4
        var i       = 0
        val builder = DictBuilder.init[SymbolId, String]
        while i < count && pos + 8 <= end do
            val negId      = SnapshotFormat.readInt32LE(bytes, pos)
            val namePoolId = SnapshotFormat.readInt32LE(bytes, pos + 4)
            pos += 8
            if namePoolId >= 0 && namePoolId < namePool.length then
                val fullName = namePool(namePoolId)
                if fullName.nonEmpty then discard(builder.add(SymbolId(negId), fullName))
            end if
            i += 1
        end while
        builder.result()
    end deserializeFullNameMap

    /** Deserialize subclassIndex from the SUBCIDX_ section .
      *
      * Layout: [4-byte count LE] then count entries each
      *   [4-byte parentSymIdx LE][4-byte childCount LE][childCount x 4-byte childSymIdx LE].
      * Returns a Map[SymbolId, Chunk[SymbolId]] keyed by the SymbolId at snapshot position parentSymIdx.
      * Entries with out-of-range indices are silently skipped.
      */
    private def deserializeSubclassIndex(
        bytes: Array[Byte],
        offset: Int,
        length: Int,
        symCount: Int
    ): Dict[SymbolId, Chunk[SymbolId]] =
        val end     = offset + length
        val count   = SnapshotFormat.readInt32LE(bytes, offset)
        var pos     = offset + 4
        var i       = 0
        val builder = DictBuilder.init[SymbolId, Chunk[SymbolId]]
        while i < count && pos + 8 <= end do
            val parentIdx  = SnapshotFormat.readInt32LE(bytes, pos)
            val childCount = SnapshotFormat.readInt32LE(bytes, pos + 4)
            pos += 8
            val children = new Array[SymbolId](childCount)
            var j        = 0
            while j < childCount && pos + 4 <= end do
                children(j) = SymbolId(SnapshotFormat.readInt32LE(bytes, pos))
                pos += 4
                j += 1
            end while
            if parentIdx >= 0 && parentIdx < symCount then
                val validChildren = Chunk.from(children.take(j).filter(cid => cid.value >= 0 && cid.value < symCount))
                if validChildren.nonEmpty then
                    discard(builder.add(SymbolId(parentIdx), validChildren))
            end if
            i += 1
        end while
        builder.result()
    end deserializeSubclassIndex

    /** Deserialize companionIndex from the COMPIDX_ section .
      *
      * Layout: [4-byte count LE] then count entries each
      *   [4-byte symIdx LE][4-byte companionSymIdx LE].
      * Returns a Map[SymbolId, SymbolId] keyed by the SymbolId at snapshot position symIdx.
      * Entries with out-of-range indices are silently skipped.
      */
    private def deserializeCompanionIndex(
        bytes: Array[Byte],
        offset: Int,
        length: Int,
        symCount: Int
    ): Dict[SymbolId, SymbolId] =
        val end     = offset + length
        val count   = SnapshotFormat.readInt32LE(bytes, offset)
        var pos     = offset + 4
        var i       = 0
        val builder = DictBuilder.init[SymbolId, SymbolId]
        while i < count && pos + 8 <= end do
            val symIdx       = SnapshotFormat.readInt32LE(bytes, pos)
            val companionIdx = SnapshotFormat.readInt32LE(bytes, pos + 4)
            pos += 8
            if symIdx >= 0 && symIdx < symCount && companionIdx >= 0 && companionIdx < symCount then
                discard(builder.add(SymbolId(symIdx), SymbolId(companionIdx)))
            end if
            i += 1
        end while
        builder.result()
    end deserializeCompanionIndex

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
        fullNameId: Int,
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
            val kindOrd    = bytes(pos) & 0xff
            val flagBits   = SnapshotFormat.readInt64LE(bytes, pos + 1)
            val nameId     = SnapshotFormat.readInt32LE(bytes, pos + 9)
            val fullNameId = SnapshotFormat.readInt32LE(bytes, pos + 13)
            val ownerId    = SnapshotFormat.readInt32LE(bytes, pos + 17)
            val bodyStart  = SnapshotFormat.readInt32LE(bytes, pos + 21)
            val bodyEnd    = SnapshotFormat.readInt32LE(bytes, pos + 25)
            raws(i) = RawSymbol(kindOrd, flagBits, nameId, fullNameId, ownerId, bodyStart, bodyEnd)
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
            val raw   = raws(idx)
            val kind  = kindFromOrd(raw.kindOrd)
            val flags = Tasty.Flags.fromBits(raw.flagBits)
            val name: Tasty.Name =
                if raw.nameId >= 0 && raw.nameId < namePool.length then Tasty.Name(namePool(raw.nameId))
                else Tasty.Name("")
            val ownerIdInt = raw.ownerId
            // ownerId: use index directly; -1 means self-referential (root sentinel).
            val ownerIdVal = if ownerIdInt >= 0 && ownerIdInt < count then ownerIdInt else idx
            // Body bytes are not propagated through SymbolDescriptor.body in snapshot loads.
            // The BODY_BYTES section is read above for backward compatibility with old snapshots.
            // bodyTree returns Absent after a snapshot load until withClasspath(roots) is used.
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
                body = kyo.Maybe.Absent
            )
            created(idx) = TypedSymbolFactory.from(desc2)
        end for

        // Build indices
        val fullNameIndex = mutable.HashMap.empty[String, Tasty.Symbol]
        val packageIndex  = mutable.HashMap.empty[String, Tasty.Symbol]
        val allSymbols    = mutable.ArrayBuffer.empty[Tasty.Symbol]
        val topLevelCls   = mutable.ArrayBuffer.empty[Tasty.Symbol]
        val packages      = mutable.ArrayBuffer.empty[Tasty.Symbol]

        i = 0
        while i < count do
            val raw      = raws(i)
            val fullName = if raw.fullNameId >= 0 && raw.fullNameId < namePool.length then namePool(raw.fullNameId) else ""
            val symbol   = created(i)
            if symbol != null then
                allSymbols += symbol
                if fullName.nonEmpty then
                    fullNameIndex(fullName) = symbol
                    symbol.kind match
                        case SymbolKind.Package =>
                            packages += symbol
                            packageIndex(fullName) = symbol
                        case SymbolKind.Class | SymbolKind.Trait | SymbolKind.Object =>
                            topLevelCls += symbol
                        case SymbolKind.EnumCase | SymbolKind.Method | SymbolKind.Field | SymbolKind.Val |
                            SymbolKind.Var | SymbolKind.TypeAlias | SymbolKind.OpaqueType |
                            SymbolKind.AbstractType | SymbolKind.TypeParam | SymbolKind.Parameter =>
                            ()
                    end match
                end if
            end if
            i += 1
        end while

        (
            Chunk.from(allSymbols),
            fullNameIndex,
            packageIndex,
            Chunk.from(topLevelCls),
            Chunk.from(packages)
        )
    end readSymbols

    /** Read errors from the ERRORS section (minor=10 string-tag format).
      *
      * Layout: [4-byte count LE] followed by count typed entries. Each entry:
      *   [LEB128 varint: tag byte count] [UTF-8 tag bytes == TastyError.productPrefix] [variant-specific fields]
      *
      * The tag is a varint-length-prefixed UTF-8 string (the case name). This format is stable against future enum
      * variant additions; unknown tags fall back to TastyError.NotImplemented carrying the unknown tag string.
      *
      * String fields: [4-byte len LE][UTF-8 bytes].
      * Long fields:   [8-byte Int64 LE].
      * Version:       [4-byte major LE][4-byte minor LE].
      * UUID:          [8-byte MSB LE][8-byte LSB LE].
      * Int fields:    [4-byte Int32 LE].
      */
    private def readErrors(bytes: Array[Byte], offset: Int, length: Int): Chunk[TastyError] =
        val count = SnapshotFormat.readInt32LE(bytes, offset)
        if count <= 0 then Chunk.empty
        else
            var pos    = offset + 4
            val buffer = mutable.ArrayBuffer.empty[TastyError]
            var i      = 0

            def readStr(): String =
                val len = SnapshotFormat.readInt32LE(bytes, pos)
                pos += 4
                val s = SnapshotFormat.decodeString(bytes, pos, len)
                pos += len
                s
            end readStr

            def readLong(): Long =
                val v = SnapshotFormat.readInt64LE(bytes, pos)
                pos += 8
                v
            end readLong

            def readInt(): Int =
                val v = SnapshotFormat.readInt32LE(bytes, pos)
                pos += 4
                v
            end readInt

            def readVersion(): Tasty.Version =
                val major = readInt()
                val minor = readInt()
                Tasty.Version(major, minor, 0)
            end readVersion

            def readUUID(): Tasty.Uuid =
                val msb = readLong()
                val lsb = readLong()
                Tasty.Uuid.unsafeWrap(new java.util.UUID(msb, lsb).toString)
            end readUUID

            // Standard LEB128 (little-endian base-128) varint decoder. Each byte contributes 7 bits
            // to the result; the high bit (0x80) signals continuation. Values 0-127 read as one byte;
            // values 128-16383 read as two bytes (e.g. 0xC8 0x01 decodes to 200).
            def readVarint(): Int =
                var result = 0
                var shift  = 0
                var more   = true
                while more do
                    val b = bytes(pos) & 0xff
                    pos += 1
                    result |= (b & 0x7f) << shift
                    shift += 7
                    if (b & 0x80) == 0 then more = false
                end while
                result
            end readVarint

            // Recursive decoder for Tasty.Type fields encoded by writeType.
            // Tag byte meanings match the writeType encoding (see SnapshotWriter.serializeErrors):
            //   0 = Named, 1 = Any, 2 = Nothing, 3 = Applied, 4 = TermRef, 5 = TypeRef,
            //   6 = Tuple, 7 = Function, 8 = ContextFunction, 9 = ByName, 10 = Repeated,
            //   11 = Array, 255 = Opaque (stored as show string; no round-trip semantic).
            def readType(): Tasty.Type =
                val tag = bytes(pos) & 0xff
                pos += 1
                tag match
                    case 0 =>
                        Tasty.Type.Named(Tasty.SymbolId(readInt()))
                    case 1 =>
                        Tasty.Type.Any
                    case 2 =>
                        Tasty.Type.Nothing
                    case 3 =>
                        val base     = readType()
                        val argCount = readVarint()
                        val argsB    = scala.collection.mutable.ArrayBuffer.empty[Tasty.Type]
                        var ai       = 0
                        while ai < argCount do
                            argsB += readType()
                            ai += 1
                        Tasty.Type.Applied(base, Chunk.from(argsB.toSeq))
                    case 4 =>
                        val prefix = readType()
                        val name   = readStr()
                        Tasty.Type.TermRef(prefix, Tasty.Name(name))
                    case 5 =>
                        val qual = readType()
                        val name = readStr()
                        Tasty.Type.TypeRef(qual, Tasty.Name(name))
                    case 6 =>
                        val count  = readVarint()
                        val elemsB = scala.collection.mutable.ArrayBuffer.empty[Tasty.Type]
                        var ei     = 0
                        while ei < count do
                            elemsB += readType()
                            ei += 1
                        Tasty.Type.Tuple(Chunk.from(elemsB.toSeq))
                    case 7 =>
                        val count   = readVarint()
                        val paramsB = scala.collection.mutable.ArrayBuffer.empty[Tasty.Type]
                        var pi      = 0
                        while pi < count do
                            paramsB += readType()
                            pi += 1
                        val result = readType()
                        Tasty.Type.Function(Chunk.from(paramsB.toSeq), result)
                    case 8 =>
                        val count   = readVarint()
                        val paramsB = scala.collection.mutable.ArrayBuffer.empty[Tasty.Type]
                        var pi      = 0
                        while pi < count do
                            paramsB += readType()
                            pi += 1
                        val result = readType()
                        Tasty.Type.ContextFunction(Chunk.from(paramsB.toSeq), result)
                    case 9 =>
                        Tasty.Type.ByName(readType())
                    case 10 =>
                        Tasty.Type.Repeated(readType())
                    case 11 =>
                        Tasty.Type.Array(readType())
                    // Carve-out: opaque tag-byte dispatch (snapshot type wire); unknown tags fall back to Nothing
                    case _ =>
                        // The show string is consumed to advance pos correctly; diagnostics are lost on read.
                        val _ = readStr()
                        Tasty.Type.Nothing
                end match
            end readType

            while i < count do
                val tagLen   = readVarint()
                val tagBytes = new Array[Byte](tagLen)
                var j        = 0
                while j < tagLen do
                    tagBytes(j) = bytes(pos)
                    pos += 1
                    j += 1
                end while
                val tag: String = new String(tagBytes, java.nio.charset.StandardCharsets.UTF_8)
                val err: TastyError = tag match
                    case "FileNotFound" => TastyError.FileNotFound(readStr())
                    case "CorruptedFile" =>
                        val p = readStr(); val at = readLong(); val r = readStr()
                        TastyError.CorruptedFile(p, at, r)
                    case "UnsupportedVersion" =>
                        val f = readVersion(); val s = readVersion()
                        TastyError.UnsupportedVersion(f, s)
                    case "InconsistentClasspath" =>
                        val f = readStr(); val e = readUUID(); val fd = readUUID()
                        TastyError.InconsistentClasspath(f, e, fd)
                    case "FullNameCollisionError" => TastyError.FullNameCollisionError(readStr())
                    case "MalformedSection" =>
                        val n = readStr(); val r = readStr(); val at = readLong()
                        TastyError.MalformedSection(n, r, at)
                    case "SymbolNotFound" => TastyError.SymbolNotFound(readStr())
                    case "NotFound"       => TastyError.NotFound(readStr())
                    case "ClassfileFormatError" =>
                        val p = readStr(); val r = readStr(); val at = readLong()
                        TastyError.ClassfileFormatError(p, r, at)
                    case "ClasspathClosed"   => TastyError.ClasspathClosed(readStr())
                    case "ClasspathBuilding" => TastyError.ClasspathBuilding(readStr())
                    case "SnapshotFormatError" =>
                        val p = readStr(); val r = readStr(); val at = readLong()
                        TastyError.SnapshotFormatError(p, r, at)
                    case "SnapshotVersionMismatch" =>
                        val f = readVersion(); val s = readVersion()
                        TastyError.SnapshotVersionMismatch(f, s)
                    case "SnapshotIoError"     => TastyError.SnapshotIoError(readStr())
                    case "NotImplemented"      => TastyError.NotImplemented(readStr())
                    case "UnsupportedPlatform" => TastyError.UnsupportedPlatform(readStr())
                    case "UnknownTagInPosition" =>
                        val t = readInt(); val p = readStr()
                        TastyError.UnknownTagInPosition(t, p)
                    case "InvalidFullName" =>
                        val fullName = readStr(); val reason = readStr()
                        TastyError.InvalidFullName(fullName, reason)
                    case "InvalidUuid" => TastyError.InvalidUuid(readStr())
                    case "DigestMismatch" =>
                        val exp = readStr(); val act = readStr()
                        TastyError.DigestMismatch(exp, act)
                    case "UnhandledSubtypingCase" =>
                        val shape = readStr(); val lhs = readType(); val rhs = readType(); val file = readStr()
                        TastyError.UnhandledSubtypingCase(shape, lhs, rhs, file)
                    case "UnresolvedReference" =>
                        val name = readStr(); val idx = readInt()
                        TastyError.UnresolvedReference(name, idx)
                    case "UnknownType" =>
                        val file = readStr(); val byteOffset = readLong(); val reason = readStr()
                        TastyError.UnknownType(file, byteOffset, reason)
                    case "MissingDeclaredType" =>
                        val symbolId = Tasty.SymbolId(readInt()); val file = readStr()
                        TastyError.MissingDeclaredType(symbolId, file)
                    case other => TastyError.NotImplemented(s"unknown TastyError wire tag: $other")
                buffer += err
                i += 1
            end while
            Chunk.from(buffer.toSeq)
        end if
    end readErrors

    /** Read the PLISTS__ section into an Array[Chunk[Chunk[Int]]] indexed by symbol position.
      *
      * Sparse two-level Int32-LE decoding symmetric with serializeParamLists in SnapshotWriter.
      * Bounds-safety: every inner read guards pos + 4 <= end mirroring deserializeRefListsByIdx.
      * Entries with out-of-range symIdx are silently skipped. Methods absent from the section
      * default to Chunk.empty (identical to the pre-minor-12 behaviour, but only reached for
      * minor-12 snapshots where all method symbols were emitted by the writer).
      */
    private def readParamListsByIdx(
        bytes: Array[Byte],
        offset: Int,
        length: Int,
        symCount: Int,
        out: Array[Chunk[Chunk[Int]]]
    ): Unit =
        val end = offset + length
        if length < 4 then return
        val entryCount = SnapshotFormat.readInt32LE(bytes, offset)
        var pos        = offset + 4
        var ei         = 0
        while ei < entryCount && pos + 8 <= end do
            val symIdx    = SnapshotFormat.readInt32LE(bytes, pos); pos += 4
            val listCount = SnapshotFormat.readInt32LE(bytes, pos); pos += 4
            val outer     = new Array[Chunk[Int]](listCount)
            var li        = 0
            while li < listCount && pos + 4 <= end do
                val innerCount = SnapshotFormat.readInt32LE(bytes, pos); pos += 4
                val inner      = new Array[Int](innerCount)
                var ii         = 0
                while ii < innerCount && pos + 4 <= end do
                    inner(ii) = SnapshotFormat.readInt32LE(bytes, pos); pos += 4
                    ii += 1
                end while
                outer(li) = Chunk.from(inner.take(ii).toSeq)
                li += 1
            end while
            if symIdx >= 0 && symIdx < symCount then
                out(symIdx) = Chunk.from(outer.take(li).toSeq)
            end if
            ei += 1
        end while
    end readParamListsByIdx

    /** Convert SymbolKind ordinal integer to enum case. */
    private def kindFromOrd(ord: Int): SymbolKind =
        import SymbolKind.*
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
            // Carve-out: snapshot ordinal-byte dispatch; legacy ordinal 14 (Unresolved) maps to Package
            case _ => Package
        end match
    end kindFromOrd

end SnapshotReader
