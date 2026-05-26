package kyo

import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kyo.internal.reflect.query.JarCentralDirectory

/** Tests for JarCentralDirectory direct CEN reader.
  *
  * Tests T1-T6 and T11-T14 per execution-plan-perf.md Phase 1.
  *
  * Test JARs are built programmatically via java.util.zip.ZipOutputStream writing to a temp file via java.io.FileOutputStream. Each test
  * allocates its own temp dir and cleans up in teardown.
  */
class JarCentralDirectoryTest extends Test:

    /** Create a temp directory for the test, returning its path. */
    private def makeTempDir(): String =
        Files.createTempDirectory("kyo-reflect-jar-cen-test").toAbsolutePath.toString

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
    "T1: empty JAR returns Chunk.empty for any suffix list" taggedAs jvmOnly in run {
        val dir     = makeTempDir()
        val jarPath = s"$dir/empty.jar"
        writeJar(jarPath, Seq.empty)
        Abort.run[ReflectError](
            JarCentralDirectory.list(jarPath, Chunk(".tasty", ".class"))
        ).map:
            case Result.Success(entries) =>
                assert(entries.isEmpty, s"Expected empty Chunk but got: $entries")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // T2: jar with only .tasty entries returns those entries when suffix is .tasty;
    //     returns empty when suffix is .class
    "T2: jar with only .tasty entries: suffix .tasty returns all, .class returns empty" taggedAs jvmOnly in run {
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
        Abort.run[ReflectError](
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

    // T3: jar with only .class entries returns those entries when suffix is .class
    "T3: jar with only .class entries: suffix .class returns all, .tasty returns empty" taggedAs jvmOnly in run {
        val dir     = makeTempDir()
        val jarPath = s"$dir/class-only.jar"
        writeJar(
            jarPath,
            Seq(
                ("com/example/Foo.class", classContent),
                ("com/example/Bar.class", classContent)
            )
        )
        Abort.run[ReflectError](
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

    // T4: jar with mixed .tasty + .class + .java entries: multi-suffix returns only .tasty and .class
    "T4: mixed jar with .tasty + .class + .java: multi-suffix returns only .tasty and .class" taggedAs jvmOnly in run {
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
        Abort.run[ReflectError](
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
    "T5: large JAR (>500 entries) returns all matching entries" taggedAs jvmOnly in run {
        val dir     = makeTempDir()
        val jarPath = s"$dir/large.jar"
        val count   = 600
        val entries = (0 until count).flatMap: i =>
            Seq(
                (s"kyo/pkg$i/Cls$i.tasty", tastyContent),
                (s"kyo/pkg$i/Cls$i.class", classContent)
            )
        writeJar(jarPath, entries.toSeq)
        Abort.run[ReflectError](
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

    // T6: non-JAR file path returns Abort[ReflectError.MalformedSection] because the
    //     EOCD signature scan fails on a file that is not a valid ZIP/JAR.
    "T6: non-JAR file returns Abort[ReflectError.MalformedSection]" taggedAs jvmOnly in run {
        val dir      = makeTempDir()
        val textPath = s"$dir/not-a-jar.txt"
        Files.write(java.nio.file.Paths.get(textPath), "hello world".getBytes(StandardCharsets.UTF_8))
        Abort.run[ReflectError](
            JarCentralDirectory.list(textPath, Chunk(".tasty"))
        ).map:
            case Result.Success(_) =>
                fail("Expected Abort[ReflectError.MalformedSection] for non-JAR file, got success")
            case Result.Failure(e) =>
                e match
                    case ReflectError.MalformedSection(_, _) => succeed
                    case other                               => fail(s"Expected MalformedSection but got: $other")
            case Result.Panic(t) =>
                fail(s"unexpected panic: $t")
    }

    // T11: JAR with corrupted EOCD signature (0xdeadbeef) returns Abort[ReflectError.MalformedSection]
    "T11: corrupted EOCD signature returns Abort[ReflectError.MalformedSection]" taggedAs jvmOnly in run {
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
        Abort.run[ReflectError](
            JarCentralDirectory.list(jarPath, Chunk(".tasty"))
        ).map:
            case Result.Success(_) =>
                fail("Expected Abort[ReflectError.MalformedSection] for corrupted EOCD")
            case Result.Failure(e) =>
                e match
                    case ReflectError.MalformedSection(_, _) => succeed
                    case other                               => fail(s"Expected MalformedSection but got: $other")
            case Result.Panic(t) =>
                throw t
    }

    // T12: JAR with general-purpose-bit-3 (data descriptor flag) set in the CEN record.
    //
    // Background: ZipOutputStream does NOT set GPB bit-3 in the CEN when sizes are known at write
    // time (which is always the case when using putNextEntry with pre-set CRC/sizes). To exercise
    // the real bit-3 hazard we patch the CEN GPB field of a normal STORED JAR after writing it.
    //
    // The CEN-based reader reads entry names from the CEN record and checks only bit-11 (UTF-8
    // flag) for charset selection. Bit-3 is not used by the reader, so an entry with bit-3 set
    // in its CEN GPB field must enumerate correctly (name returned, no error).
    "T12: JAR with CEN GPB bit-3 (data descriptor flag) set enumerates correctly" taggedAs jvmOnly in run {
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
        Abort.run[ReflectError](
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
    "T13: empty JAR with zero entries (EOCD only) returns Chunk.empty" taggedAs jvmOnly in run {
        val dir     = makeTempDir()
        val jarPath = s"$dir/zero-entries.jar"
        // writeJar with no entries produces a valid empty JAR
        writeJar(jarPath, Seq.empty)
        Abort.run[ReflectError](
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
    "T14: JAR with UTF-8 entry names (non-ASCII like münchen.tasty) decodes correctly" taggedAs jvmOnly in run {
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
        Abort.run[ReflectError](
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
    //         in FileSourceTest, whose list() pre-sorts and trivially passes the ordering check).
    "F4-JAR: JarCentralDirectory.list returns identical ordering across two consecutive calls" taggedAs jvmOnly in run {
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
        Abort.run[ReflectError](
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

end JarCentralDirectoryTest
