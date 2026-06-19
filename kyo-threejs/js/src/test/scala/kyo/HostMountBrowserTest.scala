package kyo

/** Browser proof that `UI.host` mounts fire through kyo-ui's `DomBackend.fireHostMounts` seam on
  * real elements and that the host element identity is preserved across sibling reactive re-renders.
  *
  * Loads the compiled demo bundle and calls `mountHostProbe`, which mounts a pure kyo-ui tree
  * containing a `UI.host("div")` alongside a reactive sibling span. The mount callback stamps the
  * host element with `data-mounted="1"` and increments `window.__hostMountCount`. An externally
  * driven `window.__setHostCount(n)` function forces sibling re-renders without touching the host.
  *
  * Leaf 1 asserts the mount callback fires exactly once and the stamp is present. Leaf 2 drives
  * three sibling counter emissions and asserts the host element's `data-mounted` attribute is still
  * present (the element was not replaced or re-mounted).
  *
  * Runs in a real headless Chrome via CDP; cancels (skips) where no Chrome can be downloaded. The
  * assertions read actual DOM attributes set by the compiled kyo-ui path; nothing is faked.
  */
class HostMountBrowserTest extends WebGLSceneHarness:

    "the host mount callback fires exactly once and stamps the element" in {
        cancelOnUnsupportedPlatform {
            servedProbe { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _     <- Browser.goto(url)
                            _     <- awaitProbeError
                            _     <- Browser.waitFor("window.__hostReady === true").handle(diagnoseOnTimeout)
                            count <- Browser.eval("String(window.__hostMountCount)")
                            attr <-
                                Browser.eval("""String(document.querySelector('#host-stage')?.getAttribute('data-mounted') || 'missing')""")
                        yield
                            assert(count == "1", s"host mount callback must fire exactly once, got __hostMountCount=$count")
                            assert(attr == "1", s"host element must carry data-mounted=1 after mount, got '$attr'")
                    }
                }
            }
        }
    }

    "the host element survives sibling reactive re-renders unchanged" in {
        cancelOnUnsupportedPlatform {
            servedProbe { url =>
                swiftshaderLaunch.map { launch =>
                    Browser.run(launch) {
                        for
                            _     <- Browser.goto(url)
                            _     <- awaitProbeError
                            _     <- Browser.waitFor("window.__hostReady === true").handle(diagnoseOnTimeout)
                            _     <- Browser.eval("window.__setHostCount(1)")
                            _     <- Browser.waitFor("""document.querySelector('#host-count')?.textContent === '1'""")
                            _     <- Browser.eval("window.__setHostCount(2)")
                            _     <- Browser.waitFor("""document.querySelector('#host-count')?.textContent === '2'""")
                            _     <- Browser.eval("window.__setHostCount(3)")
                            _     <- Browser.waitFor("""document.querySelector('#host-count')?.textContent === '3'""")
                            count <- Browser.eval("String(window.__hostMountCount)")
                            attr <-
                                Browser.eval("""String(document.querySelector('#host-stage')?.getAttribute('data-mounted') || 'missing')""")
                        yield
                            assert(
                                count == "1",
                                s"host mount callback must still be 1 after sibling re-renders, got __hostMountCount=$count"
                            )
                            assert(attr == "1", s"host element data-mounted must survive sibling re-renders, got '$attr'")
                    }
                }
            }
        }
    }

    /** Reads the demo bundle, three.js build, and jsm modules, and serves them with the host probe
      * page over HTTP.
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
                WebGLSceneHarness.htmlHandler(HostMountBrowserTest.page),
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
        Browser.eval("window.__hostError || ''").map { err =>
            if err.nonEmpty then Abort.fail(BrowserScriptErrorException(err))
            else ()
        }

    /** On a ready-flag timeout, attaches the page's diagnostic state so the cause is visible. */
    private def diagnoseOnTimeout(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for
                err      <- Browser.eval("String(window.__hostError)")
                count    <- Browser.eval("String(window.__hostMountCount)")
                ready    <- Browser.eval("String(window.__hostReady)")
                bodyHtml <- Browser.eval("document.body.innerHTML.slice(0, 400)")
                path0    <- Browser.eval("String(document.querySelector('[data-kyo-path=\"0\"]')?.tagName || 'NOT_FOUND')")
            yield Abort.fail(BrowserScriptErrorException(
                s"host probe ready flag never set: hostError='$err' hostMountCount=$count hostReady=$ready bodyHtml='$bodyHtml' path0tag=$path0"
            ))
        }(wait)

end HostMountBrowserTest

object HostMountBrowserTest:

    /** The probe page: an import map for `three`, and the `mountHostProbe` ES-module call. The probe
      * drives a pure kyo-ui DOM mount into `document.body` and records `window.__hostReady` and
      * `window.__hostMountCount`; the page only catches a mount throw.
      */
    private[kyo] val page: String =
        """<!doctype html>
          |<html>
          |<head><meta charset="utf-8"><title>kyo-ui host mount probe</title>
          |<script type="importmap">
          |{ "imports": {
          |    "three": "/three.module.js",
          |    "three/examples/jsm/loaders/GLTFLoader.js": "/three/examples/jsm/loaders/GLTFLoader.js"
          |} }
          |</script>
          |</head>
          |<body>
          |<script type="module">
          |window.__hostMountCount = 0;
          |window.__hostReady = false;
          |window.__hostError = "";
          |try {
          |    const { mountHostProbe } = await import("/main.js");
          |    mountHostProbe();
          |} catch (e) {
          |    window.__hostError = String(e && e.message ? e.message : e);
          |}
          |</script>
          |</body>
          |</html>""".stripMargin

end HostMountBrowserTest
