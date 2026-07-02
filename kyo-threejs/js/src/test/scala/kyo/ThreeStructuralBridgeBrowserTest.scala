package kyo

import demo.ClickBridgeScene
import demo.ServerBridgeStructuralScene
import demo.ServerBridgeStructuralScene.Item

/** The structural sibling of `ThreeBackendBridgeBrowserTest`: the FULL server-push bridge for a
  * KEYED foreach region, end to end, in a real browser. Serves `demo.ServerBridgeStructuralScene.ui`
  * through the real `UI.runHandlers`, links the `kyo-threejs-demos` bundle so the client rebuilds
  * the IDENTICAL keyed set and hydrates via `kyo.ServerBridgeStructuralHydrate`
  * (`@JSExportTopLevel("hydrateStructuralBridge")`), then drives a server `Signal[Chunk[Item]]`
  * through a splice (drop one key, add another) and a pure reorder (same key set, different list
  * order), asserting both reach the client as ONE `ReplaceSubtree` op apiece over the SAME
  * `/_kyo/ws` socket used by the scalar bridge.
  *
  * Also asserts the real-mount dispose-once/reuse behavior, same rationale as
  * `ThreeBackendBridgeBrowserTest`'s re-mount and teardown assertions (a live-only WebGL context
  * makes a Node-testable `ThreeBackendTest.scala` infeasible), as extra assertions on this SAME
  * mount: a dropped key disposes its GL resources EXACTLY once through the real
  * `ThreeBackend.replaceSubtree` wire path, and a KEPT key, whether across the splice or across a
  * pure reorder that never adds or drops a key, disposes NOTHING (`Reconciler.diffKeyed` reuses its
  * live object verbatim). The Reconciler-level `regionFor`/`foreachReplace`/`reactiveReplace` leaves
  * in `ReconcilerTest` stay as the permanent layer-appropriate carrier for the diff mechanics
  * themselves; this test proves the SAME mechanics fire correctly through the real wire.
  *
  * Also asserts the interaction half's raycast-click bridge: `demo.ClickBridgeScene.ui` embeds two
  * canvases, one with a `foreach`-keyed clickable cube and one with a `render`/`when` clickable cube,
  * and a real `Browser.click` on each resolves SERVER-SIDE through `Three.resolveOnClick`'s
  * `Ast.Foreach` and `Ast.Reactive` arms respectively, reflecting into a shared DOM label over the
  * same `/_kyo/ws` socket the structural push uses.
  *
  * Runs in a real software-WebGL Chrome via CDP; cancels (skips) where no Chrome can be downloaded.
  */
class ThreeStructuralBridgeBrowserTest extends WebGLSceneHarness:

    "a server-driven keyed splice and a pure reorder both reach the client as one ReplaceSubtree apiece, with dispose-once/reuse" in {
        cancelOnUnsupportedPlatform {
            for
                items <- Signal.initRef(ServerBridgeStructuralScene.seedItems)
                result <- servedStructural(items) { url =>
                    swiftshaderLaunch.map { launch =>
                        Browser.run(launch) {
                            for
                                _ <- Browser.goto(url)
                                _ <- Browser.waitFor("window.__bridgeReady === true").handle(diagnoseStructuralBridgeTimeout)
                                initialPositions <- Browser.eval(
                                    "String(window.__stagePositions || ('ERR:' + (window.__stagePositionsError || 'none')))"
                                )
                                initialDisposes <- Browser.evalInt("window.__disposeCount || 0")
                                // Round 1: SPLICE -- drop key "b", add key "d". "a" and "c" survive unchanged.
                                _ <- items.set(Chunk(
                                    Item("a", -2.0, 0xff0000),
                                    Item("c", 2.0, 0x0000ff),
                                    Item("d", 4.0, 0xffff00)
                                ))
                                _ <- Browser.waitFor("window.__replaceSubtreeCount === 1").handle(diagnoseStructuralOpTimeout(1))
                                positionsAfterSplice <- Browser.eval("String(window.__stagePositions || '')")
                                disposesAfterSplice  <- Browser.evalInt("window.__disposeCount || 0")
                                // Round 2: PURE REORDER -- same 3 keys (a, c, d) as after the splice, list order
                                // shuffled, no field changes. Observationally silent on position/dispose (diffKeyed
                                // reuses every key verbatim); window.__replaceSubtreeCount is the only way to tell
                                // "the op was received and correctly produced no change" from "never sent".
                                _ <- items.set(Chunk(
                                    Item("d", 4.0, 0xffff00),
                                    Item("a", -2.0, 0xff0000),
                                    Item("c", 2.0, 0x0000ff)
                                ))
                                _ <- Browser.waitFor("window.__replaceSubtreeCount === 2").handle(diagnoseStructuralOpTimeout(2))
                                positionsAfterReorder <- Browser.eval("String(window.__stagePositions || '')")
                                disposesAfterReorder  <- Browser.evalInt("window.__disposeCount || 0")
                                tuple: (String, Int, String, Int, String, Int) =
                                    (
                                        initialPositions,
                                        initialDisposes,
                                        positionsAfterSplice,
                                        disposesAfterSplice,
                                        positionsAfterReorder,
                                        disposesAfterReorder
                                    )
                            yield tuple
                        }
                    }
                }
            yield
                val (
                    initialPositions,
                    initialDisposes,
                    positionsAfterSplice,
                    disposesAfterSplice,
                    positionsAfterReorder,
                    disposesAfterReorder
                ) =
                    result
                assert(
                    initialPositions == "[-2,0,2]",
                    s"the SSR-hydrated keyed set must start as the seed [a,b,c] at x=[-2,0,2], got '$initialPositions'"
                )
                assert(initialDisposes == 0, s"no key is dropped before any drive; disposeCount must start at 0, got $initialDisposes")
                assert(
                    positionsAfterSplice == "[-2,2,4]",
                    s"dropping key 'b' and adding key 'd' must leave exactly a/c/d's positions [-2,2,4] (structural " +
                        s"discovery), got '$positionsAfterSplice'"
                )
                assert(
                    disposesAfterSplice == 1,
                    s"the ONE dropped key ('b') must dispose its GL resources exactly once through the real wire, " +
                        s"got disposeCount=$disposesAfterSplice"
                )
                assert(
                    positionsAfterReorder == "[-2,2,4]",
                    s"a pure reorder of the SAME key set must not change which positions are present, got '$positionsAfterReorder'"
                )
                assert(
                    disposesAfterReorder == 1,
                    s"a pure reorder must dispose NOTHING (every key is reused verbatim, Reconciler.diffKeyed), " +
                        s"got disposeCount=$disposesAfterReorder (was $disposesAfterSplice before the reorder)"
                )
        }
    }

    "a real raycast-click on a server-driven foreach child, and on server-driven render/when content, both resolve SERVER-SIDE and reach the client over the same /_kyo/ws socket" in {
        cancelOnUnsupportedPlatform {
            for
                lastClicked <- Signal.initRef("none")
                result <- servedClick(lastClicked) { url =>
                    swiftshaderLaunch.map { launch =>
                        Browser.run(launch) {
                            for
                                _            <- Browser.goto(url)
                                _            <- Browser.waitFor("window.__bridgeReady === true").handle(diagnoseClickBridgeTimeout)
                                initialLabel <- Browser.eval("""String(document.querySelector('#clicked-label')?.textContent || '')""")
                                // A real click on the foreach-keyed canvas raycasts client-side and posts a
                                // BackendEvent the server resolves via resolveOnClick's Ast.Foreach arm.
                                _            <- Browser.click(Browser.Selector.id("stage-foreach"))
                                _            <- Browser.assertText(Browser.Selector.id("clicked-label"), "foreach-hit")
                                afterForeach <- Browser.eval("""String(document.querySelector('#clicked-label')?.textContent || '')""")
                                // A real click on the render/when canvas raycasts client-side and posts a
                                // BackendEvent the server resolves via resolveOnClick's Ast.Reactive arm (the
                                // boundary's OWN relPath, no extra segment).
                                _             <- Browser.click(Browser.Selector.id("stage-reactive"))
                                _             <- Browser.assertText(Browser.Selector.id("clicked-label"), "render-hit")
                                afterReactive <- Browser.eval("""String(document.querySelector('#clicked-label')?.textContent || '')""")
                                tuple: (String, String, String) = (initialLabel, afterForeach, afterReactive)
                            yield tuple
                        }
                    }
                }
            yield
                val (initialLabel, afterForeach, afterReactive) = result
                assert(initialLabel == "none", s"the SSR-hydrated label must read 'none' before any click, got '$initialLabel'")
                assert(afterForeach == "foreach-hit", s"the foreach child's onClick must resolve server-side, got '$afterForeach'")
                assert(afterReactive == "render-hit", s"the render/when content's onClick must resolve server-side, got '$afterReactive'")
        }
    }

    /** Serves the SSR page for `demo.ClickBridgeScene.ui(lastClicked)` through the real
      * `UI.runHandlers`, alongside the linked demo bundle and the three.js sources its import map
      * resolves, and hands the served URL to `f`. Mirrors `servedStructural`, pointed at the
      * click-bridge hydrate export instead.
      */
    private def servedClick[A](lastClicked: SignalRef[String])(
        f: String => A < (Async & Scope & Abort[BrowserException])
    )(using Frame): A < (Async & Scope & Abort[BrowserException]) =
        for
            // The demos bundle is ONE ES module graph carrying every demo (including ones that import
            // OrbitControls/GLTFLoader/etc. at module scope); the whole import map must resolve even
            // though ClickBridgeHydrate itself uses none of those, mirroring servedStructural/servedBridge.
            bundle    <- WebGLSceneHarness.readDemoBundle
            module    <- WebGLSceneHarness.readThreeSource("three.module.js")
            core      <- WebGLSceneHarness.readThreeSource("three.core.js")
            gltf      <- WebGLSceneHarness.readThreeJsm("loaders/GLTFLoader.js")
            bufUtils  <- WebGLSceneHarness.readThreeJsm("utils/BufferGeometryUtils.js")
            skelUtils <- WebGLSceneHarness.readThreeJsm("utils/SkeletonUtils.js")
            orbit     <- WebGLSceneHarness.readThreeJsm("controls/OrbitControls.js")
            bootJs = """import { hydrateClickBridge } from "/main.js"; hydrateClickBridge();"""
            head = UI.PageHead(
                "kyo-threejs click bridge test",
                moduleScript = Present("/boot.js"),
                importMap = Seq(
                    "three"                                        -> "/three.module.js",
                    "three/examples/jsm/loaders/GLTFLoader.js"     -> "/three/examples/jsm/loaders/GLTFLoader.js",
                    "three/examples/jsm/controls/OrbitControls.js" -> "/three/examples/jsm/controls/OrbitControls.js"
                )
            )
            appHandlers <- UI.runHandlers("/", head)(ClickBridgeScene.ui(lastClicked))
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
    end servedClick

    /** On a click-bridge-ready timeout, surfaces the page-side error (if any) as part of the failure. */
    private def diagnoseClickBridgeTimeout(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for ready <- Browser.eval("String(window.__bridgeReady)")
            yield Abort.fail(BrowserScriptErrorException(s"click bridge hydrate ready flag never set: bridgeReady=$ready"))
        }(wait)

    /** Serves the SSR page for `demo.ServerBridgeStructuralScene.ui(items)` through the real
      * `UI.runHandlers`, alongside the linked demo bundle and the three.js sources its import map
      * resolves, and hands the served URL to `f`. Mirrors `ThreeBackendBridgeBrowserTest.servedBridge`,
      * pointed at the structural hydrate export instead.
      */
    private def servedStructural[A](items: SignalRef[Chunk[Item]])(
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
            bootJs = """import { hydrateStructuralBridge } from "/main.js"; hydrateStructuralBridge();"""
            head = UI.PageHead(
                "kyo-threejs structural bridge test",
                moduleScript = Present("/boot.js"),
                importMap = Seq(
                    "three"                                        -> "/three.module.js",
                    "three/examples/jsm/loaders/GLTFLoader.js"     -> "/three/examples/jsm/loaders/GLTFLoader.js",
                    "three/examples/jsm/controls/OrbitControls.js" -> "/three/examples/jsm/controls/OrbitControls.js"
                )
            )
            appHandlers <- UI.runHandlers("/", head)(ServerBridgeStructuralScene.ui(items))
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
    end servedStructural

    /** On a bridge-ready timeout, surfaces the page-side error (if any) as part of the failure. */
    private def diagnoseStructuralBridgeTimeout(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for ready <- Browser.eval("String(window.__bridgeReady)")
            yield Abort.fail(BrowserScriptErrorException(s"structural bridge hydrate ready flag never set: bridgeReady=$ready"))
        }(wait)

    /** On a `replaceSubtreeCount` timeout, surfaces the counter's actual value and the current
      * positions/dispose state, so a failure names what the client actually observed rather than
      * just "timed out".
      */
    private def diagnoseStructuralOpTimeout(expected: Int)(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for
                count     <- Browser.eval("String(window.__replaceSubtreeCount)")
                positions <- Browser.eval("String(window.__stagePositions)")
                disposes  <- Browser.eval("String(window.__disposeCount)")
            yield Abort.fail(BrowserScriptErrorException(
                s"replaceSubtreeCount never reached $expected: replaceSubtreeCount=$count positions=$positions disposeCount=$disposes"
            ))
        }(wait)

end ThreeStructuralBridgeBrowserTest
