package democlient

import kyo.*

/** The GltfInspector demo, as a standalone app: it serves its own page, its own assets, and its own MODEL,
  * and nothing else in the repository has to exist for it to run.
  *
  * The model matters here, because loading a real asset is the thing this demo teaches. It is a real glTF
  * file on disk ([[modelFile]]), read at startup and served at [[modelPath]], and that same [[modelPath]]
  * is the URL handed to the page. The route the server answers and the URL the page asks for are ONE
  * value, so a page cannot ask for an asset this app does not serve.
  *
  * Both halves of that are load-bearing. An app that names a route someone else is expected to serve can
  * drift from its server without either side noticing, and the failure surfaces as a black canvas: the
  * browser 404s, the typed glTF load fails, and a scene that renders nothing looks the same as a scene
  * that has not loaded yet. Reading the model from a real file closes the other half, because a missing
  * file fails at startup with a typed `FileException` naming the path, rather than at load time in a
  * browser with nothing to say why.
  *
  * Link the browser bundle first (`sbt kyo-threejsJS/demos/fastLinkJS`), then run this launcher on any host
  * platform (`sbt 'kyo-threejsJVM/demos/runMain democlient.GltfInspector'`, or the same under
  * `kyo-threejsJS/demos` to serve from Node).
  */
object GltfInspector extends KyoApp:

    /** The route this app serves its model on, and the URL it hands the page. One value, so the two cannot
      * disagree.
      */
    private val modelPath: String = "/models/cube.gltf"

    /** The model on disk: a real glTF file this app reads and serves. */
    private val modelFile: Path = Path("kyo-threejs", "js", "src", "demos", "models", "cube.gltf")

    run {
        for
            model  <- modelFile.read
            assets <- assetHandlers
            server <- HttpServer.init(0, "localhost")((pageHandler +: modelHandler(model) +: assets)*)
            _ <- Console.printLine(
                s"GltfInspector running on http://localhost:${server.port}/  " +
                    "(drag to orbit the loaded model, scroll to zoom, or toggle auto-rotate)"
            )
            _ <- server.await
        yield ()
    }

    /** The served page: an import map so the bundle's bare `three` and GLTFLoader imports resolve to the
      * modules this app serves, a `<div>` host for the kyo-ui tree, and a module script importing this
      * demo's entry and calling it with the model route this same app serves.
      */
    private def pageHandler(using Frame): HttpHandler[Any, "body" ~ String, Nothing] =
        handler("", "text/html; charset=utf-8", page)

    private def page: String =
        s"""<!doctype html>
           |<html>
           |<head>
           |<meta charset="utf-8">
           |<title>kyo-threejs GltfInspector</title>
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
           |    const { mountGltfInspector } = await import("$bundlePath");
           |    mountGltfInspector("#app", "$modelPath");
           |} catch (e) {
           |    document.body.innerHTML = "<pre style='color:#f88'>mount failed: " + String(e && e.message ? e.message : e) + "</pre>";
           |}
           |</script>
           |</body>
           |</html>""".stripMargin

    /** The model route: the glTF this app read off disk, served with the content type the loader expects. */
    private def modelHandler(model: String)(using Frame): HttpHandler[Any, "body" ~ String, Nothing] =
        handler(modelPath, "model/gltf+json", model)

    // ---- The assets this app serves --------------------------------------------------

    /** The route the page imports the demos bundle from. */
    private val bundlePath: String = "/gltfinspector.js"

    /** The bare specifiers the bundle imports, mapped to the routes below. This demo genuinely uses the
      * GLTFLoader and OrbitControls; the rest are here because the bundle is ONE ES module graph carrying
      * every demo, so every specifier in it must resolve for any page to load it.
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

end GltfInspector
