package kyo

import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kyo.internal.reflect.query.JvmFileSource

/** Tests for JvmFileSource.read with jar!/entry paths.
  *
  * Tests T-J1 through T-J5 per the jar-entry-read fix specification.
  *
  * Tests T-J1, T-J2, T-J3, T-J4 use synthetic JARs built programmatically via ZipOutputStream. Test T-J5 uses a real jar from the JVM
  * classpath that contains .tasty entries.
  */
class JvmFileSourceTest extends Test:

    private def makeTempDir(): String =
        Files.createTempDirectory("kyo-reflect-jvmfilesource-test").toAbsolutePath.toString

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
        Abort.run[ReflectError](
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

        Abort.run[ReflectError](
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

    // T-J3: Missing entry in existing jar returns Abort[ReflectError.FileNotFound].
    "T-J3: missing entry in existing jar returns Abort[ReflectError.FileNotFound]" taggedAs jvmOnly in run {
        val dir     = makeTempDir()
        val jarPath = s"$dir/one-entry.jar"
        writeJar(jarPath, Seq(("kyo/Exists.tasty", knownBytes1)))
        val fullPath = s"$jarPath!/kyo/DoesNotExist.tasty"
        Abort.run[ReflectError](
            JvmFileSource.read(fullPath)
        ).map:
            case Result.Success(_) =>
                fail(s"Expected Abort[ReflectError.FileNotFound] for missing entry, got success")
            case Result.Failure(e) =>
                e match
                    case ReflectError.FileNotFound(_) => succeed
                    case other                        => fail(s"Expected FileNotFound but got: $other")
            case Result.Panic(t) =>
                throw t
    }

    // T-J4: Missing jar file returns Abort[ReflectError.FileNotFound].
    "T-J4: missing jar file returns Abort[ReflectError.FileNotFound]" taggedAs jvmOnly in run {
        val dir      = makeTempDir()
        val jarPath  = s"$dir/nonexistent.jar"
        val fullPath = s"$jarPath!/kyo/Foo.tasty"
        Abort.run[ReflectError](
            JvmFileSource.read(fullPath)
        ).map:
            case Result.Success(_) =>
                fail(s"Expected Abort[ReflectError.FileNotFound] for missing jar, got success")
            case Result.Failure(e) =>
                e match
                    case ReflectError.FileNotFound(_) => succeed
                    case other                        => fail(s"Expected FileNotFound but got: $other")
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
                Abort.run[ReflectError](
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

end JvmFileSourceTest
