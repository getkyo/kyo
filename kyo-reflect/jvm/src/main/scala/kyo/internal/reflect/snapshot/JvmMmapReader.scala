package kyo.internal.reflect.snapshot

import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kyo.*
import kyo.internal.reflect.binary.MappedByteView
import kyo.internal.reflect.query.FileSource

/** JVM memory-mapped snapshot reader using java.nio.channels.FileChannel.map.
  *
  * Opens the file at `path`, creates a MappedByteBuffer for the entire file content. A shared AtomicBoolean tracks whether the mapping is
  * logically closed. The Scope finalizer sets closed=true, invalidating subsequent ByteView reads by throwing IllegalStateException.
  * Symbol.body catches IllegalStateException and maps it to ReflectError.ClasspathClosed.
  *
  * Note: The JVM does not guarantee immediate physical un-mapping; the AtomicBoolean provides the logical close semantics.
  */
object JvmMmapReader:

    /** Open `path` as a MappedByteView, registering a close finalizer on the Scope.
      *
      * @param path
      *   Absolute filesystem path to the .krfl snapshot file.
      * @return
      *   A MappedByteView over the entire file content. Scope finalizer sets the closed flag.
      */
    def open(path: String)(using Frame): MappedByteView < (Sync & Abort[ReflectError] & Scope) =
        Sync.defer:
            try
                val channel = FileChannel.open(Paths.get(path), StandardOpenOption.READ)
                try
                    val size   = channel.size()
                    val buf    = channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
                    val closed = new java.util.concurrent.atomic.AtomicBoolean(false)
                    val mapped = new MappedByteView(buf, 0L, size, closed)
                    Scope.ensure(Sync.defer(closed.set(true))).andThen(mapped)
                finally
                    channel.close()
                end try
            catch
                case ex: java.io.IOException =>
                    Abort.fail(ReflectError.FileNotFound(s"$path: ${ex.getMessage}"))
    end open

end JvmMmapReader
