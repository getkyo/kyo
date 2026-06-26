package democlient

import demo.FlagshipScene
import kyo.*

/** Live launcher for the flagship consolidated demo over the PUBLIC
  * `Three.Feed` serve path: serves ONE three.js scene that simultaneously shows ALL FOUR behaviors on the
  * SAME cube and STAYS UP so a human can open it.
  *
  *   1. CLIENT animation: the cube spins via a client `onFrame`/RAF loop in the demos bundle's `mountFlagship`
  *      entry. The motion is local and continuous; the server never drives it.
  *   2. SERVER `serverSignal`: the cube's color binds to a server-fed `SignalRef[Int]`
  *      ([[FlagshipScene.colorId]]). A server fiber cycles a fixed palette every ~1s, so the color steps in
  *      DISCRETE jumps.
  *   3. CLIENT `onClick` -> `emit` -> SERVER reflect: clicking the cube runs its `onClick` LOCALLY and
  *      `Three.Feed.emit`s a typed `Bump`; the server's [[Three.Feed.onAppEvent]] handler advances a
  *      second server-fed `SignalRef[Int]` ([[FlagshipScene.scaleId]]) bound to the cube's scale, so each
  *      click STEPS the cube larger.
  *   4. ORBIT controls: `Three.controls(autoRotate = true)` in the scene makes the mounted scene bind a live
  *      `OrbitControls`, so the camera orbits the cube (a distinct rate from the cube's own spin).
  *
  * This launcher uses ONLY the public API: two `Three.Feed.serverSignal` declarations (the auto-cycled
  * color and the click-driven scale), a `Three.Feed.onAppEvent` handler (the click back-channel), a
  * server-side background fiber (the palette cycler), and `Three.Feed.run` (the serve entry). `run` serves
  * the SSR page that links a mount shim through `head.moduleScript` (a tiny ES module that imports the demos
  * bundle's `mountFlagship` entry and calls it). The page's `head.importMap` resolves the bundle's bare
  * `three` and OrbitControls imports to the served three modules, so the plain `fastLinkJS` bundle loads
  * directly, with no separate bundling step.
  *
  * Link the demos bundle first with `sbt kyo-threejs-demos/fastLinkJS`; the `demoClientFlagship` alias does this.
  */
object Flagship extends ClientDemoApp:

    /** The server color-step interval: the server advances the fed palette color once per this. */
    private val serverStepMs: Long = 1000L

    /** The route of the mount shim the SSR page links through `head.moduleScript`: a tiny ES module that
      * imports the demos bundle's `mountFlagship` entry and calls it against the host canvas. The bundle's
      * bare `three` and OrbitControls imports resolve through the page's import map.
      */
    private val shimPath: String = "/_kyo/flagship-mount.js"

    /** The mount shim module: import the entry from the served demos bundle and mount it at `#app`. */
    private val mountShim: String =
        """import { mountFlagship } from "/main.js";
          |mountFlagship("#app");
          |""".stripMargin

    run {
        serve(port)
    }

    /** Serves the Flagship page (via [[Three.Feed.run]]) plus the mount shim, the demos bundle, and three
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
                s"Flagship running on http://localhost:${server.port}/  " +
                    "(the cube spins client-side, the camera orbits it, its color steps every ~1s from the server feed, and each click grows it via emit)"
            )
            _ <- server.await
        yield ()

    /** The page head linking the mount shim and carrying the import map that resolves the demos bundle's
      * bare `three` and OrbitControls imports. `moduleScript` emits a `<script type="module" src="$shimPath">`
      * into the SSR page; loading the shim imports the demos bundle and mounts the spinning, orbited cube at
      * `#app`, connecting both feed mirrors.
      */
    private def head(using Frame): UI.PageHead =
        UI.PageHead("kyo-threejs Flagship", moduleScript = Present(shimPath), importMap = DemoClientServe.threeImportMap)

    /** The page body the mount shim mounts into: a `<canvas id="app">` host plus the two server-owned fed
      * signals and the click back-channel.
      *
      * `Three.Feed.serverSignal(colorId, ...)` declares the auto-cycled color feed and forks a background
      * fiber that advances a palette index every ~1s. `Three.Feed.serverSignal(scaleId, ...)` declares the
      * click-driven scale feed, and `Three.Feed.onAppEvent(eventId)` registers the handler that advances
      * the scale signal one level per inbound click `Bump`, wrapping at the end. Running these inside the
      * `Three.Feed.run` WebSocket session registers a feed observer per id, so each set on a returned ref is
      * pushed over the WS. The palette cycler is forked with the scoped `Fiber.init`, so it binds to the
      * connection Scope and is interrupted on disconnect (no leaked fiber); the tree carries
      * `< (Async & Scope)` because of that scoped fork.
      */
    private def ui(using Frame): UI < (Async & Scope) =
        for
            color <- Three.Feed.serverSignal[Int](FlagshipScene.colorId, FlagshipScene.palette.head)
            scale <- Three.Feed.serverSignal[Int](FlagshipScene.scaleId, FlagshipScene.scaleLevels.head)
            _ <- Three.Feed.onAppEvent[FlagshipScene.Bump](FlagshipScene.eventId) { _ =>
                scale.updateAndGet(advanceScale).unit
            }
            _ <- Fiber.init(cyclePalette(color))
        yield UI.host("canvas").id("app").style(_.width(900.px).height(600.px))

    /** Advances the scale level one step in [[FlagshipScene.scaleLevels]], wrapping at the end, so each
      * click grows the cube one notch then resets to its natural size.
      */
    private def advanceScale(current: Int): Int =
        val levels = FlagshipScene.scaleLevels
        val idx    = levels.indexOf(current)
        val next   = if idx < 0 then 0 else (idx + 1) % levels.size
        levels(next)
    end advanceScale

    /** The server-driven palette cycler: every [[serverStepMs]]ms it advances an index and sets the fed
      * `color` signal to the next palette color, so `Three.Feed.run`'s observer pushes each step over the
      * WS. The loop state is the next palette index; it never touches the wire directly.
      */
    private def cyclePalette(color: SignalRef[Int])(using Frame): Unit < Async =
        Loop(0) { i =>
            color.set(FlagshipScene.palette(i % FlagshipScene.palette.size))
                .andThen(Async.sleep(serverStepMs.millis))
                .andThen(Loop.continue(i + 1))
        }

end Flagship
