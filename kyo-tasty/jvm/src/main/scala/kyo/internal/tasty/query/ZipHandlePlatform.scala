package kyo.internal.tasty.query

import java.net.URI
import java.nio.file.Files
import java.nio.file.FileSystems
import kyo.*
import scala.jdk.CollectionConverters.*

/** JVM platform-specific ZipHandle factory and pool management. */
private[kyo] object ZipHandlePlatform:

    def open(root: String)(using Frame): Maybe[ZipHandle] < (Sync & Scope & Abort[TastyError]) =
        JvmZipHandle.open(root)

    /** Install a fresh JarMappedReaderPool for the duration of `body`.
      *
      * A Scope.ensure finalizer calls pool.closeAll() on any exit (success, Abort, interrupt), dropping all cached MappedByteBuffer
      * references. The Scope effect is consumed by the enclosing Scope.run in ClasspathOrchestrator.
      */
    def withPool[A, S](body: A < S)(using Frame): A < (S & Sync & Scope) =
        Scope.acquireRelease(
            Sync.defer {
                val pool = new JarMappedReaderPool()
                JvmJarPool.active.set(pool)
                pool
            }
        )(pool => Sync.defer(pool.closeAll()).andThen(Sync.defer(JvmJarPool.active.set(null)))).map { _ =>
            body
        }

    /** Read one entry from a jar using the active JarMappedReaderPool when available, falling back to a one-shot reader otherwise. */
    def readJarEntry(jarPath: String, entryName: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
        Sync.Unsafe.defer {
            try
                // Unsafe: AllowUnsafe is supplied implicitly by the enclosing Sync.Unsafe.defer block;
                // we bind it to a named val and pass it explicitly to the AtomicLong .inc / .add calls
                // below because Scala 3 implicit resolution inside a try block is ambiguous when the
                // body alternates between AtomicLong perf-stat calls and pool-reader operations.
                // The named pass-through is the same proof; no widening of the unsafe boundary.
                val au: AllowUnsafe = AllowUnsafe.embrace.danger
                TastyPerfStats.jarOpens.inc()(using au)
                val t0   = java.lang.System.nanoTime()
                val pool = JvmJarPool.active.get()
                if pool != null then
                    val reader = pool.get(jarPath)
                    val t1     = java.lang.System.nanoTime()
                    TastyPerfStats.jarConstructNs.add(t1 - t0)(using au)
                    val bytes = reader.readEntry(entryName)
                    val t2    = java.lang.System.nanoTime()
                    TastyPerfStats.jarReadNs.add(t2 - t1)(using au)
                    bytes
                else
                    val reader = JarMappedReader.init(jarPath)
                    val t1     = java.lang.System.nanoTime()
                    TastyPerfStats.jarConstructNs.add(t1 - t0)(using au)
                    val bytes = reader.readEntry(entryName)
                    val t2    = java.lang.System.nanoTime()
                    TastyPerfStats.jarReadNs.add(t2 - t1)(using au)
                    bytes
                end if
            catch
                case ex: java.io.IOException =>
                    Abort.fail(TastyError.FileNotFound(s"$jarPath!/$entryName: ${ex.getMessage}"))
        }

    /** Read the raw bytes of a single `jrt:/` class file.
      *
      * Strips the `"jrt:/"` prefix, resolves the path in the JRT filesystem, and reads all bytes. Raises `Abort[TastyError.FileNotFound]`
      * when the JRT filesystem is unavailable or the path does not exist.
      */
    def readJrtEntry(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
        Sync.Unsafe.defer {
            try
                val fs      = FileSystems.getFileSystem(URI.create("jrt:/"))
                val jrtPath = fs.getPath(path.stripPrefix("jrt:/"))
                Files.readAllBytes(jrtPath)
            catch
                case ex: java.io.IOException =>
                    Abort.fail(TastyError.FileNotFound(s"$path: ${ex.getMessage}"))
        }

    /** List all entries under a jrt:/ path whose names end with any of the given suffixes.
      *
      * Walks the JRT filesystem (mounted by the JVM at `jrt:/`). Returns paths prefixed with `jrt:/`. Returns Chunk.empty when the JRT
      * filesystem is unavailable or when the root path does not exist within it.
      *
      * Used by ClasspathOrchestrator.walkRoot for jrt:/ roots.
      */
    def listJrtEntries(root: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
        if suffixes.isEmpty then Sync.defer(Chunk.empty)
        else
            Sync.defer {
                try
                    val fs      = FileSystems.getFileSystem(URI.create("jrt:/"))
                    val jrtPath = fs.getPath(root.stripPrefix("jrt:/"))
                    if !Files.exists(jrtPath) then Chunk.empty
                    else
                        val results = scala.collection.mutable.ArrayBuffer.empty[String]
                        Files.walk(jrtPath).iterator().asScala.foreach { p =>
                            val name  = p.getFileName.toString
                            var i     = 0
                            var found = false
                            while i < suffixes.length && !found do
                                if name.endsWith(suffixes(i)) then
                                    results += "jrt:/" + p.toString
                                    found = true
                                i += 1
                            end while
                        }
                        Chunk.from(results.toSeq)
                    end if
                catch
                    case ex: java.io.IOException =>
                        Abort.fail(TastyError.FileNotFound(s"$root: ${ex.getMessage}"))
            }

end ZipHandlePlatform

/** JVM-internal holder for the active JarMappedReaderPool across withPool/readJarEntry calls. */
private[kyo] object JvmJarPool:
    /** Currently active pool, or null when no pool is installed. AtomicReference for cross-fiber visibility. */
    // Unsafe: null sentinel is Java-interop; checked on every readJarEntry call.
    val active: java.util.concurrent.atomic.AtomicReference[JarMappedReaderPool] =
        new java.util.concurrent.atomic.AtomicReference(null)
end JvmJarPool
