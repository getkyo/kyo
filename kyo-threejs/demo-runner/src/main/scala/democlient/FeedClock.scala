package democlient

import demo.FeedClockScene
import kyo.*

/** Live launcher for the feed-driven demo over the PUBLIC
  * `Three.Feed` serve path: serves ONE three.js scene that simultaneously shows client animation and
  * server-fed reactivity on the SAME cube and STAYS UP so a human can open it.
  *
  *   1. CLIENT-side animation: the cube spins via a client `onFrame`/RAF loop in the demos bundle's
  *      `mountFeedClock` entry. The server does not drive the spin; the motion is local and continuous.
  *   2. SERVER-driven reactivity: the cube material's color is bound to a server-fed `SignalRef[Int]`
  *      declared with [[Three.Feed.serverSignal]] under [[demo.FeedClockScene.colorId]]. A server fiber
  *      cycles the signal through a fixed palette (red, green, blue, yellow,
  *      magenta) every ~1s; [[Three.Feed.run]] forks one observer per registered fed signal id, so each
  *      emission becomes a `HostUpdate(SignalUpdate(colorId, encoded))` over the WebSocket the scene
  *      receives and writes into its mirror, stepping the cube's color in DISCRETE ~1s jumps.
  *
  * This launcher uses ONLY the public API: `Three.Feed.serverSignal` (the server-owned fed signal), a
  * server-side background fiber (the palette cycler), and `Three.Feed.run` (the serve entry). `run` serves the
  * SSR page that links a mount shim through `head.moduleScript` (a tiny ES module that imports the demos
  * bundle's `mountFeedClock` entry and calls it) and carries the inline kyo-ui client that routes each
  * inbound `HostUpdate` into `window.__kyoHostChannels`; the launcher composes `run`'s handlers with the
  * static handlers serving the shim, the demos bundle, and three. The page's `head.importMap` resolves the
  * bundle's bare `three` import to the served three module, so the plain `fastLinkJS` bundle loads directly,
  * with no separate bundling step.
  *
  * Link the demos bundle first with `sbt kyo-threejs-demos/fastLinkJS`; the `demoClientFeedClock` alias does this.
  */
object FeedClock extends ClientDemoApp:

    /** The server color-step interval: the server advances the fed palette color once per this. */
    private val serverStepMs: Long = 1000L

    /** The route of the mount shim the SSR page links through `head.moduleScript`: a tiny ES module that
      * imports the demos bundle's `mountFeedClock` entry and calls it against the host canvas. The bundle's
      * bare `three` import resolves through the page's import map.
      */
    private val shimPath: String = "/_kyo/feedclock-mount.js"

    /** The mount shim module: import the entry from the served demos bundle and mount it at `#app`. */
    private val mountShim: String =
        """import { mountFeedClock } from "/main.js";
          |mountFeedClock("#app");
          |""".stripMargin

    run {
        serve(port)
    }

    /** Serves the FeedClock page (via [[Three.Feed.run]]) plus the mount shim, the demos bundle, and three
      * on `port` (0 = an ephemeral port), forks the server-side palette cycler, prints the open URL, and
      * awaits forever.
      */
    private def serve(port: Int)(using Frame): Unit < (Async & Scope & Abort[FileException]) =
        for
            assets   <- DemoClientServe.demoAssetHandlers
            handlers <- Three.Feed.run("", head)(ui)
            server <- HttpServer.init(port, "localhost")(
                (handlers ++ (DemoClientServe.jsHandler(shimPath, mountShim) +: assets))*
            )
            _ <- Console.printLine(
                s"FeedClock running on http://localhost:${server.port}/  " +
                    "(the cube spins client-side AND steps color red/green/blue/yellow/magenta every ~1s from the server feed)"
            )
            _ <- server.await
        yield ()

    /** The page head linking the mount shim and carrying the import map that resolves the demos bundle's
      * bare `three` (and jsm) imports. `moduleScript` emits a `<script type="module" src="$shimPath">` into
      * the SSR page; loading the shim imports the demos bundle and mounts the spinning cube at `#app`,
      * connecting the color feed mirror.
      */
    private def head(using Frame): UI.PageHead =
        UI.PageHead("kyo-threejs FeedClock", moduleScript = Present(shimPath), importMap = DemoClientServe.threeImportMap)

    /** The page body the mount shim mounts into: a `<canvas id="app">` host the FeedClock entry selects with
      * `mountFeedClock("#app")`, plus the server-owned fed color signal and its palette cycler.
      *
      * `Three.Feed.serverSignal(colorId, palette.head)` declares the fed signal; running it inside the
      * `Three.Feed.run` WebSocket session registers a feed observer for `colorId`, so each set on the
      * returned ref is pushed over the WS. A server-side background fiber advances the palette index
      * every ~1s and sets the signal, driving the color steps. The driver is forked with the scoped
      * `Fiber.init`, so it binds to the connection Scope and is interrupted on disconnect (no leaked
      * fiber); the tree carries `< (Async & Scope)` because of that scoped fork.
      */
    private def ui(using Frame): UI < (Async & Scope) =
        for
            color <- Three.Feed.serverSignal[Int](FeedClockScene.colorId, FeedClockScene.palette.head)
            _     <- Fiber.init(cyclePalette(color))
        yield UI.host("canvas").id("app")

    /** The server-driven palette cycler: every [[serverStepMs]]ms it advances an index and sets the fed
      * `color` signal to the next palette color, so `Three.Feed.run`'s observer pushes each step over the
      * WS. The loop state is the next palette index; it never touches the wire directly (the observer the
      * runner forked does that on each emission).
      */
    private def cyclePalette(color: SignalRef[Int])(using Frame): Unit < Async =
        Loop(0) { i =>
            color.set(FeedClockScene.palette(i % FeedClockScene.palette.size))
                .andThen(Async.sleep(serverStepMs.millis))
                .andThen(Loop.continue(i + 1))
        }

end FeedClock
