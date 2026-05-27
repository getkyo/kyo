package kyo

import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kyo.internal.tasty.query.JarMappedReader
import kyo.internal.tasty.query.JvmFileSource

/** Tests for JvmFileSource.read with jar!/entry paths.
  *
  * Tests T-J1 through T-J5 per the jar-entry-read fix specification.
  *
  * Tests T-J1, T-J2, T-J3, T-J4 use synthetic JARs built programmatically via ZipOutputStream. Test T-J5 uses a real jar from the JVM
  * classpath that contains .tasty entries.
  */
class JvmFileSourceTest extends Test:

    private def makeTempDir(): String =
        Files.createTempDirectory("kyo-tasty-jvmfilesource-test").toAbsolutePath.toString

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

    private val knownBytes1: Array[Byte] = "fake-tasty-content-alpha".getBytes(StandardCharsets.UTF_8)
    private val knownBytes2: Array[Byte] = "fake-tasty-content-beta".getBytes(StandardCharsets.UTF_8)
    private val knownBytes3: Array[Byte] = "fake-tasty-content-gamma".getBytes(StandardCharsets.UTF_8)

    // T-J1: Build a temp JAR with one .tasty entry. Read it via jar!/entry path. Assert bytes match.
    "T-J1: read single .tasty entry from jar returns known bytes" taggedAs jvmOnly in run {
        val dir       = makeTempDir()
        val jarPath   = s"$dir/single-entry.jar"
        val entryName = "kyo/Foo.tasty"
        writeJar(jarPath, Seq((entryName, knownBytes1)))
        val fullPath = s"$jarPath!/$entryName"
        Abort.run[TastyError](
            JvmFileSource.read(fullPath)
        ).map:
            case Result.Success(bytes) =>
                assert(
                    java.util.Arrays.equals(bytes, knownBytes1),
                    s"Bytes mismatch: expected ${knownBytes1.length} bytes, got ${bytes.length}"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure reading $fullPath: $e")
            case Result.Panic(t) =>
                throw t
    }

    // T-J2: Jar with multiple entries; read each by name; assert bytes match.
    "T-J2: read multiple entries from jar, each returns correct bytes" taggedAs jvmOnly in run {
        val dir     = makeTempDir()
        val jarPath = s"$dir/multi-entry.jar"
        val entries = Seq(
            ("kyo/Alpha.tasty", knownBytes1),
            ("kyo/pkg/Beta.tasty", knownBytes2),
            ("other/Gamma.tasty", knownBytes3)
        )
        writeJar(jarPath, entries)

        Abort.run[TastyError](
            JvmFileSource.read(s"$jarPath!/kyo/Alpha.tasty").flatMap: bytes1 =>
                JvmFileSource.read(s"$jarPath!/kyo/pkg/Beta.tasty").flatMap: bytes2 =>
                    JvmFileSource.read(s"$jarPath!/other/Gamma.tasty").map: bytes3 =>
                        (bytes1, bytes2, bytes3)
        ).map:
            case Result.Success((bytes1, bytes2, bytes3)) =>
                assert(
                    java.util.Arrays.equals(bytes1, knownBytes1),
                    s"Alpha.tasty bytes mismatch"
                )
                assert(
                    java.util.Arrays.equals(bytes2, knownBytes2),
                    s"Beta.tasty bytes mismatch"
                )
                assert(
                    java.util.Arrays.equals(bytes3, knownBytes3),
                    s"Gamma.tasty bytes mismatch"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // T-J3: Missing entry in existing jar returns Abort[TastyError.FileNotFound].
    "T-J3: missing entry in existing jar returns Abort[TastyError.FileNotFound]" taggedAs jvmOnly in run {
        val dir     = makeTempDir()
        val jarPath = s"$dir/one-entry.jar"
        writeJar(jarPath, Seq(("kyo/Exists.tasty", knownBytes1)))
        val fullPath = s"$jarPath!/kyo/DoesNotExist.tasty"
        Abort.run[TastyError](
            JvmFileSource.read(fullPath)
        ).map:
            case Result.Success(_) =>
                fail(s"Expected Abort[TastyError.FileNotFound] for missing entry, got success")
            case Result.Failure(e) =>
                e match
                    case TastyError.FileNotFound(_) => succeed
                    case other                      => fail(s"Expected FileNotFound but got: $other")
            case Result.Panic(t) =>
                throw t
    }

    // T-J4: Missing jar file returns Abort[TastyError.FileNotFound].
    "T-J4: missing jar file returns Abort[TastyError.FileNotFound]" taggedAs jvmOnly in run {
        val dir      = makeTempDir()
        val jarPath  = s"$dir/nonexistent.jar"
        val fullPath = s"$jarPath!/kyo/Foo.tasty"
        Abort.run[TastyError](
            JvmFileSource.read(fullPath)
        ).map:
            case Result.Success(_) =>
                fail(s"Expected Abort[TastyError.FileNotFound] for missing jar, got success")
            case Result.Failure(e) =>
                e match
                    case TastyError.FileNotFound(_) => succeed
                    case other                      => fail(s"Expected FileNotFound but got: $other")
            case Result.Panic(t) =>
                throw t
    }

    // T-J5: Real-classpath integration test.
    // Finds a jar on the JVM classpath that contains .tasty entries (via JvmFileSource.list),
    // reads the first 20 entries, and asserts all reads succeed with non-empty bytes.
    "T-J5: real-classpath jar: list entries then read first 20, all return non-empty bytes" taggedAs jvmOnly in run {
        // Find a real jar from the JVM classpath that has .tasty entries.
        val cpEntries = java.lang.System.getProperty("java.class.path", "")
            .split(java.io.File.pathSeparator)
            .toSeq
            .filter(_.endsWith(".jar"))
            .filter(p => java.nio.file.Files.isRegularFile(java.nio.file.Paths.get(p)))

        // Pick the first jar that has at least one .tasty entry.
        val jarWithTastyOpt: Option[String] = cpEntries.find: jar =>
            try
                val jf = new java.util.jar.JarFile(jar)
                try
                    val it    = jf.entries()
                    var found = false
                    while it.hasMoreElements && !found do
                        if it.nextElement().getName.endsWith(".tasty") then found = true
                    found
                finally
                    jf.close()
                end try
            catch case _: Throwable => false

        Sync.defer(jarWithTastyOpt).flatMap:
            case None =>
                // No jar with .tasty on classpath: skip (not a test failure)
                succeed
            case Some(realJar) =>
                Abort.run[TastyError](
                    JvmFileSource.list(realJar, Chunk(".tasty")).flatMap: allEntries =>
                        val first20 = allEntries.take(20)
                        assert(first20.nonEmpty, s"Expected at least one .tasty entry in $realJar")
                        Kyo.foreach(first20): entryPath =>
                            JvmFileSource.read(entryPath).map: bytes =>
                                (entryPath, bytes)
                ).map:
                    case Result.Success(results) =>
                        for (path, bytes) <- results do
                            assert(bytes.nonEmpty, s"Expected non-empty bytes for $path")
                        succeed
                    case Result.Failure(e) =>
                        fail(s"Unexpected failure reading real-classpath jar entries: $e")
                    case Result.Panic(t) =>
                        throw t
    }

    // T-M1: Build a synthetic jar with 3 entries (DEFLATED by default via ZipOutputStream).
    //        Open via JarMappedReader, read each entry, assert bytes match the originals.
    "T-M1: JarMappedReader reads all entries from a synthetic jar and returns correct bytes" taggedAs jvmOnly in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/mmap-basic.jar"
        writeJar(
            jarPath,
            Seq(
                ("kyo/Alpha.tasty", knownBytes1),
                ("kyo/Beta.tasty", knownBytes2),
                ("kyo/Gamma.tasty", knownBytes3)
            )
        )
        val reader = JarMappedReader.open(jarPath)
        val r1     = reader.readEntry("kyo/Alpha.tasty")
        val r2     = reader.readEntry("kyo/Beta.tasty")
        val r3     = reader.readEntry("kyo/Gamma.tasty")
        assert(java.util.Arrays.equals(r1, knownBytes1), "Alpha.tasty bytes mismatch")
        assert(java.util.Arrays.equals(r2, knownBytes2), "Beta.tasty bytes mismatch")
        assert(java.util.Arrays.equals(r3, knownBytes3), "Gamma.tasty bytes mismatch")
    }

    // T-M2: Concurrent reads from a single JarMappedReader.
    //        8 threads each read all 3 entries; assert correctness from every thread.
    "T-M2: JarMappedReader concurrent reads from multiple threads return correct bytes" taggedAs jvmOnly in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/mmap-concurrent.jar"
        writeJar(
            jarPath,
            Seq(
                ("kyo/Alpha.tasty", knownBytes1),
                ("kyo/Beta.tasty", knownBytes2),
                ("kyo/Gamma.tasty", knownBytes3)
            )
        )
        val reader = JarMappedReader.open(jarPath)

        val threadCount = 8
        val errors      = new java.util.concurrent.CopyOnWriteArrayList[String]()
        val latch       = new java.util.concurrent.CountDownLatch(threadCount)

        for tid <- 0 until threadCount do
            val task: Runnable = () =>
                try
                    val r1 = reader.readEntry("kyo/Alpha.tasty")
                    val r2 = reader.readEntry("kyo/Beta.tasty")
                    val r3 = reader.readEntry("kyo/Gamma.tasty")
                    if !java.util.Arrays.equals(r1, knownBytes1) then
                        errors.add(s"Thread $tid: Alpha bytes mismatch"): Unit
                    if !java.util.Arrays.equals(r2, knownBytes2) then
                        errors.add(s"Thread $tid: Beta bytes mismatch"): Unit
                    if !java.util.Arrays.equals(r3, knownBytes3) then
                        errors.add(s"Thread $tid: Gamma bytes mismatch"): Unit
                catch
                    case ex: Throwable => errors.add(s"Thread $tid threw: ${ex.getMessage}"): Unit
                finally latch.countDown()
            new Thread(task).start()
        end for

        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        assert(errors.isEmpty, s"Concurrent read errors: ${errors.toArray.mkString(", ")}")
    }

    // T-M3: Jar with GPB bit-3 set (data-descriptor entries where LFH carries 0 sizes).
    //        The CEN still has the true sizes. JarMappedReader uses CEN metadata so it must still
    //        decompress correctly.
    "T-M3: JarMappedReader reads entries with GPB bit-3 (data-descriptor flag) set in LFH" taggedAs jvmOnly in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/mmap-bit3.jar"
        // Write a normal jar first, then patch the LFH of the first entry to set GPB bit-3 and zero
        // out the LFH compSize/uncompSize/crc fields (simulating a streaming writer).
        writeJar(jarPath, Seq(("kyo/DataDesc.tasty", knownBytes1)))
        val rawBytes = Files.readAllBytes(java.nio.file.Paths.get(jarPath))
        // LFH starts at offset 0. Patch:
        //   offset 6 = gpFlag low byte; set bit-3 (0x08)
        //   offset 14-17 = crc (4 bytes): zero out
        //   offset 18-21 = compSize (4 bytes): zero out
        //   offset 22-25 = uncompSize (4 bytes): zero out
        rawBytes(6) = (rawBytes(6) | 0x08).toByte
        rawBytes(14) = 0; rawBytes(15) = 0; rawBytes(16) = 0; rawBytes(17) = 0
        rawBytes(18) = 0; rawBytes(19) = 0; rawBytes(20) = 0; rawBytes(21) = 0
        rawBytes(22) = 0; rawBytes(23) = 0; rawBytes(24) = 0; rawBytes(25) = 0
        Files.write(java.nio.file.Paths.get(jarPath), rawBytes)

        val reader = JarMappedReader.open(jarPath)
        val result = reader.readEntry("kyo/DataDesc.tasty")
        assert(
            java.util.Arrays.equals(result, knownBytes1),
            s"Expected original bytes after bit-3 patch, got ${result.length} bytes"
        )
    }

    // T-M4: STORED entry (method=0). ZipOutputStream uses DEFLATE by default for non-zero-length entries;
    //        we force STORED by setting the compression level to 0 (NO_COMPRESSION) via ZipEntry.setMethod.
    "T-M4: JarMappedReader reads STORED (method=0) entry correctly" taggedAs jvmOnly in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/mmap-stored.jar"
        // Write a STORED entry by using STORED method with pre-computed CRC and size.
        val storedBytes = "stored-content-12345".getBytes(StandardCharsets.UTF_8)
        val fos         = new FileOutputStream(jarPath)
        val zos         = new ZipOutputStream(fos)
        try
            val entry = new ZipEntry("kyo/Stored.tasty")
            entry.setMethod(ZipEntry.STORED)
            entry.setSize(storedBytes.length.toLong)
            entry.setCompressedSize(storedBytes.length.toLong)
            val crc = new java.util.zip.CRC32()
            crc.update(storedBytes)
            entry.setCrc(crc.getValue)
            zos.putNextEntry(entry)
            zos.write(storedBytes)
            zos.closeEntry()
        finally
            zos.close()
            fos.close()
        end try

        val reader = JarMappedReader.open(jarPath)
        val result = reader.readEntry("kyo/Stored.tasty")
        assert(
            java.util.Arrays.equals(result, storedBytes),
            s"STORED entry bytes mismatch: expected ${storedBytes.length} bytes, got ${result.length}"
        )
    }

end JvmFileSourceTest
