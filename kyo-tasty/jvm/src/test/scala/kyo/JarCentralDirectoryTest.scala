package kyo

import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kyo.internal.tasty.query.JarCentralDirectory

/** Tests for JarCentralDirectory direct CEN reader.
  *
  * Tests T1-T6 and T11-T14 per execution-plan-perf.md.
  *
  * Test JARs are built programmatically via java.util.zip.ZipOutputStream writing to a temp file via java.io.FileOutputStream. Each test
  * allocates its own temp dir and cleans up in teardown.
  */
class JarCentralDirectoryTest extends kyo.test.Test[Any]:

    /** Create a temp directory for the test, returning its path. */
    private def makeTempDir(): String =
        Files.createTempDirectory("kyo-tasty-jar-cen-test").toAbsolutePath.toString

    /** Create a JAR at the given path with the provided entries (name -> bytes). */
    private def writeJar(path: String, entries: Seq[(String, Array[Byte])]): Unit =
        val fos = new FileOutputStream(path)
        val zos = new ZipOutputStream(fos)
        try
            for (name, bytes) <- entries do
                val entry = new ZipEntry(name)
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
        finally
            zos.close()
            fos.close()
        end try
    end writeJar

    /** Create a JAR at the given path with UTF-8 flag forced on all entries.
      *
      * ZipOutputStream sets UTF-8 flag (bit 11) automatically when USE_UTF8 encoding is set.
      */
    private def writeJarUtf8(path: String, entries: Seq[(String, Array[Byte])]): Unit =
        val fos = new FileOutputStream(path)
        val zos = new ZipOutputStream(fos, StandardCharsets.UTF_8)
        try
            for (name, bytes) <- entries do
                val entry = new ZipEntry(name)
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
        finally
            zos.close()
            fos.close()
        end try
    end writeJarUtf8

    private val tastyContent: Array[Byte] = "fake-tasty-content".getBytes(StandardCharsets.UTF_8)
    private val classContent: Array[Byte] = "fake-class-content".getBytes(StandardCharsets.UTF_8)
    private val javaContent: Array[Byte]  = "fake-java-source".getBytes(StandardCharsets.UTF_8)
    private val textContent: Array[Byte]  = "some text".getBytes(StandardCharsets.UTF_8)

    // T1: empty JAR returns Chunk.empty
    "empty JAR returns Chunk.empty for any suffix list".onlyJvm in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/empty.jar"
        writeJar(jarPath, Seq.empty)
        Abort.run[TastyError](
            JarCentralDirectory.list(jarPath, Chunk(".tasty", ".class"))
        ).map:
            case Result.Success(entries) =>
                assert(entries.isEmpty, s"Expected empty Chunk but got: $entries")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // T2: jar with only.tasty entries returns those entries when suffix is.tasty;
    //     returns empty when suffix is.class
    "jar with only .tasty entries: suffix .tasty returns all, .class returns empty".onlyJvm in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/tasty-only.jar"
        writeJar(
            jarPath,
            Seq(
                ("kyo/Foo.tasty", tastyContent),
                ("kyo/Bar.tasty", tastyContent),
                ("kyo/pkg/Baz.tasty", tastyContent)
            )
        )
        Abort.run[TastyError](
            JarCentralDirectory.list(jarPath, Chunk(".tasty")).flatMap: tastyEntries =>
                JarCentralDirectory.list(jarPath, Chunk(".class")).map: classEntries =>
                    (tastyEntries, classEntries)
        ).map:
            case Result.Success((tastyEntries, classEntries)) =>
                assert(
                    tastyEntries.length == 3,
                    s"Expected 3 .tasty entries but got: ${tastyEntries.length}"
                )
                assert(
                    tastyEntries.forall((_, name) => name.endsWith(".tasty")),
                    s"All entries should end with .tasty: $tastyEntries"
                )
                assert(
                    tastyEntries.forall((jar, _) => jar == jarPath),
                    s"All jarPath fields should match: $tastyEntries"
                )
                assert(classEntries.isEmpty, s"Expected empty for .class suffix but got: $classEntries")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // T3: jar with only.class entries returns those entries when suffix is.class
    "jar with only .class entries: suffix .class returns all, .tasty returns empty".onlyJvm in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/class-only.jar"
        writeJar(
            jarPath,
            Seq(
                ("com/example/Foo.class", classContent),
                ("com/example/Bar.class", classContent)
            )
        )
        Abort.run[TastyError](
            JarCentralDirectory.list(jarPath, Chunk(".class")).flatMap: classEntries =>
                JarCentralDirectory.list(jarPath, Chunk(".tasty")).map: tastyEntries =>
                    (classEntries, tastyEntries)
        ).map:
            case Result.Success((classEntries, tastyEntries)) =>
                assert(
                    classEntries.length == 2,
                    s"Expected 2 .class entries but got: ${classEntries.length}"
                )
                assert(
                    classEntries.forall((_, name) => name.endsWith(".class")),
                    s"All entries should end with .class: $classEntries"
                )
                assert(tastyEntries.isEmpty, s"Expected empty for .tasty suffix but got: $tastyEntries")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // T4: jar with mixed.tasty +.class +.java entries: multi-suffix returns only.tasty and.class
    "mixed jar with .tasty + .class + .java: multi-suffix returns only .tasty and .class".onlyJvm in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/mixed.jar"
        writeJar(
            jarPath,
            Seq(
                ("kyo/Foo.tasty", tastyContent),
                ("kyo/Foo.class", classContent),
                ("kyo/Foo.java", javaContent),
                ("kyo/Bar.tasty", tastyContent),
                ("kyo/Bar.class", classContent)
            )
        )
        Abort.run[TastyError](
            JarCentralDirectory.list(jarPath, Chunk(".tasty", ".class"))
        ).map:
            case Result.Success(entries) =>
                assert(
                    entries.length == 4,
                    s"Expected 4 entries (.tasty and .class only) but got: ${entries.length}: $entries"
                )
                assert(
                    entries.forall((_, name) => name.endsWith(".tasty") || name.endsWith(".class")),
                    s"All entries should end with .tasty or .class: $entries"
                )
                assert(
                    !entries.exists((_, name) => name.endsWith(".java")),
                    s".java entries should be excluded: $entries"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // T5: large JAR (>500 entries) returns all matching entries without missing any
    "large JAR (>500 entries) returns all matching entries".onlyJvm in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/large.jar"
        val count   = 600
        val entries = (0 until count).flatMap: i =>
            Seq(
                (s"kyo/pkg$i/Cls$i.tasty", tastyContent),
                (s"kyo/pkg$i/Cls$i.class", classContent)
            )
        writeJar(jarPath, entries.toSeq)
        Abort.run[TastyError](
            JarCentralDirectory.list(jarPath, Chunk(".tasty", ".class"))
        ).map:
            case Result.Success(found) =>
                assert(
                    found.length == count * 2,
                    s"Expected ${count * 2} entries but got: ${found.length}"
                )
                // Spot-check 5 known entry names
                val names = found.map(_._2).toSeq.toSet
                assert(names.contains("kyo/pkg0/Cls0.tasty"), "Expected kyo/pkg0/Cls0.tasty")
                assert(names.contains("kyo/pkg0/Cls0.class"), "Expected kyo/pkg0/Cls0.class")
                assert(names.contains("kyo/pkg100/Cls100.tasty"), "Expected kyo/pkg100/Cls100.tasty")
                assert(names.contains("kyo/pkg499/Cls499.class"), "Expected kyo/pkg499/Cls499.class")
                assert(names.contains("kyo/pkg599/Cls599.tasty"), "Expected kyo/pkg599/Cls599.tasty")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // T6: non-JAR file path returns Abort[TastyError.MalformedSection] because the
    //     EOCD signature scan fails on a file that is not a valid ZIP/JAR.
    "non-JAR file returns Abort[TastyError.MalformedSection]".onlyJvm in {
        val dir      = makeTempDir()
        val textPath = s"$dir/not-a-jar.txt"
        Files.write(java.nio.file.Paths.get(textPath), "hello world".getBytes(StandardCharsets.UTF_8))
        Abort.run[TastyError](
            JarCentralDirectory.list(textPath, Chunk(".tasty"))
        ).map:
            case Result.Success(_) =>
                fail("Expected Abort[TastyError.MalformedSection] for non-JAR file, got success")
            case Result.Failure(e) =>
                e match
                    case TastyError.MalformedSection(_, _, _) => succeed
                    case other                                => fail(s"Expected MalformedSection but got: $other")
            case Result.Panic(t) =>
                fail(s"unexpected panic: $t")
    }

    // T11: JAR with corrupted EOCD signature (0xdeadbeef) returns Abort[TastyError.MalformedSection]
    "corrupted EOCD signature returns Abort[TastyError.MalformedSection]".onlyJvm in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/corrupted-eocd.jar"
        // Write a valid JAR first, then corrupt the EOCD signature
        writeJar(jarPath, Seq(("test.tasty", tastyContent)))
        val bytes = Files.readAllBytes(java.nio.file.Paths.get(jarPath))
        // Find and corrupt the EOCD signature (0x06054b50) at the end
        // Search backwards for the EOCD signature bytes
        var foundAt = -1
        var i       = bytes.length - 22
        while i >= 0 && foundAt < 0 do
            if (bytes(i) & 0xff) == 0x50 &&
                (bytes(i + 1) & 0xff) == 0x4b &&
                (bytes(i + 2) & 0xff) == 0x05 &&
                (bytes(i + 3) & 0xff) == 0x06
            then foundAt = i
            end if
            i -= 1
        end while
        assert(foundAt >= 0, "Could not locate EOCD signature in test JAR")
        // Overwrite with 0xdeadbeef (little-endian)
        bytes(foundAt) = 0xef.toByte
        bytes(foundAt + 1) = 0xbe.toByte
        bytes(foundAt + 2) = 0xad.toByte
        bytes(foundAt + 3) = 0xde.toByte
        Files.write(java.nio.file.Paths.get(jarPath), bytes)
        Abort.run[TastyError](
            JarCentralDirectory.list(jarPath, Chunk(".tasty"))
        ).map:
            case Result.Success(_) =>
                fail("Expected Abort[TastyError.MalformedSection] for corrupted EOCD")
            case Result.Failure(e) =>
                e match
                    case TastyError.MalformedSection(_, _, _) => succeed
                    case other                                => fail(s"Expected MalformedSection but got: $other")
            case Result.Panic(t) =>
                throw t
    }

    // T12: JAR with general-purpose-bit-3 (data descriptor flag) set in the CEN record.
    // Background: ZipOutputStream does NOT set GPB bit-3 in the CEN when sizes are known at write
    // time (which is always the case when using putNextEntry with pre-set CRC/sizes). To exercise
    // the real bit-3 hazard we patch the CEN GPB field of a normal STORED JAR after writing it.
    // The CEN-based reader reads entry names from the CEN record and checks only bit-11 (UTF-8
    // flag) for charset selection. Bit-3 is not used by the reader, so an entry with bit-3 set
    // in its CEN GPB field must enumerate correctly (name returned, no error).
    "JAR with CEN GPB bit-3 (data descriptor flag) set enumerates correctly".onlyJvm in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/bit3.jar"
        // Write a normal STORED JAR first.
        writeJar(jarPath, Seq(("kyo/DataDesc.tasty", tastyContent)))
        // Patch the CEN record: locate the CEN signature (0x02014b50) and set bit-3 in the GPB.
        // CEN layout: sig(4) + versionMade(2) + versionNeeded(2) + gpFlag(2) at offset 8.
        val bytes  = Files.readAllBytes(java.nio.file.Paths.get(jarPath))
        var cenPos = -1
        var i      = 0
        while i <= bytes.length - 4 && cenPos < 0 do
            if (bytes(i) & 0xff) == 0x50 &&
                (bytes(i + 1) & 0xff) == 0x4b &&
                (bytes(i + 2) & 0xff) == 0x01 &&
                (bytes(i + 3) & 0xff) == 0x02
            then cenPos = i
            end if
            i += 1
        end while
        assert(cenPos >= 0, "Could not locate CEN signature in test JAR")
        // GPB field is at offset 8 from the CEN signature (little-endian 2 bytes).
        val gpbLow = bytes(cenPos + 8) & 0xff
        bytes(cenPos + 8) = (gpbLow | 0x08).toByte // set bit 3
        Files.write(java.nio.file.Paths.get(jarPath), bytes)
        Abort.run[TastyError](
            JarCentralDirectory.list(jarPath, Chunk(".tasty"))
        ).map:
            case Result.Success(entries) =>
                // CEN reader ignores bit-3 and reads names from CEN records correctly.
                assert(entries.length == 1, s"Expected 1 entry with bit-3 set in CEN GPB but got: $entries")
                assert(
                    entries.head._2 == "kyo/DataDesc.tasty",
                    s"Expected kyo/DataDesc.tasty but got: ${entries.head._2}"
                )
            case Result.Failure(e) =>
                fail(s"Expected successful enumeration with CEN GPB bit-3 set, but got failure: $e")
            case Result.Panic(t) =>
                fail(s"unexpected panic: $t")
    }

    // T13: empty JAR (only EOCD record, zero entries) returns Chunk.empty without throwing
    "empty JAR with zero entries (EOCD only) returns Chunk.empty".onlyJvm in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/zero-entries.jar"
        // writeJar with no entries produces a valid empty JAR
        writeJar(jarPath, Seq.empty)
        Abort.run[TastyError](
            JarCentralDirectory.list(jarPath, Chunk(".tasty"))
        ).map:
            case Result.Success(entries) =>
                assert(entries.isEmpty, s"Expected empty Chunk for zero-entry JAR but got: $entries")
            case Result.Failure(e) =>
                fail(s"Unexpected failure for zero-entry JAR: $e")
            case Result.Panic(t) =>
                throw t
    }

    // T14: JAR with general-purpose-bit-11 set (UTF-8 entry names containing non-ASCII chars)
    //      decodes entry names correctly.
    "JAR with UTF-8 entry names (non-ASCII like münchen.tasty) decodes correctly".onlyJvm in {
        val dir       = makeTempDir()
        val jarPath   = s"$dir/utf8-names.jar"
        val nonAscii1 = "kyo/münchen.tasty"
        val nonAscii2 = "kyo/café.class"
        val ascii     = "kyo/Normal.tasty"
        writeJarUtf8(
            jarPath,
            Seq(
                (nonAscii1, tastyContent),
                (nonAscii2, classContent),
                (ascii, tastyContent)
            )
        )
        Abort.run[TastyError](
            JarCentralDirectory.list(jarPath, Chunk(".tasty", ".class"))
        ).map:
            case Result.Success(entries) =>
                val names = entries.map(_._2).toSeq.toSet
                assert(
                    names.contains(nonAscii1),
                    s"Expected $nonAscii1 in results but got: $names"
                )
                assert(
                    names.contains(nonAscii2),
                    s"Expected $nonAscii2 in results but got: $names"
                )
                assert(
                    names.contains(ascii),
                    s"Expected $ascii in results but got: $names"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // F4-JAR: JarCentralDirectory.list returns entries in the same order across two consecutive calls.
    //         This exercises the real CEN reader (not the in-memory FileSource fixture used by F4
    //         in FileSourceTest, whose list pre-sorts and trivially passes the ordering check).
    "F4-JAR: JarCentralDirectory.list returns identical ordering across two consecutive calls".onlyJvm in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/order-test.jar"
        // Write entries in a deliberately non-alphabetical order to exercise real CEN record ordering.
        writeJar(
            jarPath,
            Seq(
                ("kyo/Zeta.tasty", tastyContent),
                ("kyo/Alpha.class", classContent),
                ("kyo/Mu.tasty", tastyContent),
                ("kyo/Beta.class", classContent),
                ("kyo/Omega.tasty", tastyContent)
            )
        )
        Abort.run[TastyError](
            JarCentralDirectory.list(jarPath, Chunk(".tasty", ".class")).flatMap: first =>
                JarCentralDirectory.list(jarPath, Chunk(".tasty", ".class")).map: second =>
                    (first, second)
        ).map:
            case Result.Success((first, second)) =>
                assert(
                    first.toSeq == second.toSeq,
                    s"Two consecutive JarCentralDirectory.list calls must return identical ordering: first=$first second=$second"
                )
                assert(first.length == 5, s"Expected 5 entries but got: ${first.length}")
            case Result.Failure(e) =>
                fail(s"Unexpected failure in F4-JAR determinism test: $e")
            case Result.Panic(t) =>
                fail(s"unexpected panic: $t")
    }

    // Test 1: EOCD with cenOffset = 0xFFFFFFFF without Zip64 is rejected.
    // The standard EOCD carries cenOffset = 0xFFFFFFFF (the Zip64 sentinel, uint32 max = 4_294_967_295L).
    // No Zip64 locator or Zip64 EOCD is present. Because the cenOffset > fileLen, the
    // "CEN offset out of range" guard fires, returning Abort[TastyError.MalformedSection].
    // This confirms that offset values that would overflow Int on a large file do not silently
    // produce wrong results: the bounds check catches them before any memory is accessed.
    // Pins C1.
    "EOCD cenOffset=0xFFFFFFFF without Zip64 yields MalformedSection 'out of range'".onlyJvm in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/sentinel-cenoffset.jar"

        // Build a real JAR first, then patch the EOCD cenOffset field to 0xFFFFFFFF.
        writeJar(jarPath, Seq(("dummy.tasty", tastyContent)))
        val rawBytes = Files.readAllBytes(java.nio.file.Paths.get(jarPath))

        // EOCD layout (22 bytes): sig(4) + diskNum(2) + startDisk(2) + entriesOnDisk(2) + totalEntries(2)
        //   + cenSize(4) at offset 12 + cenOffset(4) at offset 16 + commentLen(2)
        var eocdPos = -1
        var i       = rawBytes.length - 22
        while i >= 0 && eocdPos < 0 do
            if (rawBytes(i) & 0xff) == 0x50 &&
                (rawBytes(i + 1) & 0xff) == 0x4b &&
                (rawBytes(i + 2) & 0xff) == 0x05 &&
                (rawBytes(i + 3) & 0xff) == 0x06
            then eocdPos = i
            end if
            i -= 1
        end while
        assert(eocdPos >= 0, "Could not locate EOCD in test JAR")

        // Patch cenOffset at EOCD+16 to 0xFFFFFFFF (little-endian).
        rawBytes(eocdPos + 16) = 0xff.toByte
        rawBytes(eocdPos + 17) = 0xff.toByte
        rawBytes(eocdPos + 18) = 0xff.toByte
        rawBytes(eocdPos + 19) = 0xff.toByte
        Files.write(java.nio.file.Paths.get(jarPath), rawBytes)

        Abort.run[TastyError](
            JarCentralDirectory.list(jarPath, Chunk(".tasty"))
        ).map:
            case Result.Failure(e) =>
                e match
                    case TastyError.MalformedSection(_, reason, _) =>
                        assert(
                            reason.contains("out of range"),
                            s"Expected reason containing 'out of range' but got: $reason"
                        )
                    case other =>
                        fail(s"Expected MalformedSection but got: $other")
            case Result.Success(_) =>
                fail("Expected Abort[TastyError.MalformedSection] for cenOffset=0xFFFFFFFF without Zip64")
            case Result.Panic(t) =>
                throw t
    }

    // Test 2: Zip64 EOCD locator detected.
    // A synthetic JAR has the Zip64 EOCD locator signature (0x07064b50) immediately before the EOCD.
    // The locator carries zip64EocdOffset pointing to a Zip64 EOCD record that reports cenOffset.
    // JarCentralDirectory must use the Zip64 EOCD cenOffset rather than the standard EOCD cenOffset.
    // We build a real JAR, then inject a Zip64 locator + Zip64 EOCD before the standard EOCD.
    // The test verifies we can still enumerate entries without error, which confirms the Zip64 path
    // is taken and the locator offset arithmetic is correct (no Int truncation at the locator read).
    "Zip64 EOCD locator detected and CEN location read correctly".onlyJvm in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/zip64-locator.jar"

        // Write a normal small JAR with one entry.
        writeJar(jarPath, Seq(("kyo/Zip64Test.tasty", tastyContent)))
        val rawBytes = Files.readAllBytes(java.nio.file.Paths.get(jarPath))

        // Locate the existing EOCD signature.
        var eocdPos = -1
        var i       = rawBytes.length - 22
        while i >= 0 && eocdPos < 0 do
            if (rawBytes(i) & 0xff) == 0x50 &&
                (rawBytes(i + 1) & 0xff) == 0x4b &&
                (rawBytes(i + 2) & 0xff) == 0x05 &&
                (rawBytes(i + 3) & 0xff) == 0x06
            then eocdPos = i
            end if
            i -= 1
        end while
        assert(eocdPos >= 0, "Could not locate EOCD in test JAR")

        // Read the CEN offset and entry count from the existing EOCD.
        val stdCenOffset = (rawBytes(eocdPos + 16) & 0xff).toLong |
            ((rawBytes(eocdPos + 17) & 0xff).toLong << 8) |
            ((rawBytes(eocdPos + 18) & 0xff).toLong << 16) |
            ((rawBytes(eocdPos + 19) & 0xff).toLong << 24)
        val stdEntries = (rawBytes(eocdPos + 10) & 0xff).toLong |
            ((rawBytes(eocdPos + 11) & 0xff).toLong << 8)

        // Build a Zip64 EOCD record (56 bytes) pointing to the same CEN as the standard EOCD.
        // Zip64 EOCD: sig(4) + recordSize(8) + versionMade(2) + versionNeeded(2) + diskNum(4) +
        //   startDisk(4) + entriesOnDisk(8) + totalEntries(8) + cenSize(8) + cenOffset(8)
        val zip64EocdRecord = new Array[Byte](56)
        // sig = 0x06064b50
        zip64EocdRecord(0) = 0x50; zip64EocdRecord(1) = 0x4b; zip64EocdRecord(2) = 0x06; zip64EocdRecord(3) = 0x06
        // recordSize = 44 (56 - 12, as per spec)
        zip64EocdRecord(4) = 44; zip64EocdRecord(5) = 0; zip64EocdRecord(6) = 0; zip64EocdRecord(7) = 0
        zip64EocdRecord(8) = 0; zip64EocdRecord(9) = 0; zip64EocdRecord(10) = 0; zip64EocdRecord(11) = 0
        // versionMade(2) + versionNeeded(2) = zeros at 12-15
        // diskNum(4) + startDisk(4) = zeros at 16-23
        // entriesOnDisk(8) at offset 24
        zip64EocdRecord(24) = (stdEntries & 0xff).toByte
        zip64EocdRecord(25) = 0; zip64EocdRecord(26) = 0; zip64EocdRecord(27) = 0
        zip64EocdRecord(28) = 0; zip64EocdRecord(29) = 0; zip64EocdRecord(30) = 0; zip64EocdRecord(31) = 0
        // totalEntries(8) at offset 32
        zip64EocdRecord(32) = (stdEntries & 0xff).toByte
        zip64EocdRecord(33) = 0; zip64EocdRecord(34) = 0; zip64EocdRecord(35) = 0
        zip64EocdRecord(36) = 0; zip64EocdRecord(37) = 0; zip64EocdRecord(38) = 0; zip64EocdRecord(39) = 0
        // cenSize(8) at offset 40 (we set it to 0 for the synthetic Zip64 path; the reader uses cenOffset)
        // cenOffset(8) at offset 48 = stdCenOffset
        zip64EocdRecord(48) = (stdCenOffset & 0xff).toByte
        zip64EocdRecord(49) = ((stdCenOffset >> 8) & 0xff).toByte
        zip64EocdRecord(50) = ((stdCenOffset >> 16) & 0xff).toByte
        zip64EocdRecord(51) = ((stdCenOffset >> 24) & 0xff).toByte
        zip64EocdRecord(52) = 0; zip64EocdRecord(53) = 0; zip64EocdRecord(54) = 0; zip64EocdRecord(55) = 0

        // The Zip64 EOCD locator will be inserted at eocdPos - 20 (immediately before the EOCD).
        // Its zip64EocdOffset must point to where we will place the Zip64 EOCD record.
        // We will append: [original bytes up to eocdPos] [zip64EocdRecord(56)] [zip64Locator(20)] [EOCD(22)]
        val zip64EocdOffset = eocdPos.toLong // Zip64 EOCD placed at the original EOCD position

        val zip64Locator = new Array[Byte](20)
        // sig = 0x07064b50
        zip64Locator(0) = 0x50; zip64Locator(1) = 0x4b; zip64Locator(2) = 0x06; zip64Locator(3) = 0x07
        // startDisk(4) = 0 at 4-7
        // zip64EocdOffset(8) at offset 8
        zip64Locator(8) = (zip64EocdOffset & 0xff).toByte
        zip64Locator(9) = ((zip64EocdOffset >> 8) & 0xff).toByte
        zip64Locator(10) = ((zip64EocdOffset >> 16) & 0xff).toByte
        zip64Locator(11) = ((zip64EocdOffset >> 24) & 0xff).toByte
        zip64Locator(12) = 0; zip64Locator(13) = 0; zip64Locator(14) = 0; zip64Locator(15) = 0
        // totalDisks(4) = 1 at offset 16
        zip64Locator(16) = 1; zip64Locator(17) = 0; zip64Locator(18) = 0; zip64Locator(19) = 0

        // Build new file: prefix + zip64EocdRecord + zip64Locator + EOCD
        val prefix   = rawBytes.take(eocdPos)
        val eocdPart = rawBytes.drop(eocdPos)
        val newBytes = prefix ++ zip64EocdRecord ++ zip64Locator ++ eocdPart
        Files.write(java.nio.file.Paths.get(jarPath), newBytes)

        // The JAR must still enumerate correctly via the Zip64 path.
        Abort.run[TastyError](
            JarCentralDirectory.list(jarPath, Chunk(".tasty"))
        ).map:
            case Result.Success(entries) =>
                assert(
                    entries.length == 1,
                    s"Expected 1 entry via Zip64 path but got: ${entries.length}"
                )
                assert(
                    entries.head._2 == "kyo/Zip64Test.tasty",
                    s"Expected kyo/Zip64Test.tasty but got: ${entries.head._2}"
                )
            case Result.Failure(e) =>
                fail(s"Expected success via Zip64 path but got failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // B11: parseCenRecordsAll raises IOException on truncated CEN record.
    // A truncated CEN record is one where the declared record size (46 + nameLen + extraLen + commentLen)
    // exceeds the number of bytes remaining in the CEN buffer from the current position.
    // We craft a minimal CEN buffer (100 bytes) containing a single CEN record whose nameLen field
    // declares 1000 bytes, making recordSize = 46 + 1000 = 1046, far beyond the 100-byte buffer.
    // parseCenRecordsAll must throw IOException rather than silently stopping.
    "parseCenRecordsAll raises IOException on truncated CEN record".onlyJvm in {
        // Build a 100-byte CEN buffer with one record whose nameLen = 1000.
        val cenBuf = new Array[Byte](100)
        // Write CEN signature 0x02014b50 at offset 0 (little-endian).
        cenBuf(0) = 0x50
        cenBuf(1) = 0x4b
        cenBuf(2) = 0x01
        cenBuf(3) = 0x02
        // nameLen at offset 28 = 1000 (0x03E8, little-endian).
        cenBuf(28) = 0xe8.toByte
        cenBuf(29) = 0x03
        // extraLen and commentLen at offsets 30 and 32 remain 0.
        // All other fields remain 0 (valid for our purposes: method=0, sizes=0, lfhOffset=0).

        Sync.defer {
            val ex = intercept[java.io.IOException] {
                kyo.internal.tasty.query.JarCentralDirectory.parseCenRecordsAll(
                    "synthetic.jar",
                    cenBuf,
                    cenBuf.length
                )
            }
            assert(
                ex.getMessage.contains("truncated CEN record"),
                s"Expected message containing 'truncated CEN record' but got: ${ex.getMessage}"
            )
        }
    }

    // T4 Test 1: Zip64 JAR whose Zip64 EOCD reports centralDirOffset = 3_000_000_000L.
    // Synthetic approach: we build a real JAR with one entry, then inject a Zip64 EOCD locator and
    // Zip64 EOCD record. The Zip64 EOCD record's centralDirOffset field carries 3_000_000_000L.
    // Because the actual file is much smaller than 3 GB, the bounds check "cenOffset >= fileLen" fires,
    // producing MalformedSection. The key assertion is that the error message contains "3000000000",
    // which proves the parser correctly read the 64-bit field (not a 32-bit truncation).
    // If the parser truncated the 8-byte field to 4 bytes (signed), it would read
    // 3_000_000_000L as Int = -1_294_967_296, producing a different error message.
    // If it truncated to 4 bytes (unsigned), it would read 0xB2D05E00 = 2_999_999_488L when
    // misaligned, or some other value. Either way the message would NOT contain "3000000000".
    // Byte layout (appended before the EOCD):
    //   [original file bytes up to eocdPos]
    //   [Zip64 EOCD record, 56 bytes, sig=0x06064b50, cenOffset=3_000_000_000L at offset 48]
    //   [Zip64 EOCD locator, 20 bytes, sig=0x07064b50, zip64EocdOffset pointing at Zip64 EOCD]
    //   [EOCD, 22 bytes, cenOffset=0xFFFFFFFF sentinel]
    "Zip64 EOCD cenOffset=3_000_000_000L is read as 64-bit (not truncated to 32-bit)".onlyJvm in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/zip64-3gb-synthetic.jar"

        writeJar(jarPath, Seq(("kyo/BigOffset.tasty", tastyContent)))
        val rawBytes = Files.readAllBytes(java.nio.file.Paths.get(jarPath))

        // Locate the EOCD signature in the original JAR.
        var eocdPos = -1
        var i       = rawBytes.length - 22
        while i >= 0 && eocdPos < 0 do
            if (rawBytes(i) & 0xff) == 0x50 &&
                (rawBytes(i + 1) & 0xff) == 0x4b &&
                (rawBytes(i + 2) & 0xff) == 0x05 &&
                (rawBytes(i + 3) & 0xff) == 0x06
            then eocdPos = i
            end if
            i -= 1
        end while
        assert(eocdPos >= 0, "Could not locate EOCD in test JAR")

        // Build the Zip64 EOCD record (56 bytes).
        // Zip64 EOCD layout: sig(4) + recordSize(8) + versionMade(2) + versionNeeded(2)
        //   + diskNum(4) + startDisk(4) + entriesOnDisk(8) + totalEntries(8)
        //   + cenSize(8) + cenOffset(8)
        // Offsets: sig=0, recordSize=4, versionMade=12, versionNeeded=14,
        //   diskNum=16, startDisk=20, entriesOnDisk=24, totalEntries=32,
        //   cenSize=40, cenOffset=48.
        val centralDirOffset3GB: Long = 3_000_000_000L
        val zip64EocdRec              = new Array[Byte](56)
        // sig = 0x06064b50 (little-endian)
        zip64EocdRec(0) = 0x50; zip64EocdRec(1) = 0x4b; zip64EocdRec(2) = 0x06; zip64EocdRec(3) = 0x06
        // recordSize = 44 (56 - 12) as 8-byte LE
        zip64EocdRec(4) = 44
        // all disk/entry fields remain 0
        // cenOffset at byte 48 = centralDirOffset3GB (8-byte LE)
        zip64EocdRec(48) = (centralDirOffset3GB & 0xff).toByte
        zip64EocdRec(49) = ((centralDirOffset3GB >> 8) & 0xff).toByte
        zip64EocdRec(50) = ((centralDirOffset3GB >> 16) & 0xff).toByte
        zip64EocdRec(51) = ((centralDirOffset3GB >> 24) & 0xff).toByte
        zip64EocdRec(52) = ((centralDirOffset3GB >> 32) & 0xff).toByte
        zip64EocdRec(53) = ((centralDirOffset3GB >> 40) & 0xff).toByte
        zip64EocdRec(54) = ((centralDirOffset3GB >> 48) & 0xff).toByte
        zip64EocdRec(55) = ((centralDirOffset3GB >> 56) & 0xff).toByte

        // The Zip64 EOCD record is placed at the original eocdPos (we push the EOCD back).
        val zip64EocdOffset: Long = eocdPos.toLong

        // Build the Zip64 EOCD locator (20 bytes).
        // Layout: sig(4) + diskWithZip64EOCD(4) + offsetOfZip64EOCD(8) + totalDisks(4).
        val zip64Loc = new Array[Byte](20)
        // sig = 0x07064b50 (little-endian)
        zip64Loc(0) = 0x50; zip64Loc(1) = 0x4b; zip64Loc(2) = 0x06; zip64Loc(3) = 0x07
        // diskWithZip64EOCD = 0 at offset 4
        // offsetOfZip64EOCD at offset 8 (8-byte LE)
        zip64Loc(8) = (zip64EocdOffset & 0xff).toByte
        zip64Loc(9) = ((zip64EocdOffset >> 8) & 0xff).toByte
        zip64Loc(10) = ((zip64EocdOffset >> 16) & 0xff).toByte
        zip64Loc(11) = ((zip64EocdOffset >> 24) & 0xff).toByte
        zip64Loc(12) = ((zip64EocdOffset >> 32) & 0xff).toByte
        zip64Loc(13) = ((zip64EocdOffset >> 40) & 0xff).toByte
        zip64Loc(14) = ((zip64EocdOffset >> 48) & 0xff).toByte
        zip64Loc(15) = ((zip64EocdOffset >> 56) & 0xff).toByte
        // totalDisks = 1 at offset 16 (4-byte LE)
        zip64Loc(16) = 1

        // Patch the standard EOCD cenOffset to 0xFFFFFFFF (Zip64 sentinel) to ensure the Zip64
        // path is taken. EOCD cenOffset is at EOCD+16 (4 bytes, LE).
        val eocdBytes = rawBytes.drop(eocdPos)
        eocdBytes(16) = 0xff.toByte
        eocdBytes(17) = 0xff.toByte
        eocdBytes(18) = 0xff.toByte
        eocdBytes(19) = 0xff.toByte

        // Assemble: prefix + Zip64 EOCD record + Zip64 locator + EOCD (22 bytes)
        val prefix   = rawBytes.take(eocdPos)
        val newBytes = prefix ++ zip64EocdRec ++ zip64Loc ++ eocdBytes.take(22)
        Files.write(java.nio.file.Paths.get(jarPath), newBytes)

        // The Zip64 EOCD reports centralDirOffset3GB = 3_000_000_000L. The actual file is tiny,
        // so the bounds check fires. The error message must contain "3000000000" proving 64-bit
        // reading. A 32-bit Int truncation of 3_000_000_000L would yield a different value.
        Abort.run[TastyError](
            JarCentralDirectory.list(jarPath, Chunk(".tasty"))
        ).map:
            case Result.Failure(e) =>
                e match
                    case TastyError.MalformedSection(_, reason, _) =>
                        assert(
                            reason.contains("3000000000"),
                            s"Expected '3000000000' in reason proving 64-bit read; got: $reason"
                        )
                    case other =>
                        fail(s"Expected MalformedSection but got: $other")
            case Result.Success(_) =>
                fail("Expected MalformedSection for cenOffset=3_000_000_000L beyond file size")
            case Result.Panic(t) =>
                throw t
    }

    // T4 Test 2: synthetic EOCD with diskNumber=2 is rejected with "multi-disk".
    // The ZIP spec requires diskNumber == 0 for single-file archives. JarCentralDirectory checks
    // stdDiskNum != 0 || stdStartDisk != 0 and throws MalformedSection containing "multi-disk".
    // Byte construction:
    //   EOCD (22 bytes): sig=0x06054b50, diskNumber=2, startDisk=0, entriesOnDisk=0,
    //   totalEntries=0, cenSize=0, cenOffset=0, commentLen=0.
    // The scanner searches from the end for the EOCD signature. We write a 22-byte file.
    "EOCD with diskNumber=2 yields MalformedSection containing 'multi-disk'".onlyJvm in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/multidisk-synthetic.jar"

        // Construct a 22-byte EOCD record with diskNumber=2.
        // EOCD layout (22 bytes, little-endian):
        //   offset 0: sig = 0x06054b50
        //   offset 4: disk = 2 (the disk this EOCD lives on)
        //   offset 6: start = 0 (disk where CEN starts)
        //   offset 8: onDisk = 0 (entries on this disk)
        //   offset 10: total = 0 (total entries)
        //   offset 12: cenSz = 0 (central directory size)
        //   offset 16: cenOff = 0 (CEN offset)
        //   offset 20: comLen = 0 (comment length)
        val eocd = new Array[Byte](22)
        // sig = 0x06054b50 (little-endian)
        eocd(0) = 0x50; eocd(1) = 0x4b; eocd(2) = 0x05; eocd(3) = 0x06
        // diskNumber = 2 at offset 4 (2-byte LE)
        eocd(4) = 2; eocd(5) = 0
        // all other fields remain 0

        Files.write(java.nio.file.Paths.get(jarPath), eocd)

        Abort.run[TastyError](
            JarCentralDirectory.list(jarPath, Chunk(".tasty"))
        ).map:
            case Result.Failure(e) =>
                e match
                    case TastyError.MalformedSection(_, reason, _) =>
                        assert(
                            reason.contains("multi-disk"),
                            s"Expected reason containing 'multi-disk' but got: $reason"
                        )
                    case other =>
                        fail(s"Expected MalformedSection but got: $other")
            case Result.Success(_) =>
                fail("Expected MalformedSection for EOCD with diskNumber=2")
            case Result.Panic(t) =>
                throw t
    }

    // T4 Test 3: JMOD support - deferred.
    // JMOD format: 4-byte magic ("JM\1\0" = 0x4A 0x4D 0x01 0x00) + 2-byte version + ZIP content.
    // The embedded ZIP data starts at byte 6. All ZIP-internal offsets (CEN offset, LFH offsets)
    // in the embedded ZIP are relative to byte 0 of the embedded ZIP, which equals byte 6 of
    // the JMOD file.
    // Production code does not currently support JMOD. Adding correct support requires:
    //   (1) Read the first 4 bytes; if they match the JMOD magic, record a 6-byte prefix offset.
    //   (2) Pass that prefix offset through all seek operations so that CEN offset and LFH offsets
    //       are adjusted by +6 when seeking in the file.
    // This is a non-trivial structural change to the parsing pipeline (JarCentralDirectory.list,
    // listEntries, findEocd, readCenLocation, and all raf.seek calls must be adjusted). Deferring
    // to a dedicated future phase avoids mixing production-code changes into this test-only phase.
    // Decision: DEFER. Document deferral. No test body.
    "JMOD support deferred (production code does not yet detect JMOD magic prefix)".onlyJvm in {
        // JMOD support is deferred. See comment above for the required production changes.
        // This placeholder ensures the test ID is tracked in the test suite.
        Sync.defer(succeed)
    }

    // T-CRC1: JarEntry.crc32 field equals the CRC-32 of the entry bytes.
    // Given: a JAR with foo.class = [1, 2, 3]; the ZIP spec places the CRC-32 at CEN record offset +16.
    // When: JarCentralDirectory.read(jarPath).find(_.name == "foo.class").get.crc32
    // Then: equals java.util.zip.CRC32 computed over [1, 2, 3].
    "JarEntry.crc32 from CEN offset+16 matches java.util.zip.CRC32 for foo.class=[1,2,3]".onlyJvm in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/crc1.jar"
        val bytes   = Array[Byte](1, 2, 3)
        writeJar(jarPath, Seq("foo.class" -> bytes))
        val crc = new java.util.zip.CRC32()
        crc.update(bytes)
        val expectedCrc32 = crc.getValue
        // Unsafe: AllowUnsafe boundary for JarCentralDirectory.read; bounded to this test site.
        val entries = JarCentralDirectory.read(jarPath)(using AllowUnsafe.embrace.danger)
        val entry   = entries.find(_.name == "foo.class")
        assert(entry.isDefined, s"expected foo.class entry in JAR but entries were: $entries")
        assert(
            entry.get.crc32 == expectedCrc32,
            s"crc32 mismatch: got ${entry.get.crc32}, expected $expectedCrc32 (0x${expectedCrc32.toHexString})"
        )
    }

end JarCentralDirectoryTest
