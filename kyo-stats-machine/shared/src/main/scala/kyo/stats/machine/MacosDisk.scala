package kyo.stats.machine

import kyo.*
import kyo.ffi.*

/** macOS per-mount disk enumeration over getmntinfo + statfs, fstype-filtered the same way as Linux. */
private[machine] object MacosDisk:

    /** Pseudo/virtual filesystems plus network/remote filesystems, none enumerated (local physical only).
      * The network types (smbfs/nfs/afpfs/webdav/ftp) are excluded because a statfs against a dead remote
      * mount blocks the sampler tick indefinitely.
      */
    val skipFstypes: Set[String] = Set(
        "devfs",
        "autofs",
        "nullfs",
        "tmpfs",
        "fdesc",
        "smbfs",
        "nfs",
        "afpfs",
        "webdav",
        "ftp",
        "cifs",
        "osxfuse"
    )

    /** Enumerate mounts via the shim; each entry is a mount path. Physical filesystems only. */
    def enumerate(b: MacosBindings)(using AllowUnsafe): Chunk[String] =
        val count = b.mountCount()
        if count <= 0 then Chunk.empty
        else
            Chunk.from((0 until count).iterator.flatMap { i =>
                val fstype = b.mountFstype(i).value
                if skipFstypes.contains(fstype) then Iterator.empty
                else Iterator.single(b.mountPath(i).value)
            }.toSeq)
        end if
    end enumerate

    def stat(b: MacosBindings, mount: String)(using AllowUnsafe): Machine.DiskReading =
        val out = Buffer.alloc[Long](2)
        try
            if b.statfs(mount, out) != 0 then Machine.DiskReading(mount, Absent, Absent)
            else Machine.DiskReading(mount, Present(out.get(0)), Present(out.get(1)))
        finally out.close()
        end try
    end stat

end MacosDisk
