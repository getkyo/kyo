package kyo.stats.machine

import kyo.*

/** The single detached fiber that samples the host once per second straight into retained `kyo.Stat`
  * handles.
  *
  * The READ, DECODE and OBSERVE path of a steady-state tick allocates ZERO heap bytes on every supported
  * OS, and that claim is MEASURED, on the real per-OS decode callbacks, at a per-op bound of zero bytes. It
  * holds because everything a tick touches is retained and built once: one `Path.ReadHandle` per proc file,
  * opened at init and rewound each tick into its own reused buffer; one decode callback per file, so a tick
  * passes a field reference rather than allocating a closure; one FFI out-buffer per syscall; and one cell
  * per metric, which the decoder writes its decoded primitives into directly, with the primitive
  * `Path.ReadHandle.AbsentLong` (or `Double.NaN`) standing for an unavailable value so that absence never
  * boxes. The `Async`, `Clock` and fiber machinery the tick RIDES (fiber, continuation, schedule state) is
  * effect-system allocation and is outside that claim, which is stated for exactly the path it covers.
  *
  * The tick LOOP fiber never blocks: every wait is kyo `Async` suspension. The disk read wraps a genuinely
  * blocking syscall on its own fiber (see `readDisksBounded`).
  */
final private[kyo] class MachineSampler(handles: MachineHandles):

    // Unsafe: the sampler owns its retained read handles and reused buffers as single-owner fields on one
    // detached fiber, reading and rewinding them off the effect context at each tick.
    import AllowUnsafe.embrace.danger

    private val opened = collection.mutable.ArrayBuffer.empty[MachineSampler.FileSlot]

    // Guards the disk read against overlapping itself. A statvfs/statfs/GetDiskFreeSpaceEx against a dead
    // mount has no suspension point, so Async.timeout cannot interrupt it: a timed-out read leaves a worker
    // parked inside the syscall until the kernel gives up. This flag is set before a disk read starts and
    // cleared only when that read GENUINELY returns, so while a stuck read is outstanding the disk fiber
    // skips its next cycle rather than launching a second read against the same stuck mount. A dead mount is
    // therefore read exactly once for the process lifetime, bounding leaked blocked workers to one.
    private val diskInFlight = AtomicBoolean.Unsafe.init(false)

    /** True while a prior disk read is still outstanding (parked in a blocking syscall a timeout could not
      * interrupt). The disk fiber reads this to skip a cycle rather than overlap a stuck read.
      */
    def diskReadInFlight()(using AllowUnsafe): Boolean = diskInFlight.get()

    /** Marks a disk read as started; returns false when one is already outstanding, so the caller skips this
      * cycle. Paired with `diskReadDone`, which the disk read calls in a `finally` on genuine completion.
      */
    def diskReadBegin()(using AllowUnsafe): Boolean = diskInFlight.compareAndSet(false, true)

    /** Clears the in-flight guard once a disk read genuinely returns (never on timeout: a timed-out read is
      * still parked in its syscall and must not be relaunched).
      */
    def diskReadDone()(using AllowUnsafe): Unit = diskInFlight.set(false)

    /** Opens ONE retained read handle for `path`, at reader construction. A file that does not exist stays
      * `Absent`, and its per-tick read is then a branch, not a lookup: no map, no key, and no
      * `path.toString` on the tick. A reference-typed `Maybe` carries no wrapper, so matching the returned
      * slot every tick allocates nothing.
      */
    def openSlot(path: Path)(using AllowUnsafe): Maybe[MachineSampler.FileSlot] =
        MachineSampler.openSlot(path) match
            case Present(slot) =>
                opened.append(slot)
                Present(slot)
            case Absent => Absent

    /** Rewinds the retained handle, refills its retained buffer, and hands the borrowed bytes to the
      * retained decoder, which writes its decoded primitives into the cells. Returns false when the file is
      * absent. The borrowed `Span[Byte]` must not escape the decoder.
      */
    def readInto(slot: Maybe[MachineSampler.FileSlot], decode: MachineSampler.Decode)(using AllowUnsafe): Boolean =
        slot match
            case Present(fs) =>
                fs.handle.position(0L)
                // fill may grow and rebind `fs.buffer`, so the span must be taken after it returns:
                // taken before, it would borrow the pre-grow array and read past its end.
                val len = MachineSampler.fill(fs)
                decode(Span.fromUnsafe(fs.buffer), len)
                true
            case Absent => false

    /** Reads a single ASCII-decimal `Long` straight out of the retained handle, with no intermediate
      * `String` and no box. Returns `Path.ReadHandle.AbsentLong` when the file is absent, empty or
      * unparseable, which is exactly the value every cell skips.
      */
    def readLongFrom(slot: Maybe[MachineSampler.FileSlot])(using AllowUnsafe): Long =
        slot match
            case Present(fs) => fs.handle.readLong()
            case Absent      => Path.ReadHandle.AbsentLong

    /** A ONE-SHOT read used at init and on a mount-table change only: it opens, reads, closes, and hands
      * the bytes to `f`. It is never called on the tick, which is why it may allocate.
      */
    def readOnce[A](path: Path, f: (Span[Byte], Int) => A)(using AllowUnsafe): Maybe[A] =
        MachineSampler.openSlot(path) match
            case Present(slot) =>
                val len = MachineSampler.fill(slot)
                val out = f(Span.fromUnsafe(slot.buffer), len)
                slot.handle.close()
                Present(out)
            case Absent => Absent

    /** Closes every retained read handle. Invoked on sampler teardown by the owning Scope. */
    def closeHandles()(using AllowUnsafe): Unit =
        opened.foreach(_.handle.close())
        opened.clear()

end MachineSampler

private[kyo] object MachineSampler:

    // Unsafe: the sampler's retained state is constructed and closed off any effect context that could
    // supply the capability, the same class-level bridge five sibling readers carry.
    import AllowUnsafe.embrace.danger

    /** A retained decode callback: ONE instance per proc file, built once at reader construction, so a tick
      * passes a field reference. A capturing lambda written at the read site would allocate one closure per
      * read per tick and survive only by escape analysis, which is the kind of unmeasured assumption this
      * module must not rest on.
      */
    private[machine] trait Decode:
        def apply(bytes: Span[Byte], len: Int)(using AllowUnsafe): Unit

    /** Builds the sampler, creates every metric cell once, constructs the OS reader once, and drives the
      * drift-corrected tick loop under this Scope, so the loop and the retained handles share one lifetime.
      *
      * Two fibers run under this Scope. The FAST fiber reads the in-kernel and proc families on a
      * drift-corrected 1 Hz anchored schedule. The DISK fiber reads the one genuinely blockable family on its
      * OWN cadence, and the fast fiber never awaits it, so a slow or dead mount can never delay a fast read of
      * the same or the next tick. Both fibers are registered with the Scope for interrupt BEFORE the effect
      * parks, and the buffer-closing finalizer is registered FIRST so it runs LAST (Scope finalizers are
      * LIFO): closing the Scope interrupts both fibers, then closes the reader's buffers and file handles, so
      * no fiber can read a closed handle. Awaiting the fast fiber's `get` (it never returns) keeps the Scope
      * open for the process lifetime, the same detached-fiber shape `OTLPClient.startExportLoop` ships.
      */
    def run(using Frame): Unit < (Async & Scope) =
        for
            os      <- System.operatingSystem
            handles <- MachineHandles.init
            _       <- runWith(handles, sampler => Sync.Unsafe.defer(Machine.forOs(os, handles, sampler)))
        yield ()

    /** Drives the loop over a caller-supplied handle set and a reader built from the sampler. Production
      * passes the OS-selected reader; a test passes a staged reader (a recording decode, a never-completing
      * disk read) and a controlled `Clock`, so the tick cadence, the disk-fiber isolation and the teardown
      * ordering are exercised deterministically. `private[machine]`; production reaches the loop only through
      * `run`.
      */
    private[machine] def runWith(
        handles: MachineHandles,
        buildMachine: MachineSampler => Machine < Sync
    )(using Frame): Unit < (Async & Scope) =
        for
            sampler <- Sync.Unsafe.defer(new MachineSampler(handles))
            machine <- buildMachine(sampler)
            _ <- Scope.ensure(Sync.Unsafe.defer {
                machine.close()
                sampler.closeHandles()
            })
            disk <- Clock.repeatAtInterval(diskInterval)(readDisksBounded(sampler, machine))
            _    <- Scope.ensure(disk.interrupt.unit)
            fast <- Clock.repeatAtInterval(Schedule.anchored(1.second))(readFast(machine))
            _    <- Scope.ensure(fast.interrupt.unit)
            _    <- fast.get
        yield ()
    end runWith

    /** The disk read runs at this cadence on its own fiber. A cycle waits at most `diskReadTimeout` before it
      * yields to the next cycle CHECK; the in-flight guard then keeps that next cycle from launching a second
      * read while a timed-out one is still parked in its syscall. The timeout only bounds the DISK fiber; the
      * fast fiber never waits on it, so its value is decoupled from the fast-read cadence.
      */
    private val diskInterval    = 1.second
    private val diskReadTimeout = 4.seconds

    /** The fast tick: reads every non-disk family straight into the retained cells. It never touches the disk
      * read, so nothing on this path can block on a mount.
      */
    private def readFast(machine: Machine)(using Frame): Unit < Async =
        Sync.Unsafe.defer(machine.read())

    /** One disk cycle on the detached disk fiber. It skips itself when a prior read is still outstanding
      * (parked in a blocking syscall), so a dead mount is read exactly once and never overlapped. Otherwise
      * it runs the bounded read; the read clears the guard in a `finally` only when it GENUINELY returns, so a
      * timed-out read that is still parked keeps the guard set and is not relaunched. The scheduler's blocking
      * compensation covers the parked worker.
      */
    private def readDisksBounded(sampler: MachineSampler, machine: Machine)(using Frame): Unit < Async =
        Sync.Unsafe.defer(sampler.diskReadBegin()).map { began =>
            if !began then ()
            else
                Abort.run[Timeout](
                    Async.timeout(diskReadTimeout)(
                        Sync.Unsafe.defer {
                            try machine.readDisks()
                            finally sampler.diskReadDone()
                        }
                    )
                ).unit
        }

    final private[machine] case class FileSlot(handle: Path.ReadHandle, var buffer: Array[Byte], scratch: Array[Byte])

    private def openSlot(path: Path)(using AllowUnsafe): Maybe[FileSlot] =
        given Frame = Frame.internal
        path.unsafe.openRead() match
            case Result.Success(h) => Present(FileSlot(h, new Array[Byte](8192), new Array[Byte](8192)))
            case _                 => Absent
    end openSlot

    /** Reads the whole retained handle into the slot's reused output buffer and returns the byte count. Each
      * `readChunk` fills the SAME reused scratch array from index 0, so the retained ByteBuffer inside the
      * handle is reused on every read; the bytes are appended into the output buffer at the running offset,
      * so a multi-chunk read keeps its tail. The output buffer grows to fit once when a large file first
      * exceeds it; the steady state re-reads the same small proc snapshot with no allocation.
      */
    private def fill(slot: FileSlot)(using AllowUnsafe): Int =
        @scala.annotation.tailrec
        def capFor(cap: Int, need: Int): Int = if cap >= need then cap else capFor(cap * 2, need)
        @scala.annotation.tailrec
        def loop(total: Int): Int =
            val chunk = slot.handle.readChunk(slot.scratch)
            if chunk.isEof then total
            else
                val n   = chunk.bytesRead
                val end = total + n
                if end > slot.buffer.length then
                    val grown = new Array[Byte](capFor(slot.buffer.length * 2, end))
                    java.lang.System.arraycopy(slot.buffer, 0, grown, 0, total)
                    slot.buffer = grown
                end if
                java.lang.System.arraycopy(slot.scratch, 0, slot.buffer, total, n)
                loop(end)
            end if
        end loop
        loop(0)
    end fill

end MachineSampler
