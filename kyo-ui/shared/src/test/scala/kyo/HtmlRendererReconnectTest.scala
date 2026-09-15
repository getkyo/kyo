package kyo

import kyo.Browser.*
import kyo.internal.UIServer

/** Regression guard for the reactive client's socket recovery (the Windows WSAENOBUFS / error 10055 click loss).
  *
  * The page opens one WebSocket back to the UI server and every event handler posts over it. While no session is live the client buffers the
  * event instead, and that buffer has exactly one drain point. So a socket that never comes back leaves the page inert while still looking
  * healthy, and the click surfaces later as an assertion failing on state that never arrived rather than as a transport failure.
  *
  * Driven here by serving the real page route beside a gated session route that ends each session immediately while the gate is shut. That
  * reproduces the client state the socket exhaustion produces (nothing reading the socket, events buffered, no recovery) without needing the
  * exhaustion itself, which is Windows-specific and rare. The gate then opens, and each leaf requires the page to catch up: recovery is the
  * property under test, so the assertion is that the update EVENTUALLY lands, with a budget sized past the client's capped backoff so it
  * detects a page that never recovers and never doubles as the thing being measured.
  *
  * These leaves bind their own server and so cannot use `withUI`, which is why they take its browser retry explicitly.
  */
class HtmlRendererReconnectTest extends UITest:

    /** Serves `app`'s real page route beside a session route that is refused until `serving` is set. */
    private def gatedServer(app: UI, serving: AtomicBoolean)(using Frame) =
        for
            routes <- UIServer.handlers("/")(app)
            // routes.head is the page route; the session route is replaced by a gated one that accepts the upgrade and ends the session at
            // once, which is the state the client is left in when its socket cannot be established.
            gated = HttpHandler.webSocket("/_kyo/ws") { (_, socket) =>
                serving.get.map {
                    case true  => UIServer.serveSession(socket, app)
                    case false => Kyo.unit
                }
            }
            server <- HttpServer.init(0, "localhost")(routes.head, gated)
        yield server

    private val recovery = Present(Schedule.fixed(100.millis).maxDuration(30.seconds))

    "a click posted while the reactive socket is down is delivered once it recovers" in {
        withBrowserRetry {
            cancelOnUnsupportedPlatform {
                for
                    ref     <- Signal.initRef("before")
                    serving <- AtomicBoolean.init(false)
                    app = UI.div(
                        UI.button("Change").id("b").onClick(ref.set("after")),
                        ref.map(value => UI.span(value).id("v"))
                    )
                    server <- gatedServer(app, serving)
                    result <- Browser.runShared() {
                        for
                            _ <- Browser.goto(s"http://localhost:${server.port}/")
                            _ <- Browser.assertText(Selector.id("v"), "before")
                            // Posted with no session reading the socket, so the client buffers it. Nothing reaches the server yet.
                            _ <- Browser.click(Selector.id("b"))
                            _ <- serving.set(true)
                            _ <- Browser.assertText(Selector.id("v"), "after", recovery)
                        yield ()
                    }
                yield result
            }
        }
    }

    "a change made while the socket is down is on the page after it recovers" in {
        // Recovery is only worth anything if the recovered page is CURRENT. A reconnected session subscribes with no record of what the client
        // already has, so each region's first emission carries the whole region and the page catches up on everything it missed. That is what
        // makes a dropped socket recoverable rather than merely reconnected, so it is asserted rather than assumed.
        withBrowserRetry {
            cancelOnUnsupportedPlatform {
                for
                    ref     <- Signal.initRef("before")
                    serving <- AtomicBoolean.init(false)
                    app = UI.div(ref.map(value => UI.span(value).id("v")))
                    server <- gatedServer(app, serving)
                    result <- Browser.runShared() {
                        for
                            _ <- Browser.goto(s"http://localhost:${server.port}/")
                            _ <- Browser.assertText(Selector.id("v"), "before")
                            // No session is observing this, so nothing is queued anywhere: the client can only learn it from the full region
                            // the next session sends.
                            _ <- ref.set("changed")
                            _ <- serving.set(true)
                            _ <- Browser.assertText(Selector.id("v"), "changed", recovery)
                        yield ()
                    }
                yield result
            }
        }
    }

end HtmlRendererReconnectTest
