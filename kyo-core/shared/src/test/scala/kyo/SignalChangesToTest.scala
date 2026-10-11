package kyo

import java.util.concurrent.atomic.AtomicInteger

/** `changesTo`, which carries a constant while tracking a source's changes.
  *
  * It exists because observation deduplicates on the emitted value, and a caller whose emitted value is a stable handle, one whose content
  * is rebuilt from the source at delivery time (as a signal-bound form field is), gets exactly one delivery out of `map(_ => constant)` and
  * then silence. That is the correct behaviour for a projection and a silent failure for such a caller, so both halves are pinned here:
  * what `changesTo` does, and what `map` does, side by side.
  *
  * Absence of a delivery is checked once the observer is parked on the source again (`waiters == 1`), which it only does after it has
  * handled the write.
  */
class SignalChangesToTest extends kyo.test.Test[Any]:

    private def countDeliveries[A](ref: SignalRef[Int], sig: Signal[A])(using
        Frame,
        kyo.test.AssertScope
    ): AtomicInteger < (Async & Scope) =
        for
            seen <- Sync.defer(new AtomicInteger(0))
            _    <- Fiber.init(sig.observe(v => Sync.defer(discard(seen.incrementAndGet())).andThen(discard(v))))
            _    <- assertEventually(Sync.defer(seen.get == 1))
            _    <- assertEventually(ref.waiters.map(_ == 1))
        yield seen

    "delivers on every source change even though its own value never changes" in {
        Scope.run {
            for
                ref  <- Signal.initRef(0)
                seen <- countDeliveries(ref, ref.changesTo("constant"))
                _    <- ref.set(1)
                _    <- assertEventually(Sync.defer(seen.get == 2))
                _    <- ref.set(2)
                _    <- assertEventually(Sync.defer(seen.get == 3))
                _    <- ref.set(3)
                _    <- assertEventually(Sync.defer(seen.get == 4))
            yield succeed
        }
    }

    "the per-delivery setup is built again for every delivery" in {
        Scope.run {
            for
                ref   <- Signal.initRef(0)
                built <- Sync.defer(new AtomicInteger(0))
                // Counted while `f` builds its computation, before any of its effects run: a setup built once and
                // replayed for later deliveries would stay at one.
                _ <- Fiber.init(ref.changesTo("constant").observe { _ =>
                    discard(built.incrementAndGet())
                    Kyo.unit
                })
                _ <- assertEventually(Sync.defer(built.get == 1))
                _ <- assertEventually(ref.waiters.map(_ == 1))
                _ <- ref.set(1)
                _ <- assertEventually(Sync.defer(built.get == 2))
                _ <- ref.set(2)
                _ <- assertEventually(Sync.defer(built.get == 3))
            yield succeed
        }
    }

    "map of the same shape goes silent after the first delivery" in {
        Scope.run {
            for
                ref  <- Signal.initRef(0)
                seen <- countDeliveries(ref, ref.map(_ => "constant"))
                _    <- ref.set(1)
                _    <- assertEventually(ref.waiters.map(_ == 1))
                _    <- ref.set(2)
                _    <- assertEventually(ref.waiters.map(_ == 1))
            // Not a defect: the projection's value did not move, and suppressing those deliveries is what keeps one selection change
            // off a thousand rows. It is only wrong for a caller that reads the source at delivery time, which is what changesTo is for.
            yield assert(seen.get == 1)
        }
    }

    "a baseline equal to the value skips exactly one delivery, not all of them" in {
        Scope.run {
            for
                ref  <- Signal.initRef(0)
                seen <- Sync.defer(new AtomicInteger(0))
                sig = ref.changesTo("constant")
                // The baseline says "I already painted this": the initial emission is skipped, and every source change after it must
                // still arrive.
                _ <- Fiber.init(sig.observe(Present("constant"), Signal.defaultRepairInterval)(_ =>
                    Sync.defer(discard(seen.incrementAndGet()))
                ))
                _ <- assertEventually(ref.waiters.map(_ == 1))
                _ = assert(seen.get == 0)
                _ <- ref.set(1)
                _ <- assertEventually(Sync.defer(seen.get == 1))
                _ <- ref.set(2)
                _ <- assertEventually(Sync.defer(seen.get == 2))
            yield succeed
        }
    }

    "an unchanged write delivers nothing" in {
        Scope.run {
            for
                ref  <- Signal.initRef(0)
                seen <- countDeliveries(ref, ref.changesTo("constant"))
                _    <- ref.set(0)
                w    <- ref.waiters
            // The source gates on inequality, so an unchanged write completes no promise and there is no change to carry.
            yield assert(w == 1 && seen.get == 1)
        }
    }

end SignalChangesToTest
