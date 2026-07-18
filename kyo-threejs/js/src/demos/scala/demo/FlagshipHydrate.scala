package demo

import kyo.*
import scala.scalajs.js.annotation.JSExportTopLevel

/** The browser half of the Flagship app: the entry its served page loads to bring the SSR'd markup to
  * life.
  *
  * `UI.runHydrate` rebuilds the tree [[FlagshipScene.ui]] describes and attaches the live WebGL mount to
  * the canvas the server already rendered. The page is then driven from BOTH sides at once, and each
  * region has exactly one writer: the cube's colour steps from the server and its clicks resolve there,
  * while the HUD beside it is marked `clientOwned`, so the browser owns that subtree. It has to: the frame
  * count reads `Three.Mount.renders` and the Capture button calls `Three.Mount.readPixels`, and a mount is
  * a live WebGL renderer that exists nowhere but here.
  */
object FlagshipHydrate:

    /** Hydrates the SSR'd Flagship page, forking the live frame-count observer [[FlagshipScene.ui]] carries. */
    @JSExportTopLevel("hydrateFlagship", "flagship")
    def hydrate(): Unit =
        // Unsafe: the page-to-kyo boundary. A JS entry point is synchronous and cannot await an effect, so
        // hydration runs on a detached fiber whose ambient Scope stays open for the page's life (it holds
        // the renderer, the frame loop and the mount observer); Async.never parks it until the page
        // unloads. The AllowUnsafe is scoped to this one call.
        import AllowUnsafe.embrace.danger
        val page: Unit < Async =
            Scope.run {
                Abort.run[ThreeException] {
                    for
                        tree <- FlagshipScene.ui
                        _    <- UI.runHydrate(tree)
                        _    <- Async.never
                    yield ()
                }.map {
                    case Result.Success(_) => Kyo.unit
                    case Result.Failure(e) => Log.error(s"Flagship hydrate failed: ${e.getMessage}")
                    case Result.Panic(e) =>
                        if e.isInstanceOf[Interrupted] then Kyo.unit
                        else Log.error("Flagship hydrate panicked", e)
                }
            }
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(page).unit)
    end hydrate

end FlagshipHydrate
