package demo

import kyo.*

/** The scene-graph builder for a solar-system demo: nested groups orbiting on a `Raf`-driven
  * `onFrame`, with raycast-to-select and a shared `SignalRef[String]` tracking the selected body.
  * Demonstrates groups, the frame loop, reactive transforms, and signal-driven interaction.
  *
  * The orbit angle advances via `onFrame` on the earth `Group` (groups are `Animated` and carry their
  * own frame hook). The earth group's `rotation` binds to the same `SignalRef[Double]` via
  * `.rotation(signal)`, so the transform updates reactively on each emission. Clicking sun or earth
  * writes the selected body name into the returned `SignalRef[String]`, which a HUD can bind to. The
  * client mount in `demoharness.DemoMounts` runs this scene through `Three.runMount` in the browser.
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
                Three.Material.standard().emissive(Three.Color.yellow)
            ).onClick(_ => selected.set("Sun"))
            earth = Three.group(
                Three.mesh(
                    Three.Geometry.sphere(0.3),
                    Three.Material.standard(color = Three.Color.blue)
                ).position(Three.Vec3(4, 0, 0))
                    .onClick(_ => selected.set("Earth"))
            ).rotation(earthAngle.map(a => Three.Vec3(0, a, 0)))
                .onFrame(t => earthAngle.updateAndGet(_ + t.delta.toMillis * 0.001))
        yield (
            Three.scene(
                Three.Light.ambient(intensity = 0.3),
                Three.Light.point(position = Three.Vec3.zero),
                sun,
                earth
            ),
            selected
        )

    /** The viewing camera, positioned above the orbital plane, aimed at the sun. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(
            fov = Three.Radians.deg(60),
            position = Three.Vec3(0, 7, 7),
            lookAt = Three.Vec3.zero
        )
end SolarSystemScene
