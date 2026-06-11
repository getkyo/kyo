package kyo

import kyo.Browser.*
import scala.language.implicitConversions

/** Guards the client DomBackend mount path for reactive Fragment values.
  *
  * Every other reactive test goes through the server `runHandlers`/WebSocket path. This test exercises
  * `DomBackend.mount` directly, covering two bugs that the server path cannot reach:
  *   - `applyJsProps` building an invalid `querySelectorAll("[data-kyo-prop-*]")` that threw on mount,
  *     preventing any update from landing.
  *   - `LocalExchange.onChange` dropping the `data-kyo-path` boundary for Fragment values that render
  *     without a path-carrying root, causing only the first update to land.
  */
class UITestSpaRunMountTest extends UITest:

    "reactive Fragment advances A -> B -> C via click-driven signal updates" in {
        val app: UI < Async =
            for ref <- Signal.initRef[String]("A")
            yield
                def advance: Unit < Async =
                    ref.get.map {
                        case "A" => ref.set("B")
                        case "B" => ref.set("C")
                        case _   => ()
                    }
                UI.div(
                    UI.button("next").id("adv").onClick(advance),
                    UI.Ast.Reactive(ref.map(s => UI.fragment(UI.span(s).id("frag-a"), UI.span(s).id("frag-b"))))
                )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("frag-a"), "A")
                _ <- Browser.click(Selector.id("adv"))
                _ <- Browser.assertText(Selector.id("frag-a"), "B")
                _ <- Browser.click(Selector.id("adv"))
                _ <- Browser.assertText(Selector.id("frag-a"), "C")
            yield ()
        }
    }

end UITestSpaRunMountTest
