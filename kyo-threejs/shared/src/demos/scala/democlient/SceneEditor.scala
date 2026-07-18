package democlient

import kyo.*

/** The SceneEditor demo, as a standalone app: it serves its own page and its own assets, and nothing else
  * in the repository has to exist for it to run.
  *
  * A static page: it imports this demo's own `mountSceneEditor` entry from the linked demos bundle and
  * calls it against the page's `<div>`, so the whole demo runs in the browser through `UI.runMount`. The
  * direction of travel is the reverse of ControlPanel's: a click on a 3D object writes the selection
  * signal, and the panel is rendered from it.
  *
  * Link the browser bundle first (`sbt kyo-threejsJS/demos/fastLinkJS`), then run this launcher on any host
  * platform (`sbt 'kyo-threejsJVM/demos/runMain democlient.SceneEditor'`, or the same under
  * `kyo-threejsJS/demos` to serve from Node).
  */
object SceneEditor extends KyoApp:

    run {
        for
            assets <- assetHandlers
            server <- HttpServer.init(0, "localhost")((pageHandler +: assets)*)
            _ <- Console.printLine(
                s"SceneEditor running on http://localhost:${server.port}/  " +
                    "(click the cube or the sphere, then edit its colour and scale in the panel)"
            )
            _ <- server.await
        yield ()
    }

    /** The served page: an import map so the bundle's bare `three` imports resolve to the modules this app
      * serves, a `<div>` host for the kyo-ui tree, and a module script importing this demo's entry and
      * calling it.
      */
    private def pageHandler(using Frame): HttpHandler[Any, "body" ~ String, Nothing] =
        handler("", "text/html; charset=utf-8", page)

    private def page: String =
        s"""<!doctype html>
           |<html>
           |<head>
           |<meta charset="utf-8">
           |<title>kyo-threejs SceneEditor</title>
           |<script type="importmap">
           |{ "imports": {
           |$importMapJson
           |} }
           |</script>
           |<style>html,body{margin:0;background:#101018;color:#cdd}
           |#app{margin:0 auto;max-width:960px;padding:12px}
           |#stage{display:block;width:900px;height:560px}</style>
           |</head>
           |<body>
           |<div id="app"></div>
           |<script type="module">
           |try {
           |    const { mountSceneEditor } = await import("$bundlePath");
           |    mountSceneEditor("#app");
           |} catch (e) {
           |    document.body.innerHTML = "<pre style='color:#f88'>mount failed: " + String(e && e.message ? e.message : e) + "</pre>";
           |}
           |</script>
           |</body>
           |</html>""".stripMargin

    // ---- The assets this app serves --------------------------------------------------

    /** The route the page imports the demos bundle from. */
    private val bundlePath: String = "/sceneeditor.js"

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

    private def importMapJson: String =
        importMap.map((specifier, route) => s"""    "$specifier": "$route"""").mkString(",\n")

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
        handler(route, "text/javascript; charset=utf-8", source)

    private def handler(route: String, contentType: String, body: String)(using
        Frame
    ): HttpHandler[Any, "body" ~ String, Nothing] =
        HttpRoute.getRaw(route).response(_.bodyText).handler { _ =>
            HttpResponse.ok(body).setHeader("Content-Type", contentType)
        }

end SceneEditor
