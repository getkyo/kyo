package kyo

/** The FULL server-push bridge, end to end, in a real browser. Serves `demo.ServerBridgeScene.ui`
  * through the REAL `UI.runHandlers` (SSR page GET + `/_kyo/ws` route), links the
  * `kyo-threejs-demos` bundle so the client rebuilds the IDENTICAL tree and hydrates the embedded
  * `Three.embed` canvas via `DomBackend.hydrateBackendNodes` (`kyo.ServerBridgeHydrate`,
  * `@JSExportTopLevel("hydrateServerBridge")`), then drives BOTH a DOM-bound `Signal[String]` and a
  * `material.color`-bound `Signal[Three.Color]` from the SERVER side and asserts both reach the
  * client through the SAME ONE WebSocket. This is the central split the whole structural-reactivity
  * design rests on (`data-kyo-path` matches server and client BY CONSTRUCTION); a FAIL here routes
  * to design, not a workaround.
  *
  * Also asserts no re-mount on a reactive change, as an extra assertion on the SAME mount (a
  * live-only WebGL context makes a Node-testable `ThreeBackendTest.scala` infeasible): the mounted
  * `<canvas>` element's identity is captured once and re-checked by reference after N driven prop
  * changes, proving no re-mount ever replaced it.
  *
  * Finally asserts deterministic Scope-close teardown as a LAST step on the SAME mount:
  * `window.__closeBridge()` (`ServerBridgeHydrate`'s `Promise`-gated close trigger) ends the client
  * hydrate's `Scope.run`, running every production finalizer (`WebGLRenderer.dispose`/
  * `forceContextLoss`, the RAF loop's Scope-cancel, `ThreeBackend.mount`'s `unregisterJsHandle`), and
  * the test asserts the released GL context and the vanished `window.__kyoBackends` entry, driven
  * off the deterministic `window.__bridgeClosed` flag, never a sleep.
  *
  * Runs in a real software-WebGL Chrome via CDP; cancels (skips) where no Chrome can be downloaded.
  */
class ThreeBackendBridgeBrowserTest extends WebGLSceneHarness:

    "the server-pushed DOM label and the embedded cube's material.color both reach the client over the one /_kyo/ws socket, with no re-mount" in {
        cancelOnUnsupportedPlatform {
            for
                label <- Signal.initRef("initial")
                color <- Signal.initRef(Three.Color.red)
                result <- servedBridge(label, color) { url =>
                    swiftshaderLaunch.map { launch =>
                        Browser.run(launch) {
                            for
                                _ <- Browser.goto(url)
                                _ <- Browser.waitFor("window.__bridgeReady === true").handle(diagnoseBridgeTimeout)
                                _ <- Browser.eval(
                                    "window.__stageCanvasRef = document.querySelector('#stage')"
                                )
                                initialColor <- Browser.eval("String(window.__stageColor || '')")
                                initialLabel <- Browser.eval("""String(document.querySelector('#label')?.textContent || '')""")
                                // Drive 3 rounds of BOTH signals; the client's canvas identity must survive all of them
                                // (patch, never re-mount).
                                _ <- label.set("round-1")
                                _ <- color.set(Three.Color.green)
                                _ <- Browser.assertText(Browser.Selector.id("label"), "round-1")
                                _ <- Browser.waitFor("window.__stageColor === '00ff00'").handle { w =>
                                    Abort.recover[BrowserReadException] { _ =>
                                        Browser.eval("String(window.__stageColor)").map { current =>
                                            Abort.fail(
                                                BrowserScriptErrorException(s"round-1 color never observed: window.__stageColor=$current")
                                            )
                                        }
                                    }(w)
                                }
                                sameAfterRound1 <- Browser.eval("String(document.querySelector('#stage') === window.__stageCanvasRef)")
                                _               <- label.set("round-2")
                                _               <- color.set(Three.Color.blue)
                                _               <- Browser.assertText(Browser.Selector.id("label"), "round-2")
                                _               <- Browser.waitFor("window.__stageColor === '0000ff'")
                                sameAfterRound2 <- Browser.eval("String(document.querySelector('#stage') === window.__stageCanvasRef)")
                                finalColor      <- Browser.eval("String(window.__stageColor)")
                                canvasCount     <- Browser.evalInt("document.querySelectorAll('canvas').length")
                                // LAST on this SAME mount: close the bridge deterministically (a
                                // Promise trigger, never a sleep) and assert the production teardown ran.
                                _      <- Browser.eval("window.__closeBridge()")
                                _      <- Browser.waitFor("window.__bridgeClosed === true").handle(diagnoseBridgeCloseTimeout)
                                glLost <- Browser.eval("String(window.__stageGl && window.__stageGl.isContextLost())")
                                backendGoneAfterClose <- Browser.eval(
                                    "String(!window.__kyoBackends || window.__kyoBackends['1'] === undefined)"
                                )
                                tuple: (String, String, String, String, String, Int, String, String) =
                                    (
                                        initialColor,
                                        initialLabel,
                                        sameAfterRound1,
                                        sameAfterRound2,
                                        finalColor,
                                        canvasCount,
                                        glLost,
                                        backendGoneAfterClose
                                    )
                            yield tuple
                        }
                    }
                }
            yield
                val (initialColor, initialLabel, sameAfterRound1, sameAfterRound2, finalColor, canvasCount, glLost, backendGoneAfterClose) =
                    result
                assert(initialLabel == "initial", s"the SSR'd label must read 'initial' before any drive, got '$initialLabel'")
                assert(initialColor == "ff0000", s"the SSR-hydrated cube must start red (ff0000), got '$initialColor'")
                assert(finalColor == "0000ff", s"the cube must reach blue (0000ff) after the second driven color, got '$finalColor'")
                assert(
                    sameAfterRound1 == "true",
                    "the #stage canvas element identity must survive a driven prop change (patch, never re-mount)"
                )
                assert(
                    sameAfterRound2 == "true",
                    "the #stage canvas element identity must survive a second driven prop change (patch, never re-mount)"
                )
                assert(canvasCount == 1, s"exactly one canvas must ever exist for the one embed, got $canvasCount")
                assert(
                    glLost == "true",
                    s"closing the bridge Scope must release the WebGL context (WebGLRenderer.dispose/forceContextLoss), got glLost=$glLost"
                )
                assert(
                    backendGoneAfterClose == "true",
                    "closing the bridge Scope must remove the mount's window.__kyoBackends entry, so any later " +
                        "patch/replaceSubtree is a no-op (unregisterJsHandle)"
                )
        }
    }

    /** Serves the SSR page for `demo.ServerBridgeScene.ui(label, color)` through the real
      * `UI.runHandlers`, alongside the linked demo bundle and the three.js sources its import map
      * resolves, and hands the served URL to `f`.
      */
    private def servedBridge[A](label: SignalRef[String], color: SignalRef[Three.Color])(
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
            // PageHead.moduleScript only links a src, it never calls anything on load; the bundle's
            // hydrateServerBridge export needs an explicit caller. bootJs is a hand-written (not
            // Scala.js-compiled) ES module that imports the named export and invokes it, mirroring
            // ThreeEmbedBrowserTest's own inline "const { probe } = await import(...); probe()" idiom,
            // just served as an external file since PageHead has no inline-script field.
            bootJs = """import { hydrateServerBridge } from "/main.js"; hydrateServerBridge();"""
            head = UI.PageHead(
                "kyo-threejs bridge test",
                moduleScript = Present("/boot.js"),
                importMap = Seq(
                    "three"                                        -> "/three.module.js",
                    "three/examples/jsm/loaders/GLTFLoader.js"     -> "/three/examples/jsm/loaders/GLTFLoader.js",
                    "three/examples/jsm/controls/OrbitControls.js" -> "/three/examples/jsm/controls/OrbitControls.js"
                )
            )
            appHandlers <- UI.runHandlers("/", head)(demo.ServerBridgeScene.ui(label, color))
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
    end servedBridge

    /** On a bridge-ready timeout, surfaces the page-side error (if any) as part of the failure. */
    private def diagnoseBridgeTimeout(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for ready <- Browser.eval("String(window.__bridgeReady)")
            yield Abort.fail(BrowserScriptErrorException(s"bridge hydrate ready flag never set: bridgeReady=$ready"))
        }(wait)

    /** On a bridge-closed timeout (the teardown never completed), surfaces the flag's actual value. */
    private def diagnoseBridgeCloseTimeout(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for closed <- Browser.eval("String(window.__bridgeClosed)")
            yield Abort.fail(BrowserScriptErrorException(s"bridge close teardown never completed: bridgeClosed=$closed"))
        }(wait)

end ThreeBackendBridgeBrowserTest
