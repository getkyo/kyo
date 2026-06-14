package kyo.internal

import kyo.discard
import scala.scalanative.libc.errno
import scala.scalanative.libc.stdlib
import scala.scalanative.libc.string as cstring
import scala.scalanative.posix.fcntl
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Native factory implementations for [[UnsafeBuffer]].
  *
  * Uses `stdlib.malloc` / `stdlib.free` for allocation and POSIX `mmap` / `munmap` for memory-mapped files.
  */
private[kyo] object UnsafeBufferPlatform:

    def alloc(byteSize: Long): UnsafeBuffer =
        val ptr = stdlib.malloc(byteSize.toCSize)
        if ptr == null then throw new OutOfMemoryError(s"malloc($byteSize) returned null")
        discard(cstring.memset(ptr, 0, byteSize.toCSize))
        new NativeUnsafeBuffer(ptr, byteSize, () => stdlib.free(ptr))
    end alloc

    /** Native has no confined/shared distinction -- same as [[alloc]]. */
    def allocConfined(byteSize: Long): UnsafeBuffer = alloc(byteSize)

    def fromArray(arr: Array[Byte]): UnsafeBuffer =
        val size = arr.length.toLong
        val ptr  = stdlib.malloc(size.toCSize)
        if ptr == null then throw new OutOfMemoryError(s"malloc($size) returned null")
        var i = 0
        while i < arr.length do
            !(ptr + i) = arr(i)
            i += 1
        new NativeUnsafeBuffer(ptr, size, () => stdlib.free(ptr))
    end fromArray

    def fromUtf8(s: String): UnsafeBuffer =
        val bytes     = s.getBytes("UTF-8")
        val totalSize = bytes.length.toLong + 1 // NUL terminator
        val ptr       = stdlib.malloc(totalSize.toCSize)
        if ptr == null then throw new OutOfMemoryError(s"malloc($totalSize) returned null")
        var i = 0
        while i < bytes.length do
            !(ptr + i) = bytes(i)
            i += 1
        !(ptr + bytes.length) = 0.toByte // NUL terminator
        new NativeUnsafeBuffer(ptr, totalSize, () => stdlib.free(ptr))
    end fromUtf8

    // --- Memory-mapped files ---

    def mmapReadOnly(path: String, offset: Long, size: Long): UnsafeBuffer =
        mmapImpl(path, offset, size, readOnly = true)

    def mmapReadWrite(path: String, offset: Long, size: Long): UnsafeBuffer =
        mmapImpl(path, offset, size, readOnly = false)

    /** Direct C bindings for mmap/munmap/msync/getpagesize using CLongLong to avoid opaque-type boxing issues with
      * scala.scalanative.posix.sys.mman wrappers (CSize/off_t are opaque types that box incorrectly in closures).
      */
    @extern
    private object MmapC:
        @name("mmap")
        def mmapNative(addr: Ptr[Byte], length: CLongLong, prot: CInt, flags: CInt, fd: CInt, offset: CLongLong): Ptr[Byte] = extern

        @name("munmap")
        def munmapNative(addr: Ptr[Byte], length: CLongLong): CInt = extern

        @name("msync")
        def msyncNative(addr: Ptr[Byte], length: CLongLong, flags: CInt): CInt = extern

        def getpagesize(): CInt = extern
    end MmapC

    // POSIX mmap constants
    private val PROT_READ   = 1
    private val PROT_WRITE  = 2
    private val MAP_SHARED  = 1
    private val MAP_PRIVATE = 2
    private val MS_SYNC_VAL = 4 // MS_SYNC on macOS

    private def mmapImpl(path: String, offset: Long, size: Long, readOnly: Boolean): UnsafeBuffer =
        val zone  = Zone.open()
        val flags = if readOnly then fcntl.O_RDONLY else fcntl.O_RDWR
        val fd    = fcntl.open(toCString(path)(using zone), flags)
        zone.close()
        if fd < 0 then
            throw new java.io.IOException(s"Cannot open $path: errno=${errno.errno}")
        try
            val fileSize: Long = new java.io.File(path).length()
            val mapSize: Long  = if size < 0 then fileSize - offset else size

            if mapSize == 0 then
                new NativeUnsafeBuffer(null.asInstanceOf[Ptr[Byte]], 0L, () => ())
            else
                val prot =
                    if readOnly then PROT_READ
                    else PROT_READ | PROT_WRITE
                val mapFl =
                    if readOnly then MAP_PRIVATE
                    else MAP_SHARED
                // POSIX mmap requires offset to be a multiple of the page size.
                val pageSize: Long   = MmapC.getpagesize().toLong
                val alignedOff: Long = (offset / pageSize) * pageSize
                val extraBytes: Long = offset - alignedOff
                val totalMap: Long   = mapSize + extraBytes
                errno.errno = 0
                val basePtr = MmapC.mmapNative(null.asInstanceOf[Ptr[Byte]], totalMap, prot, mapFl, fd, alignedOff)
                val err     = errno.errno
                if basePtr == null || err != 0 then
                    throw new java.io.IOException(s"mmap failed for $path: errno=$err")
                val ptr            = basePtr + extraBytes.toInt
                val closerTotalMap = totalMap
                val isRO           = readOnly
                val mmapCloser: () => Unit = () =>
                    if isRO then
                        discard(MmapC.munmapNative(basePtr, closerTotalMap))
                    else
                        discard(MmapC.msyncNative(basePtr, closerTotalMap, MS_SYNC_VAL))
                        discard(MmapC.munmapNative(basePtr, closerTotalMap))
                new NativeUnsafeBuffer(ptr, mapSize, mmapCloser)
            end if
        finally
            discard(unistd.close(fd))
        end try
    end mmapImpl

    def wrapBorrowed(raw: AnyRef, byteSize: Long): UnsafeBuffer =
        raw match
            case np: NativeUnsafeBufferPtr =>
                new NativeUnsafeBuffer(np.ptr, byteSize, () => ())
            case other =>
                throw new IllegalArgumentException(
                    s"wrapBorrowed expects a NativeUnsafeBufferPtr on Native, got: ${other.getClass.getName}"
                )
    end wrapBorrowed

end UnsafeBufferPlatform
