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
  * Tests use synthetic JARs built programmatically via ZipOutputStream. One test uses a real jar from the JVM
  * classpath that contains .tasty entries.
  */
class JvmFileSourceTest extends kyo.test.Test[Any]:

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

    "read single .tasty entry from jar returns known bytes".onlyJvm in {
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

    "read multiple entries from jar, each returns correct bytes".onlyJvm in {
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

    "missing entry in existing jar returns Abort[TastyError.FileNotFound]".onlyJvm in {
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

    "missing jar file returns Abort[TastyError.FileNotFound]".onlyJvm in {
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

    "real-classpath jar: list entries then read first 20, all return non-empty bytes".onlyJvm in {
        // Find a real jar from the JVM classpath that has.tasty entries.
        val cpEntries = java.lang.System.getProperty("java.class.path", "")
            .split(java.io.File.pathSeparator)
            .toSeq
            .filter(_.endsWith(".jar"))
            .filter(p => java.nio.file.Files.isRegularFile(java.nio.file.Paths.get(p)))

        // Pick the first jar that has at least one.tasty entry.
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
                // No jar with.tasty on classpath: skip (not a test failure)
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

    "JarMappedReader reads all entries from a synthetic jar and returns correct bytes".onlyJvm in {
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
        val reader = JarMappedReader.init(jarPath)
        val r1     = reader.readEntry("kyo/Alpha.tasty")
        val r2     = reader.readEntry("kyo/Beta.tasty")
        val r3     = reader.readEntry("kyo/Gamma.tasty")
        assert(java.util.Arrays.equals(r1, knownBytes1), "Alpha.tasty bytes mismatch")
        assert(java.util.Arrays.equals(r2, knownBytes2), "Beta.tasty bytes mismatch")
        assert(java.util.Arrays.equals(r3, knownBytes3), "Gamma.tasty bytes mismatch")
    }

    "JarMappedReader concurrent reads from multiple threads return correct bytes".onlyJvm in {
        import AllowUnsafe.embrace.danger
        Async.timeout(10.seconds) {
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
            val reader     = JarMappedReader.init(jarPath)
            val fiberCount = 8
            for
                startLatch <- Latch.init(1)
                fibers <- Kyo.foreach(Chunk.from(0 until fiberCount)): fid =>
                    Fiber.initUnscoped(
                        startLatch.await.andThen {
                            Sync.defer {
                                val r1 = reader.readEntry("kyo/Alpha.tasty")
                                val r2 = reader.readEntry("kyo/Beta.tasty")
                                val r3 = reader.readEntry("kyo/Gamma.tasty")
                                assert(java.util.Arrays.equals(r1, knownBytes1), s"Fiber $fid: Alpha bytes mismatch")
                                assert(java.util.Arrays.equals(r2, knownBytes2), s"Fiber $fid: Beta bytes mismatch")
                                assert(java.util.Arrays.equals(r3, knownBytes3), s"Fiber $fid: Gamma bytes mismatch")
                            }
                        }
                    )
                _ <- startLatch.release
                _ <- Kyo.foreach(fibers)(_.get)
            yield succeed
            end for
        }
    }

    "JarMappedReader reads entries with GPB bit-3 (data-descriptor flag) set in LFH".onlyJvm in {
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

        val reader = JarMappedReader.init(jarPath)
        val result = reader.readEntry("kyo/DataDesc.tasty")
        assert(
            java.util.Arrays.equals(result, knownBytes1),
            s"Expected original bytes after bit-3 patch, got ${result.length} bytes"
        )
    }

    "JarMappedReader reads STORED (method=0) entry correctly".onlyJvm in {
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

        val reader = JarMappedReader.init(jarPath)
        val result = reader.readEntry("kyo/Stored.tasty")
        assert(
            java.util.Arrays.equals(result, storedBytes),
            s"STORED entry bytes mismatch: expected ${storedBytes.length} bytes, got ${result.length}"
        )
    }

    "withReadBatch releases pool and clears activePool after successful body".onlyJvm in {
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

    "withReadBatch releases pool and clears activePool when body raises Abort failure".onlyJvm in {
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

    "JarMappedReader.readEntry rejects lfhOffset > Int.MaxValue with IOException".onlyJvm in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/big-offset.jar"
        writeJar(jarPath, Seq(("kyo/BigOffset.tasty", knownBytes1)))

        // Open the reader to get a valid mbb, then inject an oversized lfhOffset via reflection.
        val reader = JarMappedReader.init(jarPath)

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

    "JarMappedReader.init on empty file throws IOException with 'empty file' message".onlyJvm in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/empty.jar"
        // Write an empty file so RandomAccessFile succeeds but channel.size == 0.
        java.nio.file.Files.write(java.nio.file.Paths.get(jarPath), Array.emptyByteArray)

        val caught =
            try
                JarMappedReader.init(jarPath)
                None
            catch
                case ex: java.io.IOException => Some(ex)
                case t: Throwable            => throw t

        assert(caught.isDefined, "Expected IOException for empty JAR but init() succeeded")
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

    "JarMappedReader.init on malformed JAR throws plain IOException from parseAllEntries".onlyJvm in {
        val dir     = makeTempDir()
        val jarPath = s"$dir/not-a-jar.jar"
        // 4 bytes of garbage: channel.map succeeds but parseAllEntries rejects the content.
        java.nio.file.Files.write(java.nio.file.Paths.get(jarPath), Array[Byte](0x00, 0x01, 0x02, 0x03))

        val caught =
            try
                JarMappedReader.init(jarPath)
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

    "50 concurrent fibers reading the same JAR entry all succeed and pool is cleared on scope exit".onlyJvm in {
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
                    fail(s"unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
            end match
    }

    "Tasty.Classpath remains accessible after scope exits (no Closed state)".onlyJvm in {
        import kyo.internal.tasty.query.ClasspathOrchestrator
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

        // Open a classpath inside a Scope; capture the immutable case class.
        // After Scope exits, the case class remains fully accessible (no Closed state).
        var capturedCp: Tasty.Classpath = null
        val openResult: Result[TastyError, Unit] < Async =
            Abort.run[TastyError]:
                Scope.run:
                    ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1).map: cp =>
                        capturedCp = cp
        openResult.map:
            case Result.Success(_) =>
                // After scope exit, the immutable case class is still valid (no Closed state).
                assert(capturedCp != null, "Classpath should have been captured")
                assert(capturedCp.symbols.nonEmpty, "Classpath should have symbols")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

end JvmFileSourceTest
