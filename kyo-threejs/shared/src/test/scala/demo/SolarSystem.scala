package demo

import kyo.*

/** Nested groups orbiting on a `Raf`-driven `onFrame`, with raycast-to-select and a shared
  * `SignalRef[String]` tracking the selected body. Demonstrates groups, the frame loop, reactive
  * transforms, signal-driven interaction, and kyo-ui HUD composition alongside a kyo-threejs scene.
  *
  * The orbit angle advances via `onFrame` on the earth `Group` (groups are `Animated` and carry
  * their own frame hook). The earth group's `rotation` binds to the same `SignalRef[Double]` via
  * `.rotation(signal)`, so the transform updates reactively on each emission. Clicking sun or earth
  * writes the selected body name into a `SignalRef[String]` shared with the HUD.
  *
  * `UI.runMount` (kyo-ui) and `Three.runMount` (kyo-threejs) both resolve with `import kyo.*`:
  * each is forked as a concurrent `Fiber`, both driven by the same ambient `Scope`.
  */
object SolarSystem extends KyoApp:
    run {
        for
            built <- SolarSystemScene.scene
            (scene, selected) = built
            hud = UI.div(
                UI.p(selected.map(s => s"Selected: $s"))
            )
            _ <- Fiber.init(UI.runMount(hud, "#hud")).unit
            _ <- Three.runMount(scene, SolarSystemScene.camera, "#app", ThreeFrames.Raf)
        yield ()
    }
end SolarSystem

/** The scene-graph builder for [[SolarSystem]], kept off the `KyoApp` object so it carries no
  * `UI.runMount` reference. The harness mounts this scene without the kyo-ui HUD; the `KyoApp`
  * composes the same scene with its HUD via the returned selection signal.
  */
object SolarSystemScene:

    /** A selectable solar-system scene: a `SignalRef[String]` tracks the clicked body, the earth
      * `Group` orbits via `onFrame`, and its rotation binds to a `SignalRef[Double]`. Returns the
      * scene and the selection signal the HUD binds to.
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

    /** The viewing camera, positioned above the orbital plane, aimed at the sun. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(
            fov = Radians.deg(60),
            position = Vec3(0, 7, 7),
            lookAt = Vec3.zero
        )
end SolarSystemScene
