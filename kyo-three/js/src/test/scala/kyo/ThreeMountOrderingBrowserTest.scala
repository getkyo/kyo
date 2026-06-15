package kyo

/** The in-browser frame-ordering guard for [[ThreeMount]]: proves the live `Three.runMount` loop
  * applies a tick's `onFrame` mutations BEFORE that tick's render submit, so the first rendered frame
  * reflects the post-`onFrame` state.
  *
  * It loads the ACTUAL compiled demo bundle (`main.js`) and calls the `mountOrderingProbe` entry,
  * which runs the real `runMount` / `runLoop` / `oneTick` path on a scene whose mesh is built
  * invisible (scale 0) and off-center, and whose `onFrame` moves it to center and scales it to full
  * directly on the live object. The mesh's three.js `onAfterRender` reads the framebuffer center pixel
  * during the FIRST render submit (the buffer still live) and records whether it is lit. A loop that
  * applies the tick's `onFrame` before its render draws the lit mesh at center (center lit); a loop
  * that renders before applying `onFrame` draws the invisible off-center seed (center dark). The
  * assertion requires the center lit, so it holds only when each tick applies its `onFrame` before
  * that tick's render submit.
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
                            _   <- Browser.waitFor("window.__orderingReady === true").handle(diagnoseOnTimeout)
                            lit <- Browser.eval("String(window.__orderingCenterLit)")
                        yield assert(
                            lit == "true",
                            s"first rendered frame must show the post-onFrame mesh lit at center, got __orderingCenterLit=$lit"
                        )
                    }
                }
            }
        }
    }

    /** Reads the demo bundle, three.js build, and the GLTFLoader jsm stack the linked bundle imports,
      * and serves them with the probe page over HTTP.
      */
    private def servedProbe[A](
        f: String => A < (Async & Scope & Abort[BrowserException])
    )(using Frame): A < (Async & Scope & Abort[BrowserException]) =
        for
            bundle    <- WebGLSceneHarness.readDemoBundle
            module    <- WebGLSceneHarness.readThreeSource("three.module.js")
            core      <- WebGLSceneHarness.readThreeSource("three.core.js")
            gltf      <- WebGLSceneHarness.readThreeJsm("loaders/GLTFLoader.js")
            bufUtils  <- WebGLSceneHarness.readThreeJsm("utils/BufferGeometryUtils.js")
            skelUtils <- WebGLSceneHarness.readThreeJsm("utils/SkeletonUtils.js")
            server <- HttpServer.init(0, "localhost")(
                WebGLSceneHarness.htmlHandler(ThreeMountOrderingBrowserTest.page),
                WebGLSceneHarness.jsHandler("main.js", bundle),
                WebGLSceneHarness.jsHandler("three.module.js", module),
                WebGLSceneHarness.jsHandler("three.core.js", core),
                WebGLSceneHarness.jsHandler("three/examples/jsm/loaders/GLTFLoader.js", gltf),
                WebGLSceneHarness.jsHandler("three/examples/jsm/utils/BufferGeometryUtils.js", bufUtils),
                WebGLSceneHarness.jsHandler("three/examples/jsm/utils/SkeletonUtils.js", skelUtils)
            )
            result <- f(s"http://localhost:${server.port}/")
        yield result

    /** Surfaces a page-side mount error as a test failure rather than waiting out the ready flag. */
    private def awaitProbeError(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.eval("window.__orderingError || ''").map { err =>
            if err.nonEmpty then Abort.fail(BrowserScriptErrorException(err))
            else ()
        }

    /** On a ready-flag timeout, attaches the page's diagnostic state so the cause is visible. */
    private def diagnoseOnTimeout(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for
                err <- Browser.eval("String(window.__orderingError)")
                lit <- Browser.eval("String(window.__orderingCenterLit)")
            yield Abort.fail(BrowserScriptErrorException(
                s"ordering probe ready flag never set: orderingError='$err' orderingCenterLit=$lit"
            ))
        }(wait)

end ThreeMountOrderingBrowserTest

object ThreeMountOrderingBrowserTest:

    /** The probe page: an import map for `three`, the render canvas, and the `mountOrderingProbe`
      * ES-module call. The probe itself drives `requestAnimationFrame` through the kyo loop and records
      * `window.__orderingCenterLit` / `window.__orderingReady`; the page only catches a mount throw.
      */
    private[kyo] val page: String =
        """<!doctype html>
          |<html>
          |<head><meta charset="utf-8"><title>kyo-three ordering probe</title>
          |<script type="importmap">
          |{ "imports": {
          |    "three": "/three.module.js",
          |    "three/examples/jsm/loaders/GLTFLoader.js": "/three/examples/jsm/loaders/GLTFLoader.js"
          |} }
          |</script>
          |<style>body { margin: 0; background: #000; }</style>
          |</head>
          |<body>
          |<canvas id="app" width="256" height="256"></canvas>
          |<script type="module">
          |window.__orderingError = "";
          |window.__orderingCenterLit = false;
          |window.__orderingReady = false;
          |try {
          |    const { mountOrderingProbe } = await import("/main.js");
          |    mountOrderingProbe("#app");
          |} catch (e) {
          |    window.__orderingError = String(e && e.message ? e.message : e);
          |}
          |</script>
          |</body>
          |</html>""".stripMargin

end ThreeMountOrderingBrowserTest
