package fixture

import kyo.*

/** The scene that genuinely OVERFLOWS the inline client's startup buffer.
  *
  * The buffer holds one slot per bound prop and is bounded, so an op can still be lost to an eviction. A
  * lost op leaves the scene rendering its last good state, which is indistinguishable from an op that was
  * applied, so the eviction has to say so; a silent eviction inside the drop-reporting fix would be the
  * same failure the fix exists to remove. Proving it speaks needs the buffer to actually overflow.
  *
  * Reaching that through the ordinary server-push path is what this scene is for. The cap is 256 slots, so
  * [[propCount]] distinct bound props overflow it by exactly one: the server pushes an initial `SetProp`
  * per bound prop the moment the session subscribes, all of them landing while the island's registration is
  * still gated, and the last one to arrive must evict the first. Nothing is fabricated and no production
  * code is widened to observe it, which is the point: the allocation, the eviction and the report are all
  * the shipped path.
  */
object ServerBridgeOverflowScene:

    /** One more bound prop than the client's startup buffer can hold (its cap is 256). */
    val propCount: Int = 257

    /** The viewing camera. Its own props are constants, so it contributes no bound prop and no slot. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(position = Three.Vec3(0, 0, 4), lookAt = Three.Vec3.zero)

    /** One unlit cube per signal, each with its own `material.color` bound to its own signal, so each is a
      * DISTINCT slot: a slot is one bound prop of one object, and objects are addressed by path. The cubes
      * are stacked at the origin and never looked at. What is under test is the buffer, not the picture.
      */
    def scene(colors: Seq[Signal[Three.Color]])(using Frame): Three.Ast.Scene =
        Three.scene(
            colors.map(c => Three.mesh(Three.Geometry.box(1.0, 1.0, 1.0), Three.Material.basic().color(c)))*
        )

    /** The page body: the DOM label (the ordering fence the test drives last, since a DOM region is applied
      * straight to the page and never buffered) alongside the embedded overflow scene.
      */
    def ui(label: Signal[String], colors: Seq[Signal[Three.Color]])(using Frame): UI =
        UI.div(
            label.map(v => UI.span(v).id("label")),
            Three.embed(scene(colors), camera).id("stage")
        )
    end ui

end ServerBridgeOverflowScene
