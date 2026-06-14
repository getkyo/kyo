package kyo.internal.tasty.query

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** POSIX FFI bindings for low-level file I/O operations.
  *
  * Uses `@extern` bindings to the platform libc. Only the fields we need are included. The struct layouts use CStruct types that must match
  * the platform ABI. We use a simplified struct with only the fields we read (size and mtime).
  *
  * Note: fstat and stat for size/mtime queries now delegate to scalanative.posix.sys.stat which provides a canonical cross-platform struct.
  * The custom StatBuf here is only used in listDir methods to check file-type bits in st_mode (S_IFREG / S_IFDIR).
  *
  * Carve-out: POSIX @extern - parameter names mirror libc ABI. Identifiers like `buf` are required to match
  * the C signatures exactly; the naming convention does not apply to extern declarations.
  */
@extern
private[query] object PosixFileBindings:

    /** Simplified stat struct used only for directory listing file-type checks.
      *
      * Layout: we only need the mode field to check S_IFREG / S_IFDIR. The CStruct8 of Longs is used solely to satisfy the ABI requirement
      * that the buffer is large enough. Field `_2` maps to st_mode bits on this custom layout; exact bit positions are tested against
      * S_IFREG (0x8000) and S_IFDIR (0x4000) masks in listDirNative.
      */
    type StatBuf = CStruct8[Long, Long, Long, Long, Long, Long, Long, Long]

    /** POSIX open(2). flags: 0 = O_RDONLY. */
    def open(path: CString, flags: CInt): CInt = extern

    /** POSIX open(2) with mode (for O_CREAT). */
    @name("open")
    def openCreate(path: CString, flags: CInt, mode: CInt): CInt = extern

    /** POSIX read(2). */
    def read(fd: CInt, buf: Ptr[Byte], count: CInt): CInt = extern

    /** POSIX write(2). */
    def write(fd: CInt, buf: Ptr[Byte], count: CInt): CInt = extern

    /** POSIX close(2). */
    def close(fd: CInt): CInt = extern

    /** POSIX stat(2) for directory listing only. Follows symlinks.
      *
      * Returns 0 on success. The `buf` is a raw 64-byte opaque buffer; callers access only `_2` for mode bits.
      */
    def stat(path: CString, buf: Ptr[StatBuf]): CInt = extern

    /** POSIX rename(2). Atomic on POSIX when on the same filesystem. */
    def rename(from: CString, to: CString): CInt = extern

    /** POSIX mkdir(2). Mode 0755 for directories. */
    def mkdir(path: CString, mode: CInt): CInt = extern

    /** POSIX opendir(3). */
    def opendir(path: CString): Ptr[Byte] = extern

    /** POSIX readdir(3). Returns null at end of directory. */
    def readdir(dir: Ptr[Byte]): Ptr[Byte] = extern

    /** POSIX closedir(3). */
    def closedir(dir: Ptr[Byte]): CInt = extern

    /** Extract the d_name field from a dirent struct.
      *
      * The dirent struct layout varies by platform. We use a helper that returns a CString pointer to the name. This avoids hardcoding
      * struct offsets.
      */
    @name("kyo_reflect_dirent_name")
    def direntName(entry: Ptr[Byte]): CString = extern

    /** O_WRONLY flag constant. Provided as a C extern to be platform-independent. */
    @name("kyo_reflect_O_WRONLY")
    def O_WRONLY(): CInt = extern

    /** O_CREAT flag constant. */
    @name("kyo_reflect_O_CREAT")
    def O_CREAT(): CInt = extern

    /** O_TRUNC flag constant. */
    @name("kyo_reflect_O_TRUNC")
    def O_TRUNC(): CInt = extern

end PosixFileBindings
