package kyo

/** Browser proof of the orbit `Three.controls`: a STATIC object plus a `Three.controls(autoRotate =
  * true)` node, so the mounted scene binds a live three.js `OrbitControls` and the CAMERA orbits the
  * scene automatically. The rendered view changes frame to frame even though nothing in the scene
  * carries an `onFrame`, observed through the public `Three.Mount` handle: only the camera moving can
  * change the view of a static object.
  *
  *   1. CLIENT scene: [[fixture.ControlsInspectScene]], a static cluster of differently-placed,
  *      differently-colored cubes plus `Three.controls(autoRotate = true)`. The page imports the fixtures
  *      bundle's `mountControlsInspect` entry, which mounts it via `Three.runMount` and installs
  *      `ThreeInspect` LIVE as `window.controls`, binding `new OrbitControls(camera, canvas)` under the
  *      mount Scope and calling `controls.update()` each RAF frame, orbiting the camera.
  *   2. PROOF: the mount succeeds (no Abort), and a sampled multi-point view signature (an RGBA read at
  *      several canvas coordinates, not just the exact center) changes across several commits. Because
  *      no node has an `onFrame`, the change can only be the camera motion the controls drive.
  *
  * Runs in a real software-WebGL Chrome via CDP; cancels (skips) where no Chrome can be downloaded.
  */
class ThreeControlsBrowserTest extends WebGLSceneHarness:

    override def timeout = 180.seconds

    "Three.controls(autoRotate): the mount succeeds and the camera orbits a static object, changing the sampled view" in {
        cancelOnUnsupportedPlatform {
            servedControls { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _ <- Browser.goto(url)
                            _ <- awaitProbeError
                            _ <- Browser.waitFor("window.controls !== undefined").handle(diagnoseOnTimeout)
                            _ <- Browser.waitFor("window.controls.renders() >= 1")
                            samples <- Kyo.foreachIndexed(Chunk.from(0 until 6)) { (i, _) =>
                                for
                                    // Each sample waits for MANY more render commits than the last, so the
                                    // camera's cumulative auto-rotation between consecutive samples is large
                                    // enough to move the off-pivot cubes' projected screen position, not just
                                    // a fraction of a degree.
                                    _      <- Browser.waitFor(s"window.controls.renders() >= ${(i + 1) * 20}")
                                    sample <- readSignature
                                yield sample
                            }
                        yield
                            val distinct = samples.toSeq.distinct
                            assert(
                                distinct.size >= 2,
                                s"the camera did not orbit: the sampled view signature never changed across ${samples.size} " +
                                    s"commits. No node has an onFrame, so an unchanging signature means the OrbitControls " +
                                    s"did not drive the camera."
                            )
                    }
                }
            }
        }
    }

    "Three.controls autoRotate is reactive: the camera holds still until the signal flips, then it orbits" in {
        cancelOnUnsupportedPlatform {
            servedControlsReactive { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _ <- Browser.goto(url)
                            _ <- awaitProbeError
                            _ <- Browser.waitFor("window.controls !== undefined").handle(diagnoseOnTimeout)
                            _ <- Browser.waitFor("window.controls.renders() >= 30")
                            // autoRotate starts OFF: across many commits a static camera keeps ONE signature.
                            before <- Kyo.foreachIndexed(Chunk.from(0 until 4)) { (i, _) =>
                                Browser.waitFor(s"window.controls.renders() >= ${30 + (i + 1) * 15}").andThen(readSignature)
                            }
                            // Flip the reactive autoRotate on through the SAME SignalRef the scene binds.
                            _    <- Browser.eval("(window.controls.signal('autoRotate').set('true'), 'ok')")
                            base <- Browser.eval("String(Math.floor(window.controls.renders()))")
                            after <- Kyo.foreachIndexed(Chunk.from(0 until 6)) { (i, _) =>
                                Browser.waitFor(s"window.controls.renders() >= ${base.toInt + (i + 1) * 20}").andThen(readSignature)
                            }
                        yield
                            assert(
                                before.toSeq.distinct.size == 1,
                                s"the camera moved before the toggle: autoRotate was not off. Signatures: ${before.toSeq.distinct.size} distinct."
                            )
                            assert(
                                after.toSeq.distinct.size >= 2,
                                s"the camera did not orbit after enabling autoRotate: the sampled view never changed across " +
                                    s"${after.size} commits, so the reactive autoRotate signal never reached the live OrbitControls."
                            )
                    }
                }
            }
        }
    }

    /** Reads a grid of sample points spread across the CENTRAL portion of the canvas (where the
      * clustered cubes actually project, given the camera's field of view and distance; the canvas
      * corners/edges only ever show the empty background) via `window.controls.pixel(x, y)`, in one
      * awaited round trip, and returns their concatenated RGBA bytes as one signature string.
      *
      * A single dead-center sample degenerates for this fixture: the ORBIT `target` and the red cube's
      * own center coincide, so the camera's aim ray keeps hitting the same face of that cube (same
      * Lambertian shading, independent of view angle) until the orbit crosses a full 45-degree face
      * boundary, far more rotation than a short sampling window accumulates. The off-pivot green/blue/
      * yellow cubes have no such degeneracy: their projected screen position sweeps for any nonzero
      * orbit angle, so a grid spanning several rows and columns around the cluster reliably reflects
      * even a small camera rotation, wherever a cube's silhouette edge happens to fall.
      */
    private def readSignature(using Frame): String < (Browser & Abort[BrowserReadException]) =
        // Browser.eval does NOT await a returned Promise (only kyo.internal.BrowserEval.evalJsAwaiting
        // does); pixel() is async, so the async IIFE below must be read through the awaiting variant.
        kyo.internal.BrowserEval.evalJsAwaiting(
            """(async () => {
              |    const rows = [160, 240, 320];
              |    const parts = [];
              |    for (const y of rows) {
              |        for (let i = 0; i <= 8; i++) {
              |            const x = Math.floor(639 * i / 8);
              |            try {
              |                const px = await window.controls.pixel(x, y);
              |                parts.push(px.join(':'));
              |            } catch (e) {
              |                parts.push('REJECTED:' + String(e));
              |            }
              |        }
              |    }
              |    return parts.join(',');
              |})()""".stripMargin
        )

    /** Surfaces a page-side probe error as a test failure rather than waiting out the ready flag. */
    private def awaitProbeError(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.eval("window.controlsError || ''").map { err =>
            if err.nonEmpty then Abort.fail(BrowserScriptErrorException(err))
            else ()
        }

    /** On a ready-flag timeout, attaches the page's diagnostic state so the cause is visible. */
    private def diagnoseOnTimeout(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for err <- Browser.eval("String(window.controlsError)")
            yield Abort.fail(BrowserScriptErrorException(s"the controls probe never installed its projection: controlsError='$err'"))
        }(wait)

    /** Serves the fixtures bundle, three.js, and the GLTFLoader jsm stack the bundle imports, plus the static
      * controls page (an import map resolving `three` and the jsm to the served modules), then hands the
      * page URL to `f`.
      */
    private def servedControls[A](
        f: String => A < (Async & Scope & Abort[BrowserException])
    )(using Frame): A < (Async & Scope & Abort[BrowserException]) =
        served(ThreeControlsBrowserTest.controlsPage)(f)

    /** Serves the same stack against the reactive-controls page (imports `mountControlsReactive`). */
    private def servedControlsReactive[A](
        f: String => A < (Async & Scope & Abort[BrowserException])
    )(using Frame): A < (Async & Scope & Abort[BrowserException]) =
        served(ThreeControlsBrowserTest.controlsReactivePage)(f)

    private def served[A](page: String)(
        f: String => A < (Async & Scope & Abort[BrowserException])
    )(using Frame): A < (Async & Scope & Abort[BrowserException]) =
        for
            bundle    <- WebGLSceneHarness.readFixtureBundle
            module    <- WebGLSceneHarness.readThreeSource("three.module.js")
            core      <- WebGLSceneHarness.readThreeSource("three.core.js")
            gltf      <- WebGLSceneHarness.readThreeJsm("loaders/GLTFLoader.js")
            bufUtils  <- WebGLSceneHarness.readThreeJsm("utils/BufferGeometryUtils.js")
            skelUtils <- WebGLSceneHarness.readThreeJsm("utils/SkeletonUtils.js")
            orbit     <- WebGLSceneHarness.readThreeJsm("controls/OrbitControls.js")
            server <- HttpServer.init(0, "localhost")(
                WebGLSceneHarness.htmlHandler(page),
                WebGLSceneHarness.jsHandler("main.js", bundle),
                WebGLSceneHarness.jsHandler("three.module.js", module),
                WebGLSceneHarness.jsHandler("three.core.js", core),
                WebGLSceneHarness.jsHandler("three/examples/jsm/loaders/GLTFLoader.js", gltf),
                WebGLSceneHarness.jsHandler("three/examples/jsm/utils/BufferGeometryUtils.js", bufUtils),
                WebGLSceneHarness.jsHandler("three/examples/jsm/utils/SkeletonUtils.js", skelUtils),
                WebGLSceneHarness.jsHandler("three/examples/jsm/controls/OrbitControls.js", orbit)
            )
            result <- f(s"http://localhost:${server.port}/")
        yield result

end ThreeControlsBrowserTest

object ThreeControlsBrowserTest:

    /** The controls page: an import map for `three` and the GLTFLoader jsm, a `#app` canvas, and a module
      * script that imports the fixtures bundle's `mountControlsInspect` entry and calls it. The mount runs the
      * static scene through `Three.runMount` and binds the orbiting OrbitControls on load.
      */
    private val controlsPage: String =
        """<!doctype html>
          |<html>
          |<head><meta charset="utf-8"><title>kyo-threejs Three.controls</title>
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
          |window.controlsError = "";
          |try {
          |    const { mountControlsInspect } = await import("/main.js");
          |    mountControlsInspect("#app");
          |} catch (e) {
          |    window.controlsError = String(e && e.message ? e.message : e);
          |}
          |</script>
          |</body>
          |</html>""".stripMargin

    /** The same page as [[controlsPage]], but importing the reactive-controls entry so the mounted scene
      * binds a REACTIVE `autoRotate` the driver flips through `window.controls.signal("autoRotate")`.
      */
    private val controlsReactivePage: String =
        """<!doctype html>
          |<html>
          |<head><meta charset="utf-8"><title>kyo-threejs Three.controls reactive</title>
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
          |window.controlsError = "";
          |try {
          |    const { mountControlsReactive } = await import("/main.js");
          |    mountControlsReactive("#app");
          |} catch (e) {
          |    window.controlsError = String(e && e.message ? e.message : e);
          |}
          |</script>
          |</body>
          |</html>""".stripMargin

end ThreeControlsBrowserTest
