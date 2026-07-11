package kyo

import demo.ServerBridgeCameraScene
import kyo.internal.ThreeFacade
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/** The client hydrate entry point for the kyo-threejs server-push CAMERA bridge browser test
  * (`ThreeBackendBridgeBrowserTest`'s server-bound `lookAt` re-aim leaf): rebuilds the SAME
  * `ServerBridgeCameraScene.ui` tree client-side (so `data-kyo-path` matches the server's SSR markup
  * by construction) and hydrates it onto the ALREADY-SSR'd DOM via the public `UI.runHydrate` entry,
  * mirroring `ServerBridgeHydrate`.
  *
  * The camera's `lookAt` binds to a client-local `target` seeded at `redTarget`: the mount's own
  * observe fiber aims the camera at red ONCE at mount and never again (the client never re-sets
  * `target`), so every subsequent re-aim is a SERVER `SetProp` on the same `lookAt` bound prop
  * (mirroring `ServerBridgeHydrate`'s client-local color seed, which reds the cube once while the
  * server drives the later greens/blues). See `ServerBridgeHydrate` for the package-`kyo`/
  * `Frame.internal` rationale (unchanged here).
  *
  * The mount stays live behind a `window.__closeBridge()` trigger (a `Promise` awaited instead of
  * `Async.never`), mirroring `ServerBridgeHydrate`'s deterministic Scope-close teardown affordance.
  */
object ServerBridgeCameraHydrate:

    @JSExportTopLevel("hydrateServerBridgeCamera")
    def hydrateServerBridgeCamera(): Unit =
        given Frame = Frame.internal
        // Unsafe: the page-to-kyo boundary; runs hydration on a detached fiber whose ambient Scope
        // stays open for the mount's frame loop and observe fibers, mirroring ServerBridgeHydrate.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(
            Fiber.initUnscoped(
                Scope.run(hydrateAndWait).andThen(Sync.defer {
                    dom.window.asInstanceOf[js.Dynamic].__bridgeClosed = true
                })
            ).unit
        )
    end hydrateServerBridgeCamera

    private def hydrateAndWait(using Frame): Unit < (Async & Scope) =
        for
            // Seeded at redTarget so the mount's lookAt observe aims red once at mount; the client
            // never re-sets it, so only a server SetProp re-aims. Matches the server's SSR seed, so
            // data-kyo-path agrees by construction.
            target <- Signal.initRef(ServerBridgeCameraScene.redTarget)
            baseScene = ServerBridgeCameraScene.scene
            // The sentinel is a client-only, EXTRA trailing child (mirroring ServerBridgeHydrate): it
            // reads back the rendered center pixel every frame without shifting either plane's index,
            // so the camera's lookAt boundProp path stays identical to the server's tree.
            probeScene = baseScene.copy(children = baseScene.children :+ pixelSentinel())
            tree       = UI.div(Three.embed(probeScene, ServerBridgeCameraScene.camera(target)).id("stage"))
            _           <- UI.runHydrate(tree)
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

    /** An invisible sentinel mesh whose `onAfterRender` fires on EVERY rendered frame: three.js draws
      * children in scene order, so by the time this fires (the sentinel is the LAST child, appended
      * after the planes) both planes have already been drawn into the still-live framebuffer for this
      * frame. Reads back the ONE center pixel and republishes it as a lowercase `rrggbb` hex string on
      * `window.__stageColor`, so the test can poll for the color the current camera aim projects to the
      * frame center. Mirrors `ServerBridgeHydrate.pixelSentinel`. Positioned at the origin (default),
      * which sits in the gap between the two planes and off BOTH aim centers, so the sentinel itself
      * never covers the sampled center pixel.
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

end ServerBridgeCameraHydrate
