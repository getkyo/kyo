package kyo.stats.machine

import kyo.*
import kyo.ffi.*

/** Enumerates physical mounts from `/proc/mounts` (fstype-filtered), reads free/total via statvfs, and
  * sanitizes each mount path into a Stat scope segment. A per-mount statvfs failure skips only that
  * mount; an unreadable mounts file yields an empty set, never a throw.
  */
private[machine] object LinuxDisk:

    /** Pseudo/virtual filesystems that are never enumerated (physical filesystems only). */
    val skipFstypes: Set[String] = Set(
        "proc",
        "sysfs",
        "cgroup",
        "cgroup2",
        "tmpfs",
        "devtmpfs",
        "devpts",
        "mqueue",
        "overlay",
        "squashfs",
        "debugfs",
        "tracefs",
        "securityfs",
        "pstore",
        "bpf",
        "configfs",
        "fusectl",
        "hugetlbfs",
        "autofs",
        "binfmt_misc",
        "nsfs",
        "ramfs",
        "rpc_pipefs"
    )

    def enumerate(s: MachineSampler)(using AllowUnsafe): Chunk[String] =
        s.readScoped(Path("/proc/mounts"), (b, n) => parseMounts(b, n)).getOrElse(Chunk.empty)

    def parseMounts(bytes: Span[Byte], len: Int): Chunk[String] =
        Chunk.from(Text.fromSpan(bytes, len).lines.flatMap { l =>
            l.split(" ") match
                case a if a.length >= 3 && !skipFstypes.contains(a(2)) && !a(2).startsWith("fuse.") =>
                    Iterator.single(a(1))
                case _ => Iterator.empty
        }.toSeq)

    def stat(mount: String, statvfs: String => Maybe[(Long, Long)])(using AllowUnsafe): Machine.DiskReading =
        statvfs(mount) match
            case Present((total, free)) => Machine.DiskReading(mount, Present(total), Present(free))
            case Absent                 => Machine.DiskReading(mount, Absent, Absent)

    /** statvfs via the Linux binding: total = f_blocks*f_frsize, free = f_bavail*f_frsize. Absent on error.
      *
      * `struct statvfs` is 112 bytes on LP64 glibc and musl; the C library writes the whole struct, so the
      * out buffer is sized to 16 longs (128 bytes) to hold it with headroom, never the 8 longs that would
      * take a 48-byte overwrite past the end. glibc and musl agree on the first five unsigned-long fields:
      * f_bsize at index 0, f_frsize at 1, f_blocks at 2, f_bfree at 3, f_bavail at 4. Bytes/block come from
      * f_frsize (the fragment size), so total = f_blocks * f_frsize and free = f_bavail * f_frsize.
      */
    def statvfsRaw(bindings: LinuxBindings, mount: String)(using AllowUnsafe): Maybe[(Long, Long)] =
        val buf = Buffer.alloc[Long](16)
        try
            if bindings.statvfs(mount, buf) != 0 then Absent
            else
                val frsize = buf.get(1); val blocks = buf.get(2); val bavail = buf.get(4)
                Present((blocks * frsize, bavail * frsize))
            end if
        finally buf.close()
        end try
    end statvfsRaw

end LinuxDisk
