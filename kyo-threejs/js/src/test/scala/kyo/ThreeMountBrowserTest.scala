package kyo

/** Browser-path teardown guard for [[ThreeMount]] that requires a real WebGL context.
  *
  * It loads the ACTUAL compiled demo bundle (`main.js`) and calls the `mountRendererReleaseProbe` entry,
  * which runs the real `ThreeMount.makeRenderer` acquire/release through a `Scope` and records whether the
  * WebGL context was released when the scope closed. The production release runs `renderer.dispose()` then
  * `forceContextLoss()`, so the captured context reports `isContextLost()` after the scope closes; this
  * test fails if the production teardown stops releasing the context. The live-mount render path is covered
  * by [[DemoVisualReviewTest]] (all six demos) and the onFrame-before-render ordering by
  * [[ThreeMountOrderingBrowserTest]].
  *
  * Runs in a real software-WebGL Chrome over CDP; cancels (skips) where no Chrome can be downloaded. The
  * assertion observes a real GL-context state set by the compiled production path; nothing is faked.
  */
class ThreeMountBrowserTest extends WebGLSceneHarness:

    "the production makeRenderer release frees the WebGL context on scope close" in {
        cancelOnUnsupportedPlatform {
            servedProbe { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _    <- Browser.goto(url)
                            _    <- awaitProbeError
                            _    <- Browser.waitFor("window.__releaseReady === true").handle(diagnoseOnTimeout)
                            lost <- Browser.eval("String(window.__contextLost)")
                        yield assert(
                            lost == "true",
                            s"the production renderer release must free the WebGL context on scope close, got __contextLost=$lost"
                        )
                    }
                }
            }
        }
    }

    /** Reads the demo bundle, three.js build, and the GLTFLoader jsm stack the linked bundle imports, and
      * serves them with the probe page over HTTP.
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
                WebGLSceneHarness.htmlHandler(ThreeMountBrowserTest.page),
                WebGLSceneHarness.jsHandler("main.js", bundle),
                WebGLSceneHarness.jsHandler("three.module.js", module),
                WebGLSceneHarness.jsHandler("three.core.js", core),
                WebGLSceneHarness.jsHandler("three/examples/jsm/loaders/GLTFLoader.js", gltf),
                WebGLSceneHarness.jsHandler("three/examples/jsm/utils/BufferGeometryUtils.js", bufUtils),
                WebGLSceneHarness.jsHandler("three/examples/jsm/utils/SkeletonUtils.js", skelUtils)
            )
            result <- f(s"http://localhost:${server.port}/")
        yield result

    /** Surfaces a page-side probe error as a test failure rather than waiting out the ready flag. */
    private def awaitProbeError(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.eval("window.__releaseError || ''").map { err =>
            if err.nonEmpty then Abort.fail(BrowserScriptErrorException(err))
            else ()
        }

    /** On a ready-flag timeout, attaches the page's diagnostic state so the cause is visible. */
    private def diagnoseOnTimeout(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for
                err  <- Browser.eval("String(window.__releaseError)")
                lost <- Browser.eval("String(window.__contextLost)")
            yield Abort.fail(BrowserScriptErrorException(
                s"renderer-release probe ready flag never set: releaseError='$err' contextLost=$lost"
            ))
        }(wait)

end ThreeMountBrowserTest

object ThreeMountBrowserTest:

    /** The probe page: an import map for `three`, the render canvas, and the `mountRendererReleaseProbe`
      * ES-module call. The probe drives the compiled `ThreeMount.makeRenderer` acquire/release and records
      * `window.__contextLost` / `window.__releaseReady`; the page only catches a mount throw.
      */
    private[kyo] val page: String =
        """<!doctype html>
          |<html>
          |<head><meta charset="utf-8"><title>kyo-threejs renderer release</title>
          |<script type="importmap">
          |{ "imports": {
          |    "three": "/three.module.js",
          |    "three/examples/jsm/loaders/GLTFLoader.js": "/three/examples/jsm/loaders/GLTFLoader.js"
          |} }
          |</script>
          |</head>
          |<body>
          |<canvas id="app" width="256" height="256"></canvas>
          |<script type="module">
          |window.__releaseError = "";
          |window.__contextLost = "false";
          |window.__releaseReady = false;
          |try {
          |    const { mountRendererReleaseProbe } = await import("/main.js");
          |    mountRendererReleaseProbe("#app");
          |} catch (e) {
          |    window.__releaseError = String(e && e.message ? e.message : e);
          |}
          |</script>
          |</body>
          |</html>""".stripMargin

end ThreeMountBrowserTest
