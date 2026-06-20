package demo

import kyo.*

/** The scene-graph builder for the bouncing-balls demo: many meshes with per-`onFrame` physics, the
  * frame-loop and many-object showcase. Each ball carries its own `SignalRef[(Vec3, Vec3)]` holding
  * position and velocity, and an `onFrame` closure integrating gravity and bouncing off the floor. All
  * 24 closures drain on one scoped fiber per tick, keeping the hot path off heavy effect machinery.
  *
  * Demonstrates per-ball reactive state, signal position binding, and the many-object `onFrame`
  * pattern. `CanEqual` for the `(Vec3, Vec3)` pair derives automatically from `Vec3 derives CanEqual`.
  * The client mount in `demoharness.DemoMounts` runs this scene through `Three.runMount` in the browser.
  */
object BouncingBallsScene:

    /** 24 spheres each carrying a `SignalRef[(Vec3, Vec3)]` (position, velocity) and an `onFrame`
      * closure integrating gravity and floor bounce.
      */
    def scene(using Frame): Three.Ast.Scene < Sync =
        val count = 24
        for
            refs <- Kyo.foreach(Chunk.from(0 until count)) { i =>
                val pos = Vec3(i % 6 - 2.5, 4.0 + i * 0.3, i / 6 - 1.5)
                val vel = Vec3(0, 0, 0)
                Signal.initRef((pos, vel))
            }
            balls = refs.map { stateRef =>
                Three.mesh(
                    Three.Geometry.sphere(0.4),
                    Three.Material.standard(color = Color.cyan)
                ).position(stateRef.map(_._1))
                    .onFrame(t => stateRef.updateAndGet(s => bounce(s._1, s._2, t.delta.toMillis * 0.001)))
            }
        yield Three.scene(
            Chunk(
                Three.Light.ambient(): Three,
                Three.Light.directional(position = Vec3(5, 10, 5)): Three
            ).concat(balls.map(b => b: Three))*
        )
        end for
    end scene

    /** The viewing camera, pulled back to frame the falling field, aimed at mid-height center. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(
            position = Vec3(0, 4, 14),
            lookAt = Vec3(0, 2, 0)
        )

    private val gravity     = -9.8
    private val floor       = 0.4
    private val restitution = 0.75

    private def bounce(pos: Vec3, vel: Vec3, dt: Double): (Vec3, Vec3) =
        val vy1 = vel.y + gravity * dt
        val ny  = pos.y + vy1 * dt
        if ny < floor then
            (Vec3(pos.x, floor, pos.z), Vec3(vel.x, -vy1 * restitution, vel.z))
        else
            (Vec3(pos.x, ny, pos.z), Vec3(vel.x, vy1, vel.z))
        end if
    end bounce
end BouncingBallsScene
