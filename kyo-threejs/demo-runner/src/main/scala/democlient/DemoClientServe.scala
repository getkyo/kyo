package democlient

import kyo.*
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Serves a pure-animation demo as a CLIENT MOUNT: a static page that runs the demo's scene IN THE
  * BROWSER through `Three.runMount`, so the per-frame `onFrame` loop animates locally (no server
  * round-trip drives the motion). This is the launch path for the five pure-animation demos: the live
  * closures mount on a real WebGL canvas and the scene owns and animates itself.
  *
  * The page links the linked `kyo-threejs-demos` ESModule bundle (`main.js`) and imports the demo's
  * `DemoMounts.mountX` entry by name, calling it against the page's `#app` canvas. `three` and the
  * GLTFLoader jsm modules resolve from an import map served alongside the page; everything is read off
  * disk at request time, so a launch serves whatever the last `kyo-threejs-demos/fastLinkJS` produced.
  *
  * This launcher is JS-only (it reads files via Node `fs` and runs an `HttpServer` on Node), so it
  * lives in the JS-only `kyo-threejs-demo-runner` project rather than the cross-platform demo tree.
  */
object DemoClientServe:

    /** The served route of the linked demos ESModule bundle the page imports its `mountX` entry from. */
    val bundlePath: String = "/main.js"

    /** The served route of the GltfViewer model fixture (a minimal self-contained lit cube). */
    val modelPath: String = "/models/cube.gltf"

    /** The import map a `UI.runHandlers` server-push launcher's page emits so the demos bundle's bare
      * `three` (and jsm) imports resolve to the served three modules. The same five mappings the
      * client-mount demo pages carry; OrbitControls is included so a scene that binds `Three.controls`
      * resolves it, and an unused entry is simply never fetched.
      */
    private[democlient] val threeImportMap: Seq[(String, String)] = Seq(
        "three"                                           -> "/three.module.js",
        "three/examples/jsm/loaders/GLTFLoader.js"        -> "/three/examples/jsm/loaders/GLTFLoader.js",
        "three/examples/jsm/utils/BufferGeometryUtils.js" -> "/three/examples/jsm/utils/BufferGeometryUtils.js",
        "three/examples/jsm/utils/SkeletonUtils.js"       -> "/three/examples/jsm/utils/SkeletonUtils.js",
        "three/examples/jsm/controls/OrbitControls.js"    -> "/three/examples/jsm/controls/OrbitControls.js"
    )

    /** Renders [[threeImportMap]] as the JSON body of a page's `<script type="importmap">`, so every
      * served page draws its specifier map from the one source and cannot drift from the file handlers.
      */
    private def importMapJson: String =
        threeImportMap.map((k, v) => s"    \"$k\": \"$v\"").mkString(",\n")

    /** Reads and serves the demos bundle (at [[bundlePath]]), the three.js build, and the GLTFLoader jsm
      * stack a server-push launcher's page links and resolves through [[threeImportMap]]. The two
      * server-push launchers ([[democlient.FeedClock]], [[democlient.Flagship]]) compose these with the
      * `UI.runHandlers` handlers and the mount shim they link through `head.moduleScript`.
      */
    private[democlient] def demoAssetHandlers(using
        Frame
    ): Seq[HttpHandler[Any, "body" ~ String, Nothing]] < (Sync & Abort[FileException]) =
        for
            bundle    <- readFile(bundleFile)
            module    <- readFile(threeBuild("three.module.js"))
            core      <- readFile(threeBuild("three.core.js"))
            gltf      <- readFile(threeJsm("loaders/GLTFLoader.js"))
            bufUtils  <- readFile(threeJsm("utils/BufferGeometryUtils.js"))
            skelUtils <- readFile(threeJsm("utils/SkeletonUtils.js"))
            orbit     <- readFile(threeJsm("controls/OrbitControls.js"))
        yield Seq(
            jsHandler(bundlePath, bundle),
            jsHandler("/three.module.js", module),
            jsHandler("/three.core.js", core),
            jsHandler("/three/examples/jsm/loaders/GLTFLoader.js", gltf),
            jsHandler("/three/examples/jsm/utils/BufferGeometryUtils.js", bufUtils),
            jsHandler("/three/examples/jsm/utils/SkeletonUtils.js", skelUtils),
            jsHandler("/three/examples/jsm/controls/OrbitControls.js", orbit)
        )
    end demoAssetHandlers

    /** Serves a demo client-mount page plus the bundle, `three`, the GLTFLoader jsm stack, and the
      * model fixture on `port` (0 = an ephemeral port), prints the open URL with a one-line hint of the
      * motion to expect, and awaits forever. `entry` is the bundle export to import (e.g.
      * `mountBouncingBalls`); `call` is the JS call that invokes it (e.g. `mountBouncingBalls("#app")`).
      */
    def serve(name: String, motion: String, entry: String, call: String, port: Int)(using
        Frame
    ): Unit < (Async & Scope & Abort[FileException]) =
        for
            handlers <- demoAssetHandlers
            server <- HttpServer.init(port, "localhost")(
                (htmlHandler("", page(entry, call)) +: handlers :+ staticHandler(modelPath, "model/gltf+json", cubeGltf))*
            )
            _ <- Console.printLine(s"$name running on http://localhost:${server.port}/  ($motion)")
            _ <- server.await
        yield ()

    /** Serves an EMBED client-mount demo: the same bundle/three/jsm stack as [[serve]], but the page host
      * is a `<div id="app">` rather than a `<canvas>`, because the entry mounts a whole kyo-ui tree (a
      * button, the embedded 3D canvas via `Three.embed`, and a HUD label) into that div through
      * `UI.runMount`. The embedded 3D canvas is created by kyo-ui inside the tree, so the page itself need
      * not provide one. Used by the `Embedded` launcher to serve [[demo.EmbeddedSceneScene]] under Y, where
      * the earth's `onFrame` orbit and the sun/earth `onClick` selection both run client-side.
      */
    def serveEmbedded(name: String, motion: String, entry: String, call: String, port: Int)(using
        Frame
    ): Unit < (Async & Scope & Abort[FileException]) =
        for
            handlers <- demoAssetHandlers
            server <- HttpServer.init(port, "localhost")(
                (htmlHandler("", embedPage(entry, call)) +: handlers)*
            )
            _ <- Console.printLine(s"$name running on http://localhost:${server.port}/  ($motion)")
            _ <- server.await
        yield ()

    /** A client-mount page: an import map for `three` and the GLTFLoader jsm, a sized `#app` canvas, and
      * a module script that imports the named `mountX` entry from the bundle and calls it. The mount
      * runs on a detached fiber whose Scope stays open for the page lifetime, so the frame loop animates
      * until the page unloads.
      */
    private def page(entry: String, call: String): String =
        s"""<!doctype html>
           |<html>
           |<head><meta charset="utf-8"><title>kyo-threejs demo</title>
           |<script type="importmap">
           |{ "imports": {
           |$importMapJson
           |} }
           |</script>
           |<style>html,body{margin:0;background:#101018}#app{display:block;margin:0 auto}</style>
           |</head>
           |<body>
           |<canvas id="app" width="960" height="600"></canvas>
           |<script type="module">
           |try {
           |    const { $entry } = await import("$bundlePath");
           |    $call;
           |} catch (e) {
           |    document.body.innerHTML = "<pre style='color:#f88'>mount failed: " + String(e && e.message ? e.message : e) + "</pre>";
           |}
           |</script>
           |</body>
           |</html>""".stripMargin

    /** The EMBED page variant: a `<div id="app">` host (not a `<canvas>`) the entry's `UI.runMount`
      * mounts the whole kyo-ui tree into. The embedded 3D `<canvas>` is created by kyo-ui inside the tree
      * at the `Three.embed` host, so the page supplies only the div container. Its import map is the
      * same [[threeImportMap]] as [[page]], rendered by [[importMapJson]]; only the host element differs.
      */
    private def embedPage(entry: String, call: String): String =
        s"""<!doctype html>
           |<html>
           |<head><meta charset="utf-8"><title>kyo-threejs embed demo</title>
           |<script type="importmap">
           |{ "imports": {
           |$importMapJson
           |} }
           |</script>
           |<style>html,body{margin:0;background:#101018;color:#cdd}#app{margin:0 auto;max-width:960px;padding:12px}button{font-size:16px;padding:6px 14px}p{font-size:18px}</style>
           |</head>
           |<body>
           |<div id="app"></div>
           |<script type="module">
           |try {
           |    const { $entry } = await import("$bundlePath");
           |    $call;
           |} catch (e) {
           |    document.body.innerHTML = "<pre style='color:#f88'>mount failed: " + String(e && e.message ? e.message : e) + "</pre>";
           |}
           |</script>
           |</body>
           |</html>""".stripMargin

    private def staticHandler(path: String, contentType: String, source: String)(using
        Frame
    ): HttpHandler[Any, "body" ~ String, Nothing] =
        HttpRoute.getRaw(path).response(_.bodyText).handler { _ =>
            HttpResponse.ok(source).setHeader("Content-Type", contentType)
        }

    private[democlient] def jsHandler(path: String, source: String)(using Frame): HttpHandler[Any, "body" ~ String, Nothing] =
        staticHandler(path, "text/javascript; charset=utf-8", source)

    private def htmlHandler(path: String, html: String)(using Frame): HttpHandler[Any, "body" ~ String, Nothing] =
        staticHandler(path, "text/html; charset=utf-8", html)

    /** Reads a UTF-8 source file off disk, surfacing a missing file as a typed `FileNotFoundException`
      * (a `FileException`) so the caller can report the absent artifact rather than crash.
      */
    private def readFile(path: String)(using Frame): String < (Sync & Abort[FileException]) =
        Sync.defer(NodeFs.existsSync(path)).map {
            case true  => Sync.defer(NodeFs.readFileSync(path, "utf8"))
            case false => Abort.fail(FileNotFoundException(Path(path)))
        }

    /** The absolute path of the linked demos ESModule bundle `main.js`. */
    private lazy val bundleFile: String =
        val demosTarget = NodePath.join(NodeProcess.cwd(), "kyo-threejs", "demos", "target")
        locate(demosTarget, d => NodePath.join(demosTarget, d, "kyo-threejs-demos-fastopt", "main.js"))
            .getOrElse(sys.error(
                s"demos bundle main.js not found under $demosTarget; run 'sbt kyo-threejs-demos/fastLinkJS' first"
            ))
    end bundleFile

    /** The `three/build` directory under a located `node_modules/three` install. */
    private lazy val threeBuildDir: String =
        // Prefer the install next to the demos bundle target; fall back to the kyo-threejs JS test target.
        val roots = Chunk(
            NodePath.join(NodeProcess.cwd(), "kyo-threejs", "demos", "target"),
            NodePath.join(NodeProcess.cwd(), "kyo-threejs", "js", "target")
        )
        roots.iterator.flatMap { root =>
            locate(
                root,
                d => NodePath.join(root, d, "node_modules", "three", "build")
            ).iterator
        }.nextOption().getOrElse(sys.error(
            "three install not found under kyo-threejs/{demos,js}/target; run the demos installThree first"
        ))
    end threeBuildDir

    private def threeBuild(file: String): String = NodePath.join(threeBuildDir, file)

    private def threeJsm(relative: String): String =
        NodePath.join(threeBuildDir, "..", "examples", "jsm", relative)

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

    /** A minimal self-contained glTF 2.0 cube (1 lit mesh, 24 vertices, 36 indices) with an embedded
      * base64 buffer, served as the GltfViewer model fixture. The browser `GLTFLoader.load` fetches and
      * parses it (resolving the embedded data-URI buffer), so the loaded model is a real geometry the
      * rotating `Group` spins.
      */
    private val cubeGltf: String =
        """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"name":"Scene","nodes":[0]}],"nodes":[{"name":"Cube","mesh":0}],"meshes":[{"name":"Cube","primitives":[{"attributes":{"POSITION":1,"NORMAL":2},"indices":0,"material":0}]}],"materials":[{"name":"CubeMat","pbrMetallicRoughness":{"baseColorFactor":[0.85,0.45,0.2,1.0],"metallicFactor":0.1,"roughnessFactor":0.6}}],"buffers":[{"byteLength":648,"uri":"data:application/octet-stream;base64,AAABAAIAAAACAAMABAAFAAYABAAGAAcACAAJAAoACAAKAAsADAANAA4ADAAOAA8AEAARABIAEAASABMAFAAVABYAFAAWABcAAAAAvwAAAL8AAAA/AAAAPwAAAL8AAAA/AAAAPwAAAD8AAAA/AAAAvwAAAD8AAAA/AAAAPwAAAL8AAAC/AAAAvwAAAL8AAAC/AAAAvwAAAD8AAAC/AAAAPwAAAD8AAAC/AAAAPwAAAL8AAAA/AAAAPwAAAL8AAAC/AAAAPwAAAD8AAAC/AAAAPwAAAD8AAAA/AAAAvwAAAL8AAAC/AAAAvwAAAL8AAAA/AAAAvwAAAD8AAAA/AAAAvwAAAD8AAAC/AAAAvwAAAD8AAAA/AAAAPwAAAD8AAAA/AAAAPwAAAD8AAAC/AAAAvwAAAD8AAAC/AAAAvwAAAL8AAAC/AAAAPwAAAL8AAAC/AAAAPwAAAL8AAAA/AAAAvwAAAL8AAAA/AAAAAAAAAAAAAIA/AAAAAAAAAAAAAIA/AAAAAAAAAAAAAIA/AAAAAAAAAAAAAIA/AAAAAAAAAAAAAIC/AAAAAAAAAAAAAIC/AAAAAAAAAAAAAIC/AAAAAAAAAAAAAIC/AACAPwAAAAAAAAAAAACAPwAAAAAAAAAAAACAPwAAAAAAAAAAAACAPwAAAAAAAAAAAACAvwAAAAAAAAAAAACAvwAAAAAAAAAAAACAvwAAAAAAAAAAAACAvwAAAAAAAAAAAAAAAAAAgD8AAAAAAAAAAAAAgD8AAAAAAAAAAAAAgD8AAAAAAAAAAAAAgD8AAAAAAAAAAAAAgL8AAAAAAAAAAAAAgL8AAAAAAAAAAAAAgL8AAAAAAAAAAAAAgL8AAAAA"}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":72,"target":34963},{"buffer":0,"byteOffset":72,"byteLength":288,"target":34962},{"buffer":0,"byteOffset":360,"byteLength":288,"target":34962}],"accessors":[{"bufferView":0,"componentType":5123,"count":36,"type":"SCALAR"},{"bufferView":1,"componentType":5126,"count":24,"type":"VEC3","min":[-0.5,-0.5,-0.5],"max":[0.5,0.5,0.5]},{"bufferView":2,"componentType":5126,"count":24,"type":"VEC3"}]}"""

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

end DemoClientServe
