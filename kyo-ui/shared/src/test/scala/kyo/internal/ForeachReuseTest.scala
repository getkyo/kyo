package kyo.internal

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.UI.foreachKeyed

/** Direct-engine tests for the reusable keyed Foreach path (RowRegistry): rows whose key and item persist
  * across list emissions are neither re-rendered nor re-subscribed, their observers stay live across reorders,
  * and a removed or changed row's observers are torn down before the new value paints.
  */
class ForeachReuseTest extends kyo.test.Test[Any]:

    final private class Recording:
        val changes      = new AtomicInteger(0)
        val classPatches = new AtomicInteger(0)
        val trueClassOns = new AtomicInteger(0)
        val exchange = new UIExchange:
            def onChange(path: Seq[String], changed: UI, mount: Boolean)(using Frame): Unit < Async =
                Sync.defer(discard(changes.incrementAndGet()))
            override def onClassPatch(path: Seq[String], name: String, on: Boolean)(using Frame): Unit < Async =
                Sync.defer {
                    discard(classPatches.incrementAndGet())
                    if on then discard(trueClassOns.incrementAndGet())
                }
    end Recording

    "appending re-renders only the added row" in {
        Scope.run {
            for
                renders <- Sync.defer(new AtomicInteger(0))
                rows    <- Signal.initRef(Chunk("a", "b", "c"))
                rec = new Recording
                ui = UI.ul(rows.foreachKeyed(identity) { item =>
                    discard(renders.incrementAndGet())
                    UI.li(item)
                })
                root <- ReactiveUI.normalize(ui, Seq.empty)
                _    <- ReactiveUI.subscribe(root, rec.exchange)
                _    <- Async.sleep(100.millis)
                base = renders.get
                _ <- rows.getAndUpdate(_.append("d"))
                _ <- assertEventually(Sync.defer(rec.changes.get >= 1))
                delta = renders.get - base
            yield assert(delta == 1, s"expected exactly 1 render for the added row, got $delta")
        }
    }

    "reordering re-renders no rows and keeps row observers alive" in {
        Scope.run {
            for
                renders  <- Sync.defer(new AtomicInteger(0))
                rows     <- Signal.initRef(Chunk("a", "b", "c"))
                selected <- Signal.initRef("")
                rec = new Recording
                ui = UI.ul(rows.foreachKeyed(identity) { item =>
                    discard(renders.incrementAndGet())
                    UI.li(item).cssClass("sel", selected.map(_ == item))
                })
                root <- ReactiveUI.normalize(ui, Seq.empty)
                _    <- ReactiveUI.subscribe(root, rec.exchange)
                _    <- Async.sleep(100.millis)
                base = renders.get
                _ <- rows.getAndUpdate(c => Chunk.from(c.toSeq.reverse))
                _ <- assertEventually(Sync.defer(rec.changes.get >= 1))
                reorderDelta = renders.get - base
                // The retained rows' channel observers must still be live after the reorder.
                _ <- selected.set("b")
                _ <- assertEventually(Sync.defer(rec.classPatches.get >= 1))
            yield assert(reorderDelta == 0, s"expected no row re-renders on reorder, got $reorderDelta")
        }
    }

    "removing a row tears its observers down while others stay live" in {
        Scope.run {
            for
                rows     <- Signal.initRef(Chunk("a", "b", "c"))
                selected <- Signal.initRef("")
                rec = new Recording
                ui = UI.ul(rows.foreachKeyed(identity) { item =>
                    UI.li(item).cssClass("sel", selected.map(_ == item))
                })
                root <- ReactiveUI.normalize(ui, Seq.empty)
                _    <- ReactiveUI.subscribe(root, rec.exchange)
                _    <- Async.sleep(100.millis)
                _    <- rows.getAndUpdate(_.filter(_ != "b"))
                _    <- assertEventually(Sync.defer(rec.changes.get >= 1))
                // b's observer is dead (evicted and awaited before the paint). The surviving rows' mapped
                // signals re-emit their unchanged `false` on the source change (mapped observe re-emits equal
                // images for distinct source values), so the discriminator is the PATCHED VALUE: only a live
                // b observer could patch `on = true` for selected == "b".
                _ <- selected.set("b")
                _ <- Async.sleep(100.millis)
                deadTrueOns = rec.trueClassOns.get
                // A surviving row still patches true in place.
                _ <- selected.set("a")
                _ <- assertEventually(Sync.defer(rec.trueClassOns.get >= deadTrueOns + 1))
            yield assert(deadTrueOns == 0, s"expected no on=true patch from the removed row's observer, got $deadTrueOns")
        }
    }

    "a freshly added row's root channel patches" in {
        // The added row is built by the reuse path (not the initial walk): its ROOT element carries the
        // channel, so the row walk must promote it to a ReactiveUI node or the observer never starts.
        Scope.run {
            for
                rows     <- Signal.initRef(Chunk("a", "b"))
                selected <- Signal.initRef("")
                rec = new Recording
                ui = UI.ul(rows.foreachKeyed(identity) { item =>
                    UI.li(item).cssClass("sel", selected.map(_ == item))
                })
                root <- ReactiveUI.normalize(ui, Seq.empty)
                _    <- ReactiveUI.subscribe(root, rec.exchange)
                _    <- Async.sleep(100.millis)
                _    <- rows.getAndUpdate(_.append("c"))
                _    <- assertEventually(Sync.defer(rec.changes.get >= 1))
                _    <- selected.set("c")
                _    <- assertEventually(Sync.defer(rec.trueClassOns.get >= 1))
            yield assert(true)
        }
    }

    "event dispatch on live rows does not re-render the list" in {
        // Registry-first dispatch: an event resolves the target row from the live RowRegistry (cached
        // handler for rows built by an emission, on-demand single-row walk for seeded rows) instead of
        // re-rendering and re-walking the whole list per event. The per-click budget of 2 renders is the
        // click-target resolution (isTargetDisabled and isTargetButton each render the SINGLE target row
        // through the Foreach boundary); a single whole-list dispatch walk would blow the bound (11 > 4).
        Scope.run {
            for
                renders <- Sync.defer(new AtomicInteger(0))
                clicks  <- Sync.defer(new AtomicInteger(0))
                rows    <- Signal.initRef(Chunk.from((0 until 10).map(i => s"r$i")))
                rec = new Recording
                ui = UI.ul(rows.foreachKeyed(identity) { item =>
                    discard(renders.incrementAndGet())
                    UI.li(item).onClick(Sync.defer(discard(clicks.incrementAndGet())))
                })
                root <- ReactiveUI.normalize(ui, Seq.empty)
                _    <- ReactiveUI.subscribe(root, rec.exchange)
                _    <- Async.sleep(100.millis)
                // One emission so "r10" carries a cached handler; r0-r9 stay seeded (no cached handler).
                _ <- rows.getAndUpdate(_.append("r10"))
                _ <- assertEventually(Sync.defer(rec.changes.get >= 1))
                // The registry is updated after the paint notification; give it its beat before dispatching.
                _ <- Async.sleep(100.millis)
                base = renders.get
                _ <- root.handle(Seq("0", "r10"), UIEvent.Click(Seq("0", "r10"), MouseEventData(UI.Modifiers.none, Absent)))
                _ <- root.handle(Seq("0", "r5"), UIEvent.Click(Seq("0", "r5"), MouseEventData(UI.Modifiers.none, Absent)))
                _ <- assertEventually(Sync.defer(clicks.get >= 2))
                delta = renders.get - base
            yield assert(delta <= 4, s"expected at most 4 target-resolution renders from event dispatch, got $delta")
        }
    }

    "a changed item rebuilds only that row" in {
        Scope.run {
            for
                renders <- Sync.defer(new AtomicInteger(0))
                rows    <- Signal.initRef(Chunk("a:1", "b:1", "c:1"))
                rec = new Recording
                ui = UI.ul(rows.foreachKeyed(_.takeWhile(_ != ':')) { item =>
                    discard(renders.incrementAndGet())
                    UI.li(item)
                })
                root <- ReactiveUI.normalize(ui, Seq.empty)
                _    <- ReactiveUI.subscribe(root, rec.exchange)
                _    <- Async.sleep(100.millis)
                base = renders.get
                _ <- rows.getAndUpdate(c => Chunk.from(c.toSeq.map(s => if s.startsWith("b") then "b:2" else s)))
                _ <- assertEventually(Sync.defer(rec.changes.get >= 1))
                delta = renders.get - base
            yield assert(delta == 1, s"expected exactly 1 render for the changed row, got $delta")
        }
    }

end ForeachReuseTest
