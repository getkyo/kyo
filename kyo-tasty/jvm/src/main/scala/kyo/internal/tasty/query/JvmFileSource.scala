package kyo.internal.tasty.query

import java.net.URI
import java.nio.file.Files
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicReference
import kyo.*
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** JVM implementation of `FileSource`.
  *
  * Supports:
  *   - Plain directory trees (recursively lists `.tasty` and `.class` files via `Files.walk`)
  *   - JAR files (lists entries via JarCentralDirectory, a direct CEN reader)
  *   - JDK module paths via `jrt:/` URI scheme (accessible via `FileSystems.getFileSystem(URI.create("jrt:/"))`)
  *
  * All paths use `String` to remain consistent with the shared API. The `jrt:/` filesystem is accessed lazily to avoid loading it when not
  * needed.
  *
  * Jar reads: within a `withReadBatch` scope, jar entries are read via a shared JarMappedReaderPool (one MappedByteBuffer per jar, shared
  * across fibers). Outside a batch scope, a fallback mmap read is performed without pooling.
  */
object JvmFileSource extends FileSource:

    def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
        Sync.Unsafe.defer:
            try
                if path.startsWith("jrt:/") then
                    readJrtPath(path)
                else
                    val jarSepIdx = path.indexOf("!/")
                    if jarSepIdx > 0 then
                        readJarEntry(path.substring(0, jarSepIdx), path.substring(jarSepIdx + 2))
                    else if path.toLowerCase.endsWith(".jar") then
                        Abort.fail(TastyError.FileNotFound(
                            s"$path: reading individual paths inside JARs requires `jar!/entry` syntax; use list() first"
                        ))
                    else
                        Files.readAllBytes(Paths.get(path))
                    end if
            catch
                case ex: java.io.IOException =>
                    Abort.fail(TastyError.FileNotFound(s"$path: ${ex.getMessage}"))

    def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
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
                    Abort.fail(TastyError.SnapshotIoError(ex.getMessage))

    def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
        Sync.defer:
            try
                Files.move(Paths.get(from), Paths.get(to), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE): Unit
            catch
                case ex: java.io.IOException =>
                    Abort.fail(TastyError.SnapshotIoError(ex.getMessage))

    def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
        Sync.defer:
            try
                val p = Paths.get(path)
                if !Files.exists(p) then Files.createDirectories(p): Unit
            catch
                case ex: java.io.IOException =>
                    Abort.fail(TastyError.SnapshotIoError(ex.getMessage))

    def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
        if suffixes.isEmpty then Sync.defer(Chunk.empty)
        else if dir.startsWith("jrt:/") then
            Sync.defer:
                try
                    listJrtPathMulti(dir, suffixes)
                catch
                    case ex: java.io.IOException =>
                        Abort.fail(TastyError.FileNotFound(s"$dir: ${ex.getMessage}"))
        else if dir.toLowerCase.endsWith(".jar") then
            JarCentralDirectory.list(dir, suffixes).map: pairs =>
                pairs.map((jarPath, entryName) => s"$jarPath!/$entryName")
        else
            Sync.defer:
                try
                    val root = Paths.get(dir)
                    if !Files.exists(root) then Chunk.empty
                    else
                        val results = mutable.ArrayBuffer.empty[String]
                        Files.walk(root).iterator().asScala.foreach: p =>
                            val name  = p.getFileName.toString
                            var i     = 0
                            var found = false
                            while i < suffixes.length && !found do
                                if name.endsWith(suffixes(i)) then found = true
                                i += 1
                            if found then results += p.toString
                        Chunk.from(results.toSeq)
                    end if
                catch
                    case ex: java.io.IOException =>
                        Abort.fail(TastyError.FileNotFound(s"$dir: ${ex.getMessage}"))

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

    def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
        Sync.defer:
            try
                val p     = Paths.get(path)
                val mtime = Files.getLastModifiedTime(p).toMillis
                val size  = Files.size(p)
                FileSource.FileStat(mtime, size)
            catch
                case ex: java.io.IOException =>
                    Abort.fail(TastyError.FileNotFound(s"$path: ${ex.getMessage}"))

    /** Currently active JarMappedReaderPool, installed by withReadBatch.
      *
      * Unsafe: null sentinel is Java-interop for "no active pool"; checked on every readJarEntry call. AtomicReference provides visibility
      * across fibers without locks.
      */
    // Unsafe: null sentinel for "no active pool"; Java AtomicReference interop
    private val activePool: AtomicReference[JarMappedReaderPool] = new AtomicReference(null)

    /** Install a fresh JarMappedReaderPool for the duration of `body`, then clear it on exit.
      *
      * A Scope.ensure finalizer calls pool.closeAll() on any exit (success, Abort, interrupt), dropping all cached MappedByteBuffer
      * references. The Scope effect is introduced here and discharged by the enclosing Scope.run in ClasspathOrchestrator.
      */
    override def withReadBatch[A, S](body: A < S)(using Frame): A < (S & Sync & Scope) =
        Scope.acquireRelease(
            Sync.defer:
                val pool = new JarMappedReaderPool()
                activePool.set(pool)
                pool
        )(pool => Sync.defer(pool.closeAll()).andThen(Sync.defer(activePool.set(null)))).flatMap: _ =>
            body

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

    private def readJarEntry(jarPath: String, entryName: String)(using AllowUnsafe): Array[Byte] =
        TastyPerfStats.jarOpens.inc()
        val t0 = java.lang.System.nanoTime()
        // Unsafe: activePool.get() may return null when no batch is active
        val pool = activePool.get()
        if pool != null then
            val reader = pool.get(jarPath)
            val t1     = java.lang.System.nanoTime()
            TastyPerfStats.jarConstructNs.add(t1 - t0)
            val bytes = reader.readEntry(entryName)
            val t2    = java.lang.System.nanoTime()
            TastyPerfStats.jarReadNs.add(t2 - t1)
            bytes
        else
            // Fallback: init-on-demand mmap reader (not pooled; GC handles cleanup)
            val reader = JarMappedReader.init(jarPath)
            val t1     = java.lang.System.nanoTime()
            TastyPerfStats.jarConstructNs.add(t1 - t0)
            val bytes = reader.readEntry(entryName)
            val t2    = java.lang.System.nanoTime()
            TastyPerfStats.jarReadNs.add(t2 - t1)
            bytes
        end if
    end readJarEntry

    private def listJrtPathMulti(dir: String, suffixes: Chunk[String]): Chunk[String] =
        val fs = jrtFileSystem
        if fs == null then Chunk.empty
        else
            val jrtPath = fs.getPath(dir.stripPrefix("jrt:/"))
            if !Files.exists(jrtPath) then Chunk.empty
            else
                val results = mutable.ArrayBuffer.empty[String]
                Files.walk(jrtPath).iterator().asScala.foreach: p =>
                    val name  = p.getFileName.toString
                    var i     = 0
                    var found = false
                    while i < suffixes.length && !found do
                        if name.endsWith(suffixes(i)) then
                            results += "jrt:/" + p.toString
                            found = true
                        i += 1
                    end while
                Chunk.from(results.toSeq)
            end if
        end if
    end listJrtPathMulti

end JvmFileSource
