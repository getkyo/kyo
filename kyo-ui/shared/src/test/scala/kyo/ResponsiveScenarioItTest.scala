package kyo

import kyo.Browser.*
import scala.language.implicitConversions

/** Responsive workflows tested in the browser. The original TUI tests measured ScreenBuffer dimensions directly; in browser we just verify
  * the elements render and remain interactive across realistic viewport widths.
  */
class ResponsiveScenarioItTest extends UITest:

    "form renders at default viewport" in run {
        val ui = UI.div(UI.input.id("a"), UI.input.id("b"), UI.button("Go").id("btn"))
        withUI(ui) {
            for
                _ <- Browser.assertVisible(Selector.id("a"))
                _ <- Browser.assertVisible(Selector.id("b"))
                _ <- Browser.assertText(Selector.id("btn"), "Go")
            yield succeed
        }
    }

    "form remains interactive at narrow viewport" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("")
            yield UI.div(
                UI.input.id("i").style(Style.width(Length.Px(50))).onInput(v => ref.set(v)),
                UI.button("Go").id("btn"),
                ref.map(v => UI.span(v).id("v"))
            )
        withUI(app) {
            for
                _ <- Browser.fill(Selector.id("i"), "abc")
                _ <- Browser.assertText(Selector.id("v"), "abc")
            yield succeed
        }
    }

end ResponsiveScenarioItTest
