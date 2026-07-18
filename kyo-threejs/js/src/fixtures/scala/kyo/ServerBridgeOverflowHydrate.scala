package kyo

import fixture.ServerBridgeOverflowScene
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/** The client hydrate for the startup-buffer OVERFLOW browser test (`ThreeBackendBridgeBrowserTest`).
  * Rebuilds the SAME `fixture.ServerBridgeOverflowScene.ui` tree the server rendered, and GATES the
  * backend registration behind `window.releaseHydrate()` exactly as `ServerBridgePreRegisterHydrate` does,
  * so every one of the server's initial `SetProp`s lands in the startup buffer with no handle to apply it.
  *
  * The scene carries one more bound prop than the buffer can hold, so the last of those ops must evict the
  * first. The gate is what makes that reachable: without it the island would register before the burst
  * arrived and every op would apply straight through, and the eviction path would never run.
  *
  * Lives in package `kyo` (not `fixture`) because it uses the `private[kyo]` `Frame.internal`, the
  * zero-derivation frame package `kyo` non-test code needs.
  */
object ServerBridgeOverflowHydrate:

    @JSExportTopLevel("hydrateServerBridgeOverflow")
    def hydrateServerBridgeOverflow(): Unit =
        given Frame = Frame.internal
        // Unsafe: the page-to-kyo boundary; runs hydration on a detached fiber whose ambient Scope stays
        // open for the mount's frame loop and observe fibers.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Scope.run(hydrateAfterGate)).unit)
    end hydrateServerBridgeOverflow

    private def hydrateAfterGate(using Frame): Unit < (Async & Scope) =
        for
            label <- Signal.initRef("initial")
            // The client's seeds match the server's, so the SSR'd tree and this one agree on every path
            // before any op is flushed.
            colors <- Kyo.foreach(Chunk.fill(ServerBridgeOverflowScene.propCount)(()))(_ =>
                Signal.initRef(Three.Color.red)
            )
            gate <- Promise.init[Unit, Any]
            tree = ServerBridgeOverflowScene.ui(label, colors)
            _ <- Sync.defer {
                val w = dom.window.asInstanceOf[js.Dynamic]
                // The inline WS client is already up and the server's initial SetProps are filling the
                // startup buffer; tell the test the pre-registration window is open and hand it the release.
                w.preRegisterReady = true
                w.releaseHydrate = js.Any.fromFunction0 { () =>
                    import AllowUnsafe.embrace.danger
                    gate.unsafe.completeUnitDiscard()
                }
            }
            _ <- gate.get    // BLOCK: no backend registers until the test releases the gate
            _ <- UI.runHydrate(tree)
            _ <- Async.never // park so the mount and its frame loop stay live
        yield ()

end ServerBridgeOverflowHydrate
