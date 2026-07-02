package democlient

import demo.FeedClockScene
import kyo.*

/** Live launcher for the server-driven demo: serves ONE three.js scene that simultaneously shows
  * client animation and server-driven reactivity on the SAME cube and STAYS UP so a human can open it.
  *
  *   1. CLIENT-side animation: the cube spins via a client `onFrame`/RAF loop, running inside the
  *      demos bundle's `hydrateFeedClock` entry once it hydrates the SSR'd canvas.
  *   2. SERVER-driven reactivity: the cube material's color binds to a server-owned `SignalRef[Int]`
  *      ([[demo.FeedClockScene.sceneWithMirror]]'s returned signal). A server fiber cycles it through a
  *      fixed palette every ~1s; the ordinary bound-prop path emits one `SetProp` per step, patched
  *      straight onto the client's live cube.
  *
  * This launcher uses ONLY the public API: `UI.runHandlers` (the SSR page + `/_kyo/ws` serve entry),
  * `Three.embed` (the 3D host), and an ordinary `SignalRef`. `runHandlers` serves the SSR page that
  * links a mount shim through `head.moduleScript` (a tiny ES module that imports the demos bundle's
  * `hydrateFeedClock` entry and calls it), which rebuilds the SAME tree client-side (so `data-kyo-path`
  * matches the server's SSR markup by construction) and hydrates the embedded canvas. The page's
  * `head.importMap` resolves the bundle's bare `three` import to the served three module, so the plain
  * `fastLinkJS` bundle loads directly, with no separate bundling step.
  *
  * Link the demos bundle first with `sbt kyo-threejs-demos/fastLinkJS`, then run this launcher as the
  * `kyo-threejs-demo-runner` main (see the README's "Running the demos").
  */
object FeedClock extends ClientDemoApp:

    /** The server color-step interval: the server advances the color signal once per this. */
    private val serverStepMs: Long = 1000L

    /** The route of the mount shim the SSR page links through `head.moduleScript`: a tiny ES module that
      * imports the demos bundle's `hydrateFeedClock` entry and calls it. The bundle's bare `three`
      * import resolves through the page's import map.
      */
    private val shimPath: String = "/_kyo/feedclock-mount.js"

    /** The mount shim module: import the hydrate entry from the served demos bundle and run it. */
    private val mountShim: String =
        """import { hydrateFeedClock } from "/main.js";
          |hydrateFeedClock();
          |""".stripMargin

    run {
        serve(port)
    }

    /** Serves the FeedClock page (via `UI.runHandlers`) plus the mount shim, the demos bundle, and three
      * on `port` (0 = an ephemeral port), forks the server-side palette cycler, prints the open URL, and
      * awaits forever.
      *
      * The scene and its color signal are built ONCE here, under the launcher's own top-level Scope (not
      * per-connection: `UI.runHandlers`'s builder row is `< Async`, no `Scope`, so a per-connection fork
      * has nowhere to bind). The palette cycler forks with the scoped `Fiber.init`, bound to this Scope,
      * so it runs for the launcher's whole process lifetime and is interrupted on shutdown; every
      * connecting client's `ui` closure reuses the SAME scene/signal, so all connected clients observe
      * the SAME color steps.
      */
    private def serve(port: Int)(using Frame): Unit < (Async & Scope & Abort[FileException]) =
        for
            assets         <- DemoClientServe.demoAssetHandlers
            (scene, color) <- FeedClockScene.sceneWithMirror
            _              <- Fiber.init(cyclePalette(color))
            handlers       <- UI.runHandlers("", head)(ui(scene))
            server <- HttpServer.init(port, "localhost")(
                (handlers ++ (DemoClientServe.jsHandler(shimPath, mountShim) +: assets))*
            )
            _ <- Console.printLine(
                s"FeedClock running on http://localhost:${server.port}/  " +
                    "(the cube spins client-side AND steps color red/green/blue/yellow/magenta every ~1s from the server)"
            )
            _ <- server.await
        yield ()

    /** The page head linking the mount shim and carrying the import map that resolves the demos bundle's
      * bare `three` (and jsm) imports. `moduleScript` emits a `<script type="module" src="$shimPath">`
      * into the SSR page; loading the shim imports the demos bundle and hydrates the spinning cube at
      * `#app`, so the connection's own bound-prop path drives the color.
      */
    private def head(using Frame): UI.PageHead =
        UI.PageHead("kyo-threejs FeedClock", moduleScript = Present(shimPath), importMap = DemoClientServe.threeImportMap)

    /** The page body: the pre-built cube scene, embedded as `#app`. */
    private def ui(scene: Three.Ast.Scene)(using Frame): UI < Async =
        UI.div(Three.embed(scene, FeedClockScene.camera).id("app"))

    /** The server-driven palette cycler: every [[serverStepMs]]ms it advances an index and sets `color`
      * to the next palette entry, so the ordinary bound-prop path pushes each step over the WS. The loop
      * state is the next palette index; it never touches the wire directly.
      */
    private def cyclePalette(color: SignalRef[Int])(using Frame): Unit < Async =
        Loop(0) { i =>
            color.set(FeedClockScene.palette(i % FeedClockScene.palette.size))
                .andThen(Async.sleep(serverStepMs.millis))
                .andThen(Loop.continue(i + 1))
        }

end FeedClock
