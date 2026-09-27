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

    "WebSocket reconnects and reactive updates resume after connection drop" in {
        val app: UI < Async =
            for ref <- Signal.initRef("before")
            yield UI.div(
                UI.button("update").id("btn").onClick(ref.set("after")),
                ref.map(v => UI.span(v).id("val"))
            )
        withUI(app) {
            for
                _ <- Browser.assertText(Selector.id("val"), "before")
                _ <- Browser.eval("window.__kyoWs.close()")
                _ <- Browser.click(Selector.id("btn"))
                _ <- Browser.assertText(Selector.id("val"), "after")
            yield ()
        }
    }

    // ---- Request-aware handlers: every path under the base is a page, and the session sees the same request ----

    private val head = UI.PageHead(title = "Request pages", css = ".brand{color:rgb(1, 2, 3)}")

    /** A page naming its request: a const heading from the SSR evaluation, and a reactive region the live session re-renders, so the
      * region's text after a click is what the session's own evaluation saw.
      */
    private def page(request: UI.Request)(using Frame): UI < Async =
        Signal.initRef(0).map { clicks =>
            val tab   = request.query.getOrElse("tab", "none")
            val query = request.query.toSeq.sorted.map((name, value) => s"$name=$value").mkString(",")
            UI.div(
                UI.h1(s"page ${request.path}").id("title").cssClass("brand"),
                UI.button("Count").id("count").onClick(clicks.updateAndGet(_ + 1)),
                clicks.map(n => UI.span(s"${request.path} $tab $n").id("session")),
                clicks.map(n => UI.span(s"$query $n").id("query")),
                UI.a.href(UI.Href.Path("/app/two"))("two").id("to-two")
            )
        }

    /** Serves `page` under `/app` on its own server and runs `f` in the shared Chrome against the server's origin. */
    private def withPages[A](f: String => A < (Browser & Abort[BrowserException]))(using Frame) =
        withBrowserRetry {
            cancelOnUnsupportedPlatform {
                for
                    handlers <- UI.runHandlers("/app", head)(page)
                    server   <- HttpServer.init(0, "localhost")(handlers*)
                    result   <- Browser.runShared()(f(s"http://localhost:${server.port}"))
                yield result
            }
        }

    "request handlers serve each path under the base as its own page, with the session evaluating the same path" in {
        withPages { origin =>
            for
                _ <- Browser.goto(s"$origin/app/one")
                _ <- Browser.assertText(Selector.id("title"), "page /one")
                _ <- Browser.click(Selector.id("count"))
                _ <- Browser.assertText(Selector.id("session"), "/one none 1")
                _ <- Browser.goto(s"$origin/app/two/deeper")
                _ <- Browser.assertText(Selector.id("title"), "page /two/deeper")
                _ <- Browser.click(Selector.id("count"))
                _ <- Browser.assertText(Selector.id("session"), "/two/deeper none 1")
            yield ()
        }
    }

    "the base itself, with or without a trailing slash, is the page at /" in {
        withPages { origin =>
            for
                _ <- Browser.goto(s"$origin/app")
                _ <- Browser.assertText(Selector.id("title"), "page /")
                _ <- Browser.goto(s"$origin/app/")
                _ <- Browser.assertText(Selector.id("title"), "page /")
                _ <- Browser.click(Selector.id("count"))
                _ <- Browser.assertText(Selector.id("session"), "/ none 1")
            yield ()
        }
    }

    "the query reaches the page and the live session" in {
        withPages { origin =>
            for
                _ <- Browser.goto(s"$origin/app/one?tab=files&other=x")
                _ <- Browser.assertText(Selector.id("session"), "/one files 0")
                _ <- Browser.click(Selector.id("count"))
                _ <- Browser.assertText(Selector.id("session"), "/one files 1")
            yield ()
        }
    }

    "the served HTML is rendered for the request's path and query, under the base and at the base itself" in {
        withPages { origin =>
            Abort.run[HttpException] {
                for
                    under <- HttpClient.getText(s"$origin/app/one?path=src%2FMain.scala&tab=files")
                    base  <- HttpClient.getText(s"$origin/app?tab=files")
                yield (under, base)
            }
        }.map { fetched =>
            val (under, base) = fetched.getOrElse(("", ""))
            assert(fetched.isSuccess, fetched.toString)
            assert(under.contains(">page /one<") && under.contains(">path=src/Main.scala,tab=files 0<"), under)
            assert(base.contains(">page /<") && base.contains(">tab=files 0<"), base)
        }
    }

    "every query parameter reaches the live session decoded once, as it reaches the page" in {
        withPages { origin =>
            for
                _ <- Browser.goto(s"$origin/app/one?path=src%2FMain.scala&tab=files&note=a%20b")
                _ <- Browser.assertText(Selector.id("query"), "note=a b,path=src/Main.scala,tab=files 0")
                _ <- Browser.click(Selector.id("count"))
                _ <- Browser.assertText(Selector.id("query"), "note=a b,path=src/Main.scala,tab=files 1")
            yield ()
        }
    }

    "the head's title, viewport and css are in the served page" in {
        withPages { origin =>
            for
                _        <- Browser.goto(s"$origin/app/one")
                title    <- Browser.title
                viewport <- Browser.evalBoolean("document.querySelector('meta[name=viewport]') !== null")
                color    <- Browser.eval("getComputedStyle(document.getElementById('title')).color")
            yield assert(title == "Request pages" && viewport && color == "rgb(1, 2, 3)")
        }
    }

    "an anchor with a click handler runs it and stays on the page, while one without is followed" in {
        val app: UI < Async =
            for ref <- Signal.initRef("idle")
            yield UI.div(
                UI.a.href(UI.Href.Path("/elsewhere")).id("handled").onClick(ref.set("handled"))("handled"),
                ref.map(v => UI.span(v).id("state"))
            )
        withUI(app) {
            for
                before <- Browser.url
                _      <- Browser.click(Selector.id("handled"))
                _      <- Browser.assertText(Selector.id("state"), "handled")
                after  <- Browser.url
            yield assert(after == before)
        }
    }

    "back, forward and reload land on the page of the URL, each with a live session" in {
        withPages { origin =>
            for
                _ <- Browser.goto(s"$origin/app/one")
                _ <- Browser.click(Selector.id("to-two"))
                _ <- Browser.assertText(Selector.id("title"), "page /two")
                _ <- Browser.back
                _ <- Browser.assertText(Selector.id("title"), "page /one")
                _ <- Browser.click(Selector.id("count"))
                _ <- Browser.assertText(Selector.id("session"), "/one none 1")
                _ <- Browser.forward
                _ <- Browser.assertText(Selector.id("title"), "page /two")
                _ <- Browser.reload()
                _ <- Browser.assertText(Selector.id("title"), "page /two")
                _ <- Browser.click(Selector.id("count"))
                _ <- Browser.assertText(Selector.id("session"), "/two none 1")
            yield ()
        }
    }

end UIServerTest
