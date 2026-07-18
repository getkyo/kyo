package kyo

/** Browser-path teardown and readback guard for [[ThreeMount]] that requires a real WebGL context.
  *
  * It loads the ACTUAL compiled fixtures bundle (`main.js`) and calls the `mountRendererInspect` /
  * `mountRendererLiveInspect` entries, which mount through the real production path and install
  * [[ThreeInspect]] over the resulting [[Three.Mount]] as `window.inspect` / `window.inspectLive`.
  * `mountRendererInspect` closes the mount scope BEFORE installing, so `window.inspect` reports the
  * TORN-DOWN state: the production release runs `renderer.dispose()` then `forceContextLoss()`, so the
  * handle reports `contextLost()` and `disposed()` once the scope has closed, and any further `pixel`
  * read must reject typed `RenderFailure` (a disposed mount is never readable). `mountRendererLiveInspect`
  * installs over a LIVE, still-running mount, so `pixel` reads real RGBA bytes from the live
  * framebuffer, and an out-of-bounds region still rejects typed `RenderFailure` rather than returning a
  * garbage buffer. The live-mount render path is covered by the six live demos (each runnable through
  * the demos configuration; see the README's "Running the demos") and the onFrame-before-render
  * ordering by [[ThreeMountOrderingBrowserTest]].
  *
  * Runs in a real software-WebGL Chrome over CDP; cancels (skips) where no Chrome can be downloaded. Every
  * assertion observes a real GL-context state set by the compiled production path; nothing is faked.
  */
class ThreeMountBrowserTest extends WebGLSceneHarness:

    "the production makeRenderer release frees the WebGL context on scope close" in {
        cancelOnUnsupportedPlatform {
            servedProbe(ThreeMountBrowserTest.releasePage) { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _    <- Browser.goto(url)
                            _    <- awaitProbeError("inspect")
                            _    <- Browser.waitFor("window.inspect !== undefined").handle(diagnoseOnTimeout("inspect"))
                            lost <- Browser.eval("String(window.inspect.contextLost())")
                            gone <- Browser.eval("String(window.inspect.disposed())")
                        yield
                            assert(
                                lost == "true",
                                s"the production renderer release must free the WebGL context on scope close, got contextLost=$lost"
                            )
                            assert(
                                gone == "true",
                                s"the mount handle must report disposed once its scope has closed, got disposed=$gone"
                            )
                    }
                }
            }
        }
    }

    "readPixels on a live mount returns real RGBA bytes from the framebuffer" in {
        cancelOnUnsupportedPlatform {
            servedProbe(ThreeMountBrowserTest.livePage) { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _   <- Browser.goto(url)
                            _   <- awaitProbeError("inspectLive")
                            _   <- Browser.waitFor("window.inspectLive !== undefined").handle(diagnoseOnTimeout("inspectLive"))
                            _   <- Browser.waitFor("window.inspectLive.renders() >= 1")
                            rgb <- readPixel("inspectLive", 128, 128)
                        yield rgb match
                            case PixelRead.Ok(r, g, b, _) =>
                                // The probe's box carries an unlit `Material.basic(color = 0x3377ff)`, so the
                                // rendered center pixel is that color verbatim, within real-GL tolerance.
                                assert(
                                    math.abs(r - 0x33) < 24 && math.abs(g - 0x77) < 24 && math.abs(b - 0xff) < 24,
                                    s"expected the lit box color (0x3377ff-ish) at the frame center, got r=$r g=$g b=$b"
                                )
                            case PixelRead.Rejected(msg) =>
                                fail(s"a live mount's readPixels must resolve real bytes, got a rejection: $msg")
                    }
                }
            }
        }
    }

    "readPixels on a disposed mount rejects typed RenderFailure" in {
        cancelOnUnsupportedPlatform {
            servedProbe(ThreeMountBrowserTest.releasePage) { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _    <- Browser.goto(url)
                            _    <- awaitProbeError("inspect")
                            _    <- Browser.waitFor("window.inspect !== undefined").handle(diagnoseOnTimeout("inspect"))
                            _    <- Browser.waitFor("window.inspect.disposed() === true")
                            read <- readPixel("inspect", 128, 128)
                        yield read match
                            case PixelRead.Rejected(_) => succeed
                            case PixelRead.Ok(r, g, b, a) =>
                                fail(s"readPixels on a disposed mount must reject typed RenderFailure, got real bytes r=$r g=$g b=$b a=$a")
                    }
                }
            }
        }
    }

    "an out-of-bounds readPixels region rejects typed RenderFailure, never a throw" in {
        cancelOnUnsupportedPlatform {
            servedProbe(ThreeMountBrowserTest.livePage) { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _    <- Browser.goto(url)
                            _    <- awaitProbeError("inspectLive")
                            _    <- Browser.waitFor("window.inspectLive !== undefined").handle(diagnoseOnTimeout("inspectLive"))
                            _    <- Browser.waitFor("window.inspectLive.renders() >= 1")
                            read <- readPixel("inspectLive", 100000, 100000)
                        yield read match
                            case PixelRead.Rejected(_) => succeed
                            case PixelRead.Ok(r, g, b, a) =>
                                fail(s"an out-of-bounds readPixels must reject typed RenderFailure, got real bytes r=$r g=$g b=$b a=$a")
                    }
                }
            }
        }
    }

    /** Reads the fixtures bundle, three.js build, and the GLTFLoader jsm stack the linked bundle imports, and
      * serves them with the given probe page over HTTP.
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

    /** Awaits `window[name].pixel(x, y)` and decodes the resolved bytes or the rejection reason: the
      * page-side call is wrapped in an async IIFE returning a plain string ("OK:r,g,b,a" or
      * "REJECTED:<message>"), so a rejected promise never surfaces as a CDP eval exception, only as a
      * value this method decodes.
      */
    private def readPixel(name: String, x: Int, y: Int)(using
        Frame,
        kyo.test.AssertScope
    ): PixelRead < (Browser & Abort[BrowserReadException]) =
        // Browser.eval does NOT await a returned Promise (only kyo.internal.BrowserEval.evalJsAwaiting
        // does); pixel() is async, so the async IIFE below must be read through the awaiting variant.
        kyo.internal.BrowserEval.evalJsAwaiting(
            s"(async () => { try { const px = await window.$name.pixel($x, $y); return 'OK:' + px.join(','); } " +
                s"catch (e) { return 'REJECTED:' + String(e); } })()"
        ).map {
            case s if s.startsWith("OK:") =>
                s.stripPrefix("OK:").split(",").map(_.trim.toInt) match
                    case Array(r, g, b, a) => PixelRead.Ok(r, g, b, a)
                    case other             => fail(s"unexpected pixel encoding: ${other.mkString(",")}")
            case s if s.startsWith("REJECTED:") => PixelRead.Rejected(s.stripPrefix("REJECTED:"))
            case other                          => fail(s"unexpected readPixel result: $other")
        }

    /** Surfaces a page-side probe error as a test failure rather than waiting out the ready flag. */
    private def awaitProbeError(name: String)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.eval(s"window.${name}Error || ''").map { err =>
            if err.nonEmpty then Abort.fail(BrowserScriptErrorException(err))
            else ()
        }

    /** On a ready-flag timeout, attaches the page's diagnostic state so the cause is visible. */
    private def diagnoseOnTimeout(name: String)(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for
                err <- Browser.eval(s"String(window.${name}Error)")
            yield Abort.fail(BrowserScriptErrorException(
                s"the renderer-inspect probe never installed its projection: ${name}Error='$err'"
            ))
        }(wait)

end ThreeMountBrowserTest

/** The decoded result of a `window[name].pixel(x, y)` CDP read: either the real RGBA bytes, or the
  * rejection message a typed `RenderFailure` carried.
  */
private[kyo] enum PixelRead derives CanEqual:
    case Ok(r: Int, g: Int, b: Int, a: Int)
    case Rejected(message: String)
end PixelRead

object ThreeMountBrowserTest:

    /** The teardown probe page: calls `mountRendererInspect`, which mounts, holds for one frame,
      * closes the mount scope, then projects the torn-down handle as `window.inspect`.
      */
    private[kyo] val releasePage: String =
        probePage("kyo-threejs renderer release", "mountRendererInspect", "inspect")

    /** The live probe page: calls `mountRendererLiveInspect`, which mounts a lit box and projects the
      * LIVE, still-running handle as `window.inspectLive`.
      */
    private[kyo] val livePage: String =
        probePage("kyo-threejs renderer live", "mountRendererLiveInspect", "inspectLive")

    /** Builds a probe page importing `entry` from `/main.js` and calling it with `#app`, mirroring the
      * two-probe pages this file serves.
      */
    private def probePage(title: String, entry: String, errorName: String): String =
        s"""<!doctype html>
           |<html>
           |<head><meta charset="utf-8"><title>$title</title>
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
           |window.${errorName}Error = "";
           |try {
           |    const { $entry } = await import("/main.js");
           |    $entry("#app");
           |} catch (e) {
           |    window.${errorName}Error = String(e && e.message ? e.message : e);
           |}
           |</script>
           |</body>
           |</html>""".stripMargin

end ThreeMountBrowserTest
