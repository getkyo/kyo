package kyo

import kyo.Browser.*
import kyo.UI.render

/** [[kyo.UI.mounted]] end to end over the server-push transport in a real browser: the mount's range is replaced by the instance's
  * content, a kept instance stays interactive across its region's re-renders, and a key change swaps the content in place.
  */
class MountedScenarioItTest extends UITest:

    override def timeout = 120.seconds

    "the placeholder is replaced by the content once the effect publishes" in {
        val app: (Promise[Unit, Any], UI < Async) < Sync =
            for gate <- Promise.init[Unit, Any]
            yield (
                gate,
                Kyo.lift(UI.div(
                    UI.mounted(gate.get.andThen(UI.span("ready").id("content"))).placeholder(UI.span("loading").id("ph"))
                ))
            )
        app.map { (gate, ui) =>
            withUI(ui) {
                for
                    _ <- Browser.assertText(Selector.id("ph"), "loading")
                    _ <- gate.completeUnitDiscard
                    _ <- Browser.assertText(Selector.id("content"), "ready")
                yield ()
            }
        }
    }

    "a kept instance keeps its state and stays clickable while its region re-renders" in {
        val counter: UI < (Abort[Throwable] & Async & Scope) =
            for count <- Signal.initRef(0)
            yield UI.div(
                UI.button("Add").id("add").onClick(count.getAndUpdate(_ + 1).unit),
                count.map(n => UI.span(s"count:$n").id("count"))
            )
        val app: UI < Async =
            for tick <- Signal.initRef(0)
            yield UI.div(
                UI.button("Tick").id("tick").onClick(tick.getAndUpdate(_ + 1).unit),
                tick.render(n => UI.div(UI.span(s"tick:$n").id("tickview"), UI.mounted(counter).keyed("counter")))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.assertText(Selector.id("count"), "count:1")
                _ <- Browser.click(Selector.id("tick"))
                _ <- Browser.assertText(Selector.id("tickview"), "tick:1")
                _ <- Browser.assertText(Selector.id("count"), "count:1")
                _ <- Browser.click(Selector.id("add"))
                _ <- Browser.assertText(Selector.id("count"), "count:2")
            yield ()
        }
    }

    "a key change replaces the content in place" in {
        val app: UI < Async =
            for selected <- Signal.initRef("a")
            yield UI.div(
                UI.button("Switch").id("switch").onClick(selected.set("b")),
                selected.render(k => UI.div(UI.mounted(Kyo.lift(UI.span(s"page:$k").id("page"))).keyed(k)))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("page"), "page:a")
                _ <- Browser.click(Selector.id("switch"))
                _ <- Browser.assertText(Selector.id("page"), "page:b")
            yield ()
        }
    }

end MountedScenarioItTest
