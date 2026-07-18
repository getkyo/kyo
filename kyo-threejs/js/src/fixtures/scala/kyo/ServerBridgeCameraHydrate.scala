package kyo

import fixture.ServerBridgeCameraScene
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/** The client hydrate entry point for the kyo-threejs server-push CAMERA bridge browser test
  * (`ThreeBackendBridgeBrowserTest`'s server-bound `lookAt` re-aim leaf): rebuilds the SAME
  * `ServerBridgeCameraScene.ui` tree client-side (so `data-kyo-path` matches the server's SSR markup by
  * construction) and hydrates it onto the ALREADY-SSR'd DOM via the public `UI.runHydrate` entry,
  * mirroring `ServerBridgeHydrate`.
  *
  * The camera's `lookAt` binds to a client-local `target` seeded at `redTarget`: the mount's own
  * observe fiber aims the camera at red ONCE at mount and never again (the client never re-sets
  * `target`), so every subsequent re-aim is a SERVER `SetProp` on the same `lookAt` bound prop
  * (mirroring `ServerBridgeHydrate`'s client-local color seed). See `ServerBridgeHydrate` for the
  * package-`kyo`/`Frame.internal` rationale (unchanged here).
  *
  * The mount's own scope runs on a FORKED fiber that outlives the outer flow's next step, and the
  * mount stays live behind a driver-triggered `window.bridge.close()` (a `Promise` gate), mirroring
  * `ServerBridgeHydrate`'s deterministic Scope-close teardown affordance: the outer flow installs
  * `ThreeInspect` as soon as the mount handle is handed off, never sequenced behind the forked fiber's
  * own completion (which would deadlock, since that fiber only completes once `close()` is called, and
  * `close()` is reachable only through the installed projection).
  */
object ServerBridgeCameraHydrate:

    @JSExportTopLevel("hydrateServerBridgeCamera")
    def hydrateServerBridgeCamera(): Unit =
        given Frame = Frame.internal
        // Unsafe: the page-to-kyo boundary; runs hydration on a detached fiber whose ambient Scope
        // stays open for the mount's frame loop and observe fibers, mirroring ServerBridgeHydrate.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Scope.run(hydrateAndWait)).unit)
    end hydrateServerBridgeCamera

    private def hydrateAndWait(using Frame): Unit < (Async & Scope) =
        for
            // Seeded at redTarget so the mount's lookAt observe aims red once at mount; the client
            // never re-sets it, so only a server SetProp re-aims. Matches the server's SSR seed, so
            // data-kyo-path agrees by construction.
            target      <- Signal.initRef(ServerBridgeCameraScene.redTarget)
            mountSignal <- Promise.init[Three.Mount, Any]
            closeSignal <- Promise.init[Unit, Any]
            scene = ServerBridgeCameraScene.scene
            embed = Three.embed(scene, ServerBridgeCameraScene.camera(target)).id("stage")
            tree  = UI.div(embed)
            _ <- Fiber.init(Scope.run {
                for
                    _     <- UI.runHydrate(tree)
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
            _ <- Async.never
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

end ServerBridgeCameraHydrate
