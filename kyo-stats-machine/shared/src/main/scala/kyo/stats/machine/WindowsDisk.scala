package kyo.stats.machine

import kyo.*
import kyo.ffi.*

/** Windows fixed-drive enumeration via GetLogicalDrives, free/total via GetDiskFreeSpaceExW. */
private[machine] object WindowsDisk:

    /** Decode the GetLogicalDrives bitmask into drive-root strings (`C:\`, `D:\`, ...). */
    def enumerate(b: WindowsBindings)(using AllowUnsafe): Chunk[String] =
        val mask = b.getLogicalDrives()
        Chunk.from((0 until 26).iterator.flatMap { i =>
            if (mask & (1 << i)) != 0 then Iterator.single(('A' + i).toChar.toString + ":\\") else Iterator.empty
        }.toSeq)
    end enumerate

    def stat(b: WindowsBindings, drive: String)(using AllowUnsafe): Machine.DiskReading =
        // GetDiskFreeSpaceExW writes three PULARGE_INTEGER out-params: bytes available to caller, total
        // bytes, total free bytes. total = the second; free = the third (total free, not caller-available).
        val availB = Buffer.alloc[Long](1)
        val totalB = Buffer.alloc[Long](1)
        val freeB  = Buffer.alloc[Long](1)
        try
            if b.diskFreeSpace(drive, availB, totalB, freeB) == 0 then Machine.DiskReading(drive, Absent, Absent)
            else Machine.DiskReading(drive, Present(totalB.get(0)), Present(freeB.get(0)))
        finally
            availB.close(); totalB.close(); freeB.close()
        end try
    end stat

end WindowsDisk
