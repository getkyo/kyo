package democlient

import demo.FlagshipScene
import kyo.*
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Live launcher for the Option-Y FLAGSHIP consolidated demo (design 02-design-r2 G5) over the PUBLIC
  * `Three.Feed` serve path: serves ONE three.js scene that simultaneously shows ALL FOUR halves of Y on the
  * SAME cube and STAYS UP so a human can open it.
  *
  *   1. CLIENT animation: the cube spins via a client `onFrame`/RAF loop compiled into the Flagship island
  *      bundle. The motion is local and continuous; the server never drives it.
  *   2. SERVER `serverSignal`: the cube's color binds to a server-fed `SignalRef[Int]`
  *      ([[FlagshipScene.colorId]]). A server fiber cycles a fixed palette every ~1s, so the color steps in
  *      DISCRETE jumps.
  *   3. CLIENT `onClick` -> `emit` -> SERVER reflect: clicking the cube runs its `onClick` LOCALLY and
  *      `Three.Feed.emit`s a typed `Bump`; the server's [[Three.Feed.onAppEvent]] handler advances a
  *      second server-fed `SignalRef[Int]` ([[FlagshipScene.scaleId]]) bound to the cube's scale, so each
  *      click STEPS the cube larger.
  *   4. ORBIT controls: `Three.controls(autoRotate = true)` in the scene makes the island bind a live
  *      `OrbitControls`, so the camera orbits the cube (a distinct rate from the cube's own spin).
  *
  * This launcher uses ONLY the public API: two `Three.Feed.serverSignal` declarations (the auto-cycled
  * color and the click-driven scale), a `Three.Feed.onAppEvent` handler (the click back-channel), a
  * server-side background fiber (the palette cycler), and `Three.Feed.run` (the serve entry). The island
  * inlines three AND OrbitControls (esbuild), so the page needs no import map.
  *
  * Bundle the island first with `sbt flagshipIslandBundle`; the `demoClientFlagship` alias does this.
  */
object Flagship extends ClientDemoApp:

    /** The server color-step interval: the server advances the fed palette color once per this. */
    private val serverStepMs: Long = 1000L

    /** The served route of the Flagship island bundle the page links through `head.moduleScript`. */
    private val islandPath: String = "/_kyo/flagship-island.js"

    run {
        serve(port)
    }

    /** Serves the Flagship page (via [[Three.Feed.run]]) plus the island bundle on `port` (0 = an
      * ephemeral port), forks the server-side palette cycler, prints the open URL, and awaits forever.
      */
    private def serve(port: Int)(using Frame): Unit < (Async & Scope & Abort[FileException]) =
        for
            island   <- readFile(islandFile)
            handlers <- Three.Feed.run("", head)(ui)
            server   <- HttpServer.init(port, "localhost")((handlers :+ jsHandler(islandPath, island))*)
            _ <- Console.printLine(
                s"Flagship running on http://localhost:${server.port}/  " +
                    "(the cube spins client-side, the camera orbits it, its color steps every ~1s from the server feed, and each click grows it via emit)"
            )
            _ <- server.await
        yield ()

    /** The page head that links the self-contained Flagship island bundle. `moduleScript` emits a
      * `<script type="module" src="/_kyo/flagship-island.js">` into the SSR page; the bundle inlines three
      * and OrbitControls and self-runs on load, mounting the spinning, orbited cube at `#app` and
      * connecting both feed mirrors.
      */
    private def head(using Frame): UI.PageHead =
        UI.PageHead("kyo-threejs Option-Y Flagship", moduleScript = Present(islandPath))

    /** The page body the island mounts into: a `<canvas id="app">` host plus the two server-owned fed
      * signals and the click back-channel.
      *
      * `Three.Feed.serverSignal(colorId, ...)` declares the auto-cycled color feed and forks a background
      * fiber that advances a palette index every ~1s. `Three.Feed.serverSignal(scaleId, ...)` declares the
      * click-driven scale feed, and `Three.Feed.onAppEvent(eventId)` registers the handler that advances
      * the scale signal one level per inbound click `Bump`, wrapping at the end. Running these inside the
      * `Three.Feed.run` WebSocket session registers a feed observer per id, so each set on a returned ref is
      * pushed over the WS. The tree carries `< Async` because the palette cycler is forked with
      * `Fiber.initUnscoped`.
      */
    private def ui(using Frame): UI < Async =
        for
            color <- Three.Feed.serverSignal[Int](FlagshipScene.colorId, FlagshipScene.palette.head)
            scale <- Three.Feed.serverSignal[Int](FlagshipScene.scaleId, FlagshipScene.scaleLevels.head)
            _ <- Three.Feed.onAppEvent[FlagshipScene.Bump](FlagshipScene.eventId) { _ =>
                scale.updateAndGet(advanceScale).unit
            }
            _ <- Fiber.initUnscoped(cyclePalette(color))
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

    private def staticHandler(path: String, contentType: String, source: String)(using
        Frame
    ): HttpHandler[Any, "body" ~ String, Nothing] =
        HttpRoute.getRaw(path).response(_.bodyText).handler { _ =>
            HttpResponse.ok(source).setHeader("Content-Type", contentType)
        }

    private def jsHandler(path: String, source: String)(using Frame): HttpHandler[Any, "body" ~ String, Nothing] =
        staticHandler(path, "text/javascript; charset=utf-8", source)

    /** Reads a UTF-8 source file off disk, surfacing a missing file as a typed `FileNotFoundException` so
      * the caller reports the absent artifact rather than crash.
      */
    private def readFile(path: String)(using Frame): String < (Sync & Abort[FileException]) =
        Sync.defer(NodeFs.existsSync(path)).map {
            case true  => Sync.defer(NodeFs.readFileSync(path, "utf8"))
            case false => Abort.fail(FileNotFoundException(Path(path)))
        }

    /** The absolute path of the bundled Flagship island ESM `main.js` (the esbuild output). */
    private lazy val islandFile: String =
        val islandTarget = NodePath.join(NodeProcess.cwd(), "kyo-threejs", "flagship-island", "target")
        locate(islandTarget, d => NodePath.join(islandTarget, d, "esbuild", "main", "out", "main.js"))
            .getOrElse(sys.error(
                s"Flagship island bundle main.js not found under $islandTarget; run 'sbt flagshipIslandBundle' first"
            ))
    end islandFile

    /** Returns `f(dir)` for the first `scala-*` subdirectory of `target` for which the path exists. */
    private def locate(target: String, f: String => String): Maybe[String] =
        if !NodeFs.existsSync(target) then Absent
        else
            Maybe(
                NodeFs.readdirSync(target).toSeq
                    .filter(_.startsWith("scala-"))
                    .map(f)
                    .find(NodeFs.existsSync)
                    .orNull
            )

    @js.native
    @JSImport("node:path", JSImport.Namespace)
    private object NodePath extends js.Object:
        def join(parts: String*): String = js.native
    end NodePath

    @js.native
    @JSImport("node:fs", JSImport.Namespace)
    private object NodeFs extends js.Object:
        def readFileSync(path: String, encoding: String): String = js.native
        def readdirSync(path: String): js.Array[String]          = js.native
        def existsSync(path: String): Boolean                    = js.native
    end NodeFs

    @js.native
    @JSImport("node:process", JSImport.Namespace)
    private object NodeProcess extends js.Object:
        def cwd(): String = js.native
    end NodeProcess

end Flagship
