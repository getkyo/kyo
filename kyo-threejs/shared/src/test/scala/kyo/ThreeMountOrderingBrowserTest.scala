package kyo

/** The in-browser frame-ordering guard for [[ThreeMount]]: proves the live `Three.runMount` loop
  * applies a tick's `onFrame` mutations BEFORE that tick's render submit, so the first rendered frame
  * reflects the post-`onFrame` state.
  *
  * It loads the ACTUAL compiled fixtures bundle (`main.js`) and calls the `mountOrderingInspect` entry,
  * which runs the real `runMount` / `runLoop` / `oneTick` path with `ThreeFrames.Manual` on a scene
  * whose mesh is built with an unlit BLACK material and whose `onFrame` brightens it directly on the
  * live object. The probe enqueues a `Three.Mount.readPixels` at the exact center BEFORE the first
  * frame commits (a direct, non-forked call runs its enqueue synchronously; only then does the
  * already-forked `driver.step` run, so the drain lands on frame 1 exactly, never a later frame), steps
  * ONE deterministic frame, and projects whether the drained frame is lit as `window.ordering.centerLit`.
  * A loop that applies the tick's `onFrame` before its render draws the lit mesh at center (center lit);
  * a loop that renders before applying `onFrame` draws the still-black seed (center dark).
  *
  * Runs in a real software-WebGL Chrome over CDP; cancels (skips) where no Chrome can be downloaded.
  * Every assertion observes a real pixel buffer; nothing is faked.
  */
class ThreeMountOrderingBrowserTest extends WebGLSceneHarness:

    "the live runMount loop renders each onFrame mutation in the same frame (first frame center is lit)" in {
        cancelOnUnsupportedPlatform {
            servedProbe { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _   <- Browser.goto(url)
                            _   <- awaitProbeError
                            _   <- Browser.waitFor("window.ordering !== undefined").handle(diagnoseOnTimeout)
                            lit <- Browser.eval("String(window.ordering.centerLit)")
                        yield assert(
                            lit == "true",
                            s"first rendered frame must show the post-onFrame mesh lit at center, got ordering.centerLit=$lit"
                        )
                    }
                }
            }
        }
    }

    /** Reads the fixtures bundle, three.js build, and the GLTFLoader jsm stack the linked bundle imports,
      * and serves them with the probe page over HTTP.
      */
    private def servedProbe[A](
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
                WebGLSceneHarness.htmlHandler(ThreeMountOrderingBrowserTest.page),
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

    /** Surfaces a page-side probe error as a test failure rather than waiting out the ready flag. */
    private def awaitProbeError(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.eval("window.orderingError || ''").map { err =>
            if err.nonEmpty then Abort.fail(BrowserScriptErrorException(err))
            else ()
        }

    /** On a ready-flag timeout, attaches the page's diagnostic state so the cause is visible. */
    private def diagnoseOnTimeout(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for
                err <- Browser.eval("String(window.orderingError)")
            yield Abort.fail(BrowserScriptErrorException(
                s"the ordering probe never installed its projection: orderingError='$err'"
            ))
        }(wait)

end ThreeMountOrderingBrowserTest

object ThreeMountOrderingBrowserTest:

    /** The probe page: an import map for `three`, the render canvas, and the `mountOrderingInspect`
      * ES-module call. The probe itself drives the deterministic one-step Manual loop and projects
      * `window.ordering`; the page only catches an import throw.
      */
    private[kyo] val page: String =
        """<!doctype html>
          |<html>
          |<head><meta charset="utf-8"><title>kyo-threejs ordering probe</title>
          |<script type="importmap">
          |{ "imports": {
          |    "three": "/three.module.js",
          |    "three/examples/jsm/loaders/GLTFLoader.js": "/three/examples/jsm/loaders/GLTFLoader.js",
          |    "three/examples/jsm/controls/OrbitControls.js": "/three/examples/jsm/controls/OrbitControls.js"
          |} }
          |</script>
          |<style>body { margin: 0; background: #000; }</style>
          |</head>
          |<body>
          |<canvas id="app" width="256" height="256"></canvas>
          |<script type="module">
          |window.orderingError = "";
          |try {
          |    const { mountOrderingInspect } = await import("/main.js");
          |    mountOrderingInspect("#app");
          |} catch (e) {
          |    window.orderingError = String(e && e.message ? e.message : e);
          |}
          |</script>
          |</body>
          |</html>""".stripMargin

end ThreeMountOrderingBrowserTest
