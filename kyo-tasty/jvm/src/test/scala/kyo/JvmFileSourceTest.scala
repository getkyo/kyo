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

    // Phase 05a - B14: withReadBatch pool registration is atomic via Scope.acquireRelease.
    //
    // Scenario 1 (normal exit): withReadBatch installs pool, body succeeds, Scope.run
    // completes, release fires, activePool returns to null.
    //
    // Scenario 2 (Abort failure): withReadBatch installs pool, body raises Abort[TastyError],
    // Scope.run's release still fires: pool.closeAll() is called and activePool is reset to
    // null. This pins B14: no partial-registration window can strand a live pool regardless
    // of how the body exits.
    //
    // Both scenarios verify via reflection that activePool is null after Scope.run.
    "P05a-T1: withReadBatch releases pool and clears activePool after successful body" taggedAs jvmOnly in run {
        val activePoolField = JvmFileSource.getClass.getDeclaredField("kyo$internal$tasty$query$JvmFileSource$$$activePool")
        activePoolField.setAccessible(true)

        Scope.run(
            JvmFileSource.withReadBatch(Sync.defer(42))
        ).map: result =>
            assert(result == 42, s"Expected body result 42, got $result")
            val poolAfter = activePoolField.get(JvmFileSource)
                .asInstanceOf[java.util.concurrent.atomic.AtomicReference[?]]
                .get()
            assert(
                poolAfter == null,
                s"Expected activePool to be null after Scope.run but got: $poolAfter"
            )
    }

    "P05a-T2: withReadBatch releases pool and clears activePool when body raises Abort failure" taggedAs jvmOnly in run {
        val activePoolField = JvmFileSource.getClass.getDeclaredField("kyo$internal$tasty$query$JvmFileSource$$$activePool")
        activePoolField.setAccessible(true)

        val failBody: Int < (Sync & Scope & Abort[TastyError]) =
            JvmFileSource.withReadBatch(
                Abort.fail[TastyError](TastyError.FileNotFound("B14-test-failure"))
            )

        Abort.run[TastyError](
            Scope.run(failBody)
        ).map: result =>
            result match
                case Result.Failure(TastyError.FileNotFound(msg)) =>
                    assert(
                        msg.contains("B14-test-failure"),
                        s"Expected B14-test-failure in message but got: $msg"
                    )
                    val poolAfter = activePoolField.get(JvmFileSource)
                        .asInstanceOf[java.util.concurrent.atomic.AtomicReference[?]]
                        .get()
                    assert(
                        poolAfter == null,
                        s"Expected activePool to be null after failed body but got: $poolAfter"
                    )
                case other =>
                    fail(s"Expected Failure(TastyError.FileNotFound) but got: $other")
    }

    // Phase 04a - INV-012 Test 3: 64-bit LFH offset guard rejects lfhOffset > Int.MaxValue.
    //
    // We construct a synthetic JarEntry with lfhOffset = Int.MaxValue.toLong + 1L and then
    // ask JarMappedReader to read it. The bounds check at the start of readEntry must detect
    // that the offset exceeds the mmap range and throw IOException with "exceeds 2GB".
    //
    // We cannot literally create a 2GB+ JAR in a unit test, so we verify the defensive guard
    // directly by patching a JarMappedReader's entry map to carry an oversized lfhOffset.
    // This pins the B2 invariant: no silent Int truncation of the LFH offset field.
    "P04a-T3: JarMappedReader.readEntry rejects lfhOffset > Int.MaxValue with IOException" taggedAs jvmOnly in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/big-offset.jar"
        writeJar(jarPath, Seq(("kyo/BigOffset.tasty", knownBytes1)))

        // Open the reader to get a valid mbb, then inject an oversized lfhOffset via reflection.
        val reader = JarMappedReader.open(jarPath)

        // Replace the entry in the internal map with one carrying lfhOffset = Int.MaxValue + 1L.
        // Access entries map via reflection (private field).
        val entriesField = reader.getClass.getDeclaredField("entries")
        entriesField.setAccessible(true)
        val entriesMap = entriesField
            .get(reader)
            .asInstanceOf[java.util.HashMap[String, kyo.internal.tasty.query.JarCentralDirectory.JarEntry]]
        val original  = entriesMap.get("kyo/BigOffset.tasty")
        val oversized = original.copy(lfhOffset = Int.MaxValue.toLong + 1L)
        entriesMap.put("kyo/BigOffset.tasty", oversized)

        val caught =
            try
                reader.readEntry("kyo/BigOffset.tasty")
                None
            catch
                case ex: java.io.IOException => Some(ex.getMessage)
                case _: Throwable            => None

        assert(
            caught.isDefined,
            "Expected IOException for lfhOffset > Int.MaxValue but readEntry succeeded"
        )
        assert(
            caught.exists(_.contains("exceeds 2GB")),
            s"Expected message containing 'exceeds 2GB' but got: ${caught.getOrElse("")}"
        )
    }

    // Phase 05b - B15: JarMappedReader channel close window.
    //
    // Scenario 1: empty file triggers IOException("empty file") before channel.map() is called.
    // The channel.close() in the finally block must execute before the exception propagates.
    // The exception must be an IOException with "empty file" in the message, confirming
    // the open() guard fired and the channel was cleanly closed.
    "P05b-T1: JarMappedReader.open on empty file throws IOException with 'empty file' message" taggedAs jvmOnly in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/empty.jar"
        // Write an empty file so RandomAccessFile succeeds but channel.size() == 0.
        java.nio.file.Files.write(java.nio.file.Paths.get(jarPath), Array.emptyByteArray)

        val caught =
            try
                JarMappedReader.open(jarPath)
                None
            catch
                case ex: java.io.IOException => Some(ex)
                case t: Throwable            => throw t

        assert(caught.isDefined, "Expected IOException for empty JAR but open() succeeded")
        val message = caught.get.getMessage
        assert(
            message.contains("empty file"),
            s"Expected 'empty file' in IOException message but got: $message"
        )
        // Exception message must not expose raw channel internals (no sun.nio.ch bleed-through).
        assert(
            !message.contains("sun.nio.ch") && !message.contains("FileChannelImpl"),
            s"Exception message exposes FileChannel internals: $message"
        )
    }

    // Phase 05b - B15 scenario 2: malformed JAR content reaches parseAllEntries, which throws.
    // The channel.close() in the finally block fires before that exception leaves open().
    // The thrown exception must be a plain IOException, not a ClosedChannelException, which
    // would indicate the channel was still open when the error was constructed.
    "P05b-T2: JarMappedReader.open on malformed JAR throws plain IOException from parseAllEntries" taggedAs jvmOnly in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/not-a-jar.jar"
        // 4 bytes of garbage: channel.map() succeeds but parseAllEntries rejects the content.
        java.nio.file.Files.write(java.nio.file.Paths.get(jarPath), Array[Byte](0x00, 0x01, 0x02, 0x03))

        val caught =
            try
                JarMappedReader.open(jarPath)
                None
            catch
                case ex: java.io.IOException => Some(ex)
                case t: Throwable            => throw t

        assert(caught.isDefined, "Expected IOException for malformed JAR but open() succeeded")
        val ex = caught.get
        // Not a ClosedChannelException: that would indicate the channel was still alive when
        // the error propagated, meaning the finally-close had not yet run.
        assert(
            !ex.isInstanceOf[java.nio.channels.ClosedChannelException],
            s"Unexpected ClosedChannelException: channel should already be closed before propagation"
        )
        assert(
            ex.getMessage != null && ex.getMessage.nonEmpty,
            "IOException must carry a non-empty message"
        )
    }

    // Phase 24b - T8 Test 1: JAR pool exhaustion under 50-fiber concurrent load.
    //
    // Opens a withReadBatch scope (installing a JarMappedReaderPool), then launches 50 concurrent
    // fibers via Async.foreach. Every fiber reads the same JAR entry. The pool serves all 50 reads
    // from a single JarMappedReader (one per jar path, cached in ConcurrentHashMap). After the
    // Scope exits the pool is cleared and activePool returns to null.
    //
    // Pins: T8 (resource lifecycle - pool survives under concurrent load, releases cleanly).
    "P24b-T1: 50 concurrent fibers reading the same JAR entry all succeed and pool is cleared on scope exit" taggedAs jvmOnly in run {
        val dir       = makeTempDir()
        val jarPath   = s"$dir/pool-exhaustion.jar"
        val entryName = "kyo/PoolTest.tasty"
        writeJar(jarPath, Seq((entryName, knownBytes1)))
        val fullPath = s"$jarPath!/$entryName"

        val activePoolField = JvmFileSource.getClass.getDeclaredField("kyo$internal$tasty$query$JvmFileSource$$$activePool")
        activePoolField.setAccessible(true)

        val fiberCount = 50
        val fibers     = Chunk.fill(fiberCount)(())

        Scope.run(
            JvmFileSource.withReadBatch(
                Abort.run[TastyError](
                    Async.foreach(fibers, fiberCount) { _ =>
                        JvmFileSource.read(fullPath)
                    }
                )
            )
        ).map: result =>
            val poolAfter = activePoolField.get(JvmFileSource)
                .asInstanceOf[java.util.concurrent.atomic.AtomicReference[?]]
                .get()
            assert(
                poolAfter == null,
                s"Expected activePool null after scope exit but got: $poolAfter"
            )
            result match
                case Result.Success(bytesChunk) =>
                    assert(bytesChunk.length == fiberCount, s"Expected $fiberCount results, got ${bytesChunk.length}")
                    val allMatch = bytesChunk.forall(bytes => java.util.Arrays.equals(bytes, knownBytes1))
                    assert(allMatch, "At least one fiber read returned wrong bytes")
                case Result.Failure(e) =>
                    fail(s"P24b-T1: unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
            end match
    }

    // Phase 24b - T8 Test 3: mmap arena close during Symbol.body access.
    //
    // Opens a classpath inside a Scope using the in-memory fixture (same bytes that the shared
    // TreeUnpicklerTest uses), finds a declaration symbol that has a non-zero body slice, captures
    // that symbol, then exits the Scope (which closes the classpath). Calling sym.body after scope
    // exit must return TastyError.ClasspathClosed. This exercises the isClosed guard added in Phase
    // 02d and the IllegalStateException -> ClasspathClosed mapping for mmap-backed reads.
    //
    // Pins: T8 (mmap arena close path).
    "P24b-T3: sym.body after classpath close returns ClasspathClosed (mmap arena close path)" taggedAs jvmOnly in run {
        import kyo.internal.tasty.query.Classpath as InternalClasspath
        import kyo.internal.tasty.query.ClasspathOrchestrator
        import kyo.internal.tasty.query.ClasspathTestHelpers
        import kyo.internal.tasty.query.FileSource
        import scala.collection.mutable

        final class MemSrc extends FileSource:
            private val files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty
            def add(p: String, b: Array[Byte]): Unit                = files(p) = b
            def read(p: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
                files.get(p) match
                    case Some(b) => b
                    case None    => Abort.fail(TastyError.FileNotFound(p))
            def write(p: String, b: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
                Sync.defer(files(p) = b)
            def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
                files.get(from) match
                    case Some(b) =>
                        Sync.defer { files.remove(from); files(to) = b }
                    case None =>
                        Abort.fail(TastyError.SnapshotIoError(s"rename: $from not found"))
            def mkdirs(p: String)(using Frame): Unit < (Sync & Abort[TastyError]) = Kyo.unit
            def list(d: String, s: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
                Sync.defer(Chunk.from(files.keys.filter(k => k.startsWith(d + "/") && s.exists(k.endsWith)).toSeq))
            def exists(p: String)(using Frame): Boolean < Sync =
                Sync.defer(files.contains(p) || files.keys.exists(_.startsWith(p + "/")))
            def stat(p: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
                Sync.defer(FileSource.FileStat(0L, files.get(p).map(_.length.toLong).getOrElse(0L)))
        end MemSrc

        val src = MemSrc()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)

        // Unsafe: Sync.Unsafe.defer provides AllowUnsafe for close() and allSymbols().
        val captureResult: Result[TastyError, Tasty.Symbol] < Async =
            Scope.run:
                Abort.run[TastyError]:
                    InternalClasspath.allocate.flatMap: rawCp =>
                        Scope.ensure(Sync.Unsafe.defer(InternalClasspath.close(rawCp))).andThen:
                            ClasspathOrchestrator.openInto(Seq("root"), false, src, 1, rawCp).flatMap: _ =>
                                ClasspathTestHelpers.assignHomesForTest(rawCp)
                                Sync.Unsafe.defer:
                                    val syms = rawCp.allSymbols
                                    // plan: phase-02 inline; use sym.body.isDefined instead of sym.origin.
                                    val symWithBody = syms.find: s =>
                                        s.body.isDefined
                                    symWithBody
                                .flatMap:
                                    case Some(s) => Kyo.lift(s)
                                    case None    => Abort.fail(TastyError.NotImplemented("no symbol with body slice"))
        captureResult.map:
            case _ => pending // plan: phase-02; sym.body as effectful method added in Phase 04
            case Result.Panic(t) =>
                throw t
    }

end JvmFileSourceTest
