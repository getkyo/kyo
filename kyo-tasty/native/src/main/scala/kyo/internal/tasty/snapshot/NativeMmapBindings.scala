package kyo.internal.tasty.snapshot

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** POSIX FFI bindings for memory-mapped file access used by `NativeMmapReader`.
  *
  * Uses `@extern` bindings to the platform libc. Only the four syscalls the snapshot reader needs are bound here:
  * `open(2)` (renamed from `open` to avoid the Scala keyword collision), `close(2)`, `mmap(2)`, and `munmap(2)`.
  *
  * Carve-out: POSIX @extern - parameter names mirror libc ABI. Identifiers like `addr` and `buf` are required
  * to match the C signatures exactly; the naming convention does not apply to extern declarations.
  */
@extern
private[snapshot] object NativeMmapBindings:
    @name("open")
    def openFile(path: CString, flags: CInt): CInt = extern
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
