package fixture

import kyo.*

/** A minimal `Three.embed` fixture: one clickable lit sphere and a shared selection `SignalRef[String]`
  * a HUD label observes. Built for `ThreeEmbedBrowserTest`'s assertions: a click on the sphere writes
  * the selection, and the sphere renders in a known, non-black pixel region at the frame center.
  */
object EmbedInspectScene:

    /** Builds the 3D scene: one clickable sphere lit by an ambient and a point light, centered so the
      * frame's center pixel samples it, and the shared selection signal its `onClick` writes into.
      */
    def scene(using Frame): (Three.Ast.Scene, SignalRef[String]) < Sync =
        for
            selected <- Signal.initRef("")
            sphere = Three.mesh(
                Three.Geometry.sphere(1.2),
                Three.Material.standard(color = Three.Color(0xff5533))
            ).onClick(_ => selected.set("sphere"))
        yield (
            Three.scene(
                Three.Light.ambient(intensity = 0.6),
                Three.Light.point(position = Three.Vec3(3, 4, 5)),
                sphere
            ),
            selected
        )

    /** The viewing camera: centered on the sphere. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(position = Three.Vec3(0, 0, 5), lookAt = Three.Vec3.zero)

end EmbedInspectScene
