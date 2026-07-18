package kyo

/** Browser-path test for `Three.toImage` that requires a real WebGL context.
  *
  * It loads the ACTUAL compiled fixtures bundle (`main.js`) and calls the `toImageInspect` entry, which runs
  * the real `Three.toImage` (a headless capture: no live loop, no `Three.Mount`) for a lit and an empty
  * scene and projects the returned PNG bytes as `window.toImageBytes`. The test decodes the PNG with the
  * browser's OWN native decoder (`Blob` + `createImageBitmap` + an `OffscreenCanvas` readback) and asserts
  * on the DECODED pixels and dimensions, so it exercises the public `Image` result of the real
  * `Three.toImage` end to end.
  *
  * It asserts the capture's OUTPUT, not the renderer's internal teardown: the render target's GL dispose is
  * a property of `Three.toImage`'s own `Scope`, covered by the scoped-disposal invariant, and this leaf
  * does not instrument it.
  *
  * Runs in a real software-WebGL Chrome over CDP; cancels (skips) where no Chrome can be downloaded.
  * Every assertion observes real PNG-decoded pixels; nothing is faked.
  */
class ThreeToImageBrowserTest extends WebGLSceneHarness:

    "Three.toImage of a lit scene decodes to at least 2 distinct pixels" in {
        cancelOnUnsupportedPlatform {
            servedProbe(ThreeToImageBrowserTest.page(lit = true, 128, 128)) { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _      <- Browser.goto(url)
                            _      <- awaitProbeError
                            _      <- Browser.waitFor("window.toImageBytes !== undefined").handle(diagnoseOnTimeout)
                            result <- decodeResult
                        yield
                            assert(
                                result.distinct >= 2,
                                s"expected the lit scene to decode to >= 2 distinct pixels, got ${result.distinct}"
                            )
                            assert(
                                result.width == 128 && result.height == 128,
                                s"expected decoded dimensions 128x128, got ${result.width}x${result.height}"
                            )
                    }
                }
            }
        }
    }

    "Three.toImage of an empty scene decodes to at most 1 distinct pixel (the clear color only)" in {
        cancelOnUnsupportedPlatform {
            servedProbe(ThreeToImageBrowserTest.page(lit = false, 96, 64)) { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _      <- Browser.goto(url)
                            _      <- awaitProbeError
                            _      <- Browser.waitFor("window.toImageBytes !== undefined").handle(diagnoseOnTimeout)
                            result <- decodeResult
                        yield
                            assert(
                                result.distinct <= 1,
                                s"expected the empty scene to decode to at most 1 distinct pixel (clear color only), got ${result.distinct}"
                            )
                            assert(
                                result.width == 96 && result.height == 64,
                                s"expected decoded dimensions honoring a non-square width/height, got ${result.width}x${result.height}"
                            )
                    }
                }
            }
        }
    }

    /** Reads the fixtures bundle, three.js build, and the GLTFLoader jsm stack the linked bundle imports,
      * and serves them with the given probe page over HTTP.
      */
    private def servedProbe[A](page: String)(
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

    /** Decodes `window.toImageBytes` (a raw PNG `Uint8Array`) with the browser's own native decoder and
      * returns the distinct-pixel count plus the decoded dimensions.
      */
    private def decodeResult(using Frame, kyo.test.AssertScope): ToImageDecoded < (Browser & Abort[BrowserReadException]) =
        // Browser.eval does NOT await a returned Promise (only kyo.internal.BrowserEval.evalJsAwaiting
        // does); createImageBitmap is async, so the async IIFE below must be read through the awaiting variant.
        kyo.internal.BrowserEval.evalJsAwaiting(
            """(async () => {
              |    const bytes = window.toImageBytes;
              |    const blob = new Blob([bytes], { type: 'image/png' });
              |    const bitmap = await createImageBitmap(blob);
              |    const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
              |    const ctx = canvas.getContext('2d');
              |    ctx.drawImage(bitmap, 0, 0);
              |    const data = ctx.getImageData(0, 0, bitmap.width, bitmap.height).data;
              |    const seen = new Set();
              |    for (let i = 0; i < data.length; i += 4) {
              |        seen.add((data[i] << 24) | (data[i + 1] << 16) | (data[i + 2] << 8) | data[i + 3]);
              |        if (seen.size > 64) break;
              |    }
              |    return seen.size + ',' + bitmap.width + ',' + bitmap.height;
              |})()""".stripMargin
        ).map(_.split(",").map(_.trim.toInt) match
            case Array(distinct, width, height) => ToImageDecoded(distinct, width, height)
            case other                          => fail(s"unexpected decode result: ${other.mkString(",")}"))

    /** Surfaces a page-side probe error as a test failure rather than waiting out the ready flag. */
    private def awaitProbeError(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.eval("window.toImageResultError || ''").map { err =>
            if err.nonEmpty then Abort.fail(BrowserScriptErrorException(err))
            else ()
        }

    /** On a ready-flag timeout, attaches the page's diagnostic state so the cause is visible. */
    private def diagnoseOnTimeout(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for err <- Browser.eval("String(window.toImageResultError)")
            yield Abort.fail(BrowserScriptErrorException(s"the toImage probe never projected its result: toImageResultError='$err'"))
        }(wait)

end ThreeToImageBrowserTest

/** The decoded `Three.toImage` PNG result: the distinct RGBA count and the decoded dimensions. */
final private[kyo] case class ToImageDecoded(distinct: Int, width: Int, height: Int)

object ThreeToImageBrowserTest:

    /** The probe page: an import map for `three`, and the `toImageInspect(lit, width, height)`
      * ES-module call. The probe runs the real `Three.toImage` and projects the raw PNG bytes as
      * `window.toImageBytes`; the page only catches an import throw.
      */
    def page(lit: Boolean, width: Int, height: Int): String =
        s"""<!doctype html>
           |<html>
           |<head><meta charset="utf-8"><title>kyo-threejs toImage probe</title>
           |<script type="importmap">
           |{ "imports": {
           |    "three": "/three.module.js",
           |    "three/examples/jsm/loaders/GLTFLoader.js": "/three/examples/jsm/loaders/GLTFLoader.js",
           |    "three/examples/jsm/utils/BufferGeometryUtils.js": "/three/examples/jsm/utils/BufferGeometryUtils.js",
           |    "three/examples/jsm/utils/SkeletonUtils.js": "/three/examples/jsm/utils/SkeletonUtils.js",
           |    "three/examples/jsm/controls/OrbitControls.js": "/three/examples/jsm/controls/OrbitControls.js"
           |} }
           |</script>
           |</head>
           |<body>
           |<script type="module">
           |window.toImageResultError = "";
           |try {
           |    const { toImageInspect } = await import("/main.js");
           |    toImageInspect($lit, $width, $height);
           |} catch (e) {
           |    window.toImageResultError = String(e && e.message ? e.message : e);
           |}
           |</script>
           |</body>
           |</html>""".stripMargin

end ThreeToImageBrowserTest
