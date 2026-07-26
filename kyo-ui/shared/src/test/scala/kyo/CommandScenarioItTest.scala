package kyo

import kyo.Browser.*

/** Command op end-to-end over the server-push transport in real Chrome. The `focus` verb targets an input whose
  * `onFocus` writes a Signal, so the focus move is observable in the page text (not only via document.activeElement).
  */
class CommandScenarioItTest extends UITest:

    private def app: UI < Async =
        for focused <- Signal.initRef("none")
        yield UI.div(
            UI.input.id("target").tabIndex(0).onFocus(focused.set("target")),
            UI.button("focus").id("btn").onClick(UI.commands.flatMap(_.focus(Seq("0")))),
            UI.button("bogus").id("btn2").onClick(UI.commands.flatMap(_.command(Seq("0"), "totallyUnknownVerb"))),
            focused.map(v => UI.span(v).id("out"))
        )

    "Command focus moves focus to the target element" in {
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("btn"))
                // onFocus fired -> element.focus() actually moved DOM focus to #target.
                _ <- Browser.assertText(Selector.id("out"), "target")
            yield ()
        }
    }

    "an unknown Command verb is a no-op (no crash, focus unchanged, later commands still work)" in {
        withUI(app) {
            for
                _ <- Browser.click(Selector.id("btn2"))
                _ <- Browser.assertText(Selector.id("out"), "none")
                // A real focus command afterwards still works: the unknown verb did not wedge the client.
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("out"), "target")
            yield ()
        }
    }

end CommandScenarioItTest
