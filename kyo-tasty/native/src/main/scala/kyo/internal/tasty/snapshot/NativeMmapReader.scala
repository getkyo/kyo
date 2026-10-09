package kyo.internal.tasty.snapshot

import kyo.*
import kyo.internal.tasty.binary.MappedByteView
import scala.scalanative.posix.fcntl
import scala.scalanative.posix.sys.mman
import scala.scalanative.posix.sys.stat as posixStat
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Scala Native POSIX mmap reader.
  *
  * Inits the snapshot file with open(2), maps it with mmap(2), and registers munmap as a Scope finalizer. The MappedByteView guards against
  * post-munmap reads via an AtomicBoolean flag, throwing IllegalStateException which Symbol.body maps to TastyError.ClasspathClosed.
  *
  * An empty file is not mapped: mmap(2) rejects a zero length, while the JVM maps it as an empty buffer. Its view has no bytes, so every
  * read is out of bounds and the pointer is never dereferenced.
  */
object NativeMmapReader:

    def init(path: String)(using Frame): MappedByteView < (Sync & Abort[TastyError] & Scope) =
        Sync.defer {
            try
                Zone {
                    val fd = fcntl.open(toCString(path), fcntl.O_RDONLY)
                    if fd < 0 then
                        Abort.fail(TastyError.FileNotFound(s"$path: open failed"))
                    else
                        val statBuf = alloc[posixStat.stat]()
                        if posixStat.fstat(fd, statBuf) < 0 then
                            val _ = unistd.close(fd)
                            Abort.fail(TastyError.FileNotFound(s"$path: fstat failed"))
                        else
                            val size   = statBuf._6.toLong // _6 = st_size (off_t) in posixlib stat struct
                            val closed = new java.util.concurrent.atomic.AtomicBoolean(false)
                            if size == 0L then
                                val _ = unistd.close(fd)
                                Scope.ensure(Sync.defer(closed.set(true))).andThen(new MappedByteView(null, 0L, 0L, 0L, closed))
                            else
                                val ptr = mman.mmap(null, size.toUSize, mman.PROT_READ, mman.MAP_PRIVATE, fd, 0)
                                val _   = unistd.close(fd)
                                if ptr.toLong == mman.MAP_FAILED.toLong then
                                    Abort.fail(TastyError.FileNotFound(s"$path: mmap failed"))
                                else
                                    val mapped = new MappedByteView(ptr, size, 0L, size, closed)
                                    Scope.ensure(Sync.defer {
                                        closed.set(true)
                                        val _ = mman.munmap(ptr, size.toUSize)
                                    }).andThen(mapped)
                                end if
                            end if
                        end if
                    end if
                }
            catch
                case ex: Throwable =>
                    Abort.fail(TastyError.FileNotFound(s"$path: ${ex.getMessage}"))
        }
    end init

end NativeMmapReader
