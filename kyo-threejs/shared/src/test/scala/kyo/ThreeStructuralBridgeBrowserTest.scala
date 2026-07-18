package kyo

import fixture.ClickBridgeScene
import fixture.ServerBridgeStructuralScene
import fixture.ServerBridgeStructuralScene.Item

/** The structural sibling of `ThreeBackendBridgeBrowserTest`: the FULL server-push bridge for a
  * KEYED foreach region, end to end, in a real browser. Serves `fixture.ServerBridgeStructuralScene.ui`
  * through the real `UI.runHandlers`, links the fixtures configuration's bundle so the client rebuilds
  * the IDENTICAL keyed set and hydrates via `kyo.ServerBridgeStructuralHydrate`
  * (`@JSExportTopLevel("hydrateStructuralBridge")`), then drives a server `Signal[Chunk[Item]]`
  * through a splice (drop one key, add another) and a pure reorder (same key set, different list
  * order), asserting both reach the client as ONE `ReplaceSubtree` op apiece over the SAME
  * `/_kyo/ws` socket used by the scalar bridge.
  *
  * Which keys are currently materialized, and where they sit, is read through `window.bridge.pixel(x, y)`
  * (the mounted `Three.Mount`'s public surface, `ThreeInspect.install`ed by `ServerBridgeStructuralHydrate`):
  * each `Item`'s `hex` color and world x are both fixed and distinct, so a left-to-right sweep across the
  * canvas center row yields the seeded colors in ascending screen-x order. That sequence pins both WHICH
  * keys are materialized and WHERE they sit relative to one another, so a key rendered at the wrong place
  * fails the assertion, without any scene-graph traversal. `window.replaceSubtreeCount` (a driver-visible counter
  * `ServerBridgeStructuralHydrate` wraps around the retained `window.__kyoBackends` handle) is the only
  * way to tell "the op was received and correctly produced no change" (a pure reorder) from "never sent".
  *
  * A third drive covers the case the other two structurally cannot: the key set holds still and a
  * key's VALUE moves, which is the shape every server-driven feed actually takes. It is asserted on
  * the pixels, because `replaceSubtreeCount` proves only that the op arrived, and a reconciler that
  * reuses a surviving key's live object verbatim receives the op and still renders a frozen scene.
  *
  * Also asserts the real-mount dispose-once/reuse behavior, same rationale as
  * `ThreeBackendBridgeBrowserTest`'s re-mount and teardown assertions (a live-only WebGL context
  * makes a Node-testable `ThreeBackendTest.scala` infeasible), as extra assertions on this SAME
  * mount: a dropped key disposes its GL resources EXACTLY once through the real
  * `ThreeBackend.replaceSubtree` wire path, and a key whose ITEM is unchanged, whether across the
  * splice or across a pure reorder, disposes NOTHING (`Reconciler.diffKeyed` reuses its live object).
  * The Reconciler-level `regionFor`/`foreachReplace`/`reactiveReplace` leaves in `ReconcilerTest`
  * stay as the permanent layer-appropriate carrier for the diff mechanics themselves; this test
  * proves the SAME mechanics fire correctly through the real wire.
  *
  * Also asserts the interaction half's raycast-click bridge: `fixture.ClickBridgeScene.ui` embeds two
  * canvases, one with a `foreach`-keyed clickable cube and one with a `render`/`when` clickable cube,
  * and a real `Browser.click` on each resolves SERVER-SIDE through `Three.resolvePointer`'s
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
                                _               <- Browser.goto(url)
                                _               <- Browser.waitFor("window.bridge !== undefined").handle(diagnoseStructuralBridgeTimeout)
                                initialColors   <- presentColors
                                initialDisposes <- Browser.evalInt("window.disposeCount || 0")
                                // Round 1: SPLICE -- drop key "b" (green), add key "d" (yellow). "a" (red) and
                                // "c" (blue) survive unchanged.
                                _ <- items.set(Chunk(
                                    Item("a", -2.0, 0xff0000),
                                    Item("c", 2.0, 0x0000ff),
                                    Item("d", 4.0, 0xffff00)
                                ))
                                _ <- Browser.waitFor("window.replaceSubtreeCount === 1").handle(diagnoseStructuralOpTimeout(1))
                                colorsAfterSplice   <- presentColors
                                disposesAfterSplice <- Browser.evalInt("window.disposeCount || 0")
                                // Round 2: PURE REORDER -- same 3 keys (a, c, d) as after the splice, list order
                                // shuffled, no field changes. Observationally silent on color set/dispose count
                                // (diffKeyed reuses every key verbatim); window.replaceSubtreeCount is the only
                                // way to tell "the op was received and correctly produced no change" from
                                // "never sent".
                                _ <- items.set(Chunk(
                                    Item("d", 4.0, 0xffff00),
                                    Item("a", -2.0, 0xff0000),
                                    Item("c", 2.0, 0x0000ff)
                                ))
                                _ <- Browser.waitFor("window.replaceSubtreeCount === 2").handle(diagnoseStructuralOpTimeout(2))
                                colorsAfterReorder   <- presentColors
                                disposesAfterReorder <- Browser.evalInt("window.disposeCount || 0")
                                tuple: (Chunk[String], Int, Chunk[String], Int, Chunk[String], Int) =
                                    (
                                        initialColors,
                                        initialDisposes,
                                        colorsAfterSplice,
                                        disposesAfterSplice,
                                        colorsAfterReorder,
                                        disposesAfterReorder
                                    )
                            yield tuple
                        }
                    }
                }
            yield
                val (
                    initialColors,
                    initialDisposes,
                    colorsAfterSplice,
                    disposesAfterSplice,
                    colorsAfterReorder,
                    disposesAfterReorder
                ) =
                    result
                assert(
                    initialColors == Chunk("red", "green", "blue"),
                    s"the SSR-hydrated keyed set must start as the seed a=red,b=green,c=blue in ascending x order, " +
                        s"got $initialColors"
                )
                assert(initialDisposes == 0, s"no key is dropped before any drive; disposeCount must start at 0, got $initialDisposes")
                assert(
                    colorsAfterSplice == Chunk("red", "blue", "yellow"),
                    s"dropping key 'b' (green) and adding key 'd' (yellow) must leave exactly a/c/d present " +
                        s"(structural discovery), got $colorsAfterSplice"
                )
                assert(
                    disposesAfterSplice == 1,
                    s"the ONE dropped key ('b') must dispose its GL resources exactly once through the real wire, " +
                        s"got disposeCount=$disposesAfterSplice"
                )
                assert(
                    colorsAfterReorder == Chunk("red", "blue", "yellow"),
                    s"a pure reorder of the SAME key set leaves each key at its own x, so the left-to-right order " +
                        s"must be unchanged, got $colorsAfterReorder"
                )
                assert(
                    disposesAfterReorder == 1,
                    s"a pure reorder must dispose NOTHING (every key is reused verbatim, Reconciler.diffKeyed), " +
                        s"got disposeCount=$disposesAfterReorder (was $disposesAfterSplice before the reorder)"
                )
        }
    }

    "a server-driven VALUE change on a surviving key repaints that key's cube" in {
        cancelOnUnsupportedPlatform {
            for
                items <- Signal.initRef(ServerBridgeStructuralScene.seedItems)
                result <- servedStructural(items) { url =>
                    swiftshaderLaunch.map { launch =>
                        Browser.run(launch) {
                            for
                                _             <- Browser.goto(url)
                                _             <- Browser.waitFor("window.bridge !== undefined").handle(diagnoseStructuralBridgeTimeout)
                                initialColors <- presentColors
                                // The KEY SET is untouched: a, b and c all survive. Only key "a"'s VALUE moves
                                // (red -> yellow). This is the shape EVERY server-driven feed takes: a metric's
                                // value changes while its identity stays put. A splice or a reorder (the leaf
                                // above) never exercises it, so a reconciler that reuses a surviving key's live
                                // object VERBATIM passes that leaf and still renders a frozen scene here.
                                _ <- items.set(Chunk(
                                    Item("a", -2.0, 0xffff00),
                                    Item("b", 0.0, 0x00ff00),
                                    Item("c", 2.0, 0x0000ff)
                                ))
                                _ <- Browser.waitFor("window.replaceSubtreeCount === 1").handle(diagnoseStructuralOpTimeout(1))
                                colorsAfterValueChange <- presentColors
                                tuple: (Chunk[String], Chunk[String]) = (initialColors, colorsAfterValueChange)
                            yield tuple
                        }
                    }
                }
            yield
                val (initialColors, colorsAfterValueChange) = result
                assert(
                    initialColors == Chunk("red", "green", "blue"),
                    s"the SSR-hydrated keyed set must start as the seed a=red,b=green,c=blue in ascending x order, " +
                        s"got $initialColors"
                )
                assert(
                    colorsAfterValueChange == Chunk("yellow", "green", "blue"),
                    s"key 'a' survived carrying a NEW value (red -> yellow), so the cube it owns must repaint to " +
                        s"that value. The op demonstrably ARRIVED (replaceSubtreeCount reached 1), so a stale colour " +
                        s"here means the reconciler kept the prior subtree for a surviving key and the scene now " +
                        s"disagrees with the snapshot the server sent, got $colorsAfterValueChange"
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
                                _            <- Browser.waitFor("window.bridgeReady === true").handle(diagnoseClickBridgeTimeout)
                                initialLabel <- Browser.eval("""String(document.querySelector('#clicked-label')?.textContent || '')""")
                                // A real click on the foreach-keyed canvas raycasts client-side and posts a
                                // BackendEvent the server resolves via resolvePointer's Ast.Foreach arm.
                                _            <- Browser.click(Browser.Selector.id("stage-foreach"))
                                _            <- Browser.assertText(Browser.Selector.id("clicked-label"), "foreach-hit")
                                afterForeach <- Browser.eval("""String(document.querySelector('#clicked-label')?.textContent || '')""")
                                // A real click on the render/when canvas raycasts client-side and posts a
                                // BackendEvent the server resolves via resolvePointer's Ast.Reactive arm (the
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

    /** Sweeps the mounted canvas's center row via `window.bridge.pixel(x, y)` and returns the set of
      * seeded colors ("red"/"green"/"blue"/"yellow", matching `ServerBridgeStructuralScene.Item.hex`)
      * found among the samples: the structural discovery technique this leaf uses in place of a raw
      * scene-graph position read.
      */
    private def presentColors(using Frame): Chunk[String] < (Browser & Abort[BrowserReadException]) =
        // Browser.eval does NOT await a returned Promise (only kyo.internal.BrowserEval.evalJsAwaiting
        // does); pixel() is async, so the async IIFE below must be read through the awaiting variant.
        //
        // The sweep runs left to right and records each colour at its FIRST appearance, so the result is
        // the colours in ascending screen-x order. Each Item's colour and world x are both fixed, so that
        // sequence pins WHICH keys are materialized AND WHERE they sit relative to one another: a set
        // would prove only that a key rendered somewhere on the row.
        kyo.internal.BrowserEval.evalJsAwaiting(
            """(async () => {
              |    const c = document.querySelector('#stage');
              |    const y = Math.floor(c.height / 2);
              |    const order = [];
              |    for (let i = 0; i <= 24; i++) {
              |        const x = Math.floor((c.width - 1) * i / 24);
              |        const px = await window.bridge.pixel(x, y);
              |        let hit = null;
              |        if (px[0] > 150 && px[1] < 80 && px[2] < 80) hit = 'red';
              |        else if (px[1] > 150 && px[0] < 80 && px[2] < 80) hit = 'green';
              |        else if (px[2] > 150 && px[0] < 80 && px[1] < 80) hit = 'blue';
              |        else if (px[0] > 150 && px[1] > 150 && px[2] < 80) hit = 'yellow';
              |        if (hit !== null && !order.includes(hit)) order.push(hit);
              |    }
              |    return order.join(',');
              |})()""".stripMargin
        ).map(s => if s.isEmpty then Chunk.empty else Chunk.from(s.split(",")))

    /** Serves the SSR page for `fixture.ClickBridgeScene.ui(lastClicked)` through the real
      * `UI.runHandlers`, alongside the linked fixtures bundle and the three.js sources its import map
      * resolves, and hands the served URL to `f`. Mirrors `servedStructural`, pointed at the
      * click-bridge hydrate export instead.
      */
    private def servedClick[A](lastClicked: SignalRef[String])(
        f: String => A < (Async & Scope & Abort[BrowserException])
    )(using Frame): A < (Async & Scope & Abort[BrowserException]) =
        for
            // The fixtures bundle is ONE ES module graph carrying every probe (including ones that import
            // OrbitControls/GLTFLoader/etc. at module scope); the whole import map must resolve even
            // though ClickBridgeHydrate itself uses none of those, mirroring servedStructural/servedBridge.
            bundle    <- WebGLSceneHarness.readFixtureBundle
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
            for ready <- Browser.eval("String(window.bridgeReady)")
            yield Abort.fail(BrowserScriptErrorException(s"click bridge hydrate ready flag never set: bridgeReady=$ready"))
        }(wait)

    /** Serves the SSR page for `fixture.ServerBridgeStructuralScene.ui(items)` through the real
      * `UI.runHandlers`, alongside the linked fixtures bundle and the three.js sources its import map
      * resolves, and hands the served URL to `f`. Mirrors `ThreeBackendBridgeBrowserTest.servedBridge`,
      * pointed at the structural hydrate export instead.
      */
    private def servedStructural[A](items: SignalRef[Chunk[Item]])(
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

    /** On a bridge-ready timeout, surfaces the diagnostic state as part of the failure. */
    private def diagnoseStructuralBridgeTimeout(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for installed <- Browser.eval("String(window.bridge !== undefined)")
            yield Abort.fail(BrowserScriptErrorException(s"structural bridge hydrate never installed its projection: installed=$installed"))
        }(wait)

    /** On a `replaceSubtreeCount` timeout, surfaces the counter's actual value and the current
      * dispose count, so a failure names what the client actually observed rather than just "timed out".
      */
    private def diagnoseStructuralOpTimeout(expected: Int)(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for
                count    <- Browser.eval("String(window.replaceSubtreeCount)")
                disposes <- Browser.eval("String(window.disposeCount)")
            yield Abort.fail(BrowserScriptErrorException(
                s"replaceSubtreeCount never reached $expected: replaceSubtreeCount=$count disposeCount=$disposes"
            ))
        }(wait)

end ThreeStructuralBridgeBrowserTest
