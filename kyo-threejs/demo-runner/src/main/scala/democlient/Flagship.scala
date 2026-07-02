package democlient

import demo.FlagshipScene
import kyo.*

/** Live launcher for the flagship consolidated demo: serves ONE three.js scene that simultaneously
  * shows ALL FOUR behaviors on the SAME cube and STAYS UP so a human can open it.
  *
  *   1. CLIENT animation: the cube spins via a client `onFrame`/RAF loop running inside the demos
  *      bundle's `hydrateFlagship` entry. The motion is local and continuous; the server never drives it.
  *   2. SERVER-driven reactivity: the cube's color binds to a server-owned `SignalRef[Int]`
  *      ([[demo.FlagshipScene.sceneWithMirrors]]'s color signal). A server fiber cycles a fixed palette
  *      every ~1s, so the color steps in DISCRETE jumps.
  *   3. CLIENT raycast -> SERVER resolve: clicking the cube is raycast locally and posted to the server
  *      addressed by path; the server resolves and runs the SAME `onClick` closure the scene was built
  *      with (it closes over the scale signal), so each click STEPS the cube larger.
  *   4. ORBIT controls: `Three.controls(autoRotate = true)` in the scene makes the mounted scene bind a
  *      live `OrbitControls`, so the camera orbits the cube.
  *
  * This launcher uses ONLY the public API: `UI.runHandlers` (the SSR page + `/_kyo/ws` serve entry),
  * `Three.embed` (the 3D host), and an ordinary `SignalRef` for the auto-cycled color (the scale signal
  * needs no launcher-side wiring at all: the cube's own `onClick` closure, built server-side, advances
  * it directly). `runHandlers` serves the SSR page that links a mount shim through `head.moduleScript`
  * (a tiny ES module that imports the demos bundle's `hydrateFlagship` entry and calls it), which
  * rebuilds the SAME tree client-side and hydrates the embedded canvas. The page's `head.importMap`
  * resolves the bundle's bare `three` and OrbitControls imports to the served three modules.
  *
  * Link the demos bundle first with `sbt kyo-threejs-demos/fastLinkJS`, then run this launcher as the
  * `kyo-threejs-demo-runner` main (see the README's "Running the demos").
  */
object Flagship extends ClientDemoApp:

    /** The server color-step interval: the server advances the color signal once per this. */
    private val serverStepMs: Long = 1000L

    /** The route of the mount shim the SSR page links through `head.moduleScript`: a tiny ES module that
      * imports the demos bundle's `hydrateFlagship` entry and calls it. The bundle's bare `three` and
      * OrbitControls imports resolve through the page's import map.
      */
    private val shimPath: String = "/_kyo/flagship-mount.js"

    /** The mount shim module: import the hydrate entry from the served demos bundle and run it. */
    private val mountShim: String =
        """import { hydrateFlagship } from "/main.js";
          |hydrateFlagship();
          |""".stripMargin

    run {
        serve(port)
    }

    /** Serves the Flagship page (via `UI.runHandlers`) plus the mount shim, the demos bundle, and three
      * on `port` (0 = an ephemeral port), forks the server-side palette cycler, prints the open URL, and
      * awaits forever.
      *
      * The scene and its color signal are built ONCE here, under the launcher's own top-level Scope (not
      * per-connection: `UI.runHandlers`'s builder row is `< Async`, no `Scope`, so a per-connection fork
      * has nowhere to bind). The palette cycler forks with the scoped `Fiber.init`, bound to this Scope,
      * so it runs for the launcher's whole process lifetime and is interrupted on shutdown; every
      * connecting client's `ui` closure reuses the SAME scene/signal, so all connected clients observe
      * the SAME color steps. The scale signal needs no such wiring: the cube's own `onClick` closure,
      * built inside `sceneWithMirrors`, advances it directly wherever the server resolves the click.
      */
    private def serve(port: Int)(using Frame): Unit < (Async & Scope & Abort[FileException]) =
        for
            assets            <- DemoClientServe.demoAssetHandlers
            (scene, color, _) <- FlagshipScene.sceneWithMirrors
            _                 <- Fiber.init(cyclePalette(color))
            handlers          <- UI.runHandlers("", head)(ui(scene))
            server <- HttpServer.init(port, "localhost")(
                (handlers ++ (DemoClientServe.jsHandler(shimPath, mountShim) +: assets))*
            )
            _ <- Console.printLine(
                s"Flagship running on http://localhost:${server.port}/  " +
                    "(the cube spins client-side, the camera orbits it, its color steps every ~1s from the server, and each click grows it)"
            )
            _ <- server.await
        yield ()

    /** The page head linking the mount shim, the import map that resolves the demos bundle's bare
      * `three` and OrbitControls imports, and the CSS that sizes the embedded canvas (the renderer sizes
      * itself to the canvas's own layout dimensions, so an unsized `#app` would default to a small
      * intrinsic canvas).
      */
    private def head(using Frame): UI.PageHead =
        UI.PageHead(
            "kyo-threejs Flagship",
            css = "#app { display: block; margin: 0 auto; width: 900px; height: 600px; }",
            moduleScript = Present(shimPath),
            importMap = DemoClientServe.threeImportMap
        )

    /** The page body: the pre-built cube scene, embedded as `#app`. */
    private def ui(scene: Three.Ast.Scene)(using Frame): UI < Async =
        UI.div(Three.embed(scene, FlagshipScene.camera).id("app"))

    /** The server-driven palette cycler: every [[serverStepMs]]ms it advances an index and sets `color`
      * to the next palette entry, so the ordinary bound-prop path pushes each step over the WS. The loop
      * state is the next palette index; it never touches the wire directly.
      */
    private def cyclePalette(color: SignalRef[Int])(using Frame): Unit < Async =
        Loop(0) { i =>
            color.set(FlagshipScene.palette(i % FlagshipScene.palette.size))
                .andThen(Async.sleep(serverStepMs.millis))
                .andThen(Loop.continue(i + 1))
        }

end Flagship
