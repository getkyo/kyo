package demo

import kyo.*

/** The server-driven scene (kept minimal): ONE
  * three.js scene that simultaneously shows client animation and server-driven reactivity on the SAME
  * cube.
  *
  *   1. CLIENT-side animation: a `SignalRef[Double]` spin angle that the cube's `onFrame` advances every
  *      RAF tick, bound to the cube `Group`'s rotation. The motion is continuous and driven entirely by
  *      the client frame loop; the server never touches it.
  *   2. SERVER-driven reactivity: the cube material's color binds to `colorMirror`, a `SignalRef[Int]`.
  *      A server launcher owns and advances the signal on its own ~1s schedule; the ordinary bound-prop
  *      path (the same `PropRegion` walk `ServerBridgeScene` exercises) emits one `SetProp` per step over
  *      the page's single WebSocket, patched straight onto the mounted cube's material, so the color
  *      changes in DISCRETE steps visually distinguishable from the smooth spin. In a client-local mount
  *      (`Three.runMount`) the signal is simply never driven, so the cube stays at its initial color.
  *
  * The smooth spin proves the local loop runs; the discrete color steps prove server-driven reactivity
  * reaches the scene. Combining client animation with server-driven reactivity on one cube is the
  * capability the scene demonstrates.
  */
object FeedClockScene:

    /** The fixed server palette (packed `0xRRGGBB` ints) a server launcher cycles `colorMirror` through,
      * one step per ~1s. Saturated primaries so each step is unmistakable in a screencast: red, green,
      * blue, yellow, magenta. The first value (red) is also the initial signal value, so the cube starts
      * red before the first step.
      */
    val palette: Seq[Int] = Seq(0xff0000, 0x00ff00, 0x0000ff, 0xffff00, 0xff00ff)

    /** Builds the scene and returns it alongside the color `SignalRef[Int]` a server launcher drives (or a
      * client hydrate leaves untouched). The cube spins via `onFrame` on its `Group`; its material color
      * binds to `colorMirror` mapped into a `Three.Color`. The signal starts at the palette's first value
      * so the cube renders red before any drive.
      */
    def sceneWithMirror(using Frame): (Three.Ast.Scene, SignalRef[Int]) < Sync =
        for
            spin        <- Signal.initRef(0.0)
            colorMirror <- Signal.initRef(palette.head)
            cube = Three.mesh(
                Three.Geometry.box(2.0, 2.0, 2.0),
                // A lit standard material so the rotating faces catch the directional light and the
                // shading shifts frame to frame (proving the spin); the color binds to the server-driven
                // signal so the whole cube steps through the palette on the server's schedule.
                Three.Material.standard(color = Three.Color.red, roughness = Three.Normal(0.5)).color(colorMirror.map(rgb =>
                    Three.Color(rgb)
                ))
            )
            spinning = Three.group(cube)
                .rotation(spin.map(a => Three.Vec3(a * 0.6, a, 0.0)))
                .onFrame(t => spin.updateAndGet(_ + t.delta.toMillis * 0.0015))
        yield (
            Three.scene(
                Three.Light.ambient(intensity = 1.0),
                Three.Light.directional(position = Three.Vec3(4, 6, 8)),
                spinning
            ),
            colorMirror
        )
        end for
    end sceneWithMirror

    /** The viewing camera, pulled back to frame the spinning cube. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(
            position = Three.Vec3(0, 0, 6),
            lookAt = Three.Vec3(0, 0, 0)
        )

end FeedClockScene
