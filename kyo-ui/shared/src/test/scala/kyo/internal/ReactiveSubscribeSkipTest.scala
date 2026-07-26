package kyo.internal

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.UI.foreach

/** Direct-engine tests pinning the skip of redundant first emissions after subscribe: normalize captures
  * render-time snapshots (normalize happens-before the HTML render on every paint path), so a freshly painted
  * region or channel must NOT be repainted by its observer's initial emission, while a change landing between
  * normalize and subscribe differs from the snapshot and must still fire.
  */
class ReactiveSubscribeSkipTest extends kyo.test.Test[Any]:

    final private class Recording:
        val changes      = new AtomicInteger(0)
        val classPatches = new AtomicInteger(0)
        val exchange = new UIExchange:
            def onChange(path: Seq[String], changed: UI, mount: Boolean)(using Frame): Unit < Async =
                Sync.defer(discard(changes.incrementAndGet()))
            override def onClassPatch(path: Seq[String], name: String, on: Boolean)(using Frame): Unit < Async =
                Sync.defer(discard(classPatches.incrementAndGet()))
    end Recording

    "subscribe does not repaint a just-normalized region nor re-patch its channels" in {
        Scope.run {
            for
                items <- Signal.initRef(Chunk("a", "b"))
                on    <- Signal.initRef(false)
                rec = new Recording
                ui  = UI.div(items.foreach(i => UI.span(i)), UI.span("x").cssClass("hot", on))
                root <- ReactiveUI.normalize(ui, Seq.empty)
                _    <- ReactiveUI.subscribe(root, rec.exchange)
                _    <- Async.sleep(150.millis)
                silentChanges = rec.changes.get
                silentClasses = rec.classPatches.get
                _ <- items.set(Chunk("a", "b", "c"))
                _ <- assertEventually(Sync.defer(rec.changes.get >= silentChanges + 1))
                _ <- on.set(true)
                _ <- assertEventually(Sync.defer(rec.classPatches.get >= 1))
            yield assert(silentChanges == 0 && silentClasses == 0)
        }
    }

    "a change landing between normalize and subscribe still fires" in {
        Scope.run {
            for
                items <- Signal.initRef(Chunk("a"))
                rec = new Recording
                ui  = UI.div(items.foreach(i => UI.span(i)))
                root <- ReactiveUI.normalize(ui, Seq.empty)
                _    <- items.set(Chunk("a", "b")) // differs from the normalize-time snapshot
                _    <- ReactiveUI.subscribe(root, rec.exchange)
                _    <- assertEventually(Sync.defer(rec.changes.get >= 1))
            yield assert(true)
        }
    }

end ReactiveSubscribeSkipTest
