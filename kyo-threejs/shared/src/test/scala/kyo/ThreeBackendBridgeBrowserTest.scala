package kyo

/** The FULL server-push bridge, end to end, in a real browser. Serves `fixture.ServerBridgeScene.ui`
  * through the REAL `UI.runHandlers` (SSR page GET + `/_kyo/ws` route), links the
  * fixtures configuration's bundle so the client rebuilds the IDENTICAL tree and hydrates the embedded
  * `Three.embed` canvas via the public `UI.runHydrate` entry (`kyo.ServerBridgeHydrate`,
  * `@JSExportTopLevel("hydrateServerBridge")`), then drives BOTH a DOM-bound `Signal[String]` and a
  * `material.color`-bound `Signal[Three.Color]` from the SERVER side and asserts both reach the
  * client through the SAME ONE WebSocket. This is the central split the whole structural-reactivity
  * design rests on (`data-kyo-path` matches server and client BY CONSTRUCTION); a FAIL here routes
  * to design, not a workaround.
  *
  * Every mount-state assertion rides `ThreeInspect`'s `window.bridge` projection (installed by
  * `ServerBridgeHydrate` over the embed's `Three.Mount`, `Three.Embedded.mounted`): the color reaches
  * the client via `window.bridge.pixel(cx, cy)` (the live framebuffer), and the deterministic
  * Scope-close teardown at the end of the first leaf drives `window.bridge.close()` (a `Promise` gate,
  * never a sleep) then asserts `contextLost()`/`disposed()`, since the projection was installed in an
  * OUTER scope that survives the mount scope's close. The retained kyo-ui Backend SPI
  * (`window.__kyoBackends`) is untouched.
  *
  * Also asserts no re-mount on a reactive change, as an extra assertion on the SAME mount (a
  * live-only WebGL context makes a Node-testable `ThreeBackendTest.scala` infeasible): the mounted
  * `<canvas>` element's identity is captured once and re-checked by reference after N driven prop
  * changes, proving no re-mount ever replaced it.
  *
  * A separate leaf proves the SAME wire path re-aims the CAMERA, not just a material prop: a
  * perspective camera's `lookAt` binds to a server `Signal[Three.Vec3]` (`fixture.ServerBridgeCameraScene`),
  * and driving that signal from red to green plane centers, then back, swaps which unlit plane the
  * sampled center pixel reads. It asserts on the RENDERED framebuffer pixel (never a directly-applied
  * patch), so it fails if the camera `SetProp`/`applyByKey`/re-aim chain breaks.
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
                                _            <- Browser.goto(url)
                                _            <- Browser.waitFor("window.bridge !== undefined").handle(diagnoseBridgeTimeout)
                                center       <- centerOf("stage")
                                initialColor <- readPixel(center._1, center._2)
                                initialLabel <- Browser.eval("""String(document.querySelector('#label')?.textContent || '')""")
                                _            <- Browser.eval("window.stageCanvasRef = document.querySelector('#stage'); 'ok'")
                                // Drive 3 rounds of BOTH signals; the client's canvas identity must survive all of them
                                // (patch, never re-mount).
                                _               <- label.set("round-1")
                                _               <- color.set(Three.Color.green)
                                _               <- Browser.assertText(Browser.Selector.id("label"), "round-1")
                                round1Color     <- waitForColor(center._1, center._2, 0x00, 0xff, 0x00)
                                sameAfterRound1 <- Browser.eval("String(document.querySelector('#stage') === window.stageCanvasRef)")
                                _               <- label.set("round-2")
                                _               <- color.set(Three.Color.blue)
                                _               <- Browser.assertText(Browser.Selector.id("label"), "round-2")
                                finalColor      <- waitForColor(center._1, center._2, 0x00, 0x00, 0xff)
                                sameAfterRound2 <- Browser.eval("String(document.querySelector('#stage') === window.stageCanvasRef)")
                                canvasCount     <- Browser.evalInt("document.querySelectorAll('canvas').length")
                                // LAST on this SAME mount: close the bridge deterministically (a
                                // Promise trigger, never a sleep) and assert the production teardown ran.
                                _      <- Browser.eval("window.bridge.close()")
                                _      <- Browser.waitFor("window.bridge.disposed() === true").handle(diagnoseBridgeCloseTimeout)
                                glLost <- Browser.eval("String(window.bridge.contextLost())")
                                backendGoneAfterClose <- Browser.eval(
                                    "String(!window.__kyoBackends || window.__kyoBackends['1'] === undefined)"
                                )
                                tuple: (PixelRead, String, String, PixelRead, String, PixelRead, Int, String, String) =
                                    (
                                        initialColor,
                                        initialLabel,
                                        sameAfterRound1,
                                        round1Color,
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
                val (
                    initialColor,
                    initialLabel,
                    sameAfterRound1,
                    round1Color,
                    sameAfterRound2,
                    finalColor,
                    canvasCount,
                    glLost,
                    backendGoneAfterClose
                ) =
                    result
                assert(initialLabel == "initial", s"the SSR'd label must read 'initial' before any drive, got '$initialLabel'")
                assertLit(initialColor, 0xff, 0x00, 0x00, "the SSR-hydrated cube must start red")
                assertLit(round1Color, 0x00, 0xff, 0x00, "the cube must reach green after the first driven color")
                assertLit(finalColor, 0x00, 0x00, 0xff, "the cube must reach blue after the second driven color")
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

    "SetProps that arrive BEFORE the island registers buffer one slot per prop, coalesce per slot with the newest value winning, and every slot flushes on registration so no prop is lost" in {
        // The startup buffer holds ONE SLOT PER BOUND PROP, not one entry per arriving op: a second op for
        // a prop REPLACES the first, because only the newest value of a prop means anything. So the two
        // things worth proving pull in opposite directions and BOTH are asserted here:
        //   - repeats for ONE prop must collapse (four ops arrive, two slots exist), and the value that
        //     survives must be the NEWEST, not the first;
        //   - distinct props must NOT collapse (two props, two slots, and BOTH reach the scene on flush).
        // Driving one prop twice cannot show the second, which is why the scene binds `color` AND `scale`.
        cancelOnUnsupportedPlatform {
            for
                label <- Signal.initRef("initial")
                color <- Signal.initRef(Three.Color.red)
                scale <- Signal.initRef(Three.Vec3(1, 1, 1))
                result <- servedPreRegister(label, color, scale) { url =>
                    swiftshaderLaunch.map { launch =>
                        Browser.run(launch) {
                            for
                                _ <- Browser.goto(url)
                                // The inline WS client is up and the island module has loaded, but its
                                // backend registration is GATED; the server's initial SetProps are arriving.
                                _ <- Browser.waitFor("window.preRegisterReady === true").handle(diagnosePreRegisterTimeout)
                                // The pre-registration race is REAL: the initial SetProp for EACH bound prop
                                // buffers into its own slot (it arrived while no handle was registered).
                                _ <- Browser.waitFor(
                                    "!!window.__kyoBackendsPending && !!window.__kyoBackendsPending['1'] && " +
                                        "window.__kyoBackendsPending['1'].order.length === 2"
                                ).handle(diagnosePreRegisterTimeout)
                                // Drive BOTH props again, still before registration. Each is a SECOND op for a
                                // slot that already holds one. `label` is a DOM region, not a backend prop, so
                                // it is applied straight to the page rather than buffered: driving it LAST and
                                // waiting for it is an ordering fence proving the two SetProps ahead of it on
                                // the one socket have already been handled. Without it, the count below could
                                // be read before the repeats ever arrived and would pass for the wrong reason.
                                _ <- color.set(Three.Color.green)
                                _ <- scale.set(fixture.ServerBridgeScene.wideScale)
                                _ <- label.set("driven")
                                _ <- Browser.waitFor(
                                    "document.querySelector('#label') && document.querySelector('#label').textContent === 'driven'"
                                ).handle(diagnosePreRegisterTimeout)
                                // Four SetProps have now arrived and none has been applied. The repeats must
                                // have COALESCED into the two slots rather than queued behind them.
                                slotsAfterRepeats <- Browser.evalInt("window.__kyoBackendsPending['1'].order.length")
                                slotIds           <- Browser.eval("JSON.stringify(window.__kyoBackendsPending['1'].order)")
                                // No backend handle is registered yet: nothing has been applied to the cube.
                                registeredBefore <- Browser.eval("String(!!(window.__kyoBackends && window.__kyoBackends['1']))")
                                // Release the gate: the island hydrates and registers, flushing every slot.
                                _      <- Browser.eval("window.releaseHydrate()")
                                _      <- Browser.waitFor("window.bridge !== undefined").handle(diagnosePreRegisterTimeout)
                                center <- centerOf("stage")
                                // The COLOR slot flushed carrying its NEWEST value: green, not the red it was
                                // first driven with. A buffer that kept the first op per slot leaves this red.
                                colorSlot <- waitForColorMatch(center._1, center._2, 0x00, 0xff, 0x00)
                                // The SCALE slot flushed too. This pixel is high above the cube's top edge at
                                // scale 1 (the box spans ~43% of the half-height), so it reads the clear color
                                // unless the cube was actually widened. A flush that dropped the scale slot,
                                // or coalesced the two DISTINCT props into one, leaves this pixel unlit.
                                edge      <- edgeOf("stage")
                                scaleSlot <- waitForColorMatch(edge._1, edge._2, 0x00, 0xff, 0x00)
                                // The probe must DISCRIMINATE, or the assertion above proves nothing. If this
                                // pixel sat inside the cube even at scale 1 (a change to the scene's camera fov
                                // would do it, from a file that knows nothing about this leaf), it would read
                                // green whatever the scale slot did and this leaf would be green for free. Its
                                // placement rests on arithmetic, so the arithmetic gets checked: put the scale
                                // back and the same pixel must come off the cube. This runs AFTER registration,
                                // so it is applied straight through and exercises no buffer.
                                _ <- scale.set(Three.Vec3(1, 1, 1))
                                _ <- label.set("reset")
                                _ <- Browser.waitFor(
                                    "document.querySelector('#label').textContent === 'reset'"
                                ).handle(diagnosePreRegisterTimeout)
                                probeValid <- waitForNotColor(edge._1, edge._2, 0x00, 0xff, 0x00)
                                tuple: (Int, String, String, String, String, String) =
                                    (slotsAfterRepeats, slotIds, registeredBefore, colorSlot, scaleSlot, probeValid)
                            yield tuple
                        }
                    }
                }
            yield
                val (slotsAfterRepeats, slotIds, registeredBefore, colorSlot, scaleSlot, probeValid) = result
                assert(
                    registeredBefore == "false",
                    s"no backend handle may be registered while the gate holds (the race window must be real), got registeredBefore=$registeredBefore"
                )
                assert(
                    probeValid == "DIFFERS",
                    "the edge pixel must come OFF the cube when the scale is put back, or it reads green whatever " +
                        s"the scale slot did and proves nothing, got $probeValid"
                )
                assert(
                    slotsAfterRepeats == 2,
                    s"four SetProps for two props must hold exactly two slots (a repeat replaces its slot), got $slotsAfterRepeats: $slotIds"
                )
                assert(
                    slotIds.contains("material.color") && slotIds.contains("scale"),
                    s"the two slots must be the two DISTINCT bound props, not two ops for one prop, got $slotIds"
                )
                assert(
                    colorSlot == "MATCH",
                    s"the color slot must flush carrying its NEWEST value (green), not the red it first held, got $colorSlot"
                )
                assert(
                    scaleSlot == "MATCH",
                    s"the scale slot must flush too, widening the cube over a pixel that is clear at scale 1, got $scaleSlot"
                )
        }
    }

    "a startup buffer that genuinely overflows EVICTS the oldest slot and SAYS SO, never drops it silently" in {
        // The buffer is bounded, so an overflow can still lose an op, and a lost op leaves the scene at its
        // last good state, which looks exactly like an op that was applied. A silent eviction inside the
        // drop-reporting fix would be the very failure that fix exists to remove.
        //
        // The overflow is REAL, not simulated: `ServerBridgeOverflowScene` carries one more bound prop than
        // the buffer can hold, the server pushes an initial SetProp per bound prop the moment the session
        // subscribes, the gate keeps the island from registering while they land, and the last to arrive
        // must evict the first. Nothing is fabricated and no shipped code is widened to observe it.
        cancelOnUnsupportedPlatform {
            for
                label <- Signal.initRef("initial")
                colors <- Kyo.foreach(Chunk.fill(fixture.ServerBridgeOverflowScene.propCount)(()))(_ =>
                    Signal.initRef(Three.Color.red)
                )
                result <- servedOverflow(label, colors) { url =>
                    swiftshaderLaunch.map { launch =>
                        Browser.run(launch) {
                            for
                                _ <- Browser.goto(url)
                                _ <- Browser.waitFor("window.preRegisterReady === true").handle(diagnosePreRegisterTimeout)
                                // The label rides the same socket and is applied to the page directly rather
                                // than buffered, so driving it AFTER the initial burst and waiting for it
                                // proves every SetProp ahead of it has already been routed, and with them the
                                // one that overflowed. Reading the console without this fence could look
                                // before the eviction ever happened and pass for the wrong reason.
                                _ <- label.set("driven")
                                _ <- Browser.waitFor(
                                    "document.querySelector('#label') && document.querySelector('#label').textContent === 'driven'"
                                ).handle(diagnosePreRegisterTimeout)
                                buffered <- Browser.evalInt("window.__kyoBackendsPending['1'].order.length")
                                logs     <- Browser.consoleLogs
                                tuple: (Int, String) =
                                    (buffered, logs.map(_.text).filter(_.contains("dropped a backend op")).mkString("\n"))
                            yield tuple
                        }
                    }
                }
            yield
                val (buffered, drops) = result
                assert(
                    buffered == 256,
                    s"the buffer must hold its cap and no more once ${fixture.ServerBridgeOverflowScene.propCount} props arrived, got $buffered"
                )
                assert(
                    drops.linesIterator.size == 1,
                    s"one prop past the cap must evict exactly one slot, so exactly one drop is reported; got: $drops"
                )
                assert(
                    drops.contains("material.color"),
                    s"the eviction must NAME the op whose value never reached the scene, got: $drops"
                )
                assert(
                    drops.contains("256"),
                    s"the eviction report must say what overflowed, got: $drops"
                )
        }
    }

    "a SetProp whose backend root is missing from the page is REPORTED, never silently dropped" in {
        // The one drop the backend's own reporting cannot see, because the op never reaches the backend:
        // the inline client resolves the owning backend root by walking up the pushed path, and a path that
        // resolves to no `data-kyo-backend` element must be reported, not dropped in silence. A silent drop
        // leaves the scene frozen at its last good state, which looks exactly like a scene with nothing to
        // update. Removing the attribute reproduces the real cause (the browser's tree disagreeing with the
        // markup) through the ordinary server-push path, with no reach into the client's internals.
        cancelOnUnsupportedPlatform {
            for
                label <- Signal.initRef("initial")
                color <- Signal.initRef(Three.Color.red)
                warns <- servedBridge(label, color) { url =>
                    swiftshaderLaunch.map { launch =>
                        Browser.run(launch) {
                            for
                                _ <- Browser.goto(url)
                                _ <- Browser.waitFor("window.bridge !== undefined").handle(diagnoseBridgeTimeout)
                                // Drain the console of everything the page said while booting, so what is
                                // read back below can only be the drop this leaf provokes.
                                _ <- Browser.consoleLogs
                                _ <- Browser.evalDiscard("document.querySelector('#stage').removeAttribute('data-kyo-backend')")
                                _ <- color.set(Three.Color.green)
                                // The label rides the same socket and is applied to the page directly, so it
                                // fences the assertion: once it lands, the color SetProp ahead of it has been
                                // routed, and either applied or dropped.
                                _ <- label.set("driven")
                                _ <- Browser.waitFor(
                                    "document.querySelector('#label') && document.querySelector('#label').textContent === 'driven'"
                                ).handle(diagnoseBridgeTimeout)
                                logs <- Browser.consoleLogs
                            yield logs.map(_.text).mkString("\n")
                        }
                    }
                }
            yield
                assert(
                    warns.contains("dropped a backend op"),
                    s"an unroutable SetProp must say so; the console said: $warns"
                )
                assert(
                    warns.contains("material.color"),
                    s"the report must name the op it dropped, got: $warns"
                )
        }
    }

    "the server-driven camera lookAt re-aim swaps which unlit plane the frame center samples, over the one /_kyo/ws socket" in {
        cancelOnUnsupportedPlatform {
            for
                target <- Signal.initRef(fixture.ServerBridgeCameraScene.redTarget)
                result <- servedCameraBridge(target) { url =>
                    swiftshaderLaunch.map { launch =>
                        Browser.run(launch) {
                            for
                                _      <- Browser.goto(url)
                                _      <- Browser.waitFor("window.bridge !== undefined").handle(diagnoseBridgeTimeout)
                                center <- centerOf("stage")
                                // The initial aim (client-local seed at redTarget) centers the red plane, so the
                                // sampled center pixel is red before any server re-aim.
                                initialColor <- waitForColor(center._1, center._2, 0xff, 0x00, 0x00)
                                // Re-aim the SERVER camera signal at the green plane. The lookAt SetProp must reach
                                // the client and re-aim the LIVE camera, so the RENDERED center pixel converges to
                                // green; this reads the framebuffer, never a directly-applied patch, so a broken
                                // camera SetProp/applyByKey/re-aim chain would leave it red and time out here.
                                _          <- target.set(fixture.ServerBridgeCameraScene.greenTarget)
                                greenColor <- waitForColor(center._1, center._2, 0x00, 0xff, 0x00)
                                // Re-aim back at the red plane: the re-aim is bidirectional over the same socket.
                                _           <- target.set(fixture.ServerBridgeCameraScene.redTarget)
                                backColor   <- waitForColor(center._1, center._2, 0xff, 0x00, 0x00)
                                canvasCount <- Browser.evalInt("document.querySelectorAll('canvas').length")
                                tuple: (PixelRead, PixelRead, PixelRead, Int) = (initialColor, greenColor, backColor, canvasCount)
                            yield tuple
                        }
                    }
                }
            yield
                val (initialColor, greenColor, backColor, canvasCount) = result
                assertLit(initialColor, 0xff, 0x00, 0x00, "the initial aim at redTarget must center the red plane")
                assertLit(greenColor, 0x00, 0xff, 0x00, "the server re-aim at greenTarget must re-center the green plane")
                assertLit(backColor, 0xff, 0x00, 0x00, "the server re-aim back at redTarget must re-center the red plane")
                assert(canvasCount == 1, s"exactly one canvas must ever exist for the one embed, got $canvasCount")
        }
    }

    /** Reads the mounted canvas's actual pixel dimensions and returns its center coordinate. */
    private def centerOf(selector: String)(using Frame): (Int, Int) < (Browser & Abort[BrowserReadException]) =
        for
            w <- Browser.evalInt(s"document.querySelector('#$selector').width")
            h <- Browser.evalInt(s"document.querySelector('#$selector').height")
        yield (w / 2, h / 2)

    /** A pixel on the canvas's vertical midline, a tenth of the way in from one edge: far outside the
      * unscaled cube, and inside it once `ServerBridgeScene.wideScale` is applied.
      *
      * The cube spans about 43% of the visible half-height (a 2-unit box with its front face at z=1, seen
      * from z=4 through a 75-degree vertical fov), so this pixel, 80% of the way out from the center, reads
      * the clear color while the cube is unscaled. Horizontally centered, so it depends only on the vertical
      * fov and not on the canvas aspect ratio. Which edge it lands on depends on whether the pixel read is
      * top-down or bottom-up, and it does not matter: the point is symmetric about the midline.
      */
    private def edgeOf(selector: String)(using Frame): (Int, Int) < (Browser & Abort[BrowserReadException]) =
        for
            w <- Browser.evalInt(s"document.querySelector('#$selector').width")
            h <- Browser.evalInt(s"document.querySelector('#$selector').height")
        yield (w / 2, h / 10)

    /** Awaits `window.bridge.pixel(x, y)` and decodes the resolved bytes or the rejection reason,
      * mirroring `ThreeMountBrowserTest.readPixel`.
      */
    private def readPixel(x: Int, y: Int)(using Frame, kyo.test.AssertScope): PixelRead < (Browser & Abort[BrowserReadException]) =
        // Browser.eval does NOT await a returned Promise (only kyo.internal.BrowserEval.evalJsAwaiting
        // does); pixel() is async, so the async IIFE below must be read through the awaiting variant.
        kyo.internal.BrowserEval.evalJsAwaiting(
            s"(async () => { try { const px = await window.bridge.pixel($x, $y); return 'OK:' + px.join(','); } " +
                s"catch (e) { return 'REJECTED:' + String(e); } })()"
        ).map {
            case s if s.startsWith("OK:") =>
                s.stripPrefix("OK:").split(",").map(_.trim.toInt) match
                    case Array(r, g, b, a) => PixelRead.Ok(r, g, b, a)
                    case other             => fail(s"unexpected pixel encoding: ${other.mkString(",")}")
            case s if s.startsWith("REJECTED:") => PixelRead.Rejected(s.stripPrefix("REJECTED:"))
            case other                          => fail(s"unexpected readPixel result: $other")
        }

    /** Polls `window.bridge.pixel(x, y)` (each attempt a fresh, individually-awaited async read
      * draining at the mount's next live commit) against the active `Browser` retry schedule until the
      * resolved color matches the expected RGB within tolerance, returning the poll's own `"MATCH"`
      * result; used for a server-driven color the client converges to over time. `Browser.waitFor`
      * itself never awaits a returned Promise, so a promise-returning match expression given to it
      * would always evaluate the pending Promise object (always truthy) rather than its resolved
      * value; polling through `kyo.internal.BrowserEval.evalJsAwaiting` inside `Retry` is what actually
      * waits out each read before checking the match.
      */
    private def waitForColorMatch(x: Int, y: Int, r: Int, g: Int, b: Int)(using
        Frame
    ): String < (Browser & Async & Abort[BrowserReadException]) =
        Browser.configLocal.use { cfg =>
            Retry[BrowserReadException](cfg.retrySchedule) {
                kyo.internal.BrowserEval.evalJsAwaiting(
                    s"(async () => { try { const px = await window.bridge.pixel($x, $y); return px.join(','); } " +
                        s"catch (e) { return 'REJECTED:' + String(e); } })()"
                ).map {
                    case raw if raw.startsWith("REJECTED:") =>
                        Abort.fail(BrowserAssertionTimedOutException("waitForColorMatch", s"[$r,$g,$b]", raw))
                    case raw =>
                        raw.split(",").map(_.trim.toInt) match
                            case Array(pr, pg, pb, _)
                                if math.abs(pr - r) < 24 && math.abs(pg - g) < 24 && math.abs(pb - b) < 24 =>
                                "MATCH"
                            case Array(pr, pg, pb, _) =>
                                Abort.fail(BrowserAssertionTimedOutException("waitForColorMatch", s"[$r,$g,$b]", s"[$pr,$pg,$pb]"))
                            case other =>
                                Abort.fail(BrowserScriptErrorException(s"unexpected pixel encoding: ${other.mkString(",")}"))
                }
            }
        }

    /** Polls until the pixel at (x, y) is NOT the given color, within the same tolerance the positive wait
      * uses.
      *
      * This is what makes a probe pixel's positive assertion mean something. A pixel that reads the cube's
      * color no matter what the cube does would satisfy its positive assertion for free, and the leaf would
      * be green while proving nothing. Showing the same pixel take a DIFFERENT value under the opposite
      * condition is what establishes that it discriminates at all.
      */
    private def waitForNotColor(x: Int, y: Int, r: Int, g: Int, b: Int)(using
        Frame
    ): String < (Browser & Async & Abort[BrowserReadException]) =
        Browser.configLocal.use { cfg =>
            Retry[BrowserReadException](cfg.retrySchedule) {
                kyo.internal.BrowserEval.evalJsAwaiting(
                    s"(async () => { try { const px = await window.bridge.pixel($x, $y); return px.join(','); } " +
                        s"catch (e) { return 'REJECTED:' + String(e); } })()"
                ).map {
                    case raw if raw.startsWith("REJECTED:") =>
                        Abort.fail(BrowserAssertionTimedOutException("waitForNotColor", s"not [$r,$g,$b]", raw))
                    case raw =>
                        raw.split(",").map(_.trim.toInt) match
                            case Array(pr, pg, pb, _)
                                if math.abs(pr - r) >= 24 || math.abs(pg - g) >= 24 || math.abs(pb - b) >= 24 =>
                                "DIFFERS"
                            case Array(pr, pg, pb, _) =>
                                Abort.fail(
                                    BrowserAssertionTimedOutException("waitForNotColor", s"not [$r,$g,$b]", s"[$pr,$pg,$pb]")
                                )
                            case other =>
                                Abort.fail(BrowserScriptErrorException(s"unexpected pixel encoding: ${other.mkString(",")}"))
                }
            }
        }

    /** Waits for the color match, then reads back the real RGBA bytes for the assertion message. */
    private def waitForColor(x: Int, y: Int, r: Int, g: Int, b: Int)(using
        Frame,
        kyo.test.AssertScope
    ): PixelRead < (Browser & Async & Abort[BrowserReadException]) =
        waitForColorMatch(x, y, r, g, b).andThen(readPixel(x, y))

    /** Asserts a resolved pixel read is close to the expected RGB (real GL output carries anti-alias
      * and gamma-correction noise, so an exact-byte match is not the right bar).
      */
    private def assertLit(read: PixelRead, r: Int, g: Int, b: Int, msg: String)(using kyo.test.AssertScope): Unit =
        read match
            case PixelRead.Ok(ar, ag, ab, _) =>
                assert(
                    math.abs(ar - r) < 24 && math.abs(ag - g) < 24 && math.abs(ab - b) < 24,
                    s"$msg (expected ~[$r,$g,$b]), got [$ar,$ag,$ab]"
                )
            case PixelRead.Rejected(reason) => fail(s"$msg, but the pixel read rejected: $reason")

    /** Serves the SSR page for `fixture.ServerBridgeScene.ui(label, color)` through the real
      * `UI.runHandlers`, alongside the linked fixtures bundle and the three.js sources its import map
      * resolves, and hands the served URL to `f`.
      */
    private def servedBridge[A](label: SignalRef[String], color: SignalRef[Three.Color])(
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
            appHandlers <- UI.runHandlers("/", head)(fixture.ServerBridgeScene.ui(label, color))
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

    /** Serves the SSR page for `fixture.ServerBridgeScene.preRegisterUi` (the two-bound-prop variant, so the
      * client's startup buffer holds two distinct slots) but links the GATED hydrate
      * (`hydrateServerBridgePreRegister`), which blocks backend registration behind `window.releaseHydrate()`
      * so a test can drive the server signals during the pre-registration window. Mirrors `servedBridge`,
      * pointed at the pre-register hydrate export instead.
      */
    private def servedPreRegister[A](label: SignalRef[String], color: SignalRef[Three.Color], scale: SignalRef[Three.Vec3])(
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
            bootJs = """import { hydrateServerBridgePreRegister } from "/main.js"; hydrateServerBridgePreRegister();"""
            head = UI.PageHead(
                "kyo-threejs pre-register bridge test",
                moduleScript = Present("/boot.js"),
                importMap = Seq(
                    "three"                                        -> "/three.module.js",
                    "three/examples/jsm/loaders/GLTFLoader.js"     -> "/three/examples/jsm/loaders/GLTFLoader.js",
                    "three/examples/jsm/controls/OrbitControls.js" -> "/three/examples/jsm/controls/OrbitControls.js"
                )
            )
            appHandlers <- UI.runHandlers("/", head)(fixture.ServerBridgeScene.preRegisterUi(label, color, scale))
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
    end servedPreRegister

    /** Serves the SSR page for `fixture.ServerBridgeOverflowScene.ui` (one more bound prop than the client's
      * startup buffer can hold) behind the same GATED hydrate shape as `servedPreRegister`, so the server's
      * initial `SetProp` burst overflows the buffer for real while the island is still unregistered.
      */
    private def servedOverflow[A](label: SignalRef[String], colors: Seq[SignalRef[Three.Color]])(
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
            bootJs = """import { hydrateServerBridgeOverflow } from "/main.js"; hydrateServerBridgeOverflow();"""
            head = UI.PageHead(
                "kyo-threejs startup-buffer overflow test",
                moduleScript = Present("/boot.js"),
                importMap = Seq(
                    "three"                                        -> "/three.module.js",
                    "three/examples/jsm/loaders/GLTFLoader.js"     -> "/three/examples/jsm/loaders/GLTFLoader.js",
                    "three/examples/jsm/controls/OrbitControls.js" -> "/three/examples/jsm/controls/OrbitControls.js"
                )
            )
            appHandlers <- UI.runHandlers("/", head)(fixture.ServerBridgeOverflowScene.ui(label, colors))
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
    end servedOverflow

    /** Serves the SSR page for `fixture.ServerBridgeCameraScene.ui(target)` through the real
      * `UI.runHandlers`, linked to the camera hydrate export (`hydrateServerBridgeCamera`), so a test
      * can drive the server `target` signal and watch the client camera re-aim. Mirrors `servedBridge`,
      * pointed at the camera scene and its hydrate export instead.
      */
    private def servedCameraBridge[A](target: SignalRef[Three.Vec3])(
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
            bootJs = """import { hydrateServerBridgeCamera } from "/main.js"; hydrateServerBridgeCamera();"""
            head = UI.PageHead(
                "kyo-threejs camera bridge test",
                moduleScript = Present("/boot.js"),
                importMap = Seq(
                    "three"                                        -> "/three.module.js",
                    "three/examples/jsm/loaders/GLTFLoader.js"     -> "/three/examples/jsm/loaders/GLTFLoader.js",
                    "three/examples/jsm/controls/OrbitControls.js" -> "/three/examples/jsm/controls/OrbitControls.js"
                )
            )
            appHandlers <- UI.runHandlers("/", head)(fixture.ServerBridgeCameraScene.ui(target))
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
    end servedCameraBridge

    /** On a pre-register timeout, surfaces the gate/buffer state so the cause is visible. */
    private def diagnosePreRegisterTimeout(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for
                ready  <- Browser.eval("String(window.preRegisterReady)")
                bridge <- Browser.eval("String(window.bridge !== undefined)")
                buffered <- Browser.eval(
                    "String(window.__kyoBackendsPending && window.__kyoBackendsPending['1'] ? window.__kyoBackendsPending['1'].length : 'none')"
                )
            yield Abort.fail(BrowserScriptErrorException(
                s"pre-register bridge state never reached the awaited condition: preRegisterReady=$ready bridgeInstalled=$bridge buffered=$buffered"
            ))
        }(wait)

    /** On a bridge-ready timeout, surfaces the diagnostic state as part of the failure. */
    private def diagnoseBridgeTimeout(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for installed <- Browser.eval("String(window.bridge !== undefined)")
            yield Abort.fail(BrowserScriptErrorException(s"bridge hydrate never installed its projection: installed=$installed"))
        }(wait)

    /** On a bridge-closed timeout (the teardown never completed), surfaces the disposed flag's actual value. */
    private def diagnoseBridgeCloseTimeout(
        wait: String < (Browser & Abort[BrowserReadException])
    )(using Frame): String < (Browser & Abort[BrowserReadException]) =
        Abort.recover[BrowserReadException] { _ =>
            for closed <- Browser.eval("String(window.bridge.disposed())")
            yield Abort.fail(BrowserScriptErrorException(s"bridge close teardown never completed: disposed=$closed"))
        }(wait)

end ThreeBackendBridgeBrowserTest
