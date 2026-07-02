package kyo

import demo.ClickBridgeScene
import kyo.internal.DomBackend
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/** The client hydrate entry point for the kyo-threejs interaction-half browser test
  * (`ThreeStructuralBridgeBrowserTest`'s click assertions): rebuilds the SAME `ClickBridgeScene.ui`
  * tree client-side (so `data-kyo-path` matches the server's SSR markup by construction) and hydrates
  * it onto the ALREADY-SSR'd DOM via `DomBackend.hydrateBackendNodes`, mirroring `ServerBridgeHydrate`.
  * Both embedded canvases' live mounts register (raycast + pointer delegation), so a real click on
  * either canvas posts a `BackendEvent` the server resolves.
  */
object ClickBridgeHydrate:

    @JSExportTopLevel("hydrateClickBridge")
    def hydrateClickBridge(): Unit =
        given Frame = Frame.internal
        // Unsafe: the page-to-kyo boundary; runs hydration on a detached fiber whose ambient Scope
        // stays open for both mounts' frame loops and pointer delegation.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(
            Fiber.initUnscoped(Scope.run(hydrateAndWait)).unit
        )
    end hydrateClickBridge

    private def hydrateAndWait(using Frame): Unit < (Async & Scope) =
        for
            // The client's local placeholder starts at the SAME initial value the server seeds
            // (mirroring ServerBridgeHydrate's label/color seeds); never observed client-side (hydrate
            // subscribes no reactivity), only used to construct the identical tree shape.
            lastClicked <- Signal.initRef("none")
            tree = ClickBridgeScene.ui(lastClicked)
            _ <- DomBackend.hydrateBackendNodes(tree)
            _ <- Sync.defer(dom.window.asInstanceOf[js.Dynamic].__bridgeReady = true)
            _ <- Async.never
        yield ()

end ClickBridgeHydrate
