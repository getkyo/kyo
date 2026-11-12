package kyo.internal

import java.util.concurrent.atomic.AtomicInteger
import kyo.*

/** The element region of a two-way-bound field (`input.value(ref)`, `checkbox.checked(ref)`).
  *
  * Such an element re-renders from its ref on every ref change, and the emitted UI is deliberately the same object each time: it keeps its
  * `Bound.Ref` so the rendered HTML carries the auto-binding event markers, and the value is read afresh at render time. That makes the
  * region the one place where the emitted value cannot be used to detect change, and an observation that deduplicates on it delivers the
  * first paint and then goes silent: the field stops tracking its own ref, with nothing failing loudly. These count the emissions.
  *
  * The observer is parked on the ref again (`waiters == 1`) only once it has handled the previous write, which is what the tests wait on.
  */
class BoundValueRegionTest extends kyo.test.Test[Any]:

    final private class Recording:
        val changes  = new AtomicInteger(0)
        val exchange = new UIExchange:
            def onChange(
                region: ReactiveRegion,
                path: Seq[String],
                contentContext: ReactiveRegion.RegionIdentity,
                parentContext: ReactiveRegion.ParentContext,
                previous: Maybe[UI],
                changed: UI
            )(using Frame): Unit < Async =
                Sync.defer(discard(changes.incrementAndGet()))
    end Recording

    "a value-bound input re-renders on every ref change, not just the first" in {
        Scope.run {
            for
                ref <- Signal.initRef("")
                rec = new Recording
                root <- ReactiveUI.normalize(UI.div(UI.input.id("i").value(ref)), Seq.empty)
                _    <- ReactiveUI.subscribe(root, rec.exchange)
                _    <- assertEventually(ref.waiters.map(_ == 1))
                base = rec.changes.get
                _ <- ref.set("first")
                _ <- assertEventually(Sync.defer(rec.changes.get == base + 1))
                // The second edit is the one that matters: the first arrives even when change detection has collapsed onto the emitted
                // value, because there is no previous emission to compare against.
                _ <- ref.set("second")
                _ <- assertEventually(Sync.defer(rec.changes.get == base + 2))
                _ <- ref.set("third")
                _ <- assertEventually(Sync.defer(rec.changes.get == base + 3))
            yield succeed
        }
    }

    "a checked-bound checkbox re-renders on every ref change" in {
        Scope.run {
            for
                ref <- Signal.initRef(false)
                rec = new Recording
                root <- ReactiveUI.normalize(UI.div(UI.checkbox.id("c").checked(ref)), Seq.empty)
                _    <- ReactiveUI.subscribe(root, rec.exchange)
                _    <- assertEventually(ref.waiters.map(_ == 1))
                base = rec.changes.get
                _ <- ref.set(true)
                _ <- assertEventually(Sync.defer(rec.changes.get == base + 1))
                // Two values make the trap sharpest: a collapsed observation looks like it works until the ref returns to a value it
                // has already held.
                _ <- ref.set(false)
                _ <- assertEventually(Sync.defer(rec.changes.get == base + 2))
                _ <- ref.set(true)
                _ <- assertEventually(Sync.defer(rec.changes.get == base + 3))
            yield succeed
        }
    }

    "an element with no bound ref emits nothing" in {
        Scope.run {
            for
                rec  <- Sync.defer(new Recording)
                root <- ReactiveUI.normalize(UI.div(UI.input.id("i").value("static")), Seq.empty)
                _    <- ReactiveUI.subscribe(root, rec.exchange)
            // A const node has no region of its own, so nothing observes it.
            yield assert(rec.changes.get == 0)
        }
    }

end BoundValueRegionTest
