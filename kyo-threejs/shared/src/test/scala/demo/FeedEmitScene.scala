package demo

import kyo.*

/** The click-driven scene: ONE cube whose `onClick` steps a server-driven color signal.
  *
  *   1. CLIENT raycast: a click on the cube is raycast LOCALLY (the client tests its own live scene) and
  *      posted to the server addressed by the hit node's path.
  *   2. SERVER-side resolution: the server resolves the SAME `onClick` closure the scene was built with
  *      (it was captured server-side, closing over `colorMirror`) and runs it, advancing the color to the
  *      next palette step. The ordinary bound-prop path then emits one `SetProp` back over the page's
  *      single WebSocket, patched onto the mounted cube's material.
  *
  * So a click VISIBLY changes the cube's color, proving the full client-raycast -> server-resolve ->
  * client-patch loop. The cube does not spin: the color step is the only motion, so a test can assert a
  * color change caused by the click rather than confounding it with animation. In a client-local mount
  * (`Three.runMount`) the SAME `onClick` closure runs locally, stepping the color exactly the same way.
  */
object FeedEmitScene:

    /** The fixed palette (packed `0xRRGGBB`) each click steps `colorMirror` through: red, green, blue,
      * yellow, magenta. The cube starts red; each click advances one step.
      */
    val palette: Seq[Int] = Seq(0xff0000, 0x00ff00, 0x0000ff, 0xffff00, 0xff00ff)

    /** Builds the scene and returns it alongside the color `SignalRef[Int]` the cube's `onClick` advances.
      * The cube's material color binds to `colorMirror` mapped into a `Three.Color`; each click steps the
      * signal to the next palette entry, wrapping at the end. The signal starts at the palette's first
      * value so the cube renders red before any click.
      */
    def sceneWithMirror(using Frame): (Three.Ast.Scene, SignalRef[Int]) < Sync =
        for
            colorMirror <- Signal.initRef(palette.head)
            cube = Three.mesh(
                Three.Geometry.box(2.0, 2.0, 2.0),
                Three.Material.standard(color = Three.Color.red, roughness = Three.Normal(0.5))
                    .color(colorMirror.map(rgb => Three.Color(rgb)))
            ).onClick { _ =>
                colorMirror.updateAndGet(advance).unit
            }
        yield (
            Three.scene(
                Three.Light.ambient(intensity = 1.0),
                Three.Light.directional(position = Three.Vec3(4, 6, 8)),
                cube
            ),
            colorMirror
        )
        end for
    end sceneWithMirror

    /** Advances `current` to the next palette step, wrapping at the end. */
    private def advance(current: Int): Int =
        val idx  = palette.indexOf(current)
        val next = if idx < 0 then 0 else (idx + 1) % palette.size
        palette(next)
    end advance

    /** The viewing camera, pulled back to frame the cube so a raycast click lands on it. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(
            position = Three.Vec3(0, 0, 6),
            lookAt = Three.Vec3(0, 0, 0)
        )

end FeedEmitScene
