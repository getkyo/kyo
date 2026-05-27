package kyo.internal.tasty.snapshot

import java.io.ByteArrayOutputStream
import kyo.*
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.query.Classpath
import kyo.internal.tasty.query.FileSource
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
        cp: Classpath,
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
    private def serialize(cp: Classpath, digest: Array[Byte]): Array[Byte] =
        // Unsafe: stateRef.unsafe.get() non-effectful read of immutable Ready state for snapshot serialization
        import AllowUnsafe.embrace.danger
        val allSymbols = cp.stateRef.unsafe.get() match
            case s: Classpath.State.Ready => s.allSymbols
            case _                        => Chunk.empty

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
            internName(nameToStr(sym.fullName))

        // Collect body byte slices for TASTy-origin symbols.
        // Each entry is (relativeBodyStart: Int, relativeBodyEnd: Int) into the BODY_BYTES section.
        // Symbols without a valid body (Java, Package, zero-bodyStart) get (0, 0).
        val bodyBytesBuffer = new java.io.ByteArrayOutputStream()
        var runningOffset   = 0
        val symBodyStarts   = new Array[Int](symbolList.size)
        val symBodyEnds     = new Array[Int](symbolList.size)
        for (sym, idx) <- symbolList.zipWithIndex do
            sym.origin match
                case o: Tasty.Symbol.TastyOrigin
                    if o.bodyStart > 0 && o.bodyEnd > o.bodyStart && (o.sectionView ne null) =>
                    val sv       = o.sectionView
                    val sliceLen = o.bodyEnd - o.bodyStart
                    // Copy the body slice [bodyStart, bodyEnd) from the section view into the snapshot buffer.
                    val sliceBytes = sv.subView(o.bodyStart, o.bodyEnd).copyAllToArray()
                    bodyBytesBuffer.write(sliceBytes, 0, sliceLen)
                    symBodyStarts(idx) = runningOffset
                    symBodyEnds(idx) = runningOffset + sliceLen
                    runningOffset += sliceLen
                case _ =>
                    symBodyStarts(idx) = 0
                    symBodyEnds(idx) = 0
        end for

        // Collect errors
        val errors = cp.stateRef.unsafe.get() match
            case s: Classpath.State.Ready => s.errors
            case _                        => Chunk.empty

        // --- Build section payloads ---

        // NAMES section: length-prefixed UTF-8 strings
        val namesBytes = serializeNamePool(namePool.toSeq)

        // SYMBOLS section: fixed record per symbol (includes body offsets into BODY_BYTES)
        val symbolsBytes = serializeSymbols(symbolList, symNames, symFqns, symbolId, symBodyStarts, symBodyEnds)

        // FILES section: empty (metadata not critical for cache load)
        val filesBytes = Array.empty[Byte]

        // ERRORS section: length-prefixed error strings
        val errorsBytes = serializeErrors(errors)

        // Empty sections for types (not serialized; bodies re-decoded lazily from BODY_BYTES)
        val typesBytes      = Array.empty[Byte]
        val typesExtraBytes = Array.empty[Byte]
        val parentsBytes    = Array.empty[Byte]
        val membersBytes    = Array.empty[Byte]
        val bodyBytes       = bodyBytesBuffer.toByteArray

        val sections = Seq(
            (SnapshotFormat.sectionNAMES, namesBytes),
            (SnapshotFormat.sectionSYMBOLS, symbolsBytes),
            (SnapshotFormat.sectionTYPES, typesBytes),
            (SnapshotFormat.sectionTYPEXTRA, typesExtraBytes),
            (SnapshotFormat.sectionPARENTS, parentsBytes),
            (SnapshotFormat.sectionMEMBERS, membersBytes),
            (SnapshotFormat.sectionFILES, filesBytes),
            (SnapshotFormat.sectionBODYBYTES, bodyBytes),
            (SnapshotFormat.sectionERRORS, errorsBytes)
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
        val baos = new ByteArrayOutputStream()
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
            val ownerId = if sym.owner != null then symbolId.getOrElse(sym.owner, -1) else -1
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
        val baos = new ByteArrayOutputStream()
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

    /** Convert a Name (opaque Interner.Entry) to a String. */
    private def nameToStr(n: Tasty.Name): String =
        import Tasty.Name.asString
        n.asString

end SnapshotWriter
