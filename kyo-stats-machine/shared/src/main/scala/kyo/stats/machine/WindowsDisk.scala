package kyo.stats.machine

import kyo.*
import kyo.ffi.*

/** Enumerates the local fixed drives from the `GetLogicalDrives` bitmask, reads each one's free and total
  * space through `GetDiskFreeSpaceExA`, and writes both into that drive's retained store cells.
  *
  * The drive set is RETAINED: each fixed drive gets one `Store` holding its three free-space out-buffers and
  * a direct reference to its cells, resolved once and rebuilt only when the `GetLogicalDrives` bitmask
  * actually changes (compared against a retained fingerprint). A steady disk read iterates those retained
  * references and writes primitives straight into the cells: it consults `h.diskStore` never, and allocates
  * no out-buffer, so the disk path holds no per-read allocation.
  *
  * Only DRIVE_FIXED drives are enumerated: a free-space call against an unreachable network drive can park a
  * worker until the share times out, so removable, network, cdrom and ramdisk drives are filtered out up
  * front rather than probed.
  */
final private[machine] class WindowsDisk(h: MachineHandles)(using AllowUnsafe):

    private var fingerprint: Int                 = WindowsDisk.NotEnumerated
    private var stores: Chunk[WindowsDisk.Store] = Chunk.empty

    def read(b: WindowsBindings)(using AllowUnsafe): Unit =
        refresh(b)
        @scala.annotation.tailrec
        def loop(i: Int): Unit =
            if i < stores.length then
                WindowsDisk.diskFreeInto(b, stores(i))
                loop(i + 1)
        loop(0)
    end read

    /** Releases every retained free-space out-buffer. Invoked once by the sampler's Scope finalizer. */
    def close()(using AllowUnsafe): Unit =
        stores.foreach(_.close())
        stores = Chunk.empty

    /** Re-derives the retained store set only when the `GetLogicalDrives` bitmask changed since the last
      * read, so `h.diskStore` is consulted at init and on a drive-set change, never on the steady read. On a
      * change the old out-buffers are closed and one new `Store` per fixed drive is built with three retained
      * out-buffers and a direct reference to its cells. The sentinel fingerprint the field starts at differs
      * from every real bitmask (a real mask sets only the low 26 bits, one per drive letter), so the first
      * read always builds.
      */
    private def refresh(b: WindowsBindings)(using AllowUnsafe): Unit =
        val mask = b.getLogicalDrives()
        if mask != fingerprint then
            stores.foreach(_.close())
            val drives = WindowsDisk.enumerate(b, mask)
            val names  = MachineHandles.storeNames(drives)
            stores = Chunk.from(drives.indices.map { i =>
                new WindowsDisk.Store(
                    drives(i),
                    Buffer.alloc[Long](1),
                    Buffer.alloc[Long](1),
                    Buffer.alloc[Long](1),
                    h.diskStore(names(i))
                )
            })
            fingerprint = mask
        end if
    end refresh

end WindowsDisk

private[machine] object WindowsDisk:

    /** Sentinel bitmask no real `GetLogicalDrives` result equals: only bits 0..25 are ever set (one per
      * drive letter A..Z), so a value with the high bits set forces the first read to derive the store set.
      */
    private val NotEnumerated: Int = -1

    /** One fixed drive's retained disk state: the drive root, the three reused free-space out-buffers, and a
      * direct reference to its cells. Held for the drive's lifetime; the buffers are closed on
      * `WindowsDisk.close` or on the bitmask change that drops the drive.
      */
    final class Store(
        val drive: String,
        val availToCaller: Buffer[Long],
        val total: Buffer[Long],
        val totalFree: Buffer[Long],
        val cell: MachineHandles.DiskStore
    ):
        def close()(using AllowUnsafe): Unit =
            availToCaller.close()
            total.close()
            totalFree.close()
        end close
    end Store

    /** Decodes the `GetLogicalDrives` bitmask into drive-root strings, keeping ONLY local fixed disks. Runs
      * on the bitmask-change path only, never on the steady-state read. A network drive's free-space call can
      * block until the share times out if it is unreachable, so removable, network, cdrom and ramdisk drives
      * are filtered out here rather than probed.
      */
    def enumerate(b: WindowsBindings, mask: Int)(using AllowUnsafe): Chunk[String] =
        Chunk.from((0 until 26).iterator.flatMap { i =>
            if (mask & (1 << i)) != 0 then
                val root = ('A' + i).toChar.toString + ":\\"
                if b.getDriveType(root) == WindowsBindings.driveFixed then Iterator.single(root) else Iterator.empty
            else Iterator.empty
        }.toSeq)
    end enumerate

    /** Reads one drive through the binding and writes its two decoded primitives STRAIGHT into that drive's
      * retained cells. Nothing is returned: no tuple, no boxed value, no carrier. The three out-params are
      * bytes available to the caller, total bytes, and total free bytes; total is the second and free is the
      * third (the total free space, not the caller-available space). The out-buffers are the store's RETAINED
      * 1-long buffers, reused every read, so this read allocates none. A failed call, or a contained throw,
      * writes nothing, so that drive records no value for this read.
      */
    private[machine] def diskFreeInto(b: WindowsBindings, store: Store)(using AllowUnsafe): Unit =
        try
            if b.diskFreeSpace(store.drive, store.availToCaller, store.total, store.totalFree) != 0 then
                store.cell.total.set(store.total.get(0))
                store.cell.free.observe(store.totalFree.get(0))
            end if
        catch
            // The LinkageError arm is a containment boundary, not a swallowed bug: kernel32 does not exist
            // off Windows, so the first call against an unresolved symbol throws an Error rather than an
            // exception. Containing it here keeps a non-Windows host reporting no disk rows instead of
            // killing the sampler fiber; every other fatal throwable still propagates.
            case ex: Throwable if scala.util.control.NonFatal(ex) || ex.isInstanceOf[LinkageError] => ()
    end diskFreeInto

end WindowsDisk
