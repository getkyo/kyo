package kyo.internal.reflect.snapshot

import kyo.*
import kyo.internal.reflect.binary.MappedByteView
import kyo.internal.reflect.query.FileSource
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Scala Native POSIX mmap reader.
  *
  * Opens the snapshot file with open(2), maps it with mmap(2), and registers munmap as a Scope finalizer. The MappedByteView guards against
  * post-munmap reads via an AtomicBoolean flag, throwing IllegalStateException which Symbol.body maps to ReflectError.ClasspathClosed.
  */
object NativeMmapReader:

    def open(path: String)(using Frame): MappedByteView < (Sync & Abort[ReflectError] & Scope) =
        Sync.defer:
            try
                Zone:
                    val fd = NativeMmapBindings.openFile(toCString(path), 0) // O_RDONLY = 0
                    if fd < 0 then
                        Abort.fail(ReflectError.FileNotFound(s"$path: open failed"))
                    else
                        val statBuf = alloc[NativeMmapBindings.StatBuf]()
                        if NativeMmapBindings.fstat(fd, statBuf) < 0 then
                            val _ = NativeMmapBindings.close(fd)
                            Abort.fail(ReflectError.FileNotFound(s"$path: fstat failed"))
                        else
                            val size      = (!statBuf)._1.toLong
                            val mmapFd    = fd
                            val protRead  = 0x1 // PROT_READ
                            val mapShared = 0x2 // MAP_PRIVATE (0x02) -- we don't need to write back
                            val ptr       = NativeMmapBindings.mmap(null, size.toUSize, protRead, mapShared, mmapFd, 0)
                            val _         = NativeMmapBindings.close(fd)
                            if ptr == null then
                                Abort.fail(ReflectError.FileNotFound(s"$path: mmap failed"))
                            else
                                val closed = new java.util.concurrent.atomic.AtomicBoolean(false)
                                val mapped = new MappedByteView(ptr, 0L, size, closed)
                                Scope.ensure(Sync.defer:
                                    closed.set(true)
                                    val _ = NativeMmapBindings.munmap(ptr, size.toUSize)).andThen(mapped)
                            end if
                        end if
                    end if
            catch
                case ex: Throwable =>
                    Abort.fail(ReflectError.FileNotFound(s"$path: ${ex.getMessage}"))
    end open

end NativeMmapReader

@extern
private object NativeMmapBindings:
    type StatBuf = CStruct8[Long, Long, Long, Long, Long, Long, Long, Long]

    @name("open")
    def openFile(path: CString, flags: CInt): CInt = extern
    def fstat(fd: CInt, buf: Ptr[StatBuf]): CInt   = extern
    def close(fd: CInt): CInt                      = extern
    def mmap(
        addr: Ptr[Byte],
        length: CSize,
        prot: CInt,
        flags: CInt,
        fd: CInt,
        offset: CLong
    ): Ptr[Byte] = extern
    def munmap(addr: Ptr[Byte], length: CSize): CInt = extern
end NativeMmapBindings
