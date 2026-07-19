package kyo.net.internal.util

import kyo.*

/** A stable per-handle identity that pairs a file descriptor with a generation counter, so a
  * structure keyed on a handle survives the descriptor number being recycled.
  *
  * A closed fd's integer can be handed straight back to a new connection, so the bare fd is not
  * a stable key: a stale event, close, or deregister carrying an old fd can land on the live
  * connection that now owns the recycled number. Packing the fd with a monotonic generation into
  * one `Long` gives every live handle a key that a recycled fd's new handle never equals, so a
  * map removal or completion gated on the full [[HandleId]] cannot cross-contaminate.
  *
  * Zero-cost: an opaque alias over `Long`, packed `(fd << 32) | generation`. The generation comes
  * from a process-lifetime monotonic counter, so two handles for the same fd over time never share
  * an id. The fd is the high 32 bits (so [[fd]] is a shift) and the generation the low 32 bits.
  *
  * The generation occupies 32 bits and wraps after 2^32 allocations in one process, so the
  * distinctness guarantee is stable within a 2^32 window of handle allocations rather than
  * absolute. A process that opens 2^32 connections is far beyond any realistic lifetime, so the
  * window is effectively unbounded in practice.
  */
opaque type HandleId = Long

object HandleId:

    // Unsafe: a process-lifetime monotonic generation counter is capability-free (no fd, no I/O), and this object singleton has no
    // construction site to receive a propagated AllowUnsafe, so the danger bridge here is inert. AtomicLong.Unsafe is kyo's atomic-long
    // primitive over the JDK AtomicLong.
    private val genCounter = AtomicLong.Unsafe.init(0L)(using AllowUnsafe.embrace.danger)

    /** Allocate a fresh [[HandleId]] for `fd`, pairing it with the next process-monotonic generation. */
    def next(fd: Int): HandleId =
        // Unsafe: capability-free monotonic increment; see genCounter.
        val generation = genCounter.getAndIncrement()(using AllowUnsafe.embrace.danger) & 0xffffffffL
        (fd.toLong << 32) | generation
    end next

    /** Build a [[HandleId]] from an explicit `(fd, generation)` pair (the packing the kqueue udata
      * tag and the io_uring SQE user-data carry across the kernel boundary).
      */
    def of(fd: Int, generation: Int): HandleId =
        (fd.toLong << 32) | (generation.toLong & 0xffffffffL)

    extension (id: HandleId)
        /** The file descriptor this id names. */
        def fd: Int = (id >>> 32).toInt

        /** The generation that distinguishes this id from a later handle reusing the same fd number. */
        def generation: Int = (id & 0xffffffffL).toInt

        /** The packed `Long`, for crossing a C boundary (an SQE user-data field or a kqueue udata tag). */
        def packed: Long = id
    end extension

    /** Reconstruct a [[HandleId]] from a packed `Long` read back across a C boundary. */
    def fromPacked(packed: Long): HandleId = packed
end HandleId
