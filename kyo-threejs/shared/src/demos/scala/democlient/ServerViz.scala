package democlient

import demo.ServerVizScene
import demo.ServerVizScene.Bar
import kyo.*

/** The ServerViz demo, as a standalone app: it serves its own page and its own assets, and nothing else in
  * the repository has to exist for it to run.
  *
  * This one is server-driven, so unlike the static-page demos it serves through `UI.runHandlers`: an SSR'd
  * page plus a `/_kyo/ws` route. The scene and its bar mirror are built ONCE here and a fiber advances
  * every bar on a fixed schedule. Nothing in this file touches the wire: it writes an ordinary `SignalRef`,
  * and the change reaches the browser because a `Three.embed` in a server-rendered tree is a reactive
  * region like any other. The browser's half is `hydrateServerViz`, which the page loads as a module
  * script.
  *
  * What crosses the wire is the WHOLE bar list, not a per-bar edit. A `foreachKeyed` is a structural
  * region: every time the mirror changes, the server serializes the current `Chunk[Bar]` and sends one
  * snapshot, and the browser reconciles that snapshot against the live bars by id. A bar whose value moved
  * is rebuilt from the new value, because a bar's height is baked into its geometry and there is nothing to
  * patch in place; a bar whose value did not move keeps its live object untouched, so its GPU buffers
  * survive. There is no per-bar prop update to be had here even in principle: a `foreach` child's bound
  * props are never reached by the prop walk, so the snapshot is the only path its content can change by.
  *
  * The fiber is forked under this app's own Scope rather than per connection, because `UI.runHandlers`'s
  * builder row carries no `Scope` to bind it to; every connecting client therefore observes the same
  * mirror and sees the same bars.
  *
  * Link the browser bundle first (`sbt kyo-threejsJS/demos/fastLinkJS`), then run this launcher on any host
  * platform (`sbt 'kyo-threejsJVM/demos/runMain democlient.ServerViz'`, or the same under
  * `kyo-threejsJS/demos` to serve from Node).
  */
object ServerViz extends KyoApp:

    /** How often the server advances every bar. */
    private val stepInterval: Duration = 200.millis

    run {
        for
            assets        <- assetHandlers
            (scene, bars) <- ServerVizScene.sceneWithMirror
            _             <- Fiber.init(cycleBars(bars))
            appHandlers   <- UI.runHandlers("", head)(ServerVizScene.page(scene))
            server        <- HttpServer.init(0, "localhost")((appHandlers ++ (shimHandler +: assets))*)
            _ <- Console.printLine(
                s"ServerViz running on http://localhost:${server.port}/  " +
                    "(a server fiber pushes new bar values into the live chart several times a second)"
            )
            _ <- server.await
        yield ()
    }

    /** The page head: the module script that hydrates the SSR'd markup, the import map that resolves the
      * bundle's bare `three` imports, and the CSS that sizes the embedded canvas (a canvas with no layout
      * size renders at its small intrinsic default).
      */
    private def head(using Frame): UI.PageHead =
        UI.PageHead(
            "kyo-threejs ServerViz",
            css = "body { margin: 0; background: #101018; color: #cdd; } " +
                "#stage { display: block; margin: 0 auto; width: 900px; height: 560px; }",
            moduleScript = Present(shimPath),
            importMap = importMap
        )

    /** The route of the mount shim `head` links, and the shim itself: a module that imports this demo's own
      * hydrate entry from the bundle and calls it. `PageHead.moduleScript` links a src, so the named export
      * still needs a caller.
      */
    private val shimPath: String = "/_kyo/serverviz-mount.js"

    private def shimHandler(using Frame): HttpHandler[Any, "body" ~ String, Nothing] =
        jsHandler(
            shimPath,
            s"""import { hydrateServerViz } from "$bundlePath";
               |hydrateServerViz();
               |""".stripMargin
        )

    /** Advances every bar on a fixed schedule, each from its own phase-shifted wave, so the columns rise
      * and fall independently instead of moving in lockstep. The loop's only state is a tick count; it
      * writes a `SignalRef` and never mentions the network.
      */
    private def cycleBars(bars: SignalRef[Chunk[Bar]])(using Frame): Unit < Async =
        Loop(0) { tick =>
            bars.updateAndGet(current => current.map(bar => bar.copy(value = waveValue(tick, bar.id))))
                .andThen(Async.sleep(stepInterval))
                .andThen(Loop.continue(tick + 1))
        }

    /** A deterministic per-bar wave in `0.0..1.0`: no `Random` in the row, so a run is reproducible. */
    private def waveValue(tick: Int, id: Int): Double =
        0.5 + 0.5 * math.sin(tick * 0.15 + id * 0.9)

    // ---- The assets this app serves --------------------------------------------------

    /** The route the page imports the demos bundle from. */
    private val bundlePath: String = "/serverviz.js"

    /** The bare specifiers the bundle imports, mapped to the routes below. The bundle is ONE ES module
      * graph carrying every demo, so all of them must resolve even though this demo loads no model and
      * binds no orbit controls; a mapping nothing imports is simply never fetched.
      */
    private val importMap: Seq[(String, String)] = Seq(
        "three"                                           -> "/three.module.js",
        "three/examples/jsm/loaders/GLTFLoader.js"        -> "/three/examples/jsm/loaders/GLTFLoader.js",
        "three/examples/jsm/utils/BufferGeometryUtils.js" -> "/three/examples/jsm/utils/BufferGeometryUtils.js",
        "three/examples/jsm/utils/SkeletonUtils.js"       -> "/three/examples/jsm/utils/SkeletonUtils.js",
        "three/examples/jsm/controls/OrbitControls.js"    -> "/three/examples/jsm/controls/OrbitControls.js"
    )

    /** Reads the demos link output and the three.js modules off disk at startup and serves them from
      * memory, so a page load never touches the filesystem and a missing artifact fails once, loudly, at
      * boot. Every `.js` file of the link is served (this demo's module plus the shared chunk it imports),
      * so the page's import graph resolves.
      */
    private def assetHandlers(using Frame): Chunk[HttpHandler[Any, "body" ~ String, Nothing]] < (Sync & Abort[FileException]) =
        for
            jsTarget <- buildDir(Path("kyo-threejs", "js", "target"))
            demosDir = jsTarget / "kyo-threejs-demos-fastopt"
            three    = jsTarget / "node_modules" / "three"
            entries <- demosDir.list
            demoHandlers <- Kyo.foreach(entries.filter(_.name.exists(_.endsWith(".js")))) { file =>
                file.read.map(source => jsHandler("/" + file.name.getOrElse(""), source))
            }
            threeFiles = Chunk(
                "/three.module.js"                                 -> (three / "build" / "three.module.js"),
                "/three.core.js"                                   -> (three / "build" / "three.core.js"),
                "/three/examples/jsm/loaders/GLTFLoader.js"        -> (three / "examples" / "jsm" / "loaders" / "GLTFLoader.js"),
                "/three/examples/jsm/utils/BufferGeometryUtils.js" -> (three / "examples" / "jsm" / "utils" / "BufferGeometryUtils.js"),
                "/three/examples/jsm/utils/SkeletonUtils.js"       -> (three / "examples" / "jsm" / "utils" / "SkeletonUtils.js"),
                "/three/examples/jsm/controls/OrbitControls.js"    -> (three / "examples" / "jsm" / "controls" / "OrbitControls.js")
            )
            threeHandlers <- Kyo.foreach(threeFiles)((route, file) => file.read.map(source => jsHandler(route, source)))
        yield demoHandlers.concat(threeHandlers)

    /** sbt writes its output under a `scala-<version>` directory, so the app finds that directory rather
      * than hard-coding the Scala version. An absent target directory means the artifact was never linked.
      */
    private def buildDir(target: Path)(using Frame): Path < (Sync & Abort[FileException]) =
        target.list.map { entries =>
            entries.filter(_.name.exists(_.startsWith("scala-"))).headMaybe match
                case Present(dir) => dir
                case Absent       => Abort.fail(FileNotFoundException(target))
        }

    private def jsHandler(route: String, source: String)(using Frame): HttpHandler[Any, "body" ~ String, Nothing] =
        HttpRoute.getRaw(route).response(_.bodyText).handler { _ =>
            HttpResponse.ok(source).setHeader("Content-Type", "text/javascript; charset=utf-8")
        }

end ServerViz
