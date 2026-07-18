package demo

import kyo.*
import scala.scalajs.js.annotation.JSExportTopLevel

/** The browser half of the PlayableSnake app: the entry its served page imports and calls.
  *
  * `UI.runMount` builds the tree and takes over the host element, so the document-level keyboard steering,
  * the on-screen arrow buttons and the fixed-interval game tick all run in the browser.
  */
object PlayableSnakeMount:

    /** Mounts [[PlayableSnakeScene.ui]] into the element `selector` names. */
    @JSExportTopLevel("mountPlayableSnake", "playablesnake")
    def mount(selector: String): Unit =
        // Unsafe: the page-to-kyo boundary. A JS entry point is synchronous and cannot await an effect, so
        // the mount runs on a detached fiber whose ambient Scope stays open for the page's life (it holds
        // the renderer, the frame loop and every observe fiber); Async.never parks it until the page
        // unloads. The AllowUnsafe is scoped to this one call.
        import AllowUnsafe.embrace.danger
        val page: Unit < Async =
            Scope.run {
                Abort.run[ThreeException](PlayableSnakeScene.ui.map(tree => UI.runMount(tree, selector))).map {
                    case Result.Success(_) => Async.never
                    case Result.Failure(e) => Log.error(s"PlayableSnake mount failed: ${e.getMessage}")
                    case Result.Panic(e) =>
                        if e.isInstanceOf[Interrupted] then Kyo.unit
                        else Log.error("PlayableSnake mount panicked", e)
                }
            }
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(page).unit)
    end mount

end PlayableSnakeMount
