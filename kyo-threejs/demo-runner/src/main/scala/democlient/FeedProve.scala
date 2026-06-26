package democlient

import demo.FeedProveScene
import kyo.*
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Live launcher for the feed-driven demo over the PUBLIC
  * `Three.Feed` serve path: serves ONE three.js scene that simultaneously shows client animation and
  * server-fed reactivity on the SAME cube and STAYS UP so a human can open it.
  *
  *   1. CLIENT-side animation: the cube spins via a client `onFrame`/RAF loop compiled into the FeedProve
  *      island bundle. The server does not drive the spin; the motion is local and continuous.
  *   2. SERVER-driven reactivity: the cube material's color is bound to a server-fed `SignalRef[Int]`
  *      declared with [[Three.Feed.serverSignal]] under [[demo.FeedProveScene.colorId]]. A server fiber
  *      cycles the signal through a fixed palette (red, green, blue, yellow,
  *      magenta) every ~1s; [[Three.Feed.run]] forks one observer per registered fed signal id, so each
  *      emission becomes a `HostUpdate(SignalUpdate(colorId, encoded))` over the WebSocket the island
  *      receives and writes into its mirror, stepping the cube's color in DISCRETE ~1s jumps.
  *
  * This launcher uses ONLY the public API: `Three.Feed.serverSignal` (the server-owned fed signal), a
  * server-side background fiber (the palette cycler), and `Three.Feed.run` (the serve entry). `run` serves the
  * SSR page that links the self-contained FeedProve island bundle through `head.moduleScript` and carries
  * the inline kyo-ui client that routes each inbound `HostUpdate` into `window.__kyoHostChannels`; the
  * launcher composes `run`'s handlers with a static handler that serves the island bundle bytes, then
  * starts the server and awaits. The island inlines three (esbuild), so the page needs no import map and
  * no separately-served three.
  *
  * Bundle the island first with `sbt feedProveIslandBundle`; the `demoClientFeedProve` alias does this.
  */
object FeedProve extends ClientDemoApp:

    /** The server color-step interval: the server advances the fed palette color once per this. */
    private val serverStepMs: Long = 1000L

    /** The served route of the FeedProve island bundle the page links through `head.moduleScript`. */
    private val islandPath: String = "/_kyo/feedprove-island.js"

    run {
        serve(port)
    }

    /** Serves the FeedProve page (via [[Three.Feed.run]]) plus the island bundle on `port` (0 = an
      * ephemeral port), forks the server-side palette cycler, prints the open URL, and awaits forever.
      */
    private def serve(port: Int)(using Frame): Unit < (Async & Scope & Abort[FileException]) =
        for
            island   <- readFile(islandFile)
            handlers <- Three.Feed.run("", head)(ui)
            server   <- HttpServer.init(port, "localhost")((handlers :+ jsHandler(islandPath, island))*)
            _ <- Console.printLine(
                s"FeedProve running on http://localhost:${server.port}/  " +
                    "(the cube spins client-side AND steps color red/green/blue/yellow/magenta every ~1s from the server feed)"
            )
            _ <- server.await
        yield ()

    /** The page head that links the self-contained FeedProve island bundle. `moduleScript` emits a
      * `<script type="module" src="/_kyo/feedprove-island.js">` into the SSR page; the bundle inlines three
      * and self-runs on load, mounting the spinning cube at `#app` and connecting the color feed mirror.
      */
    private def head(using Frame): UI.PageHead =
        UI.PageHead("kyo-threejs FeedProve", moduleScript = Present(islandPath))

    /** The page body the island mounts into: a `<canvas id="app">` host the FeedProve island selects with
      * `mountFeedProve("#app")`, plus the server-owned fed color signal and its palette cycler.
      *
      * `Three.Feed.serverSignal(colorId, palette.head)` declares the fed signal; running it inside the
      * `Three.Feed.run` WebSocket session registers a feed observer for `colorId`, so each set on the
      * returned ref is pushed over the WS. A server-side background fiber (a `Fiber.initUnscoped` engine)
      * advances the palette index every ~1s and sets the signal, driving the color steps. The tree
      * carries `< Async` because the engine fiber is forked with `Fiber.initUnscoped`.
      */
    private def ui(using Frame): UI < Async =
        for
            color <- Three.Feed.serverSignal[Int](FeedProveScene.colorId, FeedProveScene.palette.head)
            _     <- Fiber.initUnscoped(cyclePalette(color))
        yield UI.host("canvas").id("app")

    /** The server-driven palette cycler: every [[serverStepMs]]ms it advances an index and sets the fed
      * `color` signal to the next palette color, so `Three.Feed.run`'s observer pushes each step over the
      * WS. The loop state is the next palette index; it never touches the wire directly (the observer the
      * runner forked does that on each emission).
      */
    private def cyclePalette(color: SignalRef[Int])(using Frame): Unit < Async =
        Loop(0) { i =>
            color.set(FeedProveScene.palette(i % FeedProveScene.palette.size))
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

    /** The absolute path of the bundled FeedProve island ESM `main.js` (the esbuild output). */
    private lazy val islandFile: String =
        val islandTarget = NodePath.join(NodeProcess.cwd(), "kyo-threejs", "feedprove-island", "target")
        locate(islandTarget, d => NodePath.join(islandTarget, d, "esbuild", "main", "out", "main.js"))
            .getOrElse(sys.error(
                s"FeedProve island bundle main.js not found under $islandTarget; run 'sbt feedProveIslandBundle' first"
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

end FeedProve
