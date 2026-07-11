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

    /** Network/remote filesystems that are never enumerated. A statvfs against a dead network mount blocks
      * indefinitely and would wedge the sampler tick, so remote mounts are excluded up front (the local
      * fixed filesystems only contract, matching node_exporter's default filesystem filter). The `fuse.`
      * prefix covers remote FUSE mounts (fuse.sshfs, fuse.rclone) alongside these named types.
      */
    val skipNetworkFstypes: Set[String] = Set(
        "nfs",
        "nfs4",
        "cifs",
        "smb",
        "smbfs",
        "smb3",
        "afpfs",
        "9p",
        "ceph",
        "glusterfs",
        "lustre",
        "webdav",
        "davfs",
        "ncpfs",
        "afs",
        "gfs",
        "gfs2",
        "beegfs",
        "orangefs"
    )

    def enumerate(s: MachineSampler)(using AllowUnsafe): Chunk[String] =
        s.readScoped(Path("/proc/mounts"), (b, n) => parseMounts(b, n)).getOrElse(Chunk.empty)

    def parseMounts(bytes: Span[Byte], len: Int): Chunk[String] =
        Chunk.from(Text.fromSpan(bytes, len).lines.flatMap { l =>
            l.split(" ") match
                case a if a.length >= 3 && isPhysical(a(2)) =>
                    Iterator.single(unescapeMount(a(1)))
                case _ => Iterator.empty
        }.toSeq)

    /** A fstype is enumerated only when it is neither a pseudo/virtual nor a network/remote filesystem, and
      * is not a remote FUSE mount. `fuse.` covers remote FUSE transports; a plain local `fuse` (a local
      * FUSE-backed filesystem) is not blocked, matching the prior behavior for the non-remote case.
      */
    private def isPhysical(fstype: String): Boolean =
        !skipFstypes.contains(fstype) && !skipNetworkFstypes.contains(fstype) && !fstype.startsWith("fuse.")

    /** Decodes the octal escapes the kernel writes into `/proc/mounts` for whitespace/backslash in a mount
      * path: `\040` (space), `\011` (tab), `\012` (newline), `\134` (backslash). Any other backslash run is
      * left verbatim. An escaped path is decoded so the store-name rule and statvfs see the real path.
      */
    def unescapeMount(path: String): String =
        if !path.contains('\\') then path
        else
            val sb = new StringBuilder(path.length)
            @scala.annotation.tailrec
            def loop(i: Int): Unit =
                if i >= path.length then ()
                else if path.charAt(i) == '\\' && i + 3 < path.length && isOctal(path, i + 1) then
                    val code = (digit(path.charAt(i + 1)) * 64) + (digit(path.charAt(i + 2)) * 8) + digit(path.charAt(i + 3))
                    sb.append(code.toChar)
                    loop(i + 4)
                else
                    sb.append(path.charAt(i))
                    loop(i + 1)
            loop(0)
            sb.toString
        end if
    end unescapeMount

    private def isOctal(s: String, i: Int): Boolean =
        val a = s.charAt(i); val b = s.charAt(i + 1); val c = s.charAt(i + 2)
        a >= '0' && a <= '7' && b >= '0' && b <= '7' && c >= '0' && c <= '7'

    private def digit(c: Char): Int = c - '0'

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
