package demo

import kyo.*
import scala.scalajs.js.annotation.JSExportTopLevel

/** The browser half of the GltfInspector app: the entry its served page imports and calls.
  *
  * The page passes the model's URL, so the app that SERVES the model is the app that names it: the entry
  * never assumes a route exists. `Three.loadGltf` fetches it and fails typed, and because that failure is
  * in the row, this entry cannot compile without deciding what a failed load looks like. It decides to
  * MOUNT it: [[GltfInspectorScene.failed]] renders the typed error into the same status line a successful
  * load describes itself in, so a model the server does not serve says so on the page. Logging it instead
  * would leave a black canvas and a message no viewer ever reads.
  */
object GltfInspectorMount:

    /** Mounts [[GltfInspectorScene.ui]] into the element `selector` names, loading the glTF at `modelUrl`. */
    @JSExportTopLevel("mountGltfInspector", "gltfinspector")
    def mount(selector: String, modelUrl: String): Unit =
        // Unsafe: the page-to-kyo boundary. A JS entry point is synchronous and cannot await an effect, so
        // the mount runs on a detached fiber whose ambient Scope stays open for the page's life (it holds
        // the renderer, the frame loop and every observe fiber); Async.never parks it until the page
        // unloads. The AllowUnsafe is scoped to this one call.
        import AllowUnsafe.embrace.danger
        val page: Unit < Async =
            Scope.run {
                Abort.run[ThreeException](GltfInspectorScene.ui(modelUrl).map(tree => UI.runMount(tree, selector))).map {
                    case Result.Success(_) => Async.never
                    case Result.Failure(e) =>
                        UI.runMount(GltfInspectorScene.failed(e), selector).andThen(Async.never)
                    case Result.Panic(e) =>
                        if e.isInstanceOf[Interrupted] then Kyo.unit
                        else Log.error("GltfInspector mount panicked", e)
                }
            }
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(page).unit)
    end mount

end GltfInspectorMount
