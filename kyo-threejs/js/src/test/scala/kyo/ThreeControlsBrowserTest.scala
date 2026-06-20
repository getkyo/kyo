package kyo

import kyo.Browser.ScreenshotFrame

/** Browser proof of the Option-Y orbit `Three.controls` (design 02-design-r2 DY-06, FORK-Y-A): a STATIC
  * object plus a `Three.controls(autoRotate = true)` node, so the island binds a live three.js
  * `OrbitControls` and the CAMERA orbits the scene automatically. The rendered view changes frame to frame
  * even though nothing in the scene carries an `onFrame`, observed from real Chrome pixels: only the
  * camera moving can change the view of a static object.
  *
  *   1. CLIENT scene: a static cluster of differently-placed, differently-colored cubes plus
  *      `Three.controls(autoRotate = true)`. The controls-island bundle mounts it via `Three.runMount`,
  *      which binds `new OrbitControls(camera, canvas)` under the mount Scope and calls `controls.update()`
  *      each RAF frame, orbiting the camera.
  *   2. PROOF: the screencast frames change consecutively (the camera orbits the static object). Because no
  *      node has an `onFrame`, the change can only be the camera motion the controls drive.
  *
  * Frames are saved under `runs/visual-review/y-controls/`. Runs in a real software-WebGL Chrome via CDP;
  * cancels (skips) where no Chrome can be downloaded.
  */
class ThreeControlsBrowserTest extends WebGLSceneHarness:

    import ThreeControlsBrowserTest.*

    override def timeout = 180.seconds

    "Three.controls(autoRotate): the camera orbits a static object, changing the rendered view" in {
        cancelOnUnsupportedPlatform {
            servedControls { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _ <- Browser.goto(url)
                            _ <- Browser.waitFor(
                                "(function(){var c=document.getElementById('app');return !!(c&&c.width>0&&c.getContext);})()"
                            )
                            _      <- Async.sleep(1000.millis)
                            frames <- recordFrames
                            _      <- saveFrames(frames)
                        yield
                            assert(frames.size >= 4, s"expected at least 4 recorded frames, got ${frames.size}")
                            // ---- ORBIT: consecutive frames change (the camera orbits the static object) ----
                            val changedPairs = countChangedPairs(frames)
                            assert(
                                changedPairs >= 3,
                                s"the camera did not orbit: only $changedPairs of ${frames.size - 1} consecutive frame " +
                                    s"pairs changed. No node has an onFrame, so a static view means the OrbitControls did not " +
                                    s"drive the camera. Frames saved under runs/visual-review/y-controls/"
                            )
                    }
                }
            }
        }
    }

    private def recordFrames(using Frame): Chunk[ScreenshotFrame] < (Browser & Async & Abort[BrowserReadException]) =
        Browser.screenshotFrames(maxDurationMs = 12000L, maxFrames = 2000) {
            Abort.run[BrowserReadException](Browser.waitFor("window.__never === true", Present(recordWindow))).unit
        }.map { case (frames, _) => frames }

    private def countChangedPairs(frames: Chunk[ScreenshotFrame]): Int =
        frames.toSeq.sliding(2).count {
            case Seq(a, b) => framesDiffer(a.image.binary, b.image.binary)
            case _         => false
        }

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

    private def saveFrames(frames: Chunk[ScreenshotFrame])(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        val dir = "runs/visual-review/y-controls"
        Sync.defer(mkdirp(dir)).andThen {
            Kyo.foreachIndexed(frames) { (i, frame) =>
                val idx  = f"$i%03d"
                val path = s"$dir/frame-$idx.jpg"
                Abort.run[FileWriteException](frame.image.writeFileBinary(path)).unit
            }.unit
        }
    end saveFrames

    /** Serves the static controls page plus the self-contained controls island bundle (three and
      * OrbitControls inlined), then hands the page URL to `f`. No WebSocket is needed: orbit controls are
      * pure client-side.
      */
    private def servedControls[A](
        f: String => A < (Async & Scope & Abort[BrowserException])
    )(using Frame): A < (Async & Scope & Abort[BrowserException]) =
        for
            island <- Sync.defer(readIslandBundle)
            server <- HttpServer.init(0, "localhost")(
                WebGLSceneHarness.htmlHandler(controlsPage),
                WebGLSceneHarness.jsHandler(islandRoute, island)
            )
            result <- f(s"http://localhost:${server.port}/")
        yield result

end ThreeControlsBrowserTest

object ThreeControlsBrowserTest:

    /** The served route of the controls island bundle the page links as a module script. */
    private val islandRoute: String = "/_kyo/controls-island.js"

    /** The recorder window: ~6s, spanning a good arc of the camera orbit. */
    private val recordWindow: Schedule = Schedule.fixed(50.millis).take(120)

    /** Creates `dir` and any missing parents, mirroring `mkdir -p`. */
    private def mkdirp(dir: String): Unit =
        NodeFsMk.mkdirSync(dir, scala.scalajs.js.Dynamic.literal(recursive = true))
        ()
    end mkdirp

    /** Reads the bundled controls island ESM (`main.js`, three + OrbitControls inlined) from the
      * controls-island esbuild output tree.
      */
    private def readIslandBundle: String =
        val target = NodePathJ.join(NodeProcessJ.cwd(), "kyo-threejs", "controls-island", "target")
        val located = NodeFsMk.readdirSync(target).toSeq.collectFirst {
            case d
                if d.startsWith("scala-") &&
                    NodeFsMk.existsSync(NodePathJ.join(target, d, "esbuild", "main", "out", "main.js")) =>
                NodePathJ.join(target, d, "esbuild", "main", "out", "main.js")
        }
        NodeFsMk.readFileSync(
            located.getOrElse(sys.error(
                s"controls island bundle main.js not found under $target; run 'sbt controlsIslandBundle' first"
            )),
            "utf8"
        )
    end readIslandBundle

    /** The controls page: a `#app` canvas plus a `<script type="module">` linking the self-running controls
      * island bundle, which mounts the static scene and binds the orbiting OrbitControls on load.
      */
    private val controlsPage: String =
        s"""<!doctype html>
           |<html>
           |<head><meta charset="utf-8"><title>kyo-threejs Three.controls</title>
           |<style>html,body{margin:0;background:#101018}#app{display:block}</style>
           |</head>
           |<body>
           |<canvas id="app" width="640" height="480"></canvas>
           |<script type="module" src="$islandRoute"></script>
           |</body>
           |</html>""".stripMargin

    import scala.scalajs.js
    import scala.scalajs.js.annotation.JSImport

    @js.native
    @JSImport("node:fs", JSImport.Namespace)
    private object NodeFsMk extends js.Object:
        def mkdirSync(path: String, options: js.Object): Unit    = js.native
        def readFileSync(path: String, encoding: String): String = js.native
        def readdirSync(path: String): js.Array[String]          = js.native
        def existsSync(path: String): Boolean                    = js.native
    end NodeFsMk

    @js.native
    @JSImport("node:path", JSImport.Namespace)
    private object NodePathJ extends js.Object:
        def join(parts: String*): String = js.native
    end NodePathJ

    @js.native
    @JSImport("node:process", JSImport.Namespace)
    private object NodeProcessJ extends js.Object:
        def cwd(): String = js.native
    end NodeProcessJ

end ThreeControlsBrowserTest
