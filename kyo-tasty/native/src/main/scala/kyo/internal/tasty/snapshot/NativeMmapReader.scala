package kyo.internal.tasty.snapshot

import kyo.*
import kyo.internal.tasty.binary.MappedByteView
import scala.scalanative.posix.sys.stat as posixStat
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Scala Native POSIX mmap reader.
  *
  * Inits the snapshot file with open(2), maps it with mmap(2), and registers munmap as a Scope finalizer. The MappedByteView guards against
  * post-munmap reads via an AtomicBoolean flag, throwing IllegalStateException which Symbol.body maps to TastyError.ClasspathClosed.
  */
object NativeMmapReader:

    def init(path: String)(using Frame): MappedByteView < (Sync & Abort[TastyError] & Scope) =
        Sync.defer {
            try
                Zone {
                    val fd = NativeMmapBindings.openFile(toCString(path), 0) // O_RDONLY = 0
                    if fd < 0 then
                        Abort.fail(TastyError.FileNotFound(s"$path: open failed"))
                    else
                        val statBuf = alloc[posixStat.stat]()
                        if posixStat.fstat(fd, statBuf) < 0 then
                            val _ = NativeMmapBindings.close(fd)
                            Abort.fail(TastyError.FileNotFound(s"$path: fstat failed"))
                        else
                            val size      = statBuf._6.toLong // _6 = st_size (off_t) in posixlib stat struct
                            val mmapFd    = fd
                            val protRead  = 0x1               // PROT_READ
                            val mapShared = 0x2               // MAP_PRIVATE (0x02) -- we don't need to write back
                            val ptr       = NativeMmapBindings.mmap(null, size.toUSize, protRead, mapShared, mmapFd, 0)
                            val _         = NativeMmapBindings.close(fd)
                            if ptr == null then
                                Abort.fail(TastyError.FileNotFound(s"$path: mmap failed"))
                            else
                                val closed = new java.util.concurrent.atomic.AtomicBoolean(false)
                                val mapped = new MappedByteView(ptr, 0L, size, closed)
                                Scope.ensure(Sync.defer {
                                    closed.set(true)
                                    val _ = NativeMmapBindings.munmap(ptr, size.toUSize)
                                }).andThen(mapped)
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
