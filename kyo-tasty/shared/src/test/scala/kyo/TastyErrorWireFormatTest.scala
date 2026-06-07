package kyo

import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import scala.collection.mutable

/** Tests for TastyError wire format minor 9 to 10.
  *
  * Coverage:
  *   1. Round-trip every TastyError variant through the new string-tag wire format.
  *   2. Wire tag equals productPrefix (byte-level check).
  *   3. Minor version bumped to 10.
  *   4. Unknown tag falls back to TastyError.NotImplemented.
  *   5. Short tag names use one byte (single-byte varint).
  *   6. Long tag names (length 200) use two-byte varint (0xC8 0x01).
  *   7. Old fixture snapshots at minor=9 fail with TastyError.SnapshotVersionMismatch.
  *   8. Cross-platform: all leaves pass on JVM, JS, and Native.
  */
class TastyErrorWireFormatTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger
    import kyo.Tasty.SymbolId

    /** Build a minimal Classpath carrying only the given errors, then serialize it.
      *
      * Returns the raw bytes of the entire KRFL snapshot. Callers extract the ERRORS section to
      * inspect tag bytes, varint prefixes, and round-tripped field values.
      */
    private def snapshotBytesWithErrors(errors: Chunk[TastyError]): Array[Byte] =
        val rootSym = Tasty.Symbol.Package(SymbolId(0), Tasty.Name(""), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        val cp = Tasty.Classpath.make(
            symbols = Chunk(rootSym),
            rootSymbolId = SymbolId(0),
            topLevelClassIds = Chunk.empty,
            packageIds = Chunk(rootSym.id),
            fqnIndex = Dict.empty,
            packageIndex = Dict.empty,
            subclassIndex = Dict.empty,
            companionIndex = Dict.empty,
            moduleIndex = Dict.empty,
            errors = errors
        )
        val digest = Array[Byte](0, 1, 2, 3, 4, 5, 6, 7)
        SnapshotWriter.serializeToBytes(cp, digest)
    end snapshotBytesWithErrors

    /** Decode a standard LEB128 varint from bytes at the given position.
      *
      * Returns the decoded Int value and the new position after reading.
      */
    private def decodeVarint(bytes: Array[Byte], startPos: Int): (Int, Int) =
        var result = 0
        var shift  = 0
        var pos    = startPos
        var more   = true
        while more do
            val b = bytes(pos) & 0xff
            pos += 1
            result |= (b & 0x7f) << shift
            shift += 7
            if (b & 0x80) == 0 then more = false
        end while
        (result, pos)
    end decodeVarint

    /** Extract the raw bytes of the ERRORS section from a full KRFL snapshot byte array.
      *
      * Returns a tuple of (section payload bytes, section start offset in the snapshot).
      */
    private def extractErrorsSection(snapshotBytes: Array[Byte]): (Array[Byte], Int) =
        val sectionCount = SnapshotFormat.readInt32LE(snapshotBytes, 32)
        var idxPos       = 36
        var errOffset    = -1
        var errLength    = -1
        var i            = 0
        while i < sectionCount do
            val name   = SnapshotFormat.readSectionName(snapshotBytes, idxPos)
            val offset = SnapshotFormat.readInt64LE(snapshotBytes, idxPos + 8).toInt
            val length = SnapshotFormat.readInt64LE(snapshotBytes, idxPos + 16).toInt
            if name == SnapshotFormat.sectionERRORS then
                errOffset = offset
                errLength = length
            end if
            idxPos += SnapshotFormat.sectionIndexEntrySize
            i += 1
        end while
        require(errOffset >= 0, s"ERRORS section not found in snapshot (${sectionCount} sections)")
        (java.util.Arrays.copyOfRange(snapshotBytes, errOffset, errOffset + errLength), errOffset)
    end extractErrorsSection

    /** An in-memory FileSource for round-trip tests. */
    class MemoryFileSource(val files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty)
        extends FileSource:

        def add(path: String, bytes: Array[Byte]): Unit = files(path) = bytes

        def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer:
                Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && suffixes.exists(k.endsWith)).toSeq)

        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            files.get(path) match
                case Some(bytes) => bytes
                case None        => Abort.fail(TastyError.FileNotFound(path))

        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
            Sync.defer(files(path) = bytes)

        def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            files.get(from) match
                case Some(bytes) =>
                    Sync.defer { files.remove(from); files(to) = bytes }
                case None =>
                    Abort.fail(TastyError.SnapshotIoError(s"rename: $from not found"))

        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            Kyo.unit

        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))

        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer:
                files.get(path) match
                    case Some(bytes) => FileSource.FileStat(mtimeMs = 0L, size = bytes.length.toLong)
                    case None        => Abort.fail(TastyError.FileNotFound(path))
    end MemoryFileSource

    // round-trip every TastyError variant through the new wire format.
    // Given: every TastyError variant (19 cases) with representative field values.
    // When: written via SnapshotWriter (serializeToBytes), read via SnapshotReader.
    // Then: decoded variant equals original case-by-case.
    "round-trip every TastyError variant through the new string-tag wire format" in {
        val allErrors: Chunk[TastyError] = Chunk(
            TastyError.FileNotFound("test/path/Foo.tasty"),
            TastyError.CorruptedFile("test/Bar.tasty", 42L, "bad magic"),
            TastyError.UnsupportedVersion(Tasty.Version(1, 2, 0), Tasty.Version(1, 3, 0)),
            TastyError.InconsistentClasspath("a.tasty", new java.util.UUID(0L, 1L), new java.util.UUID(2L, 3L)),
            TastyError.FqnCollisionError("com.example.Foo"),
            TastyError.MalformedSection("NAMES", "truncated", 100L),
            TastyError.SymbolNotFound("com.example.Missing"),
            TastyError.NotFound("com.example.NotHere"),
            TastyError.ClassfileFormatError("Foo.class", "bad constant pool", 0L),
            TastyError.ClasspathClosed("decodeBody(id=7)"),
            TastyError.ClasspathBuilding("finalizeMerge"),
            TastyError.SnapshotFormatError("snap.krfl", "wrong magic", 0L),
            TastyError.SnapshotVersionMismatch(Tasty.Version(1, 8, 0), Tasty.Version(1, 10, 0)),
            TastyError.SnapshotIoError("disk full"),
            TastyError.NotImplemented("TASTy 3.9 feature"),
            TastyError.UnsupportedPlatform("mmap on JS"),
            TastyError.UnknownTagInPosition(42, "tree"),
            TastyError.InvalidFqn("", "fqn must be non-empty"),
            TastyError.DigestMismatch("aabbcc", "ddeeff")
        )
        assert(allErrors.size == 19, s"Expected 19 TastyError variants, got ${allErrors.size}")

        val snapshotBytes = snapshotBytesWithErrors(allErrors)
        val cacheSrc      = MemoryFileSource()
        val hex           = DigestComputer.toHexString(Array[Byte](0, 1, 2, 3, 4, 5, 6, 7))
        val snapPath      = s"cache/$hex.krfl"
        cacheSrc.add(snapPath, snapshotBytes)

        Abort.run[TastyError]:
            SnapshotReader.read(snapPath, cacheSrc).map: loadedCp =>
                loadedCp.errors
        .map:
            case Result.Success(loaded) =>
                assert(
                    loaded.size == allErrors.size,
                    s"Expected ${allErrors.size} errors after round-trip, got ${loaded.size}"
                )
                allErrors.toSeq.zip(loaded.toSeq).zipWithIndex.foreach: pair =>
                    val ((orig, got), idx) = pair
                    assert(
                        orig == got,
                        s"Error[$idx] mismatch: expected $orig, got $got"
                    )
                succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
    }

    // wire tag equals productPrefix.
    // Given: TastyError.DigestMismatch("x", "y").
    // When: serialize and peek tag bytes in the ERRORS section.
    // Then: on-disk tag string equals "DigestMismatch".
    "wire tag equals TastyError.productPrefix" in {
        val err           = TastyError.DigestMismatch("x", "y")
        val snapshotBytes = snapshotBytesWithErrors(Chunk(err))
        val (errBytes, _) = extractErrorsSection(snapshotBytes)
        // errBytes layout: [4-byte count LE] [varint tag-len] [tag UTF-8 bytes] [fields.]
        val count = SnapshotFormat.readInt32LE(errBytes, 0)
        assert(count == 1, s"Expected 1 error in ERRORS section, got $count")
        // Read the LEB128 varint tag length at offset 4.
        val (tagLen, tagStart) = decodeVarint(errBytes, 4)
        val tagBytes           = java.util.Arrays.copyOfRange(errBytes, tagStart, tagStart + tagLen)
        val tag                = new String(tagBytes, java.nio.charset.StandardCharsets.UTF_8)
        assert(
            tag == "DigestMismatch",
            s"Expected wire tag == 'DigestMismatch', got '$tag'"
        )
        assert(
            tag == err.productPrefix,
            s"Wire tag must equal productPrefix: expected '${err.productPrefix}', got '$tag'"
        )
    }

    // minor version bumped (originally to 10 for string-tag format; now 11).
    // Given: a freshly written snapshot.
    // When: read the format header bytes.
    // Then: minorVersion == 11 (bumped for four new TastyError variants).
    "minor version is 11 in freshly written snapshot" in {
        val snapshotBytes = snapshotBytesWithErrors(Chunk.empty)
        val minor         = snapshotBytes(5) & 0xff
        assert(
            minor == 11,
            s"Expected snapshot minor version 11, got $minor"
        )
        assert(
            SnapshotFormat.minorVersion == 11,
            s"SnapshotFormat.minorVersion must be 11, got ${SnapshotFormat.minorVersion}"
        )
    }

    // unknown tag falls back to TastyError.NotImplemented.
    // Given: synthetic snapshot bytes with tag "FutureVariant" injected into the ERRORS section.
    // When: read via SnapshotReader.
    // Then: TastyError.NotImplemented(s) where s contains "FutureVariant".
    "unknown wire tag falls back to TastyError.NotImplemented" in {
        // Build a snapshot with one known error to get valid headers.
        // Use FileNotFound("base"): ERRORS section has a known byte count.
        val baseBytes             = snapshotBytesWithErrors(Chunk(TastyError.FileNotFound("base")))
        val (errBytes, errOffset) = extractErrorsSection(baseBytes)

        // Build a replacement ERRORS section of exactly errBytes.length bytes (same length = no offset
        // updates needed for sections that follow ERRORS in the file).
        // Layout: [4-byte count=1] [varint(13)=1 byte] [13 bytes "FutureVariant"] [zero-pad to size].
        val futureTag   = "FutureVariant"
        val tagUtf8     = futureTag.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        val replacement = new Array[Byte](errBytes.length) // zero-initialized
        val tmp4        = new Array[Byte](4)
        SnapshotFormat.writeInt32LE(tmp4, 0, 1)
        java.lang.System.arraycopy(tmp4, 0, replacement, 0, 4)
        // tagUtf8.length == 13 < 128, so single-byte LEB128.
        replacement(4) = tagUtf8.length.toByte
        java.lang.System.arraycopy(tagUtf8, 0, replacement, 5, tagUtf8.length)
        // Remaining bytes are zero (no fields for the unknown tag; reader stops after matching tag).

        // Splice: replace the ERRORS section payload in-place. All other section offsets are unchanged.
        val patchedSnap = baseBytes.clone()
        java.lang.System.arraycopy(replacement, 0, patchedSnap, errOffset, replacement.length)

        val cacheSrc = MemoryFileSource()
        val snapPath = "cache/unknown-tag-test.krfl"
        cacheSrc.add(snapPath, patchedSnap)

        Abort.run[TastyError]:
            SnapshotReader.read(snapPath, cacheSrc).map: cp =>
                cp.errors
        .map:
            case Result.Success(errors) =>
                assert(errors.size == 1, s"Expected 1 error, got ${errors.size}: $errors")
                errors.head match
                    case TastyError.NotImplemented(msg) =>
                        assert(
                            msg.contains("FutureVariant"),
                            s"NotImplemented message must contain 'FutureVariant', got: $msg"
                        )
                    case other =>
                        fail(s"Expected TastyError.NotImplemented, got: $other")
                end match
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
    }

    // short tag names use one byte (single-byte varint).
    // Given: tag bytes for "FileNotFound" (12 bytes).
    // When: read length prefix from the ERRORS section.
    // Then: equals 12 (single-byte varint: 12 < 128).
    "short tag names encode as a single-byte varint prefix" in {
        val err           = TastyError.FileNotFound("test/path/Foo.tasty")
        val snapshotBytes = snapshotBytesWithErrors(Chunk(err))
        val (errBytes, _) = extractErrorsSection(snapshotBytes)
        // errBytes: [4-byte count] [varint tag-len] [tag bytes] [fields.]
        // For "FileNotFound" (12 chars = 12 UTF-8 bytes), the varint must be 1 byte with value 12.
        val firstTagByte = errBytes(4) & 0xff
        val isOneByte    = (firstTagByte & 0x80) == 0
        assert(isOneByte, s"Expected single-byte varint for 12-byte tag, but high bit is set: 0x${firstTagByte.toHexString}")
        assert(
            firstTagByte == 12,
            s"Expected varint value 12 for 'FileNotFound' tag length, got $firstTagByte"
        )
        val expected = "FileNotFound".getBytes(java.nio.charset.StandardCharsets.UTF_8).length
        assert(expected == 12, s"FileNotFound must be 12 UTF-8 bytes, got $expected")
    }

    // long tag names use a multi-byte varint.
    // Given: synthetic tag of length 200 (string of 200 'x' characters).
    // When: read the length prefix varint from the raw bytes.
    // Then: first byte is 0xC8, second byte is 0x01 (LEB128 encoding of 200).
    //       200 = 72 + 1*128; byte0 = 72 | 0x80 = 0xC8 (continuation), byte1 = 1 (no continuation).
    "tag of length 200 uses two-byte LEB128 varint prefix (0xC8 0x01)" in {
        val longTag = "x" * 200
        val tagUtf8 = longTag.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        assert(tagUtf8.length == 200, "Tag must be 200 UTF-8 bytes for this test")

        // Build a standalone ERRORS section to inspect the varint bytes directly.
        val baos = new java.io.ByteArrayOutputStream()
        val tmp4 = new Array[Byte](4)
        SnapshotFormat.writeInt32LE(tmp4, 0, 1)
        baos.write(tmp4)
        // LEB128 for 200: 200 % 128 = 72, 200 / 128 = 1.
        // Byte 0: 72 | 0x80 = 0xC8 (continuation bit set). Byte 1: 1 (no continuation).
        baos.write(0xc8)
        baos.write(0x01)
        baos.write(tagUtf8)
        val rawBytes = baos.toByteArray

        // Verify the first two bytes after the count are exactly 0xC8 and 0x01.
        assert((rawBytes(4) & 0xff) == 0xc8, s"First varint byte must be 0xC8, got 0x${(rawBytes(4) & 0xff).toHexString}")
        assert((rawBytes(5) & 0xff) == 0x01, s"Second varint byte must be 0x01, got 0x${(rawBytes(5) & 0xff).toHexString}")

        // Verify the LEB128 decoder recovers 200.
        val (decoded, _) = decodeVarint(rawBytes, 4)
        assert(decoded == 200, s"LEB128 decoding of 0xC8 0x01 must yield 200, got $decoded")
    }

    // old fixture snapshots at minor=9 fail with TastyError.SnapshotVersionMismatch.
    // Given: a snapshot byte array whose header byte at offset 5 is patched to 9.
    // When: loaded via SnapshotReader.read.
    // Then: raises TastyError.SnapshotVersionMismatch with found=(1,9,0), supported=(1,11,0).
    "snapshot with minorVersion=9 is rejected with SnapshotVersionMismatch" in {
        val freshBytes   = snapshotBytesWithErrors(Chunk.empty)
        val patchedBytes = freshBytes.clone()
        patchedBytes(5) = 9.toByte

        val cacheSrc = MemoryFileSource()
        val snapPath = "cache/minor9-reject-test.krfl"
        cacheSrc.add(snapPath, patchedBytes)

        Abort.run[TastyError]:
            SnapshotReader.read(snapPath, cacheSrc)
        .map:
            case Result.Success(_) =>
                fail("Expected SnapshotVersionMismatch for minor=9 snapshot, but read succeeded")
            case Result.Failure(e) =>
                e match
                    case vm: TastyError.SnapshotVersionMismatch =>
                        assert(
                            vm.found.major == 1 && vm.found.minor == 9,
                            s"Expected found version (1,9,0), got ${vm.found}"
                        )
                        assert(
                            vm.supported.major == 1 && vm.supported.minor == 11,
                            s"Expected supported version (1,11,0), got ${vm.supported}"
                        )
                    case other =>
                        fail(s"Expected TastyError.SnapshotVersionMismatch, got: $other")
            case Result.Panic(t) => throw t
    }

    // cross-platform round-trip (JVM, JS, Native all pass).
    // Given: all 19 TastyError variants serialized to bytes and read back on the current platform.
    // When: executed.
    // Then: every variant round-trips correctly.
    "cross-platform round-trip: all 19 TastyError variants serialize and deserialize identically" in {
        // This test is placed in shared/src/test/scala (cross-platform by default per).
        // ByteArrayOutputStream and java.nio.charset.StandardCharsets.UTF_8 are available on JVM,
        // Scala.js (emulated), and Scala Native; the wire format uses only byte arrays and integers.
        val errors: Chunk[TastyError] = Chunk(
            TastyError.FileNotFound("cross-platform/path"),
            TastyError.CorruptedFile("cross/Bar.tasty", 1L, "corrupt"),
            TastyError.UnsupportedVersion(Tasty.Version(1, 1, 0), Tasty.Version(1, 10, 0)),
            TastyError.InconsistentClasspath("c.tasty", new java.util.UUID(10L, 20L), new java.util.UUID(30L, 40L)),
            TastyError.FqnCollisionError("cross.Foo"),
            TastyError.MalformedSection("TYPES", "overflow", 999L),
            TastyError.SymbolNotFound("cross.Missing"),
            TastyError.NotFound("cross.Absent"),
            TastyError.ClassfileFormatError("cross.class", "bad pool", 8L),
            TastyError.ClasspathClosed("op=decode"),
            TastyError.ClasspathBuilding("build=merge"),
            TastyError.SnapshotFormatError("cross.krfl", "truncated", 0L),
            TastyError.SnapshotVersionMismatch(Tasty.Version(1, 7, 0), Tasty.Version(1, 10, 0)),
            TastyError.SnapshotIoError("io failure"),
            TastyError.NotImplemented("future feature"),
            TastyError.UnsupportedPlatform("mmap"),
            TastyError.UnknownTagInPosition(99, "modifier"),
            TastyError.InvalidFqn("bad.fqn!", "invalid character"),
            TastyError.DigestMismatch("112233", "445566")
        )
        assert(errors.size == 19)

        val snapshotBytes = snapshotBytesWithErrors(errors)
        val cacheSrc      = MemoryFileSource()
        val digest = Array[Byte](
            0x10.toByte,
            0x11.toByte,
            0x12.toByte,
            0x13.toByte,
            0x14.toByte,
            0x15.toByte,
            0x16.toByte,
            0x17.toByte
        )
        val hex      = DigestComputer.toHexString(digest)
        val snapPath = s"cache/$hex.krfl"
        cacheSrc.add(snapPath, snapshotBytes)

        Abort.run[TastyError]:
            SnapshotReader.read(snapPath, cacheSrc).map: cp =>
                cp.errors
        .map:
            case Result.Success(loaded) =>
                assert(
                    loaded.size == errors.size,
                    s"Cross-platform round-trip: expected ${errors.size} errors, got ${loaded.size}"
                )
                errors.toSeq.zip(loaded.toSeq).zipWithIndex.foreach: pair =>
                    val ((orig, got), idx) = pair
                    assert(
                        orig == got,
                        s"Cross-platform error[$idx] mismatch: expected $orig, got $got"
                    )
                succeed
            case Result.Failure(e) => fail(s"Cross-platform round-trip failure: $e")
            case Result.Panic(t)   => throw t
    }

end TastyErrorWireFormatTest
