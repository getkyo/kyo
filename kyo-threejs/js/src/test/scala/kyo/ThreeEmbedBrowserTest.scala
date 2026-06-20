package kyo

/** Browser proof that `Three.embed` correctly integrates the kyo-threejs pipeline into a kyo-ui DOM
  * tree: the embedded canvas renders a non-blank frame, the WebGL context is released when the
  * containing Scope closes, the mount fires exactly once, a shared `SignalRef[String]` is writable
  * from outside the effect system and observed by the kyo-ui reactive label, and the host canvas
  * element identity is preserved across sibling reactive re-renders.
  *
  * Every leaf exercises the real surface: `Three.embed` returns a `UI.Ast.Host` that
  * `DomBackend.fireHostMounts` drives, running through the actual compiled Scala.js bundle linking
  * `ThreeMount.scala` and `DomBackend.scala`; nothing is hand-written JavaScript.
  *
  * Runs in a real software-WebGL Chrome via CDP; cancels (skips) where no Chrome can be downloaded.
  */
class ThreeEmbedBrowserTest extends WebGLSceneHarness:

    "the embedded Three scene renders a non-blank frame" in {
        cancelOnUnsupportedPlatform {
            servedEmbedProbe { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _     <- Browser.goto(url)
                            _     <- awaitEmbedError
                            _     <- Browser.waitFor("window.__embedReady === true").handle(diagnoseEmbedTimeout)
                            frame <- Browser.eval("String(window.__embedFrame)")
                            px    <- Browser.eval("String(window.__embedPixelCount)")
                        yield
                            assert(frame == "true", s"embedFrame flag must be true after first frame, got __embedFrame=$frame")
                            assert(
                                px.toIntOption.exists(_ >= 2),
                                s"embedded canvas must have >= 2 distinct pixel colors, got distinctPixels=$px"
                            )
                    }
                }
            }
        }
    }

    "the embedded WebGL context is released when the containing Scope closes" in {
        cancelOnUnsupportedPlatform {
            servedEmbedProbe { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _    <- Browser.goto(url)
                            _    <- awaitEmbedError
                            _    <- Browser.waitFor("window.__embedReady === true").handle(diagnoseEmbedTimeout)
                            lost <- Browser.eval("String(window.__embedContextLost)")
                        yield assert(
                            lost == "true",
                            s"embedded GL context must be lost after scope close, got __embedContextLost=$lost"
                        )
                    }
                }
            }
        }
    }

    "the embed mount callback fires exactly once" in {
        cancelOnUnsupportedPlatform {
            servedEmbedProbe { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _     <- Browser.goto(url)
                            _     <- awaitEmbedError
                            _     <- Browser.waitFor("window.__embedReady === true").handle(diagnoseEmbedTimeout)
                            count <- Browser.eval("String(window.__embedMountCount)")
                        yield assert(
                            count == "1",
                            s"embed mount must fire exactly once, got __embedMountCount=$count"
                        )
                    }
                }
            }
        }
    }

    "writing a shared SignalRef from outside the effect system updates the kyo-ui reactive label" in {
        cancelOnUnsupportedPlatform {
            servedInteractiveProbe { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _ <- Browser.goto(url)
                            _ <- awaitInteractiveError
                            _ <- Browser.waitFor("window.__interactiveReady === true").handle(diagnoseInteractiveTimeout)
                            // Wait for kyo-ui to mount the DOM tree (the ready flag fires before Fiber.init runs).
                            _ <- Browser.waitFor("document.querySelector('#selected-label') !== null")
                            // Exercise the signal-write -> label direction via the external setter.
                            _      <- Browser.eval("""window.__setSelected("Earth")""")
                            _      <- Browser.waitFor("""document.querySelector('#selected-label')?.textContent?.includes('Earth')""")
                            label1 <- Browser.eval("""String(document.querySelector('#selected-label')?.textContent || 'missing')""")
                            _      <- Browser.eval("""window.__setSelected("Sun")""")
                            _      <- Browser.waitFor("""document.querySelector('#selected-label')?.textContent?.includes('Sun')""")
                            label2 <- Browser.eval("""String(document.querySelector('#selected-label')?.textContent || 'missing')""")
                            // Exercise the button-click -> signal -> label direction: the #focus-sun button's
                            // onClick writes "Sun" through kyo-ui's event delegation, proving the bootstrap's
                            // event handling is live. First set the signal to "Earth" so a change is observable.
                            _      <- Browser.eval("""window.__setSelected("Earth")""")
                            _      <- Browser.waitFor("""document.querySelector('#selected-label')?.textContent?.includes('Earth')""")
                            _      <- Browser.click(Browser.Selector.id("focus-sun"))
                            _      <- Browser.waitFor("""document.querySelector('#selected-label')?.textContent?.includes('Sun')""")
                            label3 <- Browser.eval("""String(document.querySelector('#selected-label')?.textContent || 'missing')""")
                        yield
                            assert(label1.contains("Earth"), s"selected-label must contain 'Earth' after setSelected(Earth), got '$label1'")
                            assert(label2.contains("Sun"), s"selected-label must contain 'Sun' after setSelected(Sun), got '$label2'")
                            assert(
                                label3.contains("Sun"),
                                s"selected-label must contain 'Sun' after clicking #focus-sun, got '$label3'"
                            )
                    }
                }
            }
        }
    }

    "the embed canvas element identity is preserved across sibling reactive re-renders" in {
        cancelOnUnsupportedPlatform {
            servedSiblingProbe { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _ <- Browser.goto(url)
                            _ <- awaitSiblingError
                            _ <- Browser.waitFor("window.__siblingReady === true").handle(diagnoseSiblingTimeout)
                            _ <- Browser.eval("window.__setEmbedCount(1)")
                            _ <- Browser.waitFor("""document.querySelector('#count')?.textContent === '1'""")
                            _ <- Browser.eval("window.__setEmbedCount(2)")
                            _ <- Browser.waitFor("""document.querySelector('#count')?.textContent === '2'""")
                            _ <- Browser.eval("window.__setEmbedCount(3)")
                            _ <- Browser.waitFor("""document.querySelector('#count')?.textContent === '3'""")
                            token <-
                                Browser.eval("""String(document.querySelector('#stage')?.getAttribute('data-host-token') || 'missing')""")
                            // Assert GL context identity: the const-host canvas is never re-created, so the
                            // captured GL context's unique marker must survive every sibling re-render.
                            glStable <- Browser.eval("String(window.__siblingGl?.__kyoSiblingMark === true)")
                        yield
                            assert(
                                token == "1",
                                s"embed canvas data-host-token must survive sibling re-renders, got '$token'"
                            )
                            assert(
                                glStable == "true",
                                s"GL context must be the same object after sibling re-renders (__kyoSiblingMark must be true), got '$glStable'"
                            )
                    }
                }
            }
        }
    }

    /** Reads the demo bundle and three.js files, and serves them with the `mountEmbedProbe` page. */
    private def servedEmbedProbe[A](
        f: String => A < (Async & Scope & Abort[BrowserException])
    )(using Frame): A < (Async & Scope & Abort[BrowserException]) =
        servedWithPage(ThreeEmbedBrowserTest.embedProbePage)(f)

    /** Reads the demo bundle and three.js files, and serves them with the `mountEmbedInteractive` page. */
    private def servedInteractiveProbe[A](
        f: String => A < (Async & Scope & Abort[BrowserException])
    )(using Frame): A < (Async & Scope & Abort[BrowserException]) =
        servedWithPage(ThreeEmbedBrowserTest.interactiveProbePage)(f)

    /** Reads the demo bundle and three.js files, and serves them with the `mountEmbedSiblingProbe` page. */
    private def servedSiblingProbe[A](
        f: String => A < (Async & Scope & Abort[BrowserException])
    )(using Frame): A < (Async & Scope & Abort[BrowserException]) =
        servedWithPage(ThreeEmbedBrowserTest.siblingProbePage)(f)

    /** Common HTTP server setup shared by all three probe page variants. */
    private def servedWithPage[A](html: String)(
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

    /** Surfaces a page-side embed probe error as a test failure. */
    private def awaitEmbedError(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.eval("window.__embedError || ''").map { err =>
            if err.nonEmpty then Abort.fail(BrowserScriptErrorException(err))
            else ()
        }

    /** On an embed probe ready-flag timeout, attaches diagnostic state. */
    private def diagnoseEmbedTimeout(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for
                err   <- Browser.eval("String(window.__embedError)")
                frame <- Browser.eval("String(window.__embedFrame)")
                ready <- Browser.eval("String(window.__embedReady)")
            yield Abort.fail(BrowserScriptErrorException(
                s"embed probe ready flag never set: embedError='$err' embedFrame=$frame embedReady=$ready"
            ))
        }(wait)

    /** Surfaces a page-side interactive probe error as a test failure. */
    private def awaitInteractiveError(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.eval("window.__interactiveError || ''").map { err =>
            if err.nonEmpty then Abort.fail(BrowserScriptErrorException(err))
            else ()
        }

    /** On an interactive probe ready-flag timeout, attaches diagnostic state. */
    private def diagnoseInteractiveTimeout(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for
                err   <- Browser.eval("String(window.__interactiveError)")
                ready <- Browser.eval("String(window.__interactiveReady)")
            yield Abort.fail(BrowserScriptErrorException(
                s"interactive probe ready flag never set: interactiveError='$err' interactiveReady=$ready"
            ))
        }(wait)

    /** Surfaces a page-side sibling probe error as a test failure. */
    private def awaitSiblingError(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.eval("window.__siblingError || ''").map { err =>
            if err.nonEmpty then Abort.fail(BrowserScriptErrorException(err))
            else ()
        }

    /** On a sibling probe ready-flag timeout, attaches diagnostic state. */
    private def diagnoseSiblingTimeout(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for
                err   <- Browser.eval("String(window.__siblingError)")
                ready <- Browser.eval("String(window.__siblingReady)")
            yield Abort.fail(BrowserScriptErrorException(
                s"sibling probe ready flag never set: siblingError='$err' siblingReady=$ready"
            ))
        }(wait)

end ThreeEmbedBrowserTest

object ThreeEmbedBrowserTest:

    /** Probe page for leaves 1-3: calls `mountEmbedProbe`, which mounts the embed tree, waits for one
      * frame, closes the containing Scope, and records `__embedFrame`, `__embedContextLost`,
      * `__embedMountCount`, and `__embedReady`.
      */
    private[kyo] val embedProbePage: String =
        """<!doctype html>
          |<html>
          |<head><meta charset="utf-8"><title>kyo-threejs embed probe</title>
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
          |window.__embedReady = false;
          |window.__embedContextLost = "false";
          |window.__embedFrame = false;
          |window.__embedMountCount = 0;
          |window.__embedPixelCount = 0;
          |window.__embedError = "";
          |try {
          |    const { mountEmbedProbe } = await import("/main.js");
          |    mountEmbedProbe();
          |} catch (e) {
          |    window.__embedError = String(e && e.message ? e.message : e);
          |}
          |</script>
          |</body>
          |</html>""".stripMargin

    /** Probe page for leaf 4: calls `mountEmbedInteractive`, which mounts the full `EmbeddedSceneScene.ui`
      * tree and exposes `window.__setSelected(name)` and `window.__getSelected()` for bidirectional
      * `SignalRef` interop. Raises `__interactiveReady` after setup.
      */
    private[kyo] val interactiveProbePage: String =
        """<!doctype html>
          |<html>
          |<head><meta charset="utf-8"><title>kyo-threejs embed interactive probe</title>
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
          |window.__interactiveReady = false;
          |window.__interactiveError = "";
          |try {
          |    const { mountEmbedInteractive } = await import("/main.js");
          |    mountEmbedInteractive();
          |} catch (e) {
          |    window.__interactiveError = String(e && e.message ? e.message : e);
          |}
          |</script>
          |</body>
          |</html>""".stripMargin

    /** Probe page for leaf 5: calls `mountEmbedSiblingProbe`, which mounts an embedded canvas next to
      * a reactive counter sibling. Exposes `window.__setEmbedCount(n)` to drive re-renders. Raises
      * `__siblingReady` when the host mount callback has fired.
      */
    private[kyo] val siblingProbePage: String =
        """<!doctype html>
          |<html>
          |<head><meta charset="utf-8"><title>kyo-threejs embed sibling probe</title>
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
          |window.__siblingReady = false;
          |window.__siblingError = "";
          |try {
          |    const { mountEmbedSiblingProbe } = await import("/main.js");
          |    mountEmbedSiblingProbe();
          |} catch (e) {
          |    window.__siblingError = String(e && e.message ? e.message : e);
          |}
          |</script>
          |</body>
          |</html>""".stripMargin

end ThreeEmbedBrowserTest
