package kyo.compat

import com.twitter.util.Promise
import com.twitter.util.Return
import java.util.concurrent.atomic.AtomicInteger

/** A one-shot count-down latch implemented as a `final class` over a `com.twitter.util.Promise[Unit]` gated by an `AtomicInteger` counter —
  * no thread blocking. Twitter Future has no native latch primitive, so the structure is hand-rolled. `release` decrements the counter and
  * resolves the promise when it hits zero; `await` returns the promise as a Future. `init(n <= 0)` is normalized to "already released".
  */
final class CLatch(initial: Int):

    private val remaining           = new AtomicInteger(math.max(initial, 0))
    private val gate: Promise[Unit] = new Promise[Unit]()

    if initial <= 0 then
        val _ = gate.updateIfEmpty(Return(()))

    /** Identity on the carrier; provided for surface uniformity with the opaque-type bindings. */
    inline def lower: CLatch = this

    /** Suspends until the counter reaches zero. */
    inline def await: CIO[Unit] =
        CIO.lift(gate)

    /** Decrements the counter by one; unblocks all `await`s when it reaches zero. */
    inline def release: CIO[Unit] =
        CIO.defer {
            val r = remaining.decrementAndGet()
            if r == 0 then
                val _ = gate.updateIfEmpty(Return(()))
            else if r < 0 then
                // Already released; clamp the counter so additional releases stay idempotent.
                remaining.set(0)
            end if
        }

end CLatch

object CLatch:

    /** Allocates a latch with counter `n`; `n <= 0` is normalized to "already released". */
    inline def init(inline n: Int): CIO[CLatch] =
        CIO.defer(new CLatch(n))

    /** Wraps an existing `CLatch` instance. Identity on the carrier. */
    inline def lift(inline u: CLatch): CLatch = u

end CLatch
