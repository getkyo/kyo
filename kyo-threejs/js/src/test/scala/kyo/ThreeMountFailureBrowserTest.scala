package kyo

/** Pins that the DIRECT `Three.runMount` entry surfaces a mount-pipeline failure through its typed
  * `Abort[ThreeException]` row, rather than swallowing it as a silent success over a dead mount.
  *
  * The registry/hydrate mount path (`ThreeBackend.mount` -> `hostMountPipeline`) swallow-and-logs
  * because the kyo-ui Backend SPI seam's callback row cannot carry `Abort[ThreeException]`; the direct
  * entry (`Three.runMount` -> `hostMountPipelineTyped`) must NOT swallow, since its declared row and its
  * scaladoc/README promise the typed channel. The `mountRunMountOutcomeProbe` entry runs `runMount` and
  * records the outcome; the serving page commits the canvas to a 2D context FIRST, so a WebGL context
  * can never be created on it (deterministic, GPU-independent), and the production `makeRenderer` maps
  * that to the typed `WebGLUnavailable` leaf. The test asserts the outcome is "typed:WebGLUnavailable",
  * never "success".
  *
  * Runs in a real software-WebGL Chrome over CDP; cancels (skips) where no Chrome can be downloaded.
  */
class ThreeMountFailureBrowserTest extends WebGLSceneHarness:

    "Three.runMount surfaces a mount-pipeline failure through its typed Abort row (a canvas with no WebGL context yields WebGLUnavailable, never a silent success)" in {
        cancelOnUnsupportedPlatform {
            servedProbe { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _       <- Browser.goto(url)
                            _       <- awaitRunMountError
                            _       <- Browser.waitFor("window.__runMountReady === true").handle(diagnoseOnTimeout)
                            outcome <- Browser.eval("String(window.__runMountOutcome)")
                        yield assert(
                            outcome == "typed:WebGLUnavailable",
                            s"runMount must surface the no-WebGL failure as a typed WebGLUnavailable through Abort, got '$outcome'"
                        )
                    }
                }
            }
        }
    }

    /** Reads the demo bundle, three.js build, and the jsm stack the linked bundle imports, and serves
      * them with the probe page over HTTP.
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
            orbit     <- WebGLSceneHarness.readThreeJsm("controls/OrbitControls.js")
            server <- HttpServer.init(0, "localhost")(
                WebGLSceneHarness.htmlHandler(ThreeMountFailureBrowserTest.page),
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
    private def awaitRunMountError(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.eval("window.__runMountError || ''").map { err =>
            if err.nonEmpty then Abort.fail(BrowserScriptErrorException(err))
            else ()
        }

    /** On a ready-flag timeout, attaches the page's diagnostic state so the cause is visible. */
    private def diagnoseOnTimeout(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for
                err     <- Browser.eval("String(window.__runMountError)")
                outcome <- Browser.eval("String(window.__runMountOutcome)")
            yield Abort.fail(BrowserScriptErrorException(
                s"runMount outcome probe ready flag never set: runMountError='$err' outcome=$outcome"
            ))
        }(wait)

end ThreeMountFailureBrowserTest

object ThreeMountFailureBrowserTest:

    /** The probe page: an import map for `three`, the canvas, and the `mountRunMountOutcomeProbe` call.
      * The canvas is committed to a 2D context before the probe runs, so a WebGL context can never be
      * created on it and `Three.runMount` fails through `makeRenderer` with the typed `WebGLUnavailable`.
      */
    private[kyo] val page: String =
        """<!doctype html>
          |<html>
          |<head><meta charset="utf-8"><title>kyo-threejs runMount typed failure</title>
          |<script type="importmap">
          |{ "imports": {
          |    "three": "/three.module.js",
          |    "three/examples/jsm/loaders/GLTFLoader.js": "/three/examples/jsm/loaders/GLTFLoader.js",
          |    "three/examples/jsm/controls/OrbitControls.js": "/three/examples/jsm/controls/OrbitControls.js"
          |} }
          |</script>
          |</head>
          |<body>
          |<canvas id="app" width="256" height="256"></canvas>
          |<script type="module">
          |window.__runMountError = "";
          |window.__runMountOutcome = "";
          |window.__runMountReady = false;
          |try {
          |    // Lock the canvas to a 2D context so a WebGL context can NEVER be created on it: three.js's
          |    // WebGLRenderer constructor then fails deterministically (GPU-independent), which the
          |    // production makeRenderer maps to the typed WebGLUnavailable leaf.
          |    document.querySelector("#app").getContext("2d");
          |    const { mountRunMountOutcomeProbe } = await import("/main.js");
          |    mountRunMountOutcomeProbe("#app");
          |} catch (e) {
          |    window.__runMountError = String(e && e.message ? e.message : e);
          |}
          |</script>
          |</body>
          |</html>""".stripMargin

end ThreeMountFailureBrowserTest
