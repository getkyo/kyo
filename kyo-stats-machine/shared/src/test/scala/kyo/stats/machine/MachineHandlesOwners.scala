package kyo.stats.machine

import kyo.*

/** Test-support stand-in for the sampler's process-lifetime ownership of its cells.
  *
  * The registry holds every instrument by weak reference, and in production the one `MachineHandles` stays
  * reachable from the sampler fiber for the life of the process. A test owner that becomes unreachable before
  * its registry read lets the GC (observed on Native) collect the instrument: the read then misses it or mints
  * a fresh zeroed one, and a later handle set on a shared `machine.*` path registers a new instrument over a
  * collected one. Every handle set and standalone cell a test builds goes through here so it is owned for the
  * process lifetime, as production's is.
  */
private[machine] object MachineHandlesOwners:

    // Unsafe: a test-only append-only holder; it exists to be reachable, not to be read.
    private val owners =
        import AllowUnsafe.embrace.danger
        AtomicRef.Unsafe.init(Chunk.empty[AnyRef])
    end owners

    def retain[A <: AnyRef](owner: A)(using AllowUnsafe): A =
        discard(owners.updateAndGet(_.append(owner)))
        owner

    def init(using Frame): MachineHandles < Sync =
        MachineHandles.init.map(handles => Sync.Unsafe.defer(retain[MachineHandles](handles)))

    def initForTest(scope: Stat, cores: Long)(using AllowUnsafe): MachineHandles =
        retain(MachineHandles.initForTest(scope, cores))

end MachineHandlesOwners
