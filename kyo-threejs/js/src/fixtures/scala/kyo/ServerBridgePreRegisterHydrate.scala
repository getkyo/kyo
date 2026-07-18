package kyo

import fixture.ServerBridgeScene
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/** The client hydrate for the register-before-push startup-buffer browser test
  * (`ThreeBackendBridgeBrowserTest`'s pre-registration leaf). Rebuilds the SAME `ServerBridgeScene.ui`
  * tree client-side, but GATES the backend registration behind `window.releaseHydrate()`: it sets
  * `window.preRegisterReady` and installs the release trigger BEFORE calling the public
  * `UI.runHydrate` entry, then parks on a `Promise` until the test releases it.
  *
  * This opens the exact register-before-push window: the inline WebSocket client (in the SSR'd page)
  * connects and the server pushes its initial `SetProp` per bound prop immediately, while the island
  * has not yet registered its `{patch,replaceSubtree}` handle. A dropped-buffer implementation would
  * lose those ops; the shipped startup buffer holds one slot per bound prop and flushes them all on
  * registration, so the live cube converges to whatever the server drove during the gap. The scene binds
  * TWO props (`material.color` and `scale`), so the buffer holds two distinct slots and the flush has more
  * than one op to lose. The pre-registration buffer state itself rides the retained kyo-ui Backend SPI
  * (`window.__kyoBackends` / `window.__kyoBackendsPending`), never touched here.
  *
  * Lives in package `kyo` (not `fixture`) because it uses the `private[kyo]` `Frame.internal`, the
  * zero-derivation frame package `kyo` non-test code needs.
  */
object ServerBridgePreRegisterHydrate:

    @JSExportTopLevel("hydrateServerBridgePreRegister")
    def hydrateServerBridgePreRegister(): Unit =
        given Frame = Frame.internal
        // Unsafe: the page-to-kyo boundary; runs hydration on a detached fiber whose ambient Scope stays
        // open for the mount's frame loop and observe fibers.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Scope.run(hydrateAfterGate)).unit)
    end hydrateServerBridgePreRegister

    private def hydrateAfterGate(using Frame): Unit < (Async & Scope) =
        for
            label <- Signal.initRef("initial")
            color <- Signal.initRef(Three.Color.red)
            // The second bound prop, so the startup buffer holds TWO slots while the gate is closed. Its
            // seed matches the server's, so the SSR'd tree and this one agree before any op is flushed.
            scale       <- Signal.initRef(Three.Vec3(1, 1, 1))
            mountSignal <- Promise.init[Three.Mount, Any]
            closeSignal <- Promise.init[Unit, Any]
            scene = ServerBridgeScene.preRegisterScene(color, scale)
            embed = Three.embed(scene, ServerBridgeScene.camera).id("stage")
            tree  = UI.div(label.map(v => UI.span(v).id("label")), embed)
            gate <- Promise.init[Unit, Any]
            _ <- Sync.defer {
                val w = dom.window.asInstanceOf[js.Dynamic]
                // The inline WS client is already up and the server's initial SetProps are arriving into
                // the startup buffer; signal the test that the pre-registration window is open, and hand
                // it the release trigger (completing the gate resumes hydration -> registration).
                w.preRegisterReady = true
                w.releaseHydrate = js.Any.fromFunction0 { () =>
                    import AllowUnsafe.embrace.danger
                    gate.unsafe.completeUnitDiscard()
                }
            }
            _ <- gate.get // BLOCK: no backend registers until the test releases the gate
            // The mount's own scope runs on a FORKED fiber that outlives this flow's next step (mirroring
            // ServerBridgeHydrate): installing ThreeInspect must never sequence behind this fiber's own
            // completion, since it only completes once close() is called, and close() is reachable only
            // through the installed projection.
            _ <- Fiber.init(Scope.run {
                for
                    _     <- UI.runHydrate(tree) // registration flushes the buffered SetProps in order
                    mount <- awaitPresent(embed.mounted)
                    _     <- mountSignal.complete(Result.Success(mount))
                    _     <- closeSignal.get
                yield ()
            })
            mount <- mountSignal.get
            _ <- ThreeInspect.install("bridge", mount).andThen(Sync.Unsafe.defer {
                // Unsafe: adds the driver-triggered close to the already-installed projection at
                // the page-to-kyo boundary.
                val w = dom.window.asInstanceOf[js.Dynamic]
                w.bridge.updateDynamic("close")((() =>
                    import AllowUnsafe.embrace.danger
                    val _ = Sync.Unsafe.evalOrThrow(closeSignal.complete(Result.Success(())).unit)
                ): js.Function0[Unit])
            })
            _ <- Async.never // park so the mount and its frame loop stay live
        yield ()

    /** Waits for `mounted` to reach `Present`, reading the CURRENT value first so a transition that
      * already happened is not missed by an unconditional `.next`.
      */
    private def awaitPresent(mounted: Signal[Maybe[Three.Mount]])(using Frame): Three.Mount < Async =
        mounted.current.map {
            case Present(m) => m
            case Absent =>
                mounted.next.map {
                    case Present(m) => m
                    case Absent     => awaitPresent(mounted)
                }
        }

end ServerBridgePreRegisterHydrate
