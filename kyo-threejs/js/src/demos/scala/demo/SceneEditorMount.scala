package demo

import kyo.*
import scala.scalajs.js.annotation.JSExportTopLevel

/** The browser half of the SceneEditor app: the entry its served page imports and calls.
  *
  * `UI.runMount` builds the tree and takes over the host element, so the raycast clicks that select an
  * object, and the panel that re-renders around the selection, both run in the browser.
  */
object SceneEditorMount:

    /** Mounts [[SceneEditorScene.ui]] into the element `selector` names. */
    @JSExportTopLevel("mountSceneEditor", "sceneeditor")
    def mount(selector: String): Unit =
        // Unsafe: the page-to-kyo boundary. A JS entry point is synchronous and cannot await an effect, so
        // the mount runs on a detached fiber whose ambient Scope stays open for the page's life (it holds
        // the renderer, the frame loop and every observe fiber); Async.never parks it until the page
        // unloads. The AllowUnsafe is scoped to this one call.
        import AllowUnsafe.embrace.danger
        val page: Unit < Async =
            Scope.run {
                Abort.run[ThreeException](SceneEditorScene.ui.map(tree => UI.runMount(tree, selector))).map {
                    case Result.Success(_) => Async.never
                    case Result.Failure(e) => Log.error(s"SceneEditor mount failed: ${e.getMessage}")
                    case Result.Panic(e) =>
                        if e.isInstanceOf[Interrupted] then Kyo.unit
                        else Log.error("SceneEditor mount panicked", e)
                }
            }
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(page).unit)
    end mount

end SceneEditorMount
