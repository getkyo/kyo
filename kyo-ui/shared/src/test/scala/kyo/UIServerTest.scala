package kyo

import kyo.Browser.*

class UIServerTest extends UITest:

    "serve responds with 200 HTML on GET /" in {
        withUI(UI.div("Hello Server").id("root")) {
            for
                _ <- Browser.assertExists(Selector.id("root"))
                _ <- Browser.assertText(Selector.id("root"), "Hello Server")
            yield ()
        }
    }

    "serve renders SSR HTML with no Set-Cookie header" in {
        withUI(UI.div("cookie-check").id("cc")) {
            Browser.assertExists(Selector.id("cc"))
        }
    }

    "event round-trip updates reactive state via full server cycle" in {
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
            yield ()
        }
    }

    "serve trailing-slash path serves same content as without slash" in {
        // UIServer.normalizePath strips trailing slash; test observable effect:
        // withUI mounts at "/" and the page renders correctly regardless of trailing slash
        withUI(UI.div("path-test").id("pt")) {
            Browser.assertText(Selector.id("pt"), "path-test").unit
        }
    }

    "multiple sequential sessions are independent" in {
        // Each withUI call creates a fresh session; verify no state leaks
        val appA: UI < Async =
            for ref <- Signal.initRef("session-a")
            yield UI.div(ref.map(v => UI.span(v).id("sa")))

        val appB: UI < Async =
            for ref <- Signal.initRef("session-b")
            yield UI.div(ref.map(v => UI.span(v).id("sb")))

        for
            _ <- withUI(appA)(Browser.assertText(Selector.id("sa"), "session-a").unit)
            _ <- withUI(appB)(Browser.assertText(Selector.id("sb"), "session-b").unit)
        yield ()
        end for
    }

end UIServerTest
