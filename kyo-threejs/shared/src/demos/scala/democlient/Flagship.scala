package democlient

import demo.FlagshipScene
import kyo.*

/** The Flagship demo, as a standalone app: it serves its own page and its own assets, and nothing else in
  * the repository has to exist for it to run.
  *
  * Server-driven, so it serves through `UI.runHandlers`: an SSR'd page plus a `/_kyo/ws` route. One cube
  * carries four behaviors at once, and the value of running it here rather than as a static page is that
  * each one is owned by a different side and you can watch them coexist:
  *
  *   1. The cube SPINS from a client `onFrame` loop. The server never touches it.
  *   2. Its COLOUR steps every second from the fiber this app forks, over the page's one WebSocket.
  *   3. A CLICK is raycast in the browser, posted to the server by path, and resolved there: the server
  *      runs the same `onClick` closure the scene was built with, and the cube grows.
  *   4. The HUD beside it is `clientOwned`, so the browser drives it: the frame count reads the live
  *      mount's `renders` signal and Capture reads its framebuffer, neither of which a server can do.
  *
  * Link the browser bundle first (`sbt kyo-threejsJS/demos/fastLinkJS`), then run this launcher on any host
  * platform (`sbt 'kyo-threejsJVM/demos/runMain democlient.Flagship'`, or the same under
  * `kyo-threejsJS/demos` to serve from Node).
  */
object Flagship extends KyoApp:

    /** How often the server advances the cube's colour. */
    private val stepInterval: Duration = 1.second

    run {
        for
            assets            <- assetHandlers
            (scene, color, _) <- FlagshipScene.sceneWithMirrors
            _                 <- Fiber.init(cyclePalette(color))
            appHandlers       <- UI.runHandlers("", head)(FlagshipScene.page(scene))
            server            <- HttpServer.init(0, "localhost")((appHandlers ++ (shimHandler +: assets))*)
            _ <- Console.printLine(
                s"Flagship running on http://localhost:${server.port}/  " +
                    "(the cube spins client-side, the camera orbits it, its colour steps every second from " +
                    "the server, each click grows it, and the HUD counts real frames)"
            )
            _ <- server.await
        yield ()
    }

    /** The page head: the module script that hydrates the SSR'd markup, the import map that resolves the
      * bundle's bare `three` and OrbitControls imports, and the CSS that sizes the embedded canvas (a
      * canvas with no layout size renders at its small intrinsic default).
      */
    private def head(using Frame): UI.PageHead =
        UI.PageHead(
            "kyo-threejs Flagship",
            css = "body { margin: 0; background: #101018; color: #cdd; } " +
                "#stage { display: block; margin: 0 auto; width: 900px; height: 560px; }",
            moduleScript = Present(shimPath),
            importMap = importMap
        )

    /** The route of the mount shim `head` links, and the shim itself: a module that imports this demo's own
      * hydrate entry from the bundle and calls it. `PageHead.moduleScript` links a src, so the named export
      * still needs a caller.
      */
    private val shimPath: String = "/_kyo/flagship-mount.js"

    private def shimHandler(using Frame): HttpHandler[Any, "body" ~ String, Nothing] =
        jsHandler(
            shimPath,
            s"""import { hydrateFlagship } from "$bundlePath";
               |hydrateFlagship();
               |""".stripMargin
        )

    /** Steps the cube's colour through the scene's palette on a fixed schedule. It writes an ordinary
      * `SignalRef`; the bound-prop path carries each step to the browser. The scale signal needs no wiring
      * here at all: the cube's own `onClick` closure advances it wherever the server resolves the click.
      */
    private def cyclePalette(color: SignalRef[Int])(using Frame): Unit < Async =
        Loop(0) { i =>
            color.set(FlagshipScene.palette(i % FlagshipScene.palette.size))
                .andThen(Async.sleep(stepInterval))
                .andThen(Loop.continue(i + 1))
        }

    // ---- The assets this app serves --------------------------------------------------

    /** The route the page imports the demos bundle from. */
    private val bundlePath: String = "/flagship.js"

    /** The bare specifiers the bundle imports, mapped to the routes below. This demo genuinely uses
      * OrbitControls; the rest are here because the bundle is ONE ES module graph carrying every demo, so
      * every specifier in it must resolve for any page to load it.
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

end Flagship
