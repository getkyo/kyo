package kyo.internal.reflect.query

import java.util.concurrent.ConcurrentHashMap

/** Thread-safe pool of JarMappedReader instances, one per JAR path.
  *
  * Multiple fibers/threads that concurrently read different entries from the same JAR share one JarMappedReader. The MappedByteBuffer
  * inside each JarMappedReader is duplicated per `readEntry` call, so concurrent reads are safe.
  *
  * Lifecycle: installed by JvmFileSource.withReadBatch at the start of a ClasspathOrchestrator.openInto call and released (map cleared) at
  * the end via Scope.ensure. Clearing the map drops the JarMappedReader references; the OS memory mappings are released when the
  * MappedByteBuffers are GC'd.
  */
final private[kyo] class JarMappedReaderPool:

    private val cache: ConcurrentHashMap[String, JarMappedReader] = new ConcurrentHashMap()

    /** Return the cached JarMappedReader for jarPath, opening a new one if absent.
      *
      * Thread-safe: ConcurrentHashMap.computeIfAbsent provides the atomic open-if-absent guarantee.
      *
      * @throws java.io.IOException
      *   if JarMappedReader.open fails (propagated from computeIfAbsent lambda via UncheckedIOException)
      */
    def get(jarPath: String): JarMappedReader =
        try
            cache.computeIfAbsent(jarPath, path => JarMappedReader.open(path))
        catch
            case ex: java.io.UncheckedIOException => throw ex.getCause
    end get

    /** Clear the pool, releasing all JarMappedReader references.
      *
      * Called by the Scope.ensure finalizer registered in JvmFileSource.withReadBatch. Does not explicitly unmap MappedByteBuffers; GC
      * handles reclamation.
      */
    def closeAll(): Unit =
        cache.clear()

end JarMappedReaderPool
