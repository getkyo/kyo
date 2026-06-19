package demo

import kyo.*

/** A 3D scene embedded as a first-class child of a kyo-ui tree: demonstrates `Three.embed` with
  * surrounding kyo-ui controls and a HUD, all sharing a single `SignalRef[String]`. A button in
  * the kyo-ui controls panel writes "Sun" into the shared signal; clicking the 3D earth sphere
  * writes "Earth"; the HUD label below the canvas tracks both, proving bidirectional
  * `SignalRef` interop through the embed seam.
  *
  * The `camera` def is pure (no effect); `scene` and `ui` carry `< Sync` from `Signal.initRef`.
  */
object EmbeddedScene:

    /** Builds the 3D scene for the embedded view: a sun sphere and an orbiting earth sphere, each
      * clickable. The earth group orbits via `onFrame`. Returns both the scene AST and the shared
      * selection `SignalRef[String]` the kyo-ui controls and HUD bind to.
      */
    def scene(using Frame): (Three.Ast.Scene, SignalRef[String]) < Sync =
        for
            selected   <- Signal.initRef("Sun")
            earthAngle <- Signal.initRef(0.0)
            sun = Three.mesh(
                Three.Geometry.sphere(1.0),
                Three.Material.standard().emissive(Color.yellow)
            ).onClick(_ => selected.set("Sun"))
            earth = Three.group(
                Three.mesh(
                    Three.Geometry.sphere(0.3),
                    Three.Material.standard(color = Color.blue)
                ).position(Vec3(4, 0, 0))
                    .onClick(_ => selected.set("Earth"))
            ).rotation(earthAngle.map(a => Vec3(0, a, 0)))
                .onFrame(t => earthAngle.updateAndGet(_ + t.delta.toMillis * 0.001))
        yield (
            Three.scene(
                Three.Light.ambient(intensity = 0.3),
                Three.Light.point(position = Vec3.zero),
                sun,
                earth
            ),
            selected
        )

    /** The viewing camera: above and behind the scene, aimed at the origin. Pure: returns the
      * camera AST value directly with no effect.
      */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(
            fov = Radians.deg(60),
            position = Vec3(0, 7, 7),
            lookAt = Vec3.zero
        )

    /** Composes the full kyo-ui tree: a control panel (button writing "Sun" into the shared
      * signal), the embedded 3D canvas (via `Three.embed`), and a HUD label that reads the
      * shared signal reactively. The entire tree is a plain `UI < Sync` because `scene` is
      * `< Sync` and the rest of the composition is pure.
      */
    def ui(using Frame): UI < Sync =
        scene.map { case (built, selected) =>
            val controls = UI.div(
                UI.button("Focus Sun").id("focus-sun").onClick(selected.set("Sun"))
            )
            val embed = Three.embed(built, camera).id("stage")
            val hud = UI.div(
                UI.p(selected.map(s => s"Selected: $s")).id("selected-label")
            )
            UI.div(controls, embed, hud)
        }

end EmbeddedScene
