package kyo

/** The in-browser visual-review harness: renders each of the six compiled kyo-three demos through a
  * real Chrome software-WebGL context and proves each produces a non-blank frame.
  *
  * Unlike the hand-written-JS fixtures in [[ThreeMountBrowserTest]] / [[ThreeToImageBrowserTest]],
  * this suite loads the ACTUAL compiled demo bundle: the `kyo-three-demos` ESModule (`main.js`),
  * which links kyo-three MAIN plus the six committed demos plus the `mountDemo` entry. The page
  * imports `mountDemo` and mounts each demo's own scene-graph builder, so the kyo reconciler, frame
  * loop, raycast wiring, signal-driven mutations, glTF loader, and `Three.toImage` capture all run
  * for real. Each demo's canvas is read back for distinct colors (non-blank) and captured to a PNG
  * under `kyo-three/js/target/visual/` for review.
  *
  * The five live demos run `Three.runMount` (Raf or Clock frame loop); `thumbnail-gallery` runs the
  * headless `Three.toImage` path and paints the PNG onto the canvas. `gltf-viewer` loads a served
  * embedded-geometry glTF through the compiled `loadGltf`. Every assertion observes a real pixel
  * buffer; nothing is faked. On platforms with no downloadable Chrome the suite cancels (skips).
  *
  * One additional leaf captures the `EmbeddedScene` demo via `mountEmbedInteractive`, which mounts
  * the full kyo-ui tree (controls + embedded 3D canvas + HUD) so the visual review shows the 3D
  * scene coexisting with the surrounding kyo-ui controls.
  */
class DemoVisualReviewTest extends WebGLSceneHarness:

    import DemoVisualReviewTest.*

    for demo <- demos do
        s"${demo.name} renders a non-blank frame" in {
            renderDemo(demo) { distinct =>
                assert(distinct >= 2, s"${demo.name}: expected >= 2 distinct colors, got $distinct")
            }
        }
    end for

    "embedded-scene renders the full kyo-ui tree with a non-blank 3D frame" in {
        renderEmbeddedScene { distinct =>
            assert(distinct >= 2, s"embedded-scene: expected >= 2 distinct colors, got $distinct")
        }
    }

    /** Serves the demo bundle via `mountEmbedInteractive`, launches a software-WebGL Chrome, mounts
      * the full `EmbeddedScene.ui` tree (controls + embedded 3D canvas + HUD), waits for the
      * embedded canvas to produce a non-blank frame, screenshots the whole page body, asserts on the
      * distinct pixel count from the embedded canvas, and writes the PNG to the visual-review output
      * directory.
      */
    private def renderEmbeddedScene[A](assertion: Int => A)(using
        Frame
    ): A < (Async & Scope & Abort[BrowserException]) =
        cancelOnUnsupportedPlatform {
            servedEmbeddedScene { port =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _        <- Browser.goto(s"http://localhost:$port/")
                            _        <- awaitDemoError
                            _        <- Browser.waitFor("window.__demoReady === true").handle(diagnoseOnTimeout("embedded-scene"))
                            distinct <- Browser.evalInt("window.__demoDistinct")
                            shot     <- Browser.screenshotRegion(0, 0, pageWidth, pageHeight)
                            _        <- writeVisualPng("embedded-scene", shot)
                        yield assertion(distinct)
                    }
                }
            }
        }

    /** Reads the demo bundle, three.js build, jsm modules, and the embedded-scene page, and serves
      * them over HTTP, passing the bound port to the run body.
      */
    private def servedEmbeddedScene[A](
        f: Int => A < (Async & Scope & Abort[BrowserException])
    )(using Frame): A < (Async & Scope & Abort[BrowserException]) =
        for
            bundle    <- WebGLSceneHarness.readDemoBundle
            module    <- WebGLSceneHarness.readThreeSource("three.module.js")
            core      <- WebGLSceneHarness.readThreeSource("three.core.js")
            gltf      <- WebGLSceneHarness.readThreeJsm("loaders/GLTFLoader.js")
            bufUtils  <- WebGLSceneHarness.readThreeJsm("utils/BufferGeometryUtils.js")
            skelUtils <- WebGLSceneHarness.readThreeJsm("utils/SkeletonUtils.js")
            server <- HttpServer.init(0, "localhost")(
                WebGLSceneHarness.htmlHandler(embeddedScenePage),
                WebGLSceneHarness.jsHandler("main.js", bundle),
                WebGLSceneHarness.jsHandler("three.module.js", module),
                WebGLSceneHarness.jsHandler("three.core.js", core),
                WebGLSceneHarness.jsHandler("three/examples/jsm/loaders/GLTFLoader.js", gltf),
                WebGLSceneHarness.jsHandler("three/examples/jsm/utils/BufferGeometryUtils.js", bufUtils),
                WebGLSceneHarness.jsHandler("three/examples/jsm/utils/SkeletonUtils.js", skelUtils)
            )
            result <- f(server.port)
        yield result

    /** Serves the demo bundle, three.js, the jsm GLTFLoader stack, and the glTF fixture, launches a
      * software-WebGL Chrome, mounts the demo, waits for its render flag, asserts on the distinct
      * pixel count, and writes the screenshot PNG to the visual-review output directory.
      */
    private def renderDemo[A](demo: Demo)(assertion: Int => A)(using
        Frame
    ): A < (Async & Scope & Abort[BrowserException]) =
        cancelOnUnsupportedPlatform {
            servedDemo { port =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _        <- Browser.goto(s"http://localhost:$port/?demo=${demo.name}")
                            _        <- awaitDemoError
                            _        <- Browser.waitFor("window.__demoReady === true").handle(diagnoseOnTimeout(demo.name))
                            distinct <- Browser.evalInt("window.__demoDistinct")
                            shot     <- Browser.screenshotRegion(0, 0, canvasSize, canvasSize)
                            _        <- writeVisualPng(demo.name, shot)
                        yield assertion(distinct)
                    }
                }
            }
        }

    /** Reads the demo bundle, three.js build, jsm modules, and glTF fixture, and serves them with the
      * page over HTTP, passing the bound port to the run body.
      */
    private def servedDemo[A](
        f: Int => A < (Async & Scope & Abort[BrowserException])
    )(using Frame): A < (Async & Scope & Abort[BrowserException]) =
        for
            bundle    <- WebGLSceneHarness.readDemoBundle
            module    <- WebGLSceneHarness.readThreeSource("three.module.js")
            core      <- WebGLSceneHarness.readThreeSource("three.core.js")
            gltf      <- WebGLSceneHarness.readThreeJsm("loaders/GLTFLoader.js")
            bufUtils  <- WebGLSceneHarness.readThreeJsm("utils/BufferGeometryUtils.js")
            skelUtils <- WebGLSceneHarness.readThreeJsm("utils/SkeletonUtils.js")
            server <- HttpServer.init(0, "localhost")(
                WebGLSceneHarness.htmlHandler(page),
                WebGLSceneHarness.jsHandler("main.js", bundle),
                WebGLSceneHarness.jsHandler("three.module.js", module),
                WebGLSceneHarness.jsHandler("three.core.js", core),
                WebGLSceneHarness.jsHandler("three/examples/jsm/loaders/GLTFLoader.js", gltf),
                WebGLSceneHarness.jsHandler("three/examples/jsm/utils/BufferGeometryUtils.js", bufUtils),
                WebGLSceneHarness.jsHandler("three/examples/jsm/utils/SkeletonUtils.js", skelUtils),
                WebGLSceneHarness.staticHandler("models/helmet.glb", "model/gltf+json", modelGltf)
            )
            result <- f(server.port)
        yield result

    /** On a render-flag timeout, attaches the page's diagnostic state to the failure so the cause is
      * visible (page error, whether a frame ever fired, the distinct-color count seen).
      */
    private def diagnoseOnTimeout(name: String)(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for
                err      <- Browser.eval("String(window.__demoError)")
                frame    <- Browser.eval("String(window.__demoFrame === true)")
                distinct <- Browser.eval("String(window.__demoDistinct)")
            yield Abort.fail(BrowserScriptErrorException(
                s"$name render flag never set: demoError='$err' demoFrame=$frame demoDistinct=$distinct"
            ))
        }(wait)

    /** Surfaces a page-side mount error (a thrown exception in the entry or loader) as a test failure
      * rather than waiting out the render flag.
      */
    private def awaitDemoError(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.eval("window.__demoError || ''").map { err =>
            if err.nonEmpty then Abort.fail(BrowserScriptErrorException(err))
            else ()
        }

    /** Writes a captured screenshot PNG to the visual-review output directory. */
    private def writeVisualPng(name: String, shot: kyo.internal.Image)(using
        Frame
    ): Unit < (Async & Abort[BrowserException]) =
        Abort.recover[Throwable](e => Abort.fail(BrowserScriptErrorException(s"png write failed: $e"))) {
            shot.writeFileBinary(s"kyo-three/js/target/visual/$name.png").unit
        }

end DemoVisualReviewTest

object DemoVisualReviewTest:

    /** A demo name plus whether it needs the kyo-ui HUD mount point. */
    final private[kyo] case class Demo(name: String)

    private[kyo] val demos: Seq[Demo] = Seq(
        Demo("solar-system"),
        Demo("bouncing-balls"),
        Demo("reactive-cube-field"),
        Demo("snake-3d"),
        Demo("gltf-viewer"),
        Demo("thumbnail-gallery")
    )

    private[kyo] val canvasSize = 512

    /** The served embedded-geometry glTF the `gltf-viewer` demo loads: one PBR-material triangle with
      * its position/normal/index buffers in a base64 data URI, so the compiled `loadGltf` exercises a
      * real GLTFLoader fetch + parse and produces visible geometry.
      */
    private[kyo] val modelGltf: String =
        """{"asset":{"version":"2.0","generator":"kyo-three-harness"},"scene":0,""" +
            """"scenes":[{"name":"Scene","nodes":[0]}],"nodes":[{"name":"Triangle","mesh":0}],""" +
            """"meshes":[{"name":"TriMesh","primitives":[{"attributes":{"POSITION":0,"NORMAL":1},""" +
            """"indices":2,"material":0}]}],"materials":[{"name":"Mat","pbrMetallicRoughness":""" +
            """{"baseColorFactor":[0.2,0.8,1,1],"metallicFactor":0,"roughnessFactor":0.6}}],""" +
            """"buffers":[{"byteLength":78,"uri":"data:application/octet-stream;base64,""" +
            """MzMzv5qZGb8AAAAAMzMzP5qZGb8AAAAAAAAAAM3MTD8AAAAAAAAAAAAAAAAAAIA/AAAAAAAAAAAAAIA/""" +
            """AAAAAAAAAAAAAIA/AAABAAIA"}],"bufferViews":[{"buffer":0,"byteOffset":0,""" +
            """"byteLength":36,"target":34962},{"buffer":0,"byteOffset":36,"byteLength":36,""" +
            """"target":34962},{"buffer":0,"byteOffset":72,"byteLength":6,"target":34963}],""" +
            """"accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3",""" +
            """"min":[-0.7,-0.6,0],"max":[0.7,0.8,0]},{"bufferView":1,"componentType":5126,""" +
            """"count":3,"type":"VEC3"},{"bufferView":2,"componentType":5123,"count":3,""" +
            """"type":"SCALAR"}]}"""

    /** The harness page: an import map for `three` and the GLTFLoader subpath, a render canvas plus a
      * HUD mount point, the `mountDemo` ES-module call selected by the `?demo=` query parameter, and a
      * `requestAnimationFrame` readback that copies the rendered canvas into a 2D scratch canvas and
      * records the distinct-color count once the demo raises its render flag.
      */
    private[kyo] val page: String =
        s"""<!doctype html>
           |<html>
           |<head><meta charset="utf-8"><title>kyo-three visual review</title>
           |<script type="importmap">
           |{ "imports": {
           |    "three": "/three.module.js",
           |    "three/examples/jsm/loaders/GLTFLoader.js": "/three/examples/jsm/loaders/GLTFLoader.js"
           |} }
           |</script>
           |<style>body { margin: 0; background: #000; } #hud { position: fixed; top: 8px; left: 8px; color: #fff; }</style>
           |</head>
           |<body>
           |<canvas id="app" width="$canvasSize" height="$canvasSize"></canvas>
           |<div id="hud"></div>
           |<script type="module">
           |window.__demoError = "";
           |window.__demoDistinct = 0;
           |window.__demoReady = false;
           |function distinctOf(canvas) {
           |    const scratch = document.createElement("canvas");
           |    scratch.width = canvas.width; scratch.height = canvas.height;
           |    const sctx = scratch.getContext("2d");
           |    sctx.drawImage(canvas, 0, 0);
           |    const data = sctx.getImageData(0, 0, scratch.width, scratch.height).data;
           |    const seen = new Set();
           |    for (let i = 0; i < data.length; i += 4) {
           |        seen.add((data[i] << 24) | (data[i+1] << 16) | (data[i+2] << 8) | data[i+3]);
           |        if (seen.size > 64) break;
           |    }
           |    return seen.size;
           |}
           |try {
           |    const name = new URLSearchParams(window.location.search).get("demo");
           |    const { mountDemo } = await import("/main.js");
           |    mountDemo(name, "#app");
           |    const appCanvas = document.getElementById("app");
           |    const settle = () => {
           |        if (window.__demoFrame === true) {
           |            const d = distinctOf(appCanvas);
           |            if (d >= 2) {
           |                window.__demoDistinct = d;
           |                window.__demoReady = true;
           |                return;
           |            }
           |        }
           |        requestAnimationFrame(settle);
           |    };
           |    requestAnimationFrame(settle);
           |} catch (e) {
           |    window.__demoError = String(e && e.message ? e.message : e);
           |}
           |</script>
           |</body>
           |</html>""".stripMargin

    /** Viewport width used when screenshotting the full embedded-scene page. Wide enough to show
      * the kyo-ui controls column alongside the embedded 3D canvas.
      */
    private[kyo] val pageWidth = 800

    /** Viewport height used when screenshotting the full embedded-scene page. */
    private[kyo] val pageHeight = 600

    /** The embedded-scene visual-review page: mounts the full `EmbeddedScene.ui` tree (controls +
      * embedded 3D canvas + HUD) via `mountEmbedInteractive`, then polls the `#stage` canvas for a
      * non-blank frame and records `__demoDistinct` / `__demoReady` in the same shape as the
      * standard demo page so `renderEmbeddedScene` can share the same ready-flag and error-flag
      * checks as `renderDemo`.
      *
      * Distinct-pixel counting reads via `gl.readPixels` on the WebGL context that Three.js already
      * acquired for the `#stage` canvas. This bypasses the `preserveDrawingBuffer: false` constraint
      * (which would make a 2D `drawImage` readback unreliable): `getContext` on a canvas that
      * already has a WebGL context returns the same live context, and `readPixels` reads the
      * framebuffer state directly, so the readback is valid inside the same `requestAnimationFrame`
      * tick in which the Three.js RAF loop submitted the frame.
      */
    private[kyo] val embeddedScenePage: String =
        s"""<!doctype html>
           |<html>
           |<head><meta charset="utf-8"><title>kyo-three embedded-scene visual review</title>
           |<script type="importmap">
           |{ "imports": {
           |    "three": "/three.module.js",
           |    "three/examples/jsm/loaders/GLTFLoader.js": "/three/examples/jsm/loaders/GLTFLoader.js"
           |} }
           |</script>
           |<style>
           |body { margin: 0; background: #111; color: #eee; font-family: sans-serif; }
           |</style>
           |</head>
           |<body>
           |<script type="module">
           |window.__demoError = "";
           |window.__demoDistinct = 0;
           |window.__demoReady = false;
           |function distinctOfGl(canvas) {
           |    const gl = canvas.getContext('webgl2') || canvas.getContext('webgl');
           |    if (!gl) return 0;
           |    const w = canvas.width, h = canvas.height;
           |    if (w === 0 || h === 0) return 0;
           |    const buf = new Uint8Array(w * h * 4);
           |    gl.readPixels(0, 0, w, h, gl.RGBA, gl.UNSIGNED_BYTE, buf);
           |    const seen = new Set();
           |    for (let i = 0; i < buf.length; i += 4) {
           |        seen.add((buf[i] << 24) | (buf[i+1] << 16) | (buf[i+2] << 8) | buf[i+3]);
           |        if (seen.size > 64) break;
           |    }
           |    return seen.size;
           |}
           |try {
           |    const { mountEmbedInteractive } = await import("/main.js");
           |    mountEmbedInteractive();
           |    // Wait for the kyo-ui fiber to mount the DOM tree and the #stage canvas to appear.
           |    const waitForStage = () => new Promise(resolve => {
           |        const poll = () => {
           |            const stage = document.querySelector('#stage');
           |            if (stage && stage.tagName === 'CANVAS') { resolve(stage); return; }
           |            requestAnimationFrame(poll);
           |        };
           |        requestAnimationFrame(poll);
           |    });
           |    const stageCanvas = await waitForStage();
           |    // Poll until the embedded WebGL canvas has rendered a non-blank frame. Uses
           |    // gl.readPixels on the already-acquired WebGL context to read the live framebuffer
           |    // directly; this is reliable inside the same RAF tick as the Three.js render submit.
           |    const settle = () => {
           |        const d = distinctOfGl(stageCanvas);
           |        if (d >= 2) {
           |            window.__demoDistinct = d;
           |            window.__demoReady = true;
           |            return;
           |        }
           |        requestAnimationFrame(settle);
           |    };
           |    requestAnimationFrame(settle);
           |} catch (e) {
           |    window.__demoError = String(e && e.message ? e.message : e);
           |}
           |</script>
           |</body>
           |</html>""".stripMargin

end DemoVisualReviewTest
