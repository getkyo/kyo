package kyo.internal.tasty.snapshot

/** KRFL snapshot format constants.
  *
  * KRFL (Kyo Tasty Format Library) is a binary snapshot format for caching decoded classpath data between builds.
  *
  * Header layout:
  * {{{
  * +------------------+
  * | magic    "KRFL"  | 4 bytes (ASCII, little-endian)
  * | version  M.m.0.0 | 4 bytes (major, minor, 0, 0 as bytes)
  * | flags            | 8 bytes (bit 0: byte order, 0=LE)
  * +------------------+
  * | inputDigest      | 8 bytes (FNV-1a 64-bit hash of inputs, little-endian)
  * | reserved         | 8 bytes (zero-padded)
  * +------------------+
  * | sectionCount     | 4 bytes (little-endian)
  * | sectionIndex     | sectionCount * 24 bytes each:
  * |   name           |   8 bytes (zero-padded ASCII section ID)
  * |   offset         |   8 bytes (byte offset from file start, little-endian)
  * |   length         |   8 bytes (byte length, little-endian)
  * +------------------+
  * | section payloads | variable
  * +------------------+
  * }}}
  *
  * All multi-byte integers are little-endian. Byte order flag in `flags` is always 0 (LE) for all platforms.
  *
  * Section IDs:
  *   - `NAMES`: Packed name bytes + (offset: Int, length: Int) table indexed by NameId.
  *   - `SYMBOLS`: Fixed-size records encoding symbol fields (kind, flags, nameId, ownerId, etc.). `home` is NOT serialized; restored from
  *     the enclosing `Classpath` at load time.
  *   - `TYPES`: Packed type records indexed by canonical type ID.
  *   - `TYPESEXT`: Variable-length operand data for multi-operand types.
  *   - `PARENTS`: Int arrays for class parent lists.
  *   - `MEMBERS`: Int arrays for class member lists.
  *   - `TPARAMS_`: Type parameter records per symbol (added in minor=3).
  *   - `FILES`: Per-source-file metadata (path, mtime, size, uuid).
  *   - `BODYBYTE`: Inline byte storage for lazy body decode.
  *   - `ERRORS`: Serialized TastyError cases accumulated during decode.
  *   - `PERMITS2`: permittedSubclassIds per symbol (added in minor=4).
  *   - `ANNOTS_`: annotation tycon FQN ids per symbol (added in minor=4).
  *   - `JAVAMETA`: javaMetadata accessFlags per symbol (added in minor=4).
  *   - `FQNIDX__`: full fqnIndex (all keys including dual-index aliases) per symbol (added in minor=5).
  *   - `FQNMAP__`: unresolvedFqnByNegId map (negId to FQN string for external annotation types) (added in minor=6).
  *
  * Versioning policy:
  *   - Major bump: invalidates all old snapshots (full re-decode + fresh write). Reader emits `TastyError.SnapshotVersionMismatch`.
  *   - Minor bump (non-breaking add-only): old snapshots load; new sections are absent and fall back to empty.
  *   - Minor bump (breaking in-record change): old snapshots must be rejected; reader emits `TastyError.SnapshotVersionMismatch`.
  *     minor=4 is a breaking bump because the SYMBOLS record layout is not changed but new relational sections are required for
  *     correct permittedSubclassIds / annotations / javaMetadata; a minor=3 snapshot must trigger cold re-decode.
  *   - Patch bump: format-stable.
  *
  * Digest algorithm: FNV-1a 64-bit (non-cryptographic; sufficient for cache-invalidation). See `DigestComputer`.
  *
  * Atomic-rename concurrent write strategy: write to `${digest}-${pid}-${nonce}.krfl`, fsync, rename to `${digest}.krfl`. Two concurrent
  * writers produce identical tmp files (decode is deterministic) and both attempt rename. The last rename wins; no corruption.
  */
object SnapshotFormat:

    /** 4-byte ASCII magic at file offset 0. */
    val magic: Array[Byte] = Array('K', 'R', 'F', 'L')

    /** Current format version. Major bumps invalidate old snapshots. */
    val majorVersion: Int = 1
    val minorVersion: Int = 6

    /** Size of the fixed-length file header in bytes (magic + version + flags + digest + reserved). */
    val headerSize: Int = 4 + 4 + 8 + 8 + 8

    /** Size of one section-index entry: name (8) + offset (8) + length (8). */
    val sectionIndexEntrySize: Int = 24

    /** Section IDs (exactly 8 ASCII bytes, zero-padded). */
    val sectionNames: Array[String] =
        Array(
            "NAMES",
            "SYMBOLS",
            "TYPES",
            "TYPESEXT",
            "PARENTS",
            "MEMBERS",
            "TPARAMS_",
            "FILES",
            "BODYBYTE",
            "ERRORS",
            "PERMITS2",
            "ANNOTS_",
            "JAVAMETA",
            "FQNIDX__",
            "FQNMAP__"
        )

    val sectionNAMES: String     = "NAMES"
    val sectionSYMBOLS: String   = "SYMBOLS"
    val sectionTYPES: String     = "TYPES"
    val sectionTYPEXTRA: String  = "TYPESEXT"
    val sectionPARENTS: String   = "PARENTS"
    val sectionMEMBERS: String   = "MEMBERS"
    val sectionTPARAMS: String   = "TPARAMS_"
    val sectionFILES: String     = "FILES"
    val sectionBODYBYTES: String = "BODYBYTE"
    val sectionERRORS: String    = "ERRORS"

    /** Phase 12 sections: permittedSubclassIds, annotations, javaMetadata. */
    val sectionPERMITS2: String = "PERMITS2"
    val sectionANNOTS: String   = "ANNOTS_"
    val sectionJAVAMETA: String = "JAVAMETA"

    /** Phase 12 dual-FQN section: full fqnIndex serialization.
      *
      * Stores every key in the classpath fqnIndex (including dual-index source-FQN aliases for Object companions and opaque types) so that
      * warm-load lookups via source FQN work identically to cold-load.
      *
      * Added in minor=5 (non-breaking add; absent in minor=4 snapshots, which are rejected anyway).
      */
    val sectionFQNIDX: String = "FQNIDX__"

    /** Phase 2.13 unresolved-FQN section: unresolvedFqnByNegId map serialization.
      *
      * Stores the negId -> FQN string map accumulated during cold decoding. On warm load this map is reconstructed so that
      * `typeFqnString` can fall back to FQN strings for annotation types (e.g. `scala.deprecated`) that reference external libraries not on
      * the classpath. Without this section the warm-loaded classpath loses annotation FQN resolution for embedded-fixture loads on JS/Native.
      *
      * Layout: [4-byte count LE] then count entries each [4-byte negId LE][4-byte namePoolId LE].
      * Added in minor=6 (non-breaking add; absent in minor=5 snapshots, which fall back to an empty map).
      */
    val sectionFQNMAP: String = "FQNMAP__"

    /** Write a little-endian 32-bit int at position `pos` in `buf`. */
    def writeInt32LE(buf: Array[Byte], pos: Int, value: Int): Unit =
        buf(pos) = (value & 0xff).toByte
        buf(pos + 1) = ((value >> 8) & 0xff).toByte
        buf(pos + 2) = ((value >> 16) & 0xff).toByte
        buf(pos + 3) = ((value >> 24) & 0xff).toByte
    end writeInt32LE

    /** Write a little-endian 64-bit long at position `pos` in `buf`. */
    def writeInt64LE(buf: Array[Byte], pos: Int, value: Long): Unit =
        buf(pos) = (value & 0xff).toByte
        buf(pos + 1) = ((value >> 8) & 0xff).toByte
        buf(pos + 2) = ((value >> 16) & 0xff).toByte
        buf(pos + 3) = ((value >> 24) & 0xff).toByte
        buf(pos + 4) = ((value >> 32) & 0xff).toByte
        buf(pos + 5) = ((value >> 40) & 0xff).toByte
        buf(pos + 6) = ((value >> 48) & 0xff).toByte
        buf(pos + 7) = ((value >> 56) & 0xff).toByte
    end writeInt64LE

    /** Read a little-endian 32-bit int from position `pos` in `buf`. */
    def readInt32LE(buf: Array[Byte], pos: Int): Int =
        (buf(pos) & 0xff) |
            ((buf(pos + 1) & 0xff) << 8) |
            ((buf(pos + 2) & 0xff) << 16) |
            ((buf(pos + 3) & 0xff) << 24)

    /** Read a little-endian 64-bit long from position `pos` in `buf`. */
    def readInt64LE(buf: Array[Byte], pos: Int): Long =
        (buf(pos) & 0xffL) |
            ((buf(pos + 1) & 0xffL) << 8) |
            ((buf(pos + 2) & 0xffL) << 16) |
            ((buf(pos + 3) & 0xffL) << 24) |
            ((buf(pos + 4) & 0xffL) << 32) |
            ((buf(pos + 5) & 0xffL) << 40) |
            ((buf(pos + 6) & 0xffL) << 48) |
            ((buf(pos + 7) & 0xffL) << 56)

    /** Write a zero-padded 8-byte section name. */
    def writeSectionName(buf: Array[Byte], pos: Int, name: String): Unit =
        var i = 0
        while i < 8 && i < name.length do
            buf(pos + i) = name.charAt(i).toByte
            i += 1
        while i < 8 do
            buf(pos + i) = 0
            i += 1
    end writeSectionName

    /** Read a zero-padded 8-byte section name. */
    def readSectionName(buf: Array[Byte], pos: Int): String =
        val sb = new StringBuilder(8)
        var i  = 0
        while i < 8 && buf(pos + i) != 0 do
            sb.append(buf(pos + i).toChar)
            i += 1
        sb.toString
    end readSectionName

    /** Encode a String as UTF-8 bytes (for serialization). */
    def encodeString(s: String): Array[Byte] = s.getBytes(java.nio.charset.StandardCharsets.UTF_8)

    /** Decode UTF-8 bytes as a String. */
    def decodeString(bytes: Array[Byte], offset: Int, length: Int): String =
        new String(bytes, offset, length, java.nio.charset.StandardCharsets.UTF_8)

end SnapshotFormat
