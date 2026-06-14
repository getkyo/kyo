package kyo.internal.tasty.snapshot

import kyo.*
import kyo.Tasty.SymbolId
import kyo.internal.tasty.symbol.FullNameNormalizer
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
  * Stale tmp files (from crashed writers) are removed by `Tasty.evictOlderThan(cacheDir, maxAge)`.
  *
  * I/O errors (unwritable cache dir, disk full) produce `TastyError.SnapshotIoError`.
  */
object SnapshotWriter:

    /** Serialize a Classpath to KRFL bytes without writing to disk.
      *
      * Exposed as a package-private helper so tests can perform in-memory snapshot round-trips on all platforms (JVM, JS, Native) without
      * requiring a real filesystem. The returned array is identical to what `write` would persist on disk.
      *
      * @param classpath
      *   the fully-loaded classpath to snapshot
      * @param digest
      *   8-byte xxh64-custom digest of the inputs (from DigestComputer)
      */
    private[kyo] def serializeToBytes(classpath: Tasty.Classpath, digest: Array[Byte]): Array[Byte] =
        serialize(classpath, digest)

    /** Write a snapshot of `classpath` to `cacheDir` using the given input `digest`.
      *
      * The write is atomic: bytes are first written to a uniquely-named temp file under `cacheDir`, then atomically renamed to
      * `$cacheDir/$hexDigest.krfl`. Two concurrent writers for the same digest produce identical content; the last rename wins and no
      * partial state is visible to readers.
      *
      * I/O errors are wrapped as `TastyError.SnapshotIoError`.
      *
      * @param classpath
      *   the fully-loaded classpath to snapshot
      * @param cacheDir
      *   directory where the snapshot file is written
      * @param digest
      *   8-byte xxh64-custom digest of the inputs (from DigestComputer)
      */
    def write(
        classpath: Tasty.Classpath,
        cacheDir: String,
        digest: Array[Byte]
    )(using Frame): Unit < (Sync & Abort[TastyError]) =
        val hexDigest = DigestComputer.toHexString(digest)
        Clock.nowMonotonic.map { d =>
            Random.nextLong.map { r =>
                val unique    = d.toNanos ^ r
                val tmpName   = s"$cacheDir/$hexDigest-$unique.krfl"
                val finalName = s"$cacheDir/$hexDigest.krfl"
                Sync.defer(Span.fromUnsafe(serialize(classpath, digest))).map { span =>
                    Abort.recover[FileFsException](e => Abort.fail(TastyError.SnapshotIoError(s"mkDir $cacheDir: ${e.getMessage}")))(
                        Path(cacheDir).mkDir
                    ).map { _ =>
                        Abort.recover[FileWriteException](e => Abort.fail(TastyError.SnapshotIoError(s"write $tmpName: ${e.getMessage}")))(
                            Path(tmpName).writeBytes(span)
                        ).map { _ =>
                            Abort.recover[FileFsException](e => Abort.fail(TastyError.SnapshotIoError(s"move $tmpName: ${e.getMessage}")))(
                                Path(tmpName).move(Path(finalName), atomicMove = true)
                            )
                        }
                    }
                }
            }
        }
    end write

    /** Serialize a Classpath to KRFL bytes, embedding the given input digest in the file header. */
    private def serialize(classpath: Tasty.Classpath, digest: Array[Byte]): Array[Byte] =
        // Direct field reads from the immutable case class; no AllowUnsafe needed.
        val allSymbols = classpath.symbols
        // Build a reverse map SymbolId.value->fullName from the classpath's fullNameIndex so snapshot
        // fully-qualified names are the real registered names (e.g. "test.Foo"), not just simple names ("Foo").
        // Symbols without an fullNameIndex entry get an empty fully-qualified name (they will not be findClass-lookup-able).
        // Store only the canonical source fully-qualified name per symbol. The fullNameIndex contains dual-index
        // entries (e.g. both "scala.Predef$" and "scala.Predef"), so we apply canonicalSourceFullName before
        // storing. Multiple binary fully-qualified names that canonicalize to the same source form produce the same put,
        // which is deterministic and correct (the canonical source fully-qualified name is the user-facing name).
        // Sort fullNameIndex before building fullNameBySymbol so that when a symbol
        // has multiple fully-qualified name aliases (e.g. "scala.Predef$" and "scala.Predef"), the LAST overwrite
        // in alphabetical order wins deterministically across JVM invocations. Without sorting,
        // HashMap.foreach iteration order may differ between invocations, causing different canonical
        // fully-qualified names to be stored in fullNameBySymbol for symbols with multiple aliases.
        // Keyed by SymbolId.value (Int) instead of Symbol object identity so that warm-loaded
        // classpaths (which create fresh Symbol instances with the same id.value) produce the same
        // fully-qualified name lookup result as a cold load. IdentityHashMap caused lookup misses on
        // warm-load symbols because their object identities differ from the cold-load symbols used to
        // populate the map.
        val fullNameBySymbol: mutable.HashMap[Int, String] =
            val rev = mutable.HashMap.empty[Int, String]
            classpath.indices.byFullName.toMap.toSeq.sortBy(_._1).foreach { case (fullName, id) =>
                val canonical = FullNameNormalizer.canonicalSourceFullName(fullName)
                rev(id.value) = canonical
            }
            rev
        end fullNameBySymbol

        val symbolList = allSymbols.toSeq

        // Build name pool (all unique fully-qualified name strings)
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

        // Intern symbol names and fully-qualified names.
        val symNames = Chunk.from(symbolList.map { symbol =>
            internName(nameToStr(symbol.name))
        })

        val symFullNames = Chunk.from(symbolList.map { symbol =>
            fullNameBySymbol.get(symbol.id.value) match
                case Some(fullName) if fullName.nonEmpty => internName(fullName)
                // Carve-out: stdlib Option from Map.get; covers absent and empty-fullName cases
                case _ => internName(nameToStr(symbol.name))
        })

        // Body bytes are stored in DecodeContext.bodyStore (keyed by SymbolId) and reconstructed from
        // pickles/TASTy files on the next withClasspath(roots) call. The BODY_BYTES section is
        // written as empty so the snapshot format is backward-compatible (SnapshotReader still reads
        // the section header and discards the empty payload).
        val symBodyStarts = new Array[Int](symbolList.size)
        val symBodyEnds   = new Array[Int](symbolList.size)
        // All start/end offsets stay at 0 (no body slices stored in the snapshot).

        // Collect errors directly from the immutable case class field.
        val errors = classpath.errors

        // --- Build section payloads ---

        // SYMBOLS section: fixed record per symbol (includes body offsets into BODY_BYTES)
        val symbolsBytes = serializeSymbols(symbolList, symNames, symFullNames, symBodyStarts, symBodyEnds)

        // FILES section: empty (metadata not critical for cache load)
        val filesBytes = Array.empty[Byte]

        // ERRORS section: length-prefixed error strings
        val errorsBytes = serializeErrors(errors)

        // Empty sections for types (not serialized; bodies re-decoded lazily on next withClasspath(roots) call)
        val typesBytes      = Array.empty[Byte]
        val typesExtraBytes = Array.empty[Byte]
        val bodyBytes       = Array.empty[Byte]

        // PARENTS section: for each symbol, store the list of symbol IDs of Named parent types.
        // Non-Named parents (complex types) are encoded as -1 and skipped on read.
        // Named(symbolId) carries SymbolId.value as the serialized index.
        // Defensive filter: drop Named(SymbolId(-1)) sentinel entries before encoding so the -1
        // slot is unambiguous with the non-Named sentinel.
        val parentsBytes = serializeSymbolRelLists(
            symbolList,
            symbol =>
                (symbol match
                    case c: Tasty.Symbol.ClassLike =>
                        c.parentTypes.filter {
                            case Tasty.Type.Named(id) => id.value != -1
                            case _: Tasty.Type.TermRef | _: Tasty.Type.Applied | _: Tasty.Type.TypeLambda |
                                _: Tasty.Type.Function | _: Tasty.Type.ContextFunction | _: Tasty.Type.Tuple |
                                _: Tasty.Type.ByName | _: Tasty.Type.Repeated | _: Tasty.Type.Array |
                                _: Tasty.Type.Refinement | _: Tasty.Type.Rec | _: Tasty.Type.RecThis |
                                _: Tasty.Type.AndType | _: Tasty.Type.OrType | _: Tasty.Type.Annotated |
                                _: Tasty.Type.ConstantType | _: Tasty.Type.ThisType | _: Tasty.Type.SuperType |
                                _: Tasty.Type.ParamRef | _: Tasty.Type.Wildcard | _: Tasty.Type.Skolem |
                                _: Tasty.Type.MatchType | _: Tasty.Type.FlexibleType | _: Tasty.Type.Bind | _: Tasty.Type.MatchCase |
                                _: Tasty.Type.TypeRef | _: Tasty.Type.Bounds | Tasty.Type.Nothing | Tasty.Type.Any =>
                                true
                        }
                    case _: Tasty.Symbol.Method | _: Tasty.Symbol.Val | _: Tasty.Symbol.Var |
                        _: Tasty.Symbol.Field | _: Tasty.Symbol.TypeAlias | _: Tasty.Symbol.OpaqueType |
                        _: Tasty.Symbol.AbstractType | _: Tasty.Symbol.TypeParam | _: Tasty.Symbol.Parameter |
                        _: Tasty.Symbol.Package =>
                        Chunk.empty
                ).map {
                    case Tasty.Type.Named(id) => id.value
                    case _: Tasty.Type.TermRef | _: Tasty.Type.Applied | _: Tasty.Type.TypeLambda |
                        _: Tasty.Type.Function | _: Tasty.Type.ContextFunction | _: Tasty.Type.Tuple |
                        _: Tasty.Type.ByName | _: Tasty.Type.Repeated | _: Tasty.Type.Array |
                        _: Tasty.Type.Refinement | _: Tasty.Type.Rec | _: Tasty.Type.RecThis |
                        _: Tasty.Type.AndType | _: Tasty.Type.OrType | _: Tasty.Type.Annotated |
                        _: Tasty.Type.ConstantType | _: Tasty.Type.ThisType | _: Tasty.Type.SuperType |
                        _: Tasty.Type.ParamRef | _: Tasty.Type.Wildcard | _: Tasty.Type.Skolem |
                        _: Tasty.Type.MatchType | _: Tasty.Type.FlexibleType | _: Tasty.Type.Bind | _: Tasty.Type.MatchCase |
                        _: Tasty.Type.TypeRef | _: Tasty.Type.Bounds | Tasty.Type.Nothing | Tasty.Type.Any =>
                        -1
                }
        )

        // MEMBERS section: for each symbol, store the symbol IDs of its declarations.
        // _declarationIds carries SymbolId.value directly.
        val membersBytes = serializeSymbolRelLists(
            symbolList,
            symbol =>
                import kyo.Tasty.SymbolId
                (symbol match
                    case c: Tasty.Symbol.ClassLike => c.declarationIds
                    case p: Tasty.Symbol.Package   => p.memberIds
                    case _: Tasty.Symbol.Method | _: Tasty.Symbol.Val | _: Tasty.Symbol.Var |
                        _: Tasty.Symbol.Field | _: Tasty.Symbol.TypeAlias | _: Tasty.Symbol.OpaqueType |
                        _: Tasty.Symbol.AbstractType | _: Tasty.Symbol.TypeParam | _: Tasty.Symbol.Parameter =>
                        Chunk.empty[SymbolId]
                ).map(id => id.value)
        )

        // TPARAMS_ section: for each symbol, store the symbol IDs of its type parameters.
        // _typeParamIds carries SymbolId.value directly.
        val tparamsBytes = serializeSymbolRelLists(
            symbolList,
            symbol =>
                import kyo.Tasty.SymbolId
                (symbol match
                    case c: Tasty.Symbol.ClassLike   => c.typeParamIds
                    case m: Tasty.Symbol.Method      => m.typeParamIds
                    case ta: Tasty.Symbol.TypeAlias  => ta.typeParamIds
                    case ot: Tasty.Symbol.OpaqueType => ot.typeParamIds
                    case _: Tasty.Symbol.Val | _: Tasty.Symbol.Var | _: Tasty.Symbol.Field |
                        _: Tasty.Symbol.AbstractType | _: Tasty.Symbol.TypeParam | _: Tasty.Symbol.Parameter |
                        _: Tasty.Symbol.Package =>
                        Chunk.empty[SymbolId]
                ).map(id => id.value)
        )

        // PERMITS2 section: permittedSubclassIds per Class/Trait symbol.
        // Uses the same serializeSymbolRelLists format as PARENTS/MEMBERS.
        val permits2Bytes = serializeSymbolRelLists(
            symbolList,
            symbol =>
                import kyo.Tasty.SymbolId
                (symbol match
                    case c: Tasty.Symbol.Class =>
                        c.permittedSubclassIds match
                            case kyo.Maybe.Present(ids) => ids
                            case kyo.Maybe.Absent       => Chunk.empty[SymbolId]
                    case t: Tasty.Symbol.Trait =>
                        t.permittedSubclassIds match
                            case kyo.Maybe.Present(ids) => ids
                            case kyo.Maybe.Absent       => Chunk.empty[SymbolId]
                    case _: Tasty.Symbol.EnumCase | _: Tasty.Symbol.Object | _: Tasty.Symbol.Method |
                        _: Tasty.Symbol.Val | _: Tasty.Symbol.Var | _: Tasty.Symbol.Field |
                        _: Tasty.Symbol.TypeAlias | _: Tasty.Symbol.OpaqueType | _: Tasty.Symbol.AbstractType |
                        _: Tasty.Symbol.TypeParam | _: Tasty.Symbol.Parameter | _: Tasty.Symbol.Package =>
                        Chunk.empty[SymbolId]
                ).map(id => id.value)
        )

        // Build a symbolById map: original SymbolId.value -> symbol, for annotation tycon lookup.
        val symbolById: scala.collection.mutable.HashMap[Int, Tasty.Symbol] =
            val m = scala.collection.mutable.HashMap.empty[Int, Tasty.Symbol]
            for symbol <- symbolList do
                m(symbol.id.value) = symbol
            m
        end symbolById

        // ANNOTS_ section: annotation tycon fully-qualified name pool IDs per symbol.
        // Layout: [4-byte count] then entries [4-byte symIdx][4-byte annCount][annCount x 4-byte tyconFullNameId].
        // Non-Named annotation tycons are omitted (skipped during collection).
        // Pass unresolvedFullNameByNegId so that annotations with negative SymbolIds (unresolved
        // external annotation types like scala.deprecated on JS/Native) are serialized by fully-qualified name string.
        val annotsBytes =
            serializeAnnotations(symbolList, internName, symbolById, fullNameBySymbol, classpath.indices.unresolvedFullNameByNegId)

        // JAVAMETA section: accessFlags per symbol with javaMetadata present.
        // Layout: [4-byte count] then entries [4-byte symIdx][4-byte accessFlags].
        val javaMetaBytes = serializeJavaMetadata(symbolList)

        // FQNIDX__ section: full fullNameIndex serialization (all key->symIdx pairs, including
        // dual-index source fully-qualified name aliases for Object companions and opaque types).
        // Warm-load reconstruction uses this section verbatim instead of rebuilding from
        // per-symbol fullNameId, which only stored ONE fully-qualified name per symbol.
        // NOTE: serializeFullNameIndex calls internName for every fullNameIndex key. This MUST execute before
        // serializeNamePool so all fully-qualified name strings are present in the name pool that the reader uses to
        // decode FQNIDX__ name IDs. Moving serializeFullNameIndex after serializeNamePool causes the reader
        // to skip entries whose name IDs exceed the stored pool length.
        val fullNameIdxBytes = serializeFullNameIndex(classpath.indices.byFullName, symbolList, internName)

        // FQNMAP__ section: unresolvedFullNameByNegId map (negId -> fully-qualified name string for external annotation types).
        // Name pool must be populated (internName calls for fully-qualified name strings) BEFORE
        // serializeNamePool is called, so fullNameMapBytes must call internName here, and namesBytes is
        // built after this call.
        val fullNameMapBytes = serializeFullNameMap(classpath.indices.unresolvedFullNameByNegId, internName)

        // SUBCIDX_ section: subclassIndex map (parent SymbolId -> Chunk of child SymbolIds).
        // Serialize so warm loads can answer directSubclassesOf/subclassesOf/implementationsOf.
        // Build symIdToIdx: SymbolId.value -> snapshot position (needed to convert SymbolId to index).
        val symIdToIdxForIdx = new scala.collection.mutable.HashMap[Int, Int]()
        for (symbol, idx) <- symbolList.zipWithIndex do
            symIdToIdxForIdx(symbol.id.value) = idx
        end for
        val subcIdxBytes = serializeSubclassIndex(classpath.indices.subclassIndex, symIdToIdxForIdx)

        // COMPIDX_ section: companionIndex map (SymbolId -> companion SymbolId).
        // Serialize so warm loads can answer classpath.companion(symbol) without fullNameIndex rescan.
        val compIdxBytes = serializeCompanionIndex(classpath.indices.companionIndex, symIdToIdxForIdx)

        // PLISTS__ section: per-method paramListIds (sparse two-level Int32-LE encoding).
        // Written unconditionally even when no method has a non-empty paramListIds (count=0).
        val paramListsBytes = serializeParamLists(symbolList)

        // NAMES section: length-prefixed UTF-8 strings.
        // Built AFTER all internName calls (symNames, symFullNames, annotsBytes, fullNameIdxBytes, fullNameMapBytes) so
        // the pool is complete before serialization. Earlier placement caused FQNIDX__ name IDs to
        // reference entries beyond the serialized pool length, silently dropping 47,256 fullNameIndex entries
        // on warm load.
        val namesBytes = serializeNamePool(Chunk.from(namePool))

        val sections = Seq(
            (SnapshotFormat.sectionNAMES, namesBytes),
            (SnapshotFormat.sectionSYMBOLS, symbolsBytes),
            (SnapshotFormat.sectionTYPES, typesBytes),
            (SnapshotFormat.sectionTYPEXTRA, typesExtraBytes),
            (SnapshotFormat.sectionPARENTS, parentsBytes),
            (SnapshotFormat.sectionMEMBERS, membersBytes),
            (SnapshotFormat.sectionTPARAMS, tparamsBytes),
            // PLISTS__ joins the per-symbol relational sections (PARENTS / MEMBERS / TPARAMS_) and
            // sits before FILES per Q-007 placement rule. Sparse two-level Int32-LE layout.
            (SnapshotFormat.sectionPLISTS, paramListsBytes),
            (SnapshotFormat.sectionFILES, filesBytes),
            (SnapshotFormat.sectionBODYBYTES, bodyBytes),
            (SnapshotFormat.sectionERRORS, errorsBytes),
            (SnapshotFormat.sectionPERMITS2, permits2Bytes),
            (SnapshotFormat.sectionANNOTS, annotsBytes),
            (SnapshotFormat.sectionJAVAMETA, javaMetaBytes),
            (SnapshotFormat.sectionFQNIDX, fullNameIdxBytes),
            (SnapshotFormat.sectionFQNMAP, fullNameMapBytes),
            (SnapshotFormat.sectionSUBCIDX, subcIdxBytes),
            (SnapshotFormat.sectionCOMPIDX, compIdxBytes)
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
        val sectionMeta = sections.map { (name, bytes) =>
            val entry = (name, offset, bytes.length.toLong)
            offset += bytes.length
            entry
        }

        val totalSize = offset.toInt
        val buffer    = new Array[Byte](totalSize)

        // Write magic
        buffer(0) = 'K'
        buffer(1) = 'R'
        buffer(2) = 'F'
        buffer(3) = 'L'

        // Write version
        buffer(4) = SnapshotFormat.majorVersion.toByte
        buffer(5) = SnapshotFormat.minorVersion.toByte
        buffer(6) = 0
        buffer(7) = 0

        // Write flags (byte order = LE = 0)
        SnapshotFormat.writeInt64LE(buffer, 8, 0L)

        // Write digest (8 bytes, or zeros if empty)
        if digest.length >= 8 then
            java.lang.System.arraycopy(digest, 0, buffer, 16, 8)
        // else zeros already

        // Reserved 8 bytes at offset 24: zeros

        // Section count at offset 32
        SnapshotFormat.writeInt32LE(buffer, 32, sectionCount)

        // Section index
        var idxPos = 36
        for (name, sectionOffset, sectionLen) <- sectionMeta do
            SnapshotFormat.writeSectionName(buffer, idxPos, name)
            idxPos += 8
            SnapshotFormat.writeInt64LE(buffer, idxPos, sectionOffset)
            idxPos += 8
            SnapshotFormat.writeInt64LE(buffer, idxPos, sectionLen)
            idxPos += 8
        end for

        // Copy section payloads
        for ((_, sectionOffset, _), (_, bytes)) <- sectionMeta.zip(sections) do
            java.lang.System.arraycopy(bytes, 0, buffer, sectionOffset.toInt, bytes.length)

        buffer
    end assembleSections

    /** Serialize the name pool as: [4-byte count] followed by [4-byte len, UTF-8 bytes] per string. */
    private def serializeNamePool(names: Chunk[String]): Array[Byte] =
        val builder = ChunkBuilder.init[Byte]
        val tmp     = new Array[Byte](4)
        SnapshotFormat.writeInt32LE(tmp, 0, names.length)
        builder.addAll(tmp)
        for name <- names do
            val bytes = SnapshotFormat.encodeString(name)
            SnapshotFormat.writeInt32LE(tmp, 0, bytes.length)
            builder.addAll(tmp)
            builder.addAll(bytes)
        end for
        builder.result().toArray
    end serializeNamePool

    /** Serialize symbols as: [4-byte count] followed by fixed-size records.
      *
      * Per-record layout (40 bytes):
      *   - kindOrdinal: 1 byte
      *   - flags: 8 bytes LE
      *   - nameId: 4 bytes LE (index into name pool)
      *   - fullNameId: 4 bytes LE (index into name pool)
      *   - ownerId: 4 bytes LE (symbol index, -1 if no owner)
      *   - bodyStart: 4 bytes LE (offset into BODY_BYTES section; 0 if no body)
      *   - bodyEnd: 4 bytes LE (exclusive end offset into BODY_BYTES section; 0 if no body)
      *   - reserved: 11 bytes (zero)
      */
    private def serializeSymbols(
        symbols: Seq[Tasty.Symbol],
        names: Chunk[Int],
        fullNames: Chunk[Int],
        bodyStarts: Array[Int],
        bodyEnds: Array[Int]
    ): Array[Byte] =
        val count      = symbols.length
        val recordSize = 40
        val buffer     = new Array[Byte](4 + count * recordSize)
        SnapshotFormat.writeInt32LE(buffer, 0, count)
        var pos = 4
        var idx = 0
        for (symbol, nameId, fullNameId) <- symbols.zip(names).zip(fullNames).map { case ((s, n), f) => (s, n, f) } do
            buffer(pos) = symbol.kind.ordinal.toByte
            SnapshotFormat.writeInt64LE(buffer, pos + 1, symbol.flags.bits)
            SnapshotFormat.writeInt32LE(buffer, pos + 9, nameId)
            SnapshotFormat.writeInt32LE(buffer, pos + 13, fullNameId)
            // symbol.ownerId.value is the index into the symbols array.
            // For self-referential root (ownerId == id), write -1 to indicate no owner.
            import kyo.Tasty.SymbolId
            val ownerIdx = symbol.ownerId.value
            val ownerId  = if ownerIdx == symbol.id.value then -1 else ownerIdx
            SnapshotFormat.writeInt32LE(buffer, pos + 17, ownerId)
            SnapshotFormat.writeInt32LE(buffer, pos + 21, bodyStarts(idx))
            SnapshotFormat.writeInt32LE(buffer, pos + 25, bodyEnds(idx))
            // Remaining 11 bytes at pos+29 are zero (already initialized)
            pos += recordSize
            idx += 1
        end for
        buffer
    end serializeSymbols

    /** Serialize errors as: [4-byte count LE] followed by count typed entries.
      *
      * Each entry (minor=10 string-tag format):
      *   [varint-length-prefixed UTF-8 tag == TastyError.productPrefix] [variant-specific fields]
      *
      * The tag is encoded as: [LEB128 varint: tag byte count] [UTF-8 tag bytes]. This encoding is stable against future
      * enum variant additions because the tag is the case name string, not an ordinal.
      *
      * String fields: [4-byte len LE][UTF-8 bytes].
      * Long fields:   [8-byte Int64 LE].
      * Version:       [4-byte major LE][4-byte minor LE].
      * UUID:          [8-byte MSB LE][8-byte LSB LE].
      * Int fields:    [4-byte Int32 LE].
      */
    private def serializeErrors(errors: Chunk[TastyError]): Array[Byte] =
        val builder = ChunkBuilder.init[Byte]
        val tmp4    = new Array[Byte](4)
        val tmp8    = new Array[Byte](8)
        SnapshotFormat.writeInt32LE(tmp4, 0, errors.size)
        builder.addAll(tmp4)

        def writeStr(s: String): Unit =
            val bytes = SnapshotFormat.encodeString(s)
            SnapshotFormat.writeInt32LE(tmp4, 0, bytes.length)
            builder.addAll(tmp4)
            builder.addAll(bytes)
        end writeStr

        def writeLong(v: Long): Unit =
            SnapshotFormat.writeInt64LE(tmp8, 0, v)
            builder.addAll(tmp8)

        def writeInt(v: Int): Unit =
            SnapshotFormat.writeInt32LE(tmp4, 0, v)
            builder.addAll(tmp4)

        def writeVersion(v: Tasty.Version): Unit =
            writeInt(v.major)
            writeInt(v.minor)

        def writeUUID(u: Tasty.Uuid): Unit =
            writeLong(Tasty.Uuid.msb(u))
            writeLong(Tasty.Uuid.lsb(u))

        // Standard LEB128 (little-endian base-128) varint encoder. Each 7-bit group is written
        // with the continuation bit (0x80) set in all but the last byte. Values 0-127 fit in one
        // byte; values 128-16383 fit in two bytes (e.g. 200 encodes as 0xC8 0x01).
        def writeVarint(value: Int): Unit =
            var v = value
            while
                val b = v & 0x7f
                v = v >>> 7
                if v != 0 then
                    builder.addOne((b | 0x80).toByte)
                    true
                else
                    builder.addOne(b.toByte)
                    false
                end if
            do ()
            end while
        end writeVarint

        // Compact tag-prefixed recursive encoder for Tasty.Type fields.
        // Tag byte (single byte 0-255):
        //   0  = Named(symbolId)   : 4-byte Int32LE symbolId.value
        //   1  = Any               : no payload
        //   2  = Nothing           : no payload
        //   3  = Applied(base, args): recursive base, varint arg count, recursive args
        //   4  = TermRef(prefix, name): recursive prefix, 4-byte name len + UTF-8 bytes
        //   5  = TypeRef(qual, name): recursive qual, 4-byte name len + UTF-8 bytes
        //   6  = Tuple(elements)   : varint count, recursive elements
        //   7  = Function(params, result): varint count, recursive params, recursive result
        //   8  = ContextFunction(params, result): varint count, recursive params, recursive result
        //   9  = ByName(underlying): recursive underlying
        //   10 = Repeated(elem)    : recursive elem
        //   11 = Array(elem)       : recursive elem
        //   255 = Opaque           : 4-byte string len + UTF-8 show string (catch-all; no round-trip semantic for complex types)
        def writeType(t: Tasty.Type): Unit = t match
            case Tasty.Type.Named(symId) =>
                builder.addOne(0.toByte)
                writeInt(symId.value)
            case Tasty.Type.Any =>
                builder.addOne(1.toByte)
            case Tasty.Type.Nothing =>
                builder.addOne(2.toByte)
            case Tasty.Type.Applied(base, args) =>
                builder.addOne(3.toByte)
                writeType(base)
                writeVarint(args.size)
                args.foreach(writeType)
            case Tasty.Type.TermRef(prefix, name) =>
                builder.addOne(4.toByte)
                writeType(prefix)
                writeStr(name.asString)
            case Tasty.Type.TypeRef(qual, name) =>
                builder.addOne(5.toByte)
                writeType(qual)
                writeStr(name.asString)
            case Tasty.Type.Tuple(elements) =>
                builder.addOne(6.toByte)
                writeVarint(elements.size)
                elements.foreach(writeType)
            case Tasty.Type.Function(params, result) =>
                builder.addOne(7.toByte)
                writeVarint(params.size)
                params.foreach(writeType)
                writeType(result)
            case Tasty.Type.ContextFunction(params, result) =>
                builder.addOne(8.toByte)
                writeVarint(params.size)
                params.foreach(writeType)
                writeType(result)
            case Tasty.Type.ByName(underlying) =>
                builder.addOne(9.toByte)
                writeType(underlying)
            case Tasty.Type.Repeated(elem) =>
                builder.addOne(10.toByte)
                writeType(elem)
            case Tasty.Type.Array(elem) =>
                builder.addOne(11.toByte)
                writeType(elem)
            case other =>
                // Catch-all: store the show string. No round-trip semantic for complex production types;
                // preserves the text for diagnostics without crashing on types not covered by the encoder.
                builder.addOne(255.toByte)
                writeStr(other.toString)
        end writeType

        for err <- errors do
            val tag: String           = err.productPrefix
            val tagBytes: Array[Byte] = tag.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            writeVarint(tagBytes.length)
            builder.addAll(tagBytes)
            err match
                case TastyError.FileNotFound(path)              => writeStr(path)
                case TastyError.CorruptedFile(path, at, reason) => writeStr(path); writeLong(at); writeStr(reason)
                case TastyError.UnsupportedVersion(found, sup)  => writeVersion(found); writeVersion(sup)
                case TastyError.InconsistentClasspath(file, exp, fnd) =>
                    writeStr(file); writeUUID(exp); writeUUID(fnd)
                case TastyError.FullNameCollisionError(fullName)       => writeStr(fullName)
                case TastyError.MalformedSection(name, reason, at)     => writeStr(name); writeStr(reason); writeLong(at)
                case TastyError.SymbolNotFound(fullName)               => writeStr(fullName)
                case TastyError.NotFound(fullName)                     => writeStr(fullName)
                case TastyError.ClassfileFormatError(path, reason, at) => writeStr(path); writeStr(reason); writeLong(at)
                case TastyError.ClasspathClosed(ctx)                   => writeStr(ctx)
                case TastyError.ClasspathBuilding(ctx)                 => writeStr(ctx)
                case TastyError.SnapshotFormatError(path, reason, at)  => writeStr(path); writeStr(reason); writeLong(at)
                case TastyError.SnapshotVersionMismatch(found, sup)    => writeVersion(found); writeVersion(sup)
                case TastyError.SnapshotIoError(cause)                 => writeStr(cause)
                case TastyError.NotImplemented(feature)                => writeStr(feature)
                case TastyError.UnsupportedPlatform(feature)           => writeStr(feature)
                case TastyError.UnknownTagInPosition(tag, pos)         => writeInt(tag); writeStr(pos)
                case TastyError.InvalidFullName(fullName, reason)      => writeStr(fullName); writeStr(reason)
                case TastyError.InvalidUuid(input)                     => writeStr(input)
                case TastyError.DigestMismatch(exp, act)               => writeStr(exp); writeStr(act)
                case TastyError.UnhandledSubtypingCase(shape, lhs, rhs, file) =>
                    writeStr(shape); writeType(lhs); writeType(rhs); writeStr(file)
                case TastyError.UnresolvedReference(name, idx) =>
                    writeStr(name); writeInt(idx)
                case TastyError.UnknownType(file, byteOffset, reason) =>
                    writeStr(file); writeLong(byteOffset); writeStr(reason)
                case TastyError.MissingDeclaredType(symbolId, file) =>
                    writeInt(symbolId.value); writeStr(file)
            end match
        end for
        builder.result().toArray
    end serializeErrors

    /** Serialize per-symbol integer-reference lists into a flat byte block.
      *
      * Layout: [4-byte count] followed by count entries, each: [4-byte symIdx][4-byte refCount][refCount x 4-byte refIds]. Entries with an
      * empty ref list are omitted from the output (the reader falls back to Chunk.empty for unset symbols). Refs with value -1 (non-
      * serializable, e.g. non-Named parent types) are filtered out before writing; if filtering leaves an empty list the entry is omitted.
      */
    private def serializeSymbolRelLists(
        symbols: Seq[Tasty.Symbol],
        refsOf: Tasty.Symbol => Chunk[Int]
    ): Array[Byte] =
        // parentTypes / declarationIds / typeParamIds are direct fields on Symbol.
        val builder = ChunkBuilder.init[Byte]
        val tmp     = new Array[Byte](4)
        // Collect valid entries: (symIdx, filteredRefs) where filteredRefs is non-empty.
        val entries = symbols.zipWithIndex.flatMap { (symbol, idx) =>
            val refs = refsOf(symbol).filter(_ >= 0)
            if refs.isEmpty then None else Some((idx, refs))
        }
        SnapshotFormat.writeInt32LE(tmp, 0, entries.size)
        builder.addAll(tmp)
        for (symIdx, refs) <- entries do
            SnapshotFormat.writeInt32LE(tmp, 0, symIdx)
            builder.addAll(tmp)
            SnapshotFormat.writeInt32LE(tmp, 0, refs.size)
            builder.addAll(tmp)
            for r <- refs do
                SnapshotFormat.writeInt32LE(tmp, 0, r)
                builder.addAll(tmp)
            end for
        end for
        builder.result().toArray
    end serializeSymbolRelLists

    /** Serialize per-symbol annotation tycon fully-qualified name ids into a flat byte block.
      *
      * Layout: [4-byte count] then entries [4-byte symIdx][4-byte annCount][annCount x 4-byte tyconFullNameId].
      * Named, TermRef, and Applied (unwrapped to base) annotation tycons are handled via tyconFullName.
      * Annotations with unrecognised tycon forms produce empty fully-qualified name and are omitted.
      * Symbols with no serializable annotations are omitted.
      * `internFullName` is the name-pool intern function from `serialize`; must be the same instance so IDs are
      * consistent with the NAMES section.
      */
    private def serializeAnnotations(
        symbols: Seq[Tasty.Symbol],
        internFullName: String => Int,
        symbolById: scala.collection.mutable.HashMap[Int, Tasty.Symbol],
        fullNameBySymbol: mutable.HashMap[Int, String],
        unresolvedFullNameByNegId: Dict[Tasty.SymbolId, String]
    ): Array[Byte] =
        import Tasty.Name.asString
        val builder = ChunkBuilder.init[Byte]
        val tmp     = new Array[Byte](4)

        // Collect valid entries: (symIdx, tyconIds) where tyconIds is non-empty.
        val entries = symbols.zipWithIndex.flatMap { (symbol, idx) =>
            val annotations: Chunk[Tasty.Annotation] = symbol match
                case c: Tasty.Symbol.ClassLike     => c.annotations
                case m: Tasty.Symbol.Method        => m.annotations
                case v: Tasty.Symbol.Val           => v.annotations
                case w: Tasty.Symbol.Var           => w.annotations
                case ta: Tasty.Symbol.TypeAlias    => ta.annotations
                case ot: Tasty.Symbol.OpaqueType   => ot.annotations
                case at: Tasty.Symbol.AbstractType => at.annotations
                case p: Tasty.Symbol.Parameter     => p.annotations
                case _: Tasty.Symbol.Field | _: Tasty.Symbol.TypeParam | _: Tasty.Symbol.Package =>
                    Chunk.empty[Tasty.Annotation]
            // Extract the tycon fully-qualified name pool ID for Named and TermRef types.
            // @deprecated and most Scala annotations arrive as TermRef tycons; Named is
            // less common but handled for completeness. Applied tycons are unwrapped to their base.
            // For annotations with negative SymbolIds (unresolved external annotation
            // types like scala.deprecated on JS/Native), fall back to unresolvedFullNameByNegId.
            // Unknown tycon forms produce an empty fully-qualified name and are omitted.
            val tyconIds: Chunk[Int] = annotations.flatMap { annotation =>
                val fullName = annotation.annotationType match
                    case Tasty.Type.Named(sid) if sid.value < -1 =>
                        // Negative ID: annotation type not on classpath. Look up in unresolvedFullNameByNegId.
                        unresolvedFullNameByNegId.getOrElse(sid, "")
                    case other =>
                        tyconFullName(other, symbolById, fullNameBySymbol, unresolvedFullNameByNegId)
                if fullName.nonEmpty then Some(internFullName(fullName)) else None
            }
            if tyconIds.isEmpty then None else Some((idx, tyconIds))
        }

        SnapshotFormat.writeInt32LE(tmp, 0, entries.size)
        builder.addAll(tmp)
        for (symIdx, tyconIds) <- entries do
            SnapshotFormat.writeInt32LE(tmp, 0, symIdx)
            builder.addAll(tmp)
            SnapshotFormat.writeInt32LE(tmp, 0, tyconIds.size)
            builder.addAll(tmp)
            for tid <- tyconIds do
                SnapshotFormat.writeInt32LE(tmp, 0, tid)
                builder.addAll(tmp)
            end for
        end for
        builder.result().toArray
    end serializeAnnotations

    /** Serialize per-symbol javaMetadata accessFlags.
      *
      * Layout: [4-byte count] then entries [4-byte symIdx][4-byte accessFlags].
      * Only symbols that have javaMetadata present are included.
      */
    private def serializeJavaMetadata(
        symbols: Seq[Tasty.Symbol]
    ): Array[Byte] =
        val builder = ChunkBuilder.init[Byte]
        val tmp     = new Array[Byte](4)

        val entries = symbols.zipWithIndex.flatMap { (symbol, idx) =>
            val metaOpt: kyo.Maybe[Tasty.Java.Metadata] = symbol match
                case c: Tasty.Symbol.ClassLike => c.javaMetadata
                case f: Tasty.Symbol.Field     => f.javaMetadata
                case m: Tasty.Symbol.Method    => m.javaMetadata
                case _: Tasty.Symbol.Val | _: Tasty.Symbol.Var | _: Tasty.Symbol.TypeAlias |
                    _: Tasty.Symbol.OpaqueType | _: Tasty.Symbol.AbstractType | _: Tasty.Symbol.TypeParam |
                    _: Tasty.Symbol.Parameter | _: Tasty.Symbol.Package =>
                    kyo.Maybe.Absent
            metaOpt match
                case kyo.Maybe.Present(meta) => Some((idx, meta.accessFlags))
                case kyo.Maybe.Absent        => None
        }

        SnapshotFormat.writeInt32LE(tmp, 0, entries.size)
        builder.addAll(tmp)
        for (symIdx, accessFlags) <- entries do
            SnapshotFormat.writeInt32LE(tmp, 0, symIdx)
            builder.addAll(tmp)
            SnapshotFormat.writeInt32LE(tmp, 0, accessFlags)
            builder.addAll(tmp)
        end for
        builder.result().toArray
    end serializeJavaMetadata

    /** Serialize the full fullNameIndex (all key->symIdx pairs) into a flat byte block.
      *
      * Layout: [4-byte count LE] then count entries each [4-byte namePoolId LE][4-byte symIdx LE].
      * Each entry stores a name-pool index for the fully-qualified name string and a symbol index (position in symbolList).
      * All fully-qualified name keys are interned into the shared name pool via `internName`; keys not present in
      * `symbolId` are skipped (should never happen for a well-formed Classpath).
      *
      * This section stores the FULL fullNameIndex including dual-index source fully-qualified name aliases added by the
      * ClasspathOrchestrator (e.g. both "scala.Predef$" and "scala.Predef" for an Object companion,
      * both the binary and source fully-qualified names for opaque types). The reader reconstructs fullNameIndex verbatim
      * from this section, bypassing the single-fully-qualified-name-per-symbol limitation of the SYMBOLS section.
      */
    private def serializeFullNameIndex(
        fullNameIndex: Dict[String, kyo.Tasty.SymbolId],
        symbolList: Seq[Tasty.Symbol],
        internName: String => Int
    ): Array[Byte] =
        val builder = ChunkBuilder.init[Byte]
        val tmp     = new Array[Byte](4)
        // Build SymbolId.value -> snapshot index mapping (snapshot index = position in symbolList).
        val symIdToIdx = new scala.collection.mutable.HashMap[Int, Int]()
        for (symbol, idx) <- symbolList.zipWithIndex do
            symIdToIdx(symbol.id.value) = idx
        end for
        // Collect valid entries: (namePoolId, snapshotIdx).
        // Secondary fix: when symIdToIdx.get(id.value) misses (i.e. id.value == -1 due to a
        // ghost entry from finalizeMerge that the Path-1 fix may not have reached), fall back to a
        // fully-qualified name string lookup. canonicalSourceFullName maps the binary-alias form back to source form; if the
        // canonical form is present in fullNameIndex with a valid SymbolId, use that snapshot index.
        // This ensures EVERY entry in fullNameIndex is serialized, eliminating the cold/warm size gap.
        // Sort by fullName before building entries so FQNIDX__ byte layout is stable
        // regardless of Dict iteration order.
        val entries = fullNameIndex.toMap.toSeq.sortBy(_._1).flatMap { (fullName, id) =>
            symIdToIdx.get(id.value) match
                case Some(idx) => Some((internName(fullName), idx))
                case None =>
                    val canonicalFullName = FullNameNormalizer.canonicalSourceFullName(fullName)
                    if canonicalFullName != fullName then
                        fullNameIndex.get(canonicalFullName) match
                            case Maybe.Present(canonId) =>
                                symIdToIdx.get(canonId.value) match
                                    case Some(idx) => Some((internName(fullName), idx))
                                    case None      => None
                            case Maybe.Absent => None
                    else None
                    end if
        }
        SnapshotFormat.writeInt32LE(tmp, 0, entries.size)
        builder.addAll(tmp)
        for (nameId, idx) <- entries do
            SnapshotFormat.writeInt32LE(tmp, 0, nameId)
            builder.addAll(tmp)
            SnapshotFormat.writeInt32LE(tmp, 0, idx)
            builder.addAll(tmp)
        end for
        builder.result().toArray
    end serializeFullNameIndex

    /** Serialize the unresolvedFullNameByNegId map into a flat byte block.
      *
      * Layout: [4-byte count LE] then count entries each [4-byte negId LE][4-byte namePoolId LE].
      * The fully-qualified name strings are interned into the shared name pool via `internName` (same instance as other sections).
      * Entries are sorted by negId for determinism
      */
    private def serializeFullNameMap(
        unresolvedFullNameByNegId: Dict[kyo.Tasty.SymbolId, String],
        internName: String => Int
    ): Array[Byte] =
        import kyo.Tasty.SymbolId
        val builder = ChunkBuilder.init[Byte]
        val tmp     = new Array[Byte](4)
        // Sort by negId value for deterministic output.
        val entries = unresolvedFullNameByNegId.toMap.toSeq.sortBy(_._1.value).filter { case (_, fullName) => fullName.nonEmpty }
        SnapshotFormat.writeInt32LE(tmp, 0, entries.size)
        builder.addAll(tmp)
        for (negId, fullName) <- entries do
            SnapshotFormat.writeInt32LE(tmp, 0, negId.value)
            builder.addAll(tmp)
            SnapshotFormat.writeInt32LE(tmp, 0, internName(fullName))
            builder.addAll(tmp)
        end for
        builder.result().toArray
    end serializeFullNameMap

    /** Serialize the subclassIndex map into a flat byte block.
      *
      * Layout: [4-byte count LE] then count entries each
      *   [4-byte parentSymIdx LE][4-byte childCount LE][childCount x 4-byte childSymIdx LE].
      * All indices are snapshot positions (0-based). Entries where the parent SymbolId is not in the
      * symbols array (e.g. sentinel -1) are skipped. Child ids not in the symbols array are also
      * skipped.
      */
    private def serializeSubclassIndex(
        subclassIndex: Dict[kyo.Tasty.SymbolId, Chunk[kyo.Tasty.SymbolId]],
        symIdToIdx: scala.collection.mutable.HashMap[Int, Int]
    ): Array[Byte] =
        import kyo.Tasty.SymbolId
        val builder = ChunkBuilder.init[Byte]
        val tmp     = new Array[Byte](4)
        // Collect valid entries: (parentIdx, filteredChildren).
        val entries = subclassIndex.toMap.toSeq.sortBy(_._1.value).flatMap { (parentId, children) =>
            symIdToIdx.get(parentId.value) match
                case Some(parentIdx) =>
                    val childIdxs = children.toSeq.flatMap(cid => symIdToIdx.get(cid.value)).filter(_ >= 0)
                    if childIdxs.nonEmpty then Some((parentIdx, childIdxs)) else None
                case None => None
        }
        SnapshotFormat.writeInt32LE(tmp, 0, entries.size)
        builder.addAll(tmp)
        for (parentIdx, childIdxs) <- entries do
            SnapshotFormat.writeInt32LE(tmp, 0, parentIdx)
            builder.addAll(tmp)
            SnapshotFormat.writeInt32LE(tmp, 0, childIdxs.size)
            builder.addAll(tmp)
            for ci <- childIdxs do
                SnapshotFormat.writeInt32LE(tmp, 0, ci)
                builder.addAll(tmp)
            end for
        end for
        builder.result().toArray
    end serializeSubclassIndex

    /** Serialize the companionIndex map into a flat byte block.
      *
      * Layout: [4-byte count LE] then count entries each
      *   [4-byte symIdx LE][4-byte companionSymIdx LE].
      * Entries where either SymbolId is not in the symbols array are skipped.
      */
    private def serializeCompanionIndex(
        companionIndex: Dict[kyo.Tasty.SymbolId, kyo.Tasty.SymbolId],
        symIdToIdx: scala.collection.mutable.HashMap[Int, Int]
    ): Array[Byte] =
        import kyo.Tasty.SymbolId
        val builder = ChunkBuilder.init[Byte]
        val tmp     = new Array[Byte](4)
        val entries = companionIndex.toMap.toSeq.sortBy(_._1.value).flatMap { (symId, companionId) =>
            for
                symIdx       <- symIdToIdx.get(symId.value)
                companionIdx <- symIdToIdx.get(companionId.value)
            yield (symIdx, companionIdx)
        }
        SnapshotFormat.writeInt32LE(tmp, 0, entries.size)
        builder.addAll(tmp)
        for (symIdx, companionIdx) <- entries do
            SnapshotFormat.writeInt32LE(tmp, 0, symIdx)
            builder.addAll(tmp)
            SnapshotFormat.writeInt32LE(tmp, 0, companionIdx)
            builder.addAll(tmp)
        end for
        builder.result().toArray
    end serializeCompanionIndex

    /** Serialize per-method paramListIds into the PLISTS__ section payload.
      *
      * Sparse two-level Int32-LE encoding. Mirrors serializeSymbolRelLists at :603-627 and
      * serializeSubclassIndex at :815-841 for shape symmetry. Methods whose paramListIds field
      * is Chunk.empty are omitted; methods with Chunk(Chunk.empty) emit listCount=1, innerCount=0
      * to preserve the no-arg-empty-clause distinction.
      *
      * Written unconditionally even when no method has a non-empty paramListIds (count=0).
      *
      * Layout:
      *   [Int32-LE entryCount]
      *   entryCount x:
      *     [Int32-LE symIdx][Int32-LE listCount]
      *     listCount x:
      *       [Int32-LE innerCount]
      *       innerCount x [Int32-LE symbolId.value]
      */
    private def serializeParamLists(symbols: Seq[Tasty.Symbol]): Array[Byte] =
        val builder = ChunkBuilder.init[Byte]
        val tmp     = new Array[Byte](4)
        val entries = symbols.zipWithIndex.flatMap {
            case (m: Tasty.Symbol.Method, idx) if m.paramListIds.nonEmpty =>
                Some((idx, m.paramListIds))
            case (_: Tasty.Symbol.Method, _) => None
            case (_: Tasty.Symbol.Class, _) | (_: Tasty.Symbol.EnumCase, _) | (_: Tasty.Symbol.Trait, _) |
                (_: Tasty.Symbol.Object, _) | (_: Tasty.Symbol.Val, _) | (_: Tasty.Symbol.Var, _) |
                (_: Tasty.Symbol.Field, _) | (_: Tasty.Symbol.TypeAlias, _) | (_: Tasty.Symbol.OpaqueType, _) |
                (_: Tasty.Symbol.AbstractType, _) | (_: Tasty.Symbol.TypeParam, _) |
                (_: Tasty.Symbol.Parameter, _) | (_: Tasty.Symbol.Package, _) =>
                None
        }
        SnapshotFormat.writeInt32LE(tmp, 0, entries.size)
        builder.addAll(tmp)
        for (symIdx, lists) <- entries do
            SnapshotFormat.writeInt32LE(tmp, 0, symIdx)
            builder.addAll(tmp)
            SnapshotFormat.writeInt32LE(tmp, 0, lists.size)
            builder.addAll(tmp)
            for inner <- lists do
                SnapshotFormat.writeInt32LE(tmp, 0, inner.size)
                builder.addAll(tmp)
                for id <- inner do
                    SnapshotFormat.writeInt32LE(tmp, 0, id.value)
                    builder.addAll(tmp)
                end for
            end for
        end for
        builder.result().toArray
    end serializeParamLists

    /** Convert a Name (opaque String alias) to a String. */
    private def nameToStr(n: Tasty.Name): String =
        import Tasty.Name.asString
        n.asString
    end nameToStr

    /** Extract a dotted fully-qualified name string from an annotation tycon type without needing a Classpath.
      *
      * Handles Type.Named (looks up fully-qualified name via fullNameBySymbol; falls back to unresolvedFullNameByNegId for negative ids),
      * Type.TermRef (recursively builds prefix.name), and Type.Applied (delegates to the unapplied base).
      * Returns empty string for unrecognised types.
      * Called from serializeAnnotations to cover both Named and TermRef tycon forms.
      *
      * Passes unresolvedFullNameByNegId so that nested Named(negId) references (e.g. the "scala" package
      * qualifier in TermRef(Named(-X_scala), Name("deprecated"))) can be resolved to their fully-qualified name strings.
      * Without this, the qualifier resolves to "" and the outer TermRef returns only the simple name "deprecated" instead
      * of the full "scala.deprecated".
      */
    private def tyconFullName(
        t: Tasty.Type,
        symbolById: scala.collection.mutable.HashMap[Int, Tasty.Symbol],
        fullNameBySymbol: mutable.HashMap[Int, String],
        unresolvedFullNameByNegId: Dict[Tasty.SymbolId, String]
    ): String =
        import Tasty.Name.asString
        t match
            case Tasty.Type.Named(annSymId) =>
                fullNameBySymbol.get(annSymId.value) match
                    case Some(fullName) if fullName.nonEmpty => fullName
                    // Carve-out: stdlib Option from mutable.HashMap.get; covers absent and empty-fullName
                    case _ =>
                        if annSymId.value < -1 then
                            // Negative id: look up via unresolvedFullNameByNegId (cross-file external reference).
                            unresolvedFullNameByNegId.getOrElse(annSymId, "")
                        else
                            symbolById.get(annSymId.value) match
                                case Some(annSym) => nameToStr(annSym.name)
                                // Carve-out: stdlib Option from mutable.HashMap.get; absent symbol yields empty string
                                case None => ""
                        end if
                end match
            case Tasty.Type.TermRef(qual, name) =>
                val q = tyconFullName(qual, symbolById, fullNameBySymbol, unresolvedFullNameByNegId)
                if q.nonEmpty then q + "." + name.asString else name.asString
            case Tasty.Type.TypeRef(qual, name) =>
                // TYPEREF emits TypeRef; serialize the same way as TermRef.
                val q = tyconFullName(qual, symbolById, fullNameBySymbol, unresolvedFullNameByNegId)
                if q.nonEmpty then q + "." + name.asString else name.asString
            case Tasty.Type.Applied(base, _) =>
                tyconFullName(base, symbolById, fullNameBySymbol, unresolvedFullNameByNegId)
            case _: Tasty.Type.TypeLambda | _: Tasty.Type.Function | _: Tasty.Type.ContextFunction |
                _: Tasty.Type.Tuple | _: Tasty.Type.ByName | _: Tasty.Type.Repeated | _: Tasty.Type.Array |
                _: Tasty.Type.Refinement | _: Tasty.Type.Rec | _: Tasty.Type.RecThis | _: Tasty.Type.AndType |
                _: Tasty.Type.OrType | _: Tasty.Type.Annotated | _: Tasty.Type.ConstantType |
                _: Tasty.Type.ThisType | _: Tasty.Type.SuperType | _: Tasty.Type.ParamRef |
                _: Tasty.Type.Wildcard | _: Tasty.Type.Skolem | _: Tasty.Type.MatchType |
                _: Tasty.Type.FlexibleType | _: Tasty.Type.Bind | _: Tasty.Type.MatchCase | _: Tasty.Type.Bounds |
                Tasty.Type.Nothing | Tasty.Type.Any =>
                ""
        end match
    end tyconFullName

end SnapshotWriter
