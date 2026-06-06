package kyo.internal.tasty.snapshot

import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kyo.*
import kyo.internal.tasty.binary.MappedByteView
import kyo.internal.tasty.query.FileSource

/** JVM memory-mapped snapshot reader using java.nio.channels.FileChannel.map.
  *
  * Opens the file at `path`, creates a MappedByteBuffer for the entire file content. A shared AtomicBoolean tracks whether the mapping is
  * logically closed. The Scope finalizer sets closed=true, invalidating subsequent ByteView reads by throwing IllegalStateException.
  * Symbol.body catches IllegalStateException and maps it to TastyError.ClasspathClosed.
  *
  * Note: The JVM does not guarantee immediate physical un-mapping; the AtomicBoolean provides the logical close semantics.
  *
  * FD exhaustion gate: a process-wide Semaphore limits concurrent open mmap file descriptors to
  * `JvmMmapReader.maxOpenFds` (default 128, overridable via system property `kyo.tasty.mmap.maxOpenFds`). When the semaphore is
  * exhausted, additional callers block until a permit is released on close.
  */
object JvmMmapReader:

    /** Maximum number of concurrently open mmap file descriptors.
      *
      * Reads the system property `kyo.tasty.mmap.maxOpenFds` at startup. Defaults to 128 if the property is absent or not a positive
      * integer.
      */
    val maxOpenFds: Int =
        val prop = java.lang.System.getProperty("kyo.tasty.mmap.maxOpenFds")
        if prop == null then 128
        else
            try
                val n = prop.toInt
                if n > 0 then n else 128
            catch case _: NumberFormatException => 128
        end if
    end maxOpenFds

    private val fdSemaphore: java.util.concurrent.Semaphore =
        new java.util.concurrent.Semaphore(maxOpenFds, true)

    /** Init `path` as a MappedByteView, registering a close finalizer on the Scope.
      *
      * Acquires one permit from the fd semaphore before opening the file. The permit is released when the Scope closes. If the semaphore
      * is exhausted, the current thread blocks until a permit becomes available.
      *
      * @param path
      *   Absolute filesystem path to the .krfl snapshot file.
      * @return
      *   A MappedByteView over the entire file content. Scope finalizer releases the semaphore permit and sets the closed flag.
      */
    def init(path: String)(using Frame): MappedByteView < (Sync & Abort[TastyError] & Scope) =
        Sync.defer:
            // Acquire permit before opening the file to bound concurrent open FDs.
            // Uses a boolean to track whether the semaphore was acquired so the error path releases exactly once.
            fdSemaphore.acquire()
            var permitHeld = true
            try
                val channel = FileChannel.open(Paths.get(path), StandardOpenOption.READ)
                try
                    val size   = channel.size()
                    val buf    = channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
                    val closed = new java.util.concurrent.atomic.AtomicBoolean(false)
                    val mapped = new MappedByteView(buf, 0L, size, closed)
                    // Transfer ownership of the permit to the scope finalizer.
                    // The permit is released when the scope closes.
                    permitHeld = false
                    Scope.ensure(Sync.defer {
                        closed.set(true)
                        fdSemaphore.release()
                    }).andThen(mapped)
                finally
                    channel.close()
                end try
            catch
                case ex: java.io.IOException =>
                    if permitHeld then
                        fdSemaphore.release()
                        permitHeld = false
                    Abort.fail(TastyError.FileNotFound(s"$path: ${ex.getMessage}"))
                case ex: Throwable =>
                    if permitHeld then
                        fdSemaphore.release()
                        permitHeld = false
                    throw ex
            end try
    end init

end JvmMmapReader
