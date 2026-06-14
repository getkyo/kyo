package kyo.internal.tasty.query

import java.nio.file.Files
import java.nio.file.Paths
import kyo.*

/** JVM implementation of jar-backed ZipHandle construction.
  *
  * Opens a jar root and returns a ZipHandle backed by a memory-mapped CEN index. Uses JarMappedReader.init to mmap the jar and parse its
  * central directory once. readEntry reads only the requested entry by seeking to its offset in the mmap, not the entire jar. Cost is
  * O(CEN size + entry size) rather than O(jar size).
  *
  * listEntries enumerates matching entries via JarCentralDirectory, which reads the CEN from disk using a RandomAccessFile. This is a
  * separate read path from the mmap reader and incurs one stat + CEN scan per call.
  *
  * The JarMappedReader (and its MappedByteBuffer) outlives the Scope; the OS mapping is released when the buffer is GC'd. No explicit
  * unmap is performed (unsafe on Java 9+).
  *
  * Returns Maybe.Absent for non-jar paths (directories, jrt:/) and for paths that do not exist.
  */
private[kyo] object JvmZipHandle:

    def open(root: String)(using Frame): Maybe[ZipHandle] < (Sync & Scope & Abort[TastyError]) =
        if root.startsWith("jrt:/") then Maybe.Absent
        else if !root.toLowerCase.endsWith(".jar") then Maybe.Absent
        else
            Sync.Unsafe.defer {
                try
                    val path = Paths.get(root)
                    if !Files.exists(path) then Maybe.Absent
                    else
                        // Unsafe: JarMappedReader.init is synchronous and allocates a MappedByteBuffer;
                        // AllowUnsafe is propagated via Sync.Unsafe.defer. No Scope.acquireRelease is
                        // needed because the buffer GC lifecycle matches our needs.
                        val reader = JarMappedReader.init(root)
                        val handle = new ZipHandle:
                            def readEntry(internalPath: String)(using Frame): Maybe[Array[Byte]] < (Sync & Abort[TastyError]) =
                                Sync.Unsafe.defer {
                                    try Maybe.Present(reader.readEntry(internalPath))
                                    catch
                                        case _: java.io.FileNotFoundException => Maybe.Absent
                                        case ex: java.io.IOException =>
                                            Abort.fail(TastyError.FileNotFound(s"$root!/$internalPath: ${ex.getMessage}"))
                                }
                            def listEntries(suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
                                JarCentralDirectory.list(root, suffixes).map(_.map(_._2))
                        Maybe.Present(handle)
                    end if
                catch
                    case ex: java.io.IOException =>
                        Abort.fail(TastyError.FileNotFound(s"$root: ${ex.getMessage}"))
            }

end JvmZipHandle
