package kyo.stats.machine

import kyo.*
import kyo.ffi.*

/** Enumerates the physical mounts from `/proc/mounts`, reads each one's free and total space through the
  * statvfs binding, and writes both into that mount's retained store cells. A per-mount statvfs failure
  * skips only that mount; an unreadable mounts file yields no mounts at all, never a throw.
  *
  * The mount set is RETAINED: each physical mount gets one `Store` holding its statvfs out-buffer and a
  * direct reference to its cells, resolved once and rebuilt only when the mount table actually changes. A
  * steady disk read rewinds the one retained `/proc/mounts` read handle, refills its retained buffer, and
  * byte-compares the refilled span against a retained fingerprint IN PLACE, with no copy: only a mismatch
  * copies the bytes into the new fingerprint and reparses the mount list. A steady read therefore consults
  * the store map never and allocates no out-buffer, no fingerprint copy and no parsed mount list, so the
  * disk path holds no per-read allocation.
  *
  * The fstype allow and deny lists are the real defense against a hung mount: a statvfs against a dead
  * network mount blocks until the kernel gives up, and no timeout can interrupt a syscall that has no
  * suspension point, so remote and virtual filesystems are excluded up front rather than probed. The lists
  * are best-effort by nature: a novel remote filesystem type nobody has listed can still be probed, which
  * is why the disk read runs on its own fiber, off the tick loop, guarded so a stuck mount is read once.
  */
final private[machine] class LinuxDisk(h: MachineHandles, s: MachineSampler, mountsPath: Path = Path("/proc/mounts"))(
    using AllowUnsafe
):

    private val mountsSlot = s.openSlot(mountsPath)

    private var fingerprint: Array[Byte]       = LinuxDisk.NoFingerprint
    private var stores: Chunk[LinuxDisk.Store] = Chunk.empty

    /** Rebuilds the retained store set only when the refilled `/proc/mounts` span differs from the retained
      * fingerprint, so the store map is consulted at init and on a mount-table change, never on the steady
      * read. Held as a field so a tick passes a reference rather than allocating a closure.
      */
    private val decodeMounts: MachineSampler.Decode = new MachineSampler.Decode:
        def apply(bytes: Span[Byte], len: Int)(using AllowUnsafe): Unit =
            if !LinuxDisk.sameFingerprint(bytes, len, fingerprint) then
                stores.foreach(_.out.close())
                val mounts = LinuxDisk.parseMounts(bytes, len)
                val names  = MachineHandles.storeNames(mounts)
                stores = Chunk.from(mounts.indices.map { i =>
                    new LinuxDisk.Store(mounts(i), Buffer.alloc[Long](16), h.diskStore(names(i)))
                })
                fingerprint = bytes.toArray.take(len)
            end if
        end apply
    end decodeMounts

    def read(bindings: Maybe[LinuxBindings])(using AllowUnsafe): Unit =
        bindings match
            case Present(b) =>
                refresh()
                @scala.annotation.tailrec
                def loop(i: Int): Unit =
                    if i < stores.length then
                        LinuxDisk.statvfsInto(b, stores(i))
                        loop(i + 1)
                loop(0)
            case Absent => ()
    end read

    /** Releases every retained statvfs out-buffer. Invoked once by the sampler's Scope finalizer. The
      * retained `/proc/mounts` read handle closes with every other opened slot through
      * `MachineSampler.closeHandles`.
      */
    def close()(using AllowUnsafe): Unit =
        stores.foreach(_.out.close())
        stores = Chunk.empty

    /** Rewinds the retained `/proc/mounts` handle, refills its retained buffer, and hands the refilled span
      * to `decodeMounts`, which rebuilds the store set only on a fingerprint mismatch.
      */
    private def refresh()(using AllowUnsafe): Unit =
        discard(s.readInto(mountsSlot, decodeMounts))

end LinuxDisk

private[machine] object LinuxDisk:

    private val NoFingerprint: Array[Byte] = Array.empty[Byte]

    /** One mount's retained disk state: the decoded mount path, the reused statvfs out-buffer, and a direct
      * reference to its cells. Held for the mount's lifetime; `out` is closed on `LinuxDisk.close` or on the
      * mount-table change that drops the mount.
      */
    final class Store(val mount: String, val out: Buffer[Long], val cell: MachineHandles.DiskStore)

    /** Byte-compares `bytes[0, len)` against the retained `fingerprint` IN PLACE: no copy, no allocation. A
      * length mismatch short-circuits before any byte is read.
      */
    private[machine] def sameFingerprint(bytes: Span[Byte], len: Int, fingerprint: Array[Byte]): Boolean =
        @scala.annotation.tailrec
        def loop(i: Int): Boolean =
            if i >= len then true
            else if bytes(i) != fingerprint(i) then false
            else loop(i + 1)
        len == fingerprint.length && loop(0)
    end sameFingerprint

    /** Pseudo and virtual filesystems that are never enumerated (physical filesystems only). */
    val SkipFstypes: Set[String] = Set(
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

    /** Network and remote filesystems that are never enumerated. A statvfs against a dead network mount
      * blocks until the kernel gives up and would wedge the disk read, so remote mounts are excluded up
      * front. The `fuse.` prefix covers remote FUSE transports alongside these named types.
      */
    val SkipNetworkFstypes: Set[String] = Set(
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

    def parseMounts(bytes: Span[Byte], len: Int): Chunk[String] =
        Chunk.from(LinuxText.lines(bytes, len).flatMap { l =>
            l.split(" ") match
                case a if a.length >= 3 && isPhysical(a(2)) => Iterator.single(unescapeMount(a(1)))
                case _                                      => Iterator.empty
        }.toSeq)

    /** A fstype is enumerated only when it is neither a pseudo or virtual filesystem nor a network or
      * remote one. `fuse.` covers remote FUSE transports; a plain local `fuse` filesystem is not blocked.
      */
    private def isPhysical(fstype: String): Boolean =
        !SkipFstypes.contains(fstype) && !SkipNetworkFstypes.contains(fstype) && !fstype.startsWith("fuse.")

    /** Decodes the octal escapes the kernel writes into `/proc/mounts` for whitespace and backslash in a
      * mount path: `\040` (space), `\011` (tab), `\012` (newline), `\134` (backslash). Any other backslash
      * run is left verbatim, so the store-name rule and statvfs both see the real path.
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

    /** Reads one mount through the binding and writes its two decoded primitives STRAIGHT into that mount's
      * retained cells. Nothing is returned: no tuple, no boxed value, no carrier of any kind, on any OS. A
      * failed call or a throw writes nothing, so that mount simply records no value this read (the whole
      * kyo-ffi binding surface is the throwing unsafe tier, and the caller bridges at its own call site).
      * The out-buffer is the store's RETAINED 16-long buffer, reused every read, and read back through
      * `Buffer`'s non-generic `getLong` accessor rather than the generic `get`, which boxes every element
      * through the `UnsafeLayout[A]` typeclass dispatch (JVM erasure), so this read allocates none.
      *
      * `struct statvfs` is 112 bytes on LP64 glibc and musl, so the out buffer is sized to 16 longs to hold
      * it with headroom. glibc and musl agree on the first five unsigned-long fields: f_bsize at index 0,
      * f_frsize at 1, f_blocks at 2, f_bfree at 3, f_bavail at 4. Bytes per block come from f_frsize, the
      * fragment size.
      */
    private[machine] def statvfsInto(bindings: LinuxBindings, store: Store)(using AllowUnsafe): Unit =
        try
            if bindings.statvfs(store.mount, store.out) == 0 then
                val frsize = store.out.getLong(1)
                val blocks = store.out.getLong(2)
                val bavail = store.out.getLong(4)
                store.cell.total.set(blocks * frsize)
                store.cell.free.observe(bavail * frsize)
            end if
        catch case ex: Throwable if scala.util.control.NonFatal(ex) => ()
    end statvfsInto

end LinuxDisk
