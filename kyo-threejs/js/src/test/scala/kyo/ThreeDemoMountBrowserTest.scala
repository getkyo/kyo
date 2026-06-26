package kyo

import kyo.Browser.ScreenshotFrame

/** Browser proof that the five pure-animation demos ANIMATE when run as CLIENT MOUNTS.
  *
  * Each demo's `DemoMounts.mountX` entry builds the demo's `XScene.scene` and runs it through the real
  * `Three.runMount` on a live software-WebGL canvas, so the per-frame `onFrame` loop advances locally
  * (no server round-trip drives the motion). This is the animating counterpart to the server-push
  * KyoApps, which serve a flattened closure-free scene that renders static.
  *
  * The proof is motion: a screencast records the canvas across ~2.5 seconds and the test asserts the
  * captured frames CHANGE over time. A static (unchanging) canvas fails. Every assertion observes real
  * frames Chrome rendered from a real GL context through the actual compiled `ThreeMount` frame loop;
  * nothing is faked. The captured frames are written under `runs/visual-review/<demo>/` for inspection.
  *
  * Runs in a real software-WebGL Chrome via CDP; cancels (skips) where no Chrome can be downloaded.
  */
class ThreeDemoMountBrowserTest extends WebGLSceneHarness:

    import ThreeDemoMountBrowserTest.*

    override def timeout = 120.seconds

    for demo <- demos do
        s"${demo.name} animates under Three.runMount (canvas pixels change over time)" in {
            cancelOnUnsupportedPlatform {
                servedDemo(demo) { url =>
                    swiftshaderLaunch.map { launch =>
                        Browser.run(launch) {
                            for
                                _      <- Browser.goto(url)
                                _      <- awaitMountError
                                _      <- Browser.waitFor("window.__mounted === true").handle(diagnoseMountTimeout)
                                frames <- recordFrames
                                _      <- saveFrames(demo.name, frames)
                            yield
                                assert(frames.size >= 3, s"expected at least 3 recorded frames for ${demo.name}, got ${frames.size}")
                                val changedPairs = countChangedPairs(frames)
                                assert(
                                    changedPairs >= 2,
                                    s"${demo.name} canvas did not animate: only $changedPairs of ${frames.size - 1} consecutive " +
                                        s"frame pairs changed (a static canvas yields ~identical frames). Frames saved under " +
                                        s"runs/visual-review/${demo.name}/"
                                )
                        }
                    }
                }
            }
        }
    end for

    /** Records a screencast of the page while a fixed window elapses; the frames are whatever Chrome
      * rendered while the demo's frame loop ran. The window ([[recordWindow]], ~2.5s) is the bound, never
      * a sleep that gates a result: `waitFor` parks on a flag the page never raises, so it runs the full
      * window and aborts at its timeout, which is swallowed so the recorder returns its frames cleanly.
      * The recorder's `maxDurationMs`/`maxFrames` are generous safety ceilings above what the ~2.5s
      * window can produce (an animating canvas at ~60-100fps yields a few hundred frames), so the window
      * ends the recording, not a cap.
      */
    private def recordFrames(using Frame): Chunk[ScreenshotFrame] < (Browser & Async & Abort[BrowserReadException]) =
        Browser.screenshotFrames(maxDurationMs = 10000L, maxFrames = 2000) {
            Abort.run[BrowserReadException](Browser.waitFor("window.__never === true", Present(recordWindow))).unit
        }.map { case (frames, _) => frames }

    /** Counts consecutive frame pairs whose encoded bytes differ beyond an encoding-jitter threshold. A
      * static canvas re-encodes to near-identical JPEGs frame to frame; an animating canvas changes
      * enough of the image that the encoded size and bytes shift materially.
      */
    private def countChangedPairs(frames: Chunk[ScreenshotFrame]): Int =
        frames.toSeq.sliding(2).count {
            case Seq(a, b) => framesDiffer(a.image.binary, b.image.binary)
            case _         => false
        }

    /** Two encoded frames differ when their byte payloads are not equal under a small tolerance: the
      * lengths differ by more than a few bytes, or, at equal length, a non-trivial fraction of bytes
      * differ. Re-encoding the SAME canvas yields byte-identical or near-identical JPEGs, so any real
      * pixel motion crosses this threshold.
      */
    private def framesDiffer(a: Span[Byte], b: Span[Byte]): Boolean =
        if math.abs(a.size - b.size) > 16 then true
        else
            val n     = math.min(a.size, b.size)
            var diffs = 0
            var i     = 0
            while i < n do
                if a(i) != b(i) then diffs += 1
                i += 1
            diffs > n / 100
    end framesDiffer

    /** Writes each recorded frame as a JPEG under `runs/visual-review/<demo>/frame-NNN.jpg` so the
      * captured motion can be inspected. The directory is created if absent.
      */
    private def saveFrames(name: String, frames: Chunk[ScreenshotFrame])(using
        Frame
    ): Unit < (Async & Abort[BrowserReadException]) =
        val dir = s"runs/visual-review/$name"
        Sync.defer(mkdirp(dir)).andThen {
            Kyo.foreachIndexed(frames) { (i, frame) =>
                val idx  = f"$i%03d"
                val path = s"$dir/frame-$idx.jpg"
                Abort.run[FileWriteException](frame.image.writeFileBinary(path)).unit
            }.unit
        }
    end saveFrames

    /** Surfaces a page-side mount error as a test failure rather than waiting out the mounted flag. */
    private def awaitMountError(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.eval("window.__mountError || ''").map { err =>
            if err.nonEmpty then Abort.fail(BrowserScriptErrorException(err))
            else ()
        }

    /** On a mounted-flag timeout, attaches the page's mount-error state so the cause is visible. */
    private def diagnoseMountTimeout(
        wait: => String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            Browser.eval("String(window.__mountError)").map { err =>
                Abort.fail(BrowserScriptErrorException(s"demo mount ready flag never set: mountError='$err'"))
            }
        }(wait)

    /** Reads the demo bundle, three.js build, and the GLTFLoader jsm stack the bundle imports, serves
      * them plus the demo's client-mount page (and, for GltfViewer, the cube model fixture) over HTTP,
      * and hands the page URL to `f`.
      */
    private def servedDemo[A](demo: Demo)(
        f: String => A < (Async & Scope & Abort[BrowserException])
    )(using Frame): A < (Async & Scope & Abort[BrowserException]) =
        for
            bundle    <- WebGLSceneHarness.readDemoBundle
            module    <- WebGLSceneHarness.readThreeSource("three.module.js")
            core      <- WebGLSceneHarness.readThreeSource("three.core.js")
            gltf      <- WebGLSceneHarness.readThreeJsm("loaders/GLTFLoader.js")
            bufUtils  <- WebGLSceneHarness.readThreeJsm("utils/BufferGeometryUtils.js")
            skelUtils <- WebGLSceneHarness.readThreeJsm("utils/SkeletonUtils.js")
            orbit     <- WebGLSceneHarness.readThreeJsm("controls/OrbitControls.js")
            server <- HttpServer.init(0, "localhost")(
                WebGLSceneHarness.htmlHandler(demo.page),
                WebGLSceneHarness.jsHandler("main.js", bundle),
                WebGLSceneHarness.jsHandler("three.module.js", module),
                WebGLSceneHarness.jsHandler("three.core.js", core),
                WebGLSceneHarness.jsHandler("three/examples/jsm/loaders/GLTFLoader.js", gltf),
                WebGLSceneHarness.jsHandler("three/examples/jsm/utils/BufferGeometryUtils.js", bufUtils),
                WebGLSceneHarness.jsHandler("three/examples/jsm/utils/SkeletonUtils.js", skelUtils),
                WebGLSceneHarness.jsHandler("three/examples/jsm/controls/OrbitControls.js", orbit),
                WebGLSceneHarness.staticHandler(modelPath, "model/gltf+json", cubeGltf)
            )
            result <- f(s"http://localhost:${server.port}/")
        yield result

end ThreeDemoMountBrowserTest

object ThreeDemoMountBrowserTest:

    /** The window the recorder body runs for; the page never raises `window.__never`, so the waitFor
      * runs the whole window and aborts at its end, recording frames for its full duration.
      */
    private val recordWindow: Schedule = Schedule.fixed(50.millis).take(50)

    /** The served route of the GltfViewer model fixture. */
    private val modelPath: String = "/models/cube.gltf"

    /** One pure-animation demo: its display name and the client-mount page that imports the bundle's
      * `mountX` entry and calls it against the `#app` canvas.
      */
    final private case class Demo(name: String, page: String)

    private val demos: Seq[Demo] = Seq(
        Demo("BouncingBalls", mountPage("mountBouncingBalls", """mountBouncingBalls("#app")""")),
        Demo("ReactiveCubeField", mountPage("mountReactiveCubeField", """mountReactiveCubeField("#app")""")),
        Demo("Snake3D", mountPage("mountSnake3D", """mountSnake3D("#app")""")),
        Demo("SolarSystem", mountPage("mountSolarSystem", """mountSolarSystem("#app")""")),
        Demo("GltfViewer", mountPage("mountGltfViewer", s"""mountGltfViewer("#app", "$modelPath")"""))
    )

    /** A client-mount page: an import map for `three` and the GLTFLoader jsm, a sized `#app` canvas,
      * and a module script that imports the named entry from the demo bundle and calls it with the
      * mount call. The page raises `window.__mounted` once the entry returns (the mount fiber is
      * detached, so this fires as soon as the entry is invoked) and records any import/call throw in
      * `window.__mountError`.
      */
    private def mountPage(entry: String, call: String): String =
        s"""<!doctype html>
           |<html>
           |<head><meta charset="utf-8"><title>kyo-threejs demo mount</title>
           |<script type="importmap">
           |{ "imports": {
           |    "three": "/three.module.js",
           |    "three/examples/jsm/loaders/GLTFLoader.js": "/three/examples/jsm/loaders/GLTFLoader.js",
           |    "three/examples/jsm/utils/BufferGeometryUtils.js": "/three/examples/jsm/utils/BufferGeometryUtils.js",
           |    "three/examples/jsm/utils/SkeletonUtils.js": "/three/examples/jsm/utils/SkeletonUtils.js",
           |    "three/examples/jsm/controls/OrbitControls.js": "/three/examples/jsm/controls/OrbitControls.js"
           |} }
           |</script>
           |<style>html,body{margin:0;background:#101018}#app{display:block}</style>
           |</head>
           |<body>
           |<canvas id="app" width="640" height="480"></canvas>
           |<script type="module">
           |window.__mounted = false;
           |window.__mountError = "";
           |try {
           |    const { $entry } = await import("/main.js");
           |    $call;
           |    window.__mounted = true;
           |} catch (e) {
           |    window.__mountError = String(e && e.message ? e.message : e);
           |}
           |</script>
           |</body>
           |</html>""".stripMargin

    /** A minimal self-contained glTF 2.0 cube (1 lit mesh, 24 vertices, 36 indices) with an embedded
      * base64 buffer, served as the GltfViewer model fixture. The browser `GLTFLoader.load` fetches and
      * parses it (resolving the embedded data-URI buffer), so the loaded model is a real geometry the
      * rotating `Group` spins. Generated offline and validated against the installed three GLTFLoader.
      */
    private val cubeGltf: String =
        """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"name":"Scene","nodes":[0]}],"nodes":[{"name":"Cube","mesh":0}],"meshes":[{"name":"Cube","primitives":[{"attributes":{"POSITION":1,"NORMAL":2},"indices":0,"material":0}]}],"materials":[{"name":"CubeMat","pbrMetallicRoughness":{"baseColorFactor":[0.85,0.45,0.2,1.0],"metallicFactor":0.1,"roughnessFactor":0.6}}],"buffers":[{"byteLength":648,"uri":"data:application/octet-stream;base64,AAABAAIAAAACAAMABAAFAAYABAAGAAcACAAJAAoACAAKAAsADAANAA4ADAAOAA8AEAARABIAEAASABMAFAAVABYAFAAWABcAAAAAvwAAAL8AAAA/AAAAPwAAAL8AAAA/AAAAPwAAAD8AAAA/AAAAvwAAAD8AAAA/AAAAPwAAAL8AAAC/AAAAvwAAAL8AAAC/AAAAvwAAAD8AAAC/AAAAPwAAAD8AAAC/AAAAPwAAAL8AAAA/AAAAPwAAAL8AAAC/AAAAPwAAAD8AAAC/AAAAPwAAAD8AAAA/AAAAvwAAAL8AAAC/AAAAvwAAAL8AAAA/AAAAvwAAAD8AAAA/AAAAvwAAAD8AAAC/AAAAvwAAAD8AAAA/AAAAPwAAAD8AAAA/AAAAPwAAAD8AAAC/AAAAvwAAAD8AAAC/AAAAvwAAAL8AAAC/AAAAPwAAAL8AAAC/AAAAPwAAAL8AAAA/AAAAvwAAAL8AAAA/AAAAAAAAAAAAAIA/AAAAAAAAAAAAAIA/AAAAAAAAAAAAAIA/AAAAAAAAAAAAAIA/AAAAAAAAAAAAAIC/AAAAAAAAAAAAAIC/AAAAAAAAAAAAAIC/AAAAAAAAAAAAAIC/AACAPwAAAAAAAAAAAACAPwAAAAAAAAAAAACAPwAAAAAAAAAAAACAPwAAAAAAAAAAAACAvwAAAAAAAAAAAACAvwAAAAAAAAAAAACAvwAAAAAAAAAAAACAvwAAAAAAAAAAAAAAAAAAgD8AAAAAAAAAAAAAgD8AAAAAAAAAAAAAgD8AAAAAAAAAAAAAgD8AAAAAAAAAAAAAgL8AAAAAAAAAAAAAgL8AAAAAAAAAAAAAgL8AAAAAAAAAAAAAgL8AAAAA"}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":72,"target":34963},{"buffer":0,"byteOffset":72,"byteLength":288,"target":34962},{"buffer":0,"byteOffset":360,"byteLength":288,"target":34962}],"accessors":[{"bufferView":0,"componentType":5123,"count":36,"type":"SCALAR"},{"bufferView":1,"componentType":5126,"count":24,"type":"VEC3","min":[-0.5,-0.5,-0.5],"max":[0.5,0.5,0.5]},{"bufferView":2,"componentType":5126,"count":24,"type":"VEC3"}]}"""

    /** Creates `dir` and any missing parents, mirroring `mkdir -p`. Used to land the saved frames. */
    private def mkdirp(dir: String): Unit =
        // Node fs bridge: ensure the visual-review output directory exists before writing frames.
        NodeFsMk.mkdirSync(dir, scala.scalajs.js.Dynamic.literal(recursive = true))
        ()
    end mkdirp

    import scala.scalajs.js
    import scala.scalajs.js.annotation.JSImport

    @js.native
    @JSImport("node:fs", JSImport.Namespace)
    private object NodeFsMk extends js.Object:
        def mkdirSync(path: String, options: js.Object): Unit = js.native
    end NodeFsMk

end ThreeDemoMountBrowserTest
