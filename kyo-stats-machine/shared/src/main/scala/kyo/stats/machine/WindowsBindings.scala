package kyo.stats.machine

import kyo.Absent
import kyo.AllowUnsafe
import kyo.Chunk
import kyo.Maybe
import kyo.Present
import kyo.ffi.*

/** Windows Win32 binding over `kernel32` (a system DLL, so no bundled C): `GetSystemTimes`,
  * `GlobalMemoryStatusEx`, `GetLogicalDrives`, `GetDiskFreeSpaceExW`. Bound to the real Win32 ABI: the
  * `symbols` map pins each Scala method to its exact exported C symbol (the derivation would otherwise
  * snake-case to non-existent names, and `GetDiskFreeSpaceEx` is a macro, not an export, so the W form is
  * named explicitly). `Long` is bound as int64_t throughout (kyo-ffi Windows LP64/LLP64 handling), so the
  * counters need no C-long adapter; each FILETIME and ULARGE_INTEGER out-param is one little-endian 64-bit
  * value read from a 1-long buffer. `headers = Chunk("windows.h")` gates the Native `@extern` emission so a
  * non-Windows Native build emits runtime stubs instead of `@link("kernel32")` and does not fail to link.
  * Every method is the unsafe FFI tier: a trailing `(using AllowUnsafe)` and a bare-value return; a zero
  * return is a Win32 failure the Machine impl maps to Absent. Validated on a Windows host, not this CI.
  */
private[machine] trait WindowsBindings extends Ffi:
    /** GetSystemTimes(lpIdleTime, lpKernelTime, lpUserTime): three LPFILETIME out-params, each a FILETIME
      * read as one little-endian 100ns-unit int64. Returns non-zero on success. Each buffer holds 1 long.
      */
    def getSystemTimes(idle: Buffer[Long], kernel: Buffer[Long], user: Buffer[Long])(using AllowUnsafe): Int

    /** GlobalMemoryStatusEx(lpBuffer): fills a 64-byte MEMORYSTATUSEX. The caller MUST preset dwLength (the
      * first DWORD, packed into long index 0) to 64 before the call. Read as an 8-long buffer: index 0 packs
      * [dwLength|dwMemoryLoad]; index 1 ullTotalPhys, 2 ullAvailPhys, 3 ullTotalPageFile, 4 ullAvailPageFile,
      * 5 ullTotalVirtual, 6 ullAvailVirtual, 7 ullAvailExtendedVirtual. Returns non-zero on success.
      */
    def globalMemoryStatus(out: Buffer[Long])(using AllowUnsafe): Int

    /** GetLogicalDrives bitmask of present drive letters (bit 0 = A:). */
    def getLogicalDrives()(using AllowUnsafe): Int

    /** GetDiskFreeSpaceExW(lpDirectoryName, lpFreeBytesAvailableToCaller, lpTotalNumberOfBytes,
      * lpTotalNumberOfFreeBytes): three PULARGE_INTEGER out-params, each one int64. Returns non-zero on
      * success. Each buffer holds 1 long.
      */
    def diskFreeSpace(
        drive: String,
        availToCaller: Buffer[Long],
        total: Buffer[Long],
        totalFree: Buffer[Long]
    )(using AllowUnsafe): Int
end WindowsBindings

private[machine] object WindowsBindings
    extends Ffi.Config(
        library = "kernel32",
        headers = Chunk("windows.h"),
        symbols = Map(
            "getSystemTimes"     -> "GetSystemTimes",
            "globalMemoryStatus" -> "GlobalMemoryStatusEx",
            "getLogicalDrives"   -> "GetLogicalDrives",
            "diskFreeSpace"      -> "GetDiskFreeSpaceExW"
        )
    ):

    /** sizeof(MEMORYSTATUSEX) in bytes; the struct is 64 bytes and its `dwLength` field must be preset to
      * this value before `GlobalMemoryStatusEx`, or the call fails.
      */
    val memoryStatusExSize: Long = 64L

    /** Allocates an 8-long (64-byte) MEMORYSTATUSEX buffer, presets `dwLength` (the low DWORD of index 0) to
      * 64, and calls `GlobalMemoryStatusEx`. Returns the filled buffer on success (the caller closes it),
      * Absent on a zero return. Centralizes the dwLength protocol so both memory and swap reads honor it.
      */
    def withMemoryStatus(b: WindowsBindings)(using AllowUnsafe): Maybe[Buffer[Long]] =
        val out = Buffer.alloc[Long](8)
        out.set(0, memoryStatusExSize)
        if b.globalMemoryStatus(out) == 0 then
            out.close()
            Absent
        else Present(out)
        end if
    end withMemoryStatus

end WindowsBindings
