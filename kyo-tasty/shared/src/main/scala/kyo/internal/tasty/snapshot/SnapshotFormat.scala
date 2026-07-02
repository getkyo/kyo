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
  * | inputDigest      | 8 bytes (xxh64-custom 64-bit over jar CEN (name, CRC32) walk; little-endian)
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
  * Symbol field coverage: every field in all 14 `Symbol` case classes is serialized. There is no `home` field on any Symbol subtype.
  *
  * Section IDs:
  *   - `NAMES`: Packed name bytes + (offset: Int, length: Int) table indexed by NameId.
  *   - `SYMBOLS`: Fixed-size records encoding symbol fields (kind, flags, nameId, ownerId, body offsets, etc.).
  *   - `TYPES`: Packed type records indexed by canonical type ID.
  *   - `TYPESEXT`: Variable-length operand data for multi-operand types.
  *   - `PARENTS`: Int arrays for class parent lists.
  *   - `MEMBERS`: Int arrays for class member lists.
  *   - `TPARAMS_`: Type parameter records per symbol (added in minor=3).
  *   - `FILES`: Per-source-file metadata (path, mtime, size, uuid).
  *   - `BODYBYTE`: Inline byte storage for lazy body decode.
  *   - `ERRORS`: Serialized TastyError cases accumulated during decode.
  *   - `PERMITS2`: permittedSubclassIds per symbol (added in minor=4).
  *   - `ANNOTS_`: annotation tycon fully-qualified name ids per symbol (added in minor=4).
  *   - `JAVAMETA`: javaMetadata accessFlags per symbol (added in minor=4).
  *   - `FQNIDX__`: full fullNameIndex (all keys including dual-index aliases) per symbol (added in minor=5).
  *   - `FQNMAP__`: unresolvedFullNameByNegId map (negId to fully-qualified name string for external annotation types) (added in minor=6).
  *   - `ERRORS` (re-encoded in minor=7): typed tagged format; each error written as 1-byte tag + UTF-8-length-prefixed fields, replacing the
  *     previous flat `err.toString` encoding.
  *   - `SUBCIDX_`: subclassIndex map (parent symIdx to list of child symIdx entries) (added in minor=8).
  *   - `COMPIDX_`: companionIndex map (symIdx to companion symIdx pairs) (added in minor=8).
  *   - `ERRORS` format change (minor=9): `ClasspathClosed` and `ClasspathBuilding` variants gained a `context: String` field. Old snapshots
  *     (minor=8 and below) serialize these as tag-only (no payload). Reader rejects minor=8 to force cold re-decode.
  *   - `ERRORS` format change (minor=10): each error tag is now a varint-length-prefixed UTF-8 string (the case `productPrefix`) instead of a
  *     single-byte ordinal. This makes the format stable against future enum variant additions (no ordinal-shift breakage). Old snapshots at
  *     minor=9 and below are rejected; the reader emits `TastyError.SnapshotVersionMismatch` to force cold re-decode.
  *   - `PLISTS__` section (minor=12, breaking bump): persists `Symbol.Method.paramListIds` per method symbol.
  *     A minor=11 snapshot lacks the section; warm-loading would always return `Chunk.empty` paramListIds,
  *     a fidelity regression. Reject to force cold re-decode.
  *
  * Versioning policy:
  *   - Major bump: invalidates all old snapshots (full re-decode + fresh write). Reader emits `TastyError.SnapshotVersionMismatch`.
  *   - Minor bump (non-breaking add-only): old snapshots load; new sections are absent and fall back to empty.
  *   - Minor bump (breaking in-record change): old snapshots must be rejected; reader emits `TastyError.SnapshotVersionMismatch`.
  *     minor=4 is a breaking bump because the SYMBOLS record layout is not changed but new relational sections are required for
  *     correct permittedSubclassIds / annotations / javaMetadata; a minor=3 snapshot must trigger cold re-decode.
  *   - Patch bump: format-stable.
  *
  * Digest algorithm: xxh64-custom 64-bit over jar central-directory (name, CRC32) walk on JVM (content-addressed); path-hash fallback on JS/Native (not content-addressed for in-place jar mutation; see PlatformDigest). See `DigestComputer`.
  *
  * Atomic-rename concurrent write strategy: write to `${digest}-${pid}-${nonce}.krfl`, fsync, rename to `${digest}.krfl`. Two concurrent
  * writers produce identical tmp files (decode is deterministic) and both attempt rename. The last rename wins; no corruption.
  */
object SnapshotFormat:

    /** 4-byte ASCII magic at file offset 0. */
    val magic: Array[Byte] = Array('K', 'R', 'F', 'L')

    /** Current format version. Major bumps invalidate old snapshots. */
    val majorVersion: Int = 1
    val minorVersion: Int = 13

    /** Maximum number of sections allowed in a snapshot header.
      *
      * A snapshot with more than this many sections is treated as corrupt. The value (256) is well above the current 15 defined sections and
      * leaves room for future additions without risking OOM from a maliciously large `sectionCount` field.
      */
    val maxSectionCount: Int = 256

    /** Size of the fixed-length file header in bytes (magic + version + flags + digest + reserved). */
    val headerSize: Int = 4 + 4 + 8 + 8 + 8

    /** Size of one section-index entry: name (8) + offset (8) + length (8). */
    val sectionIndexEntrySize: Int = 24

    /** Section IDs (exactly 8 ASCII bytes, zero-padded).
      *
      * Constraint: every name must be at most 8 bytes and must not contain any NUL byte (0x00). The 8-byte zero-pad encoding reads a
      * section name by stopping at the first NUL byte; a NUL embedded inside a name would silently truncate the name at that position,
      * causing a lookup miss. All names in this array satisfy these constraints; the `requireValidSectionNames` check below provides a
      * compile-time-equivalent guard.
      */
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
            "FQNMAP__",
            "SUBCIDX_",
            "COMPIDX_",
            "PLISTS__",
            "SRCPOS__"
        )

    /** Validate that every entry in `sectionNames` is at most 8 bytes and contains no NUL character.
      *
      * Section names are encoded as 8-byte zero-padded ASCII fields. If a name contains a NUL byte at position k, then
      * `readSectionName` will return only the first k characters, silently truncating the name. This method is called from the
      * `SnapshotFormat` companion object initializer so that an assertion fires at class-load time if a future code change introduces
      * an invalid section name.
      */
    def requireValidSectionNames(): Unit =
        sectionNames.foreach { name =>
            require(
                name.length <= 8,
                s"Section name '$name' exceeds 8-byte limit (length=${name.length})"
            )
            require(
                !name.exists(c => c == 0.toChar),
                s"Section name '$name' contains NUL byte (would cause silent truncation during read)"
            )
        }
    end requireValidSectionNames

    // Eagerly validate all section names at class-load time.
    requireValidSectionNames()

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

    /** Sections added in minor=4: permittedSubclassIds, annotations, javaMetadata. */
    val sectionPERMITS2: String = "PERMITS2"
    val sectionANNOTS: String   = "ANNOTS_"
    val sectionJAVAMETA: String = "JAVAMETA"

    /** Full fullNameIndex section (added in minor=5).
      *
      * Stores every key in the classpath fullNameIndex (including dual-index source fully-qualified name aliases for Object companions and opaque types) so that
      * warm-load lookups via source fully-qualified name work identically to cold-load.
      */
    val sectionFQNIDX: String = "FQNIDX__"

    /** Unresolved fully-qualified name map section (added in minor=6).
      *
      * Stores the negId -> fully-qualified name string map accumulated during cold decoding. On warm load this map is reconstructed so that
      * `typeFullNameString` can fall back to fully-qualified name strings for annotation types (e.g. `scala.deprecated`) that reference external libraries not on
      * the classpath. Without this section the warm-loaded classpath loses annotation fully-qualified name resolution for embedded-fixture loads on JS/Native.
      *
      * Layout: [4-byte count LE] then count entries each [4-byte negId LE][4-byte namePoolId LE].
      */
    val sectionFQNMAP: String = "FQNMAP__"

    /** Subclass index section (added in minor=8, breaking bump).
      *
      * Stores the inverted parent-types graph so that warm-loaded classpaths can answer
      * `classpath.directSubclassesOf`, `classpath.subclassesOf`, and `classpath.implementationsOf` without rebuilding
      * the index from scratch.
      *
      * Layout: [4-byte count LE] then count entries each
      *   [4-byte parentSymIdx LE][4-byte childCount LE][childCount x 4-byte childSymIdx LE].
      * All indices are positions in the symbols array (snapshot order).
      */
    val sectionSUBCIDX: String = "SUBCIDX_"

    /** Companion index section (added in minor=8, same breaking bump as SUBCIDX_).
      *
      * Stores the Class<->Object companion pairing built by ClasspathOrchestrator.buildCompanionIndex so
      * that warm-loaded classpaths can answer `classpath.companion(symbol)` without a fullNameIndex rescan.
      *
      * Layout: [4-byte count LE] then count entries each
      *   [4-byte symIdx LE][4-byte companionSymIdx LE].
      * All indices are positions in the symbols array (snapshot order).
      */
    val sectionCOMPIDX: String = "COMPIDX_"

    /** Method parameter-list partition section (added in minor=12).
      *
      * Persists `Symbol.Method.paramListIds: Chunk[Chunk[SymbolId]]` so warm-loaded classpaths
      * return the per-list-group grouping without re-decoding TASTy. Sparse keying by symbol
      * index; Methods with `paramListIds == Chunk.empty` are omitted (reader defaults to empty).
      * Distinguishes `Chunk.empty` (omitted) from `Chunk(Chunk.empty)` (encoded as
      * `listCount=1, innerCount=0`).
      *
      * Layout: [Int32-LE entryCount] then per entry:
      *   [Int32-LE symIdx][Int32-LE listCount]
      *   listCount x [Int32-LE innerCount][innerCount x Int32-LE symbolId.value]
      */
    val sectionPLISTS: String = "PLISTS__"

    /** Source-position section (added in minor=13).
      *
      * Persists `Symbol.sourcePosition` (sourceFile, line, column) per symbol so a warm-loaded
      * classpath restores positions a cold load holds. Without it warm-loaded symbols carry
      * `Maybe.Absent`, so document-symbol ranges and the derived `Indices.bySourceFile` would be
      * empty on a cache hit. `bySourceFile` is recomputed from these positions in `Classpath.make`,
      * the same recompute-on-construct pattern as `bySimpleName`, so it needs no section of its own.
      *
      * Layout: [Int32-LE entryCount] then per entry:
      *   [Int32-LE symIdx][Int32-LE sourceFileNameId][Int32-LE line][Int32-LE column]
      * Sparse: symbols with `sourcePosition == Maybe.Absent` are omitted.
      */
    val sectionSRCPOS: String = "SRCPOS__"

    /** minor=7 (breaking bump): ERRORS section re-encoded as typed tagged format instead of flat strings.
      *
      * Old snapshots (minor=6 and below) are already rejected by the existing minor-version guard; this constant documents the intent.
      */
    val minorVersion7ErrorsTyped: Int = 7

    /** minor=8 (breaking bump): SUBCIDX_ and COMPIDX_ sections added for subclassIndex and companionIndex persistence.
      *
      * Old snapshots (minor=7) lack these sections. Warm loads from minor=7 snapshots would return empty
      * subclassIndex and companionIndex, which is a fidelity regression. Rejecting them forces cold re-decode.
      */
    val minorVersion8IndexSections: Int = 8

    /** Write a little-endian 32-bit int at position `pos` in `bytes`. */
    def writeInt32LE(bytes: Array[Byte], pos: Int, value: Int): Unit =
        bytes(pos) = (value & 0xff).toByte
        bytes(pos + 1) = ((value >> 8) & 0xff).toByte
        bytes(pos + 2) = ((value >> 16) & 0xff).toByte
        bytes(pos + 3) = ((value >> 24) & 0xff).toByte
    end writeInt32LE

    /** Write a little-endian 64-bit long at position `pos` in `bytes`. */
    def writeInt64LE(bytes: Array[Byte], pos: Int, value: Long): Unit =
        bytes(pos) = (value & 0xff).toByte
        bytes(pos + 1) = ((value >> 8) & 0xff).toByte
        bytes(pos + 2) = ((value >> 16) & 0xff).toByte
        bytes(pos + 3) = ((value >> 24) & 0xff).toByte
        bytes(pos + 4) = ((value >> 32) & 0xff).toByte
        bytes(pos + 5) = ((value >> 40) & 0xff).toByte
        bytes(pos + 6) = ((value >> 48) & 0xff).toByte
        bytes(pos + 7) = ((value >> 56) & 0xff).toByte
    end writeInt64LE

    /** Read a little-endian 32-bit int from position `pos` in `bytes`. */
    def readInt32LE(bytes: Array[Byte], pos: Int): Int =
        (bytes(pos) & 0xff) |
            ((bytes(pos + 1) & 0xff) << 8) |
            ((bytes(pos + 2) & 0xff) << 16) |
            ((bytes(pos + 3) & 0xff) << 24)

    /** Read a little-endian 64-bit long from position `pos` in `bytes`. */
    def readInt64LE(bytes: Array[Byte], pos: Int): Long =
        (bytes(pos) & 0xffL) |
            ((bytes(pos + 1) & 0xffL) << 8) |
            ((bytes(pos + 2) & 0xffL) << 16) |
            ((bytes(pos + 3) & 0xffL) << 24) |
            ((bytes(pos + 4) & 0xffL) << 32) |
            ((bytes(pos + 5) & 0xffL) << 40) |
            ((bytes(pos + 6) & 0xffL) << 48) |
            ((bytes(pos + 7) & 0xffL) << 56)

    /** Write a zero-padded 8-byte section name. */
    def writeSectionName(bytes: Array[Byte], pos: Int, name: String): Unit =
        var i = 0
        while i < 8 && i < name.length do
            bytes(pos + i) = name.charAt(i).toByte
            i += 1
        while i < 8 do
            bytes(pos + i) = 0
            i += 1
    end writeSectionName

    /** Read a zero-padded 8-byte section name. */
    def readSectionName(bytes: Array[Byte], pos: Int): String =
        val sb = new StringBuilder(8)
        var i  = 0
        while i < 8 && bytes(pos + i) != 0 do
            sb.append(bytes(pos + i).toChar)
            i += 1
        sb.toString
    end readSectionName

    /** Encode a String as UTF-8 bytes (for serialization). */
    def encodeString(s: String): Array[Byte] = s.getBytes(java.nio.charset.StandardCharsets.UTF_8)

    /** Decode UTF-8 bytes as a String. */
    def decodeString(bytes: Array[Byte], offset: Int, length: Int): String =
        new String(bytes, offset, length, java.nio.charset.StandardCharsets.UTF_8)

end SnapshotFormat
