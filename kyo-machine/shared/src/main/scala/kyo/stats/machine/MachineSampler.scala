package kyo.stats.machine

import kyo.*

/** The single detached fiber that samples the host once per second straight into retained `kyo.Stat`
  * handles, with a zero-allocation steady-state read path.
  *
  * It creates every metric handle once at init and holds it in a field for its whole life (a
  * WeakReference-backed registry entry that is not retained can be collected and silently reset). Each
  * tick it reads the host through the OS `Machine` impl and observes each value directly: a cumulative
  * time value advances a Counter by the per-tick delta on the unit-scaled nanosecond value and observes
  * that same delta into a paired per-second-delta Histogram; a point-in-time gauge is observed into a
  * Histogram. It owns, per proc file it reads, one `Path.ReadHandle` opened once (rewound each tick) and
  * one reused byte buffer, so file reads allocate no per-read payload. All waits are kyo `Async`
  * suspension: no thread is ever blocked.
  */
final private[kyo] class MachineSampler(handles: MachineHandles, machine: Machine):

    import AllowUnsafe.embrace.danger

    private val fileHandles = collection.mutable.HashMap.empty[String, MachineSampler.FileSlot]
    private var priorState  = MachineSampler.PriorState.empty

    /** Reads the host through the bound OS impl (the sampler is the impl's read context). */
    def machineRead()(using AllowUnsafe): Machine.Reading = machine.read(this)

    /** Observes one tick's `Reading` into the retained handles, advancing every cumulative Counter and its
      * paired Histogram by the per-tick delta and observing every gauge. Absent fields are skipped.
      */
    def observe(reading: Machine.Reading)(using AllowUnsafe): Unit =
        priorState = handles.observe(reading, priorState)

    /** Reads and rewinds one retained proc-file handle, handing the reused buffer's borrowed bytes and the
      * byte count to `f`. The borrowed `Span[Byte]` must not escape `f`.
      */
    def readScoped[A](path: Path, f: (Span[Byte], Int) => A)(using AllowUnsafe): Maybe[A] =
        val key = path.toString
        val slot = fileHandles.get(key) match
            case Some(s) => Present(s)
            case None => MachineSampler.openSlot(path).map { s =>
                    fileHandles.update(key, s); s
                }
        slot.map { s =>
            s.handle.position(0L)
            val len = MachineSampler.fill(s)
            f(Span.fromUnsafe(s.buffer), len)
        }
    end readScoped

    /** Closes every retained proc-file handle (invoked on sampler teardown by the owning Scope). */
    def closeHandles()(using AllowUnsafe): Unit =
        fileHandles.valuesIterator.foreach(_.handle.close())
        fileHandles.clear()

end MachineSampler

private[kyo] object MachineSampler:

    /** Builds the sampler, creates every metric handle once, resolves the OS impl once, and drives the tick
      * loop UNDER this Scope so the loop and the retained handles share one lifetime: an interrupt of this
      * effect stops the loop, then the Scope finalizer closes the handles, so no tick reads a closed handle.
      * The loop itself keeps this Scope open, so no separate keep-alive is needed. Runs forever until
      * interrupted.
      */
    def run(using Frame): Nothing < (Async & Scope) =
        val prepared: MachineSampler < (Async & Scope) =
            for
                os      <- System.operatingSystem
                handles <- MachineHandles.init
                sampler <- Sync.Unsafe.defer(new MachineSampler(handles, Machine.forOs(os)))
                _       <- Scope.ensure(Sync.Unsafe.defer(withUnsafe(sampler.closeHandles())))
            yield sampler
        prepared.map(sampler => Loop.forever(Async.sleep(1.seconds).andThen(tick(sampler))))
    end run

    private def tick(sampler: MachineSampler)(using Frame): Unit < Sync =
        Sync.Unsafe.defer {
            val reading = sampler.machineRead()
            sampler.observe(reading)
        }

    private inline def withUnsafe(inline body: AllowUnsafe ?=> Unit): Unit =
        import AllowUnsafe.embrace.danger
        body

    final private[machine] case class FileSlot(handle: Path.ReadHandle, var buffer: Array[Byte], scratch: Array[Byte])

    /** The sampler's per-tick prior cumulative values, keyed by metric path, for delta computation. */
    final private[machine] case class PriorState(values: Map[String, Long]):
        def get(key: String): Maybe[Long]         = values.get(key).fold(Absent)(Present(_))
        def set(key: String, v: Long): PriorState = PriorState(values.updated(key, v))
    private[machine] object PriorState:
        val empty: PriorState = PriorState(Map.empty)

    private def openSlot(path: Path)(using AllowUnsafe): Maybe[FileSlot] =
        given Frame = Frame.internal
        path.unsafe.openRead() match
            case Result.Success(h) => Present(FileSlot(h, new Array[Byte](8192), new Array[Byte](8192)))
            case _                 => Absent
    end openSlot

    /** Reads the whole retained handle into the slot's reused output buffer and returns the total byte
      * count. Each `readChunk` fills the SAME reused scratch array from index 0, so the retained
      * ByteBuffer inside the handle is reused on every read (no per-chunk wrap allocation); the bytes are
      * appended into the output buffer at the running offset, so a multi-chunk read accumulates without
      * losing its tail. The output buffer grows to fit once when a large file first exceeds it; steady
      * state re-reads the same small /proc snapshot with no allocation.
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
