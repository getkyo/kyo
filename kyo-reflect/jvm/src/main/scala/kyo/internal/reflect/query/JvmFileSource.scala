package kyo.internal.reflect.query

import java.net.URI
import java.nio.file.Files
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.jar.JarFile
import kyo.*
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** JVM implementation of `FileSource`.
  *
  * Supports:
  *   - Plain directory trees (recursively lists `.tasty` and `.class` files via `Files.walk`)
  *   - JAR files (lists entries via `JarFile`)
  *   - JDK module paths via `jrt:/` URI scheme (accessible via `FileSystems.getFileSystem(URI.create("jrt:/"))`)
  *
  * All paths use `String` to remain consistent with the shared API. The `jrt:/` filesystem is accessed lazily to avoid loading it when not
  * needed.
  */
object JvmFileSource extends FileSource:

    def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[ReflectError]) =
        Sync.defer:
            try
                if path.startsWith("jrt:/") then
                    readJrtPath(path)
                else if path.toLowerCase.endsWith(".jar") then
                    Abort.fail(ReflectError.FileNotFound(s"$path: reading individual paths inside JARs not supported; use list() first"))
                else
                    Files.readAllBytes(Paths.get(path))
            catch
                case ex: java.io.IOException =>
                    Abort.fail(ReflectError.FileNotFound(s"$path: ${ex.getMessage}"))

    def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[ReflectError]) =
        Sync.defer:
            try
                Files.write(
                    Paths.get(path),
                    bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
                ): Unit
            catch
                case ex: java.io.IOException =>
                    Abort.fail(ReflectError.SnapshotIoError(ex.getMessage))

    def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[ReflectError]) =
        Sync.defer:
            try
                Files.move(Paths.get(from), Paths.get(to), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE): Unit
            catch
                case ex: java.io.IOException =>
                    Abort.fail(ReflectError.SnapshotIoError(ex.getMessage))

    def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[ReflectError]) =
        Sync.defer:
            try
                val p = Paths.get(path)
                if !Files.exists(p) then Files.createDirectories(p): Unit
            catch
                case ex: java.io.IOException =>
                    Abort.fail(ReflectError.SnapshotIoError(ex.getMessage))

    def list(dir: String, suffix: String)(using Frame): Chunk[String] < (Sync & Abort[ReflectError]) =
        Sync.defer:
            try
                if dir.startsWith("jrt:/") then
                    listJrtPath(dir, suffix)
                else if dir.toLowerCase.endsWith(".jar") then
                    listJarEntries(dir, suffix)
                else
                    val root = Paths.get(dir)
                    if !Files.exists(root) then Chunk.empty
                    else
                        val results = mutable.ArrayBuffer.empty[String]
                        Files.walk(root).iterator().asScala.foreach: p =>
                            val name = p.getFileName.toString
                            if name.endsWith(suffix) && Files.isRegularFile(p) then
                                results += p.toString
                        Chunk.from(results.toSeq)
                    end if
            catch
                case ex: java.io.IOException =>
                    Abort.fail(ReflectError.FileNotFound(s"$dir: ${ex.getMessage}"))

    def exists(path: String)(using Frame): Boolean < Sync =
        Sync.defer:
            try
                if path.startsWith("jrt:/") then
                    val fs = jrtFileSystem
                    fs != null && Files.exists(fs.getPath(path.stripPrefix("jrt:/")))
                else
                    Files.exists(Paths.get(path))
            catch
                case _: Throwable => false

    def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[ReflectError]) =
        Sync.defer:
            try
                val p     = Paths.get(path)
                val mtime = Files.getLastModifiedTime(p).toMillis
                val size  = Files.size(p)
                FileSource.FileStat(mtime, size)
            catch
                case ex: java.io.IOException =>
                    Abort.fail(ReflectError.FileNotFound(s"$path: ${ex.getMessage}"))

    /** Lazy JRT filesystem handle. Returns null if JRT filesystem is unavailable. */
    private lazy val jrtFileSystem: java.nio.file.FileSystem =
        try FileSystems.getFileSystem(URI.create("jrt:/"))
        catch
            case _: Throwable => null

    private def readJrtPath(path: String): Array[Byte] =
        val fs = jrtFileSystem
        if fs == null then throw new java.io.IOException(s"JRT filesystem not available for: $path")
        val jrtPath = fs.getPath(path.stripPrefix("jrt:/"))
        Files.readAllBytes(jrtPath)
    end readJrtPath

    private def listJrtPath(dir: String, suffix: String): Chunk[String] =
        val fs = jrtFileSystem
        if fs == null then Chunk.empty
        else
            val jrtPath = fs.getPath(dir.stripPrefix("jrt:/"))
            if !Files.exists(jrtPath) then Chunk.empty
            else
                val results = mutable.ArrayBuffer.empty[String]
                Files.walk(jrtPath).iterator().asScala.foreach: p =>
                    val name = p.getFileName.toString
                    if name.endsWith(suffix) && Files.isRegularFile(p) then
                        results += "jrt:/" + p.toString
                Chunk.from(results.toSeq)
            end if
        end if
    end listJrtPath

    private def listJarEntries(jarPath: String, suffix: String): Chunk[String] =
        val jar     = new JarFile(jarPath)
        val results = mutable.ArrayBuffer.empty[String]
        try
            jar.entries().asIterator().asScala.foreach: entry =>
                if !entry.isDirectory && entry.getName.endsWith(suffix) then
                    results += s"$jarPath!/${entry.getName}"
        finally
            jar.close()
        end try
        Chunk.from(results.toSeq)
    end listJarEntries

end JvmFileSource
