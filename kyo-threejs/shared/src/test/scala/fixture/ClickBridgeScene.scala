package fixture

import kyo.*
import kyo.Three.foreachKeyed
import kyo.Three.render

/** The interaction-half sibling of `ServerBridgeScene`/`ServerBridgeStructuralScene`: TWO embedded
  * canvases whose click resolves SERVER-SIDE through `resolvePointer`, exercising the two corners the
  * interaction half's path->Live index maintenance covers -- a `foreach`-keyed child and a `render`/
  * `when` boundary's content -- each addressed through the spliced-child path->Live index. ONE shared
  * server-owned `SignalRef[String]` both cubes' `onClick` closures write to (the closure is built
  * server-side and resolved there, so it mutates the signal directly with no wire event type),
  * reflected to a DOM label the browser test reads back with no pixel sampling needed.
  *
  * Each canvas holds exactly ONE clickable cube CENTERED in its own camera's view (NDC `(0, 0)`), so
  * `Browser.click(selector)`'s center-of-element dispatch lands the raycast on it directly.
  */
object ClickBridgeScene:

    /** The viewing camera, shared by both canvases, framing the single centered cube head-on. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(position = Three.Vec3(0, 0, 4), lookAt = Three.Vec3.zero)

    /** Canvas A: a single-item `foreachKeyed` cube; its `onClick` writes its own key into
      * `lastClicked`, exercising `resolvePointer`'s `Ast.Foreach` arm.
      */
    def foreachScene(lastClicked: SignalRef[String])(using Frame): Three.Ast.Scene =
        val items = Signal.initConst(Chunk("foreach-hit"))
        Three.scene(
            items.foreachKeyed(identity) { key =>
                Three.mesh(Three.Geometry.box(2.0, 2.0, 2.0), Three.Material.basic())
                    .onClick(_ => lastClicked.set(key))
            }
        )
    end foreachScene

    /** Canvas B: a `render`/`when` boundary whose CURRENT content's `onClick` writes `"render-hit"`
      * into `lastClicked`, exercising `resolvePointer`'s `Ast.Reactive` arm (the boundary's condition
      * never toggles, so `relPath` resolves at the boundary's OWN path with no extra segment).
      */
    def reactiveScene(lastClicked: SignalRef[String])(using Frame): Three.Ast.Scene =
        val cond = Signal.initConst(true)
        Three.scene(
            cond.render(_ =>
                Three.mesh(Three.Geometry.box(2.0, 2.0, 2.0), Three.Material.basic())
                    .onClick(_ => lastClicked.set("render-hit"))
            )
        )
    end reactiveScene

    /** Builds the page body: the two embedded canvases plus a DOM label reflecting `lastClicked`. */
    def ui(lastClicked: SignalRef[String])(using Frame): UI =
        UI.div(
            Three.embed(foreachScene(lastClicked), camera).id("stage-foreach"),
            Three.embed(reactiveScene(lastClicked), camera).id("stage-reactive"),
            lastClicked.map(v => UI.span(v).id("clicked-label"))
        )

end ClickBridgeScene
