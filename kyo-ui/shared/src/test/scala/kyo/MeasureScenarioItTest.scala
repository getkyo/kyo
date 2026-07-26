package kyo

import kyo.Browser.*

/** Measure round-trip over the server-push transport in real Chrome. The measured element is a fixed 120x40 box, so the
  * reported rect is deterministic; viewport dimensions are asserted only to be positive (they vary with headless window size).
  */
class MeasureScenarioItTest extends UITest:

    "requestMeasure delivers a plausible element rect and viewport" in {
        val app: UI < Async =
            for info <- Signal.initRef("pending")
            yield UI.div(
                UI.div.id("box").style(Style.width(120.px) ++ Style.height(40.px))("content"),
                UI.button("measure").id("btn").onClick(
                    UI.commands.flatMap(_.requestMeasure(Seq("0"))(r =>
                        info.set(f"${r.width}%.0f x ${r.height}%.0f vp ${r.viewportWidth > 0 && r.viewportHeight > 0}")
                    ))
                ),
                info.map(v => UI.span(v).id("out"))
            )
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("out"), "120 x 40 vp true")
            yield ()
        }
    }

end MeasureScenarioItTest
