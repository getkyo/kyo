package kyo

import demo.ServerBridgeScene
import kyo.internal.DomBackend
import kyo.internal.ThreeFacade
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/** The client hydrate entry point for the kyo-threejs server-push bridge browser tests
  * (`ThreeBackendBridgeBrowserTest`, `ThreeStructuralBridgeBrowserTest`): rebuilds the SAME
  * `ServerBridgeScene.ui` tree client-side (so `data-kyo-path` matches the server's SSR markup by
  * construction) and hydrates it onto the ALREADY-SSR'd DOM via
  * `DomBackend.hydrateBackendNodes` -- registering the embedded `Three.embed` canvas's live mount
  * WITHOUT touching `container.innerHTML`. Reactivity itself rides the page's own inline WS listener
  * (`HtmlRenderer.clientJs`), unrelated to this entry point; this only gets the 3D canvas's live
  * mount registered so that listener's dispatch has somewhere to land.
  *
  * Lives in package `kyo` (not `demoharness`, unlike `DemoHarness`'s other entries) because
  * `hydrateBackendNodes` is `private[kyo]`; `Frame.internal` is the zero-derivation frame this entry
  * point needs since package `kyo` non-test code cannot auto-derive one.
  *
  * The mount stays live behind a `window.__closeBridge()` trigger (a `Promise` awaited instead of
  * `Async.never`) rather than parking forever: deterministic Scope-close teardown needs the
  * SAME mount to close on demand, mirroring `DemoHarness.rendererReleaseProbe`'s capture-then-close
  * pattern, so a browser test can drive the bridge, THEN close it, THEN assert the renderer/RAF/
  * controls finalizers ran, all on one mount.
  */
object ServerBridgeHydrate:

    @JSExportTopLevel("hydrateServerBridge")
    def hydrateServerBridge(): Unit =
        given Frame = Frame.internal
        // Unsafe: the page-to-kyo boundary; runs hydration on a detached fiber whose ambient Scope
        // stays open for the mount's frame loop and observe fibers, mirroring DemoHarness's own entries.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(
            Fiber.initUnscoped(
                Scope.run(hydrateAndWait).andThen(Sync.defer {
                    // Runs only after Scope.run's finalizers (renderer dispose/forceContextLoss, RAF
                    // cancel, ThreeBackend.mount's unregisterMount/unregisterJsHandle) have ALL completed:
                    // the deterministic "teardown is done" signal the browser assertion polls for.
                    dom.window.asInstanceOf[js.Dynamic].__bridgeClosed = true
                })
            ).unit
        )
    end hydrateServerBridge

    private def hydrateAndWait(using Frame): Unit < (Async & Scope) =
        for
            label <- Signal.initRef("initial")
            color <- Signal.initRef(Three.Color.red)
            baseScene = ServerBridgeScene.scene(color)
            // The sentinel is a client-only, EXTRA trailing child (mirroring DemoHarness's own
            // captureSentinel pattern): it never changes the cube's own index-0 position, so its
            // material.color boundProp path stays identical to the server's tree.
            probeScene = baseScene.copy(children = baseScene.children :+ pixelSentinel())
            tree = UI.div(
                label.map(v => UI.span(v).id("label")),
                Three.embed(probeScene, ServerBridgeScene.camera).id("stage")
            )
            _           <- DomBackend.hydrateBackendNodes(tree)
            closeSignal <- Promise.init[Unit, Any]
            _ <- Sync.defer {
                val w = dom.window.asInstanceOf[js.Dynamic]
                w.__bridgeReady = true
                // Exposes the deterministic close trigger a browser test calls to end the mount (no
                // sleep, no interrupt race): completing the promise resumes this for-comprehension,
                // which returns, which lets the enclosing Scope.run close and run every finalizer.
                w.__closeBridge = js.Any.fromFunction0 { () =>
                    import AllowUnsafe.embrace.danger
                    closeSignal.unsafe.completeUnitDiscard()
                }
            }
            _ <- closeSignal.get
        yield ()

    /** An invisible sentinel mesh whose `onAfterRender` fires on EVERY rendered frame (unlike
      * `DemoHarness`'s one-shot probes): three.js draws children in scene order, so by the time this
      * fires (the sentinel is the LAST child, appended after the cube) the cube has already been
      * drawn into the still-live framebuffer for this frame. Reads back the ONE center pixel and
      * republishes it as a lowercase `rrggbb` hex string on `window.__stageColor`, so the test can
      * poll for the color it just drove without a separate one-shot capture per assertion. Also stashes
      * the live GL context on `window.__stageGl` (mirroring `DemoHarness.rendererReleaseProbe`'s
      * capture-then-close pattern), so a test can read `.isContextLost()` on it AFTER driving
      * `window.__closeBridge()`, proving the production teardown actually ran.
      */
    private def pixelSentinel()(using Frame): Three =
        Three.custom { (_: Unit) =>
            val geom = js.Dynamic.newInstance(ThreeFacade.BoxGeometry)(0.01, 0.01, 0.01)
            val mat  = js.Dynamic.newInstance(ThreeFacade.MeshBasicMaterial)(js.Dynamic.literal(color = 0x000000))
            val mesh = js.Dynamic.newInstance(ThreeFacade.Mesh)(geom, mat)
            mesh.frustumCulled = false
            mesh.onAfterRender = js.Any.fromFunction6 {
                (renderer: js.Dynamic, _: js.Dynamic, _: js.Dynamic, _: js.Dynamic, _: js.Dynamic, _: js.Dynamic) =>
                    val gl     = renderer.getContext()
                    val canvas = renderer.domElement
                    val w      = canvas.width.asInstanceOf[Int]
                    val h      = canvas.height.asInstanceOf[Int]
                    val buf    = new scala.scalajs.js.typedarray.Uint8Array(4)
                    val _      = gl.readPixels(w / 2, h / 2, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, buf)
                    val r      = (buf(0).asInstanceOf[Short] & 0xff)
                    val g      = (buf(1).asInstanceOf[Short] & 0xff)
                    val b      = (buf(2).asInstanceOf[Short] & 0xff)
                    val hex    = f"$r%02x$g%02x$b%02x"
                    val win    = dom.window.asInstanceOf[js.Dynamic]
                    win.__stageColor = hex
                    win.__stageGl = gl
            }
            mesh
        }(())

end ServerBridgeHydrate
