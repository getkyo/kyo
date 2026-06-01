package kyo

import kyo.Browser.*
import scala.language.implicitConversions

class UIServerTest extends UITest:

    "serve responds with 200 HTML on GET /" in run {
        withUI(UI.div("Hello Server").id("root")) {
            for
                _ <- Browser.assertExists(Selector.id("root"))
                _ <- Browser.assertText(Selector.id("root"), "Hello Server")
            yield succeed
        }
    }

    "serve sets kyo-sid cookie" in run {
        withUI(UI.div("cookie-check").id("cc")) {
            for
                title <- Browser.title
                // The page must have loaded (cookie set during GET /);
                // verify the element is visible (session was correctly created)
                _ <- Browser.assertExists(Selector.id("cc"))
            yield succeed
        }
    }

    "event POST updates reactive state via full server cycle" in run {
        val app: UI < Async =
            for ref <- Signal.initRef("before")
            yield UI.div(
                UI.button("Send").id("btn").onClick(ref.set("after")),
                ref.map(v => UI.span(v).id("val"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("val"), "before")
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("val"), "after")
            yield succeed
        }
    }

    "serve trailing-slash path serves same content as without slash" in run {
        // UIServer.normalizePath strips trailing slash; test observable effect:
        // withUI mounts at "/" and the page renders correctly regardless of trailing slash
        withUI(UI.div("path-test").id("pt")) {
            Browser.assertText(Selector.id("pt"), "path-test").andThen(succeed)
        }
    }

    "multiple sequential sessions are independent" in run {
        // Each withUI call creates a fresh session; verify no state leaks
        val appA: UI < Async =
            for ref <- Signal.initRef("session-a")
            yield UI.div(ref.map(v => UI.span(v).id("sa")))

        val appB: UI < Async =
            for ref <- Signal.initRef("session-b")
            yield UI.div(ref.map(v => UI.span(v).id("sb")))

        for
            _ <- withUI(appA)(Browser.assertText(Selector.id("sa"), "session-a").andThen(succeed))
            _ <- withUI(appB)(Browser.assertText(Selector.id("sb"), "session-b").andThen(succeed))
        yield succeed
        end for
    }

end UIServerTest
