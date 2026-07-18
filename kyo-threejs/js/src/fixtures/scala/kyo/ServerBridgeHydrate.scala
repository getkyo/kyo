package kyo

import fixture.ServerBridgeScene
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/** The client hydrate entry point for the kyo-threejs server-push bridge browser tests
  * (`ThreeBackendBridgeBrowserTest`, `ThreeStructuralBridgeBrowserTest`): rebuilds the SAME
  * `ServerBridgeScene.ui` tree client-side (so `data-kyo-path` matches the server's SSR markup by
  * construction) and hydrates it onto the ALREADY-SSR'd DOM via the public `UI.runHydrate` entry --
  * registering the embedded `Three.embed` canvas's live mount WITHOUT touching `container.innerHTML`.
  * Reactivity itself rides the page's own inline WS listener (`HtmlRenderer.clientJs`), unrelated to
  * this entry point; this only gets the 3D canvas's live mount registered so that listener's dispatch
  * has somewhere to land.
  *
  * Lives in package `kyo` (not `fixture`) because it uses the `private[kyo]` `Frame.internal`, the
  * zero-derivation frame this entry point needs since package `kyo` non-test code cannot auto-derive
  * one.
  *
  * The mount's own scope runs on a FORKED fiber that outlives the outer flow's next step (mirroring
  * `fixture.MountInspectProbes`'s teardown pattern) and stays open behind a driver-triggered
  * `window.bridge.close()` (a `Promise` gate, never a sleep) rather than closing on a bounded internal
  * condition. The outer flow installs `ThreeInspect` as soon as the mount handle is handed off (never
  * sequenced behind the forked fiber's own completion, which would deadlock: that fiber does not
  * complete until `close()` is called, and `close()` is only reachable through the installed
  * projection), so a browser test drives the bridge live, THEN calls `close()`, THEN reads
  * `contextLost()`/`disposed()` on the SAME projection, which survives the mount scope's close because
  * it was installed outside it.
  */
object ServerBridgeHydrate:

    @JSExportTopLevel("hydrateServerBridge")
    def hydrateServerBridge(): Unit =
        given Frame = Frame.internal
        // Unsafe: the page-to-kyo boundary; runs hydration on a detached fiber whose ambient Scope
        // stays open for the mount's frame loop and observe fibers.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Scope.run(hydrateAndWait)).unit)
    end hydrateServerBridge

    private def hydrateAndWait(using Frame): Unit < (Async & Scope) =
        for
            label       <- Signal.initRef("initial")
            color       <- Signal.initRef(Three.Color.red)
            mountSignal <- Promise.init[Three.Mount, Any]
            closeSignal <- Promise.init[Unit, Any]
            scene = ServerBridgeScene.scene(color)
            embed = Three.embed(scene, ServerBridgeScene.camera).id("stage")
            tree  = UI.div(label.map(v => UI.span(v).id("label")), embed)
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

end ServerBridgeHydrate
