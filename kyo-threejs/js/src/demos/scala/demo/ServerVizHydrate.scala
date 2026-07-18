package demo

import kyo.*
import scala.scalajs.js.annotation.JSExportTopLevel

/** The browser half of the ServerViz app: the entry its served page loads to bring the SSR'd markup to
  * life.
  *
  * The server already rendered this page's HTML, so the browser does not build a DOM: `UI.runHydrate`
  * rebuilds the SAME tree (via the shared [[ServerVizScene.page]] builder, so every `data-kyo-path` agrees
  * with the markup by construction) and attaches the live WebGL mount to the canvas already sitting there.
  * From then on the bars are driven from across the network: the server's fiber writes the mirror, the
  * change rides the page's one WebSocket, and the client's keyed reconciler patches the columns whose
  * value moved. There is no socket handler in this file, because reactivity is not a network feature.
  */
object ServerVizHydrate:

    /** Hydrates the SSR'd ServerViz page. */
    @JSExportTopLevel("hydrateServerViz", "serverviz")
    def hydrate(): Unit =
        // Unsafe: the page-to-kyo boundary. A JS entry point is synchronous and cannot await an effect, so
        // hydration runs on a detached fiber whose ambient Scope stays open for the page's life (it holds
        // the renderer and the frame loop); Async.never parks it until the page unloads. The AllowUnsafe is
        // scoped to this one call.
        import AllowUnsafe.embrace.danger
        val page: Unit < Async =
            Scope.run {
                Abort.run[ThreeException] {
                    for
                        (scene, _) <- ServerVizScene.sceneWithMirror
                        _          <- UI.runHydrate(ServerVizScene.page(scene))
                        _          <- Async.never
                    yield ()
                }.map {
                    case Result.Success(_) => Kyo.unit
                    case Result.Failure(e) => Log.error(s"ServerViz hydrate failed: ${e.getMessage}")
                    case Result.Panic(e) =>
                        if e.isInstanceOf[Interrupted] then Kyo.unit
                        else Log.error("ServerViz hydrate panicked", e)
                }
            }
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(page).unit)
    end hydrate

end ServerVizHydrate
