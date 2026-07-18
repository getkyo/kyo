package kyo

/** Browser proof that `Three.embed` correctly integrates the kyo-threejs pipeline into a kyo-ui DOM
  * tree, observed entirely through the public [[Three.Embedded.mounted]] / [[Three.Mount]] /
  * [[ThreeInspect]] surface: the mount handle transitions `Absent` -> `Present` exactly once and is
  * shared across an `.id()` copy, a signal-driven re-render never re-issues it, a click writes a shared
  * `SignalRef[String]` the kyo-ui reactive label observes, and the mount reports its live state
  * (`renders`, `pixel`, `contextLost`, `canvasToken`) plus `disposed` once torn down.
  *
  * Every leaf exercises the real surface: `Three.embed` returns a `UI.Ast.BackendNode` that
  * `DomBackend.fireHostMounts` dispatches (via the `Backend` registry, keyed by `"three"`) to
  * `ThreeBackend.mount`, running through the actual compiled Scala.js bundle linking
  * `ThreeMount.scala`, `ThreeBackend.scala`, and `DomBackend.scala`; nothing is hand-written JavaScript.
  *
  * Runs in a real software-WebGL Chrome via CDP; cancels (skips) where no Chrome can be downloaded.
  */
class ThreeEmbedBrowserTest extends WebGLSceneHarness:

    "embed mounted is Absent before page mount, Present(same handle) once mounted" in {
        cancelOnUnsupportedPlatform {
            servedWithPage(ThreeEmbedBrowserTest.inspectPage) { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _            <- Browser.goto(url)
                            _            <- awaitError("embedInspect")
                            _            <- Browser.waitFor("window.embedInspect !== undefined").handle(diagnoseTimeout("embedInspect"))
                            absentBefore <- Browser.eval("String(window.embedInspect.mountedAbsentBefore)")
                            disposed     <- Browser.eval("String(window.embedInspect.disposed())")
                        yield
                            assert(
                                absentBefore == "true",
                                s"Embedded.mounted must read Absent before the async page mount, got mountedAbsentBefore=$absentBefore"
                            )
                            assert(
                                disposed == "false",
                                s"the mount must be live (not disposed) once the projection installs over the Present handle, got disposed=$disposed"
                            )
                    }
                }
            }
        }
    }

    "mounted is observable through a pre-.id() reference (the shared ref survives the .id() copy)" in {
        cancelOnUnsupportedPlatform {
            servedWithPage(ThreeEmbedBrowserTest.inspectPage) { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _    <- Browser.goto(url)
                            _    <- awaitError("embedInspect")
                            _    <- Browser.waitFor("window.embedInspect !== undefined").handle(diagnoseTimeout("embedInspect"))
                            same <- Browser.eval("String(window.embedInspect.sameHandleAsIdCopy)")
                        yield assert(
                            same == "true",
                            s"e.mounted (the pre-.id() reference) must resolve the SAME Three.Mount as e.id(\"stage\").mounted " +
                                s"(the tree-placed copy), proving id(v)'s copy preserves the one shared mountedRef, got sameHandleAsIdCopy=$same"
                        )
                    }
                }
            }
        }
    }

    "the mount handle is issued once across a signal-driven re-render" in {
        cancelOnUnsupportedPlatform {
            servedWithPage(ThreeEmbedBrowserTest.siblingPage) { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _      <- Browser.goto(url)
                            _      <- awaitError("embedSibling")
                            _      <- Browser.waitFor("window.embedSibling !== undefined").handle(diagnoseTimeout("embedSibling"))
                            stable <- Browser.eval("String(window.embedSibling.mountStableAcrossRerender)")
                        yield assert(
                            stable == "true",
                            s"the embed mount handle must be issued ONCE and stay identical across a sibling signal-driven " +
                                s"re-render (never re-issued on a re-render), got mountStableAcrossRerender=$stable"
                        )
                    }
                }
            }
        }
    }

    "embed observes renders/pixel/contextLost/disposed + signal + canvasToken through the projection" in {
        cancelOnUnsupportedPlatform {
            servedWithPage(ThreeEmbedBrowserTest.interactivePage) { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _ <- Browser.goto(url)
                            _ <- awaitError("embedInteractive")
                            _ <- Browser.waitFor("window.embedInteractive !== undefined").handle(diagnoseTimeout("embedInteractive"))
                            _ <- Browser.waitFor("document.querySelector('#selected-label') !== null")
                            beforeClick <- Browser.eval("""String(window.embedInteractive.signal("selected").get())""")
                            _           <- Browser.click(Browser.Selector.id("embed-interactive-stage"))
                            _           <- Browser.waitFor("""window.embedInteractive.signal("selected").get() === "sphere"""")
                            afterClick  <- Browser.eval("""String(window.embedInteractive.signal("selected").get())""")
                            _           <- Browser.waitFor("""document.querySelector('#selected-label')?.textContent?.includes('sphere')""")
                            // The embed canvas is laid out by kyo-ui with no explicit width/height (unlike
                            // the runMount probe pages' fixed-size <canvas> markup), so its actual pixel
                            // dimensions are read at runtime rather than assumed.
                            center      <- centerOf("embed-interactive-stage")
                            pixel       <- readPixel("embedInteractive", center._1, center._2)
                            contextLost <- Browser.eval("String(window.embedInteractive.contextLost())")
                            tokenBefore <- Browser.eval("String(window.embedInteractive.canvasToken())")
                            // Deterministic teardown: the driver-triggered close ends the SAME mount's own
                            // scope (a Promise gate, never a sleep), so disposed() reports the real
                            // post-teardown state rather than a page-navigation side effect.
                            _        <- Browser.eval("window.embedInteractive.close()")
                            _        <- Browser.waitFor("window.embedInteractive.disposed() === true")
                            disposed <- Browser.eval("String(window.embedInteractive.disposed())")
                        yield
                            assert(beforeClick == "", s"the selection signal must start empty before any click, got '$beforeClick'")
                            assert(afterClick == "sphere", s"a click on the sphere must write the selection signal, got '$afterClick'")
                            pixel match
                                case PixelRead.Ok(r, g, b, _) =>
                                    assert(
                                        r > 0x40,
                                        s"the lit sphere must occupy the frame center (0xff5533-ish), got r=$r g=$g b=$b"
                                    )
                                case PixelRead.Rejected(msg) => fail(s"a live embed mount's pixel read must resolve real bytes, got: $msg")
                            end match
                            assert(contextLost == "false", s"the GL context must be live before teardown, got contextLost=$contextLost")
                            assert(tokenBefore.nonEmpty, "canvasToken must be a stable non-empty identity for the mounted canvas")
                            assert(
                                disposed == "true",
                                s"the mount must report disposed once its scope has closed via close(), got disposed=$disposed"
                            )
                    }
                }
            }
        }
    }

    "on a SERVER-RENDERED page brought to life by UI.runHydrate, the live mount drives the page: the frame count climbs and Capture reads real pixels" in {
        cancelOnUnsupportedPlatform {
            servedHud { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _ <- Browser.goto(url)
                            // The embed hydrated onto the canvas the SERVER rendered (never re-created client-side).
                            _ <- Browser.waitFor("document.querySelector('#stage') !== null")
                            // The frame counter is fed by Three.Mount.renders, which exists ONLY in the browser: a
                            // count above zero proves a client-owned region of a server-rendered page is live.
                            _      <- Browser.waitFor(ThreeEmbedBrowserTest.framesClimbed).handle(diagnoseHud("#frame-count"))
                            frames <- Browser.eval("String(document.querySelector('#frame-count').textContent)")
                            // A real click, delivered the way a user delivers it. The handler calls readPixels on the
                            // live mount; a server-resolved click would report "Not mounted yet." instead.
                            _       <- Browser.click(Browser.Selector.id("capture"))
                            _       <- Browser.waitFor(ThreeEmbedBrowserTest.captureReported).handle(diagnoseHud("#capture-result"))
                            capture <- Browser.eval("String(document.querySelector('#capture-result').textContent)")
                            tuple: (String, String) = (frames, capture)
                        yield tuple
                    }
                }
            }.map { case (frames, capture) =>
                val counted = ThreeEmbedBrowserTest.framesOf(frames)
                assert(
                    counted > 0,
                    s"the HUD's frame count is fed by the live mount's renders signal, so it must climb past zero on a " +
                        s"hydrated server-rendered page, got '$frames'"
                )
                assert(
                    capture.startsWith("Captured "),
                    s"Capture must read the LIVE framebuffer through the mount handle the browser holds, got '$capture'"
                )
                assert(
                    ThreeEmbedBrowserTest.pixelsOf(capture) > 0,
                    s"the capture must report real pixels read off the live canvas, got '$capture'"
                )
            }
        }
    }

    /** Serves `fixture.MountHudScene.page` through the REAL `UI.runHandlers` (the SSR page GET plus the
      * `/_kyo/ws` route), alongside the linked fixtures bundle whose `hydrateMountHud` entry rebuilds the same
      * tree client-side and hydrates it. This is the path a real server-driven app takes, so the page's
      * DOM regions and click handlers are resolved by the SAME server session a live app would have.
      */
    private def servedHud[A](
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
            // PageHead.moduleScript only links a src; the bundle's hydrateMountHud export needs an explicit
            // caller, so boot.js imports the named export and invokes it (as the bridge tests do).
            bootJs = """import { hydrateMountHud } from "/main.js"; hydrateMountHud();"""
            head = UI.PageHead(
                "kyo-threejs mount hud",
                css = "#stage { display: block; width: 320px; height: 240px; }",
                moduleScript = Present("/boot.js"),
                importMap = Seq(
                    "three"                                        -> "/three.module.js",
                    "three/examples/jsm/loaders/GLTFLoader.js"     -> "/three/examples/jsm/loaders/GLTFLoader.js",
                    "three/examples/jsm/controls/OrbitControls.js" -> "/three/examples/jsm/controls/OrbitControls.js"
                )
            )
            appHandlers <- UI.runHandlers("/", head)(fixture.MountHudScene.page)
            server <- HttpServer.init(0, "localhost")(
                (appHandlers ++ Seq(
                    WebGLSceneHarness.jsHandler("boot.js", bootJs),
                    WebGLSceneHarness.jsHandler("main.js", bundle),
                    WebGLSceneHarness.jsHandler("three.module.js", module),
                    WebGLSceneHarness.jsHandler("three.core.js", core),
                    WebGLSceneHarness.jsHandler("three/examples/jsm/loaders/GLTFLoader.js", gltf),
                    WebGLSceneHarness.jsHandler("three/examples/jsm/utils/BufferGeometryUtils.js", bufUtils),
                    WebGLSceneHarness.jsHandler("three/examples/jsm/utils/SkeletonUtils.js", skelUtils),
                    WebGLSceneHarness.jsHandler("three/examples/jsm/controls/OrbitControls.js", orbit)
                ))*
            )
            result <- f(s"http://localhost:${server.port}/")
        yield result
    end servedHud

    /** On a HUD timeout, reports what the readout actually says (a frozen counter, or the server's
      * "Not mounted yet.", each of which names a different break) instead of a bare timeout.
      */
    private def diagnoseHud(selector: String)(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for
                text   <- Browser.eval(s"String(document.querySelector('$selector')?.textContent)")
                canvas <- Browser.eval("String(document.querySelector('#stage') !== null)")
            yield Abort.fail(BrowserScriptErrorException(
                s"the HUD readout at $selector never reflected the live mount: text='$text' canvasPresent=$canvas"
            ))
        }(wait)

    /** Serves the fixtures bundle and three.js files with the given probe page. */
    private def servedWithPage[A](html: String)(
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
                WebGLSceneHarness.htmlHandler(html),
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

    /** Reads the mounted canvas's actual pixel dimensions and returns its center coordinate, mirroring
      * `ThreeBackendBridgeBrowserTest.centerOf`.
      */
    private def centerOf(selector: String)(using Frame): (Int, Int) < (Browser & Abort[BrowserReadException]) =
        for
            w <- Browser.evalInt(s"document.querySelector('#$selector').width")
            h <- Browser.evalInt(s"document.querySelector('#$selector').height")
        yield (w / 2, h / 2)

    /** Awaits `window[name].pixel(x, y)` and decodes the resolved bytes or the rejection reason,
      * mirroring `ThreeMountBrowserTest.readPixel`.
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

    /** Surfaces a page-side probe error as a test failure. */
    private def awaitError(name: String)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.eval(s"window.${name}Error || ''").map { err =>
            if err.nonEmpty then Abort.fail(BrowserScriptErrorException(err))
            else ()
        }

    /** On a ready-flag timeout, attaches the page's diagnostic state. */
    private def diagnoseTimeout(name: String)(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for err <- Browser.eval(s"String(window.${name}Error)")
            yield Abort.fail(BrowserScriptErrorException(s"probe never installed its projection: ${name}Error='$err'"))
        }(wait)

end ThreeEmbedBrowserTest

object ThreeEmbedBrowserTest:

    /** Probe page for leaves 1-2: calls `mountEmbedInspect`, which mounts `EmbedInspectScene` and
      * projects `window.embedInspect` plus `mountedAbsentBefore`/`sameHandleAsIdCopy`.
      */
    private[kyo] val inspectPage: String = probePage("kyo-threejs embed inspect", "mountEmbedInspect", "embedInspect")

    /** Probe page for leaf 3: calls `mountEmbedSiblingInspect`, which mounts `EmbedInspectScene` next to
      * a reactive counter sibling and projects `window.embedSibling` plus `mountStableAcrossRerender`.
      */
    private[kyo] val siblingPage: String = probePage("kyo-threejs embed sibling", "mountEmbedSiblingInspect", "embedSibling")

    /** Probe page for leaf 4: calls `mountEmbedInteractiveInspect`, which mounts `EmbedInspectScene` with
      * a HUD label and projects `window.embedInteractive` (the full `ThreeInspect` shape plus `close()`).
      */
    private[kyo] val interactivePage: String =
        probePage("kyo-threejs embed interactive", "mountEmbedInteractiveInspect", "embedInteractive")

    /** True once the HUD's frame count has climbed past zero, i.e. the live mount's `renders` signal is
      * driving a region of the server-rendered page.
      */
    private[kyo] val framesClimbed: String =
        "(function(){var e=document.querySelector('#frame-count');if(!e)return false;" +
            "var m=/Frames rendered: (\\d+)/.exec(e.textContent||'');return !!m && Number(m[1])>0;})()"

    /** True once the capture readout has reported a result of any kind (a real capture, or the
      * "Not mounted yet." a mount-less resolution reports); the assertion, not the wait, decides which.
      */
    private[kyo] val captureReported: String =
        "(function(){var e=document.querySelector('#capture-result');if(!e)return false;" +
            "var t=e.textContent||'';return t!=='Not captured yet.' && t!=='';})()"

    /** Parses the frame count out of the HUD's "Frames rendered: N" readout; -1 when it does not match. */
    private[kyo] def framesOf(text: String): Int =
        "Frames rendered: (\\d+)".r.findFirstMatchIn(text).map(_.group(1).toInt).getOrElse(-1)

    /** Parses the pixel count out of the HUD's "Captured N pixels." readout; -1 when it does not match. */
    private[kyo] def pixelsOf(text: String): Int =
        "Captured (\\d+) pixels".r.findFirstMatchIn(text).map(_.group(1).toInt).getOrElse(-1)

    /** Builds a probe page importing `entry` from `/main.js` and calling it with no argument (every
      * embed probe builds its own scene and canvas via `UI.runMount`).
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
           |<script type="module">
           |window.${errorName}Error = "";
           |try {
           |    const { $entry } = await import("/main.js");
           |    $entry();
           |} catch (e) {
           |    window.${errorName}Error = String(e && e.message ? e.message : e);
           |}
           |</script>
           |</body>
           |</html>""".stripMargin

end ThreeEmbedBrowserTest
