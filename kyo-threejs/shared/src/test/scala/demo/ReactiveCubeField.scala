package demo

import kyo.*

/** The scene-graph builder for the reactive-cube-field demo: a grid of cubes whose colors and heights
  * bind to signals, the fine-grained reactivity showcase. A single phase signal drives a wave across
  * the field; each cube's height and color is a targeted mutation, never a scene rebuild.
  *
  * Demonstrates `Signal.map` composing derived reactive props, signal binding on both color and scale,
  * and the `onFrame` advance pattern on a `Group` scene child. `Group` implements `Animated` so a
  * `Three.group().onFrame(...)` serves as the phase ticker with no scene geometry. The client mount in
  * `demoharness.DemoMounts` runs this scene through `Three.runMount` in the browser.
  */
object ReactiveCubeFieldScene:

    /** An 8x8 grid of cubes whose color and height bind to a single phase `SignalRef`, advanced by
      * a geometry-free `Group.onFrame` ticker.
      */
    def scene(using Frame): Three.Ast.Scene < Sync =
        val n = 8
        for
            phase <- Signal.initRef(0.0)
            cubes = Chunk.from(
                for
                    x <- 0 until n
                    z <- 0 until n
                yield
                    val height = phase.map(p => 1.0 + math.sin(p + x * 0.5 + z * 0.5))
                    val color  = phase.map(p => Color.hsl((p * 40 + x * 20 + z * 20) % 360, 0.7, 0.5))
                    Three.mesh(
                        Three.Geometry.box(0.8, 1.0, 0.8),
                        Three.Material.standard().color(color)
                    ).position(Vec3(x - n / 2.0, 0, z - n / 2.0))
                        .scale(height.map(h => Vec3(1, h, 1)))
            )
            waver = Three.group().onFrame(t => phase.updateAndGet(_ + t.delta.toMillis * 0.002))
        yield Three.scene(
            Chunk(
                Three.Light.ambient(intensity = 0.4): Three,
                Three.Light.directional(position = Vec3(5, 10, 5)): Three,
                waver: Three
            ).concat(cubes.map(c => c: Three))*
        )
        end for
    end scene

    /** The viewing camera, raised to look down the wave field, aimed at the grid center at mid-height. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(
            fov = Radians.deg(70),
            position = Vec3(0, 5, 8),
            lookAt = Vec3(0, 1, 0)
        )
end ReactiveCubeFieldScene
