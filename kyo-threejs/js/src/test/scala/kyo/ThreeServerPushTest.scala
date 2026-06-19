package kyo

import demo.DemoServe
import demo.EmbeddedSceneScene
import demo.ServerClockScene
import demo.ServerStructureScene
import demo.Snake3DScene

/** Browser proof of the kyo-threejs + kyo-ui server-push path end to end, in a real headed Chrome
  * with software WebGL. This is the browser-driven acceptance of the server to client path: a
  * server-owned signal changes, and the change reaches the rendered 3D scene in a real browser over
  * the WebSocket, with no client-side animation loop driving it.
  *
  * Each scenario serves a real self-serving demo in process: `UI.runHandlers("/", DemoServe.head)`
  * over `HttpServer.init`, with `DemoServe.islandHandler` serving the linked `kyoThreeIsland` bundle.
  * The served page carries three browser-environment shims a real browser deployment of the ESModule
  * island must also provide: an import map resolving the bundle's bare `three` import to the served
  * three.js build, a bootstrap module that imports and calls `kyoThreeIsland()` (the bundle exports
  * the entry but does not self-run it), and a `getContext` patch forcing `preserveDrawingBuffer` so
  * the external pixel readback can sample the production renderer's framebuffer after a frame. None of
  * the shims touch a product code path; they reconstruct the browser environment the island expects.
  *
  * Scenario 1 drives [[ServerClockScene]], whose tick is advanced by a SERVER-SIDE fiber (not a client
  * `onFrame`) and bound to a cube's color and rotation:
  *   1. the embedded canvas renders a non-blank frame;
  *   2. the HUD `#tick-label` integer increments on its own with no user input, proving the server is
  *      driving the page;
  *   3. the rendered canvas center pixel differs between two server ticks, proving the server clock
  *      repaints the 3D scene over the WebSocket.
  *
  * Scenario 2 drives [[ServerStructureScene]], whose cube set is a server-owned keyed id list bound
  * to both a `foreachKeyed` 3D region and a kyo-ui HUD over one WebSocket. The scene mixes two static
  * lights with the foreach region, so the boot preserves the lights (a foreach flattens to an empty
  * holder placeholder, not an empty scene) and the cubes arrive over the structural channel and render
  * LIT. A server-side button onClick appends an id (Add) or flips the order (Reverse). The test asserts
  * the rendered 3D canvas as the oracle: the cubes render a non-blank frame, the Add splices a new cube
  * (the canvas signature changes), and the Reverse slides the keyed cubes to their new slots (the canvas
  * signature changes again). The HUD `#ids-label` echo, driven by the same `ids` signal over the same
  * WebSocket, corroborates the server-owned list value.
  *
  * Scenario 3 drives [[EmbeddedSceneScene]] (a lit sun/earth scene with per-mesh server-side onClick
  * closures and a `#selected-label` HUD) to prove the client to server interactivity leg: it dispatches
  * a raycast pick over the WebSocket exactly as the island's pointer handler does (a `HostPick` for the
  * earth, then the sun mesh node id), and asserts each server-side onClick ran by the HUD label flipping
  * to "Earth" then back to "Sun" over the same WebSocket.
  *
  * Scenario 4 boots the shipped [[Snake3DScene]] over server-push: a scene mixing two static lights, a
  * `foreachKeyed` body region, a `reactive` food region, and a static ticker at the root. It asserts the
  * mixed scene boots and renders a non-blank lit frame, proving the boot preserves the lights and both
  * reactive regions stream their children in over the structural channel.
  *
  * Screenshots are captured at key moments and written to `runs/visual-review/serverclock-*.png`,
  * `serverstructure-*.png`, `embedpick-*.png`, and `snake3d-*.png` for human review; the assertions, not
  * the screenshots, decide the test.
  *
  * Runs in a real software-WebGL Chrome via CDP; cancels (skips) where no Chrome can be downloaded.
  * Every assertion observes real DOM/pixel state produced by the compiled server-push path; nothing
  * is faked.
  */
class ThreeServerPushTest extends WebGLSceneHarness:

    import ThreeServerPushTest.*

    "the server clock drives the 3D scene over the WebSocket with no client animation loop" in {
        cancelOnUnsupportedPlatform {
            ServerClockScene.ui.map { ui =>
                servedDemo(ui) { url =>
                    headedSwiftshaderLaunch.map { launch =>
                        Browser.run(launch) {
                            for
                                _ <- Browser.goto(url)
                                _ <- awaitIslandError
                                _ <- Browser.waitFor("window.__kyoHostChannels && Object.keys(window.__kyoHostChannels).length > 0")
                                    .handle(diagnoseTimeout)
                                _ <- Browser.waitFor("document.querySelector('[data-kyo-host]') !== null").handle(diagnoseTimeout)
                                _ <- Browser.eval(samplerJs)
                                // Leg 1: a non-blank first frame.
                                _        <- Browser.waitFor(s"window.__readDistinct() >= $renderThreshold").handle(diagnoseTimeout)
                                distinct <- Browser.evalInt("window.__readDistinct()")
                                // Leg 2: read the server tick, capture the canvas center, screenshot.
                                _       <- Browser.waitFor(tickValueJs + " >= 0").handle(diagnoseTimeout)
                                tick0   <- Browser.evalInt(tickValueJs)
                                center0 <- Browser.eval("window.__readCenter()")
                                _       <- captureShot("serverclock-tick0.png")
                                // Leg 2 continued: wait (by polling the page, not by sleeping) until the
                                // server fiber has advanced the tick past the captured baseline. The
                                // server, not the client, is the only thing that can move it.
                                _     <- Browser.waitFor(s"$tickValueJs > $tick0").handle(diagnoseTickStalled(tick0))
                                tick1 <- Browser.evalInt(tickValueJs)
                                // Leg 3: the cube recolored/rotated from the server tick, so the canvas
                                // center pixel changed between the two ticks.
                                _       <- Browser.waitFor(s"window.__readCenter() !== '$center0'").handle(diagnoseTimeout)
                                center1 <- Browser.eval("window.__readCenter()")
                                _       <- captureShot("serverclock-tick1.png")
                            yield
                                assert(
                                    distinct >= renderThreshold,
                                    s"the server-clock scene must render a non-blank frame (>= $renderThreshold distinct pixels), got $distinct"
                                )
                                assert(
                                    tick1 > tick0,
                                    s"the server tick must advance on its own with no user input; tick0=$tick0 tick1=$tick1"
                                )
                                assert(
                                    center1 != center0,
                                    s"the canvas center pixel must change as the server clock repaints the cube; before='$center0' after='$center1'"
                                )
                            end for
                        }
                    }
                }
            }
        }
    }

    "server-owned structure mutations reach the 3D scene over the WebSocket" in {
        cancelOnUnsupportedPlatform {
            ServerStructureScene.ui.map { ui =>
                servedDemo(ui) { url =>
                    headedSwiftshaderLaunch.map { launch =>
                        Browser.run(launch) {
                            for
                                _ <- Browser.goto(url)
                                _ <- awaitIslandError
                                // The island mounts the host and registers the WS channel receiver. The boot
                                // scene mixes two static lights with a foreach region, so the channel must
                                // register (the boot is non-empty: the lights survive the flatten).
                                _ <- Browser.waitFor("window.__kyoHostChannels && Object.keys(window.__kyoHostChannels).length > 0")
                                    .handle(diagnoseTimeout)
                                _ <- Browser.waitFor("document.querySelector('[data-kyo-host]') !== null").handle(diagnoseTimeout)
                                _ <- Browser.eval(samplerJs)
                                // Leg 1: the structurally-pushed cubes render on the 3D canvas, LIT by the two
                                // static lights that survived the boot. The cubes arrive over the structural
                                // channel (the foreach region's initial diff), so a non-blank frame proves the
                                // server-owned structure reached the rendered 3D scene, not just the HUD.
                                _        <- Browser.waitFor(s"window.__readDistinct() >= $renderThreshold").handle(diagnoseTimeout)
                                distinct <- Browser.evalInt("window.__readDistinct()")
                                // The HUD echoes the server-owned id list; it starts at [0, 1, 2]. The HUD is
                                // driven by the same `ids` signal over the same WebSocket as the 3D structure.
                                _ <- Browser.waitFor("""document.querySelector('#ids-label')?.textContent?.startsWith('ids:')""")
                                    .handle(diagnoseTimeout)
                                ids0 <- Browser.eval("""String(document.querySelector('#ids-label')?.textContent || 'missing')""")
                                // The canvas signature across the cube row, the 3D structure oracle.
                                sig0 <- Browser.eval("window.__readSignature()")
                                _    <- captureShot("serverstructure-initial.png")
                                // Add: click the kyo-ui button; its onClick runs server-side over the WebSocket
                                // and appends one id, so the server-owned list grows by one AND a fresh cube
                                // splices into the foreach region, changing the rendered canvas.
                                _ <- Browser.click(Browser.Selector.id("add-btn"))
                                _ <- Browser.waitFor(s"document.querySelector('#ids-label')?.textContent !== ${jsString(ids0)}")
                                    .handle(diagnoseTimeout)
                                idsAfterAdd <- Browser.eval("""String(document.querySelector('#ids-label')?.textContent || 'missing')""")
                                // The 3D canvas changed: the new cube is present in the rendered scene.
                                _ <- Browser.waitFor(s"window.__readSignature() !== ${jsString(sig0)}")
                                    .handle(diagnoseStructureStalled("the Add did not change the 3D canvas", sig0))
                                sigAfterAdd <- Browser.eval("window.__readSignature()")
                                _           <- captureShot("serverstructure-after-add.png")
                                // Reverse: the server-side onClick flips the id order; the keyed cubes reorder
                                // and each cube's index-driven x-position slides it to its new slot, so the
                                // colors swap positions and the rendered canvas changes again.
                                _ <- Browser.click(Browser.Selector.id("reverse-btn"))
                                _ <- Browser.waitFor(s"document.querySelector('#ids-label')?.textContent !== ${jsString(idsAfterAdd)}")
                                    .handle(diagnoseTimeout)
                                idsAfterReverse <-
                                    Browser.eval("""String(document.querySelector('#ids-label')?.textContent || 'missing')""")
                                _ <- Browser.waitFor(s"window.__readSignature() !== ${jsString(sigAfterAdd)}")
                                    .handle(diagnoseStructureStalled("the Reverse did not change the 3D canvas", sigAfterAdd))
                                sigAfterReverse <- Browser.eval("window.__readSignature()")
                                _               <- captureShot("serverstructure-after-reverse.png")
                            yield
                                val addList     = parseIds(idsAfterAdd)
                                val reverseList = parseIds(idsAfterReverse)
                                val initialList = parseIds(ids0)
                                assert(
                                    distinct >= renderThreshold,
                                    s"the structurally-pushed cubes must render a non-blank, LIT frame (>= $renderThreshold distinct pixels), got $distinct"
                                )
                                assert(
                                    initialList == List(0, 1, 2),
                                    s"the structure HUD must start at [0, 1, 2]; got '$ids0'"
                                )
                                assert(
                                    addList.length == initialList.length + 1,
                                    s"the Add click must grow the server id list by one; before='$ids0' after='$idsAfterAdd'"
                                )
                                assert(
                                    reverseList == addList.reverse,
                                    s"the Reverse click must flip the server id order; before='$idsAfterAdd' after='$idsAfterReverse'"
                                )
                                // The 3D canvas changed on BOTH structural mutations: the cubes are really on
                                // the canvas, not just in the HUD.
                                assert(
                                    sigAfterAdd != sig0,
                                    s"the Add must change the rendered 3D canvas (a cube spliced in); before='$sig0' after='$sigAfterAdd'"
                                )
                                assert(
                                    sigAfterReverse != sigAfterAdd,
                                    s"the Reverse must change the rendered 3D canvas (cubes slid to new slots); before='$sigAfterAdd' after='$sigAfterReverse'"
                                )
                            end for
                        }
                    }
                }
            }
        }
    }

    "a client raycast pick runs the server-side mesh onClick and the change reaches the browser" in {
        cancelOnUnsupportedPlatform {
            EmbeddedSceneScene.ui.map { ui =>
                servedDemo(ui) { url =>
                    headedSwiftshaderLaunch.map { launch =>
                        Browser.run(launch) {
                            for
                                _ <- Browser.goto(url)
                                _ <- awaitIslandError
                                _ <- Browser.waitFor("window.__kyoHostChannels && Object.keys(window.__kyoHostChannels).length > 0")
                                    .handle(diagnoseTimeout)
                                _ <- Browser.waitFor("document.querySelector('[data-kyo-host]') !== null").handle(diagnoseTimeout)
                                _ <- Browser.eval(samplerJs)
                                // The lit sun/earth scene boots non-empty (no foreach), so the canvas renders.
                                _        <- Browser.waitFor(s"window.__readDistinct() >= $renderThreshold").handle(diagnoseTimeout)
                                distinct <- Browser.evalInt("window.__readDistinct()")
                                // The HUD starts at the initial selection "Sun".
                                _ <- Browser.waitFor("""document.querySelector('#selected-label')?.textContent?.includes('Sun')""")
                                    .handle(diagnoseTimeout)
                                label0 <- Browser.eval("""String(document.querySelector('#selected-label')?.textContent || 'missing')""")
                                _      <- captureShot("embedpick-initial.png")
                                // Dispatch a client raycast pick on the EARTH mesh exactly as the island sends
                                // it: a HostPick over the WebSocket for the host path with the earth mesh's node
                                // id. The server resolves the node, runs its onClick, and writes "Earth" into the
                                // shared signal, which the HUD reflects back over the same WebSocket.
                                _ <- Browser.eval(pickJs(earthMeshNodeId))
                                _ <- Browser.waitFor("""document.querySelector('#selected-label')?.textContent?.includes('Earth')""")
                                    .handle(diagnosePickStalled("Earth"))
                                label1 <- Browser.eval("""String(document.querySelector('#selected-label')?.textContent || 'missing')""")
                                _      <- captureShot("embedpick-after-earth-pick.png")
                                // Pick the SUN mesh; the server onClick writes "Sun" back.
                                _ <- Browser.eval(pickJs(sunMeshNodeId))
                                _ <- Browser.waitFor("""document.querySelector('#selected-label')?.textContent?.includes('Sun')""")
                                    .handle(diagnosePickStalled("Sun"))
                                label2 <- Browser.eval("""String(document.querySelector('#selected-label')?.textContent || 'missing')""")
                                _      <- captureShot("embedpick-after-sun-pick.png")
                            yield
                                assert(
                                    distinct >= renderThreshold,
                                    s"the embedded sun/earth scene must render a non-blank frame, got $distinct distinct pixels"
                                )
                                assert(
                                    label0.contains("Sun"),
                                    s"the HUD must start at the initial selection 'Sun', got '$label0'"
                                )
                                assert(
                                    label1.contains("Earth"),
                                    s"a client raycast pick on the earth mesh must run the server onClick and set the HUD to 'Earth', got '$label1'"
                                )
                                assert(
                                    label2.contains("Sun"),
                                    s"a client raycast pick on the sun mesh must run the server onClick and set the HUD to 'Sun', got '$label2'"
                                )
                            end for
                        }
                    }
                }
            }
        }
    }

    "the shipped Snake3D demo boots and renders its mixed reactive scene over the WebSocket" in {
        cancelOnUnsupportedPlatform {
            // Snake3D mixes two static lights, a foreachKeyed cube region (the snake body), a reactive
            // region (the food sphere), and a static ticker group at the scene root. Served over
            // server-push, the boot must preserve the lights and represent both reactive regions as
            // holders, and the body cubes + food must arrive over the structural channel and render LIT.
            // (The onFrame ticker that steps the snake is a client-loop hook with no effect under
            // server-push, so this asserts the initial structural render, the boot the engine produces.)
            Snake3DScene.scene.map { s =>
                val ui = UI.div(Three.embed(s, Snake3DScene.camera, Snake3DScene.frames).id("app"))
                servedDemo(ui) { url =>
                    headedSwiftshaderLaunch.map { launch =>
                        Browser.run(launch) {
                            for
                                _ <- Browser.goto(url)
                                _ <- awaitIslandError
                                _ <- Browser.waitFor("window.__kyoHostChannels && Object.keys(window.__kyoHostChannels).length > 0")
                                    .handle(diagnoseTimeout)
                                _ <- Browser.waitFor("document.querySelector('[data-kyo-host]') !== null").handle(diagnoseTimeout)
                                _ <- Browser.eval(samplerJs)
                                // The body cubes and food sphere stream in over the structural channel and
                                // render lit; a non-blank frame proves the mixed scene booted structurally.
                                _        <- Browser.waitFor(s"window.__readDistinct() >= $renderThreshold").handle(diagnoseTimeout)
                                distinct <- Browser.evalInt("window.__readDistinct()")
                                _        <- captureShot("snake3d-boot.png")
                            yield assert(
                                distinct >= renderThreshold,
                                s"the Snake3D mixed reactive scene must boot and render a non-blank, lit frame over server-push, got $distinct distinct pixels"
                            )
                            end for
                        }
                    }
                }
            }
        }
    }

    /** Serves the genuine server-push page for the prebuilt `ui` (page GET + WebSocket from
      * `UI.runHandlers`) plus the island bundle and three.js, with the import map / bootstrap /
      * preserve-buffer shims injected into the served page. The `ui` is evaluated once by the caller
      * so a server engine fiber forks once and the page GET and WebSocket session share the same
      * server-owned signals. Hands the served URL to `f`.
      */
    private def servedDemo[A](ui: UI)(
        f: String => A < (Async & Scope & Abort[BrowserException])
    )(using Frame): A < (Async & Scope & Abort[BrowserException]) =
        for
            handlers <- UI.runHandlers("/", serverPushHead)(ui)
            basePage <- renderBasePage(handlers)
            page = augmentPage(basePage)
            wsHandler <- isolateWsHandler(handlers)
            module    <- WebGLSceneHarness.readThreeSource("three.module.js")
            core      <- WebGLSceneHarness.readThreeSource("three.core.js")
            server <- HttpServer.init(0, "localhost")(
                WebGLSceneHarness.htmlHandler(page),
                wsHandler,
                WebGLSceneHarness.jsHandler("three.module.js", module),
                WebGLSceneHarness.jsHandler("three.core.js", core),
                bootstrapHandler,
                DemoServe.islandHandler
            )
            result <- f(s"http://localhost:${server.port}/")
        yield result

    /** Renders the genuine server-push page HTML by serving the real `getPage` handler on a throwaway
      * server and fetching it once. The fetched HTML is the exact SSR output: the inline WS client,
      * the per-host init island, and the host `data-kyo-host` marker. The same `handlers` (and thus
      * the same prebuilt `ui`) back the live WebSocket, so the captured page and the session agree.
      */
    private def renderBasePage(handlers: Seq[HttpHandler[?, ?, ?]])(using Frame): String < (Async & Scope) =
        Abort.recover[HttpException](ex => sys.error(s"capturing the server-push SSR page failed: ${ex.getMessage}")) {
            Scope.run {
                for
                    server <- HttpServer.init(0, "localhost")(handlers*)
                    body   <- HttpClient.getText(s"http://localhost:${server.port}/")
                yield body
            }
        }

    /** The WebSocket handler from the runHandlers set. `UIServer.handlers` returns exactly the page
      * GET handler followed by the `/_kyo/ws` WebSocket handler; the page GET is replaced by the
      * shim-augmented static page, so only the WS handler (the second) is reused, and it drives the
      * same `ui` value (and thus the same server-owned signals) as the captured page.
      */
    private def isolateWsHandler(handlers: Seq[HttpHandler[?, ?, ?]])(using Frame): HttpHandler[?, ?, ?] < Sync =
        Sync.defer {
            if handlers.size != 2 then
                sys.error(s"expected runHandlers to return [page, ws], got ${handlers.size} handlers")
            else handlers(1)
        }

    /** Captures a screenshot and writes it under the visual-review directory. A local file-write
      * failure is not part of the browser interaction under test, so it surfaces as a panic rather
      * than widening the effect row the `Browser.run` body carries.
      */
    private def captureShot(name: String)(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.screenshot().map { shot =>
            Abort.recover[FileWriteException](ex => sys.error(s"writing screenshot $name failed: ${ex.getMessage}")) {
                shot.writeFileBinary(Path(visualReviewDir, name))
            }
        }

    /** Surfaces a page-side island bootstrap error as a test failure rather than waiting it out. */
    private def awaitIslandError(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        Browser.eval("window.__islandError || ''").map { err =>
            if err.nonEmpty then Abort.fail(BrowserScriptErrorException(err))
            else ()
        }

    /** On a wait-condition timeout, attaches the page's diagnostic state so the cause is visible. */
    private def diagnoseTimeout(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for
                err        <- Browser.eval("String(window.__islandError)")
                channels   <- Browser.eval("String(window.__kyoHostChannels ? Object.keys(window.__kyoHostChannels).length : 'none')")
                hasCanvas  <- Browser.eval("String(document.querySelector('[data-kyo-host]') !== null)")
                distinct   <- Browser.eval("String(typeof window.__readDistinct === 'function' ? window.__readDistinct() : 'no-sampler')")
                tick       <- Browser.eval("""String(document.querySelector('#tick-label')?.textContent || 'none')""")
                ids        <- Browser.eval("""String(document.querySelector('#ids-label')?.textContent || 'none')""")
                consoleErr <- Browser.eval("String((window.__consoleErrors||[]).join(' || '))")
            yield Abort.fail(BrowserScriptErrorException(
                s"server-push wait timed out: islandError='$err' channels=$channels hasCanvas=$hasCanvas distinct=$distinct tick='$tick' ids='$ids' consoleErrors='$consoleErr'"
            ))
        }(wait)

    /** On a pick-driven HUD timeout, reports the current HUD label so a non-firing server onClick is
      * distinguishable from a generic timeout.
      */
    private def diagnosePickStalled(expected: String)(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            Browser.eval("""String(document.querySelector('#selected-label')?.textContent || 'missing')""").map { current =>
                Abort.fail(BrowserScriptErrorException(
                    s"the client raycast pick did not run the server onClick: HUD still '$current', expected to contain '$expected'"
                ))
            }
        }(wait)

    /** On a canvas-signature-change timeout, reports the unchanged signature so a structural mutation
      * that reached the HUD but not the 3D canvas is distinguishable from a generic timeout.
      */
    private def diagnoseStructureStalled(what: String, baseline: String)(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for
                current    <- Browser.eval("String(window.__readSignature())")
                ids        <- Browser.eval("""String(document.querySelector('#ids-label')?.textContent || 'none')""")
                consoleErr <- Browser.eval("String((window.__consoleErrors||[]).join(' || '))")
            yield Abort.fail(BrowserScriptErrorException(
                s"$what: the canvas signature stayed '$baseline' (current='$current') while the HUD read '$ids'; consoleErrors='$consoleErr'"
            ))
        }(wait)

    /** On a tick-advance timeout, reports the stalled tick so a non-advancing server engine is
      * distinguishable from a generic timeout.
      */
    private def diagnoseTickStalled(baseline: Int)(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            Browser.eval(s"String($tickValueJs)").map { current =>
                Abort.fail(BrowserScriptErrorException(
                    s"the server tick never advanced past $baseline (current='$current'); the server engine fiber is not driving the page"
                ))
            }
        }(wait)

end ThreeServerPushTest

object ThreeServerPushTest:

    /** The minimum distinct RGBA pixel count that proves the embedded scene rendered a non-blank frame
      * rather than a single clear color. A shaded standard-material cube yields far more.
      */
    private[kyo] val renderThreshold: Int = 3

    /** The output directory for the human-review screenshots, relative to the test process cwd. */
    private[kyo] val visualReviewDir: String = "runs/visual-review"

    /** The sun mesh node id in [[demo.EmbeddedSceneScene]]: scene root `r`, child index 2 (after the
      * two lights). Its server-side onClick writes "Sun".
      */
    private[kyo] val sunMeshNodeId: String = "r.2"

    /** The earth mesh node id in [[demo.EmbeddedSceneScene]]: child index 3 of the root is the earth
      * group, and its first child (index 0) is the earth mesh, so `r.3.0`. Its onClick writes "Earth".
      */
    private[kyo] val earthMeshNodeId: String = "r.3.0"

    /** Dispatches a raycast pick exactly as the island's pointer handler does: posts a `HostPick` over
      * the WebSocket via `window.__kyoPostPick` for the embed host path with the given node id and a
      * center-of-canvas pointer payload. The path is read from the host element's `data-kyo-path`.
      */
    private[kyo] def pickJs(nodeId: String): String =
        s"""(function(){
           |  var host = document.querySelector('[data-kyo-host]');
           |  var pathAttr = host && host.getAttribute('data-kyo-path');
           |  var path = (pathAttr === null || pathAttr === undefined || pathAttr === "") ? [] : pathAttr.split('.');
           |  var pointer = { pointX:0, pointY:0, pointZ:0, distance:0, ndcX:0, ndcY:0 };
           |  window.__kyoPostPick(path, "$nodeId", pointer);
           |})();""".stripMargin

    /** A JS expression yielding the integer in the `#tick-label` HUD (`server tick: N`), or `-1` when
      * the label is absent. Used to observe the server-driven tick from the browser.
      */
    private[kyo] val tickValueJs: String =
        """(function(){var t=document.querySelector('#tick-label');""" +
            """if(!t)return -1;var m=t.textContent.match(/-?\d+/);return m?parseInt(m[0],10):-1;})()"""

    /** The page head linking the island bootstrap as the module script. The bootstrap (served at
      * [[bootstrapPath]]) imports the island bundle and calls `kyoThreeIsland()`.
      */
    private[kyo] def serverPushHead(using Frame): UI.PageHead =
        UI.PageHead("kyo-threejs server-push", moduleScript = Present(bootstrapPath))

    private[kyo] val bootstrapPath: String = "/_kyo/bootstrap.js"

    /** A small ESModule that imports the linked island bundle and invokes its `kyoThreeIsland` entry,
      * recording any throw on `window.__islandError`. A real browser deployment of the island bundle
      * performs this same import-and-invoke; the bundle exports the entry but does not self-run it.
      */
    private[kyo] def bootstrapHandler(using Frame): HttpHandler[Any, "body" ~ String, Nothing] =
        WebGLSceneHarness.jsHandler(
            bootstrapPath,
            s"""window.__islandError = "";
               |try {
               |  const m = await import("${DemoServe.islandPath}");
               |  m.kyoThreeIsland();
               |} catch (e) {
               |  window.__islandError = String(e && e.message ? e.message : e);
               |}
               |""".stripMargin
        )

    /** Injects the browser-environment shims into the genuine SSR page, at the start of `<body>` so
      * the import map and the preserve-buffer patch are in place before the inline WS client and the
      * island module script that follow it: (1) an import map resolving the bundle's bare `three`
      * import to the served build, and (2) a `getContext` patch forcing `preserveDrawingBuffer` so the
      * external pixel readback can sample the production renderer's framebuffer after a frame.
      */
    private[kyo] def augmentPage(page: String): String =
        val head =
            """<script type="importmap">
              |{ "imports": { "three": "/three.module.js" } }
              |</script>
              |<script>
              |window.__consoleErrors = [];
              |(function(){
              |  ['error','warn'].forEach(function(k){
              |    var orig = console[k];
              |    console[k] = function(){ try { window.__consoleErrors.push(k+': '+Array.prototype.slice.call(arguments).map(String).join(' ')); } catch(e){} return orig.apply(console, arguments); };
              |  });
              |  window.addEventListener('error', function(e){ try { window.__consoleErrors.push('onerror: ' + (e && e.message ? e.message : String(e))); } catch(_){} });
              |  window.addEventListener('unhandledrejection', function(e){ try { window.__consoleErrors.push('unhandledrejection: ' + (e && e.reason ? String(e.reason) : String(e))); } catch(_){} });
              |})();
              |(function(){
              |  var orig = HTMLCanvasElement.prototype.getContext;
              |  HTMLCanvasElement.prototype.getContext = function(type, attrs){
              |    if (type === "webgl2" || type === "webgl") {
              |      attrs = attrs || {};
              |      attrs.preserveDrawingBuffer = true;
              |    }
              |    return orig.call(this, type, attrs);
              |  };
              |})();
              |</script>
              |""".stripMargin
        val marker = "<body>"
        val idx    = page.indexOf(marker)
        if idx < 0 then sys.error(s"server-push page had no <body> to inject shims into:\n$page")
        else page.substring(0, idx + marker.length) + "\n" + head + page.substring(idx + marker.length)
    end augmentPage

    /** Defines the pixel sampler on the page: `window.__readDistinct()` counts distinct RGBA tuples in
      * the embed canvas (capped), and `window.__readCenter()` returns the center pixel's packed RGBA as
      * a string. Both read the preserved drawing buffer of the embed canvas via a fresh WebGL read.
      */
    private[kyo] val samplerJs: String =
        """window.__readDistinct = function(){
          |  var c = document.querySelector('[data-kyo-host]'); if(!c) return 0;
          |  var gl = c.getContext('webgl2') || c.getContext('webgl'); if(!gl) return 0;
          |  var w = c.width, h = c.height;
          |  var buf = new Uint8Array(w*h*4);
          |  gl.readPixels(0,0,w,h,gl.RGBA,gl.UNSIGNED_BYTE,buf);
          |  var seen = new Set();
          |  for(var i=0;i<buf.length;i+=4){ seen.add((buf[i]<<24)|(buf[i+1]<<16)|(buf[i+2]<<8)|buf[i+3]); if(seen.size>64) break; }
          |  return seen.size;
          |};
          |window.__readCenter = function(){
          |  var c = document.querySelector('[data-kyo-host]'); if(!c) return 'none';
          |  var gl = c.getContext('webgl2') || c.getContext('webgl'); if(!gl) return 'none';
          |  var w = c.width, h = c.height;
          |  var buf = new Uint8Array(4);
          |  gl.readPixels((w/2)|0,(h/2)|0,1,1,gl.RGBA,gl.UNSIGNED_BYTE,buf);
          |  return buf[0]+','+buf[1]+','+buf[2]+','+buf[3];
          |};
          |window.__readSignature = function(){
          |  var c = document.querySelector('[data-kyo-host]'); if(!c) return 'none';
          |  var gl = c.getContext('webgl2') || c.getContext('webgl'); if(!gl) return 'none';
          |  var w = c.width, h = c.height;
          |  var buf = new Uint8Array(w*h*4);
          |  gl.readPixels(0,0,w,h,gl.RGBA,gl.UNSIGNED_BYTE,buf);
          |  // Sample a horizontal band of points across the canvas mid-height and pack each into the
          |  // signature, so a structural reorder (cubes sliding between x-slots) or an inserted cube
          |  // changes the string even when the center pixel alone does not.
          |  var y = (h/2)|0; var parts = [];
          |  for(var k=0;k<=16;k++){ var x=((w-1)*k/16)|0; var i=(y*w+x)*4; parts.push(buf[i]+','+buf[i+1]+','+buf[i+2]); }
          |  return parts.join('|');
          |};""".stripMargin

    /** Parses the `#ids-label` text (`ids: [0, 1, 2]`) into the integer list it echoes. */
    private[kyo] def parseIds(label: String): List[Int] =
        val open  = label.indexOf('[')
        val close = label.indexOf(']')
        if open < 0 || close < 0 || close <= open then Nil
        else
            label.substring(open + 1, close).split(",").toList
                .map(_.trim)
                .filter(_.nonEmpty)
                .flatMap(_.toIntOption)
        end if
    end parseIds

    /** Encodes a string as a JS string literal for safe interpolation into an eval expression. */
    private[kyo] def jsString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

end ThreeServerPushTest
