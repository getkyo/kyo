package kyo.internal.tasty.snapshot

import java.io.ByteArrayOutputStream
import kyo.*
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.symbol.FqnNormalizer
import kyo.internal.tasty.symbol.SymbolId
import scala.collection.mutable

/** Writes a `Classpath` to a KRFL snapshot file.
  *
  * Write strategy (atomic rename for concurrent safety):
  *   1. Serialize to bytes in memory.
  *   2. Write to `${cacheDir}/${digest}-${pid}-${nonce}.krfl` (temp file).
  *   3. Rename to `${cacheDir}/${digest}.krfl` (atomic on POSIX; last writer wins).
  *
  * Two concurrent processes decoding the same input produce identical tmp files (decode is deterministic) and both attempt rename. The last
  * rename wins; the loser's tmp is silently discarded. No file locking, no corruption.
  *
  * Stale tmp files (from crashed writers) are removed by `Tasty.Snapshot.evictOlderThan(d)`.
  *
  * I/O errors (unwritable cache dir, disk full) produce `TastyError.SnapshotIoError`.
  */
object SnapshotWriter:

    /** Serialize a Classpath to KRFL bytes without writing to disk.
      *
      * Exposed as a package-private helper so tests can perform in-memory snapshot round-trips on all platforms (JVM, JS, Native) without
      * requiring a real filesystem. The returned array is identical to what `write` would persist on disk.
      *
      * @param cp
      *   the fully-loaded classpath to snapshot
      * @param digest
      *   8-byte FNV-1a digest of the inputs (from DigestComputer)
      */
    private[kyo] def serializeToBytes(cp: Tasty.Classpath, digest: Array[Byte]): Array[Byte] =
        serialize(cp, digest)

    /** Write a snapshot of `cp` to `cacheDir` using the given input `digest`.
      *
      * @param cp
      *   the fully-loaded classpath to snapshot
      * @param cacheDir
      *   directory where the snapshot file is written
      * @param digest
      *   8-byte FNV-1a digest of the inputs (from DigestComputer)
      * @param source
      *   FileSource used to create the cache directory if needed and to write the snapshot
      */
    def write(
        cp: Tasty.Classpath,
        cacheDir: String,
        digest: Array[Byte],
        source: FileSource
    )(using Frame): Unit < (Sync & Abort[TastyError]) =
        val hexDigest = DigestComputer.toHexString(digest)
        val unique    = java.lang.System.nanoTime() ^ scala.util.Random.nextLong()
        val tmpName   = s"$cacheDir/$hexDigest-$unique.krfl"
        val finalName = s"$cacheDir/$hexDigest.krfl"
        Abort.run[TastyError](
            Sync.defer(serialize(cp, digest)).flatMap: bytes =>
                source.mkdirs(cacheDir).andThen:
                    source.write(tmpName, bytes).andThen:
                        source.rename(tmpName, finalName)
        ).flatMap:
            case Result.Success(_)                             => Kyo.unit
            case Result.Failure(e: TastyError.SnapshotIoError) => Abort.fail(e)
            case Result.Failure(e)                             => Abort.fail(TastyError.SnapshotIoError(e.toString))
            case Result.Panic(t)                               => Abort.fail(TastyError.SnapshotIoError(t.getMessage))
    end write

    /** Serialize a Classpath to KRFL bytes, embedding the given input digest in the file header. */
    private def serialize(cp: Tasty.Classpath, digest: Array[Byte]): Array[Byte] =
        // Direct field reads from the immutable case class; no AllowUnsafe needed.
        val allSymbols = cp.symbols
        // Build a reverse map Symbol->fqn from the classpath's fqnIndex so snapshot FQNs are
        // the real registered FQNs (e.g. "test.Foo"), not just simple names ("Foo").
        // Symbols without an fqnIndex entry get an empty FQN (they will not be findClass-lookup-able).
        // HARD RULE 10: store only the canonical source FQN per symbol. The fqnIndex contains dual-index
        // entries (e.g. both "scala.Predef$" and "scala.Predef"), so we apply canonicalSourceFqn before
        // storing. Multiple binary FQNs that canonicalize to the same source form produce the same put,
        // which is deterministic and correct (the canonical source FQN is the user-facing name).
        // F-A4-005 determinism: sort fqnIndex before building fqnBySymbol so that when a symbol
        // has multiple FQN aliases (e.g. "scala.Predef$" and "scala.Predef"), the LAST overwrite
        // in alphabetical order wins deterministically across JVM invocations. Without sorting,
        // HashMap.foreach iteration order may differ between invocations, causing different canonical
        // FQNs to be stored in fqnBySymbol for symbols with multiple aliases.
        val fqnBySymbol: java.util.IdentityHashMap[Tasty.Symbol, String] =
            val rev = new java.util.IdentityHashMap[Tasty.Symbol, String]()
            cp.fqnIndex.toSeq.sortBy(_._1).foreach { case (fqn, id) =>
                val sym       = cp.symbol(id)
                val canonical = FqnNormalizer.canonicalSourceFqn(fqn)
                rev.put(sym, canonical)
            }
            rev
        end fqnBySymbol

        val symbolList = allSymbols.toSeq

        // Build name pool (all unique FQN strings)
        val namePool  = mutable.ArrayBuffer.empty[String]
        val nameIndex = mutable.HashMap.empty[String, Int]

        def internName(s: String): Int =
            nameIndex.getOrElseUpdate(
                s, {
                    val id = namePool.length
                    namePool += s
                    id
                }
            )

        // Assign IDs to all symbols
        val symbolId = mutable.HashMap.empty[Tasty.Symbol, Int]
        for (sym, i) <- symbolList.zipWithIndex do
            symbolId(sym) = i

        // Intern symbol names and FQNs
        val symNames = symbolList.map: sym =>
            internName(nameToStr(sym.name))

        val symFqns = symbolList.map: sym =>
            val fqn = fqnBySymbol.get(sym)
            if fqn != null && fqn.nonEmpty then internName(fqn)
            else internName(nameToStr(sym.name)) // fallback: simple name for non-indexed symbols

        // Collect body byte slices from Symbol.body: Maybe[SymbolBody].
        val bodyBytesBuffer = new java.io.ByteArrayOutputStream(128 * 1024 * 1024)
        var runningOffset   = 0
        val symBodyStarts   = new Array[Int](symbolList.size)
        val symBodyEnds     = new Array[Int](symbolList.size)
        for (sym, idx) <- symbolList.zipWithIndex do
            (sym match
                case c: Tasty.Symbol.Class  => c.body
                case t: Tasty.Symbol.Trait  => t.body
                case o: Tasty.Symbol.Object => o.body
                case m: Tasty.Symbol.Method => m.body
                case v: Tasty.Symbol.Val    => v.body
                case w: Tasty.Symbol.Var    => w.body
                case _                      => kyo.Maybe.Absent
            ) match
                case kyo.Maybe.Present(b) if b.bodyStart > 0 && b.bodyEnd > b.bodyStart && b.sectionBytes.nonEmpty =>
                    val sliceLen = b.bodyEnd - b.bodyStart
                    bodyBytesBuffer.write(b.sectionBytes, b.bodyStart, sliceLen)
                    symBodyStarts(idx) = runningOffset
                    symBodyEnds(idx) = runningOffset + sliceLen
                    runningOffset += sliceLen
                case _ =>
                    symBodyStarts(idx) = 0
                    symBodyEnds(idx) = 0
        end for

        // Collect errors directly from the immutable case class field.
        val errors = cp.errors

        // --- Build section payloads ---

        // SYMBOLS section: fixed record per symbol (includes body offsets into BODY_BYTES)
        val symbolsBytes = serializeSymbols(symbolList, symNames, symFqns, symbolId, symBodyStarts, symBodyEnds)

        // FILES section: empty (metadata not critical for cache load)
        val filesBytes = Array.empty[Byte]

        // ERRORS section: length-prefixed error strings
        val errorsBytes = serializeErrors(errors)

        // Empty sections for types (not serialized; bodies re-decoded lazily from BODY_BYTES)
        val typesBytes      = Array.empty[Byte]
        val typesExtraBytes = Array.empty[Byte]
        val bodyBytes       = bodyBytesBuffer.toByteArray

        // PARENTS section: for each symbol, store the list of symbol IDs of Named parent types.
        // Non-Named parents (complex types) are encoded as -1 and skipped on read.
        // Named(symbolId) carries SymbolId.value as the serialized index.
        // F-A4-002 defensive filter: drop Named(SymbolId(-1)) sentinel entries before encoding.
        // After the F-A4-001 finalizeMerge fix Named(-1) should not appear in parentTypes, but this
        // filter provides defense-in-depth so a future regression does not silently corrupt warm loads
        // by encoding -1 in a slot that is indistinguishable from the non-Named sentinel.
        val parentsBytes = serializeSymbolRelLists(
            symbolList,
            symbolId,
            sym =>
                (sym match
                    case c: Tasty.Symbol.ClassLike =>
                        c.parentTypes.filter:
                            case Tasty.Type.Named(id) => id.value != -1
                            case _                    => true
                    case _ => Chunk.empty
                ).map:
                    case Tasty.Type.Named(id) => id.value
                    case _                    => -1
        )

        // MEMBERS section: for each symbol, store the symbol IDs of its declarations.
        // _declarationIds carries SymbolId.value directly.
        val membersBytes = serializeSymbolRelLists(
            symbolList,
            symbolId,
            sym =>
                import kyo.internal.tasty.symbol.SymbolId
                (sym match
                    case c: Tasty.Symbol.ClassLike => c.declarationIds
                    case p: Tasty.Symbol.Package   => p.memberIds
                    case _                         => Chunk.empty[SymbolId]
                ).map(id => id.value)
        )

        // TPARAMS_ section: for each symbol, store the symbol IDs of its type parameters.
        // _typeParamIds carries SymbolId.value directly.
        val tparamsBytes = serializeSymbolRelLists(
            symbolList,
            symbolId,
            sym =>
                import kyo.internal.tasty.symbol.SymbolId
                (sym match
                    case c: Tasty.Symbol.ClassLike   => c.typeParamIds
                    case m: Tasty.Symbol.Method      => m.typeParamIds
                    case ta: Tasty.Symbol.TypeAlias  => ta.typeParamIds
                    case ot: Tasty.Symbol.OpaqueType => ot.typeParamIds
                    case _                           => Chunk.empty[SymbolId]
                ).map(id => id.value)
        )

        // PERMITS2 section: permittedSubclassIds per Class/Trait symbol.
        // Uses the same serializeSymbolRelLists format as PARENTS/MEMBERS.
        val permits2Bytes = serializeSymbolRelLists(
            symbolList,
            symbolId,
            sym =>
                import kyo.internal.tasty.symbol.SymbolId
                (sym match
                    case c: Tasty.Symbol.Class =>
                        c.permittedSubclassIds match
                            case kyo.Maybe.Present(ids) => ids
                            case kyo.Maybe.Absent       => Chunk.empty[SymbolId]
                    case t: Tasty.Symbol.Trait =>
                        t.permittedSubclassIds match
                            case kyo.Maybe.Present(ids) => ids
                            case kyo.Maybe.Absent       => Chunk.empty[SymbolId]
                    case _ => Chunk.empty[SymbolId]
                ).map(id => id.value)
        )

        // Build a symbolById map: original SymbolId.value -> symbol, for annotation tycon lookup.
        val symbolById: scala.collection.mutable.HashMap[Int, Tasty.Symbol] =
            val m = scala.collection.mutable.HashMap.empty[Int, Tasty.Symbol]
            for sym <- symbolList do
                m(sym.id.value) = sym
            m
        end symbolById

        // ANNOTS_ section: annotation tycon FQN name-pool IDs per symbol.
        // Layout: [4-byte count] then entries [4-byte symIdx][4-byte annCount][annCount x 4-byte tyconFqnNameId].
        // Non-Named annotation tycons are omitted (skipped during collection).
        // Phase 2.13: pass unresolvedFqnByNegId so that annotations with negative SymbolIds (unresolved
        // external annotation types like scala.deprecated on JS/Native) are serialized by FQN string.
        val annotsBytes = serializeAnnotations(symbolList, symbolId, internName, symbolById, fqnBySymbol, cp.unresolvedFqnByNegId)

        // JAVAMETA section: accessFlags per symbol with javaMetadata present.
        // Layout: [4-byte count] then entries [4-byte symIdx][4-byte accessFlags].
        val javaMetaBytes = serializeJavaMetadata(symbolList, symbolId)

        // FQNIDX__ section: full fqnIndex serialization (all key->symIdx pairs, including
        // dual-index source-FQN aliases for Object companions and opaque types).
        // Warm-load reconstruction uses this section verbatim instead of rebuilding from
        // per-symbol fqnId, which only stored ONE FQN per symbol.
        // NOTE: serializeFqnIndex calls internName for every fqnIndex key. This MUST execute before
        // serializeNamePool so all FQN strings are present in the name pool that the reader uses to
        // decode FQNIDX__ name IDs. Moving serializeFqnIndex after serializeNamePool causes the reader
        // to skip entries whose name IDs exceed the stored pool length (the root cause of the cold/warm
        // fqnIndex.size gap fixed by this phase).
        val fqnIdxBytes = serializeFqnIndex(cp.fqnIndex, symbolList, symbolId, internName)

        // FQNMAP__ section: unresolvedFqnByNegId map (negId -> FQN string for external annotation types).
        // Per Phase 2.13: name pool must be populated (internName calls for FQN strings) BEFORE
        // serializeNamePool is called, so fqnMapBytes must call internName here, and namesBytes is
        // built after this call.
        val fqnMapBytes = serializeFqnMap(cp.unresolvedFqnByNegId, internName)

        // NAMES section: length-prefixed UTF-8 strings.
        // Built AFTER all internName calls (symNames, symFqns, annotsBytes, fqnIdxBytes, fqnMapBytes) so
        // the pool is complete before serialization. Earlier placement caused FQNIDX__ name IDs to
        // reference entries beyond the serialized pool length, silently dropping 47,256 fqnIndex entries
        // on warm load.
        val namesBytes = serializeNamePool(namePool.toSeq)

        val sections = Seq(
            (SnapshotFormat.sectionNAMES, namesBytes),
            (SnapshotFormat.sectionSYMBOLS, symbolsBytes),
            (SnapshotFormat.sectionTYPES, typesBytes),
            (SnapshotFormat.sectionTYPEXTRA, typesExtraBytes),
            (SnapshotFormat.sectionPARENTS, parentsBytes),
            (SnapshotFormat.sectionMEMBERS, membersBytes),
            (SnapshotFormat.sectionTPARAMS, tparamsBytes),
            (SnapshotFormat.sectionFILES, filesBytes),
            (SnapshotFormat.sectionBODYBYTES, bodyBytes),
            (SnapshotFormat.sectionERRORS, errorsBytes),
            (SnapshotFormat.sectionPERMITS2, permits2Bytes),
            (SnapshotFormat.sectionANNOTS, annotsBytes),
            (SnapshotFormat.sectionJAVAMETA, javaMetaBytes),
            (SnapshotFormat.sectionFQNIDX, fqnIdxBytes),
            (SnapshotFormat.sectionFQNMAP, fqnMapBytes)
        )

        assembleSections(sections, digest)
    end serialize

    /** Assemble sections into a complete KRFL file. */
    private def assembleSections(sections: Seq[(String, Array[Byte])], digest: Array[Byte]): Array[Byte] =
        val sectionCount     = sections.length
        val sectionIndexSize = 4 + sectionCount * SnapshotFormat.sectionIndexEntrySize
        val headerSize       = SnapshotFormat.headerSize + sectionIndexSize

        // Calculate section offsets
        var offset = headerSize.toLong
        val sectionMeta = sections.map: (name, bytes) =>
            val entry = (name, offset, bytes.length.toLong)
            offset += bytes.length
            entry

        val totalSize = offset.toInt
        val buf       = new Array[Byte](totalSize)

        // Write magic
        buf(0) = 'K'
        buf(1) = 'R'
        buf(2) = 'F'
        buf(3) = 'L'

        // Write version
        buf(4) = SnapshotFormat.majorVersion.toByte
        buf(5) = SnapshotFormat.minorVersion.toByte
        buf(6) = 0
        buf(7) = 0

        // Write flags (byte order = LE = 0)
        SnapshotFormat.writeInt64LE(buf, 8, 0L)

        // Write digest (8 bytes, or zeros if empty)
        if digest.length >= 8 then
            java.lang.System.arraycopy(digest, 0, buf, 16, 8)
        // else zeros already

        // Reserved 8 bytes at offset 24: zeros

        // Section count at offset 32
        SnapshotFormat.writeInt32LE(buf, 32, sectionCount)

        // Section index
        var idxPos = 36
        for (name, sectionOffset, sectionLen) <- sectionMeta do
            SnapshotFormat.writeSectionName(buf, idxPos, name)
            idxPos += 8
            SnapshotFormat.writeInt64LE(buf, idxPos, sectionOffset)
            idxPos += 8
            SnapshotFormat.writeInt64LE(buf, idxPos, sectionLen)
            idxPos += 8
        end for

        // Copy section payloads
        for ((_, sectionOffset, _), (_, bytes)) <- sectionMeta.zip(sections) do
            java.lang.System.arraycopy(bytes, 0, buf, sectionOffset.toInt, bytes.length)

        buf
    end assembleSections

    /** Serialize the name pool as: [4-byte count] followed by [4-byte len, UTF-8 bytes] per string. */
    private def serializeNamePool(names: Seq[String]): Array[Byte] =
        // Pre-size to 32 MB: profiling showed the name-pool output falls between 16 MB and 32 MB
        // for a 5 949-symbol classpath (unique simple names + FQNs with 4-byte length prefix per
        // entry). 32 MB fits the measured peak with no intermediate copies; default 32-byte
        // capacity would double ~20 times to reach that size.
        val baos = new ByteArrayOutputStream(32 * 1024 * 1024)
        val tmp  = new Array[Byte](4)
        SnapshotFormat.writeInt32LE(tmp, 0, names.length)
        baos.write(tmp)
        for name <- names do
            val bytes = SnapshotFormat.encodeString(name)
            SnapshotFormat.writeInt32LE(tmp, 0, bytes.length)
            baos.write(tmp)
            baos.write(bytes)
        end for
        baos.toByteArray
    end serializeNamePool

    /** Serialize symbols as: [4-byte count] followed by fixed-size records.
      *
      * Per-record layout (40 bytes):
      *   - kindOrdinal: 1 byte
      *   - flags: 8 bytes LE
      *   - nameId: 4 bytes LE (index into name pool)
      *   - fqnId: 4 bytes LE (index into name pool)
      *   - ownerId: 4 bytes LE (symbol index, -1 if no owner)
      *   - bodyStart: 4 bytes LE (offset into BODY_BYTES section; 0 if no body)
      *   - bodyEnd: 4 bytes LE (exclusive end offset into BODY_BYTES section; 0 if no body)
      *   - reserved: 11 bytes (zero)
      */
    private def serializeSymbols(
        symbols: Seq[Tasty.Symbol],
        names: Seq[Int],
        fqns: Seq[Int],
        symbolId: mutable.HashMap[Tasty.Symbol, Int],
        bodyStarts: Array[Int],
        bodyEnds: Array[Int]
    ): Array[Byte] =
        val count      = symbols.length
        val recordSize = 40
        val buf        = new Array[Byte](4 + count * recordSize)
        SnapshotFormat.writeInt32LE(buf, 0, count)
        var pos = 4
        var idx = 0
        for (sym, nameId, fqnId) <- symbols.zip(names).zip(fqns).map { case ((s, n), f) => (s, n, f) } do
            buf(pos) = sym.kind.ordinal.toByte
            SnapshotFormat.writeInt64LE(buf, pos + 1, sym.flags.bits)
            SnapshotFormat.writeInt32LE(buf, pos + 9, nameId)
            SnapshotFormat.writeInt32LE(buf, pos + 13, fqnId)
            // sym.ownerId.value is the index into the symbols array.
            // For self-referential root (ownerId == id), write -1 to indicate no owner.
            import kyo.internal.tasty.symbol.SymbolId
            val ownerIdx = sym.ownerId.value
            val ownerId  = if ownerIdx == sym.id.value then -1 else ownerIdx
            SnapshotFormat.writeInt32LE(buf, pos + 17, ownerId)
            SnapshotFormat.writeInt32LE(buf, pos + 21, bodyStarts(idx))
            SnapshotFormat.writeInt32LE(buf, pos + 25, bodyEnds(idx))
            // Remaining 11 bytes at pos+29 are zero (already initialized)
            pos += recordSize
            idx += 1
        end for
        buf
    end serializeSymbols

    /** Serialize errors as: [4-byte count] followed by [4-byte len, UTF-8 error message bytes] per error. */
    private def serializeErrors(errors: Chunk[TastyError]): Array[Byte] =
        // Pre-size to 4 KB: errors are rare (decode failures, file-not-found, etc.) and messages are short.
        val baos = new ByteArrayOutputStream(4 * 1024)
        val tmp  = new Array[Byte](4)
        SnapshotFormat.writeInt32LE(tmp, 0, errors.size)
        baos.write(tmp)
        for err <- errors do
            val msg   = err.toString
            val bytes = SnapshotFormat.encodeString(msg)
            SnapshotFormat.writeInt32LE(tmp, 0, bytes.length)
            baos.write(tmp)
            baos.write(bytes)
        end for
        baos.toByteArray
    end serializeErrors

    /** Serialize per-symbol integer-reference lists into a flat byte block.
      *
      * Layout: [4-byte count] followed by count entries, each: [4-byte symIdx][4-byte refCount][refCount x 4-byte refIds]. Entries with an
      * empty ref list are omitted from the output (the reader falls back to Chunk.empty for unset symbols). Refs with value -1 (non-
      * serializable, e.g. non-Named parent types) are filtered out before writing; if filtering leaves an empty list the entry is omitted.
      */
    private def serializeSymbolRelLists(
        symbols: Seq[Tasty.Symbol],
        symbolId: scala.collection.mutable.HashMap[Tasty.Symbol, Int],
        refsOf: Tasty.Symbol => Chunk[Int]
    ): Array[Byte] =
        // parentTypes / declarationIds / typeParamIds are direct fields on Symbol.
        val baos = new java.io.ByteArrayOutputStream(4 * 1024)
        val tmp  = new Array[Byte](4)
        // Collect valid entries: (symIdx, filteredRefs) where filteredRefs is non-empty.
        val entries = symbols.zipWithIndex.flatMap: (sym, idx) =>
            val refs = refsOf(sym).filter(_ >= 0)
            if refs.isEmpty then None else Some((idx, refs))
        SnapshotFormat.writeInt32LE(tmp, 0, entries.size)
        baos.write(tmp)
        for (symIdx, refs) <- entries do
            SnapshotFormat.writeInt32LE(tmp, 0, symIdx)
            baos.write(tmp)
            SnapshotFormat.writeInt32LE(tmp, 0, refs.size)
            baos.write(tmp)
            for r <- refs do
                SnapshotFormat.writeInt32LE(tmp, 0, r)
                baos.write(tmp)
            end for
        end for
        baos.toByteArray
    end serializeSymbolRelLists

    /** Serialize per-symbol annotation tycon FQN ids into a flat byte block.
      *
      * Layout: [4-byte count] then entries [4-byte symIdx][4-byte annCount][annCount x 4-byte tyconFqnNameId].
      * Named, TermRef, and Applied (unwrapped to base) annotation tycons are handled via tyconFqn.
      * Annotations with unrecognised tycon forms produce empty FQN and are omitted.
      * Symbols with no serializable annotations are omitted.
      * `internFqn` is the name-pool intern function from `serialize`; must be the same instance so IDs are
      * consistent with the NAMES section.
      */
    private def serializeAnnotations(
        symbols: Seq[Tasty.Symbol],
        symbolId: scala.collection.mutable.HashMap[Tasty.Symbol, Int],
        internFqn: String => Int,
        symbolById: scala.collection.mutable.HashMap[Int, Tasty.Symbol],
        fqnBySymbol: java.util.IdentityHashMap[Tasty.Symbol, String],
        unresolvedFqnByNegId: Map[Int, String]
    ): Array[Byte] =
        import Tasty.Name.asString
        val baos = new java.io.ByteArrayOutputStream(4 * 1024)
        val tmp  = new Array[Byte](4)

        // Collect valid entries: (symIdx, tyconIds) where tyconIds is non-empty.
        val entries = symbols.zipWithIndex.flatMap: (sym, idx) =>
            val annotations: Chunk[Tasty.Annotation] = sym match
                case c: Tasty.Symbol.ClassLike     => c.annotations
                case m: Tasty.Symbol.Method        => m.annotations
                case v: Tasty.Symbol.Val           => v.annotations
                case w: Tasty.Symbol.Var           => w.annotations
                case ta: Tasty.Symbol.TypeAlias    => ta.annotations
                case ot: Tasty.Symbol.OpaqueType   => ot.annotations
                case at: Tasty.Symbol.AbstractType => at.annotations
                case p: Tasty.Symbol.Parameter     => p.annotations
                case _                             => Chunk.empty[Tasty.Annotation]
            // Extract the tycon FQN name-pool ID for Named and TermRef types.
            // F-G-001: @deprecated and most Scala annotations arrive as TermRef tycons; Named is
            // less common but handled for completeness. Applied tycons are unwrapped to their base.
            // Phase 2.13: for annotations with negative SymbolIds (unresolved external annotation
            // types like scala.deprecated on JS/Native), fall back to unresolvedFqnByNegId.
            // Unknown tycon forms produce an empty FQN and are omitted.
            val tyconIds: Seq[Int] = annotations.toSeq.flatMap: ann =>
                val fqn = ann.annotationType match
                    case Tasty.Type.Named(sid) if sid.value < -1 =>
                        // Negative ID: annotation type not on classpath. Look up in unresolvedFqnByNegId.
                        unresolvedFqnByNegId.getOrElse(sid.value, "")
                    case other =>
                        tyconFqn(other, symbolById, fqnBySymbol)
                if fqn.nonEmpty then Some(internFqn(fqn)) else None
            if tyconIds.isEmpty then None else Some((idx, tyconIds))

        SnapshotFormat.writeInt32LE(tmp, 0, entries.size)
        baos.write(tmp)
        for (symIdx, tyconIds) <- entries do
            SnapshotFormat.writeInt32LE(tmp, 0, symIdx)
            baos.write(tmp)
            SnapshotFormat.writeInt32LE(tmp, 0, tyconIds.size)
            baos.write(tmp)
            for tid <- tyconIds do
                SnapshotFormat.writeInt32LE(tmp, 0, tid)
                baos.write(tmp)
            end for
        end for
        baos.toByteArray
    end serializeAnnotations

    /** Serialize per-symbol javaMetadata accessFlags.
      *
      * Layout: [4-byte count] then entries [4-byte symIdx][4-byte accessFlags].
      * Only symbols that have javaMetadata present are included.
      */
    private def serializeJavaMetadata(
        symbols: Seq[Tasty.Symbol],
        symbolId: scala.collection.mutable.HashMap[Tasty.Symbol, Int]
    ): Array[Byte] =
        val baos = new java.io.ByteArrayOutputStream(4 * 1024)
        val tmp  = new Array[Byte](4)

        val entries = symbols.zipWithIndex.flatMap: (sym, idx) =>
            val metaOpt: kyo.Maybe[Tasty.JavaMetadata] = sym match
                case c: Tasty.Symbol.ClassLike => c.javaMetadata
                case f: Tasty.Symbol.Field     => f.javaMetadata
                case m: Tasty.Symbol.Method    => m.javaMetadata
                case _                         => kyo.Maybe.Absent
            metaOpt match
                case kyo.Maybe.Present(meta) => Some((idx, meta.accessFlags))
                case kyo.Maybe.Absent        => None

        SnapshotFormat.writeInt32LE(tmp, 0, entries.size)
        baos.write(tmp)
        for (symIdx, accessFlags) <- entries do
            SnapshotFormat.writeInt32LE(tmp, 0, symIdx)
            baos.write(tmp)
            SnapshotFormat.writeInt32LE(tmp, 0, accessFlags)
            baos.write(tmp)
        end for
        baos.toByteArray
    end serializeJavaMetadata

    /** Serialize the full fqnIndex (all key->symIdx pairs) into a flat byte block.
      *
      * Layout: [4-byte count LE] then count entries each [4-byte namePoolId LE][4-byte symIdx LE].
      * Each entry stores a name-pool index for the FQN string and a symbol index (position in symbolList).
      * All FQN keys are interned into the shared name pool via `internName`; FQN keys not present in
      * `symbolId` are skipped (should never happen for a well-formed Classpath).
      *
      * This section stores the FULL fqnIndex including dual-index source-FQN aliases added by the
      * ClasspathOrchestrator (e.g. both "scala.Predef$" and "scala.Predef" for an Object companion,
      * both the binary and source FQN for opaque types). The reader reconstructs fqnIndex verbatim
      * from this section, bypassing the single-FQN-per-symbol limitation of the SYMBOLS section.
      */
    private def serializeFqnIndex(
        fqnIndex: Map[String, kyo.internal.tasty.symbol.SymbolId],
        symbolList: Seq[Tasty.Symbol],
        symbolId: scala.collection.mutable.HashMap[Tasty.Symbol, Int],
        internName: String => Int
    ): Array[Byte] =
        val baos = new java.io.ByteArrayOutputStream(64 * 1024)
        val tmp  = new Array[Byte](4)
        // Build SymbolId.value -> snapshot index mapping (snapshot index = position in symbolList).
        val symIdToIdx = new scala.collection.mutable.HashMap[Int, Int]()
        for (sym, idx) <- symbolList.zipWithIndex do
            symIdToIdx(sym.id.value) = idx
        end for
        // Collect valid entries: (namePoolId, snapshotIdx).
        // F-A4-001 secondary fix: when symIdToIdx.get(id.value) misses (i.e. id.value == -1 due to a
        // ghost entry from finalizeMerge that the Path-1 fix may not have reached), fall back to a
        // FQN-string lookup. canonicalSourceFqn maps the binary-alias form back to source form; if the
        // canonical form is present in fqnIndex with a valid SymbolId, use that snapshot index.
        // This ensures EVERY entry in fqnIndex is serialized, eliminating the cold/warm size gap.
        // F-A4-005 determinism: sort by fqn before building entries so FQNIDX__ byte layout is stable
        // regardless of Map iteration order (which varies with runtime state despite String hashCode
        // being deterministic, since Map bucket layout also depends on load factor and capacity).
        val entries = fqnIndex.toSeq.sortBy(_._1).flatMap: (fqn, id) =>
            symIdToIdx.get(id.value) match
                case Some(idx) => Some((internName(fqn), idx))
                case None =>
                    val canonFqn = FqnNormalizer.canonicalSourceFqn(fqn)
                    if canonFqn != fqn then
                        fqnIndex.get(canonFqn) match
                            case Some(canonId) =>
                                symIdToIdx.get(canonId.value) match
                                    case Some(idx) => Some((internName(fqn), idx))
                                    case None      => None
                            case None => None
                    else None
                    end if
        SnapshotFormat.writeInt32LE(tmp, 0, entries.size)
        baos.write(tmp)
        for (nameId, idx) <- entries do
            SnapshotFormat.writeInt32LE(tmp, 0, nameId)
            baos.write(tmp)
            SnapshotFormat.writeInt32LE(tmp, 0, idx)
            baos.write(tmp)
        end for
        baos.toByteArray
    end serializeFqnIndex

    /** Serialize the unresolvedFqnByNegId map into a flat byte block.
      *
      * Layout: [4-byte count LE] then count entries each [4-byte negId LE][4-byte namePoolId LE].
      * The FQN strings are interned into the shared name pool via `internName` (same instance as other sections).
      * Entries are sorted by negId for determinism (F-A4-005 pattern).
      */
    private def serializeFqnMap(
        unresolvedFqnByNegId: Map[Int, String],
        internName: String => Int
    ): Array[Byte] =
        val baos = new java.io.ByteArrayOutputStream(4 * 1024)
        val tmp  = new Array[Byte](4)
        // Sort by negId for deterministic output.
        val entries = unresolvedFqnByNegId.toSeq.sortBy(_._1).filter { case (_, fqn) => fqn.nonEmpty }
        SnapshotFormat.writeInt32LE(tmp, 0, entries.size)
        baos.write(tmp)
        for (negId, fqn) <- entries do
            SnapshotFormat.writeInt32LE(tmp, 0, negId)
            baos.write(tmp)
            SnapshotFormat.writeInt32LE(tmp, 0, internName(fqn))
            baos.write(tmp)
        end for
        baos.toByteArray
    end serializeFqnMap

    /** Convert a Name (opaque Interner.Entry) to a String. */
    private def nameToStr(n: Tasty.Name): String =
        import Tasty.Name.asString
        n.asString
    end nameToStr

    /** Extract a dotted FQN string from an annotation tycon type without needing a Classpath.
      *
      * Handles Type.Named (looks up FQN via fqnBySymbol), Type.TermRef (recursively builds prefix.name),
      * and Type.Applied (delegates to the unapplied base). Returns empty string for unrecognised types.
      * Called from serializeAnnotations to cover both Named and TermRef tycon forms.
      */
    private def tyconFqn(
        t: Tasty.Type,
        symbolById: scala.collection.mutable.HashMap[Int, Tasty.Symbol],
        fqnBySymbol: java.util.IdentityHashMap[Tasty.Symbol, String]
    ): String =
        import Tasty.Name.asString
        t match
            case Tasty.Type.Named(annSymId) =>
                val annSym = symbolById.getOrElse(annSymId.value, null)
                if annSym != null then
                    val fqn = fqnBySymbol.get(annSym)
                    if fqn != null && fqn.nonEmpty then fqn
                    else nameToStr(annSym.name)
                else ""
                end if
            case Tasty.Type.TermRef(qual, name) =>
                val q = tyconFqn(qual, symbolById, fqnBySymbol)
                if q.nonEmpty then q + "." + name.asString else name.asString
            case Tasty.Type.TypeRef(qual, name) =>
                // F-A-009: TYPEREF now emits TypeRef; serialize the same way as TermRef.
                val q = tyconFqn(qual, symbolById, fqnBySymbol)
                if q.nonEmpty then q + "." + name.asString else name.asString
            case Tasty.Type.Applied(base, _) =>
                tyconFqn(base, symbolById, fqnBySymbol)
            case _ => ""
        end match
    end tyconFqn

end SnapshotWriter
