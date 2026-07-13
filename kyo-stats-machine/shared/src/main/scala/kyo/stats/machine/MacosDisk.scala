package kyo.stats.machine

import kyo.*
import kyo.ffi.*

/** Enumerates the physical mounts through the `getfsstat` binding, reads each one's free and total space
  * through the statfs binding, and writes both into that mount's retained store cells. A per-mount statfs
  * failure skips only that mount; a failed or empty enumeration yields no mounts at all, never a throw.
  *
  * The mount set is RETAINED: one `mounts` call fills a caller-owned buffer with NUL-separated
  * `<mount>\0<fstype>\0` pairs, and each physical mount gets one `Store` holding its statfs out-buffer and a
  * direct reference to its cells, resolved once and rebuilt only when the mount table actually changes
  * (detected by byte-comparing the enumeration buffer against a retained fingerprint). A steady disk read
  * iterates those retained references and writes primitives straight into the cells: it consults the store
  * map never, and allocates no out-buffer, so the disk path holds no per-read allocation.
  *
  * The fstype allow and deny list is the real defense against a hung mount: a statfs against a dead network
  * mount blocks until the kernel gives up, and no timeout can interrupt a syscall that has no suspension
  * point, so remote and virtual filesystems are excluded up front rather than probed. The list is
  * best-effort by nature: a novel remote filesystem type nobody has listed can still be probed, which is why
  * the disk read runs on its own fiber, off the tick loop, guarded so a stuck mount is read once.
  */
final private[machine] class MacosDisk(h: MachineHandles)(using AllowUnsafe):

    private val mountsBuf = Buffer.alloc[Byte](MacosDisk.MountsCap)

    private var fingerprint: Array[Byte]       = MacosDisk.NoFingerprint
    private var stores: Chunk[MacosDisk.Store] = Chunk.empty

    def read(b: MacosBindings)(using AllowUnsafe): Unit =
        refresh(b)
        @scala.annotation.tailrec
        def loop(i: Int): Unit =
            if i < stores.length then
                MacosDisk.statfsInto(b, stores(i))
                loop(i + 1)
        loop(0)
    end read

    /** Releases every retained statfs out-buffer and the enumeration buffer. Invoked once by the sampler's
      * Scope finalizer.
      */
    def close()(using AllowUnsafe): Unit =
        stores.foreach(_.out.close())
        stores = Chunk.empty
        mountsBuf.close()
    end close

    /** Re-derives the retained store set only when the mount table changed since the last read, so the store
      * map is consulted at init and on a mount-table change, never on the steady read. On a change the old
      * out-buffers are closed and one new `Store` per physical mount is built with a retained out-buffer and
      * a direct reference to its cells. A failed or empty enumeration (a non-positive count, including the
      * buffer-too-small -1) leaves the store set unchanged.
      */
    private def refresh(b: MacosBindings)(using AllowUnsafe): Unit =
        val count = b.mounts(mountsBuf, MacosDisk.MountsCap)
        if count > 0 then
            val snap = MacosDisk.snapshot(mountsBuf, count)
            if !java.util.Arrays.equals(snap.raw, fingerprint) then
                stores.foreach(_.out.close())
                val names = MachineHandles.storeNames(snap.mounts)
                stores = Chunk.from(snap.mounts.indices.map { i =>
                    new MacosDisk.Store(snap.mounts(i), Buffer.alloc[Long](2), h.diskStore(names(i)))
                })
                fingerprint = snap.raw
            end if
        end if
    end refresh

end MacosDisk

private[machine] object MacosDisk:

    /** The enumeration buffer capacity. A host whose mount text exceeds this returns -1 from the shim and
      * records no disk metrics for that read, rather than truncating a mount path into a wrong one.
      */
    val MountsCap: Int = 64 * 1024

    private val NoFingerprint: Array[Byte] = Array.empty[Byte]

    /** One mount's retained disk state: the decoded mount path, the reused statfs out-buffer, and a direct
      * reference to its cells. Held for the mount's lifetime; `out` is closed on `MacosDisk.close` or on the
      * mount-table change that drops the mount.
      */
    final class Store(val mount: String, val out: Buffer[Long], val cell: MachineHandles.DiskStore)

    /** One enumeration read: the raw pair bytes retained as the change fingerprint, and the physical mount
      * paths parsed once from them. Built on the mount-change path only, so it may allocate.
      */
    final class Snapshot(val raw: Array[Byte], val mounts: Chunk[String])

    /** Pseudo and virtual filesystems plus network and remote filesystems, none enumerated (local physical
      * only). The network types are excluded because a statfs against a dead remote mount blocks until the
      * kernel gives up, and no timeout can interrupt a syscall with no suspension point.
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

    /** Reads the `count` NUL-separated `<mount>\0<fstype>\0` pairs back out of the caller-owned buffer,
      * keeping the physical mounts only, and copies the exact bytes read as the change fingerprint. Runs at
      * init and on a real mount change, never on the steady read, so it may allocate.
      */
    def snapshot(buf: Buffer[Byte], count: Int)(using AllowUnsafe): Snapshot =
        val mounts = Chunk.newBuilder[String]
        @scala.annotation.tailrec
        def loop(i: Int, at: Int): Int =
            if i >= count then at
            else
                val mountEnd  = cStringEnd(buf, at)
                val mount     = utf8(buf, at, mountEnd - at)
                val fstypeAt  = mountEnd + 1
                val fstypeEnd = cStringEnd(buf, fstypeAt)
                val fstype    = utf8(buf, fstypeAt, fstypeEnd - fstypeAt)
                if !skipFstypes.contains(fstype) then mounts += mount
                loop(i + 1, fstypeEnd + 1)
        val used = loop(0, 0)
        new Snapshot(Buffer.copyToArray[Byte](buf, 0, used), mounts.result())
    end snapshot

    /** Index of the NUL terminator at or after `at`. */
    private def cStringEnd(buf: Buffer[Byte], at: Int)(using AllowUnsafe): Int =
        @scala.annotation.tailrec
        def loop(i: Int): Int = if buf.get(i) == 0 then i else loop(i + 1)
        loop(at)
    end cStringEnd

    /** Decodes `len` bytes at offset `at` as a UTF-8 string. */
    private def utf8(buf: Buffer[Byte], at: Int, len: Int)(using AllowUnsafe): String =
        new String(Buffer.copyToArray[Byte](buf, at, len), java.nio.charset.StandardCharsets.UTF_8)

    /** Reads one mount through the binding and writes its two decoded primitives STRAIGHT into that mount's
      * retained cells. Nothing is returned: no tuple, no boxed value, no carrier of any kind, on any OS. A
      * failed call or a throw writes nothing, so that mount simply records no value this read (the whole
      * kyo-ffi binding surface is the throwing unsafe tier, and the caller bridges at its own call site). The
      * out-buffer is the store's RETAINED 2-long buffer, reused every read, so this read allocates none.
      *
      * The shim projects `statfs` to [total, free] bytes: total at index 0, free at index 1.
      */
    private[machine] def statfsInto(b: MacosBindings, store: Store)(using AllowUnsafe): Unit =
        try
            if b.statfs(store.mount, store.out) == 0 then
                store.cell.total.set(store.out.get(0))
                store.cell.free.observe(store.out.get(1))
            end if
        catch case ex: Throwable if scala.util.control.NonFatal(ex) => ()
    end statfsInto

end MacosDisk
