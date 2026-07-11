package kyo

import demo.ServerBridgeScene
import kyo.internal.DomBackend
import kyo.internal.ThreeFacade
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/** The client hydrate for the register-before-push startup-buffer browser test
  * (`ThreeBackendBridgeBrowserTest`'s pre-registration leaf). Rebuilds the SAME `ServerBridgeScene.ui`
  * tree client-side, but GATES the backend registration behind `window.__releaseHydrate()`: it sets
  * `window.__preRegisterReady` and installs the release trigger BEFORE calling
  * `DomBackend.hydrateBackendNodes`, then parks on a `Promise` until the test releases it.
  *
  * This opens the exact window CR-02 is about: the inline WebSocket client (in the SSR'd page) connects
  * and the server pushes its initial `SetProp` per bound prop immediately, while the island has not yet
  * registered its `{patch,replaceSubtree}` handle. A dropped-buffer implementation would lose those
  * ops; the shipped startup buffer holds them per root and flushes them in order on registration, so the
  * live cube converges to whatever the server drove during the gap.
  *
  * Lives in package `kyo` (not `demoharness`) because `hydrateBackendNodes` is `private[kyo]`;
  * `Frame.internal` is the zero-derivation frame package `kyo` non-test code needs.
  */
object ServerBridgePreRegisterHydrate:

    @JSExportTopLevel("hydrateServerBridgePreRegister")
    def hydrateServerBridgePreRegister(): Unit =
        given Frame = Frame.internal
        // Unsafe: the page-to-kyo boundary; runs hydration on a detached fiber whose ambient Scope stays
        // open for the mount's frame loop and observe fibers, mirroring DemoHarness's own entries.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Scope.run(hydrateAfterGate)).unit)
    end hydrateServerBridgePreRegister

    private def hydrateAfterGate(using Frame): Unit < (Async & Scope) =
        for
            label <- Signal.initRef("initial")
            color <- Signal.initRef(Three.Color.red)
            baseScene  = ServerBridgeScene.scene(color)
            probeScene = baseScene.copy(children = baseScene.children :+ pixelSentinel())
            tree = UI.div(
                label.map(v => UI.span(v).id("label")),
                Three.embed(probeScene, ServerBridgeScene.camera).id("stage")
            )
            gate <- Promise.init[Unit, Any]
            _ <- Sync.defer {
                val w = dom.window.asInstanceOf[js.Dynamic]
                // The inline WS client is already up and the server's initial SetProps are arriving into
                // the startup buffer; signal the test that the pre-registration window is open, and hand
                // it the release trigger (completing the gate resumes hydration -> registration).
                w.__preRegisterReady = true
                w.__releaseHydrate = js.Any.fromFunction0 { () =>
                    import AllowUnsafe.embrace.danger
                    gate.unsafe.completeUnitDiscard()
                }
            }
            _ <- gate.get                             // BLOCK: no backend registers until the test releases the gate
            _ <- DomBackend.hydrateBackendNodes(tree) // registration flushes the buffered SetProps in order
            _ <- Sync.defer(dom.window.asInstanceOf[js.Dynamic].__bridgeReady = true)
            _ <- Async.never                          // park so the mount and its frame loop stay live
        yield ()

    /** An invisible sentinel mesh whose `onAfterRender` republishes the rendered center pixel as a
      * lowercase `rrggbb` hex on `window.__stageColor` each frame, so the test can poll for the color the
      * flushed SetProps drove onto the live material. Mirrors `ServerBridgeHydrate.pixelSentinel`.
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
                    dom.window.asInstanceOf[js.Dynamic].__stageColor = f"$r%02x$g%02x$b%02x"
            }
            mesh
        }(())

end ServerBridgePreRegisterHydrate
