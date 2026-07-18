package democlient

import kyo.*

/** The ControlPanel demo, as a standalone app: it serves its own page and its own assets, and nothing else
  * in the repository has to exist for it to run.
  *
  * The page is static. It links the linked demos bundle, imports this demo's own `mountControlPanel` entry
  * from it, and calls it against the page's `<div>`; the demo then runs entirely in the browser through
  * `UI.runMount`, so the controls and the cube they drive are one client-side tree. No server round trip
  * animates anything here, which is the point of this one: a kyo-ui control and a 3D prop share a signal,
  * not a channel.
  *
  * Link the browser bundle first (`sbt kyo-threejsJS/demos/fastLinkJS`), then run this launcher on any host
  * platform (`sbt 'kyo-threejsJVM/demos/run'`, or `kyo-threejsJS/demos/run` to serve from Node). A missing
  * bundle surfaces as a typed `FileException` naming the file it could not read, rather than as a blank page.
  */
object ControlPanel extends KyoApp:

    run {
        for
            assets <- assetHandlers
            server <- HttpServer.init(0, "localhost")((pageHandler +: assets)*)
            _ <- Console.printLine(
                s"ControlPanel running on http://localhost:${server.port}/  " +
                    "(drag the colour picker, the size slider, or the spin checkbox)"
            )
            _ <- server.await
        yield ()
    }

    /** The served page: an import map so the bundle's bare `three` imports resolve to the modules this app
      * serves, a `<div>` host for the kyo-ui tree, and a module script importing this demo's entry and
      * calling it. A failed import is written into the page instead of the console, so a stale bundle looks
      * like a stale bundle rather than a broken demo.
      */
    private def pageHandler(using Frame): HttpHandler[Any, "body" ~ String, Nothing] =
        handler("", "text/html; charset=utf-8", page)

    private def page: String =
        s"""<!doctype html>
           |<html>
           |<head>
           |<meta charset="utf-8">
           |<title>kyo-threejs ControlPanel</title>
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
           |    const { mountControlPanel } = await import("$bundlePath");
           |    mountControlPanel("#app");
           |} catch (e) {
           |    document.body.innerHTML = "<pre style='color:#f88'>mount failed: " + String(e && e.message ? e.message : e) + "</pre>";
           |}
           |</script>
           |</body>
           |</html>""".stripMargin

    // ---- The assets this app serves --------------------------------------------------

    /** The route the page imports THIS demo's entry module from (its own `@JSExportTopLevel(_, "controlpanel")`
      * module, so the page never pulls the launcher's node code that lives in `main.js`). */
    private val bundlePath: String = "/controlpanel.js"

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
      * boot. Every `.js` file of the link is served (this demo's `controlpanel.js` module plus the shared
      * chunk it imports), so the page's import graph resolves.
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

end ControlPanel
