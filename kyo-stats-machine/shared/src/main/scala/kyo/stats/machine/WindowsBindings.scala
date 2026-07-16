package kyo.stats.machine

import kyo.Absent
import kyo.AllowUnsafe
import kyo.Chunk
import kyo.Maybe
import kyo.Present
import kyo.ffi.*

/** Windows Win32 binding over `kernel32` (a system DLL, so no bundled C): `GetSystemTimes`,
  * `GlobalMemoryStatusEx`, `GetLogicalDrives`, `GetDiskFreeSpaceExA`. Bound to the real Win32 ABI: the
  * `symbols` map pins each Scala method to its exact exported C symbol (the derivation would otherwise
  * snake-case to non-existent names, and `GetDriveType`/`GetDiskFreeSpaceEx` are `windows.h` macros that
  * expand to an `A` or `W` variant rather than being exports, so the concrete symbol is named explicitly).
  * The `A` (ANSI) forms are bound because every kyo-ffi backend marshals a Scala String as narrow UTF-8,
  * which is byte-identical to ANSI for the pure-ASCII drive-root strings (`"C:\\"`) this binding passes; a
  * `W` form would reinterpret those narrow bytes as UTF-16 and return garbage. `Long` is bound as int64_t
  * throughout (kyo-ffi Windows LP64/LLP64 handling), so the counters need no C-long adapter; each FILETIME
  * and ULARGE_INTEGER out-param is one little-endian 64-bit value read from a 1-long buffer.
  * `headers = Chunk("windows.h")` gates the Native `@extern` emission so a non-Windows Native build emits
  * runtime stubs instead of `@link("kernel32")` and does not fail to link. Every method is the unsafe FFI
  * tier: a trailing `(using AllowUnsafe)` and a bare-value return; a zero return is a Win32 failure the
  * Machine impl maps to Absent.
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

    /** GetDriveTypeA(lpRootPathName): the drive-type code for a root path (`C:\`). Only DRIVE_FIXED (3) is
      * a local fixed disk; network (4), removable (2), cdrom (5), and ramdisk (6) drives are excluded so a
      * disconnected network drive's GetDiskFreeSpaceExA cannot block the sampler tick.
      */
    def getDriveType(root: String)(using AllowUnsafe): Int

    /** GetDiskFreeSpaceExA(lpDirectoryName, lpFreeBytesAvailableToCaller, lpTotalNumberOfBytes,
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
            "getDriveType"       -> "GetDriveTypeA",
            "diskFreeSpace"      -> "GetDiskFreeSpaceExA"
        )
    ):

    /** GetDriveTypeA's DRIVE_FIXED code: a local fixed disk. Only these drives are enumerated for disk
      * metrics, so a network/removable/cdrom drive is never touched by GetDiskFreeSpaceExA.
      */
    val driveFixed: Int = 3

    /** sizeof(MEMORYSTATUSEX) in bytes; the struct is 64 bytes and its `dwLength` field must be preset to
      * this value before `GlobalMemoryStatusEx`, or the call fails.
      */
    val memoryStatusExSize: Long = 64L

    /** Presets `dwLength` (the low DWORD of index 0) to 64 in the caller's RETAINED 8-long (64-byte)
      * MEMORYSTATUSEX buffer and calls `GlobalMemoryStatusEx`, returning true when the call succeeded. The
      * buffer belongs to the reader, which allocates it once and closes it when the sampler's Scope closes;
      * the dwLength protocol lives here so every caller honors it, and both the memory and the swap rows
      * read the SAME filled buffer from ONE call per tick.
      */
    def fillMemoryStatus(b: WindowsBindings, out: Buffer[Long])(using AllowUnsafe): Boolean =
        out.setLong(0, memoryStatusExSize)
        b.globalMemoryStatus(out) != 0
    end fillMemoryStatus

end WindowsBindings
